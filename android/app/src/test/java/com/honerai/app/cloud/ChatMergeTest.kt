package com.honerai.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Слияние ленты: оптимистичная отправка, эхо, правки, удаление, очистка, страницы, непрочитанные. */
class ChatMergeTest {
    private fun msg(id: String, sender: String, time: String, text: String = id, clientId: String? = null, readByPeer: Boolean = false) =
        CloudMessage(id = id, clientId = clientId, chatId = "chat", sender = sender, text = text, createdAt = time, readByPeer = readByPeer)

    private fun sent(vararg messages: CloudMessage) = messages.map { ChatEntry(it) }

    private val t0 = "2026-09-29T10:00:00Z"
    private val t1 = "2026-09-29T10:01:00.000Z"
    private val t2 = "2026-09-29T10:02:00Z"
    private val t3 = "2026-09-29T10:03:00.500Z"

    @Test
    fun optimisticThenEchoKeepsKeyAndLocalFiles() {
        val local = LocalAttachment("/files/a.jpg", "image", "a.jpg", "image/jpeg", 10)
        val pending = ChatMerge.optimistic("chat", "c1", "Привет", null, listOf(local), Instant.parse(t1))
        assertEquals(Delivery.SENDING, pending.delivery)
        assertEquals("c1", pending.key)
        var list = sent(msg("m0", "admin", t0)) + pending
        // Эхо по WebSocket приходит раньше ответа на POST.
        list = ChatMerge.upsert(list, msg("m1", "user", t1, "Привет", clientId = "c1"))
        assertEquals(2, list.size)
        val echoed = list.last()
        assertEquals("m1", echoed.message.id)
        assertEquals(Delivery.SENT, echoed.delivery)
        assertEquals("c1", echoed.key)
        assertEquals(listOf(local), echoed.local)
        // Ответ POST с тем же сообщением — не дублируется.
        list = ChatMerge.upsert(list, msg("m1", "user", t1, "Привет", clientId = "c1"))
        assertEquals(2, list.size)
    }

    @Test
    fun pendingStaysBelowSentAndFailedCanRetry() {
        val pending = ChatMerge.optimistic("chat", "c1", "текст", null, emptyList(), Instant.parse(t0))
        var list = listOf(pending)
        list = ChatMerge.upsert(list, msg("m2", "admin", t2))
        assertEquals(listOf("m2", "local:c1"), list.map { it.message.id })
        list = ChatMerge.setDelivery(list, "c1", Delivery.FAILED)
        assertEquals(Delivery.FAILED, list.last().delivery)
        list = ChatMerge.setDelivery(list, "c1", Delivery.SENDING)
        assertEquals(Delivery.SENDING, list.last().delivery)
    }

    @Test
    fun ordersByParsedTimeNotString() {
        // «…:01:00.000Z» и «…:00Z» — строки сравниваются неверно, время — верно.
        val list = ChatMerge.normalize(sent(msg("b", "admin", t1), msg("a", "admin", t0), msg("d", "user", t3), msg("c", "user", t2)))
        assertEquals(listOf("a", "b", "c", "d"), list.map { it.message.id })
    }

    @Test
    fun updateEditsAndDeleteForEveryoneRemoves() {
        var list = sent(msg("m1", "admin", t0), msg("m2", "user", t1))
        list = ChatMerge.update(list, msg("m1", "admin", t0, "исправлено").copy(editedAt = t2))
        assertEquals("исправлено", list.first().message.text)
        // Надгробие deleted:true с пустым текстом — сообщение исчезает из ленты.
        list = ChatMerge.update(list, msg("m2", "user", t1, "").copy(deleted = true))
        assertEquals(listOf("m1"), list.map { it.message.id })
        // Обновление сообщения вне загруженного окна пропускается.
        assertEquals(list, ChatMerge.update(list, msg("zzz", "admin", t3)))
        // Новое удалённое сообщение не добавляется.
        assertEquals(list, ChatMerge.upsert(list, msg("m9", "admin", t3).copy(deleted = true)))
    }

    @Test
    fun removeAndClear() {
        val list = sent(msg("m1", "admin", t0), msg("m2", "user", t1))
        assertEquals(listOf("m2"), ChatMerge.remove(list, "m1").map { it.message.id })
        assertTrue(ChatMerge.clear().isEmpty())
    }

