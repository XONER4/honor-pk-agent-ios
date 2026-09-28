package com.honerai.app.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.math.BigDecimal
import java.math.MathContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

// Порт DocumentReader.swift: текст из документов (Office, OpenDocument, EPUB, RTF, HTML, CSV, код).
// Только JDK: ZIP — свой разбор, XML — SAX (есть и на Android, и в JVM-тестах). Работает синхронно;
// вызывать с Dispatchers.IO.

/** Результат: текст для нейросети и короткая подпись для карточки вложения. */
data class DocumentText(val text: String, val summary: String)

object DocumentReader {
    const val MAXIMUM_CHARACTERS = 160_000
    const val MAXIMUM_TABLE_ROWS = 2_000
    const val MAXIMUM_TABLE_COLUMNS = 60
    /** Рабочий бюджет UTF-8 байт при разборе: дальше всё равно обрежем до 160 000 символов. */
    const val WORKING_BUDGET = 700_000
    const val TRUNCATION_NOTE = "\n\n… (текст обрезан: документ длиннее 160 000 символов)"

    val supportedExtensions: Set<String> = setOf(
        "docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm",
        "odt", "ods", "odp", "rtf", "html", "htm", "xhtml", "xml", "epub", "csv", "tsv",
        "txt", "md", "markdown", "json", "log", "text",
        "swift", "py", "js", "jsx", "ts", "tsx", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cc",
        "m", "mm", "cs", "go", "rs", "rb", "php", "sh", "bash", "zsh", "ps1", "sql",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "css", "scss", "less", "vue", "svelte",
        "dart", "lua", "r", "pl", "scala", "gradle", "properties", "tex", "bib", "srt", "vtt",
        "ics", "vcf", "plist", "ipynb",
    )

    data class CodeLanguage(val fence: String, val name: String)

    val codeLanguages: Map<String, CodeLanguage> = mapOf(
        "swift" to CodeLanguage("swift", "Swift"),
        "py" to CodeLanguage("python", "Python"),
        "js" to CodeLanguage("javascript", "JavaScript"),
        "jsx" to CodeLanguage("jsx", "JSX"),
        "ts" to CodeLanguage("typescript", "TypeScript"),
        "tsx" to CodeLanguage("tsx", "TSX"),
        "java" to CodeLanguage("java", "Java"),
        "kt" to CodeLanguage("kotlin", "Kotlin"),
        "kts" to CodeLanguage("kotlin", "Kotlin"),
        "c" to CodeLanguage("c", "C"),
        "h" to CodeLanguage("c", "C"),
        "cpp" to CodeLanguage("cpp", "C++"),
        "hpp" to CodeLanguage("cpp", "C++"),
        "cc" to CodeLanguage("cpp", "C++"),
        "m" to CodeLanguage("objectivec", "Objective-C"),
        "mm" to CodeLanguage("objectivec", "Objective-C++"),
        "cs" to CodeLanguage("csharp", "C#"),
        "go" to CodeLanguage("go", "Go"),
        "rs" to CodeLanguage("rust", "Rust"),
        "rb" to CodeLanguage("ruby", "Ruby"),
        "php" to CodeLanguage("php", "PHP"),
        "sh" to CodeLanguage("bash", "Shell"),
        "bash" to CodeLanguage("bash", "Bash"),
        "zsh" to CodeLanguage("zsh", "Zsh"),
        "ps1" to CodeLanguage("powershell", "PowerShell"),
        "sql" to CodeLanguage("sql", "SQL"),
        "yaml" to CodeLanguage("yaml", "YAML"),
        "yml" to CodeLanguage("yaml", "YAML"),
        "toml" to CodeLanguage("toml", "TOML"),
        "ini" to CodeLanguage("ini", "INI"),
        "cfg" to CodeLanguage("ini", "CFG"),
        "conf" to CodeLanguage("ini", "CONF"),
        "env" to CodeLanguage("bash", "ENV"),
        "css" to CodeLanguage("css", "CSS"),
        "scss" to CodeLanguage("scss", "SCSS"),
        "less" to CodeLanguage("less", "Less"),
        "vue" to CodeLanguage("vue", "Vue"),
        "svelte" to CodeLanguage("svelte", "Svelte"),
        "dart" to CodeLanguage("dart", "Dart"),
        "lua" to CodeLanguage("lua", "Lua"),
        "r" to CodeLanguage("r", "R"),
        "pl" to CodeLanguage("perl", "Perl"),
        "scala" to CodeLanguage("scala", "Scala"),
        "gradle" to CodeLanguage("groovy", "Gradle"),
        "properties" to CodeLanguage("properties", "Properties"),
        "tex" to CodeLanguage("latex", "LaTeX"),
        "json" to CodeLanguage("json", "JSON"),
        "xml" to CodeLanguage("xml", "XML"),
        "plist" to CodeLanguage("xml", "Plist"),
    )

    fun isSupported(fileExtension: String): Boolean = normalizedExtension(fileExtension) in supportedExtensions

    // MARK: Точка входа

    fun extractText(data: ByteArray, fileExtension: String): DocumentText {
        val ext = normalizedExtension(fileExtension)
        if (ext !in supportedExtensions) fail(DocumentReaderError.UNSUPPORTED_FORMAT)
        if (data.isEmpty()) fail(DocumentReaderError.EMPTY_DOCUMENT)
        val raw = when (ext) {
            "docx", "docm", "dotx" -> readWord(data)
            "xlsx", "xlsm", "xltx" -> readExcel(data)
            "pptx", "pptm" -> readPowerPoint(data)
            "odt", "ods", "odp" -> readOpenDocument(data, ext)
            "epub" -> readEPUB(data)
            "rtf" -> readRTF(data)
            "html", "htm", "xhtml" -> readHTML(data)
            "csv", "tsv" -> readDelimited(data, ext)
            "ipynb" -> readNotebook(data)
            else -> readPlainText(data, ext)
        }
        val text = limited(normalize(raw.text))
        if (text.isEmpty()) fail(DocumentReaderError.EMPTY_DOCUMENT)
        return DocumentText(text, raw.summary)
    }

    internal fun fail(error: DocumentReaderError): Nothing = throw DocumentReaderException(error)

    // MARK: Подписи для интерфейса

    fun kind(forExtension: String): String = when (val ext = normalizedExtension(forExtension)) {
        "docx", "docm", "dotx" -> "Документ Word"
        "xlsx", "xlsm", "xltx" -> "Таблица Excel"
        "pptx", "pptm" -> "Презентация PowerPoint"
        "odt" -> "Документ OpenDocument"
        "ods" -> "Таблица OpenDocument"
        "odp" -> "Презентация OpenDocument"
        "rtf" -> "Документ RTF"
        "html", "htm", "xhtml" -> "Веб-страница"
        "epub" -> "Книга EPUB"
        "csv" -> "Таблица CSV"
        "tsv" -> "Таблица TSV"
        "ipynb" -> "Блокнот Jupyter"
        "md", "markdown" -> "Markdown"
        "srt", "vtt" -> "Субтитры"
        "ics" -> "Календарь"
        "vcf" -> "Контакты"
        "log" -> "Журнал"
        "pdf" -> "Документ PDF"
        else -> when {
            codeLanguages.containsKey(ext) -> "Код"
            ext in supportedExtensions -> "Текст"
            else -> "Файл"
        }
    }

    // MARK: Word

