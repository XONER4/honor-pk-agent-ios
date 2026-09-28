package com.honerai.app.ui.markdown

/**
 * Как показывать текст, который ещё печатается (порт LiveMarkdown с iOS).
 *
 * Пока ответ растёт, в последней строке бывают незакрытые конструкции: `**жир`,
 * одиночная обратная кавычка, начало ссылки `[текст](htt`, открывающий тег цвета,
 * одинокий маркер списка или заголовка. Они на мгновение появлялись бы сырыми
 * символами и потом «перещёлкивались» в оформление — текст мигал бы.
 * Здесь такие хвосты временно скрываются; как только конструкция закрыта,
 * текст показывается уже оформленным.
 */
object LiveMarkdown {
    private val loneMarker = Regex("""^(#{1,6}|[-*+>]|\d+[.)])$""")

    /** Работает только с концом текста: на длинных ответах полный разбор съедал бы кадр. */
    fun displayable(text: String): String {
        if (text.isEmpty() || text.endsWith("\n")) return text
        // Внутри незакрытого блока кода ничего не трогаем: это код.
        if (fenceLineCount(text) % 2 == 1) return text
        val lastStart = text.lastIndexOf('\n') + 1
        val last = text.substring(lastStart)
        val trimmed = last.trimWs()

        // Строки таблицы до строки-разделителя — это ещё не таблица: не показываем
        // палочки, таблица появится сразу оформленной.
        if (trimmed.startsWith("|")) {
            var hasAlignment = isAlignmentRow(last)
            var blockStart = lastStart
            while (blockStart > 0) {
                val lineEnd = blockStart - 1
                val previousStart = if (lineEnd == 0) 0 else text.lastIndexOf('\n', lineEnd - 1) + 1
                val line = text.substring(previousStart, lineEnd)
                if (!line.trimWs().startsWith("|")) break
                if (isAlignmentRow(line)) hasAlignment = true
                blockStart = previousStart
            }
            if (hasAlignment) return text
            return withoutTrailingNewline(text.substring(0, blockStart))
        }
        // Одинокий маркер: «#», «-», «*», «>», «1.», начало разделителя «--».
        if (loneMarker.matches(trimmed) || (trimmed.isNotEmpty() && trimmed.all { it in "-=*_ " })) {
            return withoutTrailingNewline(text.substring(0, lastStart))
        }
        return text.substring(0, lastStart) + hideUnclosedMarkup(last)
    }

    private fun withoutTrailingNewline(text: String): String =
        if (text.endsWith("\n")) text.dropLast(1) else text

    /** Сколько строк начинается с ``` — один быстрый проход по символам. */
    fun fenceLineCount(text: String): Int {
        var count = 0
        var atLineStart = true
        var counting = false
        var ticks = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n') {
                atLineStart = true; counting = false; ticks = 0
                continue
            }
            if (atLineStart) {
                if (c == ' ' || c == '\t') continue
                atLineStart = false
                if (c == '`') { counting = true; ticks = 1 }
                continue
            }
            if (counting) {
                if (c == '`') {
                    ticks += 1
                    if (ticks == 3) { count += 1; counting = false }
                } else {
                    counting = false
                }
            }
        }
        return count
    }

    private fun isAlignmentRow(line: String): Boolean {
        val value = line.trimWs()
        if (!value.contains('-') || value.none { it == '|' }) return false
        return value.all { it in "|-: " }
    }

    internal fun occurrences(marker: String, text: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return count
            count += 1
            from = at + marker.length
        }
    }

    private fun removingLast(marker: String, text: String): String {
        val at = text.lastIndexOf(marker)
        if (at < 0) return text
        return text.removeRange(at, at + marker.length)
    }

    private fun hideUnclosedMarkup(source: String): String {
        var line = source
        // Незаконченная ссылка или картинка: «[текст», «[текст](htt».
        val open = line.lastIndexOf('[')
        if (open >= 0) {
            val tail = line.substring(open)
            val closedLabel = tail.contains(']')
            val hasTarget = tail.contains("](")
            val closedTarget = hasTarget && tail.endsWith(")")
            if (!closedLabel || (hasTarget && !closedTarget)) {
                var start = open
                if (start > 0 && line[start - 1] == '!') start -= 1
                line = line.substring(0, start)
            }
        }
        // Незакрытый фигурный тег: «{col», «{color:red}текст» без «{/color}».
        val brace = line.lastIndexOf('{')
        if (brace >= 0 && !line.substring(brace).contains('}')) line = line.substring(0, brace)
        for (tag in arrayOf("color", "bg")) {
            val opener = "{$tag:"
            val closer = "{/$tag}"
            if (occurrences(opener, line) > occurrences(closer, line)) {
                val at = line.lastIndexOf(opener)
                val end = line.indexOf('}', at + opener.length)
                if (at >= 0 && end >= 0) line = line.removeRange(at, end + 1)
            }
        }
        if (occurrences("{upper}", line) > occurrences("{/upper}", line)) line = removingLast("{upper}", line)
        // Незакрытые жирный, зачёркнутый, маркер, спойлер и код.
        for (marker in arrayOf("**", "~~", "==", "||", "__")) {
            if (occurrences(marker, line) % 2 == 1) line = removingLast(marker, line)
        }
        if (line.count { it == '`' } % 2 == 1) line = removingLast("`", line)
        return line
    }
}

/** Обрезка пробелов как `.whitespaces` на iOS (плюс \r — ответы иногда приходят с CRLF). */
internal fun String.trimWs(): String {
    var start = 0
    var end = length
    while (start < end && isWs(this[start])) start++
    while (end > start && isWs(this[end - 1])) end--
    return if (start == 0 && end == length) this else substring(start, end)
}

internal fun isWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == ' ' ||
    (c > '\u007F' && Character.isSpaceChar(c))
