package com.honerai.app.device

// Подготовка текста к чтению вслух (порт sanitizedSpeechText, spokenForm, languageSegments,
// speakableEnd из SpeechService.swift). Чистый Kotlin — проверяется JVM-тестами.
// \w и \b в Java и в ICU (Android) ведут себя по-разному, поэтому «буква слова» задана явно.

/** Кусок текста для одного голоса: латиница читается английским голосом, остальное — русским. */
data class SpeechSegment(val text: String, val english: Boolean)

object SpeechText {
    private const val W = "[\\p{L}\\p{N}_]"
    private const val NOT_BEFORE = "(?<!$W)"
    private const val NOT_AFTER = "(?!$W)"

    private fun rx(pattern: String, ignoreCase: Boolean = false): Regex =
        if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)

    private val sanitizeRules: List<Pair<Regex, String>> = listOf(
        rx("(?s)```.*?```|~~~.*?~~~") to " ",
        rx("[^]*") to " ",
        rx("\\[\\s*\\d+(?:\\s*[,–\\-]\\s*\\d+)*\\s*\\]\\([^)]+\\)") to " ",
        rx("!?\\[([^\\]]*)\\]\\([^)]+\\)") to "\$1",
        rx("\\[\\s*\\d+(?:\\s*[,–\\-]\\s*\\d+)*\\s*\\]") to " ",
        rx("$NOT_BEFORE(?:https?://|www\\.)[^\\s<>]+", ignoreCase = true) to " ",
        rx("(?m)^\\s{0,3}(?:#{1,6}\\s*|>\\s*|[-+*]\\s+)") to "",
        rx("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$") to "",
        rx("(\\*\\*|__)(.*?)\\1") to "\$2",
        rx("$NOT_BEFORE[*_]([^*_\\n]+)[*_]$NOT_AFTER") to "\$1",
        rx("~~(.*?)~~") to "\$1",
        rx("`([^`]+)`") to "\$1",
        rx("\\\\([\\\\`*_{}\\[\\]()#+.!>\\-])") to "\$1",
    )
    private val afterEmojiRules: List<Pair<Regex, String>> = listOf(
        rx("\\s*\\|\\s*") to ", ",
        rx("[ \\t]{2,}") to " ",
        rx("[ \\t]*\\n[ \\t]*") to "\n",
        rx("\\n{3,}") to "\n\n",
    )

    /** Проза без эмодзи, разметки Markdown, ссылок и сносок — то, что стоит читать вслух. */
    fun sanitizedSpeechText(input: String): String {
        var text = input
        for ((regex, replacement) in sanitizeRules) text = regex.replace(text, replacement)
        text = replaceEmoji(text)
        for ((regex, replacement) in afterEmojiRules) text = regex.replace(text, replacement)
        return text.trim()
    }

    private val spokenRules: List<Pair<Regex, String>> = listOf(
        rx("\\s?°\\s?[CС]$NOT_AFTER") to " градусов Цельсия",
        rx("\\s?°") to " градусов",
        rx("\\s?%") to " процентов",
        rx("\\s?₽") to " рублей",
        rx("\\s?€") to " евро",
        rx("\\$\\s?(\\d[\\d\\s.,]*\\d|\\d)") to "\$1 долларов",
        rx("\\s?\\$") to " долларов",
        rx("${NOT_BEFORE}км/ч$NOT_AFTER", true) to "километров в час",
        rx("${NOT_BEFORE}м/с$NOT_AFTER", true) to "метров в секунду",
        rx("${NOT_BEFORE}т\\.\\s?е\\.", true) to "то есть",
        rx("${NOT_BEFORE}т\\.\\s?д\\.", true) to "так далее",
        rx("${NOT_BEFORE}т\\.\\s?п\\.", true) to "тому подобное",
        rx("${NOT_BEFORE}напр\\.", true) to "например",
        rx("${NOT_BEFORE}тыс\\.", true) to "тысяч",
        rx("${NOT_BEFORE}млн$NOT_AFTER\\.?", true) to "миллионов",
        rx("${NOT_BEFORE}млрд$NOT_AFTER\\.?", true) to "миллиардов",
        rx("(\\d)\\s?[–—]\\s?(\\d)") to "\$1 до \$2",
        rx("\\s?×\\s?") to " умножить на ",
        rx("\\s?≈\\s?") to " примерно ",
        rx("\\s?±\\s?") to " плюс-минус ",
        rx("\\s?(→|⇒)\\s?") to ", ",
        rx("\\s=\\s") to " равно ",
    )
    private val multipleSpaces = rx("[ \\t]{2,}")

    /** Текст для чтения по-русски: знаки и сокращения — словами, строки заканчиваются паузой. */
    fun spokenForm(input: String): String {
        var text = input
        for ((regex, replacement) in spokenRules) text = regex.replace(text, replacement)
        // Пауза в конце каждой строки: заголовки и пункты списка не сливаются.
        text = text.split('\n').joinToString("\n") { line ->
            val trimmed = DocumentReader.trimWs(line)
            val last = trimmed.lastOrNull()
            if (last == null || ".!?…:;,".contains(last)) trimmed else "$trimmed."
        }
        text = multipleSpaces.replace(text, " ")
        return text.trim()
    }

    private val tokenRegex = Regex("\\S+\\s*")

    /** Текст делится на куски по языку: латиница — английский голос, остальное — русский. Числа и знаки остаются в текущем куске. */
    fun languageSegments(text: String): List<SpeechSegment> {
        val segments = ArrayList<SpeechSegment>()
        val current = StringBuilder()
        var currentEnglish: Boolean? = null
        for (match in tokenRegex.findAll(text)) {
            val token = match.value
            val hasCyrillic = token.any { it.code in 0x0400..0x04FF }
            val hasLatin = token.any { it in 'A'..'Z' || it in 'a'..'z' }
            val kind: Boolean? = if (hasCyrillic) false else if (hasLatin) true else null
            val active = currentEnglish
            if (kind != null && active != null && kind != active && current.isNotEmpty()) {
                segments.add(SpeechSegment(current.toString(), active))
                current.setLength(0)
            }
            if (kind != null) currentEnglish = kind
            current.append(token)
        }
        if (current.isNotEmpty()) segments.add(SpeechSegment(current.toString(), currentEnglish ?: false))
        return segments.filter { it.text.isNotBlank() }
    }

    /**
     * Где можно отрезать кусок текста для чтения: после законченного предложения и не внутри
     * блока кода. Возвращает длину куска в [rest] или null — ждать продолжения.
     */
    fun speakableEnd(rest: String, final: Boolean): Int? {
        if (rest.isBlank()) return null
        // Внутри незакрытого блока кода не режем: код не читается, ждём конца блока.
        val fences = ArrayList<Int>()
        var search = 0
        while (true) {
            val found = rest.indexOf("```", search)
            if (found < 0) break
            fences.add(found)
            search = found + 3
        }
        var limit = rest.length
        if (fences.size % 2 == 1) limit = fences.last()
        if (final) return if (limit == rest.length) rest.length else if (limit > 0) limit else null
        val terminators = ".!?…\n:;"
        var cut: Int? = null
        var index = 0
        while (index < limit) {
            val next = index + 1
            val c = rest[index]
            if (terminators.indexOf(c) >= 0) {
                if (c == '\n') cut = next
                else if (next < rest.length && rest[next].isWhitespace()) cut = next
            }
            index = next
        }
        val end = cut ?: return null
        return if (end >= 18 || rest.substring(0, end).contains('\n')) end else null
    }

    /** Длинный кусок — на части не длиннее [maximum] символов, по границам предложений или слов. */
    fun chunks(text: String, maximum: Int): List<String> {
        if (text.length <= maximum) return listOf(text)
        val result = ArrayList<String>()
        var rest = text
        while (rest.length > maximum) {
            val window = rest.substring(0, maximum)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf('\n'))
            if (cut < maximum / 3) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = maximum - 1
            result.add(rest.substring(0, cut + 1))
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) result.add(rest)
        return result
    }

    // MARK: Эмодзи

    /** Эмодзи (с модификаторами, ZWJ-последовательностями, флагами и «keycap») заменяются пробелом. */
    internal fun replaceEmoji(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            // Кластер: базовый символ + модификаторы/склейки, как Character в Swift (упрощённо).
            val start = i
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            var emoji = isEmojiScalar(cp)
            if (cp in 0x1F1E6..0x1F1FF && i < text.length) {
                val next = text.codePointAt(i)
                if (next in 0x1F1E6..0x1F1FF) i += Character.charCount(next)
            }
            while (i < text.length) {
                cp = text.codePointAt(i)
                if (isExtender(cp)) {
                    if (cp == 0xFE0F || cp == 0x20E3) emoji = true
                    i += Character.charCount(cp)
                } else if (cp == 0x200D && i + 1 < text.length) {
                    i += 1
                    val joined = text.codePointAt(i)
                    if (isEmojiScalar(joined)) emoji = true
                    i += Character.charCount(joined)
                } else {
                    break
                }
            }
            if (emoji) out.append(' ') else out.append(text, start, i)
        }
        return out.toString()
    }

    private fun isExtender(cp: Int): Boolean =
        cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    /** Аналог isEmojiPresentation || (isEmoji && > U+238C) у Swift. */
    internal fun isEmojiScalar(cp: Int): Boolean = when {
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x1FC00..0x1FFFD -> true
        cp < 0x231A -> false
        else -> cp in bmpEmoji
    }

    private val bmpEmoji: Set<Int> = HashSet<Int>().apply {
        fun r(a: Int, b: Int = a) { for (x in a..b) add(x) }
        r(0x231A, 0x231B); r(0x23CF); r(0x23E9, 0x23F3); r(0x23F8, 0x23FA); r(0x24C2); r(0x25AA, 0x25AB)
        r(0x25B6); r(0x25C0); r(0x25FB, 0x25FE); r(0x2600, 0x2604); r(0x260E); r(0x2611); r(0x2614, 0x2615)
        r(0x2618); r(0x261D); r(0x2620); r(0x2622, 0x2623); r(0x2626); r(0x262A); r(0x262E, 0x262F)
        r(0x2638, 0x263A); r(0x2640); r(0x2642); r(0x2648, 0x2653); r(0x265F, 0x2660); r(0x2663)
        r(0x2665, 0x2666); r(0x2668); r(0x267B); r(0x267E, 0x267F); r(0x2692, 0x2697); r(0x2699)
        r(0x269B, 0x269C); r(0x26A0, 0x26A1); r(0x26A7); r(0x26AA, 0x26AB); r(0x26B0, 0x26B1); r(0x26BD, 0x26BE)
        r(0x26C4, 0x26C5); r(0x26C8); r(0x26CE, 0x26CF); r(0x26D1); r(0x26D3, 0x26D4); r(0x26E9, 0x26EA)
        r(0x26F0, 0x26F5); r(0x26F7, 0x26FA); r(0x26FD); r(0x2702); r(0x2705); r(0x2708, 0x270D); r(0x270F)
        r(0x2712); r(0x2714); r(0x2716); r(0x271D); r(0x2721); r(0x2728); r(0x2733, 0x2734); r(0x2744)
        r(0x2747); r(0x274C); r(0x274E); r(0x2753, 0x2755); r(0x2757); r(0x2763, 0x2764); r(0x2795, 0x2797)
        r(0x27A1); r(0x27B0); r(0x27BF); r(0x2934, 0x2935); r(0x2B05, 0x2B07); r(0x2B1B, 0x2B1C); r(0x2B50)
        r(0x2B55); r(0x3030); r(0x303D); r(0x3297); r(0x3299)
    }
}