    private fun readWord(data: ByteArray): DocumentText {
        val archive = openPackage(data)
        val documentPath = mainPart(archive, "word/document.xml")
        val documentXML = archive.read(documentPath) ?: fail(DocumentReaderError.MISSING_CONTENT)
        val rels = relationships(documentPath, archive)

        // Стили: styleId → уровень заголовка (русский Word хранит id вида "1", а имя "heading 1")
        var headingLevels: Map<String, Int> = emptyMap()
        val stylesPath = rels.firstOrNull { it.type.endsWith("/styles") }?.let { resolve(it.target, documentPath) } ?: "word/styles.xml"
        archive.read(stylesPath)?.let { xml ->
            val styles = WordStylesHandler()
            XmlSupport.parse(xml, styles)
            headingLevels = styles.headingLevels()
        }

        val body = OfficeTextHandler()
        body.styleHeadingLevels = headingLevels
        val parsed = XmlSupport.parse(documentXML, body)
        if (!parsed && !body.truncated && body.output.isBlank()) fail(DocumentReaderError.INVALID_ARCHIVE)
        val text = StringBuilder(body.output)

        // Сноски — в конце документа
        for ((suffix, title) in listOf("/footnotes" to "Сноски", "/endnotes" to "Концевые сноски")) {
            if (body.truncated) break
            val relation = rels.firstOrNull { it.type.endsWith(suffix) } ?: continue
            val xml = archive.read(resolve(relation.target, documentPath)) ?: continue
            val handler = OfficeTextHandler()
            XmlSupport.parse(xml, handler)
            val content = handler.output.trim()
            if (content.isNotEmpty()) text.append("\n\n---\n").append(title).append(":\n").append(content)
        }

        val parts = ArrayList<String>()
        val properties = appProperties(archive)
        val pages = properties["Pages"]?.toIntOrNull()
        if (pages != null && pages > 0) {
            parts.add(plural(pages, "страница", "страницы", "страниц"))
        } else {
            parts.add(plural(wordCount(text.toString()), "слово", "слова", "слов"))
        }
        if (body.tableCount > 0) parts.add(plural(body.tableCount, "таблица", "таблицы", "таблиц"))
        return DocumentText(text.toString(), "Документ Word: " + parts.joinToString(", "))
    }

    // MARK: Excel

    private fun readExcel(data: ByteArray): DocumentText {
        val archive = openPackage(data)
        val workbookPath = mainPart(archive, "xl/workbook.xml")
        val workbookXML = archive.read(workbookPath) ?: fail(DocumentReaderError.MISSING_CONTENT)
        val workbook = WorkbookHandler()
        XmlSupport.parse(workbookXML, workbook)
        val rels = relationships(workbookPath, archive)
        val targets = HashMap<String, String>()
        for (relation in rels) targets[relation.id] = resolve(relation.target, workbookPath)

        var sharedStrings: List<String> = emptyList()
        val sharedPath = rels.firstOrNull { it.type.endsWith("/sharedStrings") }?.let { resolve(it.target, workbookPath) } ?: "xl/sharedStrings.xml"
        archive.read(sharedPath)?.let { xml ->
            val handler = SharedStringsHandler()
            XmlSupport.parse(xml, handler)
            sharedStrings = handler.strings
        }

        var dateStyles: Set<Int> = emptySet()
        val stylesPath = rels.firstOrNull { it.type.endsWith("/styles") }?.let { resolve(it.target, workbookPath) } ?: "xl/styles.xml"
        archive.read(stylesPath)?.let { xml ->
            val handler = ExcelStylesHandler()
            XmlSupport.parse(xml, handler)
            dateStyles = handler.dateStyleIndexes()
        }

        data class SheetRef(val name: String, val path: String, val hidden: Boolean)
        val sheets = ArrayList<SheetRef>()
        workbook.sheets.forEachIndexed { index, sheet ->
            val path = targets[sheet.relationshipID] ?: "xl/worksheets/sheet${index + 1}.xml"
            if (path.contains("chartsheets/") || path.contains("dialogsheets/")) return@forEachIndexed
            sheets.add(SheetRef(sheet.name, path, sheet.hidden))
        }
        if (sheets.isEmpty()) {
            val paths = archive.names.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sortedBy { trailingNumber(it) }
            paths.forEachIndexed { index, path -> sheets.add(SheetRef("Лист ${index + 1}", path, false)) }
        }

        val sections = ArrayList<String>()
        var sheetCount = 0
        var totalRows = 0
        var size = 0
        for (sheet in sheets) {
            val xml = archive.read(sheet.path) ?: continue
            val handler = SheetHandler(sharedStrings, dateStyles, workbook.date1904)
            XmlSupport.parse(xml, handler)
            sheetCount += 1
            totalRows += handler.nonEmptyRowCount
            val section = renderSheet(sheet.name, sheet.hidden, handler)
            size += utf8Length(section)
            sections.add(section)
            if (size > WORKING_BUDGET) break
        }
        if (sheetCount == 0) fail(DocumentReaderError.MISSING_CONTENT)
        val summary = "Таблица Excel: " + plural(sheetCount, "лист", "листа", "листов") + ", " + plural(totalRows, "строка", "строки", "строк")
        return DocumentText(sections.joinToString("\n\n"), summary)
    }

    private fun renderSheet(name: String, hidden: Boolean, handler: SheetHandler): String {
        val section = StringBuilder("## Лист «").append(name).append("»").append(if (hidden) " (скрытый)" else "").append("\n\n")
        val rows = handler.rows
        var minColumn = Int.MAX_VALUE
        var maxColumn = -1
        for (row in rows) for (column in row.keys) {
            minColumn = minOf(minColumn, column)
            maxColumn = maxOf(maxColumn, column)
        }
        if (maxColumn < 0) return section.append("(пустой лист)").toString()
        val matrix = rows.map { row -> (minColumn..maxColumn).map { row[it] ?: "" } }
        section.append(markdownTable(matrix))
        if (handler.nonEmptyRowCount > rows.size) {
            section.append("\n… (показаны первые ${rows.size} строк из ${handler.nonEmptyRowCount})")
        }
        if (handler.columnsTruncated) section.append("\n… (показаны первые $MAXIMUM_TABLE_COLUMNS столбцов)")
        return section.toString()
    }

    // MARK: PowerPoint

    private fun readPowerPoint(data: ByteArray): DocumentText {
        val archive = openPackage(data)
        val presentationPath = mainPart(archive, "ppt/presentation.xml")
        val slidePaths = ArrayList<String>()
        archive.read(presentationPath)?.let { xml ->
            val handler = PresentationHandler()
            XmlSupport.parse(xml, handler)
            val targets = HashMap<String, String>()
            for (relation in relationships(presentationPath, archive)) targets[relation.id] = resolve(relation.target, presentationPath)
            for (id in handler.slideRelationshipIDs) {
                val path = targets[id]
                if (path != null && archive.contains(path)) slidePaths.add(path)
            }
        }
        // Запасной путь: сортировка по номеру в имени (slide2 раньше slide10)
        if (slidePaths.isEmpty()) {
            val prefix = "ppt/slides/slide"
            slidePaths.addAll(archive.names
                .filter { it.startsWith(prefix) && it.endsWith(".xml") && !it.removePrefix("ppt/slides/").contains("/") }
                .sortedBy { trailingNumber(it) })
        }
        if (slidePaths.isEmpty()) fail(DocumentReaderError.MISSING_CONTENT)

        val sections = ArrayList<String>()
        var size = 0
        for ((index, path) in slidePaths.withIndex()) {
            val xml = archive.read(path) ?: continue
            val slide = OfficeTextHandler()
            XmlSupport.parse(xml, slide)
            var section = "## Слайд ${index + 1}\n" + slide.output.trim()

            val slideRels = relationships(path, archive)
            var notesPath = "ppt/notesSlides/notesSlide${trailingNumber(path)}.xml"
            slideRels.firstOrNull { it.type.endsWith("/notesSlide") }?.let { notesPath = resolve(it.target, path) }
            archive.read(notesPath)?.let { notesXML ->
                val notes = OfficeTextHandler()
                notes.skipsFields = true
                XmlSupport.parse(notesXML, notes)
                val content = notes.output.trim()
                if (content.isNotEmpty()) section += "\n\nЗаметки: $content"
            }
            size += utf8Length(section)
            sections.add(section)
            if (size > WORKING_BUDGET) break
        }
        val summary = "Презентация PowerPoint: " + plural(slidePaths.size, "слайд", "слайда", "слайдов")
        return DocumentText(sections.joinToString("\n\n"), summary)
    }

    // MARK: OpenDocument

    private fun readOpenDocument(data: ByteArray, ext: String): DocumentText {
        val archive = openPackage(data)
        archive.read("META-INF/manifest.xml")?.let { manifest ->
            if (indexOf(manifest, "encryption-data".toByteArray()) >= 0) fail(DocumentReaderError.LEGACY_OR_ENCRYPTED)
        }
        val xml = archive.read("content.xml") ?: fail(DocumentReaderError.MISSING_CONTENT)
        val mode = when (ext) {
            "ods" -> OpenDocumentHandler.Mode.SPREADSHEET
            "odp" -> OpenDocumentHandler.Mode.PRESENTATION
            else -> OpenDocumentHandler.Mode.TEXT
        }
        val handler = OpenDocumentHandler(mode)
        val parsed = XmlSupport.parse(xml, handler)
        if (!parsed && !handler.truncated && handler.output.isBlank()) fail(DocumentReaderError.INVALID_ARCHIVE)
        val summary = when (mode) {
            OpenDocumentHandler.Mode.SPREADSHEET -> "Таблица OpenDocument: " + plural(handler.sheetCount, "лист", "листа", "листов") +
                ", " + plural(handler.rowCount, "строка", "строки", "строк")
            OpenDocumentHandler.Mode.PRESENTATION -> "Презентация OpenDocument: " + plural(handler.slideCount, "слайд", "слайда", "слайдов")
            OpenDocumentHandler.Mode.TEXT -> "Документ OpenDocument: " + plural(wordCount(handler.output), "слово", "слова", "слов")
        }
        return DocumentText(handler.output, summary)
    }

