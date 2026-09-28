package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.ChatTable
import com.honerai.app.data.GenerationStep
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import com.honerai.app.data.WebSource
import com.honerai.app.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.text.Collator
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ---- Описание новых инструментов для API ----

/** Схемы новых инструментов: таблицы, память, массовое чтение сайтов, интеграции, медиа. */
object ExtraToolSchemas {
    private val s = ToolSchema

    fun schema(tool: HonerTool): JsonObject = when (tool) {
        HonerTool.CREATE_TABLE -> s.function(tool.rawValue, "Создаёт таблицу прямо в чате. Пользователь откроет её на весь экран; редактируемую таблицу (editable=true) он может дополнять и править сам, а ты видишь все его правки в разделе «Таблицы этого чата». Используй для учёта, планов, списков, сравнений, трекеров, когда таблицу будут дополнять. Для разового сравнения достаточно Markdown-таблицы в ответе.", mapOf(
            "title" to s.string("Название таблицы"),
            "columns" to s.array(s.string(), "Названия столбцов"),
            "rows" to s.array(s.array(s.string()), "Строки: массивы значений в порядке столбцов"),
            "editable" to s.boolean("true — пользователь может редактировать; false — только просмотр"),
        ), listOf("title", "columns"))
        HonerTool.UPDATE_TABLE -> s.function(tool.rawValue, "Меняет таблицу чата по номеру (T1, T2… из раздела «Таблицы этого чата»). Действия: set_cell (row, column, value), add_row (values), delete_row (row), add_column (name, values), delete_column (column), rename_column (column, name), set_title (title), sort (column, descending), replace (columns, rows), set_editable (editable). Номера строк и столбцов — с 1; столбец можно указать названием.", mapOf(
            "table" to s.string("Номер таблицы: T1, T2 или 1, 2"),
            "action" to s.string("set_cell, add_row, delete_row, add_column, delete_column, rename_column, set_title, sort, replace, set_editable"),
            "row" to s.integer("Номер строки (с 1)"),
            "column" to s.string("Номер (с 1) или название столбца"),
            "value" to s.string("Новое значение ячейки"),
            "values" to s.array(s.string(), "Значения новой строки или столбца"),
            "name" to s.string("Название столбца"),
            "title" to s.string("Новое название таблицы"),
            "columns" to s.array(s.string()),
            "rows" to s.array(s.array(s.string())),
            "descending" to s.boolean(),
            "editable" to s.boolean(),
        ), listOf("table", "action"))
        HonerTool.READ_TABLE -> s.function(tool.rawValue, "Возвращает таблицу чата целиком (с последними правками пользователя) по номеру T1, T2…",
            mapOf("table" to s.string("Номер таблицы: T1, T2 или 1, 2")), listOf("table"))
        HonerTool.LIST_MEMORY -> s.function(tool.rawValue, "Показывает сохранённые факты памяти о пользователе с номерами. Можно отфильтровать по словам.",
            mapOf("query" to s.string("Слова для отбора, необязательно")), emptyList())
        HonerTool.UPDATE_MEMORY -> s.function(tool.rawValue, "Исправляет факт памяти по номеру из list_memory. Используй, когда факт устарел или пользователь его уточнил.",
            mapOf("number" to s.integer("Номер факта из list_memory"), "text" to s.string("Новый текст факта")), listOf("number", "text"))
        HonerTool.DELETE_MEMORY -> s.function(tool.rawValue, "Удаляет факт памяти по номеру из list_memory. Используй, когда пользователь просит забыть что-то или факт неверен.",
            mapOf("number" to s.integer("Номер факта из list_memory")), listOf("number"))
        HonerTool.READ_MANY_PAGES -> s.function(tool.rawValue, "Быстро читает много сайтов параллельно (от десятков до тысяч) и возвращает самые подходящие к вопросу выдержки со ссылками. Передай список адресов urls и/или поисковые запросы queries (по каждому соберутся ссылки из выдачи). Для исследований, сравнений цен, обзоров мнений, сбора фактов из многих источников.", mapOf(
            "question" to s.string("Что именно нужно найти на страницах"),
            "urls" to s.array(s.string(), "Адреса страниц (до 10 000)"),
            "queries" to s.array(s.string(), "Поисковые запросы для сбора ссылок (до 20)"),
            "max_pages" to s.integer("Сколько страниц прочитать максимум, по умолчанию 60"),
            "time_limit" to s.integer("Лимит времени в секундах, по умолчанию 60, максимум 300"),
        ), listOf("question"))
        HonerTool.YOUTUBE_SEARCH -> s.function(tool.rawValue, "Ищет видео на YouTube: название, канал, длительность, просмотры и ссылку. Ссылки вставляй в ответ — приложение покажет видео с кнопкой воспроизведения.",
            mapOf("query" to s.string("Поисковый запрос"), "count" to s.integer("Сколько видео, по умолчанию 6")), listOf("query"))
        HonerTool.YOUTUBE_VIDEO -> s.function(tool.rawValue, "Открывает видео YouTube по ссылке или id: название, автор, длительность, описание и текст субтитров (что говорят в видео), если они доступны.",
            mapOf("video" to s.string("Ссылка на видео или его id")), listOf("video"))
        HonerTool.GITHUB -> s.function(tool.rawValue, "Работает с публичным GitHub: search (поиск репозиториев), repo (описание и README), files (список файлов в папке), file (содержимое файла), issues (открытые задачи), releases (релизы), user (репозитории пользователя).", mapOf(
            "action" to s.string("search, repo, files, file, issues, releases или user"),
            "query" to s.string("Запрос для search или имя для user"),
            "repo" to s.string("Репозиторий в виде owner/name или ссылка"),
            "path" to s.string("Путь к файлу или папке"),
        ), listOf("action"))
        HonerTool.MARKETPLACE_SEARCH -> s.function(tool.rawValue, "Ищет товары на маркетплейсах и досках объявлений: wildberries, ozon, avito, yandex_market. Возвращает названия, цены, рейтинг и ссылки.", mapOf(
            "store" to s.string("wildberries, ozon, avito или yandex_market"),
            "query" to s.string("Что искать"),
            "count" to s.integer("Сколько товаров, по умолчанию 8"),
        ), listOf("store", "query"))
        HonerTool.VK_PAGE -> s.function(tool.rawValue, "Читает публичную страницу ВКонтакте: сообщество или профиль (короткое имя или ссылка) — описание и последние записи, если они открыты без входа.",
            mapOf("page" to s.string("Короткое имя (например, durov) или ссылка vk.com/…")), listOf("page"))
        HonerTool.TELEGRAM_CHANNEL -> s.function(tool.rawValue, "Читает последние публикации публичного канала Telegram по имени или ссылке t.me/…",
            mapOf("channel" to s.string("Имя канала (например, durov) или ссылка")), listOf("channel"))
        HonerTool.VIEW_IMAGE -> s.function(tool.rawValue, "Рассматривает изображение по ссылке (или фото из этого чата по имени) и подробно описывает, что на нём: объекты, текст, людей, детали. Используй, чтобы увидеть картинку с сайта или ответить на вопрос о ней.",
            mapOf("url" to s.string("Адрес изображения или имя файла из чата"), "question" to s.string("Что нужно понять по изображению")), listOf("url"))
        HonerTool.TRANSCRIBE_MEDIA -> s.function(tool.rawValue, "Расшифровывает речь из аудио или видео: голосовые сообщения, подкасты, ролики. Источник — ссылка на файл (mp3, m4a, wav, mp4, mov) или имя файла из этого чата.",
            mapOf("source" to s.string("Ссылка на аудио/видео или имя файла из чата"), "language" to s.string("ru или en, по умолчанию ru")), listOf("source"))
        HonerTool.EDIT_IMAGE -> s.function(tool.rawValue, "Редактирует фото из чата и показывает результат пользователю: remove_background (убрать фон), background_color (value — цвет), background_blur, filter (value: vivid, warm, cool, mono, noir, sepia, fade, chrome, instant, dramatic, vignette, sharpen, blur), adjust (brightness/contrast/saturation через amount), rotate, flip, crop (value: square, portrait4x5, story9x16, landscape16x9), text (text, x, y от 0 до 1, value — цвет), sticker (text — эмодзи), resize (amount — длинная сторона).", mapOf(
            "source" to s.string("Имя фото из чата или last — последнее фото"),
            "operations" to s.array(kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive("object")) },
                "Список операций: [{\"type\":\"remove_background\"}, {\"type\":\"text\",\"text\":\"Привет\",\"x\":0.5,\"y\":0.85}]"),
        ), listOf("operations"))
        else -> s.function(tool.rawValue, "", emptyMap(), emptyList())
    }

    /** Шаг для ленты «что делает Honer AI». */
    fun step(call: ToolCallRequest): GenerationStep? {
        val arguments = call.parsedArguments
        fun argument(key: String) = ToolArgument.string(arguments[key]).orEmpty().take(120)
        return when (HonerTool.from(call.name)) {
            HonerTool.CREATE_TABLE -> GenerationStep(kind = "table", title = "Создаю таблицу", detail = argument("title"))
            HonerTool.UPDATE_TABLE -> GenerationStep(kind = "table", title = "Обновляю таблицу", detail = argument("table"))
            HonerTool.READ_TABLE -> GenerationStep(kind = "table", title = "Смотрю таблицу", detail = argument("table"))
            HonerTool.LIST_MEMORY -> GenerationStep(kind = "memory", title = "Смотрю память")
            HonerTool.UPDATE_MEMORY -> GenerationStep(kind = "memory", title = "Исправляю факт в памяти", detail = argument("text"))
            HonerTool.DELETE_MEMORY -> GenerationStep(kind = "memory", title = "Удаляю факт из памяти")
            HonerTool.READ_MANY_PAGES -> GenerationStep(kind = "read", title = "Читаю много сайтов", detail = argument("question"))
            HonerTool.YOUTUBE_SEARCH -> GenerationStep(kind = "videos", title = "Ищу на YouTube", detail = "«${argument("query")}»", sites = listOf("youtube.com"))
            HonerTool.YOUTUBE_VIDEO -> GenerationStep(kind = "videos", title = "Смотрю видео YouTube", detail = argument("video"), sites = listOf("youtube.com"))
            HonerTool.GITHUB -> GenerationStep(kind = "read", title = "Смотрю GitHub", detail = argument("repo").ifEmpty { argument("query") }, sites = listOf("github.com"))
            HonerTool.MARKETPLACE_SEARCH -> GenerationStep(kind = "search", title = "Ищу товары", detail = "${argument("store")}: «${argument("query")}»")
            HonerTool.VK_PAGE -> GenerationStep(kind = "read", title = "Читаю ВКонтакте", detail = argument("page"), sites = listOf("vk.com"))
            HonerTool.TELEGRAM_CHANNEL -> GenerationStep(kind = "read", title = "Читаю канал Telegram", detail = argument("channel"), sites = listOf("t.me"))
            HonerTool.VIEW_IMAGE -> GenerationStep(kind = "images", title = "Рассматриваю изображение", detail = argument("question"))
            HonerTool.TRANSCRIBE_MEDIA -> GenerationStep(kind = "read", title = "Слушаю и расшифровываю", detail = argument("source"))
            HonerTool.EDIT_IMAGE -> GenerationStep(kind = "draw", title = "Редактирую фото")
            else -> null
        }
    }

    fun status(names: Set<String>): String? {
        val map = listOf(
            HonerTool.READ_MANY_PAGES to "Читаю сайты…", HonerTool.YOUTUBE_SEARCH to "Ищу на YouTube…",
            HonerTool.YOUTUBE_VIDEO to "Смотрю видео…", HonerTool.GITHUB to "Смотрю GitHub…",
            HonerTool.MARKETPLACE_SEARCH to "Ищу товары…", HonerTool.VK_PAGE to "Читаю ВКонтакте…",
            HonerTool.TELEGRAM_CHANNEL to "Читаю Telegram…", HonerTool.VIEW_IMAGE to "Рассматриваю изображение…",
            HonerTool.TRANSCRIBE_MEDIA to "Расшифровываю запись…", HonerTool.EDIT_IMAGE to "Редактирую фото…",
            HonerTool.CREATE_TABLE to "Создаю таблицу…", HonerTool.UPDATE_TABLE to "Обновляю таблицу…",
            HonerTool.READ_TABLE to "Смотрю таблицу…", HonerTool.LIST_MEMORY to "Смотрю память…",
            HonerTool.UPDATE_MEMORY to "Обновляю память…", HonerTool.DELETE_MEMORY to "Обновляю память…",
        )
        return map.firstOrNull { it.first.rawValue in names }?.second
    }
}

