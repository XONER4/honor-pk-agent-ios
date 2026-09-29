package com.honerai.admin.core

import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.Message
import com.honerai.admin.data.Overview
import com.honerai.admin.data.Presence
import com.honerai.admin.data.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Галочки ✓ / ✓✓, кадр read, «печатает…» администратора и живые счётчики. */
class ReadReceiptTest {

    private fun m(id: String, sender: String, min: Int) =
        ChatItem(Message(id = id, chatId = "chat", sender = sender, text = id, createdAt = "2026-09-28T10:%02d:00Z".format(min)))

    private val items = listOf(
        m("a1", Sender.ADMIN, 0),
        m("u1", Sender.USER, 1),
        m("a2", Sender.ADMIN, 2),
        m("ai1", Sender.AI, 3),
        m("a3", Sender.ADMIN, 4),
    )

    @Test
    fun sentIsSingleTickUntilRead() {
        assertEquals(Receipt.SENT, MessageMerge.receipt(items[0]))
        assertEquals(Receipt.NONE, MessageMerge.receipt(items[1]))
        assertEquals(Receipt.NONE, MessageMerge.receipt(items[3]))
    }

    @Test
    fun userReadMarksAdminMessagesUpToId() {
        val read = MessageMerge.applyRead(items, Sender.USER, "ai1")
        assertEquals(Receipt.READ, MessageMerge.receipt(read[0]))
        assertEquals(Receipt.READ, MessageMerge.receipt(read[2]))
        // a3 новее прочитанного — остаётся одна галочка.
        assertEquals(Receipt.SENT, MessageMerge.receipt(read[4]))
        // Сообщения пользователя и ИИ не трогаем.
        assertFalse(read[1].message.readByPeer)
        assertFalse(read[3].message.readByPeer)
    }

    @Test
    fun adminReadDoesNotMarkAdminMessages() {
        val read = MessageMerge.applyRead(items, Sender.ADMIN, "a3")
        assertEquals(Receipt.SENT, MessageMerge.receipt(read[0]))
        assertTrue(read[1].message.readByPeer)
    }

    @Test
    fun unknownReadIdIsIgnored() {
        assertSame(items, MessageMerge.applyRead(items, Sender.USER, "nope"))
    }

    @Test
    fun readSurvivesStaleServerCopy() {
        val read = MessageMerge.applyRead(items, Sender.USER, "a3")
        val stale = items[4].message.copy(readByPeer = false, text = "правка")
        val merged = MessageMerge.upsert(read, stale)
        assertEquals(Receipt.READ, MessageMerge.receipt(merged[4]))
        assertEquals("правка", merged[4].message.text)
    }

    @Test
    fun readFrameTargetsNewestPeerMessage() {
        assertEquals("ai1", MessageMerge.lastPeerMessageId(items))
        assertNull(MessageMerge.lastPeerMessageId(listOf(m("a1", Sender.ADMIN, 0))))
    }

    @Test
    fun typingThrottle() {
        val t = TypingThrottle(intervalMs = 3_000, idleMs = 5_000)
        assertEquals(true, t.onInput(0, textEmpty = false))
        assertNull(t.onInput(1_000, textEmpty = false))
        assertEquals(true, t.onInput(3_100, textEmpty = false))
        assertNull(t.onTick(6_000))
        assertEquals(false, t.onTick(8_200))
        assertNull(t.stop())
        assertEquals(true, t.onInput(9_000, textEmpty = false))
        assertEquals(false, t.onInput(9_500, textEmpty = true))
        assertNull(t.onInput(9_600, textEmpty = true))
        assertEquals(true, t.onInput(9_700, textEmpty = false))
        assertEquals(false, t.stop())
    }

    @Test
    fun liveOverviewCounters() {
        val o = Overview(users = 10, online = 2, inBackground = 1)
        assertEquals(Overview(users = 10, online = 1, inBackground = 2), UserList.adjustOverview(o, Presence.FOREGROUND, Presence.BACKGROUND))
        assertEquals(Overview(users = 10, online = 3, inBackground = 1), UserList.adjustOverview(o, Presence.OFFLINE, Presence.FOREGROUND))
        assertEquals(Overview(users = 10, online = 2, inBackground = 0), UserList.adjustOverview(o, Presence.BACKGROUND, Presence.OFFLINE))
        assertSame(o, UserList.adjustOverview(o, Presence.OFFLINE, Presence.OFFLINE))
    }

    @Test
    fun userListFilterAndOrder() {
        val list = listOf(
            DeviceSummary("d1", displayName = "Борис", presence = Presence.OFFLINE, lastSeen = "2026-09-28T09:00:00Z"),
            DeviceSummary("d2", displayName = "Аня", presence = Presence.FOREGROUND),
            DeviceSummary("d3", displayName = "Вика", presence = Presence.BACKGROUND, deviceModel = "vivo V2250"),
            DeviceSummary("d4", displayName = "Гена", presence = Presence.OFFLINE, unreadForAdmin = 2, lastSeen = "2026-09-28T08:00:00Z"),
            DeviceSummary("d5", displayName = "Дима", blocked = true),
        )
        assertEquals(listOf("d4", "d2", "d3", "d1", "d5"), UserList.visible(list, UserFilter.ALL, "").map { it.deviceId })
        assertEquals(listOf("d2"), UserList.visible(list, UserFilter.ONLINE, "").map { it.deviceId })
        assertEquals(listOf("d3"), UserList.visible(list, UserFilter.BACKGROUND, "").map { it.deviceId })
        assertEquals(listOf("d5"), UserList.visible(list, UserFilter.BLOCKED, "").map { it.deviceId })
        assertEquals(listOf("d3"), UserList.visible(list, UserFilter.ALL, "VIVO").map { it.deviceId })
    }
}