    // MARK: EPUB

    private fun readEPUB(data: ByteArray): DocumentText {
        val archive = openPackage(data)
        var packagePath = ""
        archive.read("META-INF/container.xml")?.let { container ->
            val handler = AttributeCollector("rootfile", "full-path")
            XmlSupport.parse(container, handler)
            packagePath = ZipArchiveReader.normalizedPath(handler.values.firstOrNull() ?: "")
        }
        if (packagePath.isEmpty()) packagePath = archive.names.firstOrNull { it.lowercase().endsWith(".opf") } ?: ""

        val chapterPaths = ArrayList<String>()
        var title = ""
        if (packagePath.isNotEmpty()) {
            archive.read(packagePath)?.let { opf ->
                val handler = PackageDocumentHandler()
                XmlSupport.parse(opf, handler)
                title = handler.title.trim()
                val seen = HashSet<String>()
                for (idref in handler.spine) {
                    val href = handler.manifest[idref] ?: continue
                    val path = resolve(href, packagePath)
                    if (seen.add(path)) chapterPaths.add(path)
                }
            }
        }
        if (chapterPaths.isEmpty()) {
            chapterPaths.addAll(archive.names.filter {
                val lower = it.lowercase()
                lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
            }.sorted())
        }
        if (chapterPaths.isEmpty()) fail(DocumentReaderError.MISSING_CONTENT)

        val parts = ArrayList<String>()
        if (title.isNotEmpty()) parts.add("# $title")
        var chapters = 0
        var size = 0
        for (path in chapterPaths) {
            if (size > WORKING_BUDGET) break
            val content = archive.read(path) ?: continue
            val html = decodeText(content) ?: continue
            val text = plainTextFromHTML(html).trim()
            if (text.isEmpty()) continue
            chapters += 1
            size += utf8Length(text)
            parts.add(text)
        }
        if (chapters == 0) fail(DocumentReaderError.EMPTY_DOCUMENT)
        return DocumentText(parts.joinToString("\n\n"), "Книга EPUB: " + plural(chapters, "глава", "главы", "глав"))
    }

    // MARK: RTF

    private fun readRTF(data: ByteArray): DocumentText {
        val source = String(data, Charsets.ISO_8859_1)
        if (!source.trimStart().startsWith("{\\rtf")) fail(DocumentReaderError.UNREADABLE_TEXT)
        val text = RtfText.plainText(source)
        return DocumentText(text, "Документ RTF: " + plural(wordCount(text), "слово", "слова", "слов"))
    }

    // MARK: HTML

    private fun readHTML(data: ByteArray): DocumentText {
        val html = decodeText(data) ?: fail(DocumentReaderError.UNREADABLE_TEXT)
        val text = plainTextFromHTML(html)
        return DocumentText(text, "Веб-страница: " + plural(wordCount(text), "слово", "слова", "слов"))
    }

    /** Быстрое удаление тегов: однопроходный сканер, как на iPhone. */
    fun plainTextFromHTML(html: String): String = HtmlTextScanner(html).run()