// ---- Таблицы ----

class TableEditException(message: String) : Exception(message)

object TableEditing {
    const val MAXIMUM_ROWS = 2000
    const val MAXIMUM_COLUMNS = 40

    fun strings(value: JsonElement?): List<String> {
        if (value is JsonArray) return value.map { ToolArgument.string(it).orEmpty() }
        val text = value.str ?: return emptyList()
        return text.split('|', ';', ',').map { it.trim(' ', '\t') }
    }

    fun rows(value: JsonElement?, columns: List<String>): List<List<String>> {
        val list = value as? JsonArray ?: return emptyList()
        return list.take(MAXIMUM_ROWS).map { item ->
            if (item is JsonObject) columns.map { ToolArgument.string(item[it]).orEmpty() } else strings(item)
        }
    }

    fun make(arguments: JsonObject): ChatTable? {
        var columns = strings(arguments["columns"]).take(MAXIMUM_COLUMNS)
        var rows = rows(arguments["rows"], columns)
        if (columns.isEmpty() && rows.isNotEmpty()) {
            columns = rows.first()
            rows = rows.drop(1)
        }
        if (columns.isEmpty()) return null
        val title = ToolArgument.string(arguments["title"]).orEmpty().trim()
        val editable = ToolArgument.bool(arguments["editable"]) ?: true
        return ChatTable(title = if (title.isEmpty()) "Таблица" else title.take(120), columns = columns,
            rows = rows.map { normalized(it, columns.size) }, editable = editable)
    }

    fun normalized(row: List<String>, width: Int): List<String> = when {
        row.size == width -> row
        row.size > width -> row.take(width)
        else -> row + List(width - row.size) { "" }
    }

    /** Номер столбца по номеру (с 1) или названию. */
    fun columnIndex(value: JsonElement?, table: ChatTable): Int? {
        ToolArgument.int(value)?.let { number -> if (number in 1..table.columns.size) return number - 1 }
        val name = ToolArgument.string(value)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return table.columns.indexOfFirst { it.trim().lowercase() == name }.takeIf { it >= 0 }
    }

    private fun numeric(value: String): Double? = value.replace(',', '.').filter { !it.isWhitespace() }.toDoubleOrNull()

