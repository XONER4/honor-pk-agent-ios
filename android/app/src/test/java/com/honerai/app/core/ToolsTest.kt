package com.honerai.app.core

import com.honerai.app.data.ChatTable
import com.honerai.app.data.WebSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Инструменты модели без сети: таблицы, память, чаты, настройки, рисунки, интеграции. */
class ToolsTest {
    private fun args(json: String): JsonObject = parseJson(json)!!.jsonObject

    @Test fun tableEditingActions() {
        var table = TableEditing.make(args("""{"title":"Бюджет","columns":["Статья","Сумма"],"rows":[["Еда","100"],["Кино","30"]],"editable":true}"""))!!
        table = TableEditing.apply(args("""{"action":"set_cell","row":2,"column":"Сумма","value":"45"}"""), table)
        assertEquals("45", table.rows[1][1])
        table = TableEditing.apply(args("""{"action":"add_row","values":["Такси","20"]}"""), table)
        table = TableEditing.apply(args("""{"action":"add_column","name":"Комментарий"}"""), table)
        assertEquals(3, table.columns.size)
        assertEquals(3, table.rows.size)
        table = TableEditing.apply(args("""{"action":"sort","column":2,"descending":true}"""), table)
        assertEquals("Еда", table.rows.first()[0])
        table = TableEditing.apply(args("""{"action":"delete_row","row":3}"""), table)
        assertEquals(2, table.rows.size)
        try { TableEditing.apply(args("""{"action":"set_cell","row":9,"column":1,"value":"x"}"""), table); fail() } catch (e: TableEditException) {}
        try { TableEditing.apply(args("""{"action":"teleport"}"""), table); fail() } catch (e: TableEditException) {}
        assertTrue(TableEditing.markdown(table).contains("| № | Статья | Сумма | Комментарий |"))
        assertTrue(TableEditing.csv(table).startsWith("Статья,Сумма,Комментарий"))
        assertEquals(2, TableEditing.number(JsonPrimitive("T2")))
        // Лишний номер строки из столбца «№» отбрасывается.
        val shifted = TableEditing.apply(args("""{"action":"add_row","values":["3","Кафе","540",""]}"""), table)
        assertEquals(listOf("Кафе", "540", ""), shifted.rows.last())
        val renamed = TableEditing.apply(args("""{"action":"rename_column","column":"Комментарий","name":"Заметка"}"""), shifted)
        assertEquals("Заметка", renamed.columns[2])
        val replaced = TableEditing.apply(args("""{"action":"replace","columns":["A"],"rows":[["1"],["2"]]}"""), renamed)
        assertEquals(listOf("A"), replaced.columns)
        assertEquals("Бюджет", replaced.title)
        // Строки объектами и без столбцов: первая строка становится шапкой.
        val fromObjects = TableEditing.make(args("""{"title":"","columns":["Имя","Год"],"rows":[{"Имя":"Ада","Год":1815}]}"""))!!
        assertEquals(listOf(listOf("Ада", "1815")), fromObjects.rows)
        assertEquals("Таблица", fromObjects.title)
        val headless = TableEditing.make(args("""{"rows":[["Город","Дни"],["Сочи","12"]]}"""))!!
        assertEquals(listOf("Город", "Дни"), headless.columns)
    }

    @Test fun tableToolsCreateUpdateAndPromptShowsUserEdits() {
        val create = ExtraToolExecutor().executeLocal(ToolCallRequest("1", "create_table",
            """{"title":"Покупки","columns":["Товар","Цена"],"rows":[["Хлеб","50"]],"editable":true}"""))
        val table = (create.effect as ToolEffect.CreateTable).table
        val edited = table.copy(rows = table.rows + listOf(listOf("Молоко", "90")), editedByUser = true)
        val prompt = TableEditing.promptBlock(listOf(edited))
        assertTrue(prompt.contains("## Таблицы этого чата"))
        assertTrue(prompt.contains("Молоко"))
        assertTrue(prompt.contains("пользователь вносил правки"))
        val update = ExtraToolExecutor(context = ToolExecutionContext(tables = listOf(edited)))
            .executeLocal(ToolCallRequest("2", "update_table", """{"table":"T1","action":"set_cell","row":1,"column":"Цена","value":"55"}"""))
        assertEquals("55", (update.effect as ToolEffect.ReplaceTable).table.rows[0][1])
        val read = ExtraToolExecutor(context = ToolExecutionContext(tables = listOf(edited)))
            .executeLocal(ToolCallRequest("3", "read_table", """{"table":"1"}"""))
        assertTrue(read.content.contains("Молоко"))
        val missing = ExtraToolExecutor().executeLocal(ToolCallRequest("4", "read_table", """{"table":"T1"}"""))
        assertTrue(missing.content.contains("пока нет таблиц"))
    }