    val namedEntities: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»", "copy" to "©",
        "reg" to "®", "trade" to "™", "euro" to "€", "bull" to "•", "middot" to "·", "rsquo" to "’",
        "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bdquo" to "„", "shy" to "", "times" to "×",
        "deg" to "°", "minus" to "−", "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwnj" to "", "zwj" to "",
        "rarr" to "→", "larr" to "←", "sect" to "§", "para" to "¶", "plusmn" to "±", "frac12" to "½",
        "cent" to "¢", "pound" to "£", "yen" to "¥", "numero" to "№",
    )

    /** Имя сущности без "&" и ";": "amp", "#39", "#x1F600". */
    fun decodeEntity(name: String): String? {
        if (name.startsWith("#")) {
            val body = name.substring(1)
            val code = if (body.startsWith("x") || body.startsWith("X")) body.substring(1).toIntOrNull(16) else body.toIntOrNull(10)
            if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
            return String(Character.toChars(code))
        }
        return namedEntities[name] ?: namedEntities[name.lowercase()]
    }

    // MARK: CSV / TSV

    data class DelimitedTable(val rows: List<List<String>>, val totalRows: Int, val columnsTruncated: Boolean)

    private fun readDelimited(data: ByteArray, ext: String): DocumentText {
        val text = decodeText(data) ?: fail(DocumentReaderError.UNREADABLE_TEXT)
        val delimiter = if (ext == "tsv") '\t' else detectDelimiter(text)
        val table = parseDelimited(text, delimiter, MAXIMUM_TABLE_ROWS, MAXIMUM_TABLE_COLUMNS)
        if (table.rows.isEmpty()) fail(DocumentReaderError.EMPTY_DOCUMENT)
        val result = StringBuilder(markdownTable(table.rows))
        if (table.totalRows > table.rows.size) result.append("\n… (показаны первые ${table.rows.size} строк из ${table.totalRows})")
        if (table.columnsTruncated) result.append("\n… (показаны первые $MAXIMUM_TABLE_COLUMNS столбцов)")
        var columns = 0
        for (row in table.rows) {
            val last = row.indexOfLast { trimWs(it).isNotEmpty() }
            if (last >= 0) columns = maxOf(columns, last + 1)
        }
        val summary = kind(ext) + ": " + plural(table.totalRows, "строка", "строки", "строк") + ", " + plural(columns, "столбец", "столбца", "столбцов")
        return DocumentText(result.toString(), summary)
    }

    /** Для CSV: сравниваем ';', ',' и табуляцию в первой строке. */
    fun detectDelimiter(text: String): Char {
        var semicolons = 0
        var commas = 0
        var tabs = 0
        for (c in text) {
            if (c == '\n' || c == '\r') break
            when (c) {
                ';' -> semicolons++
                ',' -> commas++
                '\t' -> tabs++
            }
        }
        if (tabs > semicolons && tabs > commas) return '\t'
        return if (semicolons > commas) ';' else ','
    }

    /** Разбор с кавычками: "" внутри кавычек, переводы строк внутри кавычек. */
    fun parseDelimited(text: String, delimiter: Char, maximumRows: Int, maximumColumns: Int): DelimitedTable {
        val rows = ArrayList<List<String>>()
        var total = 0
        var columnsTruncated = false
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        val count = text.length

        fun commit(values: List<String>) {
            if (values.none { trimWs(it).isNotEmpty() }) return
            total += 1
            if (rows.size >= maximumRows) return
            if (values.size > maximumColumns) {
                if (values.subList(maximumColumns, values.size).any { trimWs(it).isNotEmpty() }) columnsTruncated = true
                rows.add(values.subList(0, maximumColumns).toList())
            } else {
                rows.add(values)
            }
        }

        if (count > 0 && text[0] == '﻿') index = 1
        while (index < count) {
            val c = text[index]
            if (inQuotes) {
                if (c == '"') {
                    if (index + 1 < count && text[index + 1] == '"') {
                        field.append('"')
                        index += 2
                        continue
                    }
                    inQuotes = false
                    index += 1
                    continue
                }
                field.append(c)
                index += 1
                continue
            }
            if (c == '"' && field.isEmpty()) {
                inQuotes = true
                index += 1
                continue
            }
            if (c == delimiter) {
                row.add(field.toString())
                field.setLength(0)
                index += 1
                continue
            }
            if (c == '\n' || c == '\r') {
                row.add(field.toString())
                field.setLength(0)
                commit(row)
                row = ArrayList()
                index += if (c == '\r' && index + 1 < count && text[index + 1] == '\n') 2 else 1
                continue
            }
            field.append(c)
            index += 1
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            commit(row)
        }
        return DelimitedTable(rows, total, columnsTruncated)
    }

    // MARK: Jupyter

    private fun readNotebook(data: ByteArray): DocumentText {
        val root = try {
            Json.parseToJsonElement(decodeText(data) ?: fail(DocumentReaderError.UNREADABLE_TEXT)) as? JsonObject
        } catch (e: DocumentReaderException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: fail(DocumentReaderError.UNREADABLE_TEXT)
        val cells = root["cells"] as? JsonArray ?: fail(DocumentReaderError.UNREADABLE_TEXT)
        val metadata = root["metadata"] as? JsonObject
        val kernel = metadata?.get("kernelspec") as? JsonObject
        val info = metadata?.get("language_info") as? JsonObject
        val language = (stringValue(kernel?.get("language")) ?: stringValue(info?.get("name")) ?: "python").lowercase()

        val parts = ArrayList<String>()
        for (element in cells) {
            val cell = element as? JsonObject ?: continue
            val type = stringValue(cell["cell_type"]) ?: ""
            val source = notebookText(cell["source"]).trim('\n', '\r')
            if (type == "code") {
                val outputs = ArrayList<String>()
                (cell["outputs"] as? JsonArray)?.forEach { item ->
                    val output = item as? JsonObject ?: return@forEach
                    var text = notebookText(output["text"])
                    if (text.isEmpty()) (output["data"] as? JsonObject)?.let { text = notebookText(it["text/plain"]) }
                    if (text.isEmpty()) stringValue(output["ename"])?.let { name -> text = name + ": " + (stringValue(output["evalue"]) ?: "") }
                    text = text.trim()
                    if (text.length > 2_000) text = text.take(2_000) + "…"
                    if (text.isNotEmpty()) outputs.add(text)
                }
                if (source.isEmpty() && outputs.isEmpty()) continue
                var block = "```$language\n$source\n```"
                if (outputs.isNotEmpty()) block += "\nВывод:\n```\n" + outputs.joinToString("\n") + "\n```"
                parts.add(block)
            } else if (source.isNotEmpty()) {
                parts.add(source)
            }
        }
        return DocumentText(parts.joinToString("\n\n"), "Блокнот Jupyter: " + plural(cells.size, "ячейка", "ячейки", "ячеек"))
    }

    private fun stringValue(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun notebookText(value: kotlinx.serialization.json.JsonElement?): String = when (value) {
        is JsonPrimitive -> if (value.isString) value.content else ""
        is JsonArray -> value.joinToString("") { stringValue(it) ?: "" }
        else -> ""
    }

    // MARK: Текст и код

    private fun readPlainText(data: ByteArray, ext: String): DocumentText {
        val text = decodeText(data) ?: fail(DocumentReaderError.UNREADABLE_TEXT)
        val lines = lineCount(text)
        val language = codeLanguages[ext]
        if (language != null) {
            val fence = if (text.contains("```")) "````" else "```"
            val body = text.trimEnd('\n', '\r')
            return DocumentText(fence + language.fence + "\n" + body + "\n" + fence,
                "Код ${language.name}: " + plural(lines, "строка", "строки", "строк"))
        }
        return DocumentText(text, kind(ext) + ": " + plural(lines, "строка", "строки", "строк"))
    }

    /** UTF-8 → UTF-16 (BOM или явные нули) → Windows-1251 → ISO Latin 1. Бинарные данные — null. */
    fun decodeText(data: ByteArray): String? {
        if (data.isEmpty()) return ""
        val b0 = data[0].toInt() and 0xFF
        val b1 = if (data.size > 1) data[1].toInt() and 0xFF else -1
        val b2 = if (data.size > 2) data[2].toInt() and 0xFF else -1
        val decoded: String? = when {
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> strict(data, 3, Charsets.UTF_8)
            (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF) -> String(data, Charsets.UTF_16)
            else -> {
                val utf16 = guessedUTF16(data)
                if (utf16 != null) {
                    String(data, utf16)
                } else {
                    val limit = minOf(data.size, 8_192)
                    for (i in 0 until limit) if (data[i].toInt() == 0) return null
                    strict(data, 0, Charsets.UTF_8)
                        ?: runCatching { strict(data, 0, Charset.forName("windows-1251")) }.getOrNull()
                        ?: String(data, Charsets.ISO_8859_1)
                }
            }
        }
        return decoded?.removePrefix("﻿")
    }

    private fun strict(data: ByteArray, offset: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, data.size - offset)).toString()
    } catch (_: Exception) {
        null
    }

    private fun guessedUTF16(data: ByteArray): Charset? {
        val sampleSize = minOf(data.size, 512)
        val pairs = sampleSize / 2
        if (pairs < 2) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sampleSize) {
            if (data[i].toInt() == 0) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
        }
        if (oddZeros * 10 >= pairs * 4 && evenZeros * 10 < pairs) return Charsets.UTF_16LE
        if (evenZeros * 10 >= pairs * 4 && oddZeros * 10 < pairs) return Charsets.UTF_16BE
        return null
    }

    // MARK: Общие помощники

    fun normalizedExtension(fileExtension: String): String = fileExtension.lowercase().trim('.', ' ')

    /** Swift .whitespaces: пробелы (Zs) и табуляция, без переводов строк. */
    internal fun isWs(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    internal fun trimWs(s: String): String = s.trim { isWs(it) }

    /** Переводы строк → "\n", не больше одной пустой строки подряд, обрезка краёв. */
    fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(unified.length)
        var blankRun = 0
        var first = true
        for (line in unified.split('\n')) {
            val blank = line.all { it == ' ' || it == '\t' || it == ' ' }
            if (blank) {
                blankRun += 1
                if (blankRun > 1) continue
                if (!first) out.append('\n')
            } else {
                blankRun = 0
                if (!first) out.append('\n')
                out.append(line)
            }
            first = false
        }
        return out.toString().trim()
    }

    fun limited(text: String): String {
        if (text.length <= MAXIMUM_CHARACTERS) return text
        val keep = MAXIMUM_CHARACTERS - TRUNCATION_NOTE.length
        var head = text.substring(0, keep)
        if (head.isNotEmpty() && Character.isHighSurrogate(head.last())) head = head.dropLast(1)
        return head.trimEnd() + TRUNCATION_NOTE
    }

    /** Markdown-таблица: первая строка — заголовок; пустые строки и хвостовые пустые столбцы убираются. */
    fun markdownTable(rows: List<List<String>>): String {
        val cleaned = rows.map { row -> row.map { markdownCell(it) } }
        var width = 0
        for (row in cleaned) {
            val last = row.indexOfLast { it.isNotEmpty() }
            if (last >= 0) width = maxOf(width, last + 1)
        }
        if (width == 0) return ""
        val lines = ArrayList<String>()
        for (row in cleaned) {
            if (row.none { it.isNotEmpty() }) continue
            val cells = ArrayList(row.take(width))
            while (cells.size < width) cells.add("")
            lines.add("| " + cells.joinToString(" | ") + " |")
            if (lines.size == 1) lines.add("|" + "---|".repeat(width))
        }
        return lines.joinToString("\n")
    }

    fun markdownCell(value: String): String = value.trim()
        .replace("\r\n", "\n").replace("\r", "\n")
        .replace("|", "\\|")
        .replace("\n", "<br>")
        .replace("\t", " ")

    /** Вложенная таблица внутри ячейки: строки через перевод строки, ячейки через "; ". */
    fun flattenedTable(rows: List<List<String>>): String =
        rows.map { row -> row.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ") }
            .filter { it.isNotEmpty() }.joinToString("\n")

    /** "Heading 2", "heading2", "Заголовок 3", "Title" → уровень заголовка. */
    fun headingLevel(styleName: String): Int? {
        val compact = styleName.lowercase().replace(" ", "")
        if (compact == "title" || compact == "название" || compact == "заголовок" || compact == "heading") return 1
        for (prefix in listOf("heading", "заголовок")) {
            if (!compact.startsWith(prefix)) continue
            val rest = compact.substring(prefix.length)
            if (rest.isNotEmpty() && rest.all { it in '0'..'9' }) {
                val number = rest.toIntOrNull() ?: continue
                if (number >= 1) return minOf(number, 6)
            }
        }
        return null
    }

    /** "B12" → 1, "AA3" → 26. */
    fun columnIndex(reference: String): Int? {
        var column = 0
        var letters = 0
        for (c in reference) {
            when (c) {
                '$' -> continue
                in 'A'..'Z' -> column = column * 26 + (c - 'A' + 1)
                in 'a'..'z' -> column = column * 26 + (c - 'a' + 1)
                else -> break
            }
            letters += 1
            if (letters > 4) return null
        }
        return if (letters == 0) null else column - 1
    }

    /** Убирает двоичный «шум» вида 0.30000000000000004 (до 15 значащих цифр, как показывает Excel). */
    fun formatNumber(raw: String): String {
        val trimmed = trimWs(raw)
        if (trimmed.length <= 15 || !trimmed.contains('.') || trimmed.contains('e') || trimmed.contains('E')) return trimmed
        val number = trimmed.toDoubleOrNull() ?: return trimmed
        if (number.isNaN() || number.isInfinite()) return trimmed
        return BigDecimal(number).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    fun isDateFormat(id: Int, code: String?): Boolean {
        if (!code.isNullOrEmpty()) {
            val cleaned = StringBuilder()
            var inQuotes = false
            var inBrackets = false
            var escapeNext = false
            for (c in code) {
                if (escapeNext) { escapeNext = false; continue }
                if (inQuotes) { if (c == '"') inQuotes = false; continue }
                if (inBrackets) { if (c == ']') inBrackets = false; continue }
                when (c) {
                    '"' -> inQuotes = true
                    '[' -> inBrackets = true
                    '\\', '_', '*' -> escapeNext = true
                    else -> cleaned.append(c)
                }
            }
            val lower = cleaned.toString().lowercase()
            return lower.contains('y') || lower.contains('d') || lower.contains('h')
        }
        return id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58
    }

    /** Серийный номер Excel → "2024-05-01", "2024-05-01 13:30" или "13:30". */
    fun excelDate(serial: Double, date1904: Boolean): String? {
        if (serial.isNaN() || serial.isInfinite() || serial < 0 || serial >= 2_958_466) return null
        val epoch = if (date1904) -2_082_844_800L else -2_209_161_600L
        val totalSeconds = Math.round(serial * 86_400)
        val date = LocalDateTime.ofEpochSecond(epoch + totalSeconds, 0, ZoneOffset.UTC)
        val day = pad(date.year, 4) + "-" + pad(date.monthValue, 2) + "-" + pad(date.dayOfMonth, 2)
        val second = date.second
        val time = pad(date.hour, 2) + ":" + pad(date.minute, 2) + (if (second > 0) ":" + pad(second, 2) else "")
        if (serial < 1) return time
        val secondsOfDay = totalSeconds % 86_400
        return if (secondsOfDay == 0L) day else "$day $time"
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    fun plural(count: Int, one: String, few: String, many: String): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
        return "$count $word"
    }

    fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val isWord = Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.LETTER_NUMBER.toInt() || type == Character.OTHER_NUMBER.toInt()
            if (isWord && !inWord) count += 1
            inWord = isWord
            i += Character.charCount(cp)
        }
        return count
    }

    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        for (c in text) if (c == '\n') lines += 1
        if (text.last() == '\n') lines -= 1
        return maxOf(lines, 1)
    }

    /** "ppt/slides/slide12.xml" → 12. */
    fun trailingNumber(path: String): Int {
        val file = path.substringAfterLast('/')
        val base = if (file.contains('.')) file.substringBeforeLast('.') else file
        val digits = base.takeLastWhile { it in '0'..'9' }
        return digits.toIntOrNull() ?: 0
    }

    internal fun utf8Length(text: String): Int {
        var length = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            length += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return length
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // MARK: OPC-пакеты (docx/xlsx/pptx)

    private fun openPackage(data: ByteArray): ZipArchiveReader.Archive {
        // OLE-контейнер: старый .doc/.xls/.ppt или зашифрованный OOXML
        val compound = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        if (data.size >= 8 && (0 until 8).all { data[it] == compound[it] }) fail(DocumentReaderError.LEGACY_OR_ENCRYPTED)
        return ZipArchiveReader.Archive(data)
    }

    private fun mainPart(archive: ZipArchiveReader.Archive, fallback: String): String {
        val office = relationships("", archive).firstOrNull { it.type.endsWith("/officeDocument") }
        if (office != null) {
            val path = resolve(office.target, "")
            if (archive.contains(path)) return path
        }
        return fallback
    }

    internal fun relationships(part: String, archive: ZipArchiveReader.Archive): List<PackageRelationship> {
        val xml = archive.read(relationshipsPath(part)) ?: return emptyList()
        val handler = RelationshipsHandler()
        XmlSupport.parse(xml, handler)
        return handler.relationships.filter { !it.isExternal }
    }

    private fun appProperties(archive: ZipArchiveReader.Archive): Map<String, String> {
        val xml = archive.read("docProps/app.xml") ?: return emptyMap()
        val handler = ElementTextCollector(setOf("Pages", "Slides", "Words"))
        XmlSupport.parse(xml, handler)
        return handler.values
    }

    /** "xl/workbook.xml" → "xl/_rels/workbook.xml.rels"; "" → "_rels/.rels". */
    fun relationshipsPath(part: String): String {
        if (part.isEmpty()) return "_rels/.rels"
        val slash = part.lastIndexOf('/')
        if (slash < 0) return "_rels/$part.rels"
        return part.substring(0, slash) + "/_rels/" + part.substring(slash + 1) + ".rels"
    }

    /** Цель связи относительно папки части; "/..." — от корня пакета. */
    fun resolve(target: String, part: String): String {
        var clean = target.substringBefore('#')
        clean = percentDecoded(clean) ?: clean
        if (clean.startsWith("/")) return ZipArchiveReader.normalizedPath(clean)
        val slash = part.lastIndexOf('/')
        val directory = if (slash >= 0) part.substring(0, slash + 1) else ""
        return ZipArchiveReader.normalizedPath(directory + clean)
    }

    /** Как removingPercentEncoding: "%20" → " ", «+» не трогаем; ошибка → null. */
    internal fun percentDecoded(value: String): String? {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(byte)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return strict(bytes.toByteArray(), 0, Charsets.UTF_8)
    }
}

