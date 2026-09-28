package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageFeedback
import com.honerai.app.data.MessageRole
import com.honerai.app.data.WebSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/** Хранилище чатов целиком: главный поток — отдельный поток теста, нейросеть и поиск подменены. */
class ChatStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var main: ExecutorCoroutineDispatcher

    @Before fun setUp() {
        main = Executors.newSingleThreadExecutor { Runnable -> Thread(Runnable, "test-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    private fun onMain(block: suspend CoroutineScope.() -> Unit) = runBlocking(main) { block() }

    private fun store(client: DeepSeekStreaming? = null, search: WebSearching? = null, file: File? = null, apiKey: String = "test", async: Boolean = false) =
        ChatStore.forTesting(folder.root, client, search, apiKey, file ?: File(folder.root, "h-${System.nanoTime()}.json"), async)

    private suspend fun waitUntil(message: String = "Условие не выполнилось", condition: () -> Boolean) {
        repeat(600) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError(message)
    }

    private suspend fun idle(store: ChatStore) = waitUntil("Генерация не закончилась") { !store.isGenerating.value }

    private fun say(store: ChatStore, text: String) {
        store.draft.value = text
        store.send()
    }

    // ---- Подменные клиенты ----

    private open class ScriptedClient(val script: (round: Int, messages: List<ChatMessage>) -> List<DeepSeekDelta>) : DeepSeekStreaming {
        val requests = mutableListOf<List<ChatMessage>>()
        val instructions = mutableListOf<String>()
        val contexts = mutableListOf<String>()
        override fun stream(messages: List<ChatMessage>, thinking: Boolean, systemInstruction: String, searchContext: String,
                            tools: List<JsonObject>?, forceAnswer: Boolean): Flow<DeepSeekDelta> = flow {
            requests.add(messages)
            instructions.add(systemInstruction)
            contexts.add(searchContext)
            script(requests.size, messages).forEach { emit(it) }
        }
    }

    private class Immediate(vararg events: DeepSeekDelta) : ScriptedClient({ _, _ -> events.toList() })

    private class ControlledClient : DeepSeekStreaming {
        val channels = mutableListOf<Channel<DeepSeekDelta>>()
        override fun stream(messages: List<ChatMessage>, thinking: Boolean, systemInstruction: String, searchContext: String,
                            tools: List<JsonObject>?, forceAnswer: Boolean): Flow<DeepSeekDelta> = flow {
            val channel = Channel<DeepSeekDelta>(Channel.UNLIMITED)
            channels.add(channel)
            for (delta in channel) emit(delta)
        }
    }

    private class NormalizingFixture : ScriptedClient({ _, _ -> listOf(
        DeepSeekDelta(reasoning = "We need to explain how ice changes from solid to liquid."),
        DeepSeekDelta(content = "Ice melts when it receives enough heat energy."),
        DeepSeekDelta(finishReason = "stop"),
    ) }), RussianTextNormalizing {
        val kinds = mutableListOf<Boolean>()
        override suspend fun normalizeRussian(text: String, reasoning: Boolean): String {
            kinds.add(reasoning)
            return if (reasoning) "Нужно объяснить переход льда в жидкое состояние." else "Лёд тает, когда получает тепло."
        }
    }

    private class CountingSearch(val fail: Boolean = false) : WebSearching {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): List<WebSource> {
            queries.add(query)
            if (fail) throw HonorError.SearchUnavailable()
            return listOf(WebSource(title = "Клин", url = "https://api.open-meteo.com/v1/forecast", snippet = "14 °C", content = "Клин: 14 °C", fetchedAt = Instant.now()))
        }
    }

    // ---- Тесты ----

    @Test fun streamPersistsContentReasoningAndFeedback() = onMain {
        val file = File(folder.root, "history.json")
        val store = store(Immediate(DeepSeekDelta(reasoning = "Анализ вопроса"), DeepSeekDelta(content = "Ответ готов."), DeepSeekDelta(finishReason = "stop")), file = file)
        say(store, "Вопрос")
        idle(store)
        val answer = store.messages.value.last()
        assertEquals("Ответ готов.", answer.content)
        assertEquals("Анализ вопроса", answer.reasoning)
        assertTrue(answer.reasoningSeconds >= 1)
        assertNull(store.typingMessageId.value)
        assertEquals("Вопрос", store.selectedConversation()!!.title)
        assertEquals(1, store.statistics.value.sentMessages)
        assertEquals(1, store.statistics.value.receivedMessages)
        store.setFeedback(answer.id, MessageFeedback.LIKE)
        store.persistNow()
        val restored = store(file = file)
        assertEquals(MessageFeedback.LIKE, restored.messages.value.last().feedback)
        assertEquals("Ответ готов.", restored.messages.value.last().content)
    }

    @Test fun missingKeyPreservesDraftWithoutCreatingConversation() = onMain {
        val store = store(apiKey = "")
        say(store, "Не потеряй мой текст")
        assertEquals("Не потеряй мой текст", store.draft.value)
        assertTrue(store.conversations.value.isEmpty())
        assertFalse(store.isGenerating.value)
        assertNotNull(store.errorMessage.value)
    }

    @Test fun editIsNonDestructiveUntilSendAndReplacesFollowingTurns() = onMain {
        val store = store(Immediate(DeepSeekDelta(content = "Новый ответ"), DeepSeekDelta(finishReason = "stop")))
        val original = ChatMessage(role = MessageRole.USER, content = "Первый вопрос")
        val chat = Conversation(title = "Тест", messages = listOf(original, ChatMessage(role = MessageRole.ASSISTANT, content = "Первый ответ"),
            ChatMessage(role = MessageRole.USER, content = "Второй вопрос")))
        store.replaceConversations(listOf(chat), chat.id)
        store.edit(original.id)
        assertEquals(3, store.messages.value.size)
        store.cancelEditing()
        assertEquals(3, store.messages.value.size)
        store.edit(original.id)
        store.draft.value = "Исправленный вопрос"
        store.send()
        idle(store)
        assertEquals(listOf("Исправленный вопрос", "Новый ответ"), store.messages.value.map { it.content })
    }

    @Test fun stopAndNewChatCannotReceiveOldStreamTokens() = onMain {
        val client = ControlledClient()
        val store = store(client)
        say(store, "Первый чат")
        waitUntil { client.channels.size == 1 }
        val firstId = store.selectedConversationId.value!!
        client.channels[0].send(DeepSeekDelta(content = "Начало ответа."))
        waitUntil { store.liveContent.contains("Начало") }
        // Во время печати модель чата не трогаем: текст идёт в живой буфер.
        assertTrue(store.messages.value.last().content.isEmpty())
        store.newChat()
        client.channels[0].send(DeepSeekDelta(content = "Устаревший токен"))
        client.channels[0].close()
        delay(50)
        assertNull(store.selectedConversationId.value)
        assertFalse(store.isGenerating.value)
        assertEquals("", store.liveContent)
        val old = store.conversations.value.first { it.id == firstId }.messages.last()
        assertTrue(old.isInterrupted)
        assertEquals("Начало ответа.", old.content)
    }

    @Test fun stopFlushesBufferedTokensAndDeletedConversationCannotReturn() = onMain {
        val file = File(folder.root, "stop.json")
        val client = ControlledClient()
        val store = store(client, file = file)
        say(store, "Delete this conversation")
        waitUntil { client.channels.size == 1 }
        val firstId = store.selectedConversationId.value!!
        client.channels[0].send(DeepSeekDelta(content = "Первая часть. "))
        client.channels[0].send(DeepSeekDelta(content = "вторая часть."))
        delay(30)
        store.stop()
        assertEquals("Первая часть. вторая часть.", store.messages.value.last().content)
        store.deleteChats(setOf(firstId))
        client.channels[0].send(DeepSeekDelta(content = "STALE"))
        client.channels[0].close()
        say(store, "Keep this conversation")
        waitUntil { client.channels.size == 2 }
        client.channels[1].send(DeepSeekDelta(content = "Свежий ответ."))
        client.channels[1].close()
        idle(store)
        store.persistNow()
        val restored = store(file = file)
        assertEquals(1, restored.conversations.value.size)
        assertFalse(restored.conversations.value.any { it.id == firstId })
        assertEquals("Свежий ответ.", restored.messages.value.last().content)
    }

    @Test fun toolChainRunsListThenReadAndPrintsTheFinalAnswer() = onMain {
        val client = ScriptedClient { round, _ ->
            when (round) {
                1 -> listOf(DeepSeekDelta(toolCalls = listOf(ToolCallRequest("call_list", "list_chats", "", 0))),
                    DeepSeekDelta(toolCalls = listOf(ToolCallRequest("", "", "{}", 0))), DeepSeekDelta(finishReason = "tool_calls"))
                2 -> listOf(DeepSeekDelta(toolCalls = listOf(ToolCallRequest("call_read", "read_chat", "{\"number\": \"1\"}", 0))),
                    DeepSeekDelta(finishReason = "tool_calls"))
                else -> listOf(DeepSeekDelta(content = "Вы отдыхали в Сочи "), DeepSeekDelta(content = "12 дней."), DeepSeekDelta(finishReason = "stop"))
            }
        }
        val store = store(client)
        val vacation = Conversation(title = "Отпуск", pinned = true, messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Мы отдыхали в Сочи 12 дней."),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Запомнил: 12 дней в Сочи.")))
        store.replaceConversations(listOf(vacation), null)
        say(store, "Сколько дней мы отдыхали? Посмотри в моём чате про отпуск.")
        idle(store)
        assertEquals("Ожидались три прохода: список, чтение, ответ", 3, client.requests.size)
        val second = client.requests[1]
        assertTrue(second.any { it.role == MessageRole.ASSISTANT && it.toolCallsRaw.contains("list_chats") })
        assertTrue(second.any { it.role == MessageRole.TOOL && it.toolCallID == "call_list" })
        assertTrue(client.requests.last().any { it.role == MessageRole.TOOL && it.content.contains("12 дней") })
        val answer = store.messages.value.last()
        assertEquals("Вы отдыхали в Сочи 12 дней.", answer.content)
        assertNull(answer.error)
        assertEquals(listOf("Смотрю список чатов", "Читаю чат"), answer.activity!!.map { it.title })
        assertTrue(answer.activity!!.all { it.done })
        // Переписка чата про отпуск приложена к инструкции: вопрос про чаты.
        assertTrue(client.instructions.first().contains("Переписка других чатов пользователя"))
    }

    @Test fun repeatedToolCallIsNotExecutedTwiceAndFinalPassAnswers() = onMain {
        val client = ScriptedClient { round, _ ->
            if (round <= 2) listOf(DeepSeekDelta(toolCalls = listOf(ToolCallRequest("call_$round", "save_memory", "{\"text\":\"Живу в Казани\"}", 0))))
            else listOf(DeepSeekDelta(content = "Запомнил, что вы живёте в Казани."), DeepSeekDelta(finishReason = "stop"))
        }
        val store = store(client)
        say(store, "Я живу в Казани")
        idle(store)
        assertEquals(1, store.memories.value.size)
        assertEquals(3, client.requests.size)
        assertEquals("Запомнил, что вы живёте в Казани.", store.messages.value.last().content)
    }

    @Test fun tableToolAttachesTableToChatAndAnswer() = onMain {
        val client = ScriptedClient { round, _ ->
            if (round == 1) listOf(DeepSeekDelta(toolCalls = listOf(ToolCallRequest("t1", "create_table",
                "{\"title\":\"Покупки\",\"columns\":[\"Товар\",\"Цена\"],\"rows\":[[\"Хлеб\",\"50\"]]}", 0))))
            else listOf(DeepSeekDelta(content = "Создал таблицу покупок."), DeepSeekDelta(finishReason = "stop"))
        }
        val store = store(client)
        say(store, "Сделай таблицу покупок")
        idle(store)
        val chat = store.selectedConversation()!!
        val table = chat.tables!!.single()
        assertEquals(listOf(table.id), store.messages.value.last().tableIDs)
        store.saveTable(table.copy(rows = table.rows + listOf(listOf("Молоко", "90"))))
        assertEquals(true, store.table(table.id)!!.editedByUser)
        val prompt = store.systemInstruction(chat.id, "что в таблице")
        assertTrue(prompt.contains("## Таблицы этого чата"))
        assertTrue(prompt.contains("Молоко"))
        store.deleteTable(table.id)
        assertNull(store.table(table.id))
        assertTrue(store.messages.value.last().tableIDs!!.isEmpty())
    }

    @Test fun emptyAnswerNeverLeavesTheChatSilent() = onMain {
        val store = store(Immediate(DeepSeekDelta(finishReason = "stop")))
        store.setReasoningEnabled(true)
        say(store, "Ку")
        idle(store)
        val answer = store.messages.value.last()
        assertEquals(MessageRole.ASSISTANT, answer.role)
        assertTrue("Пустой ответ обязан показать сообщение", answer.content.length > 3 || !answer.error.isNullOrEmpty())
    }

    @Test fun announcementIsRecoveredWithARealAnswer() = onMain {
        val client = ScriptedClient { round, _ ->
            if (round == 1) listOf(DeepSeekDelta(content = "Сейчас найду нужный чат."), DeepSeekDelta(finishReason = "stop"))
            else listOf(DeepSeekDelta(content = "В чате про отпуск сказано: 12 дней."), DeepSeekDelta(finishReason = "stop"))
        }
        val store = store(client)
        say(store, "Сколько дней?")
        idle(store)
        assertEquals("В чате про отпуск сказано: 12 дней.", store.messages.value.last().content)
    }

    @Test fun reactionLineBecomesABadgeOnTheUserMessage() = onMain {
        val store = store(Immediate(DeepSeekDelta(content = "РЕАКЦИЯ: 🔥\n"), DeepSeekDelta(content = "Поздравляю с победой!"), DeepSeekDelta(finishReason = "stop")))
        say(store, "Я выиграл турнир!")
        idle(store)
        val messages = store.messages.value
        assertEquals("🔥", messages.first().assistantReaction)
        assertEquals("Поздравляю с победой!", messages.last().content)
    }

    @Test fun profileIsIncludedAsDataAndForeignReasoningIsNormalized() = onMain {
        val client = NormalizingFixture()
        val store = store(client)
        store.profileName = "Алексей"
        say(store, "Explain ice melting")
        idle(store)
        assertTrue(client.instructions.first().contains("Алексей"))
        val answer = store.messages.value.last()
        assertEquals("Лёд тает, когда получает тепло.", answer.content)
        assertEquals("Нужно объяснить переход льда в жидкое состояние.", answer.reasoning)
        assertEquals(true, answer.reasoningWasTranslated)
        assertEquals(listOf(false, true), client.kinds)
        // В английском режиме перевод не нужен.
        store.setResponseLanguage("en")
        assertTrue(store.respondsInEnglish)
        say(store, "Explain again")
        idle(store)
        assertEquals("Ice melts when it receives enough heat energy.", store.messages.value.last().content)
    }

    @Test fun explicitWeatherQueryFetchesEvenWhenSearchToggleIsOff() = onMain {
        val search = CountingSearch()
        val client = Immediate(DeepSeekDelta(content = "Готово, вот ответ."), DeepSeekDelta(finishReason = "stop"))
        val store = store(client, search)
        store.setSearchEnabled(false)
        say(store, "Какая погода в Клину сегодня?")
        idle(store)
        assertEquals(1, search.queries.size)
        assertEquals(1, store.messages.value.last().sources.size)
        assertTrue(client.contexts.last().contains("Клин: 14 °C"))
        assertEquals("Ищу в интернете", store.messages.value.last().activity!!.first().title)
        say(store, "Что такое лёд?")
        idle(store)
        assertEquals(1, search.queries.size)
        say(store, "Прочитай https://example.com/ice и объясни вывод.")
        idle(store)
        assertEquals(2, search.queries.size)
        // Кнопка «Поиск» не заставляет искать заранее.
        store.setSearchEnabled(true)
        say(store, "Сколько будет 17 умножить на 23?")
        idle(store)
        assertEquals(2, search.queries.size)
    }

    @Test fun searchFailureDoesNotGenerateFabricatedAnswer() = onMain {
        val store = store(Immediate(DeepSeekDelta(content = "Отвечаю по своим знаниям.")), CountingSearch(fail = true))
        say(store, "Найди последние новости")
        idle(store)
        val answer = store.messages.value.last()
        assertEquals("Отвечаю по своим знаниям.", answer.content)
        assertNull(answer.error)
        assertTrue(answer.sources.isEmpty())
        assertTrue(answer.searchFailed)
    }

    @Test fun memoryToggleControlsRealRequestInstructions() = onMain {
        val client = Immediate(DeepSeekDelta(content = "Мне нравится зелёный цвет."), DeepSeekDelta(finishReason = "stop"))
        val store = store(client)
        store.systemInstruction = "Будь вежлив"
        assertTrue(store.addMemory("Мой любимый цвет — синий"))
        say(store, "Первый вопрос")
        idle(store)
        assertTrue(client.instructions[0].contains("Мой любимый цвет — синий"))
        assertTrue(client.instructions[0].contains("Будь вежлив"))
        store.setMemoryEnabled(false)
        say(store, "Второй вопрос")
        idle(store)
        assertFalse(client.instructions[1].contains("Мой любимый цвет"))
        assertEquals(1, store.memories.value.size)
    }

    @Test fun explicitMemoryIsValidatedEditableAndPersistent() = onMain {
        val file = File(folder.root, "memory.json")
        val store = store(file = file)
        assertFalse(store.addMemory(" \n"))
        assertFalse(store.addMemory("x".repeat(ChatLogic.MAXIMUM_MEMORY_LENGTH + 1)))
        assertTrue(store.addMemory("  Обращайся ко мне на ты  "))
        assertFalse(store.addMemory("обращайся ко мне на ты"))
        val id = store.memories.value.first().id
        assertTrue(store.updateMemory(id, "Отвечай кратко"))
        store.setMemoryEnabled(false)
        store.clearAllChats()
        store.persistNow()
        val restored = store(file = file)
        assertEquals(listOf("Отвечай кратко"), restored.memories.value.map { it.text })
        assertFalse(restored.memoryEnabled.value)
        restored.deleteMemory(id)
        assertTrue(restored.memories.value.isEmpty())
    }

    @Test fun archiveRestoreAndReloadNeverSelectArchivedConversation() = onMain {
        val file = File(folder.root, "archive.json")
        val store = store(file = file)
        val chat = Conversation(title = "Archive", messages = listOf(ChatMessage(role = MessageRole.USER, content = "Question")))
        store.replaceConversations(listOf(chat), chat.id)
        store.archiveChat(chat.id)
        assertNull(store.selectedConversationId.value)
        assertEquals(listOf(chat.id), store.archivedConversations().map { it.id })
        store.selectChat(chat.id)
        assertNull(store.selectedConversationId.value)
        store.persistNow()
        val reloaded = store(file = file)
        assertEquals(1, reloaded.archivedConversations().size)
        val exported = reloaded.exportData().getOrThrow()
        val imported = store()
        imported.importBytes(exported.readBytes())
        assertNotNull(imported.archivedConversations().first().archivedAt)
        imported.restoreChat(chat.id)
        imported.selectChat(chat.id)
        assertEquals(chat.id, imported.selectedConversationId.value)
        assertTrue(imported.archivedConversations().isEmpty())
    }

    @Test fun asyncHistoryLoadBlocksSendingUntilLoaded() = onMain {
        val file = File(folder.root, "async.json")
        val first = store(file = file)
        first.replaceConversations(listOf(Conversation(title = "Loaded in background")), null)
        first.persistNow()
        val loaded = store(file = file, async = true)
        assertTrue(loaded.isLoadingHistory.value)
        loaded.draft.value = "Рано"
        assertFalse(loaded.canSend)
        waitUntil { !loaded.isLoadingHistory.value }
        assertEquals("Loaded in background", loaded.conversations.value.first().title)
    }

    @Test fun forkPreservesSourceAndCopiesOnlyThroughSelectedMessage() = onMain {
        val attachment = MessageAttachment(name = "note.txt", kind = AttachmentKind.TEXT, extractedText = "Source")
        val source = Conversation(title = "Original", pinned = true, messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Question", attachments = listOf(attachment)),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Answer", sources = listOf(WebSource(title = "Source", url = "https://example.com", snippet = "Text"))),
            ChatMessage(role = MessageRole.USER, content = "Later")))
        val store = store()
        store.replaceConversations(listOf(source), source.id)
        val branchId = store.forkConversation(source.messages[1].id)!!
        assertEquals(source, store.conversations.value.first { it.id == source.id })
        val branch = store.selectedConversation()!!
        assertEquals(branchId, branch.id)
        assertEquals(listOf("Question", "Answer"), branch.messages.map { it.content })
        assertTrue(branch.messages.map { it.id }.intersect(source.messages.map { it.id }.toSet()).isEmpty())
        assertFalse(branch.messages[0].attachments[0].id == attachment.id)
        assertEquals(source.id, branch.parentConversationID)
        assertEquals(source.messages[1].id, branch.forkedAtMessageID)
        assertFalse(branch.pinned)
        assertTrue(branch.title.startsWith("Ветка · "))
    }

    @Test fun dragPinsReordersAndUnpinsChats() = onMain {
        val store = store()
        val a = Conversation(title = "A", pinned = true, pinOrder = 0)
        val b = Conversation(title = "B", pinned = true, pinOrder = 1)
        val c = Conversation(title = "C")
        val d = Conversation(title = "D")
        store.replaceConversations(listOf(a, b, c, d), null)
        assertTrue(store.moveChat(c.id, a.id))
        assertEquals(listOf("C", "A", "B"), store.sortedConversations().take(3).map { it.title })
        assertTrue(store.moveChat(a.id, d.id))
        assertEquals(false, store.conversations.value.first { it.id == a.id }.pinned)
        assertFalse(store.moveChat(d.id, a.id))
        store.movePinned(b.id, -1)
        assertEquals(listOf("B", "C"), store.sortedConversations().take(2).map { it.title })
    }

    @Test fun sharedAttachmentFilesAreDeletedOnlyWithTheLastReference() = onMain {
        val store = store()
        val directory = File(folder.root, "attachments")
        val files = (0 until 3).map { File(directory, "photo-$it.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        val items = files.map { MessageAttachment(name = "photo.jpg", kind = AttachmentKind.IMAGE, localPath = it.path) }
        store.addAttachment(items[0])
        store.removeAttachment(items[0].id)
        assertFalse(files[0].exists())
        store.addAttachment(items[1])
        store.newChat()
        assertFalse(files[1].exists())
        val message = ChatMessage(role = MessageRole.USER, content = "Keep shared photo", attachments = listOf(items[2]))
        val source = Conversation(messages = listOf(message))
        store.replaceConversations(listOf(source), source.id)
        val branchId = store.forkConversation(message.id)!!
        store.deleteChats(setOf(source.id))
        assertTrue(files[2].exists())
        store.deleteChats(setOf(branchId))
        assertFalse(files[2].exists())
    }

    @Test fun quotePinnedInstructionsAndLibrary() = onMain {
        val store = store()
        store.quote("  Фотосинтез идёт в хлоропластах.  ")
        assertEquals("Фотосинтез идёт в хлоропластах.", store.quotedFragment.value)
        val answer = ChatMessage(role = MessageRole.ASSISTANT, content = "Отвечай списком")
        val chat = Conversation(messages = listOf(ChatMessage(role = MessageRole.USER, content = "Привет"), answer))
        store.replaceConversations(listOf(chat), chat.id)
        assertTrue(store.pinInstruction(answer.id))
        assertFalse("Дубль не закрепляется", store.pinInstruction(answer.id))
        val instruction = store.selectedConversation()!!.instructions!!.single()
        assertEquals(MessageRole.ASSISTANT, instruction.author)
        assertTrue(store.systemInstruction(chat.id, "").contains("## Закреплённые инструкции этого чата"))
        store.unpinInstruction(chat.id, instruction.id)
        assertEquals("Отвечай списком", store.instructionLibrary.value.single().text)
        assertTrue(store.applySavedInstruction(store.instructionLibrary.value.single().id, chat.id))
        assertEquals(1, store.selectedConversation()!!.instructions!!.size)
    }

    @Test fun purgeOldChatsKeepsPinnedAndFreshOnes() = onMain {
        val store = store()
        val old = Instant.now().minusSeconds(40L * 86_400)
        val stale = Conversation(title = "Старый", updatedAt = old, messages = listOf(ChatMessage(role = MessageRole.USER, content = "a", createdAt = old)))
        val pinned = stale.copy(id = com.honerai.app.data.newId(), title = "Закреплён", pinned = true)
        val fresh = Conversation(title = "Свежий")
        store.replaceConversations(listOf(stale, pinned, fresh), stale.id)
        store.purgeOldChats(30)
        assertEquals(listOf("Закреплён", "Свежий"), store.conversations.value.map { it.title })
        assertNotNull(store.selectedConversationId.value)
    }

    @Test fun regenerateAfterErrorRetriesFromTheQuestion() = onMain {
        val client = Immediate(DeepSeekDelta(content = "Повторный ответ."), DeepSeekDelta(finishReason = "stop"))
        val store = store(client)
        val question = ChatMessage(role = MessageRole.USER, content = "Вопрос")
        val failed = ChatMessage(role = MessageRole.ASSISTANT, error = "Сбой")
        val chat = Conversation(messages = listOf(question, failed, ChatMessage(role = MessageRole.USER, content = "Ещё")))
        store.replaceConversations(listOf(chat), chat.id)
        store.regenerate(failed.id)
        idle(store)
        assertEquals(listOf("Вопрос", "Повторный ответ."), store.messages.value.map { it.content })
        val orphan = ChatMessage(role = MessageRole.ASSISTANT, content = "Без вопроса")
        val orphanChat = Conversation(messages = listOf(orphan))
        store.replaceConversations(listOf(orphanChat), orphanChat.id)
        store.regenerate(orphan.id)
        assertTrue(store.errorMessage.value!!.contains("нельзя повторить"))
    }

    @Test fun gameRequestAndResult() = onMain {
        val client = ScriptedClient { round, _ ->
            if (round == 1) listOf(DeepSeekDelta(toolCalls = listOf(ToolCallRequest("g", "start_game", "{\"game\":\"chess\"}", 0))))
            else listOf(DeepSeekDelta(content = "Удачи в партии!"), DeepSeekDelta(finishReason = "stop"))
        }
        val store = store(client)
        say(store, "Давай сыграем в шахматы")
        idle(store)
        assertEquals("chess", store.requestedGame.value)
        store.consumeRequestedGame()
        assertNull(store.requestedGame.value)
        store.postGameResult("Партия окончена: победа белых.")
        assertEquals("Партия окончена: победа белых.", store.messages.value.last().content)
    }
}
