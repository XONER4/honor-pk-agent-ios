package com.honerai.app.ui.chat

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.GenerationStep
import com.honerai.app.data.MessageRole
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Чистая логика экрана чата (без Android): её проверяют модульные тесты.

/** Группа в панели чатов: «Закреплено», «Сегодня», «Вчера», «7 дней», «Ранее». */
data class HistoryGroup(val id: String, val title: String, val chats: List<Conversation>)

object HistoryGrouping {
    /** Названия групп на языке приложения. */
    fun titles(english: Boolean): Map<String, String> = if (english) {
        mapOf("pinned" to "Pinned", "today" to "Today", "yesterday" to "Yesterday",
            "week" to "Previous 7 days", "older" to "Older")
    } else {
        mapOf("pinned" to "Закреплено", "today" to "Сегодня", "yesterday" to "Вчера",
            "week" to "7 дней", "older" to "Ранее")
    }

    /**
     * Группировка чатов по дате последнего сообщения. Закреплённые идут отдельной группой
     * в своём порядке; пустые группы не показываются.
     */
    fun group(chats: List<Conversation>, english: Boolean, now: Instant = Instant.now(),
              zone: ZoneId = ZoneId.systemDefault()): List<HistoryGroup> {
        val titles = titles(english)
        val today = now.atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        val weekAgo = now.minus(Duration.ofDays(7))
        val unpinned = chats.filter { !it.pinned }
        fun day(chat: Conversation) = chat.lastMessageAt.atZone(zone).toLocalDate()
        val todayChats = unpinned.filter { day(it) == today }
        val yesterdayChats = unpinned.filter { day(it) == yesterday }
        val week = unpinned.filter {
            val d = day(it)
            d != today && d != yesterday && !it.lastMessageAt.isBefore(weekAgo)
        }
        val older = unpinned.filter { it.lastMessageAt.isBefore(weekAgo) && day(it) != today && day(it) != yesterday }
        return listOf(
            HistoryGroup("pinned", titles.getValue("pinned"), chats.filter { it.pinned }),
            HistoryGroup("today", titles.getValue("today"), todayChats),
            HistoryGroup("yesterday", titles.getValue("yesterday"), yesterdayChats),
            HistoryGroup("week", titles.getValue("week"), week),
            HistoryGroup("older", titles.getValue("older"), older),
        ).filter { it.chats.isNotEmpty() }
    }
}

/**
 * Правила поиска по истории чатов: заголовок, текст сообщений, рассуждения,
 * имена вложений и распознанный из вложений текст. Регистр не важен.
 */
object HistorySearchRules {
    fun matches(chat: Conversation, needle: String): Boolean {
        if (chat.title.contains(needle, ignoreCase = true)) return true
        return chat.messages.any { message ->
            message.content.contains(needle, ignoreCase = true) ||
                message.reasoning.contains(needle, ignoreCase = true) ||
                message.attachments.any {
                    it.name.contains(needle, ignoreCase = true) || it.extractedText.contains(needle, ignoreCase = true)
                }
        }
    }

    fun filter(chats: List<Conversation>, query: String): List<Conversation> {
        val needle = query.trim()
        if (needle.isEmpty()) return chats
        return chats.filter { matches(it, needle) }
    }

    /** Признак существенного изменения истории: пока печатается ответ, он не меняется. */
    fun revision(chats: List<Conversation>): Int {
        var hash = chats.size
        for (chat in chats) hash = hash * 31 + chat.messages.size + chat.title.length + (if (chat.pinned) 1 else 0)
        return hash
    }
}

/** Поиск внутри открытого чата: какие сообщения содержат запрос. */
object FindRules {
    fun matchingIds(messages: List<ChatMessage>, query: String): List<String> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return messages.filter {
            it.content.contains(needle, ignoreCase = true) || it.reasoning.contains(needle, ignoreCase = true)
        }.map { it.id }
    }

    /** «2 / 5» — какое совпадение из скольких. */
    fun counter(index: Int, count: Int): String = if (count == 0) "0 / 0" else "${minOf(index + 1, count)} / $count"
}

/** Подготовка текста к чтению вслух (порт SpeechService.speakableEnd / sanitizedSpeechText). */
object SpeechText {
    private val terminators = setOf('.', '!', '?', '…', '\n', ':', ';')

