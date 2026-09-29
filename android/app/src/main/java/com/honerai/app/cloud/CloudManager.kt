package com.honerai.app.cloud

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.honerai.app.AppContainer
import com.honerai.app.BuildConfig
import com.honerai.app.core.AppSettings
import com.honerai.app.data.MessageRole
import com.honerai.app.device.DeviceInfo
import com.honerai.app.device.HonerNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID

/** Всплывающая плашка поверх приложения (новое сообщение администратора или рассылка). */
data class CloudBanner(val id: Long, val title: String, val text: String, val target: String, val fromAi: Boolean = false)

/**
 * Honer Cloud на стороне устройства: регистрация, профиль и статистика, живое соединение,
 * чат с администратором, уведомления, блокировка. Состояние — StateFlow для экранов Compose;
 * меняется на главном потоке, сеть — в Dispatchers.IO.
 * Без HONER_CLOUD_URL [start] ничего не делает — облака нет.
 */
object CloudManager {
    private lateinit var app: Context
    @Volatile private var started = false
    val enabled: Boolean get() = started && CloudConfig.isConfigured

    lateinit var credentials: CloudCredentials
        private set
    lateinit var api: CloudApi
        private set
    lateinit var notifications: NotificationsStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registerMutex = Mutex()
    private var socket: CloudSocket? = null
    private val settings: AppSettings get() = AppContainer.get(app).settings

    // ---- Состояние ----
    private val _registered = MutableStateFlow(false)
    private val _deviceId = MutableStateFlow<String?>(null)
    private val _blocked = MutableStateFlow<String?>(null)
    private val _chat = MutableStateFlow<CloudChat?>(null)
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    private val _peerTyping = MutableStateFlow(false)
    private val _aiTyping = MutableStateFlow(false)
    private val _peerPresence = MutableStateFlow("offline")
    private val _peerLastSeen = MutableStateFlow<Instant?>(null)
    private val _peerReadUpTo = MutableStateFlow(0L)
    private val _lastReadMs = MutableStateFlow(0L)
    private val _hasMore = MutableStateFlow(true)
    private val _loadingOlder = MutableStateFlow(false)
    private val _loading = MutableStateFlow(false)
    private val _uploads = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _banner = MutableStateFlow<CloudBanner?>(null)
    private val _chatVisible = MutableStateFlow(false)
    private val _socketState = MutableStateFlow(CloudSocket.State.CLOSED)

    val registered: StateFlow<Boolean> = _registered.asStateFlow()
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()
    /** null — доступ есть; строка — устройство заблокировано (причина, может быть пустой). */
    val blocked: StateFlow<String?> = _blocked.asStateFlow()
    val chat: StateFlow<CloudChat?> = _chat.asStateFlow()
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()
    val peerTyping: StateFlow<Boolean> = _peerTyping.asStateFlow()
    val aiTyping: StateFlow<Boolean> = _aiTyping.asStateFlow()
    val peerPresence: StateFlow<String> = _peerPresence.asStateFlow()
    val peerLastSeen: StateFlow<Instant?> = _peerLastSeen.asStateFlow()
    val peerReadUpTo: StateFlow<Long> = _peerReadUpTo.asStateFlow()
    val hasMoreHistory: StateFlow<Boolean> = _hasMore.asStateFlow()
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    /** Ход загрузки вложений по clientId (0..1). */
    val uploads: StateFlow<Map<String, Float>> = _uploads.asStateFlow()
    val banner: StateFlow<CloudBanner?> = _banner.asStateFlow()
    val socketState: StateFlow<CloudSocket.State> = _socketState.asStateFlow()