    fun apply(arguments: JsonObject, source: ChatTable): ChatTable {
        var table = source
        val rows = source.rows.map { it.toMutableList() }.toMutableList()
        var columns = source.columns.toMutableList()
        val action = ToolArgument.string(arguments["action"]).orEmpty().lowercase()
        fun row(): Int {
            val number = ToolArgument.int(arguments["row"])
            if (number == null || number < 1 || number > rows.size) {
                throw TableEditException("Нет строки с таким номером: в таблице ${rows.size} строк.")
            }
            return number - 1
        }
        fun column(): Int = columnIndex(arguments["column"], table.copy(columns = columns))
            ?: throw TableEditException("Нет такого столбца. Столбцы: ${columns.joinToString(", ")}.")
        when (action) {
            "set_cell", "set", "update_cell" -> {
                val r = row()
                val c = column()
                while (rows[r].size <= c) rows[r].add("")
                rows[r][c] = ToolArgument.string(arguments["value"]).orEmpty()
            }
            "add_row", "append_row" -> {
                if (rows.size >= MAXIMUM_ROWS) throw TableEditException("В таблице уже $MAXIMUM_ROWS строк.")
                var values = strings(arguments["values"]).toMutableList()
                // Модель иногда передаёт первым значением номер строки из столбца «№».
                if (values.size == columns.size + 1 && values[0].trim().toIntOrNull() != null) values.removeAt(0)
                val dictionary = arguments["values"] as? JsonObject
                if (values.isEmpty() && dictionary != null) {
                    values = columns.map { ToolArgument.string(dictionary[it]).orEmpty() }.toMutableList()
                }
                rows.add(normalized(values, columns.size).toMutableList())
            }
            "delete_row", "remove_row" -> rows.removeAt(row())
            "add_column" -> {
                if (columns.size >= MAXIMUM_COLUMNS) throw TableEditException("Столбцов не больше $MAXIMUM_COLUMNS.")
                val name = ToolArgument.string(arguments["name"]) ?: "Столбец ${columns.size + 1}"
                val values = strings(arguments["values"])
                columns.add(name)
                rows.forEachIndexed { index, cells -> cells.add(values.getOrElse(index) { "" }) }
            }
            "delete_column", "remove_column" -> {
                if (columns.size <= 1) throw TableEditException("Нельзя удалить единственный столбец.")
                val c = column()
                columns.removeAt(c)
                rows.forEach { if (c < it.size) it.removeAt(c) }
            }
            "rename_column" -> {
                val c = column()
                val name = ToolArgument.string(arguments["name"]).orEmpty().trim()
                if (name.isEmpty()) throw TableEditException("Не передано новое название столбца.")
                columns[c] = name
            }
            "set_title", "rename" -> {
                val title = (ToolArgument.string(arguments["title"]) ?: ToolArgument.string(arguments["name"]) ?: "").trim()
                if (title.isEmpty()) throw TableEditException("Не передано название.")
                table = table.copy(title = title.take(120))
            }
            "sort" -> {
                val c = column()
                val descending = ToolArgument.bool(arguments["descending"]) ?: false
                val collator = Collator.getInstance(Locale("ru", "RU"))
                // Числа сравниваются как числа, остальное — по алфавиту; порядок всегда согласован.
                val comparator = Comparator<MutableList<String>> { lhs, rhs ->
                    val left = lhs.getOrElse(c) { "" }
                    val right = rhs.getOrElse(c) { "" }
                    val a = numeric(left)
                    val b = numeric(right)
                    when {
                        a != null && b != null -> a.compareTo(b)
                        a != null -> -1
                        b != null -> 1
                        else -> collator.compare(left, right)
                    }
                }
                rows.sortWith(if (descending) comparator.reversed() else comparator)
            }
            "replace", "replace_all" -> {
                val merged = JsonObject(mapOf("title" to JsonPrimitive(table.title), "editable" to JsonPrimitive(table.editable)) + arguments)
                val replacement = make(merged) ?: throw TableEditException("Для replace нужны columns и rows.")
                columns = replacement.columns.toMutableList()
                rows.clear()
                rows.addAll(replacement.rows.map { it.toMutableList() })
            }
            "set_editable" -> table = table.copy(editable = ToolArgument.bool(arguments["editable"]) ?: true)
            else -> throw TableEditException("Неизвестное действие «$action». Доступно: set_cell, add_row, delete_row, add_column, delete_column, rename_column, set_title, sort, replace, set_editable.")
        }
        return table.copy(columns = columns.toList(), rows = rows.map { it.toList() }, updatedAt = Instant.now())
    }

    fun markdown(table: ChatTable, limitRows: Int = 300): String {
        fun clean(value: String) = value.replace("|", "\\|").replace("\n", " ")
        val lines = mutableListOf<String>()
        lines.add("| № | " + table.columns.joinToString(" | ") { clean(it) } + " |")
        lines.add("|---|" + table.columns.joinToString("|") { "---" } + "|")
        table.rows.take(limitRows).forEachIndexed { index, row ->
            lines.add("| ${index + 1} | " + normalized(row, table.columns.size).joinToString(" | ") { clean(it) } + " |")
        }
        if (table.rows.size > limitRows) lines.add("… ещё ${table.rows.size - limitRows} строк")
        return lines.joinToString("\n")
    }

