package com.honerai.app.chat

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import com.honerai.app.ui.chat.HistoryGrouping
import com.honerai.app.ui.chat.HistorySearchRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class HistoryLogicTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun chat(id: String, ago: Duration, pinned: Boolean = false, title: String = id,
                     messages: List<ChatMessage>? = null): Conversation {
        val at = now.minus(ago)
        return Conversation(
            id = id, title = title, pinned = pinned, updatedAt = at, createdAt = at,
            messages = messages ?: listOf(ChatMessage(role = MessageRole.USER, content = "привет", createdAt = at)),
        )
    }

    @Test
    fun groupsByLastMessageDate() {
        val chats = listOf(
            chat("pinned", Duration.ofDays(40), pinned = true),
            chat("today", Duration.ofHours(2)),
            chat("yesterday", Duration.ofHours(20)),
            chat("week", Duration.ofDays(3)),
            chat("older", Duration.ofDays(30)),
        )
        val groups = HistoryGrouping.group(chats, english = false, now = now, zone = zone)
        assertEquals(listOf("pinned", "today", "yesterday", "week", "older"), groups.map { it.id })
        assertEquals(listOf("Закреплено", "Сегодня", "Вчера", "7 дней", "Ранее"), groups.map { it.title })
        groups.forEach { assertEquals(listOf(it.id), it.chats.map { chat -> chat.id }) }
    }

    @Test
    fun emptyGroupsAreHiddenAndTitlesAreEnglish() {
        val groups = HistoryGrouping.group(listOf(chat("a", Duration.ofMinutes(5))), english = true, now = now, zone = zone)
        assertEquals(1, groups.size)
        assertEquals("Today", groups.first().title)
    }

    @Test
    fun pinnedChatsKeepTheirOrderAndStayOutOfDateGroups() {
        val chats = listOf(chat("p2", Duration.ofHours(1), pinned = true), chat("p1", Duration.ofDays(9), pinned = true))
        val groups = HistoryGrouping.group(chats, english = false, now = now, zone = zone)
        assertEquals(listOf("pinned"), groups.map { it.id })
        assertEquals(listOf("p2", "p1"), groups.first().chats.map { it.id })
    }

    @Test
    fun searchLooksAtTitleTextReasoningAndAttachments() {
        val attachment = MessageAttachment(name = "Отчёт.pdf", kind = AttachmentKind.DOCUMENT, extractedText = "квартальная выручка")
        val chats = listOf(
            chat("title", Duration.ofHours(1), title = "Рецепт борща"),
            chat("content", Duration.ofHours(1), messages = listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "Столица Франции — Париж"))),
            chat("reasoning", Duration.ofHours(1), messages = listOf(ChatMessage(role = MessageRole.ASSISTANT, reasoning = "думаю про КОТЛИН"))),
            chat("attachment", Duration.ofHours(1), messages = listOf(ChatMessage(role = MessageRole.USER, attachments = listOf(attachment)))),
        )
        assertEquals(listOf("title"), HistorySearchRules.filter(chats, "борщ").map { it.id })
        assertEquals(listOf("content"), HistorySearchRules.filter(chats, "париж").map { it.id })
        assertEquals(listOf("reasoning"), HistorySearchRules.filter(chats, "котлин").map { it.id })
        assertEquals(listOf("attachment"), HistorySearchRules.filter(chats, "отчёт").map { it.id })
        assertEquals(listOf("attachment"), HistorySearchRules.filter(chats, "Выручка").map { it.id })
        assertTrue(HistorySearchRules.filter(chats, "марс").isEmpty())
    }

    @Test
    fun blankQueryReturnsAllChats() {
        val chats = listOf(chat("a", Duration.ofHours(1)), chat("b", Duration.ofHours(2)))
        assertEquals(chats, HistorySearchRules.filter(chats, "   "))
    }

    @Test
    fun revisionChangesWhenHistoryChanges() {
        val before = listOf(chat("a", Duration.ofHours(1)))
        val after = listOf(before.first().copy(title = "Новое название чата"))
        assertTrue(HistorySearchRules.revision(before) != HistorySearchRules.revision(after))
    }
}
