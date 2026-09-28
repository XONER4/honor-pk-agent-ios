package com.honerai.app.ui.chat

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.ui.common.MediaLinks
import java.time.Instant

/** Что было в чате: медиа, файлы, ссылки и шаги работы — для «Информации о чате». */
object ChatInsight {
    enum class Author { ALL, USER, ASSISTANT }
    enum class MediaKind { PHOTO, VIDEO, AUDIO, FILE }

    data class MediaItem(
        val id: String,
        val kind: MediaKind,
        val author: MessageRole,
        val date: Instant,
        val title: String,
        val subtitle: String,
        val attachment: MessageAttachment?,
        val url: String?,
        val messageId: String,
    )

    data class LinkItem(
        val url: String,
        val title: String,
        val snippet: String,
        val date: Instant,
        /** source — прочитано, visit — открывал, user — ссылка пользователя, answer — в ответе. */
        val origin: String,
        val messageId: String,
    )

    data class TimelineItem(
        val id: String,
        val date: Instant,
        /** Значок: person, mic, attachment:<kind>, step:<kind>, brain, sparkles. */
        val symbol: String,
        val title: String,
        val detail: String,
        val author: MessageRole,
        val messageId: String,
    )

    private val imagePattern = Regex("!\\[([^\\]]*)\\]\\((https?://[^\\s)]+)\\)")
    private val linkPattern = Regex("(?<!!)\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")

    fun matches(pattern: Regex, text: String): List<Pair<String, String>> =
        if (text.isEmpty()) emptyList() else pattern.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()

    fun media(messages: List<ChatMessage>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        for (message in messages) {
            for (attachment in message.attachments) {
                if (attachment.kind == AttachmentKind.STICKER) continue
                val kind = when (attachment.kind) {
                    AttachmentKind.IMAGE -> MediaKind.PHOTO
                    AttachmentKind.VIDEO -> MediaKind.VIDEO
                    AttachmentKind.AUDIO -> MediaKind.AUDIO
                    else -> MediaKind.FILE
                }
                items.add(MediaItem(attachment.id, kind, message.role, message.createdAt, attachment.name,
                    attachment.summary ?: "", attachment, null, message.id))
            }
            if (message.role != MessageRole.ASSISTANT) continue
            for ((title, url) in matches(imagePattern, message.content)) {
                val host = MediaLinks.host(url) ?: ""
                items.add(MediaItem(message.id + url, if (MediaLinks.isVideo(url)) MediaKind.VIDEO else MediaKind.PHOTO,
                    MessageRole.ASSISTANT, message.createdAt, title.ifEmpty { host }, host, null, url, message.id))
            }
            for ((title, url) in matches(linkPattern, message.content)) {
                if (!MediaLinks.isVideo(url)) continue
                items.add(MediaItem(message.id + url, MediaKind.VIDEO, MessageRole.ASSISTANT, message.createdAt,
                    title, MediaLinks.host(url) ?: "", null, url, message.id))
            }
        }
        return items
    }

    fun links(messages: List<ChatMessage>): List<LinkItem> {
        val items = mutableListOf<LinkItem>()
        val seen = HashSet<String>()
        fun add(item: LinkItem) { if (seen.add(item.url)) items.add(item) }
        for (message in messages) {
            for (source in message.sources) {
                add(LinkItem(source.url, source.title, source.snippet, message.createdAt, "source", message.id))
            }
            for (step in message.activity.orEmpty()) {
                for (site in step.sites) {
                    val url = if (site.startsWith("http")) site else "https://$site"
                    add(LinkItem(url, site, step.title, step.startedAt ?: message.createdAt, "visit", message.id))
                }
            }
            for ((title, url) in matches(linkPattern, message.content)) {
                add(LinkItem(url, title, "", message.createdAt,
                    if (message.role == MessageRole.USER) "user" else "answer", message.id))
            }
        }
        return items
    }

    fun timeline(messages: List<ChatMessage>, english: Boolean): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
        for (message in messages) {
            val preview = message.content.replace("\n", " ").take(120)
            when (message.role) {
                MessageRole.USER -> {
                    items.add(TimelineItem(message.id, message.createdAt,
                        if (message.inputKind == MessageInputKind.VOICE) "mic" else "person",
                        if (english) "You wrote" else "Вы написали", preview, message.role, message.id))
                    for (attachment in message.attachments) {
                        items.add(TimelineItem(attachment.id, message.createdAt, "attachment:" + attachment.kind.name,
                            if (english) "You attached" else "Вы прикрепили", attachment.name, message.role, message.id))
                    }
                }
                MessageRole.ASSISTANT -> {
                    for (step in message.activity.orEmpty()) {
                        val detail = (listOf(step.detail) + step.sites.take(4)).filter { it.isNotEmpty() }.joinToString(" · ")
                        items.add(TimelineItem(step.id, step.startedAt ?: message.createdAt, "step:" + step.kind,
                            step.title, detail, message.role, message.id))
                    }
                    if (message.reasoningSeconds > 0) {
                        items.add(TimelineItem(message.id + "-thinking", message.createdAt, "brain",
                            if (english) "Thinking" else "Размышление",
                            if (english) "${message.reasoningSeconds} s" else "${message.reasoningSeconds} с",
                            message.role, message.id))
                    }
                    if (message.content.isNotEmpty()) {
                        items.add(TimelineItem(message.id, message.createdAt, "sparkles",
                            if (english) "Honer AI answered" else "Honer AI ответил", preview, message.role, message.id))
                    }
                }
                MessageRole.TOOL -> Unit
            }
        }
        return items
    }

    /** Фильтр по автору и строке поиска. */
    fun passes(author: Author, query: String, role: MessageRole, values: List<String>): Boolean {
        if (author == Author.USER && role != MessageRole.USER) return false
        if (author == Author.ASSISTANT && role != MessageRole.ASSISTANT) return false
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return values.any { it.lowercase().contains(needle) }
    }
}
