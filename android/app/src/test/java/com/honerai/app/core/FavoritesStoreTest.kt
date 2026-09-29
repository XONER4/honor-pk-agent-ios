package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.ConversationKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/** Поведение чата «Избранное» в хранилище: создание, заметки, пересылка, закрепление, удаление. */
class FavoritesStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var main: ExecutorCoroutineDispatcher

    @Before fun setUp() {
        main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
    }

    @After fun tearDown() { Dispatchers.resetMain(); main.close() }

    private fun onMain(block: suspend CoroutineScope.() -> Unit) = runBlocking(main) { block() }

    private fun store() = ChatStore.forTesting(folder.root, storageFile = File(folder.root, "h-${System.nanoTime()}.json"))

    @Test fun `ensureDefaultFavorites creates exactly one favorites chat`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        store.ensureDefaultFavorites()
        val favorites = store.conversations.value.filter { it.kind == ConversationKind.FAVORITES }
        assertEquals(1, favorites.size)
    }

    @Test fun `favorites are excluded from the normal sorted list and AI context`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        assertTrue(store.sortedConversations().none { it.kind == ConversationKind.FAVORITES })
    }

    @Test fun `add note appends a user message`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        val id = store.conversations.value.first { it.kind == ConversationKind.FAVORITES }.id
        store.addFavoriteNote(id, "моя заметка", emptyList())
        val chat = store.conversations.value.first { it.id == id }
        assertEquals(1, chat.messages.size)
        assertEquals(MessageRole.USER, chat.messages.first().role)
        assertEquals("моя заметка", chat.messages.first().content)
    }

    @Test fun `forward copies a message into favorites with a new id`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        val favId = store.conversations.value.first { it.kind == ConversationKind.FAVORITES }.id
        val source = ChatMessage(role = MessageRole.ASSISTANT, content = "полезный ответ")
        store.forwardToFavorites(favId, source)
        val chat = store.conversations.value.first { it.id == favId }
        assertEquals(1, chat.messages.size)
        assertEquals("полезный ответ", chat.messages.first().content)
        assertEquals(MessageRole.USER, chat.messages.first().role)
    }

    @Test fun `pin toggles and delete removes a favorite message`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        val id = store.conversations.value.first { it.kind == ConversationKind.FAVORITES }.id
        store.addFavoriteNote(id, "заметка", emptyList())
        val messageId = store.conversations.value.first { it.id == id }.messages.first().id
        store.toggleFavoriteMessagePin(id, messageId)
        assertTrue(store.conversations.value.first { it.id == id }.messages.first().pinnedInChat)
        store.deleteFavoriteMessage(id, messageId)
        assertTrue(store.conversations.value.first { it.id == id }.messages.isEmpty())
    }

    @Test fun `creating a folder adds another favorites chat`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        val newId = store.createFavoritesFolder("Работа")
        assertNotNull(newId)
        assertEquals(2, store.conversations.value.count { it.kind == ConversationKind.FAVORITES })
    }

    @Test fun `last favorites folder is cleared instead of deleted`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        val id = store.conversations.value.first { it.kind == ConversationKind.FAVORITES }.id
        store.addFavoriteNote(id, "заметка", emptyList())
        store.deleteFavoritesFolder(id)
        // Папка осталась, но пустая.
        val favorites = store.conversations.value.filter { it.kind == ConversationKind.FAVORITES }
        assertEquals(1, favorites.size)
        assertTrue(favorites.first().messages.isEmpty())
    }

    @Test fun `second folder can be deleted`() = onMain {
        val store = store()
        store.ensureDefaultFavorites()
        val extra = store.createFavoritesFolder("Работа")
        store.deleteFavoritesFolder(extra)
        assertEquals(1, store.conversations.value.count { it.kind == ConversationKind.FAVORITES })
    }
}