    fun csv(table: ChatTable): String {
        fun field(value: String): String {
            if (value.none { it == ',' || it == '"' || it == '\n' || it == ';' }) return value
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        val lines = mutableListOf(table.columns.joinToString(",") { field(it) })
        for (row in table.rows) lines.add(normalized(row, table.columns.size).joinToString(",") { field(it) })
        return lines.joinToString("\n")
    }

    /** Номер таблицы из «T2», «2», «t2». */
    fun number(value: JsonElement?): Int? {
        val text = ToolArgument.string(value)?.trim()?.lowercase() ?: return null
        return text.filter { it.isDigit() }.toIntOrNull()
    }

    /** Раздел системной инструкции: таблицы чата с актуальным содержимым. */
    fun promptBlock(tables: List<ChatTable>): String {
        if (tables.isEmpty()) return ""
        var budget = 12_000
        val parts = tables.mapIndexed { index, table ->
            val kind = if (table.editable) "редактируемая" else "только просмотр"
            val edited = if (table.editedByUser) ", пользователь вносил правки" else ""
            var block = "T${index + 1}. «${table.title}» — $kind, ${table.rows.size} строк$edited"
            if (budget > 0) {
                val body = markdown(table, 60)
                block += "\n" + body.take(budget)
                budget -= body.length
            }
            block
        }
        return "\n\n## Таблицы этого чата\nЭто актуальное содержимое таблиц, включая правки пользователя. Первый столбец «№» — номер строки для update_table, это не данные: в values его не передавай. Меняй таблицы инструментом update_table, читай целиком — read_table.\n" + parts.joinToString("\n\n")
    }
}

// ---- Выполнение ----

/** Новые инструменты: таблицы и память — сразу, сеть и медиа — асинхронно с ходом для ленты. */
class ExtraToolExecutor(
    private val client: WebSearchClient = WebSearchClient(),
    private val context: ToolExecutionContext = ToolExecutionContext(),
) {
    private fun reply(call: ToolCallRequest, text: String, effect: ToolEffect? = null) = ToolCallResult(call.id, call.name, text, effect)

    fun executeLocal(call: ToolCallRequest): ToolCallResult {
        val arguments = call.parsedArguments
        return when (val tool = HonerTool.from(call.name)) {
            HonerTool.CREATE_TABLE -> {
                val table = TableEditing.make(arguments)
                    ?: return reply(call, "Не удалось создать таблицу: нужны названия столбцов (columns).")
                val number = context.tables.size + 1
                reply(call, "Таблица T$number «${table.title}» создана и показана пользователю в чате (${table.rows.size} строк, ${if (table.editable) "можно редактировать" else "только просмотр"}). Не повторяй её содержимое в ответе целиком — коротко опиши, что в ней.",
                    ToolEffect.CreateTable(table))
            }
            HonerTool.UPDATE_TABLE, HonerTool.READ_TABLE -> {
                val number = TableEditing.number(arguments["table"])
                if (number == null || number < 1 || number > context.tables.size) {
                    return reply(call, if (context.tables.isEmpty()) "В этом чате пока нет таблиц. Создай её инструментом create_table."
                    else "Нет такой таблицы. Есть: " + context.tables.indices.joinToString(", ") { "T${it + 1}" })
                }
                val table = context.tables[number - 1]
                if (tool == HonerTool.READ_TABLE) return reply(call, "Таблица T$number «${table.title}»:\n" + TableEditing.markdown(table))
                try {
                    val updated = TableEditing.apply(arguments, table)
                    reply(call, "Таблица T$number обновлена. Сейчас в ней ${updated.rows.size} строк.", ToolEffect.ReplaceTable(updated))
                } catch (e: TableEditException) {
                    reply(call, e.message.orEmpty())
                }
            }
            HonerTool.LIST_MEMORY -> {
                if (context.memoryItems.isEmpty()) return reply(call, "Память пуста.")
                val words = ChatLogic.keywords(ToolArgument.string(arguments["query"]).orEmpty().lowercase())
                val lines = mutableListOf<String>()
                for ((index, item) in context.memoryItems.withIndex()) {
                    val lower = item.text.lowercase()
                    if (words.isNotEmpty() && words.none { lower.contains(it) }) continue
                    lines.add("${index + 1}. ${item.text}")
                    if (lines.size >= 200) break
                }
                reply(call, if (lines.isEmpty()) "Ничего не нашлось по запросу." else "Факты памяти:\n" + lines.joinToString("\n"))
            }
            HonerTool.UPDATE_MEMORY, HonerTool.DELETE_MEMORY -> {
                val number = ToolArgument.int(arguments["number"])
                if (number == null || number < 1 || number > context.memoryItems.size) {
                    return reply(call, "Нет факта с таким номером. Сначала вызови list_memory.")
                }
                val item = context.memoryItems[number - 1]
                if (tool == HonerTool.DELETE_MEMORY) return reply(call, "Факт удалён из памяти: «${item.text}».", ToolEffect.DeleteMemory(item.id))
                val text = ToolArgument.string(arguments["text"]).orEmpty().trim()
                if (text.isEmpty()) return reply(call, "Не передан новый текст факта.")
                reply(call, "Факт обновлён: «$text».", ToolEffect.UpdateMemory(item.id, text))
            }
            else -> reply(call, "Неизвестный инструмент.")
        }
    }

    suspend fun execute(call: ToolCallRequest, progress: ToolProgress? = null): ToolCallResult {
        val arguments = call.parsedArguments
        fun text(key: String) = ToolArgument.string(arguments[key]).orEmpty().trim()
        val integrations = IntegrationClient(client)
        val media = MediaInsight(context)
        return when (HonerTool.from(call.name)) {
            HonerTool.READ_MANY_PAGES -> BulkPageReader(client).run(call, progress)
            HonerTool.YOUTUBE_SEARCH -> integrations.youtubeSearch(call, text("query"), ToolArgument.int(arguments["count"]) ?: 6)
            HonerTool.YOUTUBE_VIDEO -> integrations.youtubeVideo(call, text("video"))
            HonerTool.GITHUB -> integrations.github(call, text("action").lowercase(), text("query"), text("repo"), text("path"))
            HonerTool.MARKETPLACE_SEARCH -> integrations.marketplace(call, text("store").lowercase(), text("query"),
                ToolArgument.int(arguments["count"]) ?: 8, progress)
            HonerTool.VK_PAGE -> integrations.vk(call, text("page"))
            HonerTool.TELEGRAM_CHANNEL -> integrations.telegram(call, text("channel"))
            HonerTool.VIEW_IMAGE -> media.viewImage(call, text("url"), text("question"))
            HonerTool.TRANSCRIBE_MEDIA -> media.transcribe(call, text("source"), text("language"), progress)
            HonerTool.EDIT_IMAGE -> media.editImage(call, text("source"), arguments["operations"] ?: arguments)
            else -> executeLocal(call)
        }
    }
}

// ---- Массовое чтение сайтов ----

/** Читает много страниц параллельно и отбирает подходящие к вопросу выдержки. */
class BulkPageReader(private val client: WebSearchClient) {
    class Passage(val score: Int, val text: String)
    class Page(val url: String, val title: String, val passages: List<Passage>)

    suspend fun run(call: ToolCallRequest, progress: ToolProgress?): ToolCallResult {
        val arguments = call.parsedArguments
        val question = ToolArgument.string(arguments["question"]).orEmpty().trim()
        val maxPages = (ToolArgument.int(arguments["max_pages"]) ?: 60).coerceIn(1, 10_000)
        val timeLimit = (ToolArgument.int(arguments["time_limit"]) ?: 60).coerceIn(10, 300)
        val started = System.currentTimeMillis()

        // 1. Адреса: переданные и собранные из поисковой выдачи.
        val urls = TableEditing.strings(arguments["urls"]).mapNotNull { WebToolExecutor.url(it) }.toMutableList()
        val queries = TableEditing.strings(arguments["queries"]).filter { it.isNotEmpty() }.take(20)
        if (urls.isEmpty() && queries.isEmpty() && question.isNotEmpty()) urls += gather(listOf(question), progress)
        else if (queries.isNotEmpty()) urls += gather(queries, progress)
        val seen = HashSet<String>()
        val unique = urls.filter { WebPageText.isPublicWebURL(it) && seen.add(it.toString()) }.map { it.toString() }
        val targets = unique.take(maxPages)
        if (targets.isEmpty()) return ToolCallResult(call.id, call.name, "Не нашлось ни одного адреса для чтения. Передай urls или queries.")

        // 2. Параллельное чтение: не больше CONCURRENCY потоков и общий лимит времени.
        val words = ChatLogic.keywords(question).toSet()
        val deadline = started + timeLimit * 1000L
        val pages = Collections.synchronizedList(mutableListOf<Page>())
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val lastReport = AtomicLong(0)
        val semaphore = Semaphore(CONCURRENCY)
        withTimeoutOrNull(maxOf(1L, deadline - System.currentTimeMillis())) {
            coroutineScope {
                for (url in targets) {
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (System.currentTimeMillis() >= deadline) return@withPermit
                            val page = withTimeoutOrNull(PAGE_TIMEOUT_MS + 4000) { read(url, words) }
                            val count = done.incrementAndGet()
                            if (page != null) pages.add(page) else failed.incrementAndGet()
                            val now = System.currentTimeMillis()
                            val last = lastReport.get()
                            if ((now - last > 250 || count == targets.size) && lastReport.compareAndSet(last, now)) {
                                val hosts = synchronized(pages) { pages.takeLast(4).mapNotNull { it.url.toHttpUrlOrNull()?.host } }
                                progress?.invoke("Прочитано $count из ${targets.size}", hosts)
                            }
                        }
                    }
                }
            }
        }

