package com.honerai.app.core

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.ConversationKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.data.newId
import java.time.Instant

/**
 * Чистая логика чата «Избранное» (feature: Saved Messages). Без Android — проверяется тестами.
 * Избранное — это обычные [Conversation] с [ConversationKind.FAVORITES]: они хранятся в history.json,
 * но никогда не уходят в нейросеть (движок исключает их из контекста).
 */
object FavoritesLogic {
    const val MAXIMUM_FOLDERS = 50

    fun defaultTitle(english: Boolean): String = if (english) "Saved messages" else "Избранное"

    /** Все папки избранного в порядке показа: по pinOrder, затем по дате создания. */
    fun ordered(conversations: List<Conversation>): List<Conversation> =
        conversations.filter { it.kind == ConversationKind.FAVORITES }
            .sortedWith(compareBy<Conversation> { it.pinOrder }.thenBy { it.createdAt })

    /** Следующий свободный порядковый номер для новой папки избранного. */
    fun nextOrder(conversations: List<Conversation>): Int =
        (ordered(conversations).maxOfOrNull { it.pinOrder } ?: -1) + 1

    /** Заметка пользователя в избранном: обычное сообщение роли USER с вложениями. */
    fun noteMessage(text: String, attachments: List<MessageAttachment>, now: Instant = Instant.now()): ChatMessage =
        ChatMessage(
            role = MessageRole.USER,
            content = text.trim(),
            attachments = attachments,
            createdAt = now,
            inputKind = MessageInputKind.TEXT,
        )

    /**
     * Копия сообщения для пересылки в избранное: новый id, роль USER, дата — сейчас.
     * Вложения копируются в новые файлы через [copyPath] (даёт новый путь по старому или null),
     * чтобы избранное не зависело от исходного чата (его можно удалить).
     */
    fun cloneForFavorites(source: ChatMessage, now: Instant = Instant.now(), copyPath: (String) -> String?): ChatMessage {
        val attachments = source.attachments.map { attachment ->
            val newLocal = attachment.localPath?.let(copyPath)
            val newFrames = attachment.videoFramePaths?.mapNotNull(copyPath)?.takeIf { it.isNotEmpty() }
            attachment.copy(
                id = newId(),
                localPath = newLocal ?: attachment.localPath,
                videoFramePaths = newFrames ?: attachment.videoFramePaths,
            )
        }
        return ChatMessage(
            role = MessageRole.USER,
            content = source.content,
            attachments = attachments,
            createdAt = now,
            inputKind = MessageInputKind.TEXT,
            quote = source.quote,
        )
    }

    /** Можно ли удалить папку целиком (нельзя удалить последнюю — её вместо этого очищают). */
    fun canDeleteFolder(conversations: List<Conversation>, id: String): Boolean =
        ordered(conversations).count { it.id != id } >= 1

    /** Есть ли хотя бы одна папка избранного. */
    fun hasAny(conversations: List<Conversation>): Boolean =
        conversations.any { it.kind == ConversationKind.FAVORITES }
}
