package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.DeepSeekConfiguration
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Разбор потока SSE, склейка вызовов инструментов и сборка запроса (как тесты iOS). */
class DeepSeekClientTest {
    @get:Rule val folder = TemporaryFolder()

    private val client = DeepSeekClient(DeepSeekConfiguration(apiKey = "test-key"))

    private fun feed(decoder: SSEDecoder, text: String): List<String> =
        text.toByteArray(Charsets.UTF_8).toList().mapNotNull { decoder.append(it) }

    @Test fun sseDecoderHandlesSplitUtf8CrlfMultilineAndComments() {
        val decoder = SSEDecoder()
        val wire = ": keepalive\r\ndata: {\"choices\":\r\ndata: [{\"delta\":{\"content\":\"Привет 🌍\"}}]}\r\n\r\ndata: [DONE]\n\n"
        val events = feed(decoder, wire)
        assertEquals(2, events.size)
        assertEquals("[DONE]", events[1])
        assertEquals("Привет 🌍", client.decodeEvent(events[0])?.content)
        assertNull(decoder.finish())
    }

    @Test fun sseDecoderFlushesLastEventAtEof() {
        val decoder = SSEDecoder()
        feed(decoder, "data: {\"choices\":[]}")
        assertEquals("{\"choices\":[]}", decoder.finish())
    }

    @Test fun sseDecoderAcceptsBomAndCarriageReturnOnlyFrames() {
        val decoder = SSEDecoder()
        val events = feed(decoder, "﻿data: first\r\rdata: second\r\rdata: [DONE]")
        assertEquals(listOf("first", "second"), events)
        assertEquals("[DONE]", decoder.finish())
    }

    @Test fun decodeEventReadsReasoningFinishAndToolCallChunks() {
        val event = """{"choices":[{"delta":{"content":null,"reasoning_content":"Думаю","tool_calls":[{"index":0,"id":"call_1","function":{"name":"list_chats","arguments":"{\"a\""}}]},"finish_reason":null}]}"""
        val delta = client.decodeEvent(event)!!
        assertEquals("", delta.content)
        assertEquals("Думаю", delta.reasoning)
        assertNull(delta.finishReason)
        assertEquals(ToolCallRequest("call_1", "list_chats", "{\"a\"", 0), delta.toolCalls.single())
        val finish = client.decodeEvent("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")!!
        assertEquals("tool_calls", finish.finishReason)
        try {
            client.decodeEvent("""{"error":{"message":"bad"}}""")
            fail("Ошибка сервиса внутри потока должна стать исключением")
        } catch (e: HonorError.Http) {
            assertTrue(e.message!!.contains("bad"))
        }
    }

    @Test fun toolCallChunksMergeByIndexThenIdThenName() {
        val calls = mutableListOf<ToolCallRequest>()
        ChatLogic.mergeToolCall(ToolCallRequest("call_list", "list_chats", "", 0), calls)
        ChatLogic.mergeToolCall(ToolCallRequest("", "", "{\"x\":", 0), calls)
        ChatLogic.mergeToolCall(ToolCallRequest("", "", "1}", 0), calls)
        ChatLogic.mergeToolCall(ToolCallRequest("call_read", "read_chat", "{\"number\":", 1), calls)
        ChatLogic.mergeToolCall(ToolCallRequest("call_read", "", "2}", null), calls)
        assertEquals(2, calls.size)
        assertEquals("{\"x\":1}", calls[0].arguments)
        assertEquals("call_list", calls[0].id)
        assertEquals("{\"number\":2}", calls[1].arguments)
        assertEquals(2, ToolArgument.int(calls[1].parsedArguments["number"]))
        val json = ChatLogic.toolCallsJSON(calls)
        assertTrue(json.contains("\"type\":\"function\""))
        assertTrue(json.contains("list_chats"))
        assertEquals("", ChatLogic.toolCallsJSON(listOf(ToolCallRequest("", "", "", null))))
    }

    private fun messages(body: JsonObject): List<JsonObject> = (body["messages"] as JsonArray).map { it.jsonObject }

