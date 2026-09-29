package com.honerai.admin.core

import com.honerai.admin.data.Message
import com.honerai.admin.data.Sender

/** Состояние отправки своего сообщения. */
enum class SendState { SENDING, FAILED, SENT }

/** Галочки под своим сообщением: часы, ошибка, ✓ отправлено, ✓✓ прочитано. */
enum class Receipt { NONE, SENDING, FAILED, SENT, READ }

/** Сообщение в ленте чата + локальное состояние отправки. */
data class ChatItem(val message: Message, val state: SendState = SendState.SENT) {
    /** Стабильный ключ LazyColumn: не меняется, когда черновик заменяется ответом сервера. */
    val key: String get() = message.clientId.ifEmpty { message.id }
    val isLocal: Boolean get() = message.id.startsWith(LOCAL_PREFIX)

    companion object {
        const val LOCAL_PREFIX = "local:"
    }
}

/**
 * Чистая логика ленты: оптимистичная отправка, эхо сервера по clientId, правки и удаления,
 * подгрузка старых страниц, прочтения. Лента упорядочена от старых к новым; черновики — в конце.
 */
object MessageMerge {

    /** Черновик своего сообщения, пока сервер не ответил. */
    fun addOptimistic(items: List<ChatItem>, message: Message): List<ChatItem> {
        if (items.any { it.key == message.clientId && message.clientId.isNotEmpty() }) return items
        return items + ChatItem(message, SendState.SENDING)
    }

    /**
     * Новое или обновлённое сообщение с сервера (ответ POST, кадр message / message.updated).
     * Совпадение по id — замена; по clientId — черновик становится настоящим сообщением на своём месте.
     */
    fun upsert(items: List<ChatItem>, incoming: Message): List<ChatItem> {
        val byId = items.indexOfFirst { it.message.id == incoming.id }
        if (byId >= 0) {
            val current = items[byId].message
            // Прочтение не «откатывается» из-за старой копии сообщения.
            val merged = incoming.copy(readByPeer = incoming.readByPeer || current.readByPeer)
            return items.toMutableList().also { it[byId] = ChatItem(merged, SendState.SENT) }
        }
        if (incoming.clientId.isNotEmpty()) {
            val byClient = items.indexOfFirst { it.isLocal && it.message.clientId == incoming.clientId }
            if (byClient >= 0) {
                val list = items.toMutableList()
                list.removeAt(byClient)
                return insertSorted(list, ChatItem(incoming, SendState.SENT))
            }
        }
        return insertSorted(items, ChatItem(incoming, SendState.SENT))
    }

    /** Кадр message.updated для сообщения, которого нет в загруженной части, игнорируется. */
    fun applyUpdate(items: List<ChatItem>, updated: Message): List<ChatItem> =
        if (items.any { it.message.id == updated.id }) upsert(items, updated) else items

    fun markState(items: List<ChatItem>, clientId: String, state: SendState): List<ChatItem> =
        items.map { if (it.isLocal && it.message.clientId == clientId) it.copy(state = state) else it }

    /** Удаление «у себя» или черновика. */
    fun remove(items: List<ChatItem>, id: String): List<ChatItem> = items.filterNot { it.message.id == id }

    /** Старая страница (before=…) — в начало, без дублей. */
    fun prependOlder(items: List<ChatItem>, older: List<Message>): List<ChatItem> {
        val known = items.mapTo(HashSet()) { it.message.id }
        val fresh = older.filter { it.id !in known }.map { ChatItem(it) }
        return fresh + items
    }

    /** Первая страница: сообщения сервера + ещё не отправленные черновики. */
    fun replaceWithServer(items: List<ChatItem>, server: List<Message>): List<ChatItem> {
        val serverClientIds = server.mapTo(HashSet()) { it.clientId }
        val drafts = items.filter { it.isLocal && it.message.clientId !in serverClientIds }
        var result = server.map { ChatItem(it) }
        // Прочтения, пришедшие раньше, не теряем.
        val readIds = items.filter { it.message.readByPeer }.mapTo(HashSet()) { it.message.id }
        if (readIds.isNotEmpty()) result = result.map { if (it.message.id in readIds) it.copy(message = it.message.copy(readByPeer = true)) else it }
        return result + drafts
    }

    /**
     * Кадр read: [who] прочитал всё до [messageId] включительно. Отмечаются сообщения другой стороны
     * (для who=user — сообщения администратора). Неизвестный id — без изменений.
     */
    fun applyRead(items: List<ChatItem>, who: String, messageId: String): List<ChatItem> {
        val index = items.indexOfFirst { it.message.id == messageId }
        if (index < 0) return items
        return items.mapIndexed { i, item ->
            val m = item.message
            if (i <= index && !item.isLocal && isOwnSide(m.sender, who) && !m.readByPeer) {
                item.copy(message = m.copy(readByPeer = true))
            } else item
        }
    }

    /** Когда читает пользователь — «прочитано» ставится сообщениям администратора (и наоборот). */
    private fun isOwnSide(sender: String, reader: String): Boolean = when (reader) {
        Sender.USER -> sender == Sender.ADMIN
        Sender.ADMIN -> sender == Sender.USER
        else -> false
    }

    fun receipt(item: ChatItem): Receipt {
        if (item.message.sender != Sender.ADMIN) return Receipt.NONE
        return when (item.state) {
            SendState.SENDING -> Receipt.SENDING
            SendState.FAILED -> Receipt.FAILED
            SendState.SENT -> if (item.message.readByPeer) Receipt.READ else Receipt.SENT
        }
    }

    /** Самое новое сообщение собеседника (пользователя или ИИ) — для кадра read. */
    fun lastPeerMessageId(items: List<ChatItem>): String? =
        items.lastOrNull { !it.isLocal && it.message.sender != Sender.ADMIN && !it.message.deleted }?.message?.id

    /** Самый старый серверный id — курсор before для следующей страницы. */
    fun oldestServerId(items: List<ChatItem>): String? = items.firstOrNull { !it.isLocal }?.message?.id

    /** Реакция администратора: повторное нажатие того же эмодзи снимает её. */
    fun nextReaction(message: Message, emoji: String): String? =
        if (message.reactions[emoji]?.contains(Sender.ADMIN) == true) null else emoji

    /** Оптимистичное применение своей реакции (одна реакция на сторону). */
    fun withAdminReaction(message: Message, emoji: String?): Message {
        val cleaned = message.reactions.mapValues { (_, who) -> who - Sender.ADMIN }.filterValues { it.isNotEmpty() }
        val next = if (emoji == null) cleaned else cleaned + (emoji to ((cleaned[emoji] ?: emptyList()) + Sender.ADMIN))
        return message.copy(reactions = next)
    }

    private fun insertSorted(items: List<ChatItem>, item: ChatItem): List<ChatItem> {
        val list = items.toMutableList()
        val created = item.message.createdAt
        // Ищем место с конца: новые сообщения почти всегда последние. Черновики остаются ниже серверных.
        var index = list.size
        while (index > 0) {
            val prev = list[index - 1]
            if (prev.isLocal) { index--; continue }
            if (created.isNotEmpty() && prev.message.createdAt.isNotEmpty() && compareIso(prev.message.createdAt, created) > 0) {
                index--; continue
            }
            break
        }
        list.add(index, item)
        return list
    }

    /** Сравнение ISO-времени: через Instant, если строки в разных форматах. */
    private fun compareIso(a: String, b: String): Int {
        val ia = Times.parse(a)
        val ib = Times.parse(b)
        return if (ia != null && ib != null) ia.compareTo(ib) else a.compareTo(b)
    }
}
