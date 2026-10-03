package com.honerai.admin.core

import android.content.Context
import android.net.Uri
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.AttachmentRef
import com.honerai.admin.data.Chat
import com.honerai.admin.data.ClientFrames
import com.honerai.admin.data.Message
import com.honerai.admin.data.Sender
import com.honerai.admin.data.SendMessageBody
import com.honerai.admin.data.ServerFrame
import com.honerai.admin.net.ApiClient
import com.honerai.admin.net.Realtime
import com.honerai.admin.net.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Вложение, выбранное администратором, до загрузки на сервер. [uri] — content:// или file://. */
data class LocalAttachment(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: String,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val localId: String = "local:" + UUID.randomUUID().toString(),
)

data class ChatUiState(
    val chat: Chat? = null,
    val items: List<ChatItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val userTyping: Boolean = false,
    val aiTyping: Boolean = false,
    /** Прогресс загрузки вложений по clientId черновика (0…1). */
    val uploads: Map<String, Float> = emptyMap(),
    val aiBusy: Boolean = false,
)

/**
 * Состояние одного чата с пользователем: лента, отправка (черновик → эхо сервера по clientId, повтор),
 * реакции, закрепление, удаление, очистка, ИИ в чате, «печатает…» и прочтения.
 * Живёт в [AdminContainer] пока идёт сессия — повторное открытие чата мгновенное.
 */
