package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.ConversationKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика чата «Избранное»: порядок папок, заметки и пересылка сообщений. */
class FavoritesLogicTest {

    private fun favorite(order: Int, id: String = com.honerai.app.data.newId()) =
        Conversation(id = id, title = "Избранное $order", kind = ConversationKind.FAVORITES, pinOrder = order)

    @Test fun `ordered returns only favorites sorted by pin order`() {
        val list = listOf(
            Conversation(title = "Обычный", kind = ConversationKind.NORMAL),
            favorite(2), favorite(0), favorite(1),
        )
        val ordered = FavoritesLogic.ordered(list)
        assertEquals(3, ordered.size)
        assertEquals(listOf(0, 1, 2), ordered.map { it.pinOrder })
    }

    @Test fun `next order is one past the highest`() {
        val list = listOf(favorite(0), favorite(3))
        assertEquals(4, FavoritesLogic.nextOrder(list))
        assertEquals(0, FavoritesLogic.nextOrder(emptyList()))
    }

    @Test fun `note message carries text and attachments as a user message`() {
        val attachment = MessageAttachment(name = "фото.jpg", kind = AttachmentKind.IMAGE, localPath = "/a/b.jpg")
        val note = FavoritesLogic.noteMessage("  заметка  ", listOf(attachment))
        assertEquals(MessageRole.USER, note.role)
        assertEquals("заметка", note.content)
        assertEquals(1, note.attachments.size)
    }

    @Test fun `clone for favorites gives new ids and copies attachment files`() {
        val source = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "ответ",
            attachments = listOf(MessageAttachment(name = "video.mp4", kind = AttachmentKind.VIDEO,
                localPath = "/orig/v.mp4", videoFramePaths = listOf("/orig/f1.jpg", "/orig/f2.jpg"))),
        )
        val copies = HashMap<String, String>()
        val clone = FavoritesLogic.cloneForFavorites(source) { path -> "/copy/${path.substringAfterLast('/')}".also { copies[path] = it } }
        assertEquals(MessageRole.USER, clone.role)
        assertEquals("ответ", clone.content)
        assertNotEquals(source.id, clone.id)
        val cloned = clone.attachments.first()
        assertNotEquals(source.attachments.first().id, cloned.id)
        assertEquals("/copy/v.mp4", cloned.localPath)
        assertEquals(listOf("/copy/f1.jpg", "/copy/f2.jpg"), cloned.videoFramePaths)
    }

    @Test fun `last folder cannot be deleted`() {
        val only = favorite(0, id = "one")
        assertFalse(FavoritesLogic.canDeleteFolder(listOf(only), "one"))
        val two = listOf(favorite(0, id = "one"), favorite(1, id = "two"))
        assertTrue(FavoritesLogic.canDeleteFolder(two, "one"))
    }
}