    /**
     * Где можно отрезать кусок текста для чтения: после законченного предложения
     * и не внутри блока кода. Возвращает длину куска (в UTF-16) или null — ждать продолжения.
     */
    fun speakableEnd(rest: String, final: Boolean): Int? {
        if (rest.none { !it.isWhitespace() }) return null
        // Внутри незакрытого блока кода не режем: код не читается, ждём конца блока.
        val fences = mutableListOf<Int>()
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
        var cut: Int? = null
        var index = 0
        while (index < limit) {
            val next = index + 1
            val ch = rest[index]
            if (ch in terminators) {
                if (ch == '\n') cut = next
                else if (next < rest.length && rest[next].isWhitespace()) cut = next
            }
            index = next
        }
        val end = cut ?: return null
        return if (end >= 18 || rest.substring(0, end).contains('\n')) end else null
    }

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?s)```.*?```|~~~.*?~~~") to " ",
        Regex("[^]*") to " ",
        Regex("\\[\\s*\\d+(?:\\s*[,–\\-]\\s*\\d+)*\\s*\\]\\([^)]+\\)") to " ",
        Regex("!?\\[([^\\]]*)\\]\\([^)]+\\)") to "$1",
        Regex("\\[\\s*\\d+(?:\\s*[,–\\-]\\s*\\d+)*\\s*\\]") to " ",
        Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+") to " ",
        Regex("(?m)^[ \\t]{0,3}(?:#{1,6}\\s*|>\\s*|[-+*]\\s+)") to "",
        Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$") to "",
        Regex("(\\*\\*|__)(.*?)\\1") to "$2",
        Regex("(?<!\\w)[*_]([^*_\\n]+)[*_](?!\\w)") to "$1",
        Regex("~~(.*?)~~") to "$1",
        Regex("`([^`]+)`") to "$1",
        Regex("\\\\([\\\\`*_{}\\[\\]()#+.!>\\-])") to "$1",
    )
    private val tail: List<Pair<Regex, String>> = listOf(
        Regex("\\s*\\|\\s*") to ", ",
        Regex("[ \\t]{2,}") to " ",
        Regex("[ \\t]*\\n[ \\t]*") to "\n",
        Regex("\\n{3,}") to "\n\n",
    )

    private fun isEmoji(codePoint: Int): Boolean =
        codePoint == 0xFE0F || codePoint == 0x20E3 || codePoint >= 0x1F000 ||
            codePoint in 0x2600..0x27BF || codePoint in 0x238D..0x23FF || codePoint in 0x2B00..0x2BFF

    /** Текст без разметки, ссылок, кода и эмодзи — только то, что стоит произносить. */
    fun sanitized(input: String): String {
        var text = input
        for ((regex, replacement) in rules) text = regex.replace(text, replacement)
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmoji(cp)) builder.append(' ') else builder.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        text = builder.toString()
        for ((regex, replacement) in tail) text = regex.replace(text, replacement)
        return text.trim()
    }

    /** Короткое превью сообщения для навигации по чату. */
    fun preview(message: ChatMessage): String {
        val text = sanitized(message.content).replace('\n', ' ').trim()
        if (text.isNotEmpty()) return text.take(220)
        return message.attachments.joinToString(", ") { it.name }
    }
}

/** Сколько уже прочитано вслух из печатающегося ответа. */
data class LiveSpeechCursor(val messageId: String? = null, val consumed: Int = 0)

/** Шаги работы (поиск, чтение страниц, рисование) — подписи и прогресс. */
object ActivityInfo {
    private val numbers = Regex("\\d+")

    /** «Прочитано 132 из 500» → 0.264 для полосы прогресса. */
    fun progress(detail: String): Float? {
        if (!detail.contains(" из ") && !detail.contains(" of ")) return null
        val values = numbers.findAll(detail).mapNotNull { it.value.toIntOrNull() }.toList()
        if (values.size < 2) return null
        val done = values[0]
        val total = values[1]
        if (total <= 0) return null
        return minOf(1f, done.toFloat() / total)
    }

    /** Короткий итог шагов для свёрнутого заголовка: «Искал в интернете · сайтов: 5». */
    fun summary(steps: List<GenerationStep>): String {
        val parts = mutableListOf<String>()
        val sites = steps.flatMap { it.sites }.toSet()
        if (steps.any { it.kind == "search" }) parts.add("Искал в интернете")
        if (sites.isNotEmpty()) parts.add("сайтов: ${sites.size}")
        if (steps.any { it.kind == "images" }) parts.add("фото")
        if (steps.any { it.kind == "videos" }) parts.add("видео")
        if (steps.any { it.kind == "draw" }) parts.add("рисунок")
        if (steps.any { it.kind == "chats" }) parts.add("чаты")
        if (steps.any { it.kind == "memory" }) parts.add("память")
        if (steps.any { it.kind == "table" }) parts.add("таблица")
        if (parts.isEmpty()) parts.add("Шаги: ${steps.size}")
        return parts.joinToString(" · ")
    }

    /** Уникальные сайты шага без «www.», в порядке появления. */
    fun uniqueSites(sites: List<String>): List<String> {
        val list = mutableListOf<String>()
        for (host in sites) {
            val clean = host.replace("www.", "")
            if (clean !in list) list.add(clean)
        }
        return list
    }

