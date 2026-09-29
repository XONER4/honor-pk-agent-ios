package com.honerai.app.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/** Запись во вкладке «Уведомления». */
@Serializable
data class NotificationEntry(
    val id: String,
    /** admin/system/ai — рассылка сервера; message — сообщение в чате с администратором; answer — «Ответ готов». */
    val kind: String,
    val title: String,
    val body: String,
    val createdAtMs: Long,
    val read: Boolean = false,
    /** Чат на сервере (чат с администратором). */
    val chatId: String? = null,
    /** Локальный чат с Honer AI (для «Ответ готов»). */
    val localChatId: String? = null,
    /** Идентификатор уведомления на сервере — для POST /v1/notifications/read. */
    val serverId: String? = null,
) {
    companion object {
        const val KIND_MESSAGE = "message"
        const val KIND_ANSWER = "answer"
    }
}

/** День в списке уведомлений. */
data class NotificationDay(val key: String, val title: String, val items: List<NotificationEntry>)

/**
 * Локальный журнал уведомлений (JSON-файл). Новые — сверху, не больше [LIMIT] записей,
 * повтор по id не добавляется. Запись на диск — в фоне, одним потоком.
 */
class NotificationsStore(private val file: File, private val async: Boolean = true) {
    private val lock = Any()
    private val writer = if (async) Executors.newSingleThreadExecutor { Thread(it, "honer-cloud-notifications").apply { isDaemon = true } } else null
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<NotificationEntry>> = _entries.asStateFlow()

    val unreadCount: Int get() = _entries.value.count { !it.read }

    /** true — запись новая (добавлена). */
    fun add(entry: NotificationEntry): Boolean = mutate { list ->
        if (list.any { it.id == entry.id }) null
        else (listOf(entry) + list).sortedByDescending { it.createdAtMs }.take(LIMIT)
    }

    fun remove(id: String) { mutate { list -> list.filter { it.id != id } } }

    fun markRead(id: String) { mutate { list -> list.map { if (it.id == id) it.copy(read = true) else it } } }

    /** Прочитать всё; возвращает серверные id, которые нужно отметить на сервере. */
    fun markAllRead(): List<String> {
        val ids = _entries.value.filter { !it.read }.mapNotNull { it.serverId }
        mutate { list -> list.map { if (it.read) it else it.copy(read = true) } }
        return ids
    }

    /** Открыли чат с администратором — его сообщения в журнале прочитаны. */
    fun markChatRead(chatId: String) {
        mutate { list -> list.map { if (!it.read && it.kind == NotificationEntry.KIND_MESSAGE && it.chatId == chatId) it.copy(read = true) else it } }
    }

    fun markLocalChatRead(localChatId: String) {
        mutate { list -> list.map { if (!it.read && it.localChatId == localChatId) it.copy(read = true) else it } }
    }

    fun clearAll() { mutate { emptyList() } }

    private fun mutate(change: (List<NotificationEntry>) -> List<NotificationEntry>?): Boolean {
        val snapshot: List<NotificationEntry>
        synchronized(lock) {
            val next = change(_entries.value) ?: return false
            if (next == _entries.value) return false
            _entries.value = next
            snapshot = next
        }
        if (writer != null) writer.execute { save(snapshot) } else save(snapshot)
        return true
    }

    private fun load(): List<NotificationEntry> = runCatching {
        if (!file.exists()) return emptyList()
        CloudJson.decodeFromString(ListSerializer(NotificationEntry.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun save(list: List<NotificationEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(CloudJson.encodeToString(ListSerializer(NotificationEntry.serializer()), list))
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    companion object {
        const val LIMIT = 300
    }
}

/** Группировка по дням: «Сегодня», «Вчера», «12 сентября» (новые сверху). */
object NotificationGrouping {
    fun group(entries: List<NotificationEntry>, now: Instant, english: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<NotificationDay> {
        val today = now.atZone(zone).toLocalDate()
        return entries.sortedByDescending { it.createdAtMs }
            .groupBy { Instant.ofEpochMilli(it.createdAtMs).atZone(zone).toLocalDate() }
            .entries.sortedByDescending { it.key }
            .map { (day: LocalDate, items) -> NotificationDay(day.toString(), PresenceText.dayTitle(day, today, english), items) }
    }
}