// MARK: - XML: общие помощники

internal object XmlSupport {
    fun localName(qualified: String): String {
        val colon = qualified.lastIndexOf(':')
        return if (colon < 0) qualified else qualified.substring(colon + 1)
    }

    fun attribute(attributes: Map<String, String>, name: String): String? {
        attributes[name]?.let { return it }
        for ((key, value) in attributes) {
            if (key.contains(':') && !key.startsWith("xmlns") && localName(key) == name) return value
        }
        return null
    }

    /** Именно r:id (у p:sldId есть ещё обычный числовой id). */
    fun relationshipID(attributes: Map<String, String>): String? {
        for ((key, value) in attributes) {
            if (key.contains(':') && !key.startsWith("xmlns") && localName(key) == "id") return value
        }
        return null
    }

    private val factory: SAXParserFactory by lazy {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            // Внешние сущности и DTD не загружаем (защита от XXE); на Android часть флагов неизвестна — не страшно.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
    }

    /** false — разбор прерван (ошибка XML или остановка по бюджету); собранное до этого сохраняется. */
    fun parse(data: ByteArray, handler: XmlElementHandler): Boolean = try {
        val parser = synchronized(this) { factory.newSAXParser() }
        parser.parse(ByteArrayInputStream(data), handler)
        true
    } catch (_: Exception) {
        false
    }
}

/** База для обработчиков: имена элементов без префикса пространства имён. */
internal abstract class XmlElementHandler : DefaultHandler() {
    open fun start(name: String, attributes: Map<String, String>) {}
    open fun end(name: String) {}
    open fun text(string: String) {}

    /** Остановить разбор (как abortParsing у XMLParser). */
    protected fun abortParsing(): Nothing = throw SAXException("aborted")

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        val qualified = qName?.takeIf { it.isNotEmpty() } ?: localName ?: ""
        val map = HashMap<String, String>()
        if (attributes != null) {
            for (i in 0 until attributes.length) {
                val key = attributes.getQName(i)?.takeIf { it.isNotEmpty() } ?: attributes.getLocalName(i) ?: continue
                map[key] = attributes.getValue(i) ?: ""
            }
        }
        start(XmlSupport.localName(qualified), map)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val qualified = qName?.takeIf { it.isNotEmpty() } ?: localName ?: ""
        end(XmlSupport.localName(qualified))
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text(String(ch, start, length))
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
}

// MARK: - OPC: связи и служебные части

