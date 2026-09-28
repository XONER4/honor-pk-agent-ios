package com.honerai.app.device

import java.nio.charset.Charset

// HTML → текст (порт HTMLTextScanner из DocumentReader.swift) и RTF → текст.
// На iPhone RTF читает NSAttributedString; на Android такого нет — простой разбор
// управляющих слов: группы, служебные «назначения» (шрифты, цвета, картинки) пропускаются.

/** Однопроходный сканер по кодовым точкам: теги → переводы строк, script/style выбрасываются. */
internal class HtmlTextScanner(html: String) {
    private companion object {
        val blockTags = setOf(
            "p", "div", "section", "article", "header", "footer", "blockquote", "ul", "ol", "table", "tr",
            "dd", "dt", "dl", "figure", "figcaption", "nav", "aside", "main", "form", "hr", "title",
            "address", "fieldset", "details", "summary", "caption", "thead", "tbody", "tfoot",
        )
        val skippedTags = setOf("script", "style", "noscript", "template", "svg", "math", "iframe", "object")
    }

    private val scalars: IntArray = html.codePoints().toArray()
    private var index = 0
    private val output = StringBuilder()
    private var lastWasSpace = true
    private var preformatted = 0

    fun run(): String {
        while (index < scalars.size) {
            val scalar = scalars[index]
            when (scalar) {
                '<'.code -> handleTag()
                '&'.code -> handleEntity()
                else -> { emit(scalar); index += 1 }
            }
        }
        return output.toString().split('\n').joinToString("\n") { DocumentReader.trimWs(it) }
    }

    private fun emit(scalar: Int) {
        val isSpace = scalar == ' '.code || scalar == '\t'.code || scalar == '\n'.code || scalar == '\r'.code ||
            scalar == 0x0C || scalar == 0xA0
        if (isSpace) {
            if (preformatted > 0 && scalar != 0xA0) {
                output.appendCodePoint(scalar)
                lastWasSpace = true
            } else if (!lastWasSpace) {
                output.append(' ')
                lastWasSpace = true
            }
            return
        }
        output.appendCodePoint(scalar)
        lastWasSpace = false
    }

    /** Не больше одного перевода строки подряд; в начале текста — без него. */
    private fun newline(prefix: String = "") {
        if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
        output.append(prefix)
        lastWasSpace = true
    }

    private fun handleTag() {
        val start = index
        if (matches("<!--", start)) {
            val end = find("-->", start + 4)
            index = if (end != null) end + 3 else scalars.size
            return
        }
        if (matches("<![CDATA[", start)) {
            val end = find("]]>", start + 9) ?: scalars.size
            var cursor = start + 9
            while (cursor < end) { emit(scalars[cursor]); cursor += 1 }
            index = minOf(end + 3, scalars.size)
            return
        }
        var cursor = start + 1
        var closing = false
        if (cursor < scalars.size && scalars[cursor] == '/'.code) { closing = true; cursor += 1 }
        if (cursor >= scalars.size) { emit('<'.code); index = start + 1; return }
        val first = scalars[cursor]
        if (first == '!'.code || first == '?'.code) { index = tagEnd(cursor); return }
        if (!isAsciiLetter(first)) {
            if (closing) index = tagEnd(cursor) else { emit('<'.code); index = start + 1 }
            return
        }
        val nameBuilder = StringBuilder()
        while (cursor < scalars.size && isNameScalar(scalars[cursor])) {
            nameBuilder.appendCodePoint(lowercased(scalars[cursor]))
            cursor += 1
        }
        var name = nameBuilder.toString()
        val colon = name.lastIndexOf(':')
        if (colon >= 0) name = name.substring(colon + 1)
        val end = tagEnd(cursor)
        val selfClosing = end >= 2 && end - 2 >= start && scalars[end - 1] == '>'.code && scalars[end - 2] == '/'.code
        index = end
        if (closing) closeTag(name) else openTag(name, selfClosing)
    }

    private fun openTag(name: String, selfClosing: Boolean) {
        if (name in skippedTags) {
            if (!selfClosing) index = skipPastClosingTag(name)
            return
        }
        headingLevel(name)?.let { level -> newline("#".repeat(level) + " "); return }
        when (name) {
            "br" -> newline()
            "li" -> newline("- ")
            "pre" -> { preformatted += 1; newline() }
            else -> if (name in blockTags) newline()
        }
    }

