package com.honerai.app.ui.markdown

/**
 * Результат разбора, привязанный к тексту (порт MarkdownParseMemo с iOS).
 *
 * Пока ответ печатается, закреплённое начало разбирается один раз — по безопасным
 * границам (пустая строка вне блоков кода и формул), а на каждом кадре заново
 * разбирается только хвост. Номера блоков совпадают с полным разбором, поэтому
 * уже показанные блоки не пересоздаются и не перерисовываются.
 */
class MarkdownParseMemo {
    private var source: String = ""
    private var streaming = false
    private var blocks: List<MarkdownBlock> = emptyList()
    private var valid = false
    /** Закреплённое начало печатающегося ответа: разобрано один раз. */
    private var stableText: String = ""
    private val stableBlocks = ArrayList<MarkdownBlock>()

    fun blocks(text: String, streaming: Boolean): List<MarkdownBlock> {
        if (valid && streaming == this.streaming && text.length == source.length && text == source) return blocks
        blocks = if (streaming) {
            streamingBlocks(text)
        } else {
            stableText = ""
            stableBlocks.clear()
            FinishedParseCache.get(text)
        }
        source = text
        this.streaming = streaming
        valid = true
        return blocks
    }

    private fun streamingBlocks(text: String): List<MarkdownBlock> {
        if (stableText.isNotEmpty() && !text.startsWith(stableText)) {
            stableText = ""
            stableBlocks.clear()
        }
        val stableEnd = stableText.length
        val boundary = safeBoundary(text, stableEnd)
        if (boundary != null && boundary - stableEnd >= STABLE_STEP) {
            val offset = stableBlocks.size
            // Тот же режим, что и у полного разбора во время печати: результат совпадает.
            MarkdownBlockParser.parse(text.substring(stableEnd, boundary), streaming = true)
                .mapTo(stableBlocks) { it.copy(id = it.id + offset) }
            stableText = text.substring(0, boundary)
        }
        val offset = stableBlocks.size
        val tail = MarkdownBlockParser.parse(text.substring(stableText.length), streaming = true)
        if (stableBlocks.isEmpty()) return tail
        val result = ArrayList<MarkdownBlock>(stableBlocks.size + tail.size)
        result.addAll(stableBlocks)
        tail.mapTo(result) { if (offset == 0) it else it.copy(id = it.id + offset) }
        return result
    }

    companion object {
        /** Хвост не короче этого числа символов остаётся «живым», граница двигается шагами. */
        const val STABLE_STEP = 1500

        /**
         * Последняя пустая строка после [start], вне блоков кода (```) и формул ($$):
         * до неё текст уже не меняется, а пустая строка закрывает абзац, список и таблицу.
         * Правила рамок те же, что у разборщика: внутри формулы рамка кода — это текст формулы.
         */
        fun safeBoundary(text: String, start: Int): Int? {
            var inFence = false
            var inMath = false
            var best: Int? = null
            var lineStart = start
            var previousBlank = false
            val end = text.length
            while (lineStart < end) {
                val newline = text.indexOf('\n', lineStart)
                val lineEnd = if (newline < 0) end else newline
                var s = lineStart
                while (s < lineEnd && isWs(text[s])) s++
                var e = lineEnd
                while (e > s && isWs(text[e - 1])) e--
                val blank = s == e
                if (!blank) {
                    if (!inMath && text.startsWith("```", s)) {
                        inFence = !inFence
                    } else if (!inFence) {
                        if (inMath) {
                            if (containsDollars(text, lineStart, lineEnd)) inMath = false
                        } else if (text.startsWith("$$", s)) {
                            val afterLength = e - s - 2
                            val singleLine = afterLength > 2 && text.startsWith("$$", e - 2)
                            if (!singleLine) inMath = true
                        }
                    }
                }
                val next = if (lineEnd < end) lineEnd + 1 else end
                if (blank && !previousBlank && !inFence && !inMath && lineEnd < end && lineStart > start) {
                    best = next
                }
                previousBlank = blank
                lineStart = next
            }
            return best
        }

        /** Есть ли «$$» внутри строки [from, to) — поиск не выходит за её конец. */
        private fun containsDollars(text: String, from: Int, to: Int): Boolean {
            var i = from
            while (i + 1 < to) {
                if (text[i] == '$' && text[i + 1] == '$') return true
                i++
            }
            return false
        }
    }
}

/**
 * Разобранные законченные ответы. Старое сообщение, прокрученное обратно на экран,
 * не разбирается заново — блоки берутся из кэша (те же объекты, Compose их пропускает).
 */
internal object FinishedParseCache {
    private val cache = object : LinkedHashMap<String, List<MarkdownBlock>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MarkdownBlock>>?): Boolean =
            size > 48
    }

    fun get(text: String): List<MarkdownBlock> {
        synchronized(cache) { cache[text]?.let { return it } }
        val parsed = MarkdownBlockParser.parse(text, streaming = false)
        synchronized(cache) { cache[text] = parsed }
        return parsed
    }
}