        // 3. Лучшие выдержки со всех страниц в пределах объёма.
        val snapshot = synchronized(pages) { pages.toList() }
        val ranked = snapshot.flatMapIndexed { index, page -> page.passages.map { Triple(it.score, it.text, index) } }
            .sortedByDescending { it.first }
        var budget = 28_000
        val used = LinkedHashMap<Int, MutableList<String>>()
        val bestScore = HashMap<Int, Int>()
        for ((score, text, page) in ranked) {
            if (budget <= 0) break
            val list = used.getOrPut(page) { mutableListOf() }
            if (list.size >= 3) continue
            list.add(text)
            if (page !in bestScore) bestScore[page] = score
            budget -= text.length
        }
        val order = used.keys.sortedByDescending { bestScore[it] ?: 0 }
        val lines = mutableListOf<String>()
        val sources = mutableListOf<WebSource>()
        for ((number, pageIndex) in order.withIndex()) {
            val page = snapshot[pageIndex]
            lines.add("[${number + 1}] ${page.title} — ${page.url}")
            for (passage in used[pageIndex].orEmpty()) lines.add("   > $passage")
            if (sources.size < 25) {
                sources.add(WebSource(title = page.title, url = page.url, snippet = used[pageIndex]?.firstOrNull().orEmpty().take(280),
                    content = used[pageIndex].orEmpty().joinToString("\n"), fetchedAt = Instant.now()))
            }
        }
        val seconds = Math.round((System.currentTimeMillis() - started) / 1000.0)
        val skipped = maxOf(0, targets.size - done.get())
        var header = "Прочитано страниц: ${snapshot.size} из ${targets.size} (не открылись: ${failed.get()}"
        if (skipped > 0) header += ", не успел за лимит времени: $skipped"
        header += ") за $seconds с."
        if (unique.size > targets.size) header += " Всего адресов было ${unique.size}, прочитаны первые ${targets.size} (max_pages)."
        val body = if (lines.isEmpty()) "Подходящих к вопросу выдержек не нашлось."
        else "Самые подходящие выдержки (номер источника — для ссылок [N](URL)):\n" + lines.joinToString("\n")
        return ToolCallResult(call.id, call.name, "$header\n$body", if (sources.isEmpty()) null else ToolEffect.AddSources(sources))
    }

    private suspend fun gather(queries: List<String>, progress: ToolProgress?): List<okhttp3.HttpUrl> = coroutineScope {
        val result = Collections.synchronizedList(mutableListOf<okhttp3.HttpUrl>())
        queries.map { query ->
            async {
                val all = coroutineScope {
                    val bing = async { client.bingResults(query) }
                    val duck = async { client.duckDuckGoResults(query) }
                    val brave = async { client.braveResults(query) }
                    bing.await() + duck.await() + brave.await()
                }
                val urls = all.mapNotNull { it.url.toHttpUrlOrNull() }
                result.addAll(urls)
                progress?.invoke("Собрано ссылок: ${result.size}", urls.map { it.host }.take(4))
            }
        }.awaitAll()
        synchronized(result) { result.toList() }
    }

    /** Одна страница: быстрый запрос, текст и лучшие абзацы. */
    private suspend fun read(url: String, words: Set<String>): Page? {
        val document = fetchBytes(url, 2_500_000, PAGE_TIMEOUT_MS / 1000) ?: return null
        if (document.status !in 200..299) return null
        val type = document.contentType.lowercase()
        if (type.isNotEmpty() && listOf("html", "text", "json", "xml").none { type.contains(it) }) return null
        return withContext(Dispatchers.Default) {
            val html = document.text
            val isHtml = type.contains("html") || html.contains("<html") || html.contains("<body")
            val parsed = if (isHtml) WebPageText.parse(html) else WebPageText.Parsed(html, null)
            if (parsed.text.length < 80 || WebPageText.looksLikeChallenge(parsed.text)) return@withContext null
            val title = parsed.title ?: url.toHttpUrlOrNull()?.host ?: url
            Page(document.url, title, passages(parsed.text, words))
        }
    }

    companion object {
        const val CONCURRENCY = 24
        const val PAGE_TIMEOUT_MS = 8_000L
        private val digit = Regex("\\d")

        /** Абзацы страницы с оценкой по совпадению слов вопроса. */
        fun passages(text: String, words: Set<String>): List<Passage> {
            val chunks = mutableListOf<String>()
            var current = StringBuilder()
            for (line in text.split("\n")) {
                if (line.length < 30) continue
                if (current.length + line.length > 600 && current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(line)
                if (chunks.size > 400) break
            }
            if (current.isNotEmpty()) chunks.add(current.toString())
            val scored = mutableListOf<Passage>()
            for (chunk in chunks) {
                val lower = chunk.lowercase()
                var score = 0
                for (word in words) if (lower.contains(word)) score += 3
                if (digit.containsMatchIn(lower)) score += 1
                if (words.isEmpty()) score = 1
                if (score > 0) scored.add(Passage(score, chunk.take(700)))
            }
            return scored.sortedByDescending { it.score }.take(3)
        }
    }
}

// ---- Интеграции ----

class IntegrationClient(private val client: WebSearchClient) {
    private fun reply(call: ToolCallRequest, text: String, sources: List<WebSource> = emptyList()) =
        ToolCallResult(call.id, call.name, text, if (sources.isEmpty()) null else ToolEffect.AddSources(sources))

    suspend fun get(url: String, accept: String? = null, agent: String = HonerHttp.DESKTOP_AGENT, timeoutSeconds: Long = 12): Fetched? =
        fetchBytes(url, 8_000_000, timeoutSeconds, if (accept != null) mapOf("Accept" to accept) else emptyMap(), agent)

    suspend fun json(url: String, accept: String = "application/json"): JsonElement? {
        val result = get(url, accept) ?: return null
        if (result.status !in 200..299) return null
        return withContext(Dispatchers.Default) { parseJson(result.text) }
    }

    // ---- YouTube ----

    data class YouTubeVideo(val id: String, val title: String, val channel: String, val length: String, val views: String, val published: String)

    suspend fun youtubeSearch(call: ToolCallRequest, query: String, count: Int): ToolCallResult {
        if (query.isEmpty()) return reply(call, "Не передан запрос.")
        val videos = youTubeVideos(query, count)
        if (videos.isEmpty()) {
            // Запасной путь: поиск видео через поисковики.
            val found = client.videoResults("$query youtube", count)
            if (found.isEmpty()) return reply(call, "YouTube сейчас не ответил, и видео не нашлись. Скажи об этом пользователю.")
            val lines = found.joinToString("\n") { "• [${it.second}](${it.first})" }
            return reply(call, "Видео по запросу «$query»:\n$lines\nВставь ссылки в ответ — приложение покажет видео с кнопкой воспроизведения.",
                found.map { WebSource(title = it.second, url = it.first, snippet = "") })
        }
        val lines = mutableListOf<String>()
        val sources = mutableListOf<WebSource>()
        for (video in videos.take(count)) {
            val link = "https://www.youtube.com/watch?v=${video.id}"
            val details = listOf(video.channel, video.length, video.views, video.published).filter { it.isNotEmpty() }.joinToString(" · ")
            lines.add("• [${video.title}]($link) — $details")
            sources.add(WebSource(title = video.title, url = link, snippet = details))
        }
        return reply(call, "YouTube по запросу «$query»:\n" + lines.joinToString("\n") +
            "\nВставь нужные ссылки в ответ отдельными строками — приложение покажет видео с кнопкой воспроизведения.", sources)
    }

    /** Ролики со страницы поиска YouTube. */
    suspend fun youTubeVideos(query: String, limit: Int): List<YouTubeVideo> {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
            .addQueryParameter("search_query", query).addQueryParameter("hl", "ru").addQueryParameter("gl", "RU").build()
        val page = get(url.toString()) ?: return emptyList()
        if (page.status != 200) return emptyList()
        return withContext(Dispatchers.Default) {
            val data = embeddedJSON("ytInitialData", page.text) ?: return@withContext emptyList()
            val renderers = mutableListOf<JsonObject>()
            collect("videoRenderer", data, limit.coerceIn(1, 20), renderers)
            renderers.mapNotNull { item ->
                val id = item["videoId"].str ?: return@mapNotNull null
                YouTubeVideo(id, runsText(item["title"]), runsText(item["ownerText"]), runsText(item["lengthText"]),
                    runsText(item["viewCountText"]), runsText(item["publishedTimeText"]))
            }
        }
    }

