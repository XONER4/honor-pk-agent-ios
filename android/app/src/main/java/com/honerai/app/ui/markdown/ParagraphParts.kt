package com.honerai.app.ui.markdown

import androidx.compose.runtime.Immutable

/**
 * Абзац, разрезанный на текст и картинки `![подпись](ссылка)` (порт InlineContentView.parts).
 * Номер части — по позиции: разметка разбирается заново при каждом росте текста,
 * и со случайным идентификатором картинки загружались бы заново.
 */
@Immutable
data class ParagraphPart(val id: Int, val text: String?, val imageUrl: String?, val caption: String)

object ParagraphParts {
    /** Адрес берём до первого пробела: в ссылках на картинки бывают скобки и параметры. */
    private val imagePattern = Regex("""!\[([^\]]*)\]\(\s*(https?://\S+)""")

    fun split(source: String): List<ParagraphPart> {
        if (!source.contains("![")) return listOf(ParagraphPart(0, source, null, ""))
        val matches = imagePattern.findAll(source).toList()
        if (matches.isEmpty()) return listOf(ParagraphPart(0, source, null, ""))
        val result = ArrayList<ParagraphPart>()
        var cursor = 0
        for (match in matches) {
            val before = source.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) result.add(ParagraphPart(result.size, before, null, ""))
            // Точка или запятая сразу после картинки — пунктуация предложения, не адрес.
            val rawUrl = match.groupValues[2].trimEnd('.', ',', ';', ':', '!', '?', '»')
            val url = trimmedUrl(rawUrl)
            result.add(ParagraphPart(result.size, null, url, match.groupValues[1]))
            // Хвостовая «)» разметки, отрезанная от адреса, в текст не попадает.
            cursor = match.range.last + 1
        }
        val tail = source.substring(cursor).trim().removePrefix(")").trim()
        if (tail.isNotEmpty()) result.add(ParagraphPart(result.size, tail, null, ""))
        return result
    }

    /**
     * Убрать хвостовую закрывающую скобку Markdown из адреса картинки. Если скобка
     * есть и внутри адреса (`.../Photo_(1).jpg`), она часть ссылки — не трогаем.
     */
    fun trimmedUrl(raw: String): String {
        var value = raw.trim()
        if (value.endsWith(")")) {
            val opens = value.count { it == '(' }
            val closes = value.count { it == ')' }
            if (closes > opens || value.endsWith(").jpg") || value.endsWith(").png")) value = value.dropLast(1)
        }
        return value
    }
}