internal class PackageRelationship(val id: String, val type: String, val target: String, val isExternal: Boolean)

internal class RelationshipsHandler : XmlElementHandler() {
    val relationships = ArrayList<PackageRelationship>()
    override fun start(name: String, attributes: Map<String, String>) {
        if (name != "Relationship") return
        relationships.add(PackageRelationship(
            id = attributes["Id"] ?: "",
            type = attributes["Type"] ?: "",
            target = attributes["Target"] ?: "",
            isExternal = (attributes["TargetMode"] ?: "").lowercase() == "external",
        ))
    }
}

internal class ElementTextCollector(private val names: Set<String>) : XmlElementHandler() {
    val values = HashMap<String, String>()
    private var current: String? = null
    private val buffer = StringBuilder()

    override fun start(name: String, attributes: Map<String, String>) {
        if (current == null && name in names && !values.containsKey(name)) {
            current = name
            buffer.setLength(0)
        }
    }

    override fun end(name: String) {
        val active = current
        if (active != null && active == name) {
            values[active] = buffer.toString().trim()
            current = null
        }
    }

    override fun text(string: String) {
        if (current != null) buffer.append(string)
    }
}

internal class AttributeCollector(private val element: String, private val attributeName: String) : XmlElementHandler() {
    val values = ArrayList<String>()
    override fun start(name: String, attributes: Map<String, String>) {
        if (name == element) XmlSupport.attribute(attributes, attributeName)?.let { values.add(it) }
    }
}

// MARK: - Word / PowerPoint: текст, заголовки, списки, таблицы

internal class WordStylesHandler : XmlElementHandler() {
    private class Style {
        var name = ""
        var basedOn = ""
        var outline: Int? = null
    }

    private val styles = HashMap<String, Style>()
    private var currentID: String? = null
    private var current = Style()

    override fun start(name: String, attributes: Map<String, String>) {
        when (name) {
            "style" -> {
                currentID = XmlSupport.attribute(attributes, "styleId")
                current = Style()
            }
            "name" -> if (currentID != null) current.name = XmlSupport.attribute(attributes, "val") ?: ""
            "basedOn" -> if (currentID != null) current.basedOn = XmlSupport.attribute(attributes, "val") ?: ""
            "outlineLvl" -> if (currentID != null) XmlSupport.attribute(attributes, "val")?.toIntOrNull()?.let { current.outline = it }
        }
    }

    override fun end(name: String) {
        if (name == "style") {
            currentID?.let { styles[it] = current }
            currentID = null
        }
    }

    /** Уровни заголовков с учётом наследования basedOn. */
    fun headingLevels(): Map<String, Int> {
        val result = HashMap<String, Int>()
        for (id in styles.keys) {
            var cursor = id
            var depth = 0
            while (depth < 8) {
                val style = styles[cursor] ?: break
                val level = DocumentReader.headingLevel(style.name)
                if (level != null) { result[id] = level; break }
                val outline = style.outline
                if (outline != null && outline in 0..8) { result[id] = minOf(outline + 1, 6); break }
                if (style.basedOn.isEmpty()) break
                cursor = style.basedOn
                depth += 1
            }
        }
        return result
    }
}

/** Общий разбор WordprocessingML и DrawingML: у обоих p/t/tbl/tr/tc/br по локальным именам. */
internal class OfficeTextHandler : XmlElementHandler() {
    private class Paragraph {
        val text = StringBuilder()
        var styleID = ""
        var outline: Int? = null
        var isList = false
        var listLevel = 0
    }

    private class Table {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var span = 1
        var inCell = false
        var paragraphDepth = 0
    }

    companion object {
        // Fallback дублирует mc:Choice; *Change — старые версии свойств при рецензировании
        private val skippedElements = setOf(
            "Fallback", "pPrChange", "rPrChange", "tblPrChange", "trPrChange", "tcPrChange", "sectPrChange", "moveFrom",
        )
    }

    var styleHeadingLevels: Map<String, Int> = emptyMap()
    var skipsFields = false
    private val out = StringBuilder()
    /** Примерный объём вывода в байтах UTF-8 — для остановки по бюджету без пересчёта всего текста. */
    private var outBytes = 0
    val output: String get() = out.toString()
    var tableCount = 0
        private set
    var truncated = false
        private set
    private val paragraphs = ArrayList<Paragraph>()
    private val tables = ArrayList<Table>()
    private var inText = false
    private var tabStopDepth = 0
    private var skipDepth = 0

    private fun isSkipped(name: String) = name in skippedElements || (skipsFields && name == "fld")

    override fun start(name: String, attributes: Map<String, String>) {
        if (isSkipped(name)) { skipDepth += 1; return }
        if (skipDepth != 0) return
        when (name) {
            "p" -> paragraphs.add(Paragraph())
            "t" -> inText = true
            "tab", "ptab" -> if (tabStopDepth == 0) append("\t")
            "tabs", "tabLst" -> tabStopDepth += 1
            "br", "cr" -> append("\n")
            "noBreakHyphen" -> append("-")
            "pStyle" -> paragraphs.lastOrNull()?.styleID = XmlSupport.attribute(attributes, "val") ?: ""
            "outlineLvl" -> XmlSupport.attribute(attributes, "val")?.toIntOrNull()?.let { level -> paragraphs.lastOrNull()?.outline = level }
            "numPr" -> paragraphs.lastOrNull()?.isList = true
            "ilvl" -> XmlSupport.attribute(attributes, "val")?.toIntOrNull()?.let { level ->
                paragraphs.lastOrNull()?.listLevel = level.coerceIn(0, 8)
            }
            // numId = 0 означает «без нумерации»
            "numId" -> if (XmlSupport.attribute(attributes, "val") == "0") paragraphs.lastOrNull()?.isList = false
            "tbl" -> {
                tables.add(Table().also { it.paragraphDepth = paragraphs.size })
                tableCount += 1
            }
            "tr" -> tables.lastOrNull()?.row = ArrayList()
            "tc" -> tables.lastOrNull()?.let { it.cell.setLength(0); it.span = 1; it.inCell = true }
            "gridSpan" -> XmlSupport.attribute(attributes, "val")?.toIntOrNull()?.let { span ->
                tables.lastOrNull()?.span = span.coerceIn(1, DocumentReader.MAXIMUM_TABLE_COLUMNS)
            }
        }
    }

    override fun end(name: String) {
        if (isSkipped(name)) { skipDepth = maxOf(0, skipDepth - 1); return }
        if (skipDepth != 0) return
        when (name) {
            "t" -> inText = false
            "tabs", "tabLst" -> tabStopDepth = maxOf(0, tabStopDepth - 1)
            "p" -> finishParagraph()
            "tc" -> tables.lastOrNull()?.let { table ->
                table.row.add(table.cell.toString())
                repeat(table.span - 1) { table.row.add("") }
                table.cell.setLength(0)
                table.span = 1
                table.inCell = false
            }
            "tr" -> tables.lastOrNull()?.let { table ->
                table.rows.add(table.row)
                table.row = ArrayList()
            }
            "tbl" -> finishTable()
        }
    }

    override fun text(string: String) {
        if (inText && skipDepth == 0) append(string)
    }

    private fun append(string: String) {
        paragraphs.lastOrNull()?.text?.append(string)
    }

    private fun finishParagraph() {
        if (paragraphs.isEmpty()) return
        val paragraph = paragraphs.removeAt(paragraphs.size - 1)
        val text = DocumentReader.trimWs(paragraph.text.toString())
        val table = tables.lastOrNull()
        if (table != null && table.inCell && table.paragraphDepth == paragraphs.size) {
            if (text.isNotEmpty()) {
                if (table.cell.isNotEmpty()) table.cell.append('\n')
                table.cell.append(text)
            }
            return
        }
        // Абзац внутри надписи (text box) — дописываем к внешнему
        val parent = paragraphs.lastOrNull()
        if (parent != null) {
            if (text.isNotEmpty()) {
                if (parent.text.isNotEmpty()) parent.text.append('\n')
                parent.text.append(text)
            }
            return
        }
        if (text.isEmpty()) { out.append('\n'); return }
        val level = headingLevel(paragraph)
        when {
            level != null -> out.append('\n').append("#".repeat(level)).append(' ').append(text).append('\n')
            paragraph.isList -> out.append("  ".repeat(paragraph.listLevel)).append("- ").append(text).append('\n')
            else -> out.append(text).append('\n')
        }
        outBytes += DocumentReader.utf8Length(text) + 8
        if (outBytes > DocumentReader.WORKING_BUDGET) {
            truncated = true
            abortParsing()
        }
    }

