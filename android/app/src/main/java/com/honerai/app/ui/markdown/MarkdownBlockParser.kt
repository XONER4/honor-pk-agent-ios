package com.honerai.app.ui.markdown

import com.honerai.app.ui.questions.QuestionMedia

/**
 * Блочный разбор Markdown (порт MarkdownBlockParser с iOS): заголовки, абзацы,
 * списки, чек-листы, цитаты, код, карточки, вопросы, формулы, диаграммы,
 * таблицы с выравниванием и разделители. Чистый Kotlin — работает в фоне и в тестах.
 */
object MarkdownBlockParser {
    /** Не больше стольких вопросов в одном блоке ```ask. */
    const val MAX_QUESTIONS = 30

    /** Режем строго по «\n», сохраняя пустые строки: они разделяют абзацы. */
    fun splitLines(source: String): List<String> = source.split('\n')

    /**
     * [streaming] — текст ещё печатается. Тогда растущая таблица рисуется сразу
     * таблицей из законченных строк, а строка, которая ещё дописывается, появится,
     * когда будет готова.
     */
    fun parse(source: String, streaming: Boolean = false): List<MarkdownBlock> {
        val blocks = ArrayList<MarkdownBlock>()
        val lines = splitLines(source)
        var index = 0
        val buffer = ArrayList<String>()

        fun add(kind: BlockKind, text: String) {
            blocks.add(MarkdownBlock(blocks.size, kind, text))
        }

        fun flushParagraph() {
            if (buffer.isEmpty()) return
            val joined = buffer.joinToString("\n").trim()
            buffer.clear()
            if (joined.isNotEmpty()) add(BlockKind.Paragraph, joined)
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimWs()

            // Блок кода ```
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val language = trimmed.substring(3).trimWs()
                val codeLines = ArrayList<String>()
                index += 1
                while (index < lines.size && !lines[index].trimWs().startsWith("```")) {
                    codeLines.add(lines[index])
                    index += 1
                }
                // Блок закрыт, если нашлась закрывающая рамка — даже когда она последняя строка.
                val fenceClosed = index < lines.size
                if (fenceClosed) index += 1
                val body = codeLines.joinToString("\n")
                val marker = language.lowercase()
                when {
                    marker == "copy" -> add(BlockKind.CopyBlock, body)
                    // Диаграмма рисуется нативно, без веб-вью.
                    marker == "mermaid" -> add(BlockKind.Diagram(body), body)
                    // Незакрытый блок вопросов тоже показываем: варианты видны сразу,
                    // флаг open не даёт карточке принимать ответы до конца печати.
                    marker == "ask" || marker == "questions" ->
                        add(BlockKind.Ask(parseQuestions(body), open = !fenceClosed), body)
                    marker.startsWith("card") -> {
                        val style = if (marker.contains(':')) {
                            marker.split(':').lastOrNull { it.isNotEmpty() } ?: "info"
                        } else "info"
                        add(BlockKind.Card(style, ""), body)
                    }
                    else -> add(BlockKind.Code(language), body)
                }
                continue
            }

            // Блок формулы $$ … $$
            if (trimmed.startsWith("$$")) {
                flushParagraph()
                val body = ArrayList<String>()
                val afterOpen = trimmed.substring(2)
                if (afterOpen.endsWith("$$") && afterOpen.length > 2) {
                    body.add(afterOpen.dropLast(2))
                    index += 1
                } else {
                    if (afterOpen.isNotEmpty()) body.add(afterOpen)
                    index += 1
                    while (index < lines.size) {
                        val mathLine = lines[index]
                        if (mathLine.contains("$$")) {
                            val head = mathLine.substringBefore("$$")
                            if (head.trimWs().isNotEmpty()) body.add(head)
                            index += 1
                            break
                        }
                        body.add(mathLine)
                        index += 1
                    }
                }
                val expression = body.joinToString(" ").trim()
                if (expression.isNotEmpty()) add(BlockKind.MathBlock(expression), expression)
                continue
            }

            val setext = isSetextUnderline(lines, index)

            // Горизонтальный разделитель. «---» под строкой списка (текста в абзаце нет)
            // — тоже разделитель, а не сырой текст.
            if (isDivider(trimmed) && (!setext || buffer.isEmpty())) {
                flushParagraph()
                add(BlockKind.Divider, "")
                index += 1
                continue
            }

            // Setext-заголовок: строка текста, подчёркнутая ===== (H1) или ----- (H2)
            if (setext && buffer.isNotEmpty()) {
                val title = buffer.joinToString(" ").trim()
                buffer.clear()
                val level = if (trimmed.startsWith("=")) 1 else 2
                add(BlockKind.Heading(level), title)
                index += 1
                continue
            }

            // Заголовок ATX
            val heading = headingLevel(trimmed)
            if (heading != null) {
                flushParagraph()
                add(BlockKind.Heading(heading), trimmed.substring(heading).trimWs())
                index += 1
                continue
            }

            // Таблица GFM: с внешними палочками и без, с разделителем «|» или «+».
            val alignments = if (index + 1 < lines.size) alignmentRow(lines[index + 1], trimmed) else null
            if (alignments != null && isTableHeader(trimmed)) {
                val headers = splitRow(trimmed)
                val rows = ArrayList<List<String>>()
                var cursor = index + 2
                while (cursor < lines.size && isTableRow(lines[cursor])) {
                    val cells = splitRow(lines[cursor].trimWs())
                    // Строка из пустых ячеек и палочек («| | |») данными не считается.
                    if (cells.any { hasText(it) }) rows.add(cells)
                    cursor += 1
                }
                if (streaming) {
                    // Строка на конце текста без закрывающей палочки ещё печатается.
                    val columns = maxOf(headers.size, 1)
                    if (cursor == lines.size && !source.endsWith("\n") && rows.isNotEmpty()) {
                        val lastLine = lines.last().trimWs()
                        if (isTableRow(lastLine) && rows.last() == splitRow(lastLine) &&
                            (!lastLine.endsWith("|") || splitRow(lastLine).size < columns)
                        ) {
                            rows.removeAt(rows.size - 1)
                        }
                    }
                    flushParagraph()
                    if (rows.isNotEmpty()) add(BlockKind.Table(headers, alignments, rows), "")
                    index = cursor
                    continue
                }
                // Таблица без единой строки данных не существует: текст шапки отдаём
                // обычным абзацем, а строку-разделитель пропускаем.
                if (rows.isEmpty()) {
                    val fallback = headers.filter { hasText(it) }.joinToString(" · ")
                    flushParagraph()
                    if (fallback.isNotEmpty()) buffer.add(fallback)
                    index = cursor
                    continue
                }
                flushParagraph()
                // Таблица, которая ещё дописывается, пока показывается текстом:
                // иначе она пересобиралась бы на каждом кадре и мигала.
                val columnCount = maxOf(headers.size, rows.maxOf { it.size })
                if (tableContinues(lines, cursor, source, columnCount)) {
                    add(BlockKind.Paragraph, lines.subList(index, lines.size).joinToString("\n"))
                    index = lines.size
                    continue
                }
                index = cursor
                add(BlockKind.Table(headers, alignments, rows), "")
                continue
            }

            // Остаток нераспознанной таблицы: строка из одних палочек, дефисов и двоеточий.
            if (isTextlessTableLine(trimmed)) {
                index += 1
                continue
            }

            // Чек-лист
            if (isChecklistItem(trimmed)) {
                flushParagraph()
                val items = ArrayList<ChecklistItem>()
                while (index < lines.size) {
                    val item = lines[index].trimWs()
                    if (!isChecklistItem(item)) break
                    val lower = item.lowercase()
                    val done = lower.startsWith("- [x]") || lower.startsWith("* [x]")
                    items.add(ChecklistItem(done, item.substring(5).trimWs()))
                    index += 1
                }
                add(BlockKind.Checklist(items), "")
                continue
            }

            // Маркированный или нумерованный список
            val first = listItem(trimmed)
            if (first != null) {
                flushParagraph()
                val items = ArrayList<String>()
                val ordered = first.first
                while (index < lines.size) {
                    val item = listItem(lines[index].trimWs()) ?: break
                    if (item.first != ordered) break
                    items.add(item.second)
                    index += 1
                }
                add(BlockKind.Bullets(items, ordered), "")
                continue
            }

            // Цитата
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoted = ArrayList<String>()
                while (index < lines.size) {
                    val quoteLine = lines[index].trimWs()
                    if (!quoteLine.startsWith(">")) break
                    quoted.add(quoteLine.substring(1).trimWs())
                    index += 1
                }
                add(BlockKind.Quote, quoted.joinToString("\n"))
                continue
            }