    suspend fun youtubeVideo(call: ToolCallRequest, reference: String): ToolCallResult {
        var id = MediaLinks.youTubeID(reference) ?: reference
        id = id.trim()
        if (id.length < 6 || id.any { !(it.isLetterOrDigit() || it == '_' || it == '-') }) {
            return reply(call, "Не удалось понять, какое это видео. Нужна ссылка на YouTube.")
        }
        val page = get("https://www.youtube.com/watch?v=$id&hl=ru")
        if (page == null || page.status != 200) return reply(call, "YouTube не открылся. Скажи пользователю, что видео сейчас недоступно.")
        val player = withContext(Dispatchers.Default) { embeddedJSON("ytInitialPlayerResponse", page.text) } as? JsonObject
        val details = player?.get("videoDetails") as? JsonObject ?: return reply(call, "Не удалось прочитать данные видео.")
        val title = details["title"].str ?: "Видео"
        val author = details["author"].str.orEmpty()
        val seconds = details["lengthSeconds"].str?.toIntOrNull() ?: 0
        val views = details["viewCount"].str.orEmpty()
        val description = details["shortDescription"].str.orEmpty().take(3000)
        var text = "Видео: $title\nКанал: $author\nДлительность: ${seconds / 60} мин ${seconds % 60} с\nПросмотров: $views\nСсылка: https://www.youtube.com/watch?v=$id\n\nОписание:\n$description"
        val transcript = captions(player)
        text += if (transcript.isEmpty()) "\n\nСубтитры недоступны: о содержании суди по названию и описанию и честно скажи об этом."
        else "\n\nСубтитры (что говорят в видео):\n" + transcript.take(24_000)
        return reply(call, text, listOf(WebSource(title = title, url = "https://www.youtube.com/watch?v=$id", snippet = author)))
    }

    /** Текст субтитров: русские, затем английские, затем любые. */
    private suspend fun captions(player: JsonObject): String {
        val tracks = player["captions"]["playerCaptionsTracklistRenderer"]["captionTracks"].arr?.mapNotNull { it.obj } ?: return ""
        if (tracks.isEmpty()) return ""
        fun rank(track: JsonObject): Int {
            val code = track["languageCode"].str.orEmpty().lowercase()
            return if (code.startsWith("ru")) 0 else if (code.startsWith("en")) 1 else 2
        }
        for (track in tracks.sortedBy { rank(it) }.take(2)) {
            val base = track["baseUrl"].str ?: continue
            val result = get(base) ?: continue
            if (result.status != 200 || result.data.isEmpty()) continue
            val text = WebPageText.decodeEntities(result.text.replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ").trim()
            if (text.length > 40) return text
        }
        return ""
    }

    // ---- GitHub ----

    suspend fun github(call: ToolCallRequest, action: String, query: String, repo: String, path: String): ToolCallResult {
        val api = "https://api.github.com"
        val accept = "application/vnd.github+json"
        fun encoded(text: String) = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        when (action) {
            "search", "search_repos" -> {
                val items = (if (query.isEmpty()) null else json("$api/search/repositories?q=${encoded(query)}&per_page=8", accept)["items"].arr)
                    ?: return reply(call, "GitHub не ответил или ничего не нашёл (у GitHub лимит 60 запросов в час без входа).")
                val sources = mutableListOf<WebSource>()
                val lines = items.map { item ->
                    val name = item["full_name"].str.orEmpty()
                    val stars = item["stargazers_count"].int ?: 0
                    val language = item["language"].str.orEmpty()
                    val about = item["description"].str.orEmpty()
                    val link = item["html_url"].str.orEmpty()
                    if (link.isNotEmpty()) sources.add(WebSource(title = name, url = link, snippet = about))
                    "• [$name]($link) ★$stars $language — $about"
                }
                return reply(call, "Репозитории GitHub по запросу «$query»:\n" + lines.joinToString("\n"), sources)
            }
            "user" -> {
                val name = query.ifEmpty { repo }
                val items = (if (name.isEmpty()) null else json("$api/users/${encoded(name)}/repos?sort=updated&per_page=15", accept).arr)
                    ?: return reply(call, "Пользователь GitHub не найден или GitHub не ответил.")
                val lines = items.map { "• ${it["name"].str.orEmpty()} ★${it["stargazers_count"].int ?: 0} — ${it["description"].str.orEmpty()}" }
                return reply(call, "Репозитории $name:\n" + lines.joinToString("\n"))
            }
        }
        val full = repoPath(repo.ifEmpty { query }) ?: return reply(call, "Укажи репозиторий в виде owner/name.")
        return when (action) {
            "repo", "readme", "info" -> {
                val info = json("$api/repos/$full", accept).obj ?: return reply(call, "Репозиторий $full не найден или GitHub не ответил.")
                var text = "Репозиторий $full\nОписание: ${info["description"].str.orEmpty()}\nЗвёзд: ${info["stargazers_count"].int ?: 0}, форков: ${info["forks_count"].int ?: 0}, открытых задач: ${info["open_issues_count"].int ?: 0}\nЯзык: ${info["language"].str.orEmpty()}\nОбновлён: ${info["pushed_at"].str.orEmpty()}\nСсылка: https://github.com/$full"
                val readme = get("$api/repos/$full/readme", "application/vnd.github.raw")
                if (readme != null && readme.status == 200) text += "\n\nREADME:\n" + readme.text.take(20_000)
                reply(call, text, listOf(WebSource(title = full, url = "https://github.com/$full", snippet = info["description"].str.orEmpty())))
            }
            "files", "list", "tree" -> {
                val folder = path.trim('/')
                val items = json("$api/repos/$full/contents/$folder", accept).arr ?: return reply(call, "Папка не найдена.")
                val lines = items.map { "${if (it["type"].str == "dir") "📁" else "📄"} ${it["path"].str.orEmpty()}" }
                reply(call, "Файлы $full/$folder:\n" + lines.joinToString("\n"))
            }
            "file", "read" -> {
                val file = path.trim('/')
                val result = if (file.isEmpty()) null else get("https://raw.githubusercontent.com/$full/HEAD/$file")
                if (result == null || result.status != 200) return reply(call, "Файл не найден. Посмотри список файлов действием files.")
                val content = decodeText(result.data.copyOf(minOf(result.data.size, 300_000)))
                val language = file.substringAfterLast('.', "")
                reply(call, "Файл $full/$file:\n```$language\n${content.take(40_000)}\n```")
            }
            "issues" -> {
                val items = json("$api/repos/$full/issues?state=open&per_page=15", accept).arr ?: return reply(call, "Не удалось получить задачи.")
                val lines = items.map { "• #${it["number"].int ?: 0} ${it["title"].str.orEmpty()} — ${it["user"]["login"].str.orEmpty()}" }
                reply(call, "Открытые задачи $full:\n" + if (lines.isEmpty()) "нет" else lines.joinToString("\n"))
            }
            "releases" -> {
                val items = json("$api/repos/$full/releases?per_page=6", accept).arr ?: return reply(call, "Не удалось получить релизы.")
                val lines = items.map { "• ${it["tag_name"].str.orEmpty()} ${it["name"].str.orEmpty()} (${it["published_at"].str.orEmpty()})\n  ${it["body"].str.orEmpty().take(600)}" }
                reply(call, "Релизы $full:\n" + if (lines.isEmpty()) "нет" else lines.joinToString("\n"))
            }
            else -> reply(call, "Неизвестное действие. Доступно: search, repo, files, file, issues, releases, user.")
        }
    }

    // ---- Маркетплейсы ----

    suspend fun marketplace(call: ToolCallRequest, store: String, query: String, count: Int, progress: ToolProgress?): ToolCallResult {
        if (query.isEmpty()) return reply(call, "Не передан запрос.")
        val limit = count.coerceIn(1, 20)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        if (store.contains("wild") || store == "wb") {
            progress?.invoke("Wildberries", listOf("wildberries.ru"))
            val products = wildberries(query, limit)
            if (products.isNotEmpty()) {
                return reply(call, "Wildberries — «$query»:\n" + products.joinToString("\n") { it.line }, products.map { it.source })
            }
            return siteSearch(call, "wildberries.ru", "Wildberries", query, limit)
        }
        if (store.contains("ozon")) return siteSearch(call, "ozon.ru", "Ozon", query, limit, "https://www.ozon.ru/search/?text=$encoded")
        if (store.contains("avito") || store.contains("авито")) return siteSearch(call, "avito.ru", "Авито", query, limit, "https://www.avito.ru/rossiya?q=$encoded")
        if (store.contains("yandex") || store.contains("market")) return siteSearch(call, "market.yandex.ru", "Яндекс Маркет", query, limit)
        return reply(call, "Неизвестный магазин. Доступно: wildberries, ozon, avito, yandex_market.")
    }

    class Product(val line: String, val source: WebSource)

    /** Wildberries отдаёт поиск открытым JSON. Версии адреса меняются — пробуем несколько. */
    suspend fun wildberries(query: String, limit: Int): List<Product> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        for (version in listOf("v13", "v9", "v7", "v5", "v4")) {
            val url = "https://search.wb.ru/exactmatch/ru/common/$version/search?ab_testing=false&appType=1&curr=rub&dest=-1257786&query=$encoded&resultset=catalog&sort=popular&spp=30"
            val root = json(url) ?: continue
            val products = (root["data"]["products"].arr ?: root["products"].arr).orEmpty()
            if (products.isEmpty()) continue
            return products.take(limit).mapNotNull { item ->
                val id = item["id"].int ?: item["id"].str?.toIntOrNull() ?: return@mapNotNull null
                val name = item["name"].str ?: "Товар"
                val brand = item["brand"].str.orEmpty()
                var price = (item["salePriceU"].int ?: 0) / 100
                if (price == 0) {
                    val first = item["sizes"].arr?.firstOrNull()["price"]
                    price = ((first["product"].int ?: first["total"].int) ?: 0) / 100
                }
                val rating = item["reviewRating"].dbl ?: item["rating"].dbl ?: 0.0
                val feedbacks = item["feedbacks"].int ?: 0
                val link = "https://www.wildberries.ru/catalog/$id/detail.aspx"
                val priceText = if (price > 0) "$price ₽" else "цена на сайте"
                val line = "• [${if (brand.isEmpty()) "" else "$brand — "}$name]($link) — $priceText, ★${String.format(Locale.US, "%.1f", rating)} ($feedbacks отзывов)"
                Product(line, WebSource(title = name, url = link, snippet = priceText))
            }
        }
        return emptyList()
    }