    /** Непрочитанные сообщения администратора. */
    val unread: StateFlow<Int> = combine(_entries, _lastReadMs, _chat) { list, lastRead, chat ->
        ChatMerge.unreadCount(list, lastRead, chat?.unread ?: 0)
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    @Volatile private var foreground = false
    private var foregroundSince = 0L
    private var backgroundClose: Job? = null
    private var statsTicker: Job? = null
    private var blockPoll: Job? = null
    private var presencePoll: Job? = null
    private var typingTicker: Job? = null
    private var peerTypingReset: Job? = null
    private var aiTypingReset: Job? = null
    private var lastStats: StatsRequest? = null
    private val typing = TypingThrottle()
    private val uploadedRefs = HashMap<String, CloudAttachment>()
    private var bannerCounter = 0L
    /** Держим ссылку: SharedPreferences хранит слушателей слабыми ссылками. */
    private var licenseListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val cacheFile: File get() = File(app.filesDir, "cloud/admin_chat.json")
    val outboxDir: File get() = File(app.filesDir, "cloud/outbox").apply { mkdirs() }
    val mediaCacheDir: File get() = File(app.cacheDir, "cloud_media").apply { mkdirs() }

    // ---- Запуск ----

    /** Вызывается из HonerApp.onCreate (и из фоновых задач). Повторный вызов ничего не делает. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (!CloudConfig.isConfigured || started) return
        synchronized(this) {
            if (started) return
            app = context.applicationContext
            CloudManagerContext.context = app
            credentials = CloudCredentials(app)
            credentials.installId // проверка копии с другого телефона — до чтения токена
            api = CloudApi(CloudConfig.baseUrl, { credentials.token })
            notifications = NotificationsStore(File(app.filesDir, "cloud/notifications.json"))
            started = true
        }
        AiProxy.installed = true
        CloudNotifier.ensureChannels(app)
        _registered.value = credentials.token != null
        _deviceId.value = credentials.deviceId
        _blocked.value = credentials.blockReason
        _lastReadMs.value = credentials.lastReadMs
        loadCache()

        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onForeground()
                override fun onStop(owner: LifecycleOwner) = onBackground()
            })
            // Регистрация — после знакомства (имя и дата рождения уже известны).
            launch {
                settings.completedOnboarding.first { it }
                if (ensureRegistered()) onRegistered()
            }
            // Профиль и язык: изменения уходят на сервер (с паузой, чтобы не слать каждую букву).
            launch {
                combine(settings.displayName, settings.birthday, settings.language) { a, b, c -> "$a|$b|$c" }
                    .drop(1).debounce(1_500).collect { syncProfile() }
            }
            // Сохранение ленты на диск — открывается мгновенно и без сети.
            launch { _entries.drop(1).debounce(800).collect { saveCache(it) } }
        }
        // Дата принятия лицензии (её пишет экран лицензии в общие настройки).
        val prefs = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        licenseListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LICENSE_KEY) scope.launch { syncProfile() }
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        watchNetwork()
        setupPush()
        cleanOutbox()
    }

    /** Копии отправленных файлов нужны для показа без скачивания; старше 30 дней — удаляются. */
    private fun cleanOutbox() {
        scope.launch(Dispatchers.IO) {
            val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            outboxDir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }
    }

    private fun onRegistered() {
        _registered.value = true
        if (foreground) {
            openSocket()
            scope.launch { refreshAll() }
        }
    }

    /**
     * Регистрация устройства (один раз; при 401 — заново с тем же installId).
     * [requireOnboarding] — автоматическая регистрация ждёт знакомства; запросы нейросети — нет.
     */
    suspend fun ensureRegistered(requireOnboarding: Boolean = true): Boolean {
        if (!enabled) return false
        if (credentials.token != null) return true
        if (requireOnboarding && !settings.completedOnboarding.value) return false
        return registerMutex.withLock {
            if (credentials.token != null) return@withLock true
            try {
                val request = registerRequest()
                val response = withContext(Dispatchers.IO) { api.register(request) }
                credentials.token = response.token
                credentials.deviceId = response.deviceId
                credentials.userId = response.userId
                if (response.adminChatId.isNotEmpty()) credentials.adminChatId = response.adminChatId
                credentials.profileFingerprint = profileFingerprint(request)
                scope.launch {
                    _deviceId.value = response.deviceId
                    if (_blocked.value != null) onUnblocked()
                    onRegistered()
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudHttpException) {
                if (e.isBlocked) onBlocked(e.serverMessage)
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Токен для прокси нейросети; если устройства ещё нет — регистрирует (не дольше 20 с). Вызывается из фонового потока. */
    fun tokenBlocking(): String? {
        if (!enabled) return null
        credentials.token?.let { return it }
        return runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(20_000) { ensureRegistered(requireOnboarding = false) }
            credentials.token
        }
    }

    /** Сервер не принял токен: следующая попытка зарегистрирует устройство заново. */
    fun invalidateToken() {
        if (!enabled) return
        credentials.clearSession()
        scope.launch { _registered.value = false }
    }

    private fun registerRequest(): RegisterRequest {
        val s = settings
        DeviceInfo.init(app)
        return RegisterRequest(
            installId = credentials.installId,
            platform = "android",
            deviceModel = DeviceInfo.modelName,
            deviceName = deviceName(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            displayName = s.displayName.value,
            birthday = s.birthday.value.takeIf { it.isNotBlank() },
            language = if (s.language.value == "en") "en" else "ru",
            licenseAcceptedAt = licenseAcceptedAt(),
            pushToken = credentials.pushToken,
        )
    }

    private fun deviceName(): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) runCatching {
            android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull() else null
        return name?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL.orEmpty()
    }

    /** Дата принятия лицензии: ключ пишет другой модуль — читаем осторожно, в любом формате. */
    private fun licenseAcceptedAt(): String? {
        val prefs = app.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        return LicenseDate.toIso(runCatching { prefs.all[LICENSE_KEY] }.getOrNull())
    }

    private fun profileFingerprint(request: RegisterRequest): String =
        listOf(request.displayName, request.birthday, request.language, request.licenseAcceptedAt, request.appVersion).joinToString("|")

    /** PATCH /v1/devices/me, если профиль, язык, лицензия или версия приложения изменились. */
    suspend fun syncProfile() {
        if (!enabled || credentials.token == null) return
        val request = registerRequest()
        val fingerprint = profileFingerprint(request)
        if (fingerprint == credentials.profileFingerprint) return
        val body = buildJsonObject {
            put("displayName", request.displayName)
            put("birthday", request.birthday?.let { JsonPrimitive(it) } ?: JsonNull)
            put("language", request.language)
            put("appVersion", request.appVersion)
            request.licenseAcceptedAt?.let { put("licenseAcceptedAt", it) }
        }
        if (runCatching { authed { it.patchMe(body) } }.isSuccess) credentials.profileFingerprint = fingerprint
    }

    // ---- Жизненный цикл ----

    private fun onForeground() {
        foreground = true
        foregroundSince = System.currentTimeMillis()
        backgroundClose?.cancel(); backgroundClose = null
        if (_blocked.value != null) { startBlockPoll(); return }
        if (credentials.token == null) {
            scope.launch { if (ensureRegistered()) onRegistered() }
            return
        }
        openSocket()
        socket?.send(ClientFrames.presence(true))
        scope.launch {
            refreshAll()
            syncProfile()
        }
        statsTicker?.cancel()
        statsTicker = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                uploadStats()
            }
        }
        if (_chatVisible.value) startPresencePoll()
    }

    private fun onBackground() {
        // Время текущего сеанса учитывается до перехода в фон (статистика приложения пишется раньше).
        foreground = false
        statsTicker?.cancel(); statsTicker = null
        presencePoll?.cancel(); presencePoll = null
        blockPoll?.cancel(); blockPoll = null
        stopTyping()
        socket?.send(ClientFrames.presence(false))
        scope.launch { uploadStats() }
        // Соединение держим ещё ~2 минуты (ответ администратора придёт сразу), потом закрываем ради батареи.
        backgroundClose?.cancel()
        backgroundClose = scope.launch {
            delay(BACKGROUND_KEEP_MS)
            if (!foreground) socket?.close()
        }
    }

    private fun openSocket() {
        if (!enabled || credentials.token == null || _blocked.value != null) return
        val current = socket ?: CloudSocket(
            client = CloudHttp.socket,
            scope = scope,
            url = { credentials.token?.let { CloudUrls.webSocket(CloudConfig.baseUrl, it) } },
            greeting = { ClientFrames.presence(foreground) },
            onFrame = ::onFrame,
            onRejected = { status, body ->
                val (code, message) = CloudHttpException.parseBody(body)
                if (status == 403 && (code == "blocked" || code.isEmpty())) onBlocked(message)
                else if (status == 401) {
                    invalidateToken()
                    scope.launch { if (ensureRegistered(false)) openSocket() }
                }
            },
        ).also { created ->
            socket = created
            scope.launch { created.state.collect { _socketState.value = it } }
        }
        current.open()
    }

    private fun watchNetwork() {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { if (foreground) socket?.reconnectNow() }
                }
            })
        }
    }

    private fun setupPush() {
        val fcm = PushSupport.available(app)
        if (fcm) {
            CloudPollWorker.cancel(app)
            PushSupport.fetchToken { token -> updatePushToken(token) }
        } else {
            CloudPollWorker.schedule(app)
        }
    }

    /** Новый токен FCM — на сервер. */
    fun updatePushToken(token: String) {
        if (!enabled || token.isBlank() || token == credentials.pushToken) return
        credentials.pushToken = token
        scope.launch {
            if (credentials.token == null) return@launch
            runCatching { authed { it.patchMe(buildJsonObject { put("pushToken", token) }) } }
        }
    }

    // ---- Запросы с токеном ----

    /** Запрос с токеном: 401 — регистрация заново и повтор; 403 blocked — экран блокировки. */
    private suspend fun <T> authed(block: (CloudApi) -> T): T {
        if (!ensureRegistered(requireOnboarding = false)) throw CloudHttpException(401, "no_token", "")
        val result = try {
            withContext(Dispatchers.IO) { block(api) }
        } catch (e: CloudHttpException) {
            when {
                e.isBlocked -> { onBlocked(e.serverMessage); throw e }
                e.isUnauthorized -> {
                    invalidateToken()
                    if (!ensureRegistered(requireOnboarding = false)) throw e
                    withContext(Dispatchers.IO) { block(api) }
                }
                else -> throw e
            }
        }
        if (_blocked.value != null) scope.launch { onUnblocked() }
        return result
    }

    // ---- Блокировка ----

    fun onBlocked(reason: String) {
        if (!enabled) return
        credentials.blockReason = reason
        scope.launch {
            _blocked.value = reason
            socket?.close()
            stopTyping()
            if (foreground) startBlockPoll()
        }
    }

    private fun onUnblocked() {
        if (_blocked.value == null) return
        credentials.blockReason = null
        _blocked.value = null
        blockPoll?.cancel(); blockPoll = null
        if (foreground) {
            openSocket()
            scope.launch { refreshAll() }
        }
    }

    /** Пока приложение открыто и заблокировано — проверяем раз в 20 с: снятие блокировки видно сразу. */
    private fun startBlockPoll() {
        if (blockPoll?.isActive == true) return
        blockPoll = scope.launch {
            while (isActive && _blocked.value != null && foreground) {
                val ok = runCatching {
                    if (credentials.token == null) ensureRegistered(requireOnboarding = false)
                    else { withContext(Dispatchers.IO) { api.chats() }; true }
                }
                if (ok.getOrNull() == true) { onUnblocked(); break }
                val error = ok.exceptionOrNull()
                if (error is CloudHttpException && error.isUnauthorized) invalidateToken()
                delay(BLOCK_POLL_MS)
            }
        }
    }

    // ---- Кадры сервера ----

    private fun onFrame(frame: CloudFrame) {
        when (frame) {
            is CloudFrame.Message -> if (isAdminChat(frame.chatId)) onIncoming(frame.message)
            is CloudFrame.MessageUpdated -> if (isAdminChat(frame.chatId)) {
                _entries.value = ChatMerge.applyPin(_entries.value, frame.message)
                if (frame.message.pinned) _chat.value = _chat.value?.copy(pinnedMessageId = frame.message.id)
                else if (_chat.value?.pinnedMessageId == frame.message.id) _chat.value = _chat.value?.copy(pinnedMessageId = null)
            }
            is CloudFrame.ChatCleared -> if (isAdminChat(frame.chatId)) {
                _entries.value = ChatMerge.clear() + _entries.value.filter { it.pending }
                _chat.value = _chat.value?.copy(pinnedMessageId = null, unread = 0)
            }
            is CloudFrame.Typing -> if (isAdminChat(frame.chatId)) onPeerTyping(frame.who, frame.typing)
            is CloudFrame.Read -> if (isAdminChat(frame.chatId) && frame.who != CloudMessage.SENDER_USER) {
                _peerReadUpTo.value = ChatMerge.readUpToTime(_entries.value, frame.messageId, _peerReadUpTo.value)
                peerSeenNow()
            }
            is CloudFrame.Presence -> Unit // приходит только администраторам
            is CloudFrame.Blocked -> onBlocked(frame.message)
            is CloudFrame.Notification -> onServerNotification(frame.notification, quiet = false)
            CloudFrame.Pong -> Unit
        }
        if (_blocked.value != null && frame !is CloudFrame.Blocked) onUnblocked()
    }

    private fun isAdminChat(chatId: String): Boolean {
        val known = credentials.adminChatId ?: _chat.value?.id
        if (known == null) { if (chatId.isNotEmpty()) credentials.adminChatId = chatId; return true }
        return chatId.isEmpty() || chatId == known
    }

    private fun onPeerTyping(who: String, value: Boolean) {
        if (who == CloudMessage.SENDER_AI) {
            _aiTyping.value = value
            aiTypingReset?.cancel()
            if (value) aiTypingReset = scope.launch { delay(60_000); _aiTyping.value = false }
        } else if (who == CloudMessage.SENDER_ADMIN) {
            _peerTyping.value = value
            peerSeenNow()
            peerTypingReset?.cancel()
            if (value) peerTypingReset = scope.launch { delay(8_000); _peerTyping.value = false }
        }
    }

    /** Администратор проявил себя (печатает, прочитал, написал) — он в сети. */
    private fun peerSeenNow() {
        _peerPresence.value = "foreground"
        _peerLastSeen.value = Instant.now()
    }

    private fun onIncoming(message: CloudMessage) {
        _entries.value = ChatMerge.upsert(_entries.value, message)
        if (message.fromUser) return
        if (message.fromAi) {
            _aiTyping.value = false
            // Администратор включил/выключил ИИ — сервер пишет об этом сообщением от ai; флаг aiEnabled — в GET /v1/chats.
            scope.launch { refreshChatMeta() }
        } else { _peerTyping.value = false; peerSeenNow() }
        handleNewPeer(message)
    }

    /** Новое сообщение собеседника: журнал уведомлений, плашка, системное уведомление (если чат не на экране). */
    private fun handleNewPeer(message: CloudMessage) {
        if (message.deleted || message.fromUser) return
        val time = ChatMerge.timeOf(message.createdAt)
        if (time <= credentials.lastNotifiedMs && time != 0L) return
        credentials.lastNotifiedMs = maxOf(credentials.lastNotifiedMs, time)
        if (_chatVisible.value && foreground) return
        if (time <= _lastReadMs.value) return
        val english = settings.isEnglish
        val name = if (message.fromAi) "Honer AI" else if (english) "Administrator" else "Администратор"
        val preview = MessagePreview.text(message, english)
        val added = notifications.add(NotificationEntry(
            id = "msg:" + message.id, kind = NotificationEntry.KIND_MESSAGE, title = name, body = preview,
            createdAtMs = if (time > 0) time else System.currentTimeMillis(), chatId = message.chatId.ifEmpty { credentials.adminChatId },
        ))
        if (!added) return
        if (!credentials.firstContactShown && message.fromAdmin) {
            credentials.firstContactShown = true
            CloudNotifier.firstContact(app)
        }
        if (foreground) showBanner(name, preview, CloudNotifier.OPEN_ADMIN, message.fromAi)
        if (settings.notificationsEnabled.value) CloudNotifier.adminMessage(app, message.sender, preview, unread.value)
    }

    private fun onServerNotification(notification: CloudNotification, quiet: Boolean) {
        val time = ChatMerge.timeOf(notification.createdAt).takeIf { it > 0 } ?: System.currentTimeMillis()
        val added = notifications.add(NotificationEntry(
            id = "n:" + notification.id, kind = notification.kind, title = notification.title.ifBlank { "Honer AI" },
            body = notification.body, createdAtMs = time, read = notification.read, chatId = notification.chatId,
            serverId = notification.id,
        ))
        val after = credentials.notificationsAfter
        if (after == null || ChatMerge.timeOf(after) < time) credentials.notificationsAfter = notification.createdAt.ifEmpty { after }
        if (!added || notification.read || quiet) return
        val title = notification.title.ifBlank { "Honer AI" }
        if (foreground) showBanner(title, notification.body, CloudNotifier.OPEN_NOTIFICATIONS)
        else if (settings.notificationsEnabled.value) CloudNotifier.broadcast(app, notification.id, title, notification.body)
    }

    private fun showBanner(title: String, text: String, target: String, fromAi: Boolean = false) {
        val banner = CloudBanner(++bannerCounter, title, text, target, fromAi)
        _banner.value = banner
        scope.launch {
            delay(4_500)
            if (_banner.value?.id == banner.id) _banner.value = null
        }
    }

    fun dismissBanner() { _banner.value = null }

    // ---- Синхронизация ----

    /** Чат, новые сообщения и уведомления (при открытии приложения и из фоновых задач). */
    suspend fun refreshAll() {
        if (!enabled || credentials.token == null) return
        refreshChat()
        refreshNotifications()
        uploadStats()
    }

    suspend fun refreshChat() {
        if (!enabled) return
        _loading.value = _entries.value.isEmpty()
        try {
            val chats = authed { it.chats() }
            val known = credentials.adminChatId
            val chat = chats.firstOrNull { it.id == known } ?: chats.firstOrNull { it.kind == "admin" } ?: chats.firstOrNull()
            if (chat != null) applyChatMeta(chat)
            val chatId = chat?.id ?: known ?: return
            val page = authed { it.messages(chatId, before = null) }
            val before = _entries.value.map { it.message.id }.toHashSet()
            _entries.value = ChatMerge.mergeLatest(_entries.value, page)
            if (page.size < CloudApi.PAGE) _hasMore.value = false
            chat?.peerReadUpTo?.let { _peerReadUpTo.value = ChatMerge.readUpToTime(_entries.value, it, _peerReadUpTo.value) }
            // Сообщения, пропущенные без соединения, — как новые.
            page.filter { it.id !in before && !it.fromUser && !it.deleted }.forEach { handleNewPeer(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            _loading.value = false
        }
    }

    private suspend fun refreshChatMeta() {
        runCatching {
            val chats = authed { it.chats() }
            (chats.firstOrNull { it.id == currentChatId() } ?: chats.firstOrNull { it.kind == "admin" })?.let { applyChatMeta(it) }
        }
    }

    private fun applyChatMeta(chat: CloudChat) {
        credentials.adminChatId = chat.id
        _chat.value = chat
        _peerPresence.value = chat.peerPresence
        chat.peerLastSeen?.let { iso -> runCatching { Instant.parse(iso) }.getOrNull() }?.let { _peerLastSeen.value = it }
        if (chat.peerTyping) onPeerTyping(CloudMessage.SENDER_ADMIN, true)
    }

    suspend fun refreshNotifications() {
        if (!enabled) return
        val after = credentials.notificationsAfter
        val list = runCatching { authed { it.notifications(after) } }.getOrNull() ?: return
        // Первая загрузка (after == null) — без системных уведомлений: это старые рассылки.
        list.sortedBy { ChatMerge.timeOf(it.createdAt) }.forEach { onServerNotification(it, quiet = after == null) }
    }

    /** Фоновая проверка (WorkManager без FCM, или push): новые сообщения и рассылки. */
    suspend fun backgroundSync() = withContext(Dispatchers.Main) {
        if (!enabled || credentials.token == null || _blocked.value != null) return@withContext
        if (foreground && _socketState.value == CloudSocket.State.OPEN) return@withContext
        refreshChat()
        refreshNotifications()
    }

    suspend fun loadOlder() {
        if (!enabled || _loadingOlder.value || !_hasMore.value) return
        val chatId = currentChatId() ?: return
        val oldest = _entries.value.firstOrNull { !it.pending } ?: return
        _loadingOlder.value = true
        try {
            val page = authed { it.messages(chatId, before = oldest.message.id) }
            _entries.value = ChatMerge.mergeOlder(_entries.value, page)
            if (page.size < CloudApi.PAGE) _hasMore.value = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            _loadingOlder.value = false
        }
    }

    private fun currentChatId(): String? = _chat.value?.id ?: credentials.adminChatId

    // ---- Статистика ----

    suspend fun uploadStats() {
        if (!enabled || credentials.token == null) return
        val stats = withContext(Dispatchers.Default) { currentStats() }
        if (stats == lastStats) return
        if (runCatching { authed { it.stats(stats) } }.isSuccess) lastStats = stats
    }

    /** Отправлено сообщений (максимум из счётчика и сообщений пользователя в чатах) и секунд в приложении. */
    private fun currentStats(): StatsRequest {
        val store = AppContainer.get(app).store
        val statistics = store.statistics.value
        val inChats = store.conversations.value.sumOf { chat -> chat.messages.count { it.role == MessageRole.USER } }
        val live = if (foreground && foregroundSince > 0) (System.currentTimeMillis() - foregroundSince) / 1000.0 else 0.0
        return StatsRequest(
            messagesSent = maxOf(statistics.sentMessages, inChats),
            secondsInApp = (statistics.totalSessionSeconds + live.coerceIn(0.0, 3600.0)).toInt(),
        )
    }

    // ---- Чат с администратором: действия пользователя ----

    /** Экран чата открыт/закрыт: пока открыт, новые сообщения не уведомляют, «в сети» обновляется. */
    fun setChatVisible(visible: Boolean) {
        _chatVisible.value = visible
        if (!enabled) return
        if (visible) {
            currentChatId()?.let { notifications.markChatRead(it) }
            CloudNotifier.cancelAdmin(app)
            if (foreground) startPresencePoll()
        } else {
            presencePoll?.cancel(); presencePoll = null
            stopTyping()
        }
    }

    private fun startPresencePoll() {
        presencePoll?.cancel()
        presencePoll = scope.launch {
            while (isActive && _chatVisible.value && foreground) {
                runCatching {
                    val chats = authed { it.chats() }
                    (chats.firstOrNull { it.id == currentChatId() } ?: chats.firstOrNull())?.let { applyChatMeta(it) }
                }
                delay(PRESENCE_POLL_MS)
            }
        }
    }

    /** Сообщения собеседника видны на экране до [entry] включительно — отметка «прочитано» и кадр read. */
    fun markReadUpTo(entry: ChatEntry) {
        if (!enabled || !foreground || entry.pending || entry.message.fromUser) return
        val time = entry.timeMs
        if (time <= _lastReadMs.value) return
        _lastReadMs.value = time
        credentials.lastReadMs = time
        val chatId = entry.message.chatId.ifEmpty { currentChatId() ?: return }
        if (socket?.send(ClientFrames.read(chatId, entry.message.id)) != true) {
            val messageId = entry.message.id
            scope.launch { runCatching { authed { it.read(chatId, messageId) } } }
        }
        notifications.markChatRead(chatId)
        CloudNotifier.cancelAdmin(app)
    }

    /** Ввод в поле чата: кадры typing (не чаще раза в 3 с) и typing=false после паузы. */
    fun onComposerInput(text: String) {
        val chatId = currentChatId() ?: return
        if (text.isEmpty()) { stopTyping(); return }
        if (typing.onInput(System.currentTimeMillis())) socket?.send(ClientFrames.typing(chatId, true))
        if (typingTicker?.isActive != true) {
            typingTicker = scope.launch {
                while (isActive && typing.isTyping) {
                    delay(1_000)
                    if (typing.onTick(System.currentTimeMillis())) socket?.send(ClientFrames.typing(chatId, false))
                }
            }
        }
    }

    fun stopTyping() {
        val chatId = currentChatId() ?: return
        if (typing.stop()) socket?.send(ClientFrames.typing(chatId, false))
        typingTicker?.cancel(); typingTicker = null
    }

    /** Отправка: сообщение сразу в ленте, файлы грузятся, затем POST с clientId (повтор безопасен). */
    fun send(text: String, attachments: List<LocalAttachment>, replyTo: String?) {
        if (!enabled) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        stopTyping()
        val clientId = UUID.randomUUID().toString()
        val entry = ChatMerge.optimistic(currentChatId().orEmpty(), clientId, trimmed, replyTo, attachments, Instant.now())
        _entries.value = ChatMerge.normalize(_entries.value + entry)
        scope.launch { deliver(clientId) }
    }

    fun retry(clientId: String) {
        _entries.value = ChatMerge.setDelivery(_entries.value, clientId, Delivery.SENDING)
        scope.launch { deliver(clientId) }
    }

    private suspend fun deliver(clientId: String) {
        val entry = _entries.value.firstOrNull { it.message.clientId == clientId && it.pending } ?: return
        try {
            if (!ensureRegistered(requireOnboarding = false)) throw CloudHttpException(401, "no_token", "")
            if (currentChatId() == null) refreshChat()
            val chatId = currentChatId() ?: throw CloudHttpException(404, "no_chat", "")
            val total = entry.local.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            val refs = entry.local.map { local ->
                uploadedRefs[local.path] ?: authed { api ->
                    val base = done
                    api.upload(File(local.path), local) { sent, _ ->
                        val value = ((base + sent).toFloat() / total).coerceIn(0f, 1f)
                        scope.launch { _uploads.value = _uploads.value + (clientId to value) }
                    }
                }.let { ref ->
                    // Сервер мог не знать длительность/размеры — дополняем тем, что измерили на телефоне.
                    ref.copy(
                        kind = if (local.kind == "voice") "voice" else ref.kind.ifEmpty { local.kind },
                        durationMs = ref.durationMs ?: local.durationMs,
                        width = ref.width ?: local.width, height = ref.height ?: local.height,
                    ).also { uploadedRefs[local.path] = it }
                }.also { done += local.size }
            }
            val message = authed { it.send(chatId, SendMessageRequest(clientId, entry.message.text, refs, entry.message.replyTo)) }
            _entries.value = ChatMerge.upsert(_entries.value, message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _entries.value = ChatMerge.setDelivery(_entries.value, clientId, Delivery.FAILED)
        } finally {
            _uploads.value = _uploads.value - clientId
        }
    }

    fun react(entry: ChatEntry, emoji: String) {
        val chatId = currentChatId() ?: return
        if (entry.pending) return
        val choice = ChatMerge.toggledReaction(entry.message, emoji)
        val before = _entries.value
        _entries.value = ChatMerge.update(before, ChatMerge.withReaction(entry.message, choice))
        scope.launch {
            runCatching { authed { it.react(chatId, entry.message.id, choice) } }
                .onSuccess { _entries.value = ChatMerge.update(_entries.value, it) }
                .onFailure { _entries.value = ChatMerge.update(_entries.value, entry.message) }
        }
    }

    fun setPinned(entry: ChatEntry, pinned: Boolean) {
        val chatId = currentChatId() ?: return
        if (entry.pending) return
        val previousPin = _chat.value?.pinnedMessageId
        _entries.value = ChatMerge.update(_entries.value, entry.message.copy(pinned = pinned))
        _chat.value = _chat.value?.copy(pinnedMessageId = if (pinned) entry.message.id else null)
        scope.launch {
            runCatching { authed { it.pin(chatId, entry.message.id, pinned) } }
                .onSuccess { updated -> _entries.value = ChatMerge.applyPin(_entries.value, updated) }
                .onFailure {
                    _entries.value = ChatMerge.update(_entries.value, entry.message)
                    _chat.value = _chat.value?.copy(pinnedMessageId = previousPin)
                }
        }
    }

    /** Изменить своё отправленное сообщение. */
    fun edit(entry: ChatEntry, text: String) {
        val chatId = currentChatId() ?: return
        val trimmed = text.trim()
        if (entry.pending || !entry.message.fromUser || trimmed.isEmpty() || trimmed == entry.message.text) return
        val original = entry.message
        _entries.value = ChatMerge.update(_entries.value, original.copy(text = trimmed, editedAt = Instant.now().toString()))
        scope.launch {
            runCatching { authed { it.edit(chatId, original.id, trimmed) } }
                .onSuccess { _entries.value = ChatMerge.update(_entries.value, it) }
                .onFailure { _entries.value = ChatMerge.update(_entries.value, original) }
        }
    }

    /** Удалить у себя (любое) или у всех (только своё). Неотправленное просто убирается. */
    fun delete(entry: ChatEntry, everyone: Boolean) {
        _entries.value = ChatMerge.remove(_entries.value, entry.message.id)
        if (entry.pending) return
        val chatId = currentChatId() ?: return
        scope.launch {
            if (runCatching { authed { it.delete(chatId, entry.message.id, everyone && entry.message.fromUser) } }.isFailure) {
                refreshChat()
            }
        }
    }

    /** Очистить историю у себя. */
    fun clearForMe() {
        val chatId = currentChatId() ?: return
        _entries.value = ChatMerge.clear()
        _chat.value = _chat.value?.copy(pinnedMessageId = null)
        scope.launch { runCatching { authed { it.clear(chatId) } } }
    }

    fun markAllNotificationsRead() {
        if (!enabled) return
        val ids = notifications.markAllRead()
        if (ids.isNotEmpty()) scope.launch { runCatching { authed { it.markNotificationsRead(ids) } } }
    }

    fun markNotificationRead(entry: NotificationEntry) {
        if (!enabled) return
        notifications.markRead(entry.id)
        val id = entry.serverId ?: return
        if (!entry.read) scope.launch { runCatching { authed { it.markNotificationsRead(listOf(id)) } } }
    }

    /** Заголовок авторизации для файлов облака (картинки, видео, голосовые). */
    fun authHeader(): String? = if (enabled) credentials.token?.let { "Bearer $it" } else null

    // ---- Push ----

    /** Данные push FCM: быстрая синхронизация; если сеть не ответила — уведомление из самих данных push. */
    fun onPush(type: String, title: String, body: String) {
        if (!enabled) return
        val synced = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                withContext(Dispatchers.Main) {
                    if (type == "notification") refreshNotifications() else refreshChat()
                }
                true
            } ?: false
        }
        if (synced || foreground) return
        if (!settings.notificationsEnabled.value) return
        if (type == "notification") CloudNotifier.broadcast(app, "push:" + System.currentTimeMillis(), title, body)
        else CloudNotifier.adminMessage(app, CloudMessage.SENDER_ADMIN, body.ifBlank { title }, unread.value + 1)
    }

    // ---- «Ответ готов» во вкладке уведомлений ----

    /** Вызывается из HonerNotifications.notifyAnswer: запись «Ответ готов» с переходом в чат. */
    fun recordAnswer(context: Context, chatId: String, chatTitle: String, text: String) {
        if (!CloudConfig.isConfigured) return
        start(context)
        if (!enabled) return
        val english = settings.isEnglish
        notifications.add(NotificationEntry(
            id = "answer:" + chatId + ":" + text.hashCode(),
            kind = NotificationEntry.KIND_ANSWER,
            title = (if (english) "Answer ready" else "Ответ готов") + if (chatTitle.isNotBlank()) " · " + chatTitle.take(60) else "",
            body = HonerNotifications.preview(text).take(300),
            createdAtMs = System.currentTimeMillis(),
            read = HonerNotifications.isAppInForeground(),
            localChatId = chatId,
        ))
    }

    // ---- Кэш ленты ----

    private fun loadCache() {
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    val file = cacheFile
                    if (!file.exists()) null
                    else CloudJson.decodeFromString(ListSerializer(ChatEntry.serializer()), file.readText())
                }.getOrNull()
            } ?: return@launch
            if (_entries.value.isEmpty()) {
                // Незавершённые отправки после перезапуска — «не отправлено», с кнопкой повтора.
                _entries.value = ChatMerge.normalize(cached.map { if (it.pending) it.copy(delivery = Delivery.FAILED) else it })
            }
        }
    }

    private suspend fun saveCache(list: List<ChatEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(CloudJson.encodeToString(ListSerializer(ChatEntry.serializer()), list.takeLast(CACHE_LIMIT)))
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    // ---- Константы ----
    const val SETTINGS_PREFS = "honer.settings"
    const val LICENSE_KEY = "honor.licenseAcceptedAt"
    private const val STATS_INTERVAL_MS = 5L * 60 * 1000
    private const val BACKGROUND_KEEP_MS = 2L * 60 * 1000
    private const val BLOCK_POLL_MS = 20_000L
    private const val PRESENCE_POLL_MS = 30_000L
    private const val CACHE_LIMIT = 300
}

