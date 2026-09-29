package com.honerai.app.chat

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import com.honerai.app.ui.library.AttachmentLibraryIndex
import com.honerai.app.ui.library.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Индекс библиотеки вложений: раскладка по вкладкам, порядок по дате, область одного чата. */
class AttachmentLibraryIndexTest {

    private fun attach(name: String, kind: AttachmentKind, summary: String? = null) =
        MessageAttachment(name = name, kind = kind, localPath = "/a/$name", summary = summary)

    private fun chat(id: String, title: String, vararg messages: ChatMessage) =
        Conversation(id = id, title = title, messages = messages.toList())

    @Test fun `attachments are grouped by tab`() {
        val m1 = ChatMessage(role = MessageRole.USER, attachments = listOf(
            attach("p.jpg", AttachmentKind.IMAGE),
            attach("v.mp4", AttachmentKind.VIDEO),
            attach("song.mp3", AttachmentKind.AUDIO),
            attach("doc.pdf", AttachmentKind.DOCUMENT),
        ))
        val index = AttachmentLibraryIndex.build(listOf(chat("c1", "Чат", m1)), scopeChatId = null)
        assertEquals(1, index[LibraryTab.PHOTOS]?.size)
        assertEquals(1, index[LibraryTab.VIDEOS]?.size)
        assertEquals(1, index[LibraryTab.MUSIC]?.size)
        assertEquals(1, index[LibraryTab.FILES]?.size)
    }

    @Test fun `voice recordings go to the voice tab`() {
        val m = ChatMessage(role = MessageRole.USER, attachments = listOf(
            attach("Голосовое сообщение.m4a", AttachmentKind.AUDIO, summary = "Аудио 0:12"),
            attach("track.mp3", AttachmentKind.AUDIO),
        ))
        val index = AttachmentLibraryIndex.build(listOf(chat("c", "Ч", m)), scopeChatId = null)
        assertEquals(1, index[LibraryTab.VOICE]?.size)
        assertEquals(1, index[LibraryTab.MUSIC]?.size)
    }

    @Test fun `stickers are ignored`() {
        val m = ChatMessage(role = MessageRole.USER, attachments = listOf(
            MessageAttachment(name = "😀", kind = AttachmentKind.STICKER)))
        val index = AttachmentLibraryIndex.build(listOf(chat("c", "Ч", m)), scopeChatId = null)
        assertTrue(index.isEmpty())
        assertNull(AttachmentLibraryIndex.tabFor(MessageAttachment(name = "😀", kind = AttachmentKind.STICKER)))
    }

    @Test fun `scope limits the index to one chat`() {
        val a = chat("a", "A", ChatMessage(role = MessageRole.USER, attachments = listOf(attach("a.jpg", AttachmentKind.IMAGE))))
        val b = chat("b", "B", ChatMessage(role = MessageRole.USER, attachments = listOf(attach("b.jpg", AttachmentKind.IMAGE))))
        val index = AttachmentLibraryIndex.build(listOf(a, b), scopeChatId = "a")
        assertEquals(1, index[LibraryTab.PHOTOS]?.size)
        assertEquals("A", index[LibraryTab.PHOTOS]?.first()?.chatTitle)
    }

    @Test fun `items are sorted newest first`() {
        val old = ChatMessage(role = MessageRole.USER, createdAt = Instant.parse("2020-01-01T00:00:00Z"),
            attachments = listOf(attach("old.jpg", AttachmentKind.IMAGE)))
        val recent = ChatMessage(role = MessageRole.USER, createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            attachments = listOf(attach("new.jpg", AttachmentKind.IMAGE)))
        val index = AttachmentLibraryIndex.build(listOf(chat("c", "Ч", old, recent)), scopeChatId = null)
        val photos = index[LibraryTab.PHOTOS].orEmpty()
        assertEquals("new.jpg", photos.first().attachment.name)
    }

    @Test fun `tabs with content lists only non empty tabs in order`() {
        val m = ChatMessage(role = MessageRole.USER, attachments = listOf(attach("p.jpg", AttachmentKind.IMAGE), attach("d.pdf", AttachmentKind.DOCUMENT)))
        val index = AttachmentLibraryIndex.build(listOf(chat("c", "Ч", m)), scopeChatId = null)
        assertEquals(listOf(LibraryTab.PHOTOS, LibraryTab.FILES), AttachmentLibraryIndex.tabsWithContent(index))
    }
}
