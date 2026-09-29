package com.honerai.admin.core

import com.honerai.admin.data.Message
import com.honerai.admin.data.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMergeTest {

    private fun msg(id: String, sender: String = Sender.USER, at: String = "2026-09-28T10:00:00Z", clientId: String = "", text: String = id) =
        Message(id = id, clientId = clientId, chatId = "chat", sender = sender, text = text, createdAt = at)

    private fun draft(clientId: String, at: String = "2026-09-28T10:05:00Z") =
        msg(ChatItem.LOCAL_PREFIX + clientId, Sender.ADMIN, at, clientId, "черновик")

    private fun ids(items: List<ChatItem>) = items.map { it.message.id }

    private val base = listOf(
        ChatItem(msg("1", at = "2026-09-28T10:00:00Z")),
        ChatItem(msg("2", Sender.ADMIN, "2026-09-28T10:01:00Z")),
        ChatItem(msg("3", at = "2026-09-28T10:02:00Z")),
    )

    @Test
    fun optimisticDraftIsReplacedByServerEchoByClientId() {
        var items = MessageMerge.addOptimistic(base, draft("c1"))
        assertEquals(SendState.SENDING, items.last().state)
        assertEquals("c1", items.last().key)

        val echo = msg("4", Sender.ADMIN, "2026-09-28T10:05:01Z", clientId = "c1", text = "черновик")
        items = MessageMerge.upsert(items, echo)
        assertEquals(listOf("1", "2", "3", "4"), ids(items))
        assertEquals(SendState.SENT, items.last().state)
        // Ключ строки не меняется — LazyColumn не мигает.
        assertEquals("c1", items.last().key)
    }

    @Test
    fun echoArrivingTwiceDoesNotDuplicate() {
        var items = MessageMerge.addOptimistic(base, draft("c1"))
        val echo = msg("4", Sender.ADMIN, "2026-09-28T10:05:01Z", clientId = "c1")
        items = MessageMerge.upsert(items, echo) // кадр WebSocket
        items = MessageMerge.upsert(items, echo) // ответ POST
        assertEquals(4, items.size)
        assertEquals(1, items.count { it.message.id == "4" })
    }

    @Test
    fun addOptimisticIsIdempotent() {
        val once = MessageMerge.addOptimistic(base, draft("c1"))
        assertSame(once, MessageMerge.addOptimistic(once, draft("c1")))
    }

    @Test
    fun peerMessageInsertedBeforePendingDrafts() {
        var items = MessageMerge.addOptimistic(base, draft("c1"))
        items = MessageMerge.upsert(items, msg("5", at = "2026-09-28T10:06:00Z"))
        // Черновики остаются внизу, пока не подтверждены.
        assertEquals(listOf("1", "2", "3", "5", ChatItem.LOCAL_PREFIX + "c1"), ids(items))
    }

    @Test
    fun outOfOrderMessageSortedByTime() {
        val items = MessageMerge.upsert(base, msg("1b", at = "2026-09-28T10:00:30Z"))
        assertEquals(listOf("1", "1b", "2", "3"), ids(items))
    }

    @Test
    fun failedAndRetryStates() {
        var items = MessageMerge.addOptimistic(base, draft("c1"))
        items = MessageMerge.markState(items, "c1", SendState.FAILED)
        assertEquals(Receipt.FAILED, MessageMerge.receipt(items.last()))
        items = MessageMerge.markState(items, "c1", SendState.SENDING)
        assertEquals(Receipt.SENDING, MessageMerge.receipt(items.last()))
        items = MessageMerge.remove(items, ChatItem.LOCAL_PREFIX + "c1")
        assertEquals(listOf("1", "2", "3"), ids(items))
    }

    @Test
    fun updatedFrameReplacesKnownAndIgnoresUnknown() {
        val edited = msg("2", Sender.ADMIN, "2026-09-28T10:01:00Z", text = "исправлено").copy(editedAt = "2026-09-28T10:03:00Z")
        val items = MessageMerge.applyUpdate(base, edited)
        assertEquals("исправлено", items[1].message.text)
        assertEquals(listOf("1", "2", "3"), ids(items))
        assertSame(base, MessageMerge.applyUpdate(base, msg("99")))
    }

    @Test
    fun deletedForEveryoneKeepsPlaceholder() {
        val items = MessageMerge.applyUpdate(base, msg("3").copy(deleted = true, text = ""))
        assertTrue(items[2].message.deleted)
        assertEquals(3, items.size)
        // Удалённое не считается последним сообщением собеседника для read.
        assertEquals("1", MessageMerge.lastPeerMessageId(items))
    }

    @Test
    fun prependOlderPageWithoutDuplicates() {
        val older = listOf(msg("-1", at = "2026-09-28T09:00:00Z"), msg("0", at = "2026-09-28T09:30:00Z"), msg("1"))
        val items = MessageMerge.prependOlder(base, older)
        assertEquals(listOf("-1", "0", "1", "2", "3"), ids(items))
        assertEquals("-1", MessageMerge.oldestServerId(items))
    }

    @Test
    fun replaceWithServerKeepsUnsentDraftsAndReadMarks() {
        var items = MessageMerge.addOptimistic(base, draft("c1"))
        items = MessageMerge.addOptimistic(items, draft("c2", "2026-09-28T10:06:00Z"))
        items = MessageMerge.applyRead(items, Sender.USER, "3")
        // Сервер уже знает c1 (сообщение 4), но не c2.
        val server = base.map { it.message } + msg("4", Sender.ADMIN, "2026-09-28T10:05:01Z", clientId = "c1")
        val merged = MessageMerge.replaceWithServer(items, server)
        assertEquals(listOf("1", "2", "3", "4", ChatItem.LOCAL_PREFIX + "c2"), ids(merged))
        assertTrue(merged[1].message.readByPeer)
    }

    @Test
    fun reactionsToggleForAdmin() {
        val m = msg("1").copy(reactions = mapOf("👍" to listOf("user")))
        assertEquals("👍", MessageMerge.nextReaction(m, "👍"))
        val withMine = MessageMerge.withAdminReaction(m, "👍")
        assertEquals(listOf("user", "admin"), withMine.reactions["👍"])
        assertNull(MessageMerge.nextReaction(withMine, "👍"))
        // Другая реакция заменяет прежнюю реакцию администратора.
        val swapped = MessageMerge.withAdminReaction(withMine, "🔥")
        assertEquals(listOf("user"), swapped.reactions["👍"])
        assertEquals(listOf("admin"), swapped.reactions["🔥"])
        val removed = MessageMerge.withAdminReaction(swapped, null)
        assertFalse(removed.reactions.containsKey("🔥"))
    }
}