    private fun headingLevel(paragraph: Paragraph): Int? {
        if (paragraph.styleID.isNotEmpty()) {
            styleHeadingLevels[paragraph.styleID]?.let { return it }
            DocumentReader.headingLevel(paragraph.styleID)?.let { return it }
        }
        val outline = paragraph.outline
        if (outline != null && outline in 0..8) return minOf(outline + 1, 6)
        return null
    }

    private fun finishTable() {
        if (tables.isEmpty()) return
        val table = tables.removeAt(tables.size - 1)
        if (table.row.isNotEmpty()) {
            table.rows.add(table.row)
            table.row = ArrayList()
        }
        val parentTable = tables.lastOrNull()
        if (parentTable != null && parentTable.inCell && parentTable.paragraphDepth == paragraphs.size) {
            val flat = DocumentReader.flattenedTable(table.rows)
            if (flat.isNotEmpty()) {
                if (parentTable.cell.isNotEmpty()) parentTable.cell.append('\n')
                parentTable.cell.append(flat)
            }
            return
        }
        val paragraph = paragraphs.lastOrNull()
        if (paragraph != null) {
            val flat = DocumentReader.flattenedTable(table.rows)
            if (flat.isNotEmpty()) {
                if (paragraph.text.isNotEmpty()) paragraph.text.append('\n')
                paragraph.text.append(flat)
            }
            return
        }
        val markdown = DocumentReader.markdownTable(table.rows)
        if (markdown.isNotEmpty()) out.append('\n').append(markdown).append("\n\n")
    }
}

// MARK: - Excel: книга, общие строки, стили, листы

internal class WorkbookSheet(val name: String, val relationshipID: String, val hidden: Boolean)

internal class WorkbookHandler : XmlElementHandler() {
    val sheets = ArrayList<WorkbookSheet>()
    var date1904 = false
        private set

    override fun start(name: String, attributes: Map<String, String>) {
        when (name) {
            "sheet" -> {
                val state = XmlSupport.attribute(attributes, "state") ?: ""
                sheets.add(WorkbookSheet(
                    XmlSupport.attribute(attributes, "name") ?: "Лист ${sheets.size + 1}",
                    XmlSupport.relationshipID(attributes) ?: "",
                    state == "hidden" || state == "veryHidden",
                ))
            }
            "workbookPr" -> {
                val flag = (XmlSupport.attribute(attributes, "date1904") ?: "").lowercase()
                date1904 = flag == "1" || flag == "true"
            }
        }
    }
}

internal class SharedStringsHandler : XmlElementHandler() {
    val strings = ArrayList<String>()
    private val current = StringBuilder()
    private var inItem = false
    private var inText = false
    private var phoneticDepth = 0

    override fun start(name: String, attributes: Map<String, String>) {
        when (name) {
            "si" -> { current.setLength(0); inItem = true }
            "rPh" -> phoneticDepth += 1
            "t" -> if (inItem && phoneticDepth == 0) inText = true
        }
    }

    override fun end(name: String) {
        when (name) {
            "si" -> { strings.add(current.toString()); inItem = false }
            "rPh" -> phoneticDepth = maxOf(0, phoneticDepth - 1)
            "t" -> inText = false
        }
    }

    override fun text(string: String) {
        if (inText) current.append(string)
    }
}

internal class ExcelStylesHandler : XmlElementHandler() {
    private val formats = HashMap<Int, String>()
    private val cellFormats = ArrayList<Int>()
    private var inCellFormats = false

    override fun start(name: String, attributes: Map<String, String>) {
        when (name) {
            "numFmt" -> XmlSupport.attribute(attributes, "numFmtId")?.toIntOrNull()?.let {
                formats[it] = XmlSupport.attribute(attributes, "formatCode") ?: ""
            }
            "cellXfs" -> inCellFormats = true
            "xf" -> if (inCellFormats) cellFormats.add(XmlSupport.attribute(attributes, "numFmtId")?.toIntOrNull() ?: 0)
        }
    }

    override fun end(name: String) {
        if (name == "cellXfs") inCellFormats = false
    }

    /** Индексы стилей ячеек (атрибут s), означающих дату/время. */
    fun dateStyleIndexes(): Set<Int> {
        val result = HashSet<Int>()
        cellFormats.forEachIndexed { index, formatID ->
            if (DocumentReader.isDateFormat(formatID, formats[formatID])) result.add(index)
        }
        return result
    }
}

internal class SheetHandler(
    private val sharedStrings: List<String>,
    private val dateStyles: Set<Int>,
    private val date1904: Boolean,
) : XmlElementHandler() {
    /** Только непустые строки, не больше MAXIMUM_TABLE_ROWS. */
    val rows = ArrayList<Map<Int, String>>()
    var nonEmptyRowCount = 0
        private set
    var columnsTruncated = false
        private set
    private var currentRow = HashMap<Int, String>()
    private var nextColumn = 0
    private var cellColumn = 0
    private var cellType = "n"
    private var cellStyle = 0
    private val value = StringBuilder()
    private val inlineText = StringBuilder()
    private var inValue = false
    private var inInlineString = false
    private var inInlineText = false
    private var phoneticDepth = 0

    override fun start(name: String, attributes: Map<String, String>) {
        when (name) {
            "row" -> { currentRow = HashMap(); nextColumn = 0 }
            "c" -> {
                cellColumn = attributes["r"]?.let { DocumentReader.columnIndex(it) } ?: nextColumn
                cellType = attributes["t"] ?: "n"
                cellStyle = attributes["s"]?.toIntOrNull() ?: 0
                value.setLength(0)
                inlineText.setLength(0)
            }
            "v" -> inValue = true
            "is" -> inInlineString = true
            "rPh" -> phoneticDepth += 1
            "t" -> if (inInlineString && phoneticDepth == 0) inInlineText = true
        }
    }

    override fun end(name: String) {
        when (name) {
            "v" -> inValue = false
            "t" -> inInlineText = false
            "is" -> inInlineString = false
            "rPh" -> phoneticDepth = maxOf(0, phoneticDepth - 1)
            "c" -> {
                val display = cellDisplay().trim()
                if (display.isNotEmpty()) {
                    if (cellColumn < DocumentReader.MAXIMUM_TABLE_COLUMNS) currentRow[cellColumn] = display
                    else columnsTruncated = true
                }
                nextColumn = cellColumn + 1
            }
            "row" -> {
                if (currentRow.isNotEmpty()) {
                    nonEmptyRowCount += 1
                    if (rows.size < DocumentReader.MAXIMUM_TABLE_ROWS) rows.add(currentRow)
                }
                currentRow = HashMap()
            }
        }
    }

    override fun text(string: String) {
        if (inValue) value.append(string) else if (inInlineText) inlineText.append(string)
    }

    private fun cellDisplay(): String {
        val raw = value.toString().trim()
        return when (cellType) {
            "s" -> raw.toIntOrNull()?.takeIf { it in sharedStrings.indices }?.let { sharedStrings[it] } ?: ""
            "inlineStr" -> inlineText.toString()
            "b" -> if (raw.isEmpty()) "" else if (raw == "1") "TRUE" else "FALSE"
            "str", "e", "d" -> value.toString()
            else -> {
                if (raw.isEmpty()) return inlineText.toString()
                if (cellStyle in dateStyles) {
                    raw.toDoubleOrNull()?.let { serial -> DocumentReader.excelDate(serial, date1904)?.let { return it } }
                }
                DocumentReader.formatNumber(raw)
            }
        }
    }
}

// MARK: - PowerPoint: порядок слайдов

internal class PresentationHandler : XmlElementHandler() {
    val slideRelationshipIDs = ArrayList<String>()
    override fun start(name: String, attributes: Map<String, String>) {
        if (name == "sldId") XmlSupport.relationshipID(attributes)?.let { slideRelationshipIDs.add(it) }
    }
}

// MARK: - OpenDocument

internal class OpenDocumentHandler(val mode: Mode) : XmlElementHandler() {
    enum class Mode { TEXT, SPREADSHEET, PRESENTATION }

    private class Paragraph {
        val text = StringBuilder()
        var headingLevel = 0
        var listLevel = -1
    }