            // Пустая строка завершает абзац
            if (trimmed.isEmpty()) {
                flushParagraph()
                index += 1
                continue
            }

            buffer.add(line)
            index += 1
        }
        flushParagraph()
        return blocks
    }

    /**
     * Разбор блока ```ask — до 30 вопросов с вариантами.
     * Формат: `? Вопрос`, ниже варианты `- вариант`; правильный вариант теста —
     * `-* вариант` или `- [x] вариант`; строка `+ свой вариант` под вариантами
     * разрешает вписать свой ответ; `![](адрес)`, `@image`, `@audio`, `@video`,
     * `@file` — медиа к вопросу. Настройки блока читает QuestionnaireHeader.
     */
    fun parseQuestions(body: String): List<QuickQuestion> {
        val result = ArrayList<QuickQuestion>()
        var current: QuickQuestion? = null
        for (rawLine in body.split('\n')) {
            val line = rawLine.trimWs()
            if (line.isEmpty()) continue
            val question = current
            if (line.startsWith("+") && question != null) {
                // «+ свой вариант» — разрешить свой ответ на текущий вопрос.
                current = question.copy(allowsCustom = true)
            } else if (line.startsWith("?") || line.startsWith("+")) {
                if (question != null) result.add(question)
                if (result.size >= MAX_QUESTIONS) { current = null; break }
                current = QuickQuestion(text = line.substring(1).trimWs(), allowsCustom = line.startsWith("+"))
            } else if (line.startsWith("@")) {
                val media = QuestionMedia.parse(line)
                if (media != null && question != null) current = question.copy(media = question.media + media)
            } else if (line.startsWith("![")) {
                val media = QuestionMedia.markdownImage(line)
                if (media != null && question != null) current = question.copy(media = question.media + media)
            } else if (line.startsWith("-") || line.startsWith("*")) {
                if (question == null) continue
                val (text, correct) = parseOption(line.substring(1))
                if (text.isNotEmpty()) {
                    val position = question.options.size
                    current = question.copy(
                        options = question.options + text,
                        correct = if (correct) question.correct + position else question.correct,
                    )
                }
            } else if (question != null && question.options.isEmpty() && question.media.isEmpty()) {
                // продолжение текста вопроса
                current = question.copy(text = question.text + " " + line)
            }
        }
        current?.let { if (result.size < MAX_QUESTIONS) result.add(it) }
        return result.take(MAX_QUESTIONS)
    }

    private val correctMarkers = arrayOf("[x]", "[X]", "[х]", "[Х]", "✓", "✔", "✅")

    /** Вариант ответа и отметка «правильный» (`*`, `[x]`, `✓`). `**жирный**` — не отметка. */
    fun parseOption(raw: String): Pair<String, Boolean> {
        var text = raw.trimWs()
        var correct = false
        if (text.startsWith("[ ]")) {
            text = text.substring(3)
        } else {
            for (marker in correctMarkers) {
                if (text.startsWith(marker)) {
                    correct = true
                    text = text.substring(marker.length)
                    break
                }
            }
            if (!correct && text.startsWith("*") && !text.startsWith("**")) {
                correct = true
                text = text.substring(1)
            }
        }
        return text.trimWs() to correct
    }

    private fun isDivider(line: String): Boolean {
        var count = 0
        var kind = ' '
        for (c in line) {
            if (c == ' ') continue
            if (c != '-' && c != '*' && c != '_') return false
            if (kind == ' ') kind = c else if (c != kind) return false
            count++
        }
        return count >= 3
    }

    /** Строка `=====` или `-----` под текстом — подчёркивание Setext-заголовка. */
    private fun isSetextUnderline(lines: List<String>, index: Int): Boolean {
        if (index <= 0) return false
        val current = lines[index]
        // Быстрый отсев: подчёркивание начинается с «=» или «-».
        val firstChar = current.firstOrNull { !isWs(it) } ?: return false
        if (firstChar != '=' && firstChar != '-') return false
        val previous = lines[index - 1].trimWs()
        if (previous.isEmpty() || previous.startsWith("#") || previous.startsWith("|")) return false
        var count = 0
        var kind = ' '
        for (c in current) {
            if (c == ' ' || isWs(c)) continue
            if (c != '=' && c != '-') return false
            if (kind == ' ') kind = c else if (c != kind) return false
            count++
        }
        return count >= 3
    }

    private fun headingLevel(line: String): Int? {
        var level = 0
        while (level < line.length && line[level] == '#') level++
        if (level !in 1..6) return null
        if (level < line.length && line[level] != ' ') return null
        return level
    }

    private fun isChecklistItem(line: String): Boolean {
        if (line.length < 5) return false
        val c0 = line[0]
        if ((c0 != '-' && c0 != '*') || line[1] != ' ' || line[2] != '[' || line[4] != ']') return false
        val mark = line[3]
        return mark == ' ' || mark == 'x' || mark == 'X'
    }

    /** Пункт списка: (нумерованный?, текст). */
    private fun listItem(line: String): Pair<Boolean, String>? {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return false to line.substring(2).trimWs()
        }
        var cursor = 0
        while (cursor < line.length && line[cursor].isDigit()) cursor++
        if (cursor == 0 || cursor >= line.length) return null
        if (line[cursor] != '.' && line[cursor] != ')') return null
        val afterMarker = cursor + 1
        if (afterMarker >= line.length || line[afterMarker] != ' ') return null
        return true to line.substring(afterMarker + 1).trimWs()
    }

    /** Разделитель столбцов: палочка или «+» — что встретится раньше. */
    private fun separator(line: String): Char? {
        for (c in line) {
            if (c == '|') return '|'
            if (c == '+') return '+'
        }
        return null
    }

    /** Строка-шапка: есть разделитель, есть настоящий текст, это не строка выравнивания. */
    private fun isTableHeader(line: String): Boolean {
        if (separator(line) == null) return false
        if (!splitRow(line).any { hasText(it) }) return false
        return alignmentRow(line, line) == null
    }

    /**
     * Продолжается ли таблица после уже разобранных строк: следующая строка снова
     * с палочкой или последняя строка текста ещё не дописана.
     */
    private fun tableContinues(lines: List<String>, index: Int, source: String, columnCount: Int): Boolean {
        if (index < lines.size) {
            val next = lines[index].trimWs()
            if (isTableRow(next)) return true
            if (next.startsWith("|") || next.startsWith("+")) return true
        }
        if (!source.endsWith("\n") && lines.isNotEmpty()) {
            val trimmed = lines.last().trimWs()
            if (isTableRow(trimmed) && splitRow(trimmed).size < columnCount) return true
        }
        return false
    }

    private fun isTextlessTableLine(line: String): Boolean {
        if (separator(line) == null) return false
        return splitRow(line).none { hasText(it) }
    }

    /** Настоящий текст в ячейке: не пусто и не одни разделители («---», «:--:», «===»). */
    private fun hasText(cell: String): Boolean {
        for (c in cell) {
            if (c == ' ') continue
            if (c != '-' && c != ':' && c != '=') return true
        }
        return false
    }

    private fun isTableRow(line: String): Boolean {
        val trimmed = line.trimWs()
        return trimmed.isNotEmpty() && separator(trimmed) != null
    }

    /** Ячейки строки таблицы. Экранированная палочка «\|» остаётся внутри ячейки. */
    fun splitRow(line: String): List<String> {
        var value = line.trimWs()
        val marker = separator(value) ?: '|'
        if (value.isNotEmpty() && value[0] == marker) value = value.substring(1)
        if (value.isNotEmpty() && value.last() == marker && !value.endsWith("\\$marker")) value = value.dropLast(1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                if (next == marker) current.append(marker) else current.append(c).append(next)
                i += 2
                continue
            }
            if (c == marker) {
                cells.add(current.toString().trimWs())
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        cells.add(current.toString().trimWs())
        return cells
    }

    /**
     * Строка выравнивания под шапкой. [header] отличает таблицу из одного столбца
     * от обычного разделителя `---`: у разделителя сверху нет текста.
     */
    private fun alignmentRow(line: String, header: String?): List<TableAlignment>? {
        val trimmed = line.trimWs()
        if (separator(trimmed) == null || !trimmed.contains('-')) return null
        val cells = splitRow(trimmed)
        if (cells.isEmpty()) return null
        if (cells.size < 2 && !(header != null && splitRow(header).any { hasText(it) })) return null
        val result = ArrayList<TableAlignment>(cells.size)
        for (cell in cells) {
            val dashes = cell.replace(" ", "")
            if (dashes.isEmpty() || !dashes.all { it == '-' || it == ':' || it == '=' }) return null
            val left = dashes.startsWith(":")
            val right = dashes.endsWith(":")
            result.add(
                when {
                    left && right -> TableAlignment.CENTER
                    right -> TableAlignment.TRAILING
                    else -> TableAlignment.LEADING
                }
            )
        }
        return result
    }
}
