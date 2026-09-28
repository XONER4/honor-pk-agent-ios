package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.HistoryArchive
import com.honerai.app.data.HonorMemory
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.data.newId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** Резервная копия: совместимость с iPhone (ISO-даты, UUID заглавными, base64-файлы). */
class ArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val chatId = "6F9619FF-8B86-D011-B42D-00C04FC964FF"
    private val userId = "1B4E28BA-2FA1-11D2-883F-0016D3CCA427"
    private val answerId = "C56A4180-65AA-42EC-A945-5FD21DEC0538"
    private val photoId = "E4EAAAF2-D142-11E1-B3E4-080027620CDD"

    /** Архив в том виде, в каком его пишет JSONEncoder на iPhone. */
    private val iosSample = """
        {
          "attachmentFiles" : { "$photoId" : "/9j/2Q==" },
          "attachments" : [ ],
          "conversations" : [ {
            "createdAt" : "2026-09-27T09:59:00Z",
            "id" : "$chatId",
            "instructions" : [ { "author" : "user", "createdAt" : "2026-09-27T10:00:00Z", "id" : "0F8FAD5B-D9CB-469F-A165-70867728950E", "text" : "Пиши кратко" } ],
            "messages" : [ {
              "attachments" : [ { "extractedText" : "", "id" : "$photoId", "kind" : "image", "localPath" : "/var/mobile/Containers/Data/Application/X/Library/Application Support/HonorPKAgent/Attachments/p.jpg", "name" : "photo.jpg" } ],
              "content" : "Что на фото?", "role" : "user", "createdAt" : "2026-09-27T10:00:00Z", "id" : "$userId", "inputKind" : "voice",
              "isInterrupted" : false, "reasoning" : "", "reasoningSeconds" : 0, "searchFailed" : false, "sources" : [ ], "toolCallsRaw" : "",
              "assistantReaction" : "🔥"
            }, {
              "attachments" : [ ], "role" : "assistant", "content" : "Кот на диване.", "createdAt" : "2026-09-27T10:00:05Z", "id" : "$answerId",
              "isInterrupted" : false, "reasoning" : "Смотрю", "reasoningSeconds" : 2, "searchFailed" : false,
              "sources" : [ { "id" : "7C9E6679-7425-40DE-944B-E07FC1F90AE7", "snippet" : "s", "title" : "t", "url" : "https://example.com/a", "fetchedAt" : "2026-09-27T10:00:03Z" } ],
              "toolCallsRaw" : "", "feedback" : "like", "inputKind" : "text", "futureField" : 42,
              "activity" : [ { "id" : "A1B2C3D4-0000-0000-0000-000000000001", "kind" : "search", "title" : "Ищу в интернете", "detail" : "", "sites" : ["example.com"], "done" : true, "startedAt" : "2026-09-27T10:00:01Z" } ]
            } ],
            "pinOrder" : 0, "pinned" : true, "systemPrompt" : "", "title" : "Кот", "updatedAt" : "2026-09-27T10:00:05Z"
          } ],
          "draft" : "",
          "instructionLibrary" : [ { "id" : "9A1C2B3D-0000-0000-0000-000000000002", "savedAt" : "2026-09-27T10:00:00Z", "text" : "Отвечай по-русски" } ],
          "memories" : [ { "createdAt" : "2026-09-27T10:00:00Z", "id" : "5D2F0E8A-0000-0000-0000-000000000003", "keywords" : [ "живу", "казани" ], "text" : "Живу в Казани" } ],
          "memoryEnabled" : false,
          "selectedConversationID" : "$chatId",
          "appSettings" : { "language" : "ru" },
          "version" : 1
        }
    """.trimIndent()

    @Test fun iosArchiveImportsWithFilesDatesAndIds() {
        val directory = folder.newFolder("attachments")
        val imported = HistoryArchiveIO.prepareImport(iosSample.toByteArray(), emptySet(), directory)
        val chat = imported.conversations.single()
        assertEquals(chatId, chat.id)
        assertEquals(Instant.parse("2026-09-27T10:00:05Z"), chat.updatedAt)
        assertEquals(MessageInputKind.VOICE, chat.messages[0].inputKind)
        assertEquals("🔥", chat.messages[0].assistantReaction)
        assertEquals("Пиши кратко", chat.instructions!!.single().text)
        assertEquals(com.honerai.app.data.MessageFeedback.LIKE, chat.messages[1].feedback)
        assertEquals("example.com", chat.messages[1].activity!!.single().sites.single())
        val photo = chat.messages[0].attachments.single()
        // Путь с iPhone никогда не используется: файл записан заново из архива.
        assertTrue(photo.localPath!!.startsWith(directory.path))
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()), File(photo.localPath!!).readBytes())
        assertNull(imported.attachmentFiles)
        assertEquals(false, imported.memoryEnabled)
        assertEquals("Живу в Казани", imported.memories!!.single().text)
        // Повторный импорт того же чата пропускается.
        assertTrue(HistoryArchiveIO.prepareImport(iosSample.toByteArray(), setOf(chatId), folder.newFolder("again")).conversations.isEmpty())
    }

    @Test fun encodedArchiveLooksLikeTheIphoneOne() {
        val message = ChatMessage(role = MessageRole.USER, content = "Привет", createdAt = Instant.parse("2026-09-27T10:00:00.123Z"))
        val archive = HistoryArchive(conversations = listOf(Conversation(title = "Тест", messages = listOf(message))), selectedConversationID = null)
        val text = HistoryArchiveIO.encode(archive)
        assertTrue(text, text.contains("\"createdAt\":\"2026-09-27T10:00:00Z\""))
        assertTrue(text.contains("\"role\":\"user\""))
        assertFalse("Пустые необязательные поля не пишутся", text.contains("selectedConversationID"))
        assertTrue(Regex("\"id\":\"[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\"").containsMatchIn(text))
        val id = newId()
        assertEquals("Идентификаторы — заглавными, как UUID на iPhone", id.uppercase(), id)
        val decoded = HistoryArchiveIO.decode(text)
        assertEquals(archive.conversations.single().messages.single().content, decoded.conversations.single().messages.single().content)
    }

    @Test fun exportRoundTripKeepsFramesAndSharedFilesOnce() {
        val source = folder.newFolder("source")
        val movie = File(source, "clip.mp4").apply { writeText("MOVIE") }
        val frame = File(source, "frame.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val video = MessageAttachment(name = "clip.mp4", kind = AttachmentKind.VIDEO, localPath = movie.path, videoFramePaths = listOf(frame.path))
        val copy = video.copy(id = newId())
        val chat = Conversation(messages = listOf(ChatMessage(role = MessageRole.USER, content = "Видео", attachments = listOf(video)),
            ChatMessage(role = MessageRole.USER, content = "Ещё раз", attachments = listOf(copy))))
        val exported = HistoryArchiveIO.export(HistoryArchive(conversations = listOf(chat), memories = listOf(HonorMemory(text = "Факт"))), folder.newFolder("out"))
        val raw = HistoryArchiveIO.decode(exported.readText())
        assertEquals(1, raw.attachmentFiles!!.size)
        assertEquals(video.id, raw.attachmentFileReferences!![copy.id])
        val restored = HistoryArchiveIO.prepareImport(exported.readBytes(), emptySet(), folder.newFolder("restored"))
        val attachments = restored.conversations.single().messages.flatMap { it.attachments }
        assertEquals("MOVIE", File(attachments[0].localPath!!).readText())
        assertEquals(attachments[0].localPath, attachments[1].localPath)
        assertEquals(1, attachments[0].videoFramePaths!!.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(attachments[0].videoFramePaths!!.single()).readBytes())
    }

    @Test fun invalidAndOverfullArchivesAreRejected() {
        val overload = HistoryArchive(conversations = emptyList(), memories = (0..ChatLogic.MAXIMUM_MEMORY_COUNT).map { HonorMemory(text = "Memory $it") })
        try {
            HistoryArchiveIO.prepareImport(HistoryArchiveIO.encode(overload).toByteArray(), emptySet(), folder.newFolder("a"))
            fail("Архив с числом записей памяти больше лимита должен отклоняться")
        } catch (e: HonorError.InvalidArchive) {}
        try {
            HistoryArchiveIO.prepareImport("не json".toByteArray(), emptySet(), folder.newFolder("b"))
            fail()
        } catch (e: HonorError.InvalidArchive) {}
        val duplicate = Conversation(title = "Дубль")
        try {
            HistoryArchiveIO.prepareImport(HistoryArchiveIO.encode(HistoryArchive(conversations = listOf(duplicate, duplicate))).toByteArray(), emptySet(), folder.newFolder("c"))
            fail()
        } catch (e: HonorError.InvalidArchive) {}
    }

    @Test fun importMarksInFlightAnswerInterruptedAndIgnoresForeignPaths() {
        val answer = ChatMessage(role = MessageRole.ASSISTANT, content = "Partial imported answer")
        val photo = MessageAttachment(name = "photo.jpg", kind = AttachmentKind.IMAGE, localPath = "/external/private-photo.jpg")
        val chat = Conversation(messages = listOf(ChatMessage(role = MessageRole.USER, content = "Question", attachments = listOf(photo)), answer))
        val bytes = HistoryArchiveIO.encode(HistoryArchive(conversations = listOf(chat), inFlightMessageID = answer.id)).toByteArray()
        val imported = HistoryArchiveIO.prepareImport(bytes, emptySet(), folder.newFolder("d"))
        val messages = imported.conversations.single().messages
        assertTrue(messages.last().isInterrupted)
        assertNull(messages.first().attachments.single().localPath)
        assertEquals("photo.jpg", messages.first().attachments.single().name)
    }

    @Test fun unreadableHistoryIsKeptAside() {
        val file = File(folder.root, "history.json").apply { writeText("{broken") }
        val result = HistoryArchiveIO.readHistory(file)
        assertNull(result.archive)
        assertTrue(result.error!!.contains("Не удалось открыть историю"))
        assertTrue(folder.root.listFiles()!!.any { it.name.contains("unreadable") })
        assertNull(HistoryArchiveIO.readHistory(File(folder.root, "none.json")).archive)
    }
}
