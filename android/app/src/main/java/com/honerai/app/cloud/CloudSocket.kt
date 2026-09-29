package com.honerai.app.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Живое соединение с сервером (WebSocket /v1/ws): переподключение с паузами [Backoff],
 * кадр ping каждые 25 с, сторож тишины. Кадры сервера уходят в [onFrame] на главном потоке.
 */
class CloudSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    /** Адрес с токеном; null — токена нет, подключаться не к чему. */
    private val url: () -> String?,
    /** Первый кадр после подключения (presence). */
    private val greeting: () -> String,
    private val onFrame: (CloudFrame) -> Unit,
    /** Сервер отказал: 401 (токен устарел) или 403 (устройство заблокировано). */
    private val onRejected: (status: Int, body: String) -> Unit,
) {
    enum class State { CLOSED, CONNECTING, OPEN }

    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var wanted = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var lastFrameAt = 0L
    /** Поколение соединения: события старого сокета после переподключения игнорируются. */
    private var generation = 0

    /** Держать соединение открытым (повторно — ничего не делает). */
    fun open() {
        wanted = true
        if (socket != null || reconnectJob?.isActive == true) return
        connect()
    }

    /** Закрыть и не переподключаться. */
    fun close() {
        wanted = false
        reconnectJob?.cancel(); reconnectJob = null
        pingJob?.cancel(); pingJob = null
        generation++
        socket?.close(1000, "bye")
        socket = null
        _state.value = State.CLOSED
    }

    /** Отправить кадр; false — соединения нет (кадр не нужен позже: typing/read/presence актуальны лишь сейчас). */
    fun send(text: String): Boolean = if (_state.value == State.OPEN) socket?.send(text) ?: false else false

    /** Сеть вернулась или приложение открыли — не ждать паузы. */
    fun reconnectNow() {
        if (!wanted) return
        if (_state.value == State.OPEN) return
        reconnectJob?.cancel(); reconnectJob = null
        attempt = 0
        if (socket == null) connect()
    }

    private fun connect() {
        val address = url() ?: run { _state.value = State.CLOSED; return }
        val request = runCatching { Request.Builder().url(address).build() }.getOrNull() ?: return
        val current = ++generation
        _state.value = State.CONNECTING
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post(current) {
                attempt = 0
                lastFrameAt = System.currentTimeMillis()
                _state.value = State.OPEN
                webSocket.send(greeting())
                startPing(current, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameAt = System.currentTimeMillis()
                val frame = CloudFrame.parse(text) ?: return
                post(current) { onFrame(frame) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (code == CLOSE_BLOCKED) post(current) { rejected(403, "{\"error\":\"blocked\",\"message\":\"\"}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(current) {
                if (code == CLOSE_BLOCKED) rejected(403, "{\"error\":\"blocked\",\"message\":\"\"}") else dropped()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code ?: 0
                val body = if (status == 401 || status == 403) runCatching { response?.body?.string().orEmpty() }.getOrDefault("") else ""
                post(current) { if (status == 401 || status == 403) rejected(status, body) else dropped() }
            }
        })
    }

    /** Сервер отказал (401 — токен, 403/4003 — блокировка): не переподключаемся сами. */
    private fun rejected(status: Int, body: String) {
        if (!wanted && _state.value == State.CLOSED) return
        wanted = false
        pingJob?.cancel(); pingJob = null
        reconnectJob?.cancel(); reconnectJob = null
        generation++
        socket = null
        _state.value = State.CLOSED
        onRejected(status, body)
    }

    private fun post(current: Int, block: () -> Unit) {
        scope.launch { if (current == generation) block() }
    }

    private fun startPing(current: Int, webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && current == generation) {
                delay(PING_MS)
                if (current != generation) break
                // Сервер молчит дольше двух пингов — соединение «зависло» (сменилась сеть): переподключаемся.
                if (System.currentTimeMillis() - lastFrameAt > PING_MS * 2 + 10_000) {
                    webSocket.cancel()
                    dropped()
                    break
                }
                webSocket.send(ClientFrames.ping())
            }
        }
    }

    private fun dropped() {
        pingJob?.cancel(); pingJob = null
        socket = null
        generation++
        _state.value = State.CLOSED
        if (!wanted) return
        val wait = Backoff.withJitter(Backoff.delayFor(attempt++))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            if (wanted && socket == null) connect()
        }
    }

    companion object {
        const val PING_MS = 25_000L
        /** Код закрытия сервера: устройство заблокировано. */
        const val CLOSE_BLOCKED = 4003
    }
}
