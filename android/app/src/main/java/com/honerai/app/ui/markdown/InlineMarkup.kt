package com.honerai.app.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.honerai.app.data.WebSource

/** Цвета строчной разметки. Входят в ключ кэша: при смене темы строки пересобираются. */
@Immutable
data class InlinePalette(
    val foreground: Color,
    val secondary: Color,
    val accent: Color,
    val codeBackground: Color,
)

/**
 * Строчная разметка абзаца → AnnotatedString (порт AttributedString(markdown:) +
 * InlineStyleParser + linkedCitations + highlighted с iOS).
 *
 * Понимает `**жирный**`, `*курсив*`, `` `код` ``, `~~зачёркнутый~~`, `==маркер==`,
 * `||спойлер||`, `{color:red}…{/color}`, `{bg:yellow}…{/bg}`, `{upper}…{/upper}`,
 * `$формулы$`, ссылки `[текст](адрес)`, `<адрес>`, голые адреса и ссылки на
 * источники `[1]`. Разбор — один проход с рекурсией для вложенных стилей.
 */
object InlineMarkup {
    const val TAG_CITATION = "honer.cite"
    const val TAG_SPOILER = "honer.spoiler"

    private val namedColors: Map<String, Long> = mapOf(
        "white" to 0xFFFFFF, "белый" to 0xFFFFFF, "black" to 0x000000, "чёрный" to 0x000000, "черный" to 0x000000,
        "red" to 0xFF4D4D, "красный" to 0xFF4D4D, "green" to 0x3DDC84, "зелёный" to 0x3DDC84, "зеленый" to 0x3DDC84,
        "blue" to 0x4D94FF, "синий" to 0x4D94FF, "orange" to 0xFFA53D, "оранжевый" to 0xFFA53D,
        "purple" to 0xB07CFF, "фиолетовый" to 0xB07CFF, "gray" to 0x9A9A9A, "grey" to 0x9A9A9A, "серый" to 0x9A9A9A,
        "yellow" to 0xFFE066, "жёлтый" to 0xFFE066, "желтый" to 0xFFE066,
    )

    /** Цвет из `#rgb`, `#rrggbb` или имени (по-русски и по-английски). */
    fun color(token: String): Color? {
        val value = token.lowercase()
        if (value.startsWith("#")) {
            var hex = value.substring(1)
            if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
            if (hex.length != 6) return null
            val number = hex.toLongOrNull(16) ?: return null
            return Color(0xFF000000 or number)
        }
        val number = namedColors[value] ?: return null
        return Color(0xFF000000 or number)
    }

    val markColor = Color(0x59FFEB3B)
    val findColor = Color(0x59FFD60A)

    // ---- Кэш ----

