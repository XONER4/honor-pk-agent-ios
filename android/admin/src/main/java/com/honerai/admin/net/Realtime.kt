package com.honerai.admin.net

import android.util.Log
import com.honerai.admin.core.SessionStore
import com.honerai.admin.data.ClientFrames
import com.honerai.admin.data.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

enum class ConnectionState { OFFLINE, CONNECTING, CONNECTED }

/**
 * Одно WebSocket-соединение на сессию приложения: переподключение с экспоненциальной паузой (1…30 с),
 * ping каждые 25 с, сторож тишины 70 с. Кадры сервера — в [frames]; после каждого (пере)подключения
 * растёт [epoch], чтобы экраны догрузили пропущенное.
 */
class Realtime(
    private val http: OkHttpClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit,
) {
    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 512)
    val frames: SharedFlow<ServerFrame> get() = _frames

    private val _state = MutableStateFlow(ConnectionState.OFFLINE)
    val state: StateFlow<ConnectionState> get() = _state

    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> get() = _epoch

    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var foreground = true
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val isRunning: Boolean get() = loop?.isActive == true

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "bye")
        socket = null
        _state.value = ConnectionState.OFFLINE
    }

    /** Сеть появилась или приложение вернулось — не ждать окончания паузы. */
    fun kick() { wake.trySend(Unit) }

    fun send(text: String): Boolean = socket?.send(text) ?: false

    fun setForeground(value: Boolean) {
        foreground = value
        send(ClientFrames.presence(value))
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            val token = session.session.value?.token ?: break
            val base = session.serverUrl.value
            val url = (base + "/v1/ws").toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("token", token)?.build()
            if (url == null) break
            _state.value = ConnectionState.CONNECTING
            val closed = CompletableDeferred<Int>()
            val opened = CompletableDeferred<Unit>()
            val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastFrameAt = System.currentTimeMillis()
                    webSocket.send(ClientFrames.presence(foreground))
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastFrameAt = System.currentTimeMillis()
                    ServerFrame.parse(text)?.let { _frames.tryEmit(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed.complete(0)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(0) }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "socket failure: ${t.message} (${response?.code})")
                    closed.complete(response?.code ?: -1)
                }
            })
            socket = ws
            // Ждём открытия или ошибки — что раньше.
            val openedOk = withTimeoutOrNull(20_000) {
                select { opened.onAwait { true }; closed.onAwait { false } }
            } ?: false
            if (openedOk) {
                attempt = 0
                _state.value = ConnectionState.CONNECTED
                _epoch.value = _epoch.value + 1
                val pinger = scope.launch {
                    while (isActive) {
                        delay(PING_MS)
                        ws.send(ClientFrames.ping())
                        if (System.currentTimeMillis() - lastFrameAt > SILENCE_MS) {
                            Log.w(TAG, "socket silent, reconnecting")
                            ws.cancel()
                            closed.complete(-1)
                        }
                    }
                }
                val code = closed.await()
                pinger.cancel()
                if (code == 401) { socket = null; _state.value = ConnectionState.OFFLINE; onUnauthorized(); break }
            } else {
                ws.cancel()
                val code = if (closed.isCompleted) closed.await() else -1
                if (code == 401) { socket = null; _state.value = ConnectionState.OFFLINE; onUnauthorized(); break }
            }
            socket = null
            _state.value = ConnectionState.CONNECTING
            attempt++
            val backoff = minOf(30_000L, 1_000L shl minOf(attempt - 1, 5)) + Random.nextLong(0, 700)
            withTimeoutOrNull(backoff) { wake.receive() }
        }
        _state.value = ConnectionState.OFFLINE
    }

    companion object {
        private const val TAG = "Realtime"
        private const val PING_MS = 25_000L
        private const val SILENCE_MS = 70_000L
    }
}