    @Test fun requestUsesModelThinkingAndDoesNotReplayReasoningWithoutTools() {
        val input = listOf(ChatMessage(role = MessageRole.USER, content = "Привет"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Здравствуйте", reasoning = "private-model-reasoning"),
            ChatMessage(role = MessageRole.USER, content = "Продолжай"))
        val body = client.buildBody(input, true, "Кратко", "")
        assertEquals("deepseek-flash", body["model"].str)
        assertEquals("enabled", body["thinking"]["type"].str)
        assertEquals("high", body["reasoning_effort"].str)
        assertFalse(body.toString().contains("private-model-reasoning"))
        val request = client.makeRequest(body, stream = true)
        assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        val plain = client.buildBody(input, false, "", "")
        assertEquals("disabled", plain["thinking"]["type"].str)
        assertNull(plain["reasoning_effort"])
    }

    @Test fun toolsRequireReasoningContentToBeSentBack() {
        val input = listOf(ChatMessage(role = MessageRole.USER, content = "Вопрос"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Готовый ответ.", reasoning = "Ход мысли."))
        val withTools = messages(client.buildBody(input, true, "", "", HonerTool.apiSchemas))
        assertEquals("Ход мысли.", withTools.last()["reasoning_content"].str)
        val withoutTools = messages(client.buildBody(input, true, "", "", null))
        assertNull(withoutTools.last()["reasoning_content"])
    }

    @Test fun toolCallMessageWithoutTextIsSentAndFinalPassForbidsNewCalls() {
        val call = ChatMessage(role = MessageRole.ASSISTANT, reasoning = "Нужно посмотреть список чатов.",
            toolCallsRaw = ChatLogic.toolCallsJSON(listOf(ToolCallRequest("call_1", "list_chats", "{}"))))
        val result = ChatMessage(role = MessageRole.TOOL, content = "Чаты пользователя: 1. «Отпуск»", toolCallID = "call_1")
        val input = listOf(ChatMessage(role = MessageRole.USER, content = "Какие у меня чаты?"), call, result)
        val body = client.buildBody(input, true, "", "[1] Источник", HonerTool.apiSchemas, forceAnswer = true)
        val payload = messages(body)
        assertEquals(listOf("system", "user", "user", "assistant", "tool"), payload.map { it["role"].str })
        assertTrue(payload[2]["content"].str!!.contains("Результаты поиска"))
        assertNotNull(payload[3]["tool_calls"])
        assertEquals("Нужно посмотреть список чатов.", payload[3]["reasoning_content"].str)
        assertEquals("call_1", payload[4]["tool_call_id"].str)
        assertEquals("none", body["tool_choice"].str)
        assertEquals("auto", client.buildBody(input, false, "", "", HonerTool.apiSchemas)["tool_choice"].str)
    }

    @Test fun requestSendsImageBytesAndDocumentText() {
        val image = folder.newFile("photo.jpg")
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        image.writeBytes(bytes)
        val message = ChatMessage(role = MessageRole.USER, content = "Сравни", attachments = listOf(
            MessageAttachment(name = "photo.jpg", kind = AttachmentKind.IMAGE, localPath = image.path),
            MessageAttachment(name = "note.txt", kind = AttachmentKind.TEXT, extractedText = "Факт из файла"),
        ))
        val parts = messages(client.buildBody(listOf(message), false, "", "")).last()["content"] as JsonArray
        assertTrue(parts[0]["text"].str!!.contains("Факт из файла"))
        assertEquals("data:image/jpeg;base64,${bytes.toByteString().base64()}", parts[1]["image_url"]["url"].str)
    }

    @Test fun videoUsesFramesOnlyAndMissingFilesDoNotKillTheRequest() {
        val movie = folder.newFile("clip.mp4").apply { writeText("WHOLE_MOVIE_MUST_NOT_BE_SENT") }
        val frame = folder.newFile("frame.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val video = MessageAttachment(name = "clip.mp4", kind = AttachmentKind.VIDEO, extractedText = "Кадр на 1.0 секунде",
            localPath = movie.path, videoFramePaths = listOf(frame.path))
        val body = client.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Что видно?", attachments = listOf(video))), false, "", "").toString()
        assertTrue(body.contains("image_url"))
        assertFalse(body.contains(movie.readBytes().toByteString().base64()))
        assertTrue(body.contains("аудио не передано"))

        val missing = MessageAttachment(name = "пропавшее.jpg", kind = AttachmentKind.IMAGE, localPath = folder.root.path + "/нет-такого.jpg")
        val text = messages(client.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Что на фото?", attachments = listOf(missing))), false, "", "")).last()["content"].str!!
        assertTrue(text.contains("пропавшее.jpg"))
        assertTrue(text.contains("недоступно"))
        client.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Прочитай",
            attachments = listOf(MessageAttachment(name = "пустое.pdf", kind = AttachmentKind.DOCUMENT)))), false, "", "")
    }

    @Test fun quotedFragmentReactionsAndShortFragmentsInContext() {
        val question = ChatMessage(role = MessageRole.USER, content = "Что это значит?", quote = "Фотосинтез идёт в хлоропластах.")
        val answer = ChatMessage(role = MessageRole.ASSISTANT, content = "Это процесс в клетках.", reaction = "👍")
        val fragment = ChatMessage(role = MessageRole.ASSISTANT, content = "В")
        val payload = messages(client.buildBody(listOf(question, answer, fragment), false, "", ""))
        assertTrue(payload[1]["content"].str!!.contains("выделил в переписке фрагмент"))
        assertTrue(payload[1]["content"].str!!.contains("хлоропластах"))
        assertTrue(payload[2]["content"].str!!.contains("[Реакция пользователя на это сообщение: 👍]"))
        assertEquals("Огрызок «В» не должен попадать в контекст", 3, payload.size)
    }

    @Test fun identityIsRussianBriefRuleAndEnglishModeReplacesLanguageRule() {
        val system = messages(client.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Explain the sky in English")), true, "", ""))[0]["content"].str!!
        assertTrue(system.contains("Honer AI"))
        assertTrue(system.contains("по-русски"))
        assertFalse(system.contains("PowerShell"))
        assertTrue(system.contains("Текущие дата и время на устройстве пользователя"))
        val brief = messages(client.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Почему небо голубое?")), true,
            "Обращайся ко мне на ты, отвечай кратко по-русски.", ""))[0]["content"].str!!
        assertTrue(brief.contains("1–3 коротких предложения"))
        assertFalse(PersonalizationPolicy.prefersBriefAnswers("Отвечай подробно, не кратко."))

        assertTrue(HonerIdentity.instruction.startsWith("Ты — Honer AI"))
        assertTrue(HonerIdentity.instruction.contains("\$x^2\$ в строке"))
        assertTrue(HonerIdentity.instruction.contains("\\frac{a}{b}"))
        assertTrue(HonerIdentity.instruction.contains(HonerIdentity.russianLanguageRule))
        val english = HonerIdentity.englishInstruction
        assertFalse(english.contains(HonerIdentity.russianLanguageRule))
        assertTrue(english.contains("switched the app to English"))
        val englishClient = DeepSeekClient(DeepSeekConfiguration(apiKey = "k", language = "en"))
        val englishSystem = messages(englishClient.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Hi")), false, "", ""))[0]["content"].str!!
        assertTrue(englishSystem.contains("Mandatory app rule: the app language is English"))
    }

    @Test fun identityContextIsSelective() {
        assertTrue(HonerIdentity.context("Как приготовить суп?").isEmpty())
        val creator = HonerIdentity.context("Кто твой создатель?")
        assertTrue(creator.contains("Я Honer AI"))
        val pc = HonerIdentity.context("Что умеет настольный Honer PK Agent?")
        assertTrue(pc.contains("Honor PK Agent"))
        assertTrue(pc.contains("PowerShell"))
        assertTrue(pc.contains("Мобильное Honer AI не заявляет управление компьютером"))
        assertTrue(HonerIdentity.context("Какие у него функции?", "Пользователь спрашивал про Honer PK Agent.").contains("PowerShell"))
    }

    @Test fun missingKeyIsAnError() {
        try {
            DeepSeekClient(DeepSeekConfiguration(apiKey = "")).buildBody(emptyList(), false, "", "")
            fail("Без ключа запрос собирать нельзя")
        } catch (e: HonorError.MissingApiKey) {
            assertTrue(e.message!!.contains("ключ DeepSeek"))
        }
    }

    @Test fun combinedTranslationIsSplit() {
        assertEquals("Ответ" to "Ход мысли", DeepSeekClient.splitCombined("ОТВЕТ:\nОтвет\nРАССУЖДЕНИЕ:\nХод мысли"))
        assertEquals("Только перевод" to "", DeepSeekClient.splitCombined("ответ: Только перевод"))
        assertNull(DeepSeekClient.splitCombined("  "))
    }
}