    private val cache = object : LinkedHashMap<String, AnnotatedString>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?): Boolean = size > 800
    }

    /**
     * Разметка абзаца с кэшем: уже разобранный абзац не разбирается повторно
     * ни на одном кадре. [store] = false — для растущего хвоста печати (он всё равно
     * изменится на следующем кадре, незачем вытеснять им готовые абзацы).
     */
    fun cached(
        text: String, palette: InlinePalette, sources: List<WebSource>, sourcesKey: String,
        findQuery: String, store: Boolean = true,
    ): AnnotatedString {
        val key = buildString(text.length + findQuery.length + sourcesKey.length + 24) {
            append(text); append('\u001F'); append(findQuery); append('\u001F'); append(sourcesKey)
            append('\u001F'); append(palette.hashCode())
        }
        synchronized(cache) { cache[key]?.let { return it } }
        val value = build(text, palette, sources, findQuery)
        if (store) synchronized(cache) { cache[key] = value }
        return value
    }

    fun sourcesKey(sources: List<WebSource>): String = sources.joinToString(" ") { it.url }

    // ---- Разбор ----

    fun build(text: String, palette: InlinePalette, sources: List<WebSource> = emptyList(), findQuery: String = ""): AnnotatedString {
        val builder = AnnotatedString.Builder(text.length)
        val parser = Parser(text, builder, palette, sources)
        parser.parse(0, text.length)
        val result = builder.toAnnotatedString()
        return if (findQuery.isBlank()) result else highlighted(result, findQuery)
    }

    /** Подсветка найденного текста (поиск по чату). */
    fun highlighted(value: AnnotatedString, query: String): AnnotatedString {
        val needle = query.trim()
        if (needle.isEmpty()) return value
        val plain = value.text
        var cursor = plain.indexOf(needle, 0, ignoreCase = true)
        if (cursor < 0) return value
        val builder = AnnotatedString.Builder(value)
        var guard = 0
        while (cursor >= 0 && guard < 500) {
            builder.addStyle(SpanStyle(background = findColor), cursor, cursor + needle.length)
            cursor = plain.indexOf(needle, cursor + needle.length, ignoreCase = true)
            guard++
        }
        return builder.toAnnotatedString()
    }

    /**
     * Нажатия на источники и спойлеры: аннотации из кэша превращаются в ссылки
     * с обработчиком конкретного блока. Скрытые спойлеры закрашиваются.
     */
    fun interactive(
        value: AnnotatedString,
        revealed: Set<Int>,
        spoilerColor: Color,
        accent: Color,
        listener: LinkInteractionListener,
    ): AnnotatedString {
        val cites = value.getStringAnnotations(TAG_CITATION, 0, value.length)
        val spoilers = value.getStringAnnotations(TAG_SPOILER, 0, value.length)
        if (cites.isEmpty() && spoilers.isEmpty()) return value
        val builder = AnnotatedString.Builder(value)
        val citeStyles = TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))
        for (cite in cites) {
            builder.addLink(LinkAnnotation.Clickable(TAG_CITATION + ":" + cite.item, citeStyles, listener), cite.start, cite.end)
        }
        for (spoiler in spoilers) {
            val index = spoiler.item.toIntOrNull() ?: continue
            if (index !in revealed) {
                builder.addStyle(SpanStyle(color = spoilerColor, background = spoilerColor), spoiler.start, spoiler.end)
                builder.addLink(LinkAnnotation.Clickable(TAG_SPOILER + ":" + index, null, listener), spoiler.start, spoiler.end)
            }
        }
        return builder.toAnnotatedString()
    }

    /**
     * Плавное проявление хвоста печати: последние символы полупрозрачны, самый
     * новый едва виден (порт TextFade.tail).
     */
    fun fadeTail(value: AnnotatedString, length: Int, base: Color): AnnotatedString {
        val total = value.length
        val count = minOf(length, total)
        if (count <= 1) return value
        val builder = AnnotatedString.Builder(value)
        val spans = value.spanStyles
        val startIndex = total - count
        for (step in 0 until count) {
            val index = startIndex + step
            val opacity = 1f - 0.85f * (step + 1).toFloat() / (count + 1).toFloat()
            var color = base
            for (span in spans) {
                if (span.start <= index && index < span.end && span.item.color != Color.Unspecified) color = span.item.color
            }
            builder.addStyle(SpanStyle(color = color.copy(alpha = color.alpha * opacity)), index, index + 1)
        }
        return builder.toAnnotatedString()
    }

    private class Parser(
        val src: String,
        val out: AnnotatedString.Builder,
        val palette: InlinePalette,
        val sources: List<WebSource>,
    ) {
        var upper = 0
        var spoilerCount = 0
        val linkStyles = TextLinkStyles(SpanStyle(color = palette.accent))

        fun emit(c: Char) {
            out.append(if (upper > 0) c.uppercaseChar() else c)
        }

        fun emit(s: String) {
            out.append(if (upper > 0) s.uppercase() else s)
        }

        fun parse(start: Int, end: Int) {
            var i = start
            while (i < end) {
                val c = src[i]
                val next = if (i + 1 < end) src[i + 1] else '\u0000'
                when {
                    c == '\\' && i + 1 < end && next.isAsciiPunctuation() -> { emit(next); i += 2 }
                    c == '`' -> i = code(i, end)
                    c == '$' -> i = math(i, end)
                    c == '!' && next == '[' -> i = image(i, end)
                    c == '[' -> i = link(i, end)
                    c == '<' -> i = angleLink(i, end)
                    (c == 'h' || c == 'H') && startsBareUrl(i, end) -> i = bareUrl(i, end)
                    c == '*' || c == '_' -> i = emphasis(i, end)
                    c == '~' && next == '~' -> i = pair(i, end, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough))
                    c == '=' && next == '=' -> i = mark(i, end)
                    c == '|' && next == '|' -> i = spoiler(i, end)
                    c == '{' -> i = brace(i, end)
                    else -> { emit(c); i++ }
                }
            }
        }

        private fun Char.isAsciiPunctuation(): Boolean = this in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        /** Длина серии одинаковых символов с позиции [at]. */
        private fun run(at: Int, end: Int, c: Char): Int {
            var n = 0
            while (at + n < end && src[at + n] == c) n++
            return n
        }

        // `код`
        private fun code(i: Int, end: Int): Int {
            val n = run(i, end, '`')
            var j = i + n
            while (j < end) {
                if (src[j] == '`') {
                    val m = run(j, end, '`')
                    if (m == n) {
                        var body = src.substring(i + n, j)
                        if (body.length >= 2 && body.startsWith(" ") && body.endsWith(" ") && body.isNotBlank()) {
                            body = body.substring(1, body.length - 1)
                        }
                        out.pushStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground, fontSize = 0.9.em)
                        )
                        out.append(body)
                        out.pop()
                        return j + m
                    }
                    j += m
                } else {
                    j++
                }
            }
            emit(src.substring(i, i + n))
            return i + n
        }

        // $формула$: после открывающего и перед закрывающим — не пробел (иначе это цены «$5 и $10»).
        private fun math(i: Int, end: Int): Int {
            if (i + 1 < end && src[i + 1] == '$') { emit("$$"); return i + 2 }
            val first = i + 1
            if (first >= end || src[first] == ' ' || src[first] == '\n') { emit('$'); return i + 1 }
            var j = first
            val limit = minOf(end, first + 121)
            while (j < limit) {
                val c = src[j]
                if (c == '\n') break
                if (c == '$') {
                    val closingOk = src[j - 1] != ' ' && !(j + 1 < end && src[j + 1].isDigit())
                    if (j > first && closingOk) {
                        out.pushStyle(SpanStyle(fontFamily = FontFamily.Serif, color = palette.foreground))
                        emit(InlineMath.unicode(src.substring(first, j)))
                        out.pop()
                        return j + 1
                    }
                    break
                }
                j++
            }
            emit('$')
            return i + 1
        }

        /** Конец [текста] с учётом вложенных скобок и экранирования; -1 — не закрыто. */
        private fun closingBracket(open: Int, end: Int, openChar: Char, closeChar: Char): Int {
            var depth = 0
            var j = open
            val limit = minOf(end, open + 3000)
            while (j < limit) {
                val c = src[j]
                if (c == '\\') { j += 2; continue }
                if (c == '\n' && openChar == '(') return -1
                if (c == openChar) depth++
                if (c == closeChar) {
                    depth--
                    if (depth == 0) return j
                }
                j++
            }
            return -1
        }

        private fun cleanUrl(raw: String): String {
            var value = raw.trim()
            if (value.startsWith("<") && value.endsWith(">")) value = value.substring(1, value.length - 1)
            // «адрес "подсказка"» — подсказку отбрасываем.
            val space = value.indexOf(' ')
            if (space > 0) value = value.substring(0, space)
            return value
        }

        /** Адрес, который можно открыть: http(s), почта, телефон; «www.…» дополняется до https. */
        private fun linkTarget(raw: String): String? {
            val lower = raw.lowercase()
            return when {
                lower.startsWith("https://") || lower.startsWith("http://") ||
                    lower.startsWith("mailto:") || lower.startsWith("tel:") -> raw
                lower.startsWith("www.") -> "https://$raw"
                else -> null
            }
        }

        // ![подпись](адрес) внутри строки: показываем подпись ссылкой на картинку.
        private fun image(i: Int, end: Int): Int {
            val close = closingBracket(i + 1, end, '[', ']')
            if (close < 0 || close + 1 >= end || src[close + 1] != '(') { emit('!'); return i + 1 }
            val paren = closingBracket(close + 1, end, '(', ')')
            if (paren < 0) { emit('!'); return i + 1 }
            val url = linkTarget(cleanUrl(src.substring(close + 2, paren)))
            val caption = src.substring(i + 2, close).ifBlank { "🖼" }
            if (url != null) out.pushLink(LinkAnnotation.Url(url, linkStyles))
            emit(caption)
            if (url != null) out.pop()
            return paren + 1
        }

        // [текст](адрес) и [1] — ссылка на источник.
        private fun link(i: Int, end: Int): Int {
            val close = closingBracket(i, end, '[', ']')
            if (close < 0) { emit('['); return i + 1 }
            val label = src.substring(i + 1, close)
            if (close + 1 < end && src[close + 1] == '(') {
                val paren = closingBracket(close + 1, end, '(', ')')
                if (paren > 0) {
                    val raw = cleanUrl(src.substring(close + 2, paren))
                    if (raw.isNotEmpty()) {
                        val url = linkTarget(raw)
                        val number = label.trim().removePrefix("[").removeSuffix("]")
                        if (url != null && number.isNotEmpty() && number.length <= 3 && number.all { it.isDigit() }) {
                            citation(number, url)
                        } else if (url != null) {
                            out.pushLink(LinkAnnotation.Url(url, linkStyles))
                            parse(i + 1, close)
                            out.pop()
                        } else {
                            // Якорь или непонятный адрес: показываем только текст ссылки.
                            parse(i + 1, close)
                        }
                        return paren + 1
                    }
                }
            }
            // [1] без адреса — сноска на источник из поиска.
            if (sources.isNotEmpty() && label.isNotEmpty() && label.length <= 3 && label.all { it.isDigit() }) {
                val number = label.toInt()
                val preceded = i > 0 && src[i - 1] == '!'
                if (!preceded && number in 1..sources.size) {
                    citation(label, sources[number - 1].url)
                    return close + 1
                }
            }
            emit('[')
            return i + 1
        }

        private fun citation(number: String, url: String) {
            out.pushStringAnnotation(TAG_CITATION, "$number\u001F$url")
            out.pushStyle(SpanStyle(color = palette.accent, fontWeight = FontWeight.SemiBold, fontSize = 0.85.em))
            out.append("[$number]")
            out.pop()
            out.pop()
        }

        private fun angleLink(i: Int, end: Int): Int {
            val close = src.indexOf('>', i + 1)
            if (close in (i + 1) until end) {
                val body = src.substring(i + 1, close)
                if ((body.startsWith("http://") || body.startsWith("https://")) && body.none { it == ' ' || it == '\n' }) {
                    out.pushLink(LinkAnnotation.Url(body, linkStyles))
                    emit(body)
                    out.pop()
                    return close + 1
                }
            }
            emit('<')
            return i + 1
        }

        private fun startsBareUrl(i: Int, end: Int): Boolean {
            if (i > 0) {
                val before = src[i - 1]
                if (before.isLetterOrDigit() || before == '/' || before == '(' && i > 1 && src[i - 2] == ']') return false
            }
            return src.startsWith("https://", i, ignoreCase = true) && i + 8 < end ||
                src.startsWith("http://", i, ignoreCase = true) && i + 7 < end
        }

        private fun bareUrl(i: Int, end: Int): Int {
            var j = i
            while (j < end && !src[j].isWhitespace() && src[j] != '<' && src[j] != '>' && src[j] != '"') j++
            // Хвостовая пунктуация — не часть адреса; лишняя «)» тоже.
            while (j > i && src[j - 1] in ".,;:!?»…'*_~|") j--
            var url = src.substring(i, j)
            while (url.endsWith(")") && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
            out.pushLink(LinkAnnotation.Url(url, linkStyles))
            emit(url)
            out.pop()
            return i + url.length
        }

        /** Позиция закрывающего разделителя [delim] (пропуская код), -1 — нет. */
        private fun findCloser(from: Int, end: Int, delim: String, exactRun: Boolean, intraword: Boolean): Int {
            val c = delim[0]
            var j = from
            val limit = minOf(end, from + 4000)
            while (j < limit) {
                val ch = src[j]
                if (ch == '\\') { j += 2; continue }
                if (ch == '`') {
                    val n = run(j, end, '`')
                    val close = src.indexOf("`".repeat(n), j + n)
                    j = if (close in 0 until end) close + n else j + n
                    continue
                }
                if (ch == c) {
                    val n = run(j, end, c)
                    val fits = if (exactRun) n == delim.length else n >= delim.length
                    if (fits && j > from && !src[j - 1].isWhitespace()) {
                        val afterIndex = j + delim.length
                        val after = if (afterIndex < end) src[afterIndex] else ' '
                        if (intraword || !after.isLetterOrDigit()) return j
                    }
                    j += n
                    continue
                }
                j++
            }
            return -1
        }

        private fun emphasis(i: Int, end: Int): Int {
            val c = src[i]
            val n = run(i, end, c)
            val openLength = minOf(n, 3)
            val afterOpen = i + openLength
            val before = if (i > 0) src[i - 1] else ' '
            val canOpen = afterOpen < end && !src[afterOpen].isWhitespace() &&
                (c == '*' || !before.isLetterOrDigit())
            if (canOpen && n <= 3) {
                val delim = c.toString().repeat(openLength)
                val close = findCloser(afterOpen, end, delim, exactRun = true, intraword = c == '*')
                if (close > afterOpen) {
                    val style = when (openLength) {
                        1 -> SpanStyle(fontStyle = FontStyle.Italic)
                        2 -> SpanStyle(fontWeight = FontWeight.Bold)
                        else -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    }
                    out.pushStyle(style)
                    parse(afterOpen, close)
                    out.pop()
                    return close + openLength
                }
            }
            emit(src.substring(i, i + n))
            return i + n
        }

        private fun pair(i: Int, end: Int, delim: String, style: SpanStyle): Int {
            val afterOpen = i + delim.length
            if (afterOpen < end && !src[afterOpen].isWhitespace()) {
                val close = findCloser(afterOpen, end, delim, exactRun = false, intraword = true)
                if (close > afterOpen) {
                    out.pushStyle(style)
                    parse(afterOpen, close)
                    out.pop()
                    return close + delim.length
                }
            }
            emit(delim)
            return afterOpen
        }

        /** Закрывающий «==» или «||» в пределах строки; внутри не должно быть [forbidden]. */
        private fun simpleCloser(from: Int, end: Int, delim: String, forbidden: Char): Int {
            var j = from
            while (j + 1 < end) {
                val ch = src[j]
                if (ch == '\n') return -1
                if (src.startsWith(delim, j)) return if (j > from) j else -1
                if (ch == forbidden) return -1
                j++
            }
            return -1
        }

        // ==маркер==
        private fun mark(i: Int, end: Int): Int {
            val close = simpleCloser(i + 2, end, "==", '=')
            if (close < 0) { emit("=="); return i + 2 }
            out.pushStyle(SpanStyle(background = markColor))
            parse(i + 2, close)
            out.pop()
            return close + 2
        }

        // ||спойлер||: содержимое скрыто, пока его не нажмут.
        private fun spoiler(i: Int, end: Int): Int {
            val close = simpleCloser(i + 2, end, "||", '|')
            if (close < 0) { emit("||"); return i + 2 }
            val index = spoilerCount++
            out.pushStringAnnotation(TAG_SPOILER, index.toString())
            out.pushStyle(SpanStyle(background = palette.secondary.copy(alpha = 0.45f)))
            parse(i + 2, close)
            out.pop()
            out.pop()
            return close + 2
        }

        // {color:red}…{/color}, {bg:yellow}…{/bg}, {upper}…{/upper}
        private fun brace(i: Int, end: Int): Int {
            if (src.startsWith("{upper}", i)) {
                val close = src.indexOf("{/upper}", i + 7)
                if (close in 0 until end) {
                    upper++
                    parse(i + 7, close)
                    upper--
                    return close + 8
                }
            }
            for (tag in arrayOf("color", "bg")) {
                val opener = "{$tag:"
                if (!src.startsWith(opener, i)) continue
                val tokenEnd = src.indexOf('}', i + opener.length)
                if (tokenEnd < 0 || tokenEnd >= end || tokenEnd - i > 40) break
                val color = color(src.substring(i + opener.length, tokenEnd)) ?: break
                val closer = "{/$tag}"
                val close = src.indexOf(closer, tokenEnd + 1)
                if (close < 0 || close >= end) break
                out.pushStyle(if (tag == "bg") SpanStyle(background = color.copy(alpha = 0.55f)) else SpanStyle(color = color))
                parse(tokenEnd + 1, close)
                out.pop()
                return close + closer.length
            }
            emit('{')
            return i + 1
        }
    }
}