    private class Table {
        var name = ""
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var rowRepeat = 1
        val cell = StringBuilder()
        var cellRepeat = 1
        var cellValue = ""
        var inCell = false
        var totalRows = 0
        var columnsTruncated = false
        var paragraphDepth = 0
    }

    companion object {
        // Заметки докладчика, комментарии и история правок не нужны
        private val skippedElements = setOf("notes", "annotation", "tracked-changes")
    }

    private val out = StringBuilder()
    /** Примерный объём вывода в байтах UTF-8 — для остановки по бюджету без пересчёта всего текста. */
    private var outBytes = 0
    val output: String get() = out.toString()
    var sheetCount = 0
        private set
    var slideCount = 0
        private set
    var rowCount = 0
        private set
    var truncated = false
        private set
    private val paragraphs = ArrayList<Paragraph>()
    private val tables = ArrayList<Table>()
    private var listDepth = 0
    private var pendingListItem = false
    private var skipDepth = 0

    override fun start(name: String, attributes: Map<String, String>) {
        if (name in skippedElements) { skipDepth += 1; return }
        if (skipDepth != 0) return
        when (name) {
            "p", "h" -> {
                val paragraph = Paragraph()
                if (name == "h") {
                    val level = XmlSupport.attribute(attributes, "outline-level")?.toIntOrNull() ?: 1
                    paragraph.headingLevel = level.coerceIn(1, 6)
                }
                if (pendingListItem) {
                    paragraph.listLevel = (listDepth - 1).coerceIn(0, 8)
                    pendingListItem = false
                }
                paragraphs.add(paragraph)
            }
            "list" -> listDepth += 1
            "list-item" -> pendingListItem = true
            "list-header" -> pendingListItem = false
            "s" -> {
                val count = (XmlSupport.attribute(attributes, "c")?.toIntOrNull() ?: 1).coerceIn(1, 100)
                append(" ".repeat(count))
            }
            "tab" -> append("\t")
            "line-break" -> append("\n")
            "page" -> if (mode == Mode.PRESENTATION) {
                slideCount += 1
                out.append("\n## Слайд ").append(slideCount).append('\n')
            }
            "table" -> tables.add(Table().also {
                it.name = XmlSupport.attribute(attributes, "name") ?: ""
                it.paragraphDepth = paragraphs.size
            })
            "table-row" -> tables.lastOrNull()?.let { table ->
                table.row = ArrayList()
                val repeated = XmlSupport.attribute(attributes, "number-rows-repeated")?.toIntOrNull() ?: 1
                table.rowRepeat = repeated.coerceIn(1, 100)
            }
            "table-cell", "covered-table-cell" -> tables.lastOrNull()?.let { table ->
                table.cell.setLength(0)
                val repeated = XmlSupport.attribute(attributes, "number-columns-repeated")?.toIntOrNull() ?: 1
                table.cellRepeat = repeated.coerceIn(1, DocumentReader.MAXIMUM_TABLE_COLUMNS)
                table.cellValue = XmlSupport.attribute(attributes, "value")
                    ?: XmlSupport.attribute(attributes, "date-value")
                    ?: XmlSupport.attribute(attributes, "time-value")
                    ?: XmlSupport.attribute(attributes, "boolean-value")
                    ?: ""
                table.inCell = true
            }
        }
    }

    override fun end(name: String) {
        if (name in skippedElements) { skipDepth = maxOf(0, skipDepth - 1); return }
        if (skipDepth != 0) return
        when (name) {
            "p", "h" -> finishParagraph()
            "list" -> listDepth = maxOf(0, listDepth - 1)
            "table-cell", "covered-table-cell" -> finishCell()
            "table-row" -> finishRow()
            "table" -> finishTable()
        }
    }

    override fun text(string: String) {
        if (skipDepth == 0) append(string)
    }

    private fun append(string: String) {
        paragraphs.lastOrNull()?.text?.append(string)
    }

    private fun finishParagraph() {
        if (paragraphs.isEmpty()) return
        val paragraph = paragraphs.removeAt(paragraphs.size - 1)
        val text = DocumentReader.trimWs(paragraph.text.toString())
        val table = tables.lastOrNull()
        if (table != null && table.inCell && table.paragraphDepth == paragraphs.size) {
            if (text.isNotEmpty()) {
                if (table.cell.isNotEmpty()) table.cell.append('\n')
                table.cell.append(text)
            }
            return
        }
        // Сноска или подпись внутри абзаца
        val parent = paragraphs.lastOrNull()
        if (parent != null) {
            if (text.isNotEmpty()) parent.text.append(" [").append(text).append("]")
            return
        }
        if (text.isEmpty()) { out.append('\n'); return }
        when {
            paragraph.headingLevel > 0 -> out.append('\n').append("#".repeat(paragraph.headingLevel)).append(' ').append(text).append('\n')
            paragraph.listLevel >= 0 -> out.append("  ".repeat(paragraph.listLevel)).append("- ").append(text).append('\n')
            else -> out.append(text).append('\n')
        }
        outBytes += DocumentReader.utf8Length(text) + 8
        if (outBytes > DocumentReader.WORKING_BUDGET) {
            truncated = true
            abortParsing()
        }
    }

    private fun finishCell() {
        val table = tables.lastOrNull() ?: return
        if (!table.inCell) return
        var content = table.cell.toString().trim()
        if (content.isEmpty()) content = table.cellValue
        val room = DocumentReader.MAXIMUM_TABLE_COLUMNS - table.row.size
        if (room > 0) {
            repeat(minOf(table.cellRepeat, room)) { table.row.add(content) }
        } else if (content.isNotEmpty()) {
            table.columnsTruncated = true
        }
        table.cell.setLength(0)
        table.inCell = false
    }

    private fun finishRow() {
        val table = tables.lastOrNull() ?: return
        val row = table.row
        table.row = ArrayList()
        if (row.none { it.isNotEmpty() }) return
        repeat(table.rowRepeat) {
            table.totalRows += 1
            if (table.rows.size < DocumentReader.MAXIMUM_TABLE_ROWS) table.rows.add(row)
        }
    }

    private fun finishTable() {
        if (tables.isEmpty()) return
        val table = tables.removeAt(tables.size - 1)
        val parentTable = tables.lastOrNull()
        if (parentTable != null && parentTable.inCell && parentTable.paragraphDepth == paragraphs.size) {
            val flat = DocumentReader.flattenedTable(table.rows)
            if (flat.isNotEmpty()) {
                if (parentTable.cell.isNotEmpty()) parentTable.cell.append('\n')
                parentTable.cell.append(flat)
            }
            return
        }
        val paragraph = paragraphs.lastOrNull()
        if (paragraph != null) {
            val flat = DocumentReader.flattenedTable(table.rows)
            if (flat.isNotEmpty()) paragraph.text.append(" [").append(flat).append("]")
            return
        }
        val markdown = DocumentReader.markdownTable(table.rows)
        if (mode == Mode.SPREADSHEET) {
            sheetCount += 1
            rowCount += table.totalRows
            out.append("\n## Лист «").append(table.name).append("»\n\n").append(if (markdown.isEmpty()) "(пустой лист)" else markdown)
            if (table.totalRows > table.rows.size) out.append("\n… (показаны первые ${table.rows.size} строк из ${table.totalRows})")
            if (table.columnsTruncated) out.append("\n… (показаны первые ${DocumentReader.MAXIMUM_TABLE_COLUMNS} столбцов)")
            out.append("\n\n")
        } else if (markdown.isNotEmpty()) {
            out.append('\n').append(markdown).append("\n\n")
        }
    }
}

// MARK: - EPUB: OPF

internal class PackageDocumentHandler : XmlElementHandler() {
    val manifest = HashMap<String, String>()
    val spine = ArrayList<String>()
    private val titleBuffer = StringBuilder()
    val title: String get() = titleBuffer.toString()
    private var inTitle = false
    private var titleDone = false

    override fun start(name: String, attributes: Map<String, String>) {
        when (name) {
            "item" -> {
                val id = attributes["id"]
                val href = attributes["href"]
                if (id != null && href != null) manifest[id] = href
            }
            "itemref" -> attributes["idref"]?.let { spine.add(it) }
            "title" -> if (!titleDone) inTitle = true
        }
    }

    override fun end(name: String) {
        if (name == "title" && inTitle) { inTitle = false; titleDone = true }
    }

    override fun text(string: String) {
        if (inTitle) titleBuffer.append(string)
    }
}