    private fun closeTag(name: String) {
        if (headingLevel(name) != null) { newline(); return }
        when (name) {
            "pre" -> { preformatted = maxOf(0, preformatted - 1); newline() }
            "td", "th" -> { output.append('\t'); lastWasSpace = true }
            "li" -> newline()
            else -> if (name in blockTags) newline()
        }
    }

    private fun handleEntity() {
        var cursor = index + 1
        val name = StringBuilder()
        while (cursor < scalars.size && cursor - index <= 32) {
            val scalar = scalars[cursor]
            if (scalar == ';'.code) break
            if (!(isAsciiLetter(scalar) || isAsciiDigit(scalar) || scalar == '#'.code)) break
            name.appendCodePoint(scalar)
            cursor += 1
        }
        val decoded = if (cursor < scalars.size && scalars[cursor] == ';'.code && name.isNotEmpty()) {
            DocumentReader.decodeEntity(name.toString())
        } else null
        if (decoded != null) {
            decoded.codePoints().forEach { emit(it) }
            index = cursor + 1
        } else {
            emit('&'.code)
            index += 1
        }
    }

    private fun headingLevel(name: String): Int? {
        if (name.length != 2 || name[0] != 'h') return null
        val level = name[1] - '0'
        return if (level in 1..6) level else null
    }

    /** Позиция сразу после ">" с учётом кавычек в атрибутах. */
    private fun tagEnd(position: Int): Int {
        var cursor = position
        var quote = -1
        val limit = minOf(scalars.size, position + 8_192)
        while (cursor < limit) {
            val scalar = scalars[cursor]
            if (quote >= 0) {
                if (scalar == quote) quote = -1
            } else if (scalar == '"'.code || scalar == '\''.code) {
                quote = scalar
            } else if (scalar == '>'.code) {
                return cursor + 1
            }
            cursor += 1
        }
        // Незакрытая кавычка: первый ">" без учёта кавычек
        cursor = position
        while (cursor < scalars.size) {
            if (scalars[cursor] == '>'.code) return cursor + 1
            cursor += 1
        }
        return scalars.size
    }

    private fun skipPastClosingTag(name: String): Int {
        val target = name.codePoints().toArray()
        var cursor = index
        while (cursor + 1 < scalars.size) {
            if (scalars[cursor] == '<'.code && scalars[cursor + 1] == '/'.code) {
                var matched = true
                for (offset in target.indices) {
                    val position = cursor + 2 + offset
                    if (position >= scalars.size || lowercased(scalars[position]) != target[offset]) { matched = false; break }
                }
                if (matched) return tagEnd(cursor + 2 + target.size)
            }
            cursor += 1
        }
        return scalars.size
    }

    private fun matches(pattern: String, position: Int): Boolean {
        var cursor = position
        for (expected in pattern) {
            if (cursor >= scalars.size || lowercased(scalars[cursor]) != lowercased(expected.code)) return false
            cursor += 1
        }
        return true
    }

    private fun find(pattern: String, from: Int): Int? {
        val target = pattern.codePoints().toArray()
        if (target.isEmpty()) return null
        var cursor = maxOf(from, 0)
        while (cursor + target.size <= scalars.size) {
            var matched = true
            for (offset in target.indices) if (scalars[cursor + offset] != target[offset]) { matched = false; break }
            if (matched) return cursor
            cursor += 1
        }
        return null
    }

    private fun isAsciiLetter(s: Int) = s in 'A'.code..'Z'.code || s in 'a'.code..'z'.code
    private fun isAsciiDigit(s: Int) = s in '0'.code..'9'.code
    private fun isNameScalar(s: Int) = isAsciiLetter(s) || isAsciiDigit(s) || s == '-'.code || s == ':'.code || s == '_'.code
    private fun lowercased(s: Int) = if (s in 'A'.code..'Z'.code) s + 32 else s
}