    /** Магазины с защитой от роботов: страница поиска браузером, при отказе — поиск по сайту. */
    suspend fun siteSearch(call: ToolCallRequest, site: String, name: String, query: String, limit: Int, direct: String? = null): ToolCallResult {
        val hostKey = site.replace("market.", "")
        if (direct != null) {
            val rendered = client.renderer?.render(direct, 14_000)
            if (rendered != null && rendered.text.length > 400 && !WebPageText.looksLikeChallenge(rendered.text)) {
                val links = WebPageText.resultLinks(rendered.html, emptyList()).filter { (it.host ?: "").contains(hostKey) }
                val lines = links.take(limit).joinToString("\n") { "• [${it.title}](${it.url})" }
                return reply(call, "$name — страница поиска «$query» (цены и названия — в тексте ниже):\n${rendered.text.take(9000)}\n\nСсылки на товары:\n$lines",
                    links.take(limit))
            }
        }
        val found = try { client.search("$query site:$site") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val relevant = found.filter { (it.host ?: "").contains(hostKey) || site.startsWith("market") }
        if (relevant.isEmpty()) {
            return reply(call, "$name не отдал результаты без входа (защита от роботов), и поиск по сайту ничего не дал. Предложи пользователю открыть поиск на сайте самому: https://$site")
        }
        val lines = relevant.take(limit).joinToString("\n") { "• [${it.title}](${it.url}) — ${it.snippet.take(200)}" }
        return reply(call, "$name — «$query» (через поиск по сайту; актуальные цены уточняй по ссылке):\n$lines", relevant.take(limit))
    }

    // ---- ВКонтакте и Telegram ----

    suspend fun vk(call: ToolCallRequest, page: String): ToolCallResult {
        var name = page.trim()
        name.toHttpUrlOrNull()?.let { url -> if (url.host.contains("vk.com") || url.host.contains("vk.ru")) name = url.encodedPath.trim('/') }
        name = name.replace("@", "")
        val url = if (name.isEmpty()) null else "https://m.vk.com/$name".toHttpUrlOrNull()
        if (url == null) return reply(call, "Не передано имя страницы.")
        val source = client.readPage(url)
        val content = source?.content
        if (source == null || content == null || content.length <= 100) {
            return reply(call, "Страница ВКонтакте не открылась без входа или закрыта настройками приватности.")
        }
        return reply(call, "ВКонтакте — ${source.title}:\n" + content.take(12_000), listOf(source))
    }

    suspend fun telegram(call: ToolCallRequest, channel: String): ToolCallResult {
        var name = channel.trim().replace("@", "")
        name.toHttpUrlOrNull()?.let { url ->
            if (url.host.contains("t.me")) name = url.pathSegments.firstOrNull { it.isNotEmpty() && it != "s" }.orEmpty()
        }
        if (!name.contains("://") && name.contains("t.me/")) name = name.substringAfter("t.me/").removePrefix("s/").substringBefore('/')
        val url = if (name.isEmpty()) null else "https://t.me/s/$name".toHttpUrlOrNull()
        if (url == null) return reply(call, "Не передано имя канала.")
        val source = client.readPage(url)
        val content = source?.content
        if (source == null || content == null || content.length <= 100) return reply(call, "Канал не открылся: он закрытый или не существует.")
        return reply(call, "Telegram-канал @$name — последние публикации:\n" + content.take(12_000), listOf(source))
    }

    companion object {
        /** JSON, встроенный в страницу YouTube: `var ytInitialData = {...};` */
        fun embeddedJSON(name: String, html: String): JsonElement? {
            for (marker in listOf("var $name = ", "$name = ", "window[\"$name\"] = ")) {
                val start = html.indexOf(marker)
                if (start < 0) continue
                val from = start + marker.length
                if (from >= html.length || html[from] != '{') continue
                // Конец объекта ищем по балансу скобок с учётом строк.
                var depth = 0
                var inString = false
                var escaped = false
                var end = -1
                var index = from
                while (index < html.length) {
                    val c = html[index]
                    if (inString) {
                        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                    } else if (c == '"') inString = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) { end = index; break }
                    }
                    index++
                }
                if (end < 0) continue
                parseJson(html.substring(from, end + 1))?.let { return it }
            }
            return null
        }

        fun collect(key: String, element: JsonElement?, limit: Int, into: MutableList<JsonObject>) {
            if (into.size >= limit) return
            when (element) {
                is JsonObject -> {
                    (element[key] as? JsonObject)?.let { into.add(it) }
                    for ((name, value) in element) if (name != key) collect(key, value, limit, into)
                }
                is JsonArray -> for (value in element) collect(key, value, limit, into)
                else -> {}
            }
        }