/** Дата принятия лицензии в любом формате (ISO-строка, миллисекунды, секунды) → ISO-8601 UTC. */
object LicenseDate {
    fun toIso(value: Any?): String? {
        fun fromNumber(number: Double): String? {
            if (number <= 0) return null
            // Меньше 10^11 — секунды, иначе миллисекунды.
            val ms = if (number < 1e11) (number * 1000).toLong() else number.toLong()
            return Instant.ofEpochMilli(ms).toString()
        }
        return when (value) {
            null -> null
            is Number -> fromNumber(value.toDouble())
            is String -> {
                val text = value.trim()
                if (text.isEmpty()) null
                else runCatching { Instant.parse(text).toString() }.getOrNull()
                    ?: text.toDoubleOrNull()?.let { fromNumber(it) }
                    ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toString() }.getOrNull()
            }
            is Boolean -> null
            else -> null
        }
    }
}

/** Короткий текст сообщения для уведомлений и списка чатов. */
object MessagePreview {
    fun text(message: CloudMessage, english: Boolean): String {
        if (message.text.isNotBlank()) return message.text.trim().take(300)
        val first = message.attachments.firstOrNull() ?: return ""
        return when (first.kind) {
            "image" -> if (english) "📷 Photo" else "📷 Фото"
            "video" -> if (english) "🎬 Video" else "🎬 Видео"
            "voice" -> if (english) "🎤 Voice message" else "🎤 Голосовое сообщение"
            "audio" -> "🎵 " + first.name.ifBlank { if (english) "Audio" else "Аудио" }
            else -> "📎 " + first.name.ifBlank { if (english) "File" else "Файл" }
        }
    }
}