/** RTF → простой текст: абзацы, табуляции, \uN, \'hh в кодировке документа. */
internal object RtfText {
    /** Группы-«назначения», текст которых не показывается. */
    private val skippedDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "headerf", "footerl", "footerr", "footerf", "listtable", "listoverridetable", "rsidtbl", "generator",
        "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "mmathPr", "pgdsctbl",
        "filetbl", "revtbl", "fldinst", "bkmkstart", "bkmkend", "shppict", "nonshppict", "blipuid", "fontemb",
        "fontfile", "userprops", "docvar", "wgrffmtfilter", "passwordhash", "xe", "tc", "listtext",
    )

    private class GroupState(var skip: Boolean, var unicodeSkip: Int)

    fun plainText(source: String): String {
        val out = StringBuilder()
        val stack = ArrayList<GroupState>()
        var state = GroupState(skip = false, unicodeSkip = 1)
        var charset: Charset = Charset.forName("windows-1252")
        var pendingSkip = 0 // сколько символов пропустить после \uN
        var i = 0
        val n = source.length

        fun emitText(text: String) {
            if (state.skip) return
            out.append(text)
        }

        while (i < n) {
            val c = source[i]
            when (c) {
                '{' -> {
                    stack.add(state)
                    state = GroupState(state.skip, state.unicodeSkip)
                    i++
                }
                '}' -> {
                    state = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else state
                    pendingSkip = 0
                    i++
                }
                '\\' -> {
                    if (i + 1 >= n) { i++; continue }
                    val next = source[i + 1]
                    when {
                        next == '\\' || next == '{' || next == '}' -> {
                            if (pendingSkip > 0) pendingSkip-- else emitText(next.toString())
                            i += 2
                        }
                        next == '\'' -> {
                            // \'hh — байт в кодировке документа
                            val hex = if (i + 4 <= n) source.substring(i + 2, i + 4) else ""
                            val byte = hex.toIntOrNull(16)
                            i += 4
                            if (pendingSkip > 0) { pendingSkip--; continue }
                            if (byte != null) emitText(String(byteArrayOf(byte.toByte()), charset))
                        }
                        next == '*' -> { state.skip = true; i += 2 }
                        next == '~' -> { emitText(" "); i += 2 }
                        next == '_' -> { emitText("-"); i += 2 }
                        next == '-' -> i += 2
                        next == '\n' || next == '\r' -> { emitText("\n"); i += 2 }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < n && source[j].isLetter() && source[j].code < 128) j++
                            val word = source.substring(i + 1, j)
                            var k = j
                            if (k < n && (source[k] == '-' || source[k].isDigit())) {
                                k++
                                while (k < n && source[k].isDigit()) k++
                            }
                            val param = source.substring(j, k).toIntOrNull()
                            if (k < n && source[k] == ' ') k++ // пробел-разделитель съедается
                            i = k
                            if (word == "bin" && param != null && param > 0) { i += param; continue }
                            // Управляющее слово тоже считается «символом» замены после \uN.
                            if (pendingSkip > 0 && word != "u") { pendingSkip--; continue }
                            handleWord(word, param, state, out, { charset = it }, { pendingSkip = it })
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else emitText(c.toString())
                    i++
                }
            }
        }
        return out.toString().replace(" ", " ")
    }

    private fun handleWord(
        word: String,
        param: Int?,
        state: GroupState,
        out: StringBuilder,
        setCharset: (Charset) -> Unit,
        setPendingSkip: (Int) -> Unit,
    ) {
        if (word in skippedDestinations) { state.skip = true; return }
        val visible = !state.skip
        when (word) {
            "ansicpg" -> param?.let { code ->
                runCatching { Charset.forName("windows-$code") }.getOrNull()?.let(setCharset)
            }
            "mac" -> runCatching { Charset.forName("x-MacRoman") }.getOrNull()?.let(setCharset)
            "uc" -> if (param != null) state.unicodeSkip = maxOf(0, param)
            "u" -> if (param != null) {
                val code = if (param < 0) param + 65_536 else param
                if (visible) out.append(code.toChar())
                setPendingSkip(state.unicodeSkip)
            }
            "par", "line", "sect", "page" -> if (visible) out.append('\n')
            "row" -> if (visible) out.append('\n')
            "tab", "cell" -> if (visible) out.append('\t')
            "emdash" -> if (visible) out.append('—')
            "endash" -> if (visible) out.append('–')
            "bullet" -> if (visible) out.append('•')
            "lquote" -> if (visible) out.append('‘')
            "rquote" -> if (visible) out.append('’')
            "ldblquote" -> if (visible) out.append('“')
            "rdblquote" -> if (visible) out.append('”')
            "emspace", "enspace", "qmspace" -> if (visible) out.append(' ')
        }
    }
}
