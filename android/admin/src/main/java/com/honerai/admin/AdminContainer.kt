package com.honerai.admin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import com.honerai.admin.core.AdminNotifications
import com.honerai.admin.core.AdminRepository
import com.honerai.admin.core.AdminSettings
import com.honerai.admin.core.ChatController
import com.honerai.admin.core.SessionStore
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.Sender
import com.honerai.admin.data.ServerFrame
import com.honerai.admin.data.title
import com.honerai.admin.net.ApiClient
import com.honerai.admin.net.Realtime
import com.honerai.admin.ui.chat.AudioPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Общие объекты админки на всё время жизни процесса. */
class AdminContainer private constructor(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = AdminSettings(app)
    val session = SessionStore(app)

    // Мои права (из /account). У разработчика в UI проверяем через session.isDeveloper. План п.3.
    private val _myPermissions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val myPermissions: kotlinx.coroutines.flow.StateFlow<Map<String, Boolean>> = _myPermissions

    /** Один HTTP-клиент: API, картинки (Coil), скачивание. Токен добавляется к запросам на наш сервер. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Доверие к серверу-ретранслятору (RunPod, свой TLS-сертификат) + обычным сайтам.
        .sslSocketFactory(com.honerai.admin.net.AdminTrust.sslSocketFactory, com.honerai.admin.net.AdminTrust.trustManager)
        .hostnameVerifier(com.honerai.admin.net.AdminTrust.hostnameVerifier)
        .addInterceptor { chain ->
            val request = chain.request()
            val token = session.session.value?.token
            val host = session.serverUrl.value.toHttpUrlOrNull()?.host
            if (token != null && request.header("Authorization") == null && host != null && request.url.host == host) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
            } else chain.proceed(request)
        }
        .build()

    private val wsClient: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    val api = ApiClient(session, http) { onUnauthorized() }
    val realtime = Realtime(wsClient, session, scope) { onUnauthorized() }
    val repo = AdminRepository(api, scope, settings)

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .okHttpClient(http)
            .components {
                // Кадр видео — только для локальных черновиков (сетевые ролики не скачиваются ради обложки).
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    val audio: AudioPlayback by lazy { AudioPlayback(app, this) }

    private val chats = HashMap<String, ChatController>()
    @Volatile var inForeground = false
        private set
    private var stopJob: Job? = null

    init {
        // Узнаём актуальный адрес ретранслятора у точки обнаружения (релей публикует его сам).
        // Админка не ломается при смене адреса пода RunPod и при выключенном компьютере хозяина.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(DISCOVERY_URL).get().build()
                http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()?.let { body ->
                Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.startsWith("http") && it != session.serverUrl.value }
                    ?.let { session.setServerUrl(it) }
            }
        }
    }


    fun chat(chatId: String): ChatController = chats.getOrPut(chatId) {
        ChatController(chatId, app, api, realtime, repo, scope, settings)
    }

    private fun onUnauthorized() {
        scope.launch {
            if (session.session.value != null) {
                session.logout(settings.text("Сессия истекла, войдите снова.", "Session expired, please sign in again."))
            }
        }
    }

    fun onForeground() {
        inForeground = true
        stopJob?.cancel()
        if (session.session.value != null) {
            realtime.start()
            realtime.kick()
            realtime.setForeground(true)
        }
    }

    /** Свёрнуто: соединение держим 10 минут (уведомления о новых сообщениях), потом отпускаем. */
    fun onBackground() {
        inForeground = false
        realtime.setForeground(false)
        audio.pause()
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(10 * 60_000L)
            realtime.stop()
        }
    }

    private fun start() {
        AdminNotifications.createChannels(app)
        scope.launch {
            session.session.map { it?.token }.distinctUntilChanged().collect { token ->
                if (token != null) {
                    repo.refreshAll("")
                    // Подтягиваем актуальную роль и права (вдруг повысили до developer) — без перелогина.
                    runCatching {
                        val acc = api.account()
                        session.updateRole(acc.role)
                        _myPermissions.value = acc.permissions
                    }
                    if (inForeground) realtime.start()
                } else {
                    realtime.stop()
                    repo.clear()
                    chats.clear()
                    audio.stop()
                }
            }
        }
        scope.launch { realtime.frames.collect { dispatch(it) } }
        scope.launch {
            realtime.epoch.collect { epoch ->
                // Переподключились — догружаем пропущенное.
                if (epoch > 1) {
                    repo.refreshAll()
                    chats.values.forEach { if (it.state.value.loaded) it.load(force = true) }
                }
            }
        }
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { realtime.kick() }
            })
        }
    }

    private fun dispatch(frame: ServerFrame) {
        repo.onFrame(frame)
        val chatId = when (frame) {
            is ServerFrame.NewMessage -> frame.chatId
            is ServerFrame.MessageUpdated -> frame.chatId
            is ServerFrame.ChatCleared -> frame.chatId
            is ServerFrame.Typing -> frame.chatId
            is ServerFrame.Read -> frame.chatId
            else -> null
        }
        if (chatId != null) chats[chatId]?.onFrame(frame)
        if (frame is ServerFrame.NewMessage && frame.message.sender == Sender.USER && !inForeground) {
            val device = repo.deviceByChat(frame.chatId)
            val english = settings.english.value
            val m = frame.message
            val text = m.text.ifBlank {
                when (m.attachments.firstOrNull()?.kind) {
                    AttachmentKinds.IMAGE -> if (english) "Photo" else "Фото"
                    AttachmentKinds.VIDEO -> if (english) "Video" else "Видео"
                    AttachmentKinds.VOICE -> if (english) "Voice message" else "Голосовое сообщение"
                    AttachmentKinds.AUDIO -> if (english) "Audio" else "Аудио"
                    null -> if (english) "New message" else "Новое сообщение"
                    else -> if (english) "File" else "Файл"
                }
            }
            AdminNotifications.notifyMessage(app, frame.chatId, device?.deviceId,
                device?.title(english) ?: (if (english) "User" else "Пользователь"), text)
        }
    }

    companion object {
        const val DISCOVERY_URL = "https://honer-relay.vladislavponomarev16.workers.dev/relay-endpoint"
        @Volatile private var instance: AdminContainer? = null

        fun get(context: Context): AdminContainer = instance ?: synchronized(this) {
            instance ?: AdminContainer(context).also { instance = it; it.start() }
        }
    }
}
