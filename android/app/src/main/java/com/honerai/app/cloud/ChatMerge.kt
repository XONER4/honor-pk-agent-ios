package com.honerai.app.cloud

import kotlinx.serialization.Serializable
import java.time.Instant

/** Состояние доставки своего сообщения. */
@Serializable
enum class Delivery { SENDING, FAILED, SENT }

/** Файл отправляемого сообщения на телефоне (пока он грузится — показываем его, а не сетевую копию). */
@Serializable
data class LocalAttachment(
    val path: String,
    val kind: String,
    val name: String,
    val mime: String,
    val size: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** Строка ленты чата с администратором: сообщение сервера или отправляемое (оптимистичное). */
@Serializable
data class ChatEntry(
    val message: CloudMessage,
    val delivery: Delivery = Delivery.SENT,
    val local: List<LocalAttachment> = emptyList(),
) {
    /** Стабильный ключ списка: у своего сообщения — clientId (не меняется, когда приходит ответ сервера). */
    val key: String get() = message.clientId?.takeIf { it.isNotEmpty() } ?: message.id
    val pending: Boolean get() = delivery != Delivery.SENT
    val timeMs: Long get() = ChatMerge.timeOf(message.createdAt)
}

/**
 * Слияние ленты (чистые функции, проверяются тестами): оптимистичная отправка и эхо сервера,
 * правки, удаление, очистка, страницы истории, непрочитанные и отметки «прочитано».
 */
object ChatMerge {
    const val LOCAL_PREFIX = "local:"

    fun timeOf(iso: String?): Long {
        if (iso.isNullOrEmpty()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    /** Свое сообщение до ответа сервера. */
    fun optimistic(chatId: String, clientId: String, text: String, replyTo: String?, local: List<LocalAttachment>, now: Instant): ChatEntry =
        ChatEntry(
            message = CloudMessage(
                id = LOCAL_PREFIX + clientId, clientId = clientId, chatId = chatId, sender = CloudMessage.SENDER_USER,
                text = text, replyTo = replyTo, createdAt = now.toString(),
            ),
            delivery = Delivery.SENDING,
            local = local,
        )

    /** Порядок: отправленные — по времени сервера; неотправленные — внизу, в порядке создания. */
    fun normalize(entries: List<ChatEntry>): List<ChatEntry> {
        val (pending, sent) = entries.partition { it.pending }
        return sent.sortedWith(compareBy<ChatEntry> { it.timeMs }.thenBy { it.message.id }) + pending.sortedBy { it.timeMs }
    }

    /**
     * Новое сообщение (кадр «message», ответ на POST, опрос): эхо своего заменяет оптимистичное
     * по clientId, повтор по id — обновляет, удалённое для всех — убирает.
     */
    fun upsert(entries: List<ChatEntry>, message: CloudMessage): List<ChatEntry> {
        val byId = entries.indexOfFirst { it.message.id == message.id }
        val byClient = if (byId < 0 && !message.clientId.isNullOrEmpty()) entries.indexOfFirst { it.message.clientId == message.clientId } else -1
        val index = if (byId >= 0) byId else byClient
        if (message.deleted) return if (index >= 0) entries.filterIndexed { i, _ -> i != index } else entries
        val result = entries.toMutableList()
        if (index >= 0) {
            result[index] = ChatEntry(message, Delivery.SENT, result[index].local)
        } else {
            result.add(ChatEntry(message, Delivery.SENT))
        }
        return normalize(result)
    }

    /** Правка, реакция, закрепление, удаление для всех. Сообщение вне загруженного окна пропускается. */
    fun update(entries: List<ChatEntry>, message: CloudMessage): List<ChatEntry> {
        val index = entries.indexOfFirst { it.message.id == message.id }
        if (index < 0) return entries
        if (message.deleted) return entries.filterIndexed { i, _ -> i != index }
        return entries.toMutableList().also { it[index] = ChatEntry(message, Delivery.SENT, it[index].local) }
    }

    /** Свежая страница (новейшие сообщения): сервер — истина для своего промежутка. */
    fun mergeLatest(entries: List<ChatEntry>, page: List<CloudMessage>): List<ChatEntry> {
        val visible = page.filter { !it.deleted }
        val pageIds = visible.map { it.id }.toHashSet()
        val pageClientIds = visible.mapNotNull { it.clientId }.toHashSet()
        val oldest = visible.minOfOrNull { timeOf(it.createdAt) }
        val previous = entries.associateBy { it.message.id }
        val pendingByClient = entries.filter { it.pending }.associateBy { it.message.clientId }
        // Старше страницы — оставляем (подгружены раньше); внутри промежутка — только то, что прислал сервер.
        val older = if (oldest == null) emptyList() else entries.filter { !it.pending && it.timeMs < oldest && it.message.id !in pageIds }
        val fresh = visible.map { message ->
            val local = previous[message.id]?.local ?: pendingByClient[message.clientId]?.local ?: emptyList()
            ChatEntry(message, Delivery.SENT, local)
        }
        val pending = entries.filter { it.pending && it.message.clientId !in pageClientIds }
        // Пустая страница — на сервере нет сообщений (чат очищен): остаются только неотправленные.
        return normalize(older + fresh + pending)
    }

    /** Страница старых сообщений (прокрутка вверх). */
    fun mergeOlder(entries: List<ChatEntry>, page: List<CloudMessage>): List<ChatEntry> {
        val known = entries.map { it.message.id }.toHashSet()
        val added = page.filter { !it.deleted && it.id !in known }.map { ChatEntry(it, Delivery.SENT) }
        return normalize(added + entries)
    }

    /** Обновление с учётом закрепа: закреплённое сообщение в чате одно, у прежнего флаг снимается. */
    fun applyPin(entries: List<ChatEntry>, message: CloudMessage): List<ChatEntry> {
        val base = if (message.pinned && !message.deleted) entries.map {
            if (it.message.pinned && it.message.id != message.id) it.copy(message = it.message.copy(pinned = false)) else it
        } else entries
        return update(base, message)
    }

    fun clear(): List<ChatEntry> = emptyList()

    fun remove(entries: List<ChatEntry>, id: String): List<ChatEntry> = entries.filter { it.message.id != id }

    fun setDelivery(entries: List<ChatEntry>, clientId: String, delivery: Delivery): List<ChatEntry> =
        entries.map { if (it.message.clientId == clientId && it.pending) it.copy(delivery = delivery) else it }

    /** Сообщение собеседника (администратор или Honer AI). */
    fun isPeer(message: CloudMessage): Boolean = !message.fromUser && !message.deleted

    /**
     * Непрочитанные: сообщения собеседника новее отметки [lastReadMs]. Пока лента не загружена —
     * число с сервера [serverUnread].
     */
    fun unreadCount(entries: List<ChatEntry>, lastReadMs: Long, serverUnread: Int): Int {
        if (entries.isEmpty()) return serverUnread
        // readByPeer у сообщения администратора = пользователь его уже прочитал (указатель на сервере).
        return entries.count { !it.pending && isPeer(it.message) && !it.message.readByPeer && it.timeMs > lastReadMs }
    }

    /** Самое новое сообщение собеседника (его id уходит в кадре «read»). */
    fun newestPeer(entries: List<ChatEntry>): ChatEntry? = entries.lastOrNull { !it.pending && isPeer(it.message) }

    /** «Прочитано до»: время сообщения, до которого прочитал собеседник (кадр read who=admin). */
    fun readUpToTime(entries: List<ChatEntry>, messageId: String?, current: Long): Long {
        if (messageId == null) return current
        val time = entries.firstOrNull { it.message.id == messageId }?.timeMs ?: return current
        return maxOf(current, time)
    }

    /** ✓✓ у своего сообщения. */
    fun isReadByPeer(entry: ChatEntry, peerReadUpToMs: Long): Boolean =
        entry.message.fromUser && !entry.pending && (entry.message.readByPeer || (peerReadUpToMs > 0 && entry.timeMs <= peerReadUpToMs))

    /** Закреплённое сообщение: из чата (pinnedMessageId), иначе самое новое с pinned=true. */
    fun pinned(entries: List<ChatEntry>, pinnedMessageId: String?): ChatEntry? {
        if (pinnedMessageId != null) entries.firstOrNull { it.message.id == pinnedMessageId }?.let { return it }
        return entries.lastOrNull { it.message.pinned }
    }

    /** Реакция пользователя: повторное нажатие той же — снять. */
    fun toggledReaction(message: CloudMessage, emoji: String): String? {
        val mine = message.reactions.entries.firstOrNull { CloudMessage.SENDER_USER in it.value }?.key
        return if (mine == emoji) null else emoji
    }

    /** Оптимистичная реакция: у пользователя одна реакция на сообщение. */
    fun withReaction(message: CloudMessage, emoji: String?): CloudMessage {
        val reactions = message.reactions.mapValues { (_, who) -> who - CloudMessage.SENDER_USER }.filterValues { it.isNotEmpty() }.toMutableMap()
        if (emoji != null) reactions[emoji] = (reactions[emoji].orEmpty() + CloudMessage.SENDER_USER).distinct()
        return message.copy(reactions = reactions)
    }
}
