package com.honerai.app.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.honerai.app.BuildConfig
import com.honerai.app.data.ChatInstruction
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.ChatTable
import com.honerai.app.data.Conversation
import com.honerai.app.data.ConversationKind
import com.honerai.app.data.DeepSeekConfiguration
import com.honerai.app.data.GenerationStep
import com.honerai.app.data.HistoryArchive
import com.honerai.app.data.HonerJson
import com.honerai.app.data.HonorMemory
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageFeedback
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.data.SavedInstruction
import com.honerai.app.data.UsageStatistics
import com.honerai.app.data.WebSource
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.newId
import com.honerai.app.device.AttachmentImporter
import com.honerai.app.device.DeviceInfo
import com.honerai.app.device.GenerationService
import com.honerai.app.device.GuardVerdict
import com.honerai.app.device.HonerNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Движок чата (перенос ChatStore.swift): чаты, отправка и остановка, цикл ответа
 * с инструментами, поиск, память, закреплённые инструкции, таблицы, ветки,
 * сохранение истории и резервные копии в формате iPhone.
 *
 * Все публичные методы вызываются с главного потока. Состояние меняется только
 * на главном потоке, сеть и диск — в фоне.
 */
class ChatStore internal constructor(
    private val context: Context?,
    private val settings: AppSettings?,
    private val storageFile: File,
    private val attachmentsRoot: File,
    private val injectedClient: DeepSeekStreaming?,
    searchClient: WebSearching?,
    private val prefs: KeyValueStore,
    private var configuration: DeepSeekConfiguration,
    loadHistoryAsynchronously: Boolean,
    /** Android-обвязка: печать по кадрам экрана, фоновая служба, уведомления. В тестах выключена. */
    private val platform: Boolean,
) : ChatStoreApi {

    constructor(context: Context, settings: AppSettings) : this(
        context = context.applicationContext,
        settings = settings,
        storageFile = File(context.filesDir, "history.json"),
        attachmentsRoot = AttachmentImporter.attachmentsDir(context),
        injectedClient = null,
        searchClient = null,
        prefs = SharedPrefsStore(context),
        configuration = DeepSeekConfiguration(apiKey = KeyVault.deepSeekKey, language = if (settings.language.value == "en") "en" else "ru"),
        loadHistoryAsynchronously = true,
        platform = true,
    )

    // ---- Состояние ----

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _selected = MutableStateFlow<String?>(null)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _attachments = MutableStateFlow<List<MessageAttachment>>(emptyList())
    private val _reasoningEnabled = MutableStateFlow(prefs.getString(KEY_REASONING)?.toBooleanStrictOrNull() ?: false)
    private val _searchEnabled = MutableStateFlow(prefs.getString(KEY_SEARCH)?.toBooleanStrictOrNull() ?: false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _isGenerating = MutableStateFlow(false)
    private val _isLoadingHistory = MutableStateFlow(false)
    private val _generationStatus = MutableStateFlow<String?>(null)
    private val _typingMessageId = MutableStateFlow<String?>(null)
    private val _editingMessageId = MutableStateFlow<String?>(null)
    private val _quotedFragment = MutableStateFlow<String?>(null)
    private val _memories = MutableStateFlow<List<HonorMemory>>(emptyList())
    private val _memoryEnabled = MutableStateFlow(true)
    private val _instructionLibrary = MutableStateFlow<List<SavedInstruction>>(emptyList())
    private val _statistics = MutableStateFlow(loadStatistics())
    private val _requestedGame = MutableStateFlow<String?>(null)
    // agent: важное действие агента, ждущее подтверждения пользователя.
    private val _pendingAgentAction = MutableStateFlow<com.honerai.app.core.agent.AgentPendingAction?>(null)
    private var pendingAgentConfirm: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val selectedConversationId: StateFlow<String?> = _selected.asStateFlow()
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    override val draft = MutableStateFlow("")
    override val attachments: StateFlow<List<MessageAttachment>> = _attachments.asStateFlow()
    override val reasoningEnabled: StateFlow<Boolean> = _reasoningEnabled.asStateFlow()
    override val searchEnabled: StateFlow<Boolean> = _searchEnabled.asStateFlow()
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    override val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()
    override val generationStatus: StateFlow<String?> = _generationStatus.asStateFlow()
    override val typingMessageId: StateFlow<String?> = _typingMessageId.asStateFlow()
    override val pacer = TypingPacer()
    override val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()
    override val quotedFragment: StateFlow<String?> = _quotedFragment.asStateFlow()
    override val memories: StateFlow<List<HonorMemory>> = _memories.asStateFlow()
    override val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()
    override val instructionLibrary: StateFlow<List<SavedInstruction>> = _instructionLibrary.asStateFlow()
    override val statistics: StateFlow<UsageStatistics> = _statistics.asStateFlow()
    override val requestedGame: StateFlow<String?> = _requestedGame.asStateFlow()
    // agent: карточка подтверждения действия агента (наблюдает экран чата).
    override val pendingAgentAction: StateFlow<com.honerai.app.core.agent.AgentPendingAction?> = _pendingAgentAction.asStateFlow()

    /** Персональная инструкция (тон, обращение). На iPhone она пустая по умолчанию. */
    var systemInstruction: String = ""
    var profileName: String = settings?.displayName?.value.orEmpty()
    /** Дата рождения из профиля ("yyyy-MM-dd"). */
    var profileBirthday: String = settings?.birthday?.value.orEmpty()
    var accountCreatedAt: Instant? = settings?.accountCreatedAt

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistence = HistoryPersistence(storageFile)
    private val webClient: WebSearchClient = (searchClient as? WebSearchClient) ?: WebSearchClient(
        urlDiscovery = DeepSeekURLDiscovery(configuration),
        renderer = if (platform && context != null) AndroidPageRenderer(context) else null,
    )
    private val searchClient: WebSearching = searchClient ?: webClient

    private var chats: List<Conversation>
        get() = _conversations.value
        set(value) {
            _conversations.value = value
            publishMessages()
        }

    private var isLoading = true
    private var generationJob: Job? = null
    private var persistenceJob: Job? = null
    private var activeRunId: String? = null
    private var activeConversationId: String? = null
    private var activeMessageId: String? = null
    private var flushStreamingBuffer: (() -> Unit)? = null
    /** Один следующий запрос — без режима рассуждения (повтор после неудачного ответа). */
    private var suppressThinkingOnce = false
    private var currentSteps = mutableListOf<GenerationStep>()
    /** Живой буфер печатаемого ответа: в модель чата текст попадает только в конце. */
    internal var liveContent = ""; private set
    internal var liveReasoning = ""; private set
    private var liveReasoningSeconds = 0
    private var backgroundWorkStarted = false
    // appui: чаты, для которых сейчас готовится краткое содержание (чтобы не запускать дважды).
    private val summarizingChats = HashSet<String>()

    init {
        AttachmentFiles.root = attachmentsRoot
        pacer.onFinished = { id -> if (_typingMessageId.value == id) _typingMessageId.value = null }
        if (loadHistoryAsynchronously) {
            _isLoadingHistory.value = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { HistoryArchiveIO.readHistory(storageFile) }
                applyLoadedHistory(result)
                isLoading = false
                _isLoadingHistory.value = false
                afterLoad()
            }
        } else {
            applyLoadedHistory(HistoryArchiveIO.readHistory(storageFile))
            isLoading = false
            afterLoad()
        }
        // Поле ввода экран меняет напрямую — сохраняем с задержкой.
        scope.launch { draft.drop(1).collect { scheduleSave() } }
        if (settings != null) observeSettings(settings)
        if (platform) scope.launch { observeLifecycle() }
    }

    private fun afterLoad() {
        val days = settings?.autoDeleteDays?.value ?: 0
        if (days > 0) purgeOldChats(days)
    }

    private fun observeSettings(settings: AppSettings) {
        scope.launch { settings.displayName.collect { profileName = it } }
        scope.launch { settings.birthday.collect { profileBirthday = it } }
        scope.launch { settings.language.collect { setResponseLanguage(it) } }
        scope.launch { settings.autoDeleteDays.drop(1).collect { if (it > 0 && !isLoading) purgeOldChats(it) } }
    }

    /** Приложение свернули: сохранить историю и держать идущий ответ живым в фоне. */
    private fun observeLifecycle() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    persistNow()
                    if (_isGenerating.value) startBackgroundWork()
                }
            })
        }
    }

    private val isForeground: Boolean
        get() = if (!platform) true else runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(true)

    private fun startBackgroundWork() {
        val ctx = context ?: return
        if (!platform || backgroundWorkStarted) return
        val title = chats.firstOrNull { it.id == activeConversationId }?.title ?: "Honer AI"
        runCatching { GenerationService.start(ctx, title) }.onSuccess { backgroundWorkStarted = true }
    }

    private fun endBackgroundWork() {
        val ctx = context ?: return
        if (!backgroundWorkStarted) return
        backgroundWorkStarted = false
        runCatching { GenerationService.stop(ctx) }
    }

    private fun publishMessages() {
        _messages.value = selectedConversation()?.messages ?: emptyList()
    }

    private fun setSelected(id: String?) {
        _selected.value = id
        publishMessages()
    }

    private fun setAttachments(value: List<MessageAttachment>) {
        val old = _attachments.value
        _attachments.value = value
        if (!isLoading) {
            val retained = value.map { it.id }.toSet()
            removeUnreferencedAttachments(old.filter { it.id !in retained })
        }
        scheduleSave()
    }

    // ---- Вычисляемые ----

    override val canSend: Boolean
        get() = !_isLoadingHistory.value && !_isGenerating.value &&
            (draft.value.isNotBlank() || _attachments.value.isNotEmpty() || _quotedFragment.value != null)

    val hasApiKey: Boolean get() = configuration.apiKey.isNotBlank() || com.honerai.app.cloud.AiProxy.active // cloud: ключ на сервере
    val respondsInEnglish: Boolean get() = configuration.language == "en"
    private val answerLanguagePhrase: String get() = if (respondsInEnglish) "на английском языке (in English)" else "по-русски"

    override fun selectedConversation(): Conversation? =
        chats.firstOrNull { it.id == _selected.value && it.archivedAt == null }

    override fun sortedConversations(): List<Conversation> {
        // appui: чаты «Избранное» — локальные, их нет в общем списке и в контексте нейросети.
        val active = chats.filter { it.archivedAt == null && it.kind == ConversationKind.NORMAL }
        val pinned = active.filter { it.pinned }.sortedWith(compareBy<Conversation> { it.pinOrder }.thenByDescending { it.lastMessageAt })
        val others = active.filter { !it.pinned }.sortedByDescending { it.lastMessageAt }
        return pinned + others
    }

    override fun archivedConversations(): List<Conversation> =
        chats.filter { it.archivedAt != null }.sortedByDescending { it.archivedAt }

    // ---- Изменения чатов ----

    private fun mutateChat(chatId: String, update: (Conversation) -> Conversation) {
        val index = chats.indexOfFirst { it.id == chatId }
        if (index < 0) return
        chats = chats.toMutableList().also { it[index] = update(it[index]) }
    }

    private fun mutateMessage(chatId: String, messageId: String, update: (ChatMessage) -> ChatMessage) {
        mutateChat(chatId) { chat ->
            val index = chat.messages.indexOfFirst { it.id == messageId }
            if (index < 0) chat else chat.copy(messages = chat.messages.toMutableList().also { it[index] = update(it[index]) })
        }
    }

    private fun message(chatId: String, messageId: String): ChatMessage? =
        chats.firstOrNull { it.id == chatId }?.messages?.firstOrNull { it.id == messageId }

    /** Для тестов и восстановления: заменить список чатов целиком. */
    internal fun replaceConversations(list: List<Conversation>, selected: String? = _selected.value) {
        _conversations.value = list
        setSelected(selected)
    }

    // ---- Переписка ----

    override fun send(inputKind: MessageInputKind) {
        // agent: пока агент ждёт подтверждения, «да/подтверждаю/оплачивай» — это подтверждение,
        // «нет/отмена/стоп» — отмена. Обычное сообщение в это время не отправляем.
        if (_pendingAgentAction.value != null) {
            val verdict = com.honerai.app.core.agent.ConfirmationParser.parse(draft.value)
            if (verdict != com.honerai.app.core.agent.ConfirmationParser.Verdict.UNKNOWN) {
                draft.value = ""
                confirmPendingAction(verdict == com.honerai.app.core.agent.ConfirmationParser.Verdict.YES)
            }
            return
        }
        if (!canSend) return
        if (!hasApiKey) { _errorMessage.value = HonorError.MissingApiKey().message; return }
        // Родительский контроль: лимит времени, тихие часы и запрещённые темы.
        ParentalGuard.blockReason?.let { _errorMessage.value = it; return }
        val verdict = ParentalGuard.check(draft.value + " " + (_quotedFragment.value ?: ""))
        if (verdict is GuardVerdict.Blocked) { refuseLocally(verdict.reason); return }
        val text = draft.value.trim()
        val outgoing = _attachments.value
        if (_selected.value == null || selectedConversation() == null) {
            val conversation = Conversation()
            chats = listOf(conversation) + chats
            setSelected(conversation.id)
        }
        val chatId = _selected.value ?: return
        var discarded = emptyList<MessageAttachment>()
        val editingId = _editingMessageId.value
        if (editingId != null) {
            val chat = chats.firstOrNull { it.id == chatId }
            val position = chat?.messages?.indexOfFirst { it.id == editingId && it.role == MessageRole.USER } ?: -1
            if (chat != null && position >= 0) {
                discarded = chat.messages.drop(position).flatMap { it.attachments }
                mutateChat(chatId) { it.copy(messages = it.messages.take(position)) }
            }
        }
        val user = ChatMessage(role = MessageRole.USER, content = text, attachments = outgoing, inputKind = inputKind, quote = _quotedFragment.value)
        _quotedFragment.value = null
        mutateChat(chatId) { chat ->
            var updated = chat.copy(messages = chat.messages + user, updatedAt = Instant.now())
            if (updated.messages.count { it.role == MessageRole.USER } == 1) {
                val title = text.ifEmpty { outgoing.firstOrNull()?.name ?: "Новый чат" }
                updated = updated.copy(title = title.replace("\n", " ").take(48))
            }
            updated
        }
        recordSentMessage(inputKind == MessageInputKind.VOICE)
        draft.value = ""
        _attachments.value = emptyList()
        _editingMessageId.value = null
        removeUnreferencedAttachments(discarded)
        beginGeneration(chatId)
    }

    /** Запрос остановлен родительским контролем: вопрос и вежливый отказ остаются в чате, в сервис ничего не уходит. */
    private fun refuseLocally(reason: String) {
        val text = draft.value.trim()
        if (_selected.value == null || selectedConversation() == null) {
            val conversation = Conversation()
            chats = listOf(conversation) + chats
            setSelected(conversation.id)
        }
        val chatId = _selected.value ?: return
        mutateChat(chatId) {
            it.copy(messages = it.messages + ChatMessage(role = MessageRole.USER, content = text) +
                ChatMessage(role = MessageRole.ASSISTANT, content = reason), updatedAt = Instant.now())
        }
        draft.value = ""
        _quotedFragment.value = null
        setAttachments(emptyList())
        saveSnapshot()
    }

    override fun stop() {
        if (!_isGenerating.value) {
            // Поток закончился, но хвост ещё допечатывается: показываем его сразу.
            if (_typingMessageId.value != null) { pacerCancel(); _typingMessageId.value = null }
            return
        }
        flushStreamingBuffer?.invoke()
        // Напечатанное остаётся в переписке и сохраняется в истории.
        val printedContent = liveContent
        val printedReasoning = liveReasoning
        val chatId = activeConversationId
        val messageId = activeMessageId
        if (chatId != null && messageId != null && (printedContent.isNotEmpty() || printedReasoning.isNotEmpty())) {
            mutateMessage(chatId, messageId) { message ->
                message.copy(
                    content = if (printedContent.isNotEmpty()) ChatLogic.reactionSplit(printedContent).second.ifEmpty { printedContent } else message.content,
                    reasoning = printedReasoning.ifEmpty { message.reasoning },
                )
            }
        }
        flushStreamingBuffer = null
        generationJob?.cancel()
        generationJob = null
        // Печать останавливается сразу: на экране остаётся весь полученный текст.
        pacerCancel()
        _typingMessageId.value = null
        endBackgroundWork()
        if (chatId != null && messageId != null) mutateMessage(chatId, messageId) { it.copy(isInterrupted = true) }
        activeRunId = null
        activeConversationId = null
        activeMessageId = null
        _isGenerating.value = false
        _generationStatus.value = null
        // Живой буфер обязательно очищаем: иначе текст «переехал» бы в другой чат.
        liveContent = ""
        liveReasoning = ""
        liveReasoningSeconds = 0
        saveSnapshot()
    }

    override fun regenerate(messageId: String) {
        if (!hasApiKey) { _errorMessage.value = HonorError.MissingApiKey().message; return }
        // Повтор не последнего ответа сначала возвращает чат к этому месту.
        _selected.value?.let { chatId ->
            val chat = chats.firstOrNull { it.id == chatId }
            val position = chat?.messages?.indexOfFirst { it.id == messageId && it.role == MessageRole.ASSISTANT } ?: -1
            if (chat != null && position >= 0 && position < chat.messages.size - 1) {
                val dropped = chat.messages.drop(position + 1).flatMap { it.attachments }
                mutateChat(chatId) { it.copy(messages = it.messages.take(position + 1)) }
                removeUnreferencedAttachments(dropped)
            }
        }
        stop()
        val chatId = _selected.value
        val chat = chats.firstOrNull { it.id == chatId }
        val position = chat?.messages?.indexOfFirst { it.id == messageId && it.role == MessageRole.ASSISTANT } ?: -1
        if (chatId == null || chat == null || position < 0 || chat.messages.take(position).none { it.role == MessageRole.USER }) {
            _errorMessage.value = "Этот ответ нельзя повторить: перед ним нет вашего сообщения. Напишите запрос заново."
            return
        }
        // Повтор после неудачного ответа идёт без режима рассуждения — другим путём.
        suppressThinkingOnce = chat.messages[position].error != null
        val discarded = chat.messages.drop(position).flatMap { it.attachments }
        mutateChat(chatId) { it.copy(messages = it.messages.take(position)) }
        _editingMessageId.value = null
        removeUnreferencedAttachments(discarded)
        beginGeneration(chatId)
    }

    override fun edit(messageId: String) {
        stop()
        val message = _messages.value.firstOrNull { it.id == messageId && it.role == MessageRole.USER } ?: return
        _editingMessageId.value = messageId
        draft.value = message.content
        setAttachments(message.attachments)
    }

    override fun cancelEditing() {
        if (_editingMessageId.value == null) return
        _editingMessageId.value = null
        draft.value = ""
        setAttachments(emptyList())
    }

    override fun setReasoningEnabled(enabled: Boolean) {
        _reasoningEnabled.value = enabled
        prefs.putString(KEY_REASONING, enabled.toString())
    }

    override fun setSearchEnabled(enabled: Boolean) {
        _searchEnabled.value = enabled
        prefs.putString(KEY_SEARCH, enabled.toString())
    }

    override fun clearError() { _errorMessage.value = null }

    /** Процитировать фрагмент: он появится над полем ввода и уйдёт вместе с вопросом. */
    override fun quote(fragment: String) {
        val value = fragment.trim()
        if (value.isEmpty()) return
        _quotedFragment.value = value.take(4000)
    }

    override fun clearQuote() { _quotedFragment.value = null }

    /** Повторное добавление того же вложения (после importAttachment) ничего не меняет. */
    override fun addAttachment(attachment: MessageAttachment) {
        if (_attachments.value.any { it.id == attachment.id }) return
        setAttachments(_attachments.value + attachment)
    }

    override fun removeAttachment(id: String) {
        setAttachments(_attachments.value.filterNot { it.id == id })
    }

    override suspend fun importAttachment(uri: Uri): Result<MessageAttachment> {
        val ctx = context ?: return Result.failure(IllegalStateException("Нет доступа к файлам."))
        return try {
            val attachment = withContext(Dispatchers.IO) { AttachmentImporter.import(ctx, uri) }
            addAttachment(attachment)
            Result.success(attachment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override fun setFeedback(messageId: String, feedback: MessageFeedback?) {
        val chatId = _selected.value ?: return
        mutateMessage(chatId, messageId) { it.copy(feedback = feedback) }
        saveSnapshot()
    }

    /** Реакция-эмодзи на сообщение: нейросеть видит её в следующем ответе. */
    override fun setReaction(messageId: String, emoji: String?) {
        val chatId = _selected.value ?: return
        mutateMessage(chatId, messageId) { it.copy(reaction = emoji) }
        saveSnapshot()
    }

    fun setAssistantReaction(messageId: String, emoji: String?) {
        val chatId = _selected.value ?: return
        mutateMessage(chatId, messageId) { it.copy(assistantReaction = emoji) }
        saveSnapshot()
    }

    override fun consumeRequestedGame() { _requestedGame.value = null }

    /** Результат партии — сообщением в чат: Honer AI видит его в переписке. */
    override fun postGameResult(text: String) {
        if (selectedConversation() == null) {
            val chat = Conversation(title = "Игры с Honer AI")
            chats = listOf(chat) + chats
            setSelected(chat.id)
        }
        val chatId = _selected.value ?: return
        mutateChat(chatId) { it.copy(messages = it.messages + ChatMessage(role = MessageRole.ASSISTANT, content = text), updatedAt = Instant.now()) }
        saveSnapshot()
    }

    // ---- Чаты ----

    override fun newChat() {
        stop()
        setSelected(null)
        _editingMessageId.value = null
        draft.value = ""
        setAttachments(emptyList())
        _errorMessage.value = null
        saveSnapshot()
    }

    override fun selectChat(id: String) {
        if (chats.none { it.id == id && it.archivedAt == null }) return
        if (_selected.value == id) return
        stop()
        setSelected(id)
        _editingMessageId.value = null
        draft.value = ""
        setAttachments(emptyList())
        _errorMessage.value = null
        saveSnapshot()
    }

    override fun renameChat(id: String, title: String) {
        val value = title.trim()
        if (value.isEmpty() || chats.none { it.id == id }) return
        mutateChat(id) { it.copy(title = value.take(100)) }
        saveSnapshot()
    }

    override fun togglePin(ids: Set<String>) {
        val matching = chats.filter { it.id in ids }
        if (matching.isEmpty()) return
        val pin = !matching.all { it.pinned }
        chats = chats.map { if (it.id in ids) it.copy(pinned = pin) else it }
        saveSnapshot()
    }

    /** Брошен на закреплённый — закрепляется и встаёт перед ним; закреплённый на обычный — открепляется. */
    override fun moveChat(id: String, ontoId: String): Boolean {
        if (id == ontoId) return false
        val target = chats.firstOrNull { it.id == ontoId && it.archivedAt == null } ?: return false
        val dragged = chats.firstOrNull { it.id == id && it.archivedAt == null } ?: return false
        if (target.pinned) {
            val pinned = chats.filter { it.pinned && it.archivedAt == null && it.id != id }.sortedBy { it.pinOrder }.toMutableList()
            val position = pinned.indexOfFirst { it.id == ontoId }.let { if (it < 0) pinned.size else it }
            pinned.add(position, dragged)
            val order = pinned.mapIndexed { index, chat -> chat.id to index }.toMap()
            chats = chats.map { chat -> order[chat.id]?.let { chat.copy(pinned = true, pinOrder = it) } ?: chat }
        } else if (dragged.pinned) {
            mutateChat(id) { it.copy(pinned = false, pinOrder = 0) }
        } else {
            return false
        }
        saveSnapshot()
        return true
    }

    /** Меняет закреплённые чаты местами (только среди закреплённых). */
    override fun movePinned(id: String, offset: Int) {
        val pinned = chats.filter { it.pinned && it.archivedAt == null }.sortedBy { it.pinOrder }.toMutableList()
        val position = pinned.indexOfFirst { it.id == id }
        if (position < 0) return
        val target = position + offset
        if (target !in pinned.indices) return
        java.util.Collections.swap(pinned, position, target)
        val order = pinned.mapIndexed { index, chat -> chat.id to index }.toMap()
        chats = chats.map { chat -> order[chat.id]?.let { chat.copy(pinOrder = it) } ?: chat }
        saveSnapshot()
    }

    override fun archiveChat(id: String) {
        if (chats.none { it.id == id && it.archivedAt == null }) return
        if (activeConversationId == id) stop()
        mutateChat(id) { it.copy(archivedAt = Instant.now()) }
        if (_selected.value == id) {
            setSelected(null)
            _editingMessageId.value = null
            draft.value = ""
            setAttachments(emptyList())
        }
        saveSnapshot()
    }

    override fun restoreChat(id: String) {
        if (chats.none { it.id == id && it.archivedAt != null }) return
        mutateChat(id) { it.copy(archivedAt = null, updatedAt = Instant.now()) }
        saveSnapshot()
    }

    override fun deleteChats(ids: Set<String>) {
        if (activeConversationId != null && activeConversationId in ids) stop()
        val removed = chats.filter { it.id in ids }.flatMap { it.messages }.flatMap { it.attachments }
        chats = chats.filterNot { it.id in ids }
        if (_selected.value != null && _selected.value in ids) {
            setSelected(null)
            _editingMessageId.value = null
            draft.value = ""
            setAttachments(emptyList())
        }
        removeUnreferencedAttachments(removed)
        saveSnapshot()
    }

    override fun clearAllChats() {
        stop()
        chats = emptyList()
        setSelected(null)
        _editingMessageId.value = null
        draft.value = ""
        _attachments.value = emptyList()
        _errorMessage.value = null
        if (attachmentsRoot.exists() && !attachmentsRoot.deleteRecursively()) {
            _errorMessage.value = "Не удалось удалить вложения."
        }
        attachmentsRoot.mkdirs()
        saveSnapshot()
    }

    /** Ветка: копия чата до выбранного сообщения с новыми идентификаторами. */
    override fun forkConversation(atMessageId: String): String? {
        val source = selectedConversation() ?: return null
        val position = source.messages.indexOfFirst { it.id == atMessageId }
        if (position < 0) return null
        stop()
        // Перечитываем после stop(): живой ответ в копии должен быть помечен прерванным.
        val current = chats.firstOrNull { it.id == source.id } ?: return null
        val copies = current.messages.take(position + 1).map { original ->
            original.copy(
                id = newId(),
                attachments = original.attachments.map { it.copy(id = newId()) },
                sources = original.sources.map { it.copy(id = newId()) },
            )
        }
        val branch = Conversation(title = ("Ветка · " + source.title).take(100), messages = copies,
            parentConversationID = source.id, forkedAtMessageID = atMessageId, instructions = current.instructions)
        chats = listOf(branch) + chats
        setSelected(branch.id)
        _editingMessageId.value = null
        draft.value = ""
        setAttachments(emptyList())
        _errorMessage.value = null
        saveSnapshot()
        return branch.id
    }

    /** Автоудаление чатов по сроку хранения. Закреплённые не трогаются. */
    override fun purgeOldChats(olderThanDays: Int) {
        if (olderThanDays <= 0) return
        val threshold = Instant.now().minusSeconds(olderThanDays * 86_400L)
        val doomed = chats.filter { it.archivedAt == null && !it.pinned && it.lastMessageAt.isBefore(threshold) }
        if (doomed.isEmpty()) return
        val ids = doomed.map { it.id }.toSet()
        if (activeConversationId != null && activeConversationId in ids) stop()
        val removed = doomed.flatMap { it.messages }.flatMap { it.attachments }
        chats = chats.filterNot { it.id in ids }
        if (_selected.value != null && _selected.value in ids) setSelected(chats.firstOrNull { it.archivedAt == null }?.id)
        removeUnreferencedAttachments(removed)
        saveSnapshot()
    }

    // ---- Избранное (appui: локальный чат-заметки, наружу ничего не уходит) ----

    override fun ensureDefaultFavorites() {
        if (isLoading) return
        if (FavoritesLogic.hasAny(chats)) return
        val favorite = Conversation(
            title = FavoritesLogic.defaultTitle(respondsInEnglish),
            kind = ConversationKind.FAVORITES,
            pinOrder = 0,
        )
        chats = listOf(favorite) + chats
        saveSnapshot()
    }

    override fun createFavoritesFolder(name: String): String {
        val title = name.trim().take(100).ifEmpty { FavoritesLogic.defaultTitle(respondsInEnglish) }
        if (FavoritesLogic.ordered(chats).size >= FavoritesLogic.MAXIMUM_FOLDERS) {
            _errorMessage.value = "Слишком много папок избранного."
            return ""
        }
        val folder = Conversation(title = title, kind = ConversationKind.FAVORITES, pinOrder = FavoritesLogic.nextOrder(chats))
        chats = listOf(folder) + chats
        saveSnapshot()
        return folder.id
    }

    override fun deleteFavoritesFolder(id: String) {
        val target = chats.firstOrNull { it.id == id && it.kind == ConversationKind.FAVORITES } ?: return
        if (!FavoritesLogic.canDeleteFolder(chats, id)) {
            // Последнюю папку не удаляем — очищаем её содержимое.
            clearFavoritesHistory(id)
            return
        }
        val removed = target.messages.flatMap { it.attachments }
        chats = chats.filterNot { it.id == id }
        if (_selected.value == id) setSelected(null)
        removeUnreferencedAttachments(removed)
        saveSnapshot()
    }

    override fun clearFavoritesHistory(id: String) {
        val target = chats.firstOrNull { it.id == id && it.kind == ConversationKind.FAVORITES } ?: return
        val removed = target.messages.flatMap { it.attachments }
        mutateChat(id) { it.copy(messages = emptyList(), updatedAt = Instant.now()) }
        removeUnreferencedAttachments(removed)
        saveSnapshot()
    }

    override fun addFavoriteNote(chatId: String, text: String, attachments: List<MessageAttachment>) {
        val chat = chats.firstOrNull { it.id == chatId && it.kind == ConversationKind.FAVORITES } ?: return
        val value = text.trim()
        if (value.isEmpty() && attachments.isEmpty()) return
        val note = FavoritesLogic.noteMessage(value, attachments)
        mutateChat(chatId) { it.copy(messages = it.messages + note, updatedAt = Instant.now()) }
        saveSnapshot()
    }

    override fun forwardToFavorites(chatId: String, message: ChatMessage) {
        val chat = chats.firstOrNull { it.id == chatId && it.kind == ConversationKind.FAVORITES } ?: return
        // Вложения копируем в отдельные файлы: избранное не должно зависеть от исходного чата.
        val copy = FavoritesLogic.cloneForFavorites(message) { path -> copyAttachmentFile(path) }
        mutateChat(chatId) { it.copy(messages = it.messages + copy, updatedAt = Instant.now()) }
        saveSnapshot()
    }

    override fun toggleFavoriteMessagePin(chatId: String, messageId: String) {
        if (chats.none { it.id == chatId && it.kind == ConversationKind.FAVORITES }) return
        mutateMessage(chatId, messageId) { it.copy(pinnedInChat = !it.pinnedInChat) }
        saveSnapshot()
    }

    override fun deleteFavoriteMessage(chatId: String, messageId: String) {
        val chat = chats.firstOrNull { it.id == chatId && it.kind == ConversationKind.FAVORITES } ?: return
        val removed = chat.messages.firstOrNull { it.id == messageId }?.attachments.orEmpty()
        mutateChat(chatId) { it.copy(messages = it.messages.filterNot { m -> m.id == messageId }, updatedAt = Instant.now()) }
        removeUnreferencedAttachments(removed)
        saveSnapshot()
    }

    override fun moveFavorite(id: String, offset: Int) {
        val ordered = FavoritesLogic.ordered(chats).toMutableList()
        val position = ordered.indexOfFirst { it.id == id }
        if (position < 0) return
        val target = position + offset
        if (target !in ordered.indices) return
        java.util.Collections.swap(ordered, position, target)
        val order = ordered.mapIndexed { index, chat -> chat.id to index }.toMap()
        chats = chats.map { chat -> order[chat.id]?.let { chat.copy(pinOrder = it) } ?: chat }
        saveSnapshot()
    }

    override fun clearPendingAttachments() {
        if (_attachments.value.isNotEmpty()) setAttachments(emptyList())
    }

    /** Копия файла вложения в папке приложения с новым именем (для пересылки в избранное). */
    private fun copyAttachmentFile(sourcePath: String): String? {
        val source = AttachmentFiles.resolve(sourcePath) ?: File(sourcePath).takeIf { it.isFile } ?: return null
        return try {
            val ext = source.name.substringAfterLast('.', "").ifEmpty { "bin" }
            val target = File(attachmentsRoot, "${newId()}.$ext")
            source.copyTo(target, overwrite = true)
            target.absolutePath
        } catch (e: Throwable) {
            null
        }
    }

    // ---- Память ----

    override fun setMemoryEnabled(enabled: Boolean) {
        _memoryEnabled.value = enabled
        scheduleSave()
    }

    override fun addMemory(text: String): Boolean {
        val value = validatedMemory(text, null) ?: return false
        if (_memories.value.size >= ChatLogic.MAXIMUM_MEMORY_COUNT) {
            _errorMessage.value = "В памяти уже ${ChatLogic.MAXIMUM_MEMORY_COUNT} записей. Удалите ненужную запись, чтобы добавить новую."
            return false
        }
        _memories.value = _memories.value + HonorMemory(text = value, keywords = ChatLogic.keywords(value))
        _errorMessage.value = null
        saveSnapshot()
        return true
    }

    override fun updateMemory(id: String, text: String): Boolean {
        if (_memories.value.none { it.id == id }) return false
        val value = validatedMemory(text, id) ?: return false
        _memories.value = _memories.value.map { if (it.id == id) it.copy(text = value, keywords = ChatLogic.keywords(value)) else it }
        _errorMessage.value = null
        saveSnapshot()
        return true
    }

    override fun deleteMemory(id: String) {
        _memories.value = _memories.value.filterNot { it.id == id }
        saveSnapshot()
    }

    override fun clearMemories() {
        _memories.value = emptyList()
        saveSnapshot()
    }

    private fun validatedMemory(raw: String, excluding: String?): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > ChatLogic.MAXIMUM_MEMORY_LENGTH) {
            _errorMessage.value = "Запись памяти должна содержать от 1 до ${ChatLogic.MAXIMUM_MEMORY_LENGTH} символов."
            return null
        }
        if (_memories.value.any { it.id != excluding && it.text.equals(text, ignoreCase = true) }) {
            _errorMessage.value = "Такая запись уже есть в памяти Honor."
            return null
        }
        return text
    }

    // ---- Закреплённые инструкции ----

    override fun pinInstruction(fromMessageId: String): Boolean {
        val chatId = _selected.value ?: return false
        val message = chats.firstOrNull { it.id == chatId }?.messages?.firstOrNull { it.id == fromMessageId } ?: return false
        return addInstruction(chatId, message.content, if (message.role == MessageRole.ASSISTANT) MessageRole.ASSISTANT else MessageRole.USER, fromMessageId)
    }

    override fun addInstruction(chatId: String, text: String, author: MessageRole, sourceMessageId: String?): Boolean {
        val value = text.trim().take(ChatLogic.MAXIMUM_INSTRUCTION_LENGTH)
        val chat = chats.firstOrNull { it.id == chatId }
        if (value.isEmpty() || chat == null) return false
        val list = chat.instructions.orEmpty()
        if (list.any { it.text == value }) { _errorMessage.value = "Такая инструкция уже закреплена."; return false }
        if (list.size >= ChatLogic.MAXIMUM_INSTRUCTIONS_PER_CHAT) {
            _errorMessage.value = "В чате можно закрепить до ${ChatLogic.MAXIMUM_INSTRUCTIONS_PER_CHAT} инструкций."
            return false
        }
        mutateChat(chatId) { it.copy(instructions = list + ChatInstruction(text = value, author = author, sourceMessageID = sourceMessageId)) }
        saveSnapshot()
        return true
    }

    override fun updateInstruction(chatId: String, id: String, text: String) {
        val value = text.trim().take(ChatLogic.MAXIMUM_INSTRUCTION_LENGTH)
        if (value.isEmpty()) return
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        if (chat.instructions.orEmpty().none { it.id == id }) return
        mutateChat(chatId) { c -> c.copy(instructions = c.instructions.orEmpty().map { if (it.id == id) it.copy(text = value) else it }) }
        saveSnapshot()
    }

    /** Открепить инструкцию. По умолчанию она сохраняется в черновики. */
    override fun unpinInstruction(chatId: String, id: String, keepInLibrary: Boolean) {
        val item = chats.firstOrNull { it.id == chatId }?.instructions.orEmpty().firstOrNull { it.id == id } ?: return
        mutateChat(chatId) { c -> c.copy(instructions = c.instructions.orEmpty().filterNot { it.id == id }) }
        if (keepInLibrary) saveInstructionToLibrary(item.text)
        saveSnapshot()
    }

    override fun deleteInstruction(chatId: String, id: String) = unpinInstruction(chatId, id, keepInLibrary = false)

    override fun saveInstructionToLibrary(text: String) {
        val value = text.trim().take(ChatLogic.MAXIMUM_INSTRUCTION_LENGTH)
        if (value.isEmpty() || _instructionLibrary.value.any { it.text == value }) return
        _instructionLibrary.value = listOf(SavedInstruction(text = value)) + _instructionLibrary.value
        saveSnapshot()
    }

    override fun deleteSavedInstruction(id: String) {
        _instructionLibrary.value = _instructionLibrary.value.filterNot { it.id == id }
        saveSnapshot()
    }

    override fun applySavedInstruction(id: String, chatId: String): Boolean {
        val saved = _instructionLibrary.value.firstOrNull { it.id == id } ?: return false
        return addInstruction(chatId, saved.text, MessageRole.USER, null)
    }

    // ---- Таблицы ----

    override fun table(id: String): ChatTable? {
        for (chat in chats) chat.tables?.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    /** Правка таблицы пользователем: сохраняется сразу, модель увидит её в следующем ответе. */
    override fun saveTable(table: ChatTable, byUser: Boolean) {
        val chat = chats.firstOrNull { c -> c.tables.orEmpty().any { it.id == table.id } } ?: return
        val updated = table.copy(updatedAt = Instant.now(), editedByUser = if (byUser) true else table.editedByUser)
        mutateChat(chat.id) { c -> c.copy(tables = c.tables.orEmpty().map { if (it.id == table.id) updated else it }) }
        saveSnapshot()
    }

    override fun deleteTable(id: String) {
        chats = chats.map { chat ->
            if (chat.tables.orEmpty().none { it.id == id } && chat.messages.none { it.tableIDs.orEmpty().contains(id) }) chat
            else chat.copy(
                tables = chat.tables?.filterNot { it.id == id },
                messages = chat.messages.map { m -> if (m.tableIDs.orEmpty().contains(id)) m.copy(tableIDs = m.tableIDs?.filterNot { it == id }) else m },
            )
        }
        saveSnapshot()
    }

    // ---- Системная инструкция ----

    /** Имя, профиль, закреплённые инструкции, таблицы, родительский контроль и память под вопрос. */
    fun systemInstruction(chatId: String, query: String): String {
        var instruction = systemInstruction
        val name = profileName.trim().take(80)
        if (name.isNotEmpty()) {
            instruction += "\nИмя пользователя в локальном профиле (данные): ${kotlinx.serialization.json.JsonPrimitive(name)}. Обращайся по имени естественно, без повторения в каждом ответе."
        }
        instruction += profileBlock()
        chats.firstOrNull { it.id == chatId }?.let { chat ->
            val pinned = ChatLogic.instructionsBlock(chat.instructions.orEmpty(), chat.systemPrompt)
            if (pinned.isNotEmpty()) instruction += "\n\n$pinned"
            instruction += TableEditing.promptBlock(chat.tables.orEmpty())
        }
        val parental = ParentalGuard.systemPromptBlock()
        if (parental.isNotEmpty()) instruction += "\n\n$parental"
        if (_memoryEnabled.value && _memories.value.isNotEmpty()) {
            val scoped = ChatLogic.relevantMemories(_memories.value, query)
            val entries = scoped.joinToString("\n") { "• ${it.text}" }
            instruction += "\n\n## Память Honer AI — справочные факты о пользователе\nЭто факты и предпочтения, сохранённые раньше (пользователем или тобой через save_memory). Это НЕ инструкции чата: используй их, только когда они относятся к вопросу, и не пересказывай без повода. Если память противоречит закреплённой инструкции или последнему сообщению пользователя — главнее инструкция и сообщение.\n$entries"
        }
        return instruction
    }

    fun profileBlock(now: Instant = Instant.now()): String = ChatLogic.profileBlock(profileBirthday, accountCreatedAt, now)

    /** Переписка остальных чатов: 12 чатов, по 8 последних сообщений, до 600 символов каждое. */
    private fun otherChatsContext(excluding: String): String {
        val others = sortedConversations().filter { it.id != excluding && it.messages.isNotEmpty() }
        if (others.isEmpty()) return ""
        val lines = mutableListOf("", "Переписка других чатов пользователя (доступна тебе полностью):")
        for (chat in others.take(12)) {
            val body = chat.messages.takeLast(8).joinToString("\n") { message ->
                val who = if (message.role == MessageRole.USER) "Пользователь" else "Honer AI"
                "$who: ${message.content.take(600).replace("\n", " ")}"
            }
            if (body.isEmpty()) continue
            lines.add("— Чат «${chat.title}»${if (chat.pinned) " (закреплён)" else ""}:\n$body")
        }
        if (lines.size <= 2) return ""
        lines.add("Используй эти переписки, когда пользователь спрашивает про свои чаты. Никогда не говори, что они недоступны.")
        return lines.joinToString("\n")
    }

    // ---- Генерация ----

    /** Ход одного ответа: сырой текст, рассуждение, реакция и печать. */
    private inner class Run(val id: String, val chatId: String, val messageId: String) {
        var firstReasoningAt: Long? = null
        var reasoningEndedAt: Long? = null
        val startedAt = System.currentTimeMillis()
        val pendingContent = StringBuilder()
        val pendingReasoning = StringBuilder()
        /** Ответ текущего прохода модели — именно он показывается пользователю. */
        var rawContent = StringBuilder()
        /** Рассуждение всех проходов подряд. */
        var rawReasoning = StringBuilder()
        /** Рассуждение текущего прохода: возвращается в API вместе с вызовом инструмента. */
        val passReasoning = StringBuilder()
        var finishReason: String? = null
        var reactionApplied = false
        var lastFlush = 0L

        val content: String get() = rawContent.toString()
        val reasoning: String get() = rawReasoning.toString()

        fun ensureCurrent() { if (activeRunId != id) throw StaleRun() }

        /** Полученный текст — в живой буфер и в печать; модель чата во время потока не трогаем. */
        fun flush() {
            if (pendingContent.isEmpty() && pendingReasoning.isEmpty()) return
            val now = System.currentTimeMillis()
            val window = (reasoningEndedAt ?: now) - (firstReasoningAt ?: startedAt)
            val seconds = maxOf(1, Math.round(window / 1000.0).toInt())
            val reasoningChanged = pendingReasoning.isNotEmpty()
            val contentChanged = pendingContent.isNotEmpty()
            rawContent.append(pendingContent)
            if (reasoningChanged) {
                // Рассуждение нового прохода (после инструмента) — с нового абзаца.
                if (passReasoning.isEmpty() && rawReasoning.isNotEmpty()) rawReasoning.append("\n\n")
                rawReasoning.append(pendingReasoning)
                passReasoning.append(pendingReasoning)
            }
            val text = content
            if (contentChanged) liveContent = text
            if (reasoningChanged) liveReasoning = reasoning
            // Строка «РЕАКЦИЯ: 🔥» — служебная: реакцию сразу ставим под сообщением пользователя.
            val split = ChatLogic.reactionSplit(text)
            if (split.first != null && !reactionApplied) {
                reactionApplied = true
                setReactionOnLastUserMessage(chatId, messageId, split.first!!)
            }
            pacerUpdate(split.second, liveReasoning)
            if (seconds != liveReasoningSeconds || (reasoningChanged && liveReasoningSeconds == 0)) {
                liveReasoningSeconds = seconds
                mutateMessage(chatId, messageId) { it.copy(reasoningSeconds = seconds) }
            }
            pendingContent.setLength(0)
            pendingReasoning.setLength(0)
            lastFlush = now
        }

        /** Заменить показываемый ответ целиком: печать продолжится с общего начала. */
        fun show(text: String) {
            rawContent = StringBuilder(text)
            pendingContent.setLength(0)
            liveContent = text
            pacerUpdate(ChatLogic.reactionSplit(text).second, reasoning)
        }

        fun replaceReasoning(text: String) {
            rawReasoning = StringBuilder(text)
            liveReasoning = text
        }
    }

    private class StaleRun : Exception()

    /**
     * appui: один лёгкий запрос к модели — обновить краткое содержание ранних сообщений и закэшировать
     * его на чате. Работает в фоне и не мешает основному ответу; если не удалось — просто не кэшируем.
     */
    private fun maybeRefreshSummary(chatId: String, upTo: Int) {
        if (!hasApiKey || upTo <= 0) return
        if (!summarizingChats.add(chatId)) return
        scope.launch {
            try {
                val chat = chats.firstOrNull { it.id == chatId } ?: return@launch
                if (chat.kind != ConversationKind.NORMAL) return@launch
                val toSummarize = ContextCompressor.messagesToSummarize(chat.messages)
                if (toSummarize.isEmpty()) return@launch
                val transcript = ContextCompressor.transcriptForSummary(toSummarize, chat.runningSummary)
                val instruction = ContextCompressor.summaryInstruction(respondsInEnglish)
                val client = injectedClient ?: makeClient()
                val summary = withContext(Dispatchers.IO) {
                    client.complete(listOf(ChatMessage(role = MessageRole.USER, content = transcript)), false, instruction, "")
                }.trim()
                if (summary.isNotEmpty()) {
                    mutateChat(chatId) { it.copy(runningSummary = summary.take(4000), summarizedUpTo = upTo) }
                    saveSnapshot()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Резюме — вспомогательное: неудача не влияет на ответ.
            } finally {
                summarizingChats.remove(chatId)
            }
        }
    }

    private fun beginGeneration(chatId: String) {
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        _errorMessage.value = null
        // appui: сжатие контекста — длинную переписку заменяем «краткое содержание + свежий хвост».
        val compression = ContextCompressor.compress(chat.messages, chat.runningSummary, chat.summarizedUpTo)
        if (compression.needsSummary) maybeRefreshSummary(chatId, compression.summarizeUpTo)
        // В запрос уходит не вся переписка, а последние сообщения в пределах разумного размера.
        val input = ChatLogic.requestHistory(compression.contextMessages)
        val response = ChatMessage(role = MessageRole.ASSISTANT)
        mutateChat(chatId) { it.copy(messages = it.messages + response, updatedAt = Instant.now()) }
        val runId = newId()
        activeRunId = runId
        activeConversationId = chatId
        activeMessageId = response.id
        _isGenerating.value = true
        currentSteps = mutableListOf()
        liveContent = ""
        liveReasoning = ""
        liveReasoningSeconds = 0
        if (!isForeground) startBackgroundWork()
        pacerBegin(response.id)
        _typingMessageId.value = response.id
        val thinking = _reasoningEnabled.value && !suppressThinkingOnce
        suppressThinkingOnce = false
        val query = input.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        // Кнопка «Поиск» даёт модели инструменты интернета — искать или нет, модель решает сама.
        val webToolsOn = _searchEnabled.value && ParentalGuard.canSearchWeb
        // Вопрос явно про свежие данные или источник: поиск запускается заранее.
        val searching = ParentalGuard.canSearchWeb && SearchIntent.needsSearch(query, searchToggleOn = false)
        val recentContext = input.takeLast(4).joinToString("\n") { it.content.take(1500) }
        // Для решения «вопрос про другие чаты» смотрим только на реплики пользователя.
        val recentUserContext = input.filter { it.role == MessageRole.USER }.dropLast(1).takeLast(2).joinToString("\n") { it.content.take(600) }
        var instruction = systemInstruction(chatId, query)
        instruction += HonerIdentity.context(query, recentContext)
        if (ChatLogic.queryMentionsChats(query, recentUserContext)) instruction += otherChatsContext(chatId)
        val client = injectedClient ?: makeClient()
        // В английском режиме перевод на русский не нужен.
        val normalizer = if (respondsInEnglish) null else client as? RussianTextNormalizing
        _generationStatus.value = if (searching) "Ищу в интернете…" else if (thinking) "Размышляю…" else "Отвечаю…"
        saveSnapshot()
        val run = Run(runId, chatId, response.id)
        generationJob = scope.launch {
            try {
                generate(run, client, normalizer, input, instruction, query, thinking, searching, webToolsOn)
            } catch (e: StaleRun) {
                return@launch
            } catch (e: CancellationException) {
                if (activeRunId != runId) return@launch
                mutateMessage(chatId, response.id) { it.copy(isInterrupted = true) }
            } catch (e: Throwable) {
                if (activeRunId != runId) return@launch
                val description = describe(e)
                _errorMessage.value = description
                mutateMessage(chatId, response.id) { message ->
                    // Напечатанная часть ответа остаётся видна вместе с ошибкой.
                    message.copy(
                        content = message.content.ifEmpty { ChatLogic.reactionSplit(liveContent).second },
                        reasoning = message.reasoning.ifEmpty { liveReasoning },
                        error = description,
                    )
                }
            }
            finishRun(run)
        }
    }

    private suspend fun generate(
        run: Run, client: DeepSeekStreaming, normalizer: RussianTextNormalizing?, input: List<ChatMessage>,
        instruction: String, query: String, thinking: Boolean, searching: Boolean, webToolsOn: Boolean,
    ) {
        val chatId = run.chatId
        val messageId = run.messageId
        run.ensureCurrent()
        var context = ""
        var searchFailed = false
        if (searching) {
            if (query.isBlank()) {
                searchFailed = true
            } else {
                val stepId = newId()
                val direct = WebPageText.urls(query).isNotEmpty()
                startStep(GenerationStep(id = stepId, kind = if (direct) "read" else "search",
                    title = if (direct) "Читаю страницу по ссылке" else "Ищу в интернете", detail = "«${query.take(80)}»"))
                try {
                    val sources = searchClient.search(query)
                    run.ensureCurrent()
                    val read = sources.filter { it.content != null }
                    updateStep(stepId, detail = "Прочитано страниц: ${read.size}", sites = sources.mapNotNull { it.host }, done = true)
                    mutateMessage(chatId, messageId) { it.copy(sources = sources) }
                    context = WebSearchClient.context(sources)
                    _generationStatus.value = if (thinking) "Размышляю…" else "Отвечаю…"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StaleRun) {
                    throw e
                } catch (e: Exception) {
                    updateStep(stepId, detail = "Страницы не открылись — отвечаю по своим знаниям", done = true)
                    // Поиск не удался — отвечаем по своим знаниям и честно помечаем ответ.
                    searchFailed = true
                }
            }
        }
        if (searchFailed) mutateMessage(chatId, messageId) { it.copy(searchFailed = true) }
        flushStreamingBuffer = { if (activeRunId == run.id) run.flush() }

        // Проходы модели: первый — с инструментами; после инструментов следующий проход
        // снова с ними (цепочки list_chats → read_chat); последний запрещает новые вызовы.
        val passMessages = input.toMutableList()
        var forceAnswer = false
        var toolRounds = 0
        val toolResults = mutableListOf<ToolCallResult>()
        val executedSignatures = HashSet<String>()
        val resultsBySignature = HashMap<String, String>()
        var announcement = ""
        val tools = if (ChatLogic.TOOLS_ENABLED) HonerTool.schemas(webToolsOn, ParentalGuard.canSearchWeb) { name -> allowsTool(name) } else null
        passLoop@ while (true) {
            val toolCalls = mutableListOf<ToolCallRequest>()
            run.passReasoning.setLength(0)
            try {
                client.stream(passMessages, thinking, instruction, context, tools, forceAnswer).collect { delta ->
                    run.ensureCurrent()
                    if (delta.reasoning.isNotEmpty() && run.firstReasoningAt == null) run.firstReasoningAt = System.currentTimeMillis()
                    if (delta.content.isNotEmpty()) {
                        if (run.firstReasoningAt != null && run.reasoningEndedAt == null) run.reasoningEndedAt = System.currentTimeMillis()
                        if (_generationStatus.value != "Отвечаю…") _generationStatus.value = "Отвечаю…"
                    }
                    // Куски одного вызова склеиваются по index.
                    for (call in delta.toolCalls) ChatLogic.mergeToolCall(call, toolCalls)
                    run.pendingContent.append(delta.content)
                    run.pendingReasoning.append(delta.reasoning)
                    run.finishReason = delta.finishReason ?: run.finishReason
                    // Печать обновляется не чаще 25 раз в секунду: длинный ответ не нагружает главный поток.
                    if (System.currentTimeMillis() - run.lastFlush >= FLUSH_INTERVAL_MS || run.rawContent.isEmpty()) run.flush()
                }
                run.flush()
            } catch (e: StaleRun) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (activeRunId == run.id) run.flush()
                // Сбой первого прохода — ошибка запроса. Сбой после инструментов закрывается восстановлением ниже.
                if (toolRounds == 0) throw e
                break@passLoop
            }
            val calls = toolCalls.filter { it.name.isNotEmpty() }.toMutableList()
            if (calls.isEmpty() || !ChatLogic.TOOLS_ENABLED || forceAnswer || toolRounds >= ChatLogic.MAXIMUM_TOOL_ROUNDS) break@passLoop
            toolRounds++
            // У каждого вызова обязан быть id: по нему сервис связывает результат с вызовом.
            for (i in calls.indices) if (calls[i].id.isEmpty()) calls[i] = calls[i].copy(id = "call_" + newId().take(12))
            // Список чатов выполняем первым: read_chat опирается на номера из списка.
            calls.sortBy { if (it.name == HonerTool.LIST_CHATS.rawValue) 0 else 1 }
            _generationStatus.value = ChatLogic.toolStatus(calls)
            val toolContext = toolExecutionContext(client)
            val roundResults = mutableListOf<ToolCallResult>()
            var repeated = false
            for (call in calls) {
                val signature = call.name + "|" + call.arguments
                val isRepeat = !executedSignatures.add(signature)
                // Повтор с теми же аргументами не выполняем второй раз.
                val previous = resultsBySignature[signature]
                if (isRepeat && previous != null) {
                    repeated = true
                    roundResults.add(ToolCallResult(call.id, call.name, previous))
                    continue
                }
                val step = ChatLogic.step(call)
                startStep(step)
                val result = executeTool(call, step.id, toolContext, webToolsOn)
                run.ensureCurrent()
                resultsBySignature[signature] = result.content
                val effect = result.effect
                if (effect is ToolEffect.AddSources) updateStep(step.id, sites = effect.sources.mapNotNull { it.host }, done = true)
                else updateStep(step.id, done = true)
                if (call.name == HonerTool.WEB_SEARCH.rawValue && effect == null) {
                    // Поиск ничего не дал: ответ честно помечается как ответ без свежих данных.
                    mutateMessage(chatId, messageId) { it.copy(searchFailed = true) }
                }
                if (effect is ToolEffect.AddSources) {
                    mutateMessage(chatId, messageId) { message ->
                        val added = effect.sources.filter { source ->
                            message.sources.none { it.url == source.url } && ParentalGuard.isUrlAllowed(source.url)
                        }
                        message.copy(sources = message.sources + added)
                    }
                } else if (effect != null && !applyToAnswer(effect, chatId, messageId)) {
                    apply(effect)
                }
                roundResults.add(result)
            }
            toolResults.addAll(roundResults)
            // Вызов и результаты — строго по протоколу: ассистент с tool_calls, затем tool на каждый вызов.
            passMessages.add(ChatMessage(role = MessageRole.ASSISTANT, content = run.content,
                reasoning = run.passReasoning.toString(), toolCallsRaw = ChatLogic.toolCallsJSON(calls)))
            for (result in roundResults) {
                passMessages.add(ChatMessage(role = MessageRole.TOOL, content = result.content, toolCallID = result.callID))
            }
            // Текст прохода был вступлением к действию. Итоговый ответ печатается с чистого листа.
            if (run.content.isNotBlank()) announcement = run.content
            run.show("")
            run.finishReason = null
            // Модель повторяет тот же вызов или исчерпала шаги — дальше только ответ.
            if (repeated || toolRounds >= ChatLogic.MAXIMUM_TOOL_ROUNDS) forceAnswer = true
        }
        run.ensureCurrent()
        if (run.content.isBlank() && announcement.isNotEmpty()) run.show(announcement)
        // Поток закончился: переносим текст в модель чата — дальше перевод, сохранение и проверки.
        val printedSeconds = liveReasoningSeconds
        val rawReasoning = run.reasoning
        mutateMessage(chatId, messageId) { it.copy(content = run.content, reasoning = rawReasoning, reasoningSeconds = printedSeconds) }
        if (normalizer != null) normalize(run, normalizer)
        recover(run, client, input, instruction, context, toolResults)
        cleanAnnouncements(run, client, input, toolResults)
        // media: карточки медиа и кнопки приложений не теряются, даже если модель их не вставила.
        MediaAnswerBlocks.completed(run.content, toolResults.map { it.callID })?.let { text ->
            run.show(text)
            mutateMessage(chatId, messageId) { it.copy(content = text) }
        }
        val final = message(chatId, messageId)
        if (final == null || final.content.isEmpty()) throw HonorError.EmptyResponse()
        if (ChatLogic.isTooShortToBeAnAnswer(final.content)) {
            mutateMessage(chatId, messageId) { it.copy(error = "Ответ пришёл обрывком. Нажмите «Повторить запрос».") }
            throw HonorError.EmptyResponse()
        }
        when (run.finishReason) {
            "length" -> mutateMessage(chatId, messageId) { it.copy(error = "Достигнута максимальная длина ответа. Попросите продолжить.") }
            "content_filter" -> mutateMessage(chatId, messageId) { it.copy(error = "Сервис остановил этот ответ.") }
            "insufficient_system_resource" -> mutateMessage(chatId, messageId) { it.copy(error = "Ответ оборвался на стороне сервиса. Повторите запрос.") }
        }
    }

    /** Перевод на русский, если модель ответила или рассуждала на другом языке. */
    private suspend fun normalize(run: Run, normalizer: RussianTextNormalizing) {
        val chatId = run.chatId
        val messageId = run.messageId
        val sourceContent = run.content
        val sourceReasoning = run.reasoning
        val normalizeContent = RussianTextPolicy.needsNormalization(sourceContent)
        val normalizeReasoning = RussianTextPolicy.needsReasoningNormalization(sourceReasoning)
        if (normalizeContent && normalizeReasoning) {
            _generationStatus.value = "Перевожу на русский…"
            try {
                val pair = normalizer.normalizeBoth(sourceContent, sourceReasoning)
                run.ensureCurrent()
                run.replaceReasoning(pair.second)
                run.show(pair.first)
                mutateMessage(chatId, messageId) { it.copy(content = pair.first, reasoning = pair.second, reasoningWasTranslated = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleRun) {
                throw e
            } catch (e: Exception) {
                normalizeSeparately(run, normalizer, sourceContent, sourceReasoning, true, true)
            }
        } else {
            if (normalizeContent) {
                _generationStatus.value = "Перевожу ответ на русский…"
                try {
                    val translated = normalizer.normalizeRussian(sourceContent, false)
                    run.ensureCurrent()
                    run.show(translated)
                    mutateMessage(chatId, messageId) { it.copy(content = translated) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StaleRun) {
                    throw e
                } catch (e: Exception) {
                    // Перевод не удался — оставляем исходный ответ целиком.
                }
            }
            if (normalizeReasoning) {
                try {
                    val translated = normalizer.normalizeRussian(sourceReasoning, true)
                    run.ensureCurrent()
                    mutateMessage(chatId, messageId) { it.copy(reasoning = translated, reasoningWasTranslated = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StaleRun) {
                    throw e
                } catch (e: Exception) {
                    // Рассуждение остаётся на языке модели — честно помечаем.
                    mutateMessage(chatId, messageId) { it.copy(reasoning = it.reasoning.ifBlank { sourceReasoning }, reasoningStayedForeign = true) }
                }
            }
        }
        // Показываемый ответ — тот, что в модели после перевода.
        val translated = message(chatId, messageId)?.content
        if (translated != null && translated != run.content) run.show(translated)
    }

    /** Запасной путь перевода: по частям, если общий запрос не прошёл. */
    private suspend fun normalizeSeparately(run: Run, normalizer: RussianTextNormalizing, sourceContent: String,
                                            sourceReasoning: String, contentNeeded: Boolean, reasoningNeeded: Boolean) {
        if (contentNeeded) {
            _generationStatus.value = "Перевожу ответ на русский…"
            val translated = try { normalizer.normalizeRussian(sourceContent, false) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (translated != null && activeRunId == run.id) mutateMessage(run.chatId, run.messageId) { it.copy(content = translated) }
        }
        if (reasoningNeeded && activeRunId == run.id) {
            val translated = try { normalizer.normalizeRussian(sourceReasoning, true) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (activeRunId != run.id) return
            if (translated != null) mutateMessage(run.chatId, run.messageId) { it.copy(reasoning = translated, reasoningWasTranslated = true) }
            else mutateMessage(run.chatId, run.messageId) { it.copy(reasoning = it.reasoning.ifBlank { sourceReasoning }, reasoningStayedForeign = true) }
        }
    }

    /** Страховка от пустого ответа и обрывка: повтор без рассуждения, затем обычный запрос. */
    private suspend fun recover(run: Run, client: DeepSeekStreaming, input: List<ChatMessage>, instruction: String,
                                context: String, toolResults: List<ToolCallResult>) {
        val answerIsEmpty = run.content.isBlank()
        val answerIsFragment = ChatLogic.needsAnswerRecovery(run.content)
        val serverAnomaly = run.finishReason == "insufficient_system_resource" || run.finishReason == "aborted"
        if (!(answerIsEmpty || answerIsFragment || serverAnomaly)) return
        _generationStatus.value = "Дописываю ответ…"
        val original = run.content
        // Данные инструментов прикладываются обычным сообщением — иначе повтор снова обещает «сейчас найду».
        val recoveryInput = input.toMutableList()
        if (toolResults.isNotEmpty()) {
            if (run.content.isNotBlank()) recoveryInput.add(ChatMessage(role = MessageRole.ASSISTANT, content = run.content))
            val collected = toolResults.joinToString("\n") { "• ${it.content}" }
            recoveryInput.add(ChatMessage(role = MessageRole.USER,
                content = "Вот данные, которые ты запросил:\n$collected\n\nИспользуй их и дай итоговый ответ пользователю. Больше инструментов нет — отвечай текстом."))
        }
        var retryContent = ""
        try {
            val streamed = StringBuilder()
            client.stream(recoveryInput, false, instruction, context, null, false).collect { delta ->
                run.ensureCurrent()
                streamed.append(delta.content)
                if (streamed.isNotEmpty()) run.show(streamed.toString())
            }
            retryContent = streamed.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StaleRun) {
            throw e
        } catch (e: Throwable) {
        }
        // Поток не дал текста — обычный запрос без потока: он приходит целиком.
        if (ChatLogic.needsAnswerRecovery(retryContent)) {
            try {
                val whole = client.complete(recoveryInput, false, instruction, context)
                run.ensureCurrent()
                if (whole.isNotEmpty()) { retryContent = whole; run.show(whole) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleRun) {
                throw e
            } catch (e: Throwable) {
            }
        }
        // Последняя попытка: данные инструментов есть, а ответа по существу нет.
        if (ChatLogic.needsAnswerRecovery(retryContent) && toolResults.isNotEmpty()) {
            _generationStatus.value = "Формулирую ответ…"
            try {
                val forced = client.complete(recoveryInput, false,
                    "Отвечай только итоговым текстом $answerLanguagePhrase. Никаких обещаний что-то найти или прочитать, никаких рассуждений о своих действиях. Сразу дай ответ по данным, которые есть в переписке.", "")
                run.ensureCurrent()
                if (forced.isNotEmpty() && !ChatLogic.needsAnswerRecovery(forced)) { retryContent = forced; run.show(forced) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleRun) {
                throw e
            } catch (e: Throwable) {
            }
        }
        // Неудачный повтор не должен стирать показанный ответ.
        val retryIsAnswer = !ChatLogic.needsAnswerRecovery(retryContent)
        if (retryIsAnswer || (ChatLogic.needsAnswerRecovery(original) && retryContent.isNotEmpty())) run.show(retryContent) else run.show(original)
        mutateMessage(run.chatId, run.messageId) { it.copy(content = run.content) }
        if (retryIsAnswer) run.finishReason = null
    }

    /** Последняя очистка: убрать из ответа объявления о действиях («Сначала найду чат…»). */
    private suspend fun cleanAnnouncements(run: Run, client: DeepSeekStreaming, input: List<ChatMessage>, toolResults: List<ToolCallResult>) {
        if (!ChatLogic.isToolAnnouncement(run.content)) return
        val cleaned = ChatLogic.strippingToolAnnouncements(run.content)
        if (!ChatLogic.isTooShortToBeAnAnswer(cleaned)) {
            run.show(cleaned)
            mutateMessage(run.chatId, run.messageId) { it.copy(content = cleaned) }
        } else if (toolResults.isNotEmpty()) {
            _generationStatus.value = "Формулирую ответ…"
            val collected = toolResults.joinToString("\n") { "• ${it.content}" }
            val synthesisInput = input + ChatMessage(role = MessageRole.USER, content = "Данные, полученные инструментами:\n$collected")
            val answer = try {
                client.complete(synthesisInput, false,
                    "Ответь пользователю $answerLanguagePhrase одним связным ответом, используя данные выше. Без вступлений, без описания своих действий и без markdown-заголовков первого уровня.", "")
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
            run.ensureCurrent()
            if (answer != null && !ChatLogic.isTooShortToBeAnAnswer(answer)) {
                run.show(answer)
                mutateMessage(run.chatId, run.messageId) { it.copy(content = answer) }
            }
        }
    }

    /** Завершение ответа: реакция, страховка от тишины, допечатка хвоста, сохранение, уведомление. */
    private fun finishRun(run: Run) {
        if (activeRunId != run.id) return
        val chatId = run.chatId
        val messageId = run.messageId
        activeRunId = null
        activeConversationId = null
        activeMessageId = null
        flushStreamingBuffer = null
        _isGenerating.value = false
        _generationStatus.value = null
        generationJob = null
        liveContent = ""
        liveReasoning = ""
        liveReasoningSeconds = 0
        val delivered = message(chatId, messageId)
        if (!delivered?.content.isNullOrEmpty()) {
            recordReceivedMessage()
            extractAssistantReaction(chatId, messageId)
        }
        ensureVisibleOutcome(chatId, messageId)
        val settled = message(chatId, messageId)
        pacerClose(settled?.content.orEmpty(), settled?.reasoning.orEmpty())
        if (currentSteps.isNotEmpty()) {
            val steps = currentSteps.map { it.copy(done = true) }
            mutateMessage(chatId, messageId) { it.copy(activity = steps) }
        }
        saveSnapshot()
        val text = settled?.content.orEmpty()
        if (text.isNotEmpty()) notifyAnswerReady(chatId, text, settled)
        if (text.isNotEmpty()) maybeAutoTitle(chatId)
        endBackgroundWork()
    }

    // Чаты, для которых уже сгенерировали название — не повторяем. План прил. п.1.
    private val autoTitled = mutableSetOf<String>()

    /**
     * Авто-название чата после первого ответа ИИ (#1): короткий заголовок вместо «сырого» первого сообщения.
     * Только если пользователь ещё не переименовал чат вручную. Фоном, ошибки молча игнорируются.
     */
    private fun maybeAutoTitle(chatId: String) {
        // В тестах клиент внедряют со сценарными ответами — лишний запрос названия их ломает.
        if (injectedClient != null) return
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        if (chat.kind != ConversationKind.NORMAL || chatId in autoTitled) return
        val users = chat.messages.filter { it.role == MessageRole.USER }
        val firstUser = users.firstOrNull()?.content.orEmpty()
        if (users.size != 1 || firstUser.isBlank()) return
        val crude = firstUser.replace("\n", " ").take(48)
        if (chat.title != crude) return // переименовано вручную — не трогаем
        val answer = chat.messages.firstOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotEmpty() }?.content ?: return
        autoTitled.add(chatId)
        val instruction = "Придумай короткое название для диалога: 2–5 слов, на языке вопроса, без кавычек и без точки в конце. Выдай только название."
        val prompt = ChatMessage(role = MessageRole.USER, content = "Вопрос: ${firstUser.take(500)}\n\nОтвет: ${answer.take(500)}")
        scope.launch {
            val client = injectedClient ?: makeClient()
            val title = runCatching { client.complete(listOf(prompt), false, instruction, "") }
                .getOrNull()?.trim()?.replace("\"", "")?.trim('.', ' ', '\n')?.take(60).orEmpty()
            if (title.length in 2..60) {
                val cur = chats.firstOrNull { it.id == chatId }
                if (cur != null && cur.title == crude) mutateChat(chatId) { it.copy(title = title) }
            }
        }
    }

    private fun notifyAnswerReady(chatId: String, text: String, message: ChatMessage?) {
        val ctx = context ?: return
        if (!platform || isForeground) return
        if (settings?.notificationsEnabled?.value == false) return
        val title = chats.firstOrNull { it.id == chatId }?.title.orEmpty()
        val image = ChatLogic.firstImageUrl(text)
            ?: message?.attachments?.firstOrNull { it.kind == AttachmentKind.IMAGE }?.localPath?.let { "file://$it" }
        runCatching { HonerNotifications.notifyAnswer(ctx, chatId, title, text, image) }
    }

    /** Реакция в ответе «РЕАКЦИЯ: 🙂» убирается из текста и ставится под сообщением пользователя. */
    private fun extractAssistantReaction(chatId: String, messageId: String) {
        val content = message(chatId, messageId)?.content ?: return
        val (emoji, remainder) = ChatLogic.extractReaction(content) ?: return
        mutateMessage(chatId, messageId) { it.copy(content = remainder) }
        setReactionOnLastUserMessage(chatId, messageId, emoji)
    }

    private fun setReactionOnLastUserMessage(chatId: String, beforeMessageId: String, emoji: String) {
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        val answer = chat.messages.indexOfFirst { it.id == beforeMessageId }
        if (answer < 0) return
        val user = chat.messages.subList(0, answer).indexOfLast { it.role == MessageRole.USER }
        if (user < 0) return
        mutateMessage(chatId, chat.messages[user].id) { it.copy(assistantReaction = emoji) }
    }

    /** Страховка: сообщение ассистента не может остаться без текста и без ошибки. */
    private fun ensureVisibleOutcome(chatId: String, messageId: String) {
        val message = message(chatId, messageId) ?: return
        if (message.content.isBlank() && message.error == null && !message.isInterrupted) {
            mutateMessage(chatId, messageId) { it.copy(error = HonorError.EmptyResponse().message) }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is HonorError -> error.message.orEmpty()
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Нет подключения к интернету. Проверьте соединение и повторите запрос."
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Сервис долго не отвечает. Повторите запрос."
        is IOException -> HonorError.UnfinishedResponse().message.orEmpty()
        else -> error.message ?: HonorError.InvalidResponse().message.orEmpty()
    }

    private fun makeClient(): DeepSeekClient = DeepSeekClient(configuration, deviceSummary = { DeviceContext.summary(context) })

    private fun allowsTool(name: String): Boolean = context?.let { runCatching { Integrations.allowsTool(it, name) }.getOrDefault(true) } ?: true

    /** Выполнение одного вызова инструмента. */
    private suspend fun executeTool(call: ToolCallRequest, stepId: String, toolContext: ToolExecutionContext, webToolsOn: Boolean): ToolCallResult {
        ChatLogic.parentalRefusal(call)?.let { return ToolCallResult(call.id, call.name, it) }
        val tool = HonerTool.from(call.name)
        val progress: ToolProgress = { detail, sites -> scope.launch { updateStep(stepId, detail = detail, sites = sites) } }
        val offline = "Интернет выключен. Предложи пользователю включить кнопку «Поиск»."
        // Фото, видео, музыка, погода, ссылки — по прямой просьбе и без кнопки «Поиск».
        val online = webToolsOn || (tool?.isOnDemand == true && ParentalGuard.canSearchWeb)
        return when {
            tool == HonerTool.FIND_CONTACT -> ContactLookup.execute(context, call)
            // agent: действия в приложениях и подтверждение важных шагов.
            tool == HonerTool.RUN_DEVICE_TASK -> runDeviceTask(call)
            // cloud: действие в облачном аккаунте (сервис на сервере, пользователь остаётся в чате).
            tool == HonerTool.CLOUD_TASK -> runCloudTask(call)
            tool == HonerTool.CONFIRM_PENDING_ACTION -> {
                val confirm = ToolArgument.bool(call.parsedArguments["confirm"]) ?: true
                val had = _pendingAgentAction.value != null
                confirmPendingAction(confirm)
                ToolCallResult(call.id, call.name,
                    if (!had) "Сейчас нет действия, ожидающего подтверждения."
                    else if (confirm) "Подтверждение принято — продолжаю." else "Действие отменено.")
            }
            // integ: GitHub через официальный API; запись/создание — с подтверждением пользователя.
            tool != null && tool.isGitHub -> runGitHubTool(call)
            // media: кнопка открытия приложения и медиа в чат.
            tool == HonerTool.OPEN_APP -> AppLauncher.execute(context, call)
            tool == HonerTool.SEND_MEDIA -> if (online) MediaSender(webClient, configuration.language).execute(call, progress)
                else ToolCallResult(call.id, call.name, offline)
            tool != null && tool.isExtra -> {
                val executor = ExtraToolExecutor(webClient, toolContext)
                when {
                    tool.isWeb && !online -> ToolCallResult(call.id, call.name, offline)
                    tool.isAsync -> executor.execute(call, progress)
                    else -> executor.executeLocal(call)
                }
            }
            tool != null && tool.isAsync ->
                if (online) WebToolExecutor(webClient, configuration.language /* media */).execute(call, progress) else ToolCallResult(call.id, call.name, offline)
            else -> ToolExecutor.executeExtended(call, toolContext)
        }
    }

    // agent: запуск действия в приложении через службу специальных возможностей.
    private suspend fun runDeviceTask(call: ToolCallRequest): ToolCallResult {
        val controller = com.honerai.app.core.agent.AgentController.controller
        val serviceEnabled = context?.let { com.honerai.app.device.agent.AgentAccessibility.isServiceEnabled(it) } ?: false
        val availability = com.honerai.app.core.agent.DeviceTaskTool.Availability(
            masterEnabled = com.honerai.app.core.agent.AgentAvailability.enabled,
            serviceEnabled = serviceEnabled,
            connected = com.honerai.app.core.agent.AgentController.connected.value,
        )
        val brain = if (availability.ready && controller != null) com.honerai.app.core.agent.ModelAgentBrain(makeClient()) else null
        val sink = object : com.honerai.app.core.agent.AgentProgressSink {
            override fun start(title: String, detail: String): String {
                val id = newId()
                scope.launch { startStep(GenerationStep(id = id, kind = "settings", title = title, detail = detail)) }
                return id
            }
            override fun update(stepId: String, detail: String?, done: Boolean?) {
                scope.launch { updateStep(stepId, detail = detail, done = done) }
            }
        }
        val confirm: suspend (String, String?) -> Boolean = { description, amount -> awaitAgentConfirmation(description, amount) }
        return com.honerai.app.core.agent.DeviceTaskTool.execute(call, controller, brain, availability, sink, confirm)
    }

    // cloud: действие в облачном аккаунте на сервере. Тот же цикл агента, но «глаза и руки» — облачный
    // браузер (пользователь вошёл в сервис через «Облачные аккаунты» и остаётся в чате).
    private suspend fun runCloudTask(call: ToolCallRequest): ToolCallResult {
        val task = com.honerai.app.core.ToolArgument.string(call.parsedArguments["task"])?.trim().orEmpty()
        if (task.isEmpty()) return ToolCallResult(call.id, call.name, "Не передано, что сделать в облачном аккаунте.")
        val controller = com.honerai.app.cloud.CloudManager.cloudBrowser.controller()
            ?: return ToolCallResult(call.id, call.name,
                "Сначала войдите в облачный аккаунт: Настройки → Интеграции → Облачные аккаунты. После входа я всё сделаю прямо там.")
        val brain = com.honerai.app.core.agent.ModelAgentBrain(makeClient())
        var lastStepId: String? = null
        val session = com.honerai.app.core.agent.AgentSession(
            screen = controller, brain = brain, maxSteps = 25,
            onProgress = { title, detail ->
                lastStepId?.let { sid -> scope.launch { updateStep(sid, done = true) } }
                val id = newId(); lastStepId = id
                scope.launch { startStep(GenerationStep(id = id, kind = "settings", title = title, detail = detail)) }
            },
            onConfirm = { description, amount -> awaitAgentConfirmation(description, amount) },
        )
        val outcome = session.run(task)
        lastStepId?.let { sid -> updateStep(sid, done = true) }
        val steps = if (outcome.steps.isNotEmpty()) "\nШаги: " + outcome.steps.joinToString("; ") else ""
        return ToolCallResult(call.id, call.name, outcome.summary + steps)
    }

    // integ: инструменты GitHub. Токен берётся из зашифрованного хранилища; запись файла и создание
    // репозитория проходят через ту же карточку подтверждения, что и действия агента.
    private suspend fun runGitHubTool(call: ToolCallRequest): ToolCallResult {
        val token = context?.let { com.honerai.app.core.github.GitHubIntegration.tokenStore(it).token }
        val executor = com.honerai.app.core.github.GitHubToolExecutor(token,
            confirm = { description, amount -> awaitAgentConfirmation(description, amount) })
        return executor.execute(call)
    }

    /**
     * Ставит действие агента на подтверждение и ждёт ответа пользователя (кнопка или текст).
     * Ждём не дольше 3 минут: если пользователь не ответил (ушёл, свернул, потерял карточку) —
     * считаем «не подтверждено» и не оставляем чат висеть навсегда на «Жду подтверждения…».
     */
    private suspend fun awaitAgentConfirmation(description: String, amount: String?): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingAgentConfirm = deferred
        _pendingAgentAction.value = com.honerai.app.core.agent.AgentPendingAction(description, amount)
        _generationStatus.value = "Жду подтверждения…"
        return try {
            kotlinx.coroutines.withTimeoutOrNull(3 * 60_000L) { deferred.await() } ?: false
        } finally {
            _pendingAgentAction.value = null
            if (pendingAgentConfirm === deferred) pendingAgentConfirm = null
        }
    }

    override fun confirmPendingAction(confirm: Boolean) {
        val deferred = pendingAgentConfirm ?: return
        pendingAgentConfirm = null
        _pendingAgentAction.value = null
        deferred.complete(confirm)
    }

    /** Данные для инструментов: список всех чатов, переписка каждого, настройки, таблицы, память. */
    private fun toolExecutionContext(client: DeepSeekStreaming): ToolExecutionContext {
        val overviews = mutableListOf<ChatOverview>()
        val transcripts = HashMap<Int, List<ChatTranscriptLine>>()
        for ((index, chat) in sortedConversations().withIndex()) {
            val number = index + 1
            val preview = chat.messages.lastOrNull { it.content.isNotEmpty() }?.content?.take(120).orEmpty()
            overviews.add(ChatOverview(number, chat.id, chat.title, chat.messages.size, chat.lastMessageAt, chat.pinned, chat.archivedAt != null, preview))
            transcripts[number] = chat.messages.takeLast(120).map {
                ChatTranscriptLine(when (it.role) { MessageRole.USER -> "user"; MessageRole.ASSISTANT -> "assistant"; MessageRole.TOOL -> "tool" }, it.content.take(2000))
            }
        }
        val selected = selectedConversation()
        val current = _messages.value
        val clipboard: ((String) -> Unit)? = context?.let { ctx ->
            { text: String ->
                val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("Honer AI", text))
            }
        }
        return ToolExecutionContext(
            deviceModel = runCatching { DeviceInfo.modelName }.getOrDefault("Android"),
            systemVersion = runCatching { DeviceInfo.osDescription }.getOrDefault("Android"),
            appVersion = runCatching { DeviceInfo.appVersion }.getOrDefault(""),
            messageCount = current.size,
            voiceMessageCount = current.count { it.inputKind == MessageInputKind.VOICE },
            chatStartedAt = selected?.createdAt,
            lastMessageAt = current.lastOrNull()?.createdAt,
            chats = overviews,
            transcripts = transcripts,
            settingsSummary = settingsSummary(),
            tables = selected?.tables.orEmpty(),
            memoryItems = _memories.value.map { MemoryRef(it.id, it.text) },
            chatAttachments = current.flatMap { it.attachments }.filter { it.kind != AttachmentKind.STICKER },
            visionClient = client,
            clipboard = clipboard,
            outputDirectory = attachmentsRoot,
            cacheDirectory = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."),
        )
    }

    /** Настройки пользователя словами — для get_app_settings. */
    private fun settingsSummary(): String {
        fun onOff(value: Boolean) = if (value) "включено" else "выключено"
        val lines = mutableListOf(
            "Рассуждение: ${onOff(_reasoningEnabled.value)}",
            "Поиск в интернете: ${onOff(_searchEnabled.value)}",
            "Память Honer AI: ${onOff(_memoryEnabled.value)}, записей: ${_memories.value.size}",
            "Чатов: ${chats.count { it.archivedAt == null }}, в архиве: ${chats.count { it.archivedAt != null }}",
        )
        settings?.let { s ->
            val theme = when (s.appearance.value) { "system" -> "как в системе"; "light" -> "светлая"; else -> "тёмная" }
            lines += listOf(
                "Озвучивать ответы автоматически: ${onOff(s.autoRead.value)}",
                "Уведомления о готовом ответе: ${onOff(s.notificationsEnabled.value)}",
                "Стикеры и эмодзи в ответах: ${onOff(s.stickersEnabled.value)}",
                "Память между чатами: ${onOff(s.crossChatMemoryEnabled.value)}",
                "Тема оформления: $theme",
                "Обои в чате: ${if (s.wallpaperPath.value.isNotEmpty()) "установлены пользователем" else "нет"}",
                "Язык интерфейса: ${if (s.language.value == "en") "английский" else "русский"}",
                "Размер шрифта: ${Math.round(s.fontScale.value * 100)}%",
                "Скорость чтения вслух: ${String.format(java.util.Locale.US, "%.2f", s.voiceRate.value)}",
                "Язык распознавания речи: ${s.speechLanguage.value}",
                "Автоудаление чатов: ${if (s.autoDeleteDays.value == 0) "никогда" else "через ${s.autoDeleteDays.value} дн."}",
                "Имя в профиле: ${s.displayName.value.ifEmpty { "не указано" }}",
            )
        }
        return lines.joinToString("\n") { "• $it" }
    }

    /** Действие по просьбе модели: сообщение в другой чат, память, настройки, чаты, игра. */
    private fun apply(effect: ToolEffect) {
        when (effect) {
            is ToolEffect.SendToChat -> chatForNumber(effect.number)?.let { appendMessage(effect.text, it.id) }
            is ToolEffect.SendToChatID -> appendMessage(effect.text, effect.id)
            is ToolEffect.RenameChat -> if (chats.any { it.id == effect.id }) {
                mutateChat(effect.id) { it.copy(title = effect.title.take(100)) }
                saveSnapshot()
            }
            is ToolEffect.PinChat -> if (chats.any { it.id == effect.id }) {
                mutateChat(effect.id) { it.copy(pinned = effect.pinned, pinOrder = if (effect.pinned) it.pinOrder else 0) }
                saveSnapshot()
            }
            is ToolEffect.SaveMemory -> addMemory(effect.text)
            is ToolEffect.SetSetting -> applySetting(effect.name, effect.value)
            is ToolEffect.OpenGame -> if (ParentalGuard.isGameAllowed(effect.raw)) _requestedGame.value = effect.raw
            is ToolEffect.UpdateMemory -> updateMemory(effect.id, effect.text)
            is ToolEffect.DeleteMemory -> deleteMemory(effect.id)
            is ToolEffect.AddSources, is ToolEffect.CreateTable, is ToolEffect.ReplaceTable, is ToolEffect.AttachFile -> {}
        }
    }

    /** Действия, которые показываются в самом ответе: таблица, отредактированное фото. */
    private fun applyToAnswer(effect: ToolEffect, chatId: String, messageId: String): Boolean {
        when (effect) {
            is ToolEffect.CreateTable -> {
                mutateChat(chatId) { it.copy(tables = it.tables.orEmpty() + effect.table) }
                mutateMessage(chatId, messageId) { it.copy(tableIDs = it.tableIDs.orEmpty() + effect.table.id) }
            }
            is ToolEffect.ReplaceTable -> {
                mutateChat(chatId) { chat ->
                    if (chat.tables.orEmpty().none { it.id == effect.table.id }) chat
                    else chat.copy(tables = chat.tables.orEmpty().map { if (it.id == effect.table.id) effect.table else it })
                }
                // Изменённая таблица показывается и под новым ответом.
                mutateMessage(chatId, messageId) { m ->
                    if (m.tableIDs.orEmpty().contains(effect.table.id)) m else m.copy(tableIDs = m.tableIDs.orEmpty() + effect.table.id)
                }
            }
            is ToolEffect.AttachFile -> mutateMessage(chatId, messageId) { it.copy(attachments = it.attachments + effect.attachment) }
            else -> return false
        }
        saveSnapshot()
        return true
    }

    private fun appendMessage(text: String, chatId: String) {
        if (chats.none { it.id == chatId }) return
        mutateChat(chatId) { it.copy(messages = it.messages + ChatMessage(role = MessageRole.ASSISTANT, content = text), updatedAt = Instant.now()) }
        saveSnapshot()
    }

    private fun chatForNumber(number: Int): Conversation? = sortedConversations().getOrNull(number - 1)

    /** Настройки, которые разрешено менять модели. */
    private fun applySetting(name: String, value: String) {
        val lowered = name.lowercase()
        if (lowered.startsWith("rename_chat:")) {
            val chat = lowered.removePrefix("rename_chat:").toIntOrNull()?.let { chatForNumber(it) } ?: return
            mutateChat(chat.id) { it.copy(title = value.take(100)) }
            saveSnapshot()
            return
        }
        if (lowered.startsWith("pin_chat:")) {
            val chat = lowered.removePrefix("pin_chat:").toIntOrNull()?.let { chatForNumber(it) } ?: return
            val pinned = value == "true"
            mutateChat(chat.id) { it.copy(pinned = pinned, pinOrder = if (pinned) it.pinOrder else 0) }
            saveSnapshot()
            return
        }
        val flag = value.lowercase() in setOf("true", "1", "да", "вкл", "on", "yes")
        when (lowered) {
            "reasoning" -> setReasoningEnabled(flag)
            "search" -> setSearchEnabled(flag && ParentalGuard.canSearchWeb)
            "notifications" -> settings?.setNotificationsEnabled(flag)
            "autoread" -> settings?.setAutoRead(flag)
            "fontscale" -> value.replace(',', '.').toDoubleOrNull()?.let { settings?.setFontScale(it.coerceIn(0.85, 1.5)) }
        }
    }

    // ---- Лента шагов ----

    private fun startStep(step: GenerationStep) {
        currentSteps.add(if (step.startedAt == null) step.copy(startedAt = Instant.now()) else step)
        pacerSetSteps()
    }

    private fun updateStep(id: String, detail: String? = null, sites: List<String>? = null, done: Boolean? = null) {
        val index = currentSteps.indexOfFirst { it.id == id }
        if (index < 0) return
        var step = currentSteps[index]
        if (detail != null) step = step.copy(detail = detail)
        if (!sites.isNullOrEmpty()) step = step.copy(sites = sites.take(30))
        if (done != null) step = step.copy(done = done)
        currentSteps[index] = step
        pacerSetSteps()
    }

    // ---- Печать (TypingPacer живёт на главном потоке) ----

    private fun pacerBegin(id: String) { if (platform) pacer.begin(id) }
    private fun pacerUpdate(content: String, reasoning: String) { if (platform) pacer.update(content, reasoning) }
    private fun pacerSetSteps() { if (platform) pacer.setSteps(currentSteps.toList()) }
    private fun pacerCancel() { if (platform) pacer.cancel() }

    private fun pacerClose(content: String, reasoning: String) {
        if (platform) pacer.close(content, reasoning) else _typingMessageId.value = null
    }

    // ---- Статистика ----

    private fun loadStatistics(): UsageStatistics = prefs.getString(KEY_STATISTICS)
        ?.let { runCatching { HonerJson.decodeFromString(UsageStatistics.serializer(), it) }.getOrNull() } ?: UsageStatistics()

    private fun setStatistics(value: UsageStatistics) {
        _statistics.value = value
        prefs.putString(KEY_STATISTICS, HonerJson.encodeToString(UsageStatistics.serializer(), value))
    }

    private fun recordSentMessage(voice: Boolean) {
        val current = _statistics.value
        setStatistics(current.copy(sentMessages = current.sentMessages + 1, voiceMessages = current.voiceMessages + if (voice) 1 else 0))
    }

    private fun recordReceivedMessage() {
        setStatistics(_statistics.value.let { it.copy(receivedMessages = it.receivedMessages + 1) })
    }

    /** Время в приложении: экран сообщает его при уходе в фон (как addSessionTime на iPhone). */
    override fun recordSessionTime(seconds: Double) {
        if (seconds <= 0 || seconds >= 3600) return
        setStatistics(_statistics.value.let { it.copy(totalSessionSeconds = it.totalSessionSeconds + seconds) })
    }

    override fun resetStatistics() {
        setStatistics(UsageStatistics(firstLaunch = _statistics.value.firstLaunch))
    }

    override fun setResponseLanguage(code: String) {
        configuration = configuration.copy(language = if (code == "en") "en" else "ru")
    }

    override fun setProfile(name: String, birthday: String) {
        profileName = name
        profileBirthday = birthday
    }

    // ---- Сохранение ----

    private val archive: HistoryArchive
        get() = HistoryArchive(
            conversations = chats, selectedConversationID = _selected.value, draft = draft.value,
            attachments = _attachments.value, inFlightMessageID = activeMessageId, memories = _memories.value,
            memoryEnabled = _memoryEnabled.value, instructionLibrary = _instructionLibrary.value,
            appSettings = settings?.let { runCatching { it.exportSnapshot() }.getOrNull() },
        )

    private fun scheduleSave() {
        if (isLoading || persistenceJob != null) return
        persistenceJob = scope.launch {
            delay(700)
            persistenceJob = null
            saveSnapshot()
        }
    }

    private fun saveSnapshot() {
        if (isLoading) return
        persistenceJob?.cancel()
        persistenceJob = null
        persistence.enqueue(archive) { error ->
            scope.launch { _errorMessage.value = "Не удалось сохранить историю: ${error.message.orEmpty()}" }
        }
    }

    /** Бэкап истории+настроек в JSON для облака (п.12). Без бинарных вложений — только текст/структура. */
    override fun exportBackupJson(): String = HistoryArchiveIO.encode(archive)

    /** Восстановление из облачного бэкапа: сливает чаты/настройки с текущими (дедуп по id). */
    override fun importBackupJson(json: String) {
        runCatching { applyImport(HistoryArchiveIO.decode(json)) }
    }

    /** Немедленная запись (уход приложения в фон). */
    override fun persistNow() {
        if (isLoading) return
        persistenceJob?.cancel()
        persistenceJob = null
        try {
            persistence.saveSynchronously(archive)
        } catch (e: Throwable) {
            _errorMessage.value = "Не удалось сохранить историю: ${e.message.orEmpty()}"
        }
        // Облачный бэкап при уходе в фон, если пользователь вошёл в аккаунт (п.12). Фоном, молча.
        if (com.honerai.app.cloud.CloudManager.account.value?.linked == true) {
            scope.launch { runCatching { com.honerai.app.cloud.CloudManager.uploadBackup(exportBackupJson()) } }
        }
    }

    override suspend fun exportData(): Result<File> {
        val snapshot = archive
        val directory = File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "exports")
        return try {
            Result.success(withContext(Dispatchers.IO) { HistoryArchiveIO.export(snapshot, directory) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun importData(uri: Uri): Result<Unit> {
        val ctx = context ?: return Result.failure(HonorError.InvalidArchive())
        return try {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > HistoryArchiveIO.MAX_ARCHIVE_BYTES) throw HonorError.ArchiveTooLarge()
                    }
                    out.toByteArray()
                } ?: throw HonorError.InvalidArchive()
            }
            importBytes(bytes)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Импорт архива из байтов (файл резервной копии). */
    internal suspend fun importBytes(bytes: ByteArray) {
        val existing = chats.map { it.id }.toSet()
        val incoming = withContext(Dispatchers.IO) { HistoryArchiveIO.prepareImport(bytes, existing, attachmentsRoot) }
        applyImport(incoming)
    }

    private fun applyImport(incoming: HistoryArchive) {
        val merged = _memories.value.toMutableList()
        for (memory in incoming.memories.orEmpty()) {
            if (merged.any { it.id == memory.id || it.text.equals(memory.text, ignoreCase = true) }) continue
            merged.add(memory)
        }
        if (merged.size > ChatLogic.MAXIMUM_MEMORY_COUNT) {
            removeUnreferencedAttachments(incoming.conversations.flatMap { it.messages }.flatMap { it.attachments })
            throw HonorError.MemoryLimit()
        }
        stop()
        val wasEmpty = chats.isEmpty() && _memories.value.isEmpty()
        val existing = chats.map { it.id }.toSet()
        chats = (chats + incoming.conversations.filter { it.id !in existing }).sortedByDescending { it.updatedAt }
        _memories.value = merged
        val library = _instructionLibrary.value.toMutableList()
        for (saved in incoming.instructionLibrary.orEmpty()) if (library.none { it.text == saved.text }) library.add(saved)
        _instructionLibrary.value = library
        incoming.appSettings?.let { values -> settings?.let { runCatching { it.applySnapshot(values) } } }
        if (wasEmpty) _memoryEnabled.value = incoming.memoryEnabled ?: true
        saveSnapshot()
    }

    private fun applyLoadedHistory(result: HistoryReadResult) {
        val loaded = result.archive
        if (loaded != null) {
            var list = loaded.conversations.map { chat ->
                // Старый «Промт чата» становится закреплённой инструкцией этого чата.
                val legacy = chat.systemPrompt.trim()
                if (legacy.isEmpty()) chat else {
                    val instructions = chat.instructions.orEmpty()
                    chat.copy(instructions = if (instructions.any { it.text == legacy }) instructions else listOf(ChatInstruction(text = legacy)) + instructions,
                        systemPrompt = "")
                }
            }
            // Прерванный процессом ответ помечается как прерванный — у любого пустого ответа, не только последнего.
            val stale = Instant.now().minusSeconds(30 * 60)
            list = list.map { chat ->
                chat.copy(messages = chat.messages.map { message ->
                    var m = message
                    if (m.continuesInBackground == true && m.createdAt.isBefore(stale)) m = m.copy(continuesInBackground = null, isInterrupted = true)
                    if (m.role == MessageRole.ASSISTANT && m.error == null &&
                        (m.content.isBlank() || m.id == loaded.inFlightMessageID) && m.continuesInBackground != true) {
                        m = m.copy(isInterrupted = true)
                    }
                    m
                })
            }
            _conversations.value = list
            val selected = loaded.selectedConversationID?.takeIf { id -> list.any { it.id == id && it.archivedAt == null } }
            setSelected(selected)
            draft.value = loaded.draft
            _attachments.value = loaded.attachments
            _memories.value = loaded.memories.orEmpty()
                .filter { it.text.isNotBlank() && it.text.length <= ChatLogic.MAXIMUM_MEMORY_LENGTH }
                .take(ChatLogic.MAXIMUM_MEMORY_COUNT)
            _memoryEnabled.value = loaded.memoryEnabled ?: true
            _instructionLibrary.value = loaded.instructionLibrary.orEmpty()
        }
        result.error?.let { _errorMessage.value = it }
    }

    /** Удаляет файлы вложений, на которые больше никто не ссылается (только в папке вложений). */
    private fun removeUnreferencedAttachments(candidates: List<MessageAttachment>) {
        if (candidates.isEmpty()) return
        fun paths(attachment: MessageAttachment) = listOfNotNull(attachment.localPath) + attachment.videoFramePaths.orEmpty()
        fun canonical(path: String) = runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath)
        val retained = (chats.flatMap { it.messages }.flatMap { it.attachments } + _attachments.value)
            .flatMap { paths(it) }.map { canonical(it) }.toHashSet()
        val root = canonical(attachmentsRoot.path) + File.separator
        for (path in candidates.flatMap { paths(it) }) {
            val file = canonical(path)
            if (!file.startsWith(root) || file in retained) continue
            File(file).delete()
        }
    }

    companion object {
        private const val KEY_REASONING = "honor.reasoningEnabled"
        private const val KEY_SEARCH = "honor.searchEnabled"
        private const val KEY_STATISTICS = "honor.statistics"
        private const val FLUSH_INTERVAL_MS = 40L

        /** Хранилище для модульных тестов: без Android, с подменой клиента и поиска. */
        internal fun forTesting(
            directory: File,
            client: DeepSeekStreaming? = null,
            search: WebSearching? = null,
            apiKey: String = "test",
            storageFile: File = File(directory, "history-${newId()}.json"),
            loadAsynchronously: Boolean = false,
        ): ChatStore = ChatStore(null, null, storageFile, File(directory, "attachments").apply { mkdirs() }, client,
            search ?: WebSearchClient(), MemoryKeyValueStore(), DeepSeekConfiguration(apiKey = apiKey), loadAsynchronously, false)
    }
}