    /** Цвет-заглушка значка сайта по его адресу (индекс в палитре). */
    fun colorIndex(host: String, paletteSize: Int): Int {
        var hash = 0
        for (ch in host) hash = (hash * 31 + ch.code) and 0xFFFF
        return hash % paletteSize
    }
}

/** Раскладка линий навигации справа: по центру по высоте, не выше 60 % экрана. */
data class NavigationLayout(val count: Int, val available: Float, val spacing: Float = 12f) {
    val height: Float = minOf(available * 0.6f, maxOf(count - 1, 1) * spacing)
    val top: Float = (available - height) / 2
    val step: Float = if (count > 1) height / (count - 1) else 0f

    fun y(index: Int): Float = top + index * step

    fun index(at: Float): Int {
        if (count <= 1 || step <= 0f) return 0
        val raw = Math.round((at - top) / step)
        return raw.coerceIn(0, count - 1)
    }
}

/** Действия долгого нажатия на сообщение. */
enum class MessageMenuAction(val raw: String) {
    COPY("copy"), SELECT("select"), QUOTE("quote"), EDIT("edit"), SHARE("share"), RETRY("retry"),
    LIKE("like"), DISLIKE("dislike"), SPEAK("speak"), FORK("fork"), REMEMBER("remember"),
    PIN_INSTRUCTION("pinInstruction"),
    FORWARD_TO_FAVORITES("forwardFavorites"); // appui: переслать сообщение в чат «Избранное»

    /** Для пункта нужен текст сообщения: без текста он недоступен. */
    val requiresContent: Boolean
        get() = this in setOf(COPY, SELECT, QUOTE, REMEMBER, SPEAK, SHARE, PIN_INSTRUCTION)

    fun title(english: Boolean): String = when (this) {
        COPY -> if (english) "Copy" else "Копировать"
        SELECT -> if (english) "Select text and ask" else "Выбрать текст и спросить"
        QUOTE -> if (english) "Quote" else "Цитировать"
        EDIT -> if (english) "Edit" else "Редактировать"
        SHARE -> if (english) "Share" else "Поделиться"
        RETRY -> if (english) "Retry" else "Повторить"
        LIKE -> if (english) "Like" else "Нравится"
        DISLIKE -> if (english) "Dislike" else "Не нравится"
        SPEAK -> if (english) "Read aloud" else "Читать вслух"
        FORK -> if (english) "Continue in a branch" else "Продолжить в ветке"
        REMEMBER -> if (english) "Remember" else "Запомнить"
        PIN_INSTRUCTION -> if (english) "Pin as instruction" else "Закрепить как инструкцию"
        FORWARD_TO_FAVORITES -> if (english) "Forward to Saved" else "Переслать в Избранное"
    }

    companion object {
        fun available(role: MessageRole): List<MessageMenuAction> = if (role == MessageRole.USER) {
            listOf(COPY, PIN_INSTRUCTION, SELECT, QUOTE, EDIT, FORK, REMEMBER, FORWARD_TO_FAVORITES, SHARE)
        } else {
            listOf(COPY, SELECT, QUOTE, PIN_INSTRUCTION, RETRY, FORK, REMEMBER, FORWARD_TO_FAVORITES, LIKE, DISLIKE, SPEAK, SHARE)
        }
    }
}

/** Текст чата для «Поделиться чатом»: автор, сообщение, вложения и источники. */
object ChatTranscript {
    fun build(chat: Conversation, english: Boolean): String {
        val blocks = chat.messages.filter { it.role != MessageRole.TOOL }.map { message ->
            val author = if (message.role == MessageRole.USER) (if (english) "You" else "Вы") else "Honer AI"
            var block = author + ":\n" + message.content
            if (message.attachments.isNotEmpty()) block += "\n" + message.attachments.joinToString(", ") { it.name }
            if (message.sources.isNotEmpty()) {
                block += "\n\n" + message.sources.mapIndexed { i, s -> "[${i + 1}] ${s.title}: ${s.url}" }.joinToString("\n")
            }
            block
        }
        return (listOf(chat.title, "Honer AI") + blocks).joinToString("\n\n")
    }
}

/** Подписи карточки источников под ответом. */
object SourceText {
    fun summary(message: ChatMessage, english: Boolean): String {
        val pages = message.sources.count { !it.content.isNullOrEmpty() }
        val count = message.sources.size
        return when {
            pages == 0 -> if (english) "$count search result(s)" else "$count результат(ов) поиска"
            pages == count -> if (english) "$count pages read" else "$count прочитанных страниц(ы)"
            else -> if (english) "$pages of $count pages read" else "прочитано $pages из $count страниц"
        }
    }

    fun titles(message: ChatMessage): String {
        val names = message.sources.take(3).map { source ->
            source.title.trim().ifEmpty { com.honerai.app.ui.common.MediaLinks.host(source.url) ?: source.url }
        }
        var value = names.joinToString(" · ")
        if (message.sources.size > 3) value += " …"
        return value
    }
}