        fun runsText(value: JsonElement?): String {
            val dictionary = value as? JsonObject ?: return ""
            dictionary["simpleText"].str?.let { return it }
            val runs = dictionary["runs"].arr ?: return ""
            return runs.mapNotNull { it["text"].str }.joinToString("")
        }

        fun repoPath(raw: String): String? {
            var value = raw.trim()
            value.toHttpUrlOrNull()?.let { url -> if (url.host.contains("github.com")) value = url.encodedPath }
            val parts = value.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return parts[0] + "/" + parts[1].replace(".git", "")
        }
    }
}

// ---- Изображения, звук и редактор ----

class MediaInsight(private val context: ToolExecutionContext) {
    private fun reply(call: ToolCallRequest, text: String, effect: ToolEffect? = null) = ToolCallResult(call.id, call.name, text, effect)

    /** Вложение из чата по имени или «last». */
    fun attachment(name: String, kinds: Set<AttachmentKind>): MessageAttachment? {
        val candidates = context.chatAttachments.filter { it.kind in kinds }
        val lower = name.lowercase().trim()
        if (lower.isEmpty() || lower == "last" || lower == "последнее" || lower == "последнее фото") return candidates.lastOrNull()
        return candidates.lastOrNull { it.name.lowercase() == lower }
            ?: candidates.lastOrNull { it.name.lowercase().contains(lower) || lower.contains(it.name.lowercase()) }
    }

    /** Скачать файл во временную папку (не больше [maximumBytes]). */
    private suspend fun download(url: okhttp3.HttpUrl, maximumBytes: Int): File? {
        val data = fetchBytes(url.toString(), maximumBytes + 1, 30, agent = HonerHttp.DESKTOP_AGENT) ?: return null
        if (data.status !in 200..299 || data.data.isEmpty() || data.data.size > maximumBytes) return null
        val directory = context.cacheDirectory ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val name = url.pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() }?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "media"
        val target = File(directory, newId() + "-" + name.take(60))
        return withContext(Dispatchers.IO) { runCatching { target.writeBytes(data.data); target }.getOrNull() }
    }

    suspend fun viewImage(call: ToolCallRequest, reference: String, question: String): ToolCallResult {
        val vision = context.visionClient ?: return reply(call, "Просмотр изображений сейчас недоступен.")
        var file: File? = null
        var temporary = false
        val local = attachment(reference, setOf(AttachmentKind.IMAGE))
        val localFile = AttachmentFiles.resolve(local?.localPath)
        if (local != null && localFile != null) file = localFile
        else WebToolExecutor.url(reference)?.let { file = download(it, 15_000_000); temporary = true }
        val source = file ?: return reply(call, "Не удалось открыть изображение. Проверь ссылку.")
        try {
            val jpeg = withContext(Dispatchers.Default) { runCatching { ImagePayload.jpeg(source) }.getOrNull() }
                ?: return reply(call, "Не удалось открыть изображение. Проверь ссылку.")
            val directory = context.cacheDirectory ?: source.parentFile
            val prepared = File(directory, "view-${newId()}.jpg")
            try {
                withContext(Dispatchers.IO) { prepared.writeBytes(jpeg) }
                val message = ChatMessage(
                    role = MessageRole.USER,
                    content = question.ifEmpty { "Подробно опиши изображение: что и кто на нём, текст на нём, детали, цвета, настроение." } +
                        " Отвечай по существу, по-русски, опираясь только на то, что видно.",
                    attachments = listOf(MessageAttachment(name = "image.jpg", kind = AttachmentKind.IMAGE, localPath = prepared.path)),
                )
                val answer = vision.complete(listOf(message), false, "", "").trim()
                if (answer.isEmpty()) return reply(call, "Изображение открыто, но описать его не удалось.")
                return reply(call, "Что на изображении:\n$answer")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return reply(call, "Не удалось рассмотреть изображение: ${e.message.orEmpty()}")
            } finally {
                prepared.delete()
            }
        } finally {
            if (temporary) source.delete()
        }
    }

    /**
     * На Android нет системного распознавания речи из файла (SpeechRecognizer слушает
     * только микрофон), поэтому честно говорим об этом. Если запись прикреплена в чат
     * и при прикреплении у неё уже появилась расшифровка, отдаём её.
     */
    suspend fun transcribe(call: ToolCallRequest, reference: String, language: String, progress: ToolProgress?): ToolCallResult {
        val local = attachment(reference, setOf(AttachmentKind.AUDIO, AttachmentKind.VIDEO))
        if (local != null && local.extractedText.isNotBlank()) {
            return reply(call, "Расшифровка записи «${local.name}» (сделана при прикреплении файла):\n" + local.extractedText.take(40_000))
        }
        val link = WebToolExecutor.url(reference)
        if (link != null) progress?.invoke("Скачиваю запись", listOf(link.host))
        return reply(call, "Расшифровка речи из аудио- и видеофайлов на Android недоступна: система распознаёт речь только с микрофона, а не из файла. " +
            "Честно скажи об этом пользователю и предложи варианты: наговорить нужный фрагмент голосом (кнопка с микрофоном в панели ввода), " +
            "прислать текст или субтитры; для роликов YouTube можно получить субтитры инструментом youtube_video.")
    }

    suspend fun editImage(call: ToolCallRequest, source: String, operations: JsonElement): ToolCallResult {
        val original = attachment(source, setOf(AttachmentKind.IMAGE))
        val file = AttachmentFiles.resolve(original?.localPath)
        if (original == null || file == null) return reply(call, "В этом чате нет такого фото. Попроси пользователя прислать фото.")
        val parsed = normalizeOperations(operations)
        if (parsed.isEmpty()) return reply(call, "Не переданы операции (operations).")
        return try {
            val bitmap = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.path) }
                ?: return reply(call, "В этом чате нет такого фото. Попроси пользователя прислать фото.")
            val edited = com.honerai.app.ui.editor.ImageEditing.apply(JsonArray(parsed).toString(), bitmap)
            val directory = context.outputDirectory ?: file.parentFile ?: return reply(call, "Не удалось сохранить фото.")
            val saved = withContext(Dispatchers.IO) { com.honerai.app.ui.editor.ImageEditing.save(edited, directory, edited.hasAlpha()) }
            val base = original.name.substringBeforeLast('.', original.name)
            val name = "$base (изменено).${saved.extension.ifEmpty { "png" }}"
            val attachment = MessageAttachment(name = name, kind = AttachmentKind.IMAGE, localPath = saved.path)
            reply(call, "Готово: отредактированное фото «$name» показано пользователю под ответом. Коротко опиши, что изменено.",
                ToolEffect.AttachFile(attachment))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            reply(call, "Не удалось отредактировать фото: ${e.message.orEmpty()}")
        }
    }

    companion object {
        /** Операции редактора: массив, одна операция объектом или строка с JSON. */
        fun normalizeOperations(value: JsonElement?): List<JsonObject> = when (value) {
            is JsonArray -> value.mapNotNull { item ->
                (item as? JsonObject) ?: item.str?.let { JsonObject(mapOf("type" to JsonPrimitive(it))) }
            }
            is JsonObject -> when {
                value["operations"] != null -> normalizeOperations(value["operations"])
                value["type"] != null -> listOf(value)
                else -> emptyList()
            }
            is JsonPrimitive -> value.str?.let { text -> parseJson(text)?.let { normalizeOperations(it) } } ?: emptyList()
            else -> emptyList()
        }
    }
}
