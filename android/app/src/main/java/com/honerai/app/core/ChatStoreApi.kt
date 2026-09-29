package com.honerai.app.core

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
 * Контракт хранилища чатов — то же, что ChatStore на iPhone.
 * Экраны пишутся против этого интерфейса; реализация — [ChatStore] (движок чата:
 * запросы к нейросети, инструменты, интернет, память, таблицы, сохранение истории).
 * Все методы вызываются с главного потока; тяжёлая работа уходит в фоновые корутины.
 */
interface ChatStoreApi {
    // ---- Состояние ----
    val conversations: StateFlow<List<Conversation>>
    val selectedConversationId: StateFlow<String?>
    /** Сообщения выбранного чата. */
    val messages: StateFlow<List<ChatMessage>>
    /** Текст в поле ввода (экран пишет в него напрямую). */
    val draft: MutableStateFlow<String>
    /** Вложения, прикреплённые к следующему сообщению. */
    val attachments: StateFlow<List<MessageAttachment>>
    val reasoningEnabled: StateFlow<Boolean>
    val searchEnabled: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>
    val isGenerating: StateFlow<Boolean>
    val isLoadingHistory: StateFlow<Boolean>
    /** «Ищу в интернете…», «Размышляю…» — строка состояния, пока идёт ответ. */
    val generationStatus: StateFlow<String?>
    /** Сообщение, которое сейчас печатается (его показывает [pacer]). */
    val typingMessageId: StateFlow<String?>
    val pacer: TypingPacer
    val editingMessageId: StateFlow<String?>
    /** Процитированный фрагмент над полем ввода. */
    val quotedFragment: StateFlow<String?>
    val memories: StateFlow<List<HonorMemory>>
    val memoryEnabled: StateFlow<Boolean>
    val instructionLibrary: StateFlow<List<SavedInstruction>>
    val statistics: StateFlow<UsageStatistics>
    /** Игра, которую попросила открыть нейросеть (сырой идентификатор: chess, checkers, durak, slots). */
    val requestedGame: StateFlow<String?>
    // agent: важное действие агента, ждущее подтверждения (карточка «Подтвердить»/«Отмена»).
    val pendingAgentAction: StateFlow<com.honerai.app.core.agent.AgentPendingAction?>
    /** Подтвердить (true) или отменить (false) действие агента. */
    fun confirmPendingAction(confirm: Boolean)

    val canSend: Boolean
    fun selectedConversation(): Conversation?
    /** Закреплённые сверху (в своём порядке), остальные — по времени последнего сообщения. */
    fun sortedConversations(): List<Conversation>
    fun archivedConversations(): List<Conversation>

    // ---- Переписка ----
    fun send(inputKind: MessageInputKind = MessageInputKind.TEXT)
    fun stop()
    fun regenerate(messageId: String)
    fun edit(messageId: String)
    fun cancelEditing()
    fun setReasoningEnabled(enabled: Boolean)
    fun setSearchEnabled(enabled: Boolean)
    fun clearError()
    fun quote(fragment: String)
    fun clearQuote()
    fun addAttachment(attachment: MessageAttachment)
    fun removeAttachment(id: String)
    /** Импорт файла, выбранного пользователем (фото, видео, аудио, документ). */
    suspend fun importAttachment(uri: Uri): Result<MessageAttachment>
    fun setFeedback(messageId: String, feedback: MessageFeedback?)
    fun setReaction(messageId: String, emoji: String?)
    fun consumeRequestedGame()
    fun postGameResult(text: String)

    // ---- Чаты ----
    fun newChat()
    fun selectChat(id: String)
    fun renameChat(id: String, title: String)
    fun togglePin(ids: Set<String>)
    fun moveChat(id: String, ontoId: String): Boolean
    fun movePinned(id: String, offset: Int)
    fun archiveChat(id: String)
    fun restoreChat(id: String)
    fun deleteChats(ids: Set<String>)
    fun clearAllChats()
    fun forkConversation(atMessageId: String): String?
    fun purgeOldChats(olderThanDays: Int)

    // ---- Память ----
    fun setMemoryEnabled(enabled: Boolean)
    fun addMemory(text: String): Boolean
    fun updateMemory(id: String, text: String): Boolean
    fun deleteMemory(id: String)
    fun clearMemories()

    // ---- Закреплённые инструкции ----
    fun pinInstruction(fromMessageId: String): Boolean
    fun addInstruction(chatId: String, text: String, author: MessageRole = MessageRole.USER, sourceMessageId: String? = null): Boolean
    fun updateInstruction(chatId: String, id: String, text: String)
    fun unpinInstruction(chatId: String, id: String, keepInLibrary: Boolean = true)
    fun deleteInstruction(chatId: String, id: String)
    fun saveInstructionToLibrary(text: String)
    fun deleteSavedInstruction(id: String)
    fun applySavedInstruction(id: String, chatId: String): Boolean

    // ---- Избранное (appui: локальный чат «Избранное», наружу ничего не уходит) ----
    /** Создать чат «Избранное» по умолчанию, если ни одного ещё нет. */
    fun ensureDefaultFavorites()
    /** Создать дополнительную папку избранного с названием; возвращает её id. */
    fun createFavoritesFolder(name: String): String
    /** Удалить папку избранного (последнюю удалить нельзя — она очищается). */
    fun deleteFavoritesFolder(id: String)
    /** Очистить всю переписку папки избранного. */
    fun clearFavoritesHistory(id: String)
    /** Добавить заметку (текст + вложения) в чат избранного. Вложения уже в папке приложения. */
    fun addFavoriteNote(chatId: String, text: String, attachments: List<MessageAttachment>)
    /** Переслать сообщение из обычного чата в папку избранного (копирует текст и вложения). */
    fun forwardToFavorites(chatId: String, message: ChatMessage)
    /** Закрепить/открепить сообщение внутри чата избранного. */
    fun toggleFavoriteMessagePin(chatId: String, messageId: String)
    /** Удалить одно сообщение из чата избранного. */
    fun deleteFavoriteMessage(chatId: String, messageId: String)
    /** Поменять порядок папки избранного среди других папок избранного. */
    fun moveFavorite(id: String, offset: Int)
    /** Очистить вложения, ожидающие отправки (используется композером избранного). */
    fun clearPendingAttachments()

    // ---- Таблицы ----
    fun table(id: String): ChatTable?
    fun saveTable(table: ChatTable, byUser: Boolean = true)
    fun deleteTable(id: String)

    // ---- Данные ----
    /** Экспорт истории в JSON (тот же формат, что у iPhone). Возвращает файл во временной папке. */
    suspend fun exportData(): Result<java.io.File>
    suspend fun importData(uri: Uri): Result<Unit>
    fun persistNow()
    fun recordSessionTime(seconds: Double)
    fun resetStatistics()
    /** Язык ответов нейросети: "ru" или "en". */
    fun setResponseLanguage(code: String)
    fun setProfile(name: String, birthday: String)
}