    @Test fun memoryToolsListUpdateAndDelete() {
        val context = ToolExecutionContext(memoryItems = listOf(MemoryRef("A", "Живу в Казани"), MemoryRef("B", "Люблю кофе")))
        val executor = ExtraToolExecutor(context = context)
        assertTrue(executor.executeLocal(ToolCallRequest("1", "list_memory", "{}")).content.contains("1. Живу в Казани"))
        assertEquals("2. Люблю кофе", executor.executeLocal(ToolCallRequest("1", "list_memory", """{"query":"кофе"}""")).content.lines().last())
        val update = executor.executeLocal(ToolCallRequest("2", "update_memory", """{"number":1,"text":"Живу в Москве"}"""))
        assertEquals(ToolEffect.UpdateMemory("A", "Живу в Москве"), update.effect)
        val delete = executor.executeLocal(ToolCallRequest("3", "delete_memory", """{"number":"2"}"""))
        assertEquals(ToolEffect.DeleteMemory("B"), delete.effect)
    }

    @Test fun chatToolsActOnStableChatIdentifiers() {
        val context = ToolExecutionContext(
            chats = listOf(ChatOverview(1, "FIRST", "Первый", 2, null, false, false, ""),
                ChatOverview(2, "SECOND", "Второй", 5, Instant.now(), true, false, "про отпуск")),
            transcripts = mapOf(2 to listOf(ChatTranscriptLine("user", "Отпуск 12 дней"))))
        val rename = ToolExecutor.executeExtended(ToolCallRequest("c1", "rename_chat", """{"number": 2, "title": "Отпуск"}"""), context)
        assertEquals(ToolEffect.RenameChat("SECOND", "Отпуск"), rename.effect)
        assertTrue(ToolExecutor.executeExtended(ToolCallRequest("c2", "read_chat", """{"number": "2"}"""), context).content.contains("12 дней"))
        val list = ToolExecutor.executeExtended(ToolCallRequest("c3", "list_chats", "{}"), context).content
        assertTrue(list.contains("2. «Второй»"))
        assertTrue(list.contains("закреплён"))
        val pin = ToolExecutor.executeExtended(ToolCallRequest("c4", "pin_chat", """{"number": 1, "pinned": "да"}"""), context)
        assertEquals(ToolEffect.PinChat("FIRST", true), pin.effect)
        val send = ToolExecutor.executeExtended(ToolCallRequest("c5", "send_message_to_chat", """{"number": 1, "text": "Привет"}"""), context)
        assertEquals(ToolEffect.SendToChatID("FIRST", "Привет"), send.effect)
        assertTrue(ToolExecutor.executeExtended(ToolCallRequest("c6", "read_chat", """{"number": 7}"""), context).content.contains("не найден"))
        val setting = ToolExecutor.executeExtended(ToolCallRequest("c7", "set_app_setting", """{"name": "theme", "value": "dark"}"""), context)
        assertNull(setting.effect)
        assertEquals(ToolEffect.SetSetting("autoRead", "true"),
            ToolExecutor.executeExtended(ToolCallRequest("c8", "set_app_setting", """{"name": "autoRead", "value": true}"""), context).effect)
    }

    @Test fun settingsDrawingGameAndClipboardAnswerWithoutNetwork() {
        var copied = ""
        val context = ToolExecutionContext(settingsSummary = "• Поиск в интернете: включено\n• Рассуждение: выключено", clipboard = { copied = it })
        assertTrue(ToolExecutor.executeExtended(ToolCallRequest("s", "get_app_settings", "{}"), context).content.contains("Поиск в интернете: включено"))
        val draw = ToolExecutor.executeExtended(ToolCallRequest("d", "draw_image", """{"prompt": "red fox in snow"}"""), context)
        assertTrue(draw.content, draw.content.contains("![Рисунок: red fox in snow](https://image.pollinations.ai/prompt/red%20fox%20in%20snow"))
        assertTrue(ToolExecutor.executeExtended(ToolCallRequest("e", "draw_image", "{}"), context).content.contains("Не передано"))
        val game = ToolExecutor.executeExtended(ToolCallRequest("g", "start_game", """{"game": "шахматы"}"""), context)
        assertEquals(ToolEffect.OpenGame("chess"), game.effect)
        ToolExecutor.executeExtended(ToolCallRequest("c", "copy_to_clipboard", """{"text": "код 1234"}"""), context)
        assertEquals("код 1234", copied)
        assertTrue(ToolExecutor.executeExtended(ToolCallRequest("t", "get_current_datetime", "{}"), context).content.startsWith("Сейчас"))
    }

    @Test fun internetToolsAreOfferedOnlyWithSearchButtonAndIntegrationsFilter() {
        fun names(list: List<JsonObject>) = list.map { it["function"]["name"].str!! }.toSet()
        val off = names(HonerTool.schemas(false))
        val on = names(HonerTool.schemas(true))
        for (web in listOf("web_search", "open_page", "find_images", "find_videos", "screenshot_page", "get_weather", "github")) {
            assertFalse(web, web in off)
            assertTrue(web, web in on)
        }
        for (always in listOf("draw_image", "get_app_settings", "list_chats", "read_chat", "set_app_setting", "save_memory", "create_table", "edit_image")) {
            assertTrue(always, always in off && always in on)
        }
        val filtered = names(HonerTool.schemas(true) { it != "github" })
        assertFalse("github" in filtered)
        assertEquals(HonerTool.entries.size, HonerTool.apiSchemas.size)
        // Каждая схема — корректная функция с параметрами-объектом.
        for (schema in HonerTool.apiSchemas) {
            assertEquals("function", schema["type"].str)
            assertEquals("object", schema["function"]["parameters"]["type"].str)
            assertTrue(schema["function"]["description"].str!!.isNotEmpty())
            assertTrue(schema["function"]["parameters"]["required"] is JsonArray)
        }
    }