    @Test
    fun latestPageIsTruthForItsRangeAndKeepsOlderAndPending() {
        val pending = ChatMerge.optimistic("chat", "c5", "в пути", null, emptyList(), Instant.parse(t3))
        val list = sent(msg("old", "admin", "2026-09-28T09:00:00Z"), msg("m1", "admin", t1), msg("gone", "admin", t2)) + pending
        val page = listOf(msg("m1", "admin", t1, "обновлено"), msg("m3", "admin", t3))
        val merged = ChatMerge.mergeLatest(list, page)
        assertEquals(listOf("old", "m1", "m3", "local:c5"), merged.map { it.message.id })
        assertEquals("обновлено", merged[1].message.text)
        // Пустая свежая страница — на сервере ничего нет (очищено): остаются только неотправленные.
        assertEquals(listOf("local:c5"), ChatMerge.mergeLatest(list, emptyList()).map { it.message.id })
        // Эхо отправки в странице заменяет оптимистичное.
        val echoed = ChatMerge.mergeLatest(list, listOf(msg("m5", "user", t3, "в пути", clientId = "c5")))
        assertEquals(1, echoed.count { it.message.clientId == "c5" })
        assertFalse(echoed.last().pending)
    }

    @Test
    fun olderPagePrependsWithoutDuplicates() {
        val list = sent(msg("m3", "admin", t2), msg("m4", "user", t3))
        val merged = ChatMerge.mergeOlder(list, listOf(msg("m1", "admin", t0), msg("m2", "user", t1), msg("m3", "admin", t2)))
        assertEquals(listOf("m1", "m2", "m3", "m4"), merged.map { it.message.id })
    }

    @Test
    fun unreadCounting() {
        val list = sent(
            msg("a1", "admin", t0), msg("u1", "user", t1), msg("a2", "admin", t2), msg("ai", "ai", t3),
        )
        // Не открывали — все сообщения собеседника (администратор и Honer AI).
        assertEquals(3, ChatMerge.unreadCount(list, 0L, 0))
        // Прочитано до a2 включительно.
        assertEquals(1, ChatMerge.unreadCount(list, ChatMerge.timeOf(t2), 0))
        // Свои сообщения и неотправленные не считаются; пустая лента — число сервера.
        assertEquals(0, ChatMerge.unreadCount(list, ChatMerge.timeOf(t3), 5))
        assertEquals(5, ChatMerge.unreadCount(emptyList(), 0L, 5))
        // readByPeer у сообщения администратора — прочитано на сервере.
        val serverRead = sent(msg("a1", "admin", t0, readByPeer = true), msg("a2", "admin", t2))
        assertEquals(1, ChatMerge.unreadCount(serverRead, 0L, 0))
        assertEquals("ai", ChatMerge.newestPeer(list)?.message?.id)
    }

    @Test
    fun readReceipts() {
        val list = sent(msg("u1", "user", t0), msg("a1", "admin", t1), msg("u2", "user", t2))
        val upTo = ChatMerge.readUpToTime(list, "a1", 0L)
        assertEquals(ChatMerge.timeOf(t1), upTo)
        assertTrue(ChatMerge.isReadByPeer(list[0], upTo))
        assertFalse(ChatMerge.isReadByPeer(list[2], upTo))
        assertTrue(ChatMerge.isReadByPeer(ChatEntry(msg("u3", "user", t3, readByPeer = true)), 0L))
        // Указатель только растёт; неизвестный id не меняет его.
        assertEquals(upTo, ChatMerge.readUpToTime(list, "u1", upTo))
        assertEquals(upTo, ChatMerge.readUpToTime(list, "nope", upTo))
        // Неотправленное — никогда не «прочитано».
        val pending = ChatMerge.optimistic("chat", "c", "x", null, emptyList(), Instant.parse(t0))
        assertFalse(ChatMerge.isReadByPeer(pending, Long.MAX_VALUE))
    }

    @Test
    fun reactionsTogglePerUser() {
        val message = msg("m1", "admin", t0).copy(reactions = mapOf("👍" to listOf("admin")))
        assertEquals("❤️", ChatMerge.toggledReaction(message, "❤️"))
        val liked = ChatMerge.withReaction(message, "❤️")
        assertEquals(mapOf("👍" to listOf("admin"), "❤️" to listOf("user")), liked.reactions)
        // Повторное нажатие той же — снять; новая заменяет прежнюю.
        assertNull(ChatMerge.toggledReaction(liked, "❤️"))
        val switched = ChatMerge.withReaction(liked, "👍")
        assertEquals(mapOf("👍" to listOf("admin", "user")), switched.reactions)
        assertEquals(mapOf("👍" to listOf("admin")), ChatMerge.withReaction(switched, null).reactions)
    }

    @Test
    fun onePinnedMessagePerChat() {
        var list = sent(msg("m1", "admin", t0).copy(pinned = true), msg("m2", "user", t1))
        assertEquals("m1", ChatMerge.pinned(list, null)?.message?.id)
        list = ChatMerge.applyPin(list, msg("m2", "user", t1).copy(pinned = true))
        assertEquals(listOf(false, true), list.map { it.message.pinned })
        assertEquals("m2", ChatMerge.pinned(list, null)?.message?.id)
        assertEquals("m1", ChatMerge.pinned(list, "m1")?.message?.id)
        assertNull(ChatMerge.pinned(ChatMerge.applyPin(list, msg("m2", "user", t1)), null))
    }
}