class ChatController(
    val chatId: String,
    private val context: Context,
    private val api: ApiClient,
    private val realtime: Realtime,
    private val repo: AdminRepository,
    private val scope: CoroutineScope,
    private val settings: AdminSettings,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> get() = _state

    /** Короткие сообщения для всплывающей подсказки (ошибки действий). */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> get() = _toasts

    private val pending = HashMap<String, PendingSend>()
    private val typing = TypingThrottle()
    private var typingTicker: Job? = null
    private var userTypingReset: Job? = null
    private var aiTypingReset: Job? = null
    private var loadJob: Job? = null
    private var lastReadSent: String? = null

    private val english get() = settings.english.value

    private class PendingSend(
        val clientId: String,
        val text: String,
        val replyTo: String?,
        val locals: List<LocalAttachment>,
        val uploaded: MutableMap<String, AttachmentRef> = HashMap(),
    )

    // --- Загрузка -----------------------------------------------------------------------------------------

    /** Первая страница (или обновление после переподключения). */
    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (_state.value.loaded && !force) return
        _state.update { it.copy(loading = !it.loaded, error = null) }
        loadJob = scope.launch {
            try {
                val chat = runCatching { api.chats().firstOrNull { it.id == chatId } }.getOrNull()
                val page = api.messages(chatId, before = null)
                _state.update { s ->
                    var items = MessageMerge.replaceWithServer(s.items, page)
                    val readUpTo = chat?.peerReadUpTo
                    if (readUpTo != null) items = MessageMerge.applyRead(items, Sender.USER, readUpTo)
                    // Уже загруженные старые страницы при обновлении сохраняем, если новая страница с ними смыкается.
                    var keptOlder = false
                    if (s.loaded && page.isNotEmpty()) {
                        val joint = s.items.indexOfFirst { it.message.id == page.first().id }
                        if (joint > 0) {
                            items = MessageMerge.prependOlder(items, s.items.take(joint).filter { !it.isLocal }.map { it.message })
                            keptOlder = true
                        }
                    }
                    s.copy(
                        chat = chat ?: s.chat,
                        items = items,
                        loading = false,
                        loaded = true,
                        hasMore = if (keptOlder) s.hasMore else page.size >= ApiClient.PAGE,
                        error = null,
                        userTyping = chat?.peerTyping ?: s.userTyping,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = friendlyError(e, english)) }
            }
        }
    }

    fun loadOlder() {
        val s = _state.value
        if (s.loadingOlder || !s.hasMore || !s.loaded) return
        val before = MessageMerge.oldestServerId(s.items) ?: return
        _state.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val page = api.messages(chatId, before)
                _state.update { it.copy(items = MessageMerge.prependOlder(it.items, page), loadingOlder = false, hasMore = page.size >= ApiClient.PAGE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingOlder = false) }
                _toasts.tryEmit(friendlyError(e, english))
            }
        }
    }

    // --- Отправка -------------------------------------------------------------------------------------------

    fun send(text: String, attachments: List<LocalAttachment>, replyTo: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        stopTyping()
        val clientId = UUID.randomUUID().toString()
        val draft = Message(
            id = ChatItem.LOCAL_PREFIX + clientId,
            clientId = clientId,
            chatId = chatId,
            sender = Sender.ADMIN,
            text = trimmed,
            attachments = attachments.map { it.toPreviewRef() },
            replyTo = replyTo,
            createdAt = Times.nowIso(),
        )
        pending[clientId] = PendingSend(clientId, trimmed, replyTo, attachments)
        _state.update { it.copy(items = MessageMerge.addOptimistic(it.items, draft)) }
        deliver(clientId)
    }

    fun retry(clientId: String) {
        if (!pending.containsKey(clientId)) return
        _state.update { it.copy(items = MessageMerge.markState(it.items, clientId, SendState.SENDING)) }
        deliver(clientId)
    }

    /** Убрать неотправленный черновик. */
    fun discard(clientId: String) {
        pending.remove(clientId)
        _state.update { it.copy(items = MessageMerge.remove(it.items, ChatItem.LOCAL_PREFIX + clientId), uploads = it.uploads - clientId) }
    }

    private fun deliver(clientId: String) {
        val job = pending[clientId] ?: return
        scope.launch {
            try {
                val refs = ArrayList<AttachmentRef>()
                job.locals.forEachIndexed { index, local ->
                    val ready = job.uploaded[local.localId] ?: upload(local) { p ->
                        val total = (index + p) / job.locals.size
                        _state.update { it.copy(uploads = it.uploads + (clientId to total)) }
                    }.also { job.uploaded[local.localId] = it }
                    refs += ready
                }
                val sent = api.sendMessage(chatId, SendMessageBody(clientId, job.text, refs, job.replyTo))
                pending.remove(clientId)
                job.locals.filter { it.uri.startsWith("file:") }.forEach { runCatching { Uri.parse(it.uri).path?.let { p -> File(p).delete() } } }
                _state.update { it.copy(items = MessageMerge.upsert(it.items, sent), uploads = it.uploads - clientId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(items = MessageMerge.markState(it.items, clientId, SendState.FAILED), uploads = it.uploads - clientId) }
                _toasts.tryEmit(friendlyError(e, english))
            }
        }
    }

    private suspend fun upload(local: LocalAttachment, progress: (Float) -> Unit): AttachmentRef {
        val uri = Uri.parse(local.uri)
        val extra = buildMap {
            put("kind", local.kind)
            put("name", local.name)
            local.durationMs?.let { put("durationMs", it.toString()) }
            local.width?.let { put("width", it.toString()) }
            local.height?.let { put("height", it.toString()) }
        }
        val ref = api.upload(local.name, local.mime, local.size, extra, open = {
            if (uri.scheme == "file") File(uri.path!!).inputStream()
            else context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(local.uri)
        }, onProgress = progress)
        // Вид и длительность голосового знает только телефон — дополняем ответ сервера.
        return ref.copy(
            kind = if (local.kind == AttachmentKinds.VOICE) AttachmentKinds.VOICE else ref.kind.ifBlank { local.kind },
            durationMs = ref.durationMs ?: local.durationMs,
            width = ref.width ?: local.width,
            height = ref.height ?: local.height,
        )
    }

    private fun LocalAttachment.toPreviewRef() = AttachmentRef(
        id = localId, kind = kind, name = name, mime = mime, size = size,
        durationMs = durationMs, width = width, height = height, url = uri,
    )

    // --- Действия с сообщениями ------------------------------------------------------------------------------

    fun react(message: Message, emoji: String) {
        val next = MessageMerge.nextReaction(message, emoji)
        val before = message
        _state.update { it.copy(items = MessageMerge.applyUpdate(it.items, MessageMerge.withAdminReaction(message, next))) }
        action({ _state.update { it.copy(items = MessageMerge.applyUpdate(it.items, before)) } }) {
            api.react(chatId, message.id, next)?.let { m -> _state.update { it.copy(items = MessageMerge.applyUpdate(it.items, m)) } }
        }
    }

    fun pin(message: Message, pinned: Boolean) {
        val oldPinned = _state.value.chat?.pinnedMessageId
        applyPin(message.copy(pinned = pinned))
        action({ applyPin(message); _state.update { s -> s.copy(chat = s.chat?.copy(pinnedMessageId = oldPinned)) } }) {
            api.pin(chatId, message.id, pinned)?.let { applyPin(it) }
        }
    }

    private fun applyPin(message: Message) {
        _state.update { s ->
            val chat = s.chat?.let { c ->
                when {
                    message.pinned -> c.copy(pinnedMessageId = message.id)
                    c.pinnedMessageId == message.id -> c.copy(pinnedMessageId = null)
                    else -> c
                }
            }
            s.copy(items = MessageMerge.applyUpdate(s.items, message), chat = chat)
        }
    }

    /** Правка своего сообщения (расширение сервера PATCH …/messages/:id). */
    fun edit(message: Message, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == message.text) return
        stopTyping()
        _state.update { it.copy(items = MessageMerge.applyUpdate(it.items, message.copy(text = trimmed, editedAt = Times.nowIso()))) }
        action({ _state.update { it.copy(items = MessageMerge.applyUpdate(it.items, message)) } }) {
            api.editMessage(chatId, message.id, trimmed)?.let { m -> _state.update { it.copy(items = MessageMerge.applyUpdate(it.items, m)) } }
        }
    }

    fun delete(message: Message, everyone: Boolean) {
        val snapshot = _state.value.items
        _state.update {
            it.copy(items = if (everyone) MessageMerge.applyUpdate(it.items, message.copy(deleted = true, text = "", attachments = emptyList()))
            else MessageMerge.remove(it.items, message.id))
        }
        action({ _state.update { it.copy(items = snapshot) } }) { api.deleteMessage(chatId, message.id, everyone) }
    }

    fun clear(everyone: Boolean) {
        val snapshot = _state.value
        _state.update { it.copy(items = it.items.filter { i -> i.isLocal && i.state == SendState.SENDING }, hasMore = false, chat = it.chat?.copy(pinnedMessageId = null)) }
        action({ _state.value = snapshot }) { api.clearChat(chatId, everyone) }
    }

    fun setAi(enabled: Boolean) {
        _state.update { it.copy(aiBusy = true, chat = it.chat?.copy(aiEnabled = enabled)) }
        scope.launch {
            try {
                val chat = api.setAi(chatId, enabled)
                _state.update { s -> s.copy(aiBusy = false, chat = chat?.let { c -> c.copy(aiEnabled = enabled) } ?: s.chat) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(aiBusy = false, chat = it.chat?.copy(aiEnabled = !enabled)) }
                _toasts.tryEmit(friendlyError(e, english))
            }
        }
    }

    /** «Взять» обращение в работу (assigned=true) или освободить. Обновляет chat из ответа сервера. План п.16. */
    fun assign(assigned: Boolean) {
        scope.launch {
            try {
                val chat = api.assignChat(chatId, assigned)
                if (chat != null) _state.update { it.copy(chat = chat) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toasts.tryEmit(friendlyError(e, english))
            }
        }
    }

    private fun action(rollback: () -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rollback()
                _toasts.tryEmit(friendlyError(e, english))
            }
        }
    }

    // --- «Печатает…» и прочтения --------------------------------------------------------------------------

    fun onInput(text: String) {
        typing.onInput(System.currentTimeMillis(), text.isBlank())?.let { realtime.send(ClientFrames.typing(chatId, it)) }
        if (typingTicker?.isActive != true) {
            typingTicker = scope.launch {
                while (isActive) {
                    delay(1_000)
                    val result = typing.onTick(System.currentTimeMillis())
                    if (result != null) { realtime.send(ClientFrames.typing(chatId, result)); break }
                }
            }
        }
    }

    fun stopTyping() {
        typingTicker?.cancel()
        typing.stop()?.let { realtime.send(ClientFrames.typing(chatId, it)) }
    }

    /** Экран виден и внизу ленты — всё прочитано. */
    fun markRead() {
        val last = MessageMerge.lastPeerMessageId(_state.value.items) ?: return
        repo.markChatRead(chatId)
        if (last == lastReadSent) return
        lastReadSent = last
        if (!realtime.send(ClientFrames.read(chatId, last))) {
            // Сокет переподключается — отмечаем прочтение через REST.
            scope.launch {
                try {
                    api.markRead(chatId, last)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (lastReadSent == last) lastReadSent = null
                }
            }
        }
    }

    /** Сведения о чате (aiEnabled, закреп) — после сообщений ИИ о входе/выходе из чата; не чаще раза в 5 с. */
    private var chatInfoAt = 0L

    private fun refreshChatInfo() {
        val now = System.currentTimeMillis()
        if (now - chatInfoAt < 5_000) return
        chatInfoAt = now
        scope.launch {
            val chat = runCatching { api.chats().firstOrNull { it.id == chatId } }.getOrNull() ?: return@launch
            _state.update { it.copy(chat = chat) }
        }
    }

    // --- Кадры сервера --------------------------------------------------------------------------------------

    fun onFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.NewMessage -> {
                if (frame.message.sender == Sender.AI && _state.value.loaded) refreshChatInfo()
                _state.update { s ->
                    s.copy(
                        items = MessageMerge.upsert(s.items, frame.message),
                        userTyping = if (frame.message.sender == Sender.USER) false else s.userTyping,
                        aiTyping = if (frame.message.sender == Sender.AI) false else s.aiTyping,
                    )
                }
            }
            is ServerFrame.MessageUpdated -> {
                val m = frame.message
                _state.update { s ->
                    val chat = s.chat?.let { c ->
                        when {
                            m.pinned && !m.deleted -> c.copy(pinnedMessageId = m.id)
                            c.pinnedMessageId == m.id && (!m.pinned || m.deleted) -> c.copy(pinnedMessageId = null)
                            else -> c
                        }
                    }
                    s.copy(items = MessageMerge.applyUpdate(s.items, m), chat = chat)
                }
            }
            is ServerFrame.ChatCleared -> _state.update { s ->
                s.copy(items = s.items.filter { it.isLocal }, chat = s.chat?.copy(pinnedMessageId = null), hasMore = false)
            }
            is ServerFrame.Typing -> when (frame.who) {
                Sender.USER -> {
                    _state.update { it.copy(userTyping = frame.typing) }
                    userTypingReset?.cancel()
                    // Если typing:false потеряется — индикатор сам гаснет.
                    if (frame.typing) userTypingReset = scope.launch { delay(8_500); _state.update { it.copy(userTyping = false) } }
                }
                Sender.AI -> {
                    _state.update { it.copy(aiTyping = frame.typing) }
                    aiTypingReset?.cancel()
                    if (frame.typing) aiTypingReset = scope.launch { delay(60_000); _state.update { it.copy(aiTyping = false) } }
                }
                else -> Unit
            }
            is ServerFrame.Read -> if (frame.who == Sender.USER) {
                _state.update { s -> s.copy(items = MessageMerge.applyRead(s.items, Sender.USER, frame.messageId), chat = s.chat?.copy(peerReadUpTo = frame.messageId)) }
            }
            else -> Unit
        }
    }
}
