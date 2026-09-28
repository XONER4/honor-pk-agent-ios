package com.honerai.app.core

import android.content.Context
import android.net.Uri
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.ChatTable
import com.honerai.app.data.Conversation
import com.honerai.app.data.HonorMemory
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageFeedback
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.data.SavedInstruction
import com.honerai.app.data.UsageStatistics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ВРЕМЕННАЯ заглушка: каркас собирается и запускается. Настоящая реализация
 * (порт ChatStore.swift) заменит этот файл целиком, сохранив конструктор.
 */
class ChatStore(private val context: Context, private val settings: AppSettings) : ChatStoreApi {
    override val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val selectedConversationId = MutableStateFlow<String?>(null)
    override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val draft = MutableStateFlow("")
    override val attachments = MutableStateFlow<List<MessageAttachment>>(emptyList())
    override val reasoningEnabled = MutableStateFlow(true)
    override val searchEnabled = MutableStateFlow(false)
    override val errorMessage = MutableStateFlow<String?>(null)
    override val isGenerating = MutableStateFlow(false)
    override val isLoadingHistory = MutableStateFlow(false)
    override val generationStatus = MutableStateFlow<String?>(null)
    override val typingMessageId = MutableStateFlow<String?>(null)
    override val pacer = TypingPacer()
    override val editingMessageId = MutableStateFlow<String?>(null)
    override val quotedFragment = MutableStateFlow<String?>(null)
    override val memories = MutableStateFlow<List<HonorMemory>>(emptyList())
    override val memoryEnabled = MutableStateFlow(true)
    override val instructionLibrary = MutableStateFlow<List<SavedInstruction>>(emptyList())
    override val statistics = MutableStateFlow(UsageStatistics())
    override val requestedGame = MutableStateFlow<String?>(null)

    override val canSend: Boolean get() = draft.value.isNotBlank()
    override fun selectedConversation(): Conversation? = conversations.value.firstOrNull { it.id == selectedConversationId.value }
    override fun sortedConversations(): List<Conversation> = conversations.value
    override fun archivedConversations(): List<Conversation> = emptyList()
    override fun send(inputKind: MessageInputKind) {}
    override fun stop() {}
    override fun regenerate(messageId: String) {}
    override fun edit(messageId: String) {}
    override fun cancelEditing() {}
    override fun setReasoningEnabled(enabled: Boolean) { reasoningEnabled.value = enabled }
    override fun setSearchEnabled(enabled: Boolean) { searchEnabled.value = enabled }
    override fun clearError() { errorMessage.value = null }
    override fun quote(fragment: String) { quotedFragment.value = fragment }
    override fun clearQuote() { quotedFragment.value = null }
    override fun addAttachment(attachment: MessageAttachment) { attachments.value = attachments.value + attachment }
    override fun removeAttachment(id: String) { attachments.value = attachments.value.filterNot { it.id == id } }
    override suspend fun importAttachment(uri: Uri): Result<MessageAttachment> = Result.failure(UnsupportedOperationException())
    override fun setFeedback(messageId: String, feedback: MessageFeedback?) {}
    override fun setReaction(messageId: String, emoji: String?) {}
    override fun consumeRequestedGame() { requestedGame.value = null }
    override fun postGameResult(text: String) {}
    override fun newChat() {}
    override fun selectChat(id: String) { selectedConversationId.value = id }
    override fun renameChat(id: String, title: String) {}
    override fun togglePin(ids: Set<String>) {}
    override fun moveChat(id: String, ontoId: String): Boolean = false
    override fun movePinned(id: String, offset: Int) {}
    override fun archiveChat(id: String) {}
    override fun restoreChat(id: String) {}
    override fun deleteChats(ids: Set<String>) {}
    override fun clearAllChats() {}
    override fun forkConversation(atMessageId: String): String? = null
    override fun purgeOldChats(olderThanDays: Int) {}
    override fun setMemoryEnabled(enabled: Boolean) { memoryEnabled.value = enabled }
    override fun addMemory(text: String): Boolean = false
    override fun updateMemory(id: String, text: String): Boolean = false
    override fun deleteMemory(id: String) {}
    override fun clearMemories() {}
    override fun pinInstruction(fromMessageId: String): Boolean = false
    override fun addInstruction(chatId: String, text: String, author: MessageRole, sourceMessageId: String?): Boolean = false
    override fun updateInstruction(chatId: String, id: String, text: String) {}
    override fun unpinInstruction(chatId: String, id: String, keepInLibrary: Boolean) {}
    override fun deleteInstruction(chatId: String, id: String) {}
    override fun saveInstructionToLibrary(text: String) {}
    override fun deleteSavedInstruction(id: String) {}
    override fun applySavedInstruction(id: String, chatId: String): Boolean = false
    override fun table(id: String): ChatTable? = null
    override fun saveTable(table: ChatTable, byUser: Boolean) {}
    override fun deleteTable(id: String) {}
    override suspend fun exportData(): Result<java.io.File> = Result.failure(UnsupportedOperationException())
    override suspend fun importData(uri: Uri): Result<Unit> = Result.failure(UnsupportedOperationException())
    override fun persistNow() {}
    override fun recordSessionTime(seconds: Double) {}
    override fun resetStatistics() {}
    override fun setResponseLanguage(code: String) {}
    override fun setProfile(name: String, birthday: String) {}
}