    @Test fun embeddedYouTubeJsonAndGitHubPaths() {
        val html = """<script>var ytInitialData = {"a":{"videoRenderer":{"videoId":"abc123XYZ","title":{"runs":[{"text":"Видео \"1\""}]}}}};</script>"""
        val data = IntegrationClient.embeddedJSON("ytInitialData", html)
        assertNotNull(data)
        val found = mutableListOf<JsonObject>()
        IntegrationClient.collect("videoRenderer", data, 5, found)
        assertEquals("abc123XYZ", found.first()["videoId"].str)
        assertEquals("Видео \"1\"", IntegrationClient.runsText(found.first()["title"]))
        assertEquals("Просто", IntegrationClient.runsText(parseJson("""{"simpleText":"Просто"}""")))
        assertEquals("apple/swift", IntegrationClient.repoPath("https://github.com/apple/swift.git"))
        assertEquals("apple/swift-nio", IntegrationClient.repoPath("apple/swift-nio"))
        assertNull(IntegrationClient.repoPath("swift"))
        assertNull(IntegrationClient.embeddedJSON("ytInitialData", "<script>var ytInitialData = {\"a\": </script>"))
    }

    @Test fun bulkReaderRanksPassagesByQuestionWords() {
        val text = (0 until 20).joinToString("\n") { "Абзац номер $it про погоду и облака, совсем не про цену билетов на поезд." } +
            "\nСтоимость билета на поезд Москва — Казань составляет 3500 рублей в плацкарте, это важный факт."
        val words = ChatLogic.keywords("стоимость билета поезд Казань").toSet()
        val passages = BulkPageReader.passages(text, words)
        assertTrue(passages.first().text.contains("3500"))
        assertTrue(passages.size <= 3)
        assertEquals(1, BulkPageReader.passages("Короткая строка, но длиннее тридцати знаков точно.", emptySet()).first().score)
    }

    @Test fun editOperationsAreNormalized() {
        assertEquals(1, MediaInsight.normalizeOperations(parseJson("""[{"type":"remove_background"}]""")).size)
        assertEquals(1, MediaInsight.normalizeOperations(parseJson("""{"type":"filter","value":"mono"}""")).size)
        assertEquals(2, MediaInsight.normalizeOperations(JsonPrimitive("""[{"type":"rotate"},{"type":"flip"}]""")).size)
        assertEquals(1, MediaInsight.normalizeOperations(parseJson("""["remove_background"]""")).size)
        assertTrue(MediaInsight.normalizeOperations(parseJson("""{"source":"last"}""")).isEmpty())
    }

    @Test fun mediaInsightFindsChatAttachmentsAndTranscribeIsHonest() {
        val voice = com.honerai.app.data.MessageAttachment(name = "voice.m4a", kind = com.honerai.app.data.AttachmentKind.AUDIO, extractedText = "Привет, это голосовое")
        val photo = com.honerai.app.data.MessageAttachment(name = "cat.jpg", kind = com.honerai.app.data.AttachmentKind.IMAGE)
        val insight = MediaInsight(ToolExecutionContext(chatAttachments = listOf(photo, voice)))
        assertEquals(photo, insight.attachment("last", setOf(com.honerai.app.data.AttachmentKind.IMAGE)))
        assertEquals(photo, insight.attachment("CAT", setOf(com.honerai.app.data.AttachmentKind.IMAGE)))
        val transcript = kotlinx.coroutines.runBlocking {
            insight.transcribe(ToolCallRequest("1", "transcribe_media", "{}"), "voice.m4a", "ru", null)
        }
        assertTrue(transcript.content.contains("Привет, это голосовое"))
        val unavailable = kotlinx.coroutines.runBlocking {
            MediaInsight(ToolExecutionContext()).transcribe(ToolCallRequest("1", "transcribe_media", "{}"), "podcast.mp3", "ru", null)
        }
        assertTrue(unavailable.content.contains("на Android недоступна"))
    }

    @Test fun webSourcesStayImmutableWithRawHtml() {
        val source = WebSource(title = "t", url = "https://example.com", snippet = "s").also { it.rawHTML = "<img src='a.jpg'>" }
        assertEquals("<img src='a.jpg'>", source.copyKeepingRaw(title = "new").rawHTML)
        assertEquals(ChatTable(title = "x", columns = listOf("a"), rows = emptyList()).title, "x")
    }
}
