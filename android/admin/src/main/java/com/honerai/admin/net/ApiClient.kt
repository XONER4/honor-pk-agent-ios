package com.honerai.admin.net

import com.honerai.admin.core.SessionStore
import com.honerai.admin.data.AdminAccount
import com.honerai.admin.data.AdminAction
import com.honerai.admin.data.AdminJson
import com.honerai.admin.data.AdminLogin
import com.honerai.admin.data.AdminNote
import com.honerai.admin.data.ActivityItem
import com.honerai.admin.data.SupportStats
import com.honerai.admin.data.TranslateResult
import com.honerai.admin.data.ProfileView
import com.honerai.admin.data.AiSettings
import com.honerai.admin.data.AiWindow
import com.honerai.admin.data.ClientReport
import com.honerai.admin.data.DeviceEvent
import com.honerai.admin.data.Metrics
import com.honerai.admin.data.Overrides
import com.honerai.admin.data.OverridesResponse
import com.honerai.admin.data.SetupStatus
import com.honerai.admin.data.ApiError
import com.honerai.admin.data.AttachmentRef
import com.honerai.admin.data.BroadcastResult
import com.honerai.admin.data.Chat
import com.honerai.admin.data.DeviceDetail
import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.LoginResponse
import com.honerai.admin.data.Message
import com.honerai.admin.data.Overview
import com.honerai.admin.data.SendMessageBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ошибка сервера: HTTP-статус + {"error","message"}. */
class ApiException(val status: Int, val code: String, val serverMessage: String) :
    IOException("HTTP $status $code $serverMessage")

/** Нет адреса сервера — его вводят на экране входа. */
class NoServerException : IOException("server url is not configured")

/** Понятный текст ошибки для экрана (русский или английский). */
fun friendlyError(error: Throwable, english: Boolean): String = when (error) {
    is NoServerException -> if (english) "Server address is not set." else "Не указан адрес сервера."
    is ApiException -> when {
        error.status == 401 -> if (english) "Session expired. Please sign in again." else "Сессия истекла, войдите снова."
        error.serverMessage.isNotBlank() && !english -> error.serverMessage
        error.status == 403 -> if (english) "Access denied." else "Нет доступа."
        error.status == 404 -> if (english) "Not found on the server." else "Не найдено на сервере."
        error.status == 413 -> if (english) "The file is too large (max 100 MB)." else "Файл слишком большой (до 100 МБ)."
        error.status == 429 -> if (english) "Too many requests, try again later." else "Слишком много запросов, попробуйте позже."
        error.status >= 500 -> if (english) "The server is temporarily unavailable. Try again." else "Сервер временно недоступен. Попробуйте ещё раз."
        error.serverMessage.isNotBlank() -> error.serverMessage
        else -> if (english) "Request failed (${error.status})." else "Ошибка запроса (${error.status})."
    }
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
    is javax.net.ssl.SSLException ->
        if (english) "No connection to the server. Check the internet and try again."
        else "Нет соединения с сервером. Проверьте интернет и попробуйте снова."
    is IOException -> if (english) "Network error. Try again." else "Ошибка сети. Попробуйте снова."
    else -> if (english) "Something went wrong. Try again." else "Что-то пошло не так. Попробуйте снова."
}

/**
 * REST-клиент админки (API.md → Admin endpoints). Все вызовы suspend и отменяемые;
 * 401 → выход из аккаунта с понятной причиной.
 */
class ApiClient(
    private val session: SessionStore,
    val http: OkHttpClient,
    private val onUnauthorized: () -> Unit,
) {
    private val jsonType: MediaType = "application/json; charset=utf-8".toMediaType()

    fun baseUrl(): String = session.serverUrl.value

    /** Полный адрес файла: «/v1/media/…» → «https://…/v1/media/…». */
    fun absolute(url: String): String = if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl() + url

    fun authHeader(): String? = session.session.value?.token?.let { "Bearer $it" }

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val base = baseUrl().takeIf { it.isNotEmpty() } ?: throw NoServerException()
        val builder = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw NoServerException()
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun request(url: HttpUrl, auth: Boolean = true): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (auth) authHeader()?.let { builder.header("Authorization", it) }
        return builder
    }

    private suspend fun execute(request: Request, client: OkHttpClient = http): String {
        val response = client.newCall(request).await()
        return response.use { res ->
            val body = withContext(Dispatchers.IO) { res.body?.string().orEmpty() }
            if (!res.isSuccessful) {
                val err = runCatching { AdminJson.decodeFromString(ApiError.serializer(), body) }.getOrNull()
                val ex = ApiException(res.code, err?.error.orEmpty(), err?.message.orEmpty())
                if (res.code == 401 && request.header("Authorization") != null) onUnauthorized()
                throw ex
            }
            body
        }
    }

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, query: Map<String, String?> = emptyMap()): T {
        val text = execute(request(url(path, query)).get().build())
        return withContext(Dispatchers.Default) { AdminJson.decodeFromString(strategy, text) }
    }

    private suspend fun send(method: String, path: String, body: JsonElement?, query: Map<String, String?> = emptyMap()): String {
        val requestBody = (body ?: buildJsonObject { }).toString().toRequestBody(jsonType)
        return execute(request(url(path, query)).method(method, requestBody).build())
    }

    private fun <T> decodeOrNull(strategy: DeserializationStrategy<T>, text: String): T? =
        if (text.isBlank()) null else runCatching { AdminJson.decodeFromString(strategy, text) }.getOrNull()

    // --- Вход ---------------------------------------------------------------------------------------------

    suspend fun loginWithGoogle(idToken: String): LoginResponse =
        login(buildJsonObject { put("googleIdToken", idToken) })

    suspend fun loginWithKey(key: String): LoginResponse =
        login(buildJsonObject { put("adminKey", key) })

    /** Основной вход: логин и пароль аккаунта администратора. */
    suspend fun loginWithPassword(login: String, password: String): LoginResponse =
        login(buildJsonObject { put("login", login); put("password", password) })

    /** Есть ли уже аккаунт администратора (иначе — экран «Создать администратора»). */
    suspend fun setupStatus(): SetupStatus {
        val text = execute(request(url("/v1/admin/setup-status"), auth = false).get().build())
        return AdminJson.decodeFromString(SetupStatus.serializer(), text)
    }

    /** Создание первого аккаунта; текущий ключ администратора подтверждает владельца сервера. */
    suspend fun setupAccount(login: String, password: String, adminKey: String): LoginResponse {
        val body = buildJsonObject { put("login", login); put("password", password) }.toString().toRequestBody(jsonType)
        val builder = request(url("/v1/admin/setup"), auth = false).post(body)
        if (adminKey.isNotBlank()) builder.header("x-admin-key", adminKey.trim())
        return AdminJson.decodeFromString(LoginResponse.serializer(), execute(builder.build()))
    }

    private suspend fun login(body: JsonElement): LoginResponse {
        val text = execute(request(url("/v1/admin/login"), auth = false).post(body.toString().toRequestBody(jsonType)).build())
        return AdminJson.decodeFromString(LoginResponse.serializer(), text)
    }

    // --- Обзор и пользователи ------------------------------------------------------------------------------

    suspend fun overview(): Overview = get("/v1/admin/overview", Overview.serializer())

    suspend fun devices(query: String, status: String = "all"): List<DeviceSummary> =
        get("/v1/admin/devices", ListSerializer(DeviceSummary.serializer()), mapOf("query" to query, "status" to status))

    suspend fun device(deviceId: String): DeviceDetail = get("/v1/admin/devices/$deviceId", DeviceDetail.serializer())

    /** Сервер возвращает обновлённую карточку устройства (NOTES.md). [until] — ISO конца блокировки, null — навсегда. */
    suspend fun setBlocked(deviceId: String, blocked: Boolean, reason: String?, until: String? = null): DeviceDetail? {
        val text = send("POST", "/v1/admin/devices/$deviceId/block", buildJsonObject {
            put("blocked", blocked)
            if (!reason.isNullOrBlank()) put("reason", reason.trim())
            if (blocked && until != null) put("until", until)
        })
        return decodeOrNull(DeviceDetail.serializer(), text)
    }

    // --- Метрики, ИИ, отчёты, история, заметки, журнал, ограничения ------------------------------------------

    suspend fun metrics(): Metrics = get("/v1/admin/metrics", Metrics.serializer())

    suspend fun aiSettings(): AiSettings = get("/v1/admin/ai", AiSettings.serializer())

    /** Общий выключатель ИИ и/или расписание (null — не менять). */
    suspend fun updateAiSettings(enabled: Boolean? = null, schedule: List<AiWindow>? = null, timezone: String? = null): AiSettings {
        val text = send("POST", "/v1/admin/ai", buildJsonObject {
            if (enabled != null) put("enabled", enabled)
            if (schedule != null) put("schedule", AdminJson.encodeToJsonElement(ListSerializer(AiWindow.serializer()), schedule))
            if (timezone != null) put("timezone", timezone)
        })
        return AdminJson.decodeFromString(AiSettings.serializer(), text)
    }

    suspend fun reports(deviceId: String? = null, kind: String? = null, limit: Int = 50, before: String? = null): List<ClientReport> =
        get("/v1/admin/reports", ListSerializer(ClientReport.serializer()),
            mapOf("deviceId" to deviceId, "kind" to kind, "limit" to limit.toString(), "before" to before))

    suspend fun deviceEvents(deviceId: String): List<DeviceEvent> =
        get("/v1/admin/devices/$deviceId/events", ListSerializer(DeviceEvent.serializer()))

    suspend fun notes(deviceId: String): List<AdminNote> =
        get("/v1/admin/devices/$deviceId/notes", ListSerializer(AdminNote.serializer()))

    suspend fun addNote(deviceId: String, text: String): AdminNote =
        AdminJson.decodeFromString(AdminNote.serializer(),
            send("POST", "/v1/admin/devices/$deviceId/notes", buildJsonObject { put("text", text) }))

    suspend fun editNote(noteId: String, text: String): AdminNote =
        AdminJson.decodeFromString(AdminNote.serializer(),
            send("PATCH", "/v1/admin/notes/$noteId", buildJsonObject { put("text", text) }))

    suspend fun deleteNote(noteId: String) {
        execute(request(url("/v1/admin/notes/$noteId")).delete().build())
    }

    suspend fun actions(deviceId: String? = null, limit: Int = 50): List<AdminAction> =
        get("/v1/admin/actions", ListSerializer(AdminAction.serializer()), mapOf("deviceId" to deviceId, "limit" to limit.toString()))

    /** Изменение ограничений: в [patch] только меняемые ключи; JsonNull снимает ограничение. */
    suspend fun setOverrides(deviceId: String, patch: JsonObject): Overrides {
        val text = send("PATCH", "/v1/admin/devices/$deviceId/overrides", patch)
        return AdminJson.decodeFromString(OverridesResponse.serializer(), text).overrides
    }

    // --- Админка v2: аккаунт, роли, входы, просмотры, лента, профиль ----------------------------------------

    /** Текущий аккаунт администратора (роль, есть ли пароль). */
    suspend fun account(): AdminAccount = get("/v1/admin/account", AdminAccount.serializer())

    /** Смена собственного пароля. [current] нужен, если пароль уже задан. */
    suspend fun changePassword(current: String?, newPassword: String) {
        send("POST", "/v1/admin/account/password", buildJsonObject {
            if (!current.isNullOrEmpty()) put("currentPassword", current)
            put("newPassword", newPassword)
        })
    }

    /** Журнал входов/попыток входа в админку. */
    suspend fun logins(limit: Int = 50, before: String? = null, success: Boolean? = null): List<AdminLogin> =
        get("/v1/admin/logins", ListSerializer(AdminLogin.serializer()),
            mapOf("limit" to limit.toString(), "before" to before, "success" to success?.toString()))

    /** Список администраторов и их ролей. */
    suspend fun admins(): List<AdminAccount> = get("/v1/admin/admins", ListSerializer(AdminAccount.serializer()))

    /** Назначить роль администратору (только разработчик). */
    suspend fun setAdminRole(adminId: String, role: String): AdminAccount =
        AdminJson.decodeFromString(AdminAccount.serializer(),
            send("PATCH", "/v1/admin/admins/$adminId/role", buildJsonObject { put("role", role) }))

    /** Общая лента действий (админы + пользователи). */
    suspend fun activity(limit: Int = 80): List<ActivityItem> =
        get("/v1/admin/activity", ListSerializer(ActivityItem.serializer()), mapOf("limit" to limit.toString()))

    /** Статус поддержки: в сети и среднее время ответа. */
    suspend fun supportStats(): SupportStats = get("/v1/admin/support-stats", SupportStats.serializer())

    /** Перевод текста пользователя на русский (для поддержки). */
    suspend fun translate(text: String): TranslateResult =
        AdminJson.decodeFromString(TranslateResult.serializer(),
            send("POST", "/v1/admin/translate", buildJsonObject { put("text", text) }))

    /** Кто смотрел карточку этого пользователя. */
    suspend fun profileViews(deviceId: String, limit: Int = 50): List<ProfileView> =
        get("/v1/admin/devices/$deviceId/profile-views", ListSerializer(ProfileView.serializer()),
            mapOf("limit" to limit.toString()))

    /** Очистить историю установок/обновлений устройства. */
    suspend fun clearEvents(deviceId: String) {
        execute(request(url("/v1/admin/devices/$deviceId/events")).delete().build())
    }

    /** Изменить имя пользователя (админом). */
    suspend fun editProfile(deviceId: String, displayName: String?): DeviceDetail? {
        val text = send("PATCH", "/v1/admin/devices/$deviceId/profile", buildJsonObject {
            put("displayName", displayName?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        return decodeOrNull(DeviceDetail.serializer(), text)
    }

    /** Возвращает, сколько устройств получили уведомление ({"ok":true,"count":n}), если сервер сообщил. */
    suspend fun sendNotification(deviceId: String?, title: String, body: String): Int? {
        val text = send("POST", "/v1/admin/notifications", buildJsonObject {
            put("deviceId", deviceId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("title", title)
            put("body", body)
        })
        return decodeOrNull(BroadcastResult.serializer(), text)?.count
    }

    // --- Чаты -----------------------------------------------------------------------------------------------

    suspend fun chats(): List<Chat> = get("/v1/admin/chats", ListSerializer(Chat.serializer()))

    suspend fun messages(chatId: String, before: String?, limit: Int = PAGE): List<Message> =
        get("/v1/admin/chats/$chatId/messages", ListSerializer(Message.serializer()),
            mapOf("before" to before, "limit" to limit.toString()))

    suspend fun sendMessage(chatId: String, body: SendMessageBody): Message {
        val text = send("POST", "/v1/admin/chats/$chatId/messages", AdminJson.encodeToJsonElement(SendMessageBody.serializer(), body))
        return AdminJson.decodeFromString(Message.serializer(), text)
    }

    suspend fun react(chatId: String, messageId: String, emoji: String?): Message? {
        val text = send("POST", "/v1/admin/chats/$chatId/messages/$messageId/reaction", buildJsonObject {
            put("emoji", emoji?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        return decodeOrNull(Message.serializer(), text)
    }

    suspend fun pin(chatId: String, messageId: String, pinned: Boolean): Message? {
        val text = send("POST", "/v1/admin/chats/$chatId/messages/$messageId/pin", buildJsonObject { put("pinned", pinned) })
        return decodeOrNull(Message.serializer(), text)
    }

    /** Расширение сервера: правка своего сообщения (PATCH …/messages/:id {text}). */
    suspend fun editMessage(chatId: String, messageId: String, text: String): Message? {
        val body = buildJsonObject { put("text", text) }.toString().toRequestBody(jsonType)
        val response = execute(request(url("/v1/admin/chats/$chatId/messages/$messageId")).patch(body).build())
        return decodeOrNull(Message.serializer(), response)
    }

    /** REST-аналог кадра read — когда WebSocket сейчас не подключён. */
    suspend fun markRead(chatId: String, messageId: String) {
        send("POST", "/v1/admin/chats/$chatId/read", buildJsonObject { put("messageId", messageId) })
    }

    suspend fun deleteMessage(chatId: String, messageId: String, everyone: Boolean) {
        execute(request(url("/v1/admin/chats/$chatId/messages/$messageId", mapOf("scope" to if (everyone) "everyone" else "me")))
            .delete().build())
    }

    suspend fun clearChat(chatId: String, everyone: Boolean) {
        send("POST", "/v1/admin/chats/$chatId/clear", null, mapOf("scope" to if (everyone) "everyone" else "me"))
    }

    suspend fun setAi(chatId: String, enabled: Boolean): Chat? {
        val text = send("POST", "/v1/admin/chats/$chatId/ai", buildJsonObject { put("enabled", enabled) })
        return decodeOrNull(Chat.serializer(), text)
    }

    // --- Файлы ----------------------------------------------------------------------------------------------

    /** POST /v1/media (multipart «file»). [open] открывает поток заново при повторе запроса. */
    suspend fun upload(
        name: String,
        mime: String,
        size: Long,
        extra: Map<String, String> = emptyMap(),
        open: () -> InputStream,
        onProgress: (Float) -> Unit = {},
    ): AttachmentRef {
        val file = StreamBody(mime.toMediaTypeOrNull(), size, open, onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            extra.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", name.ifBlank { "file" }, file)
        }.build()
        val text = execute(request(url("/v1/media")).post(body).build(), uploadClient)
        return AdminJson.decodeFromString(AttachmentRef.serializer(), text)
    }

    /** Скачивание файла вложения в [target] (для «Открыть»). */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) {
        val req = Request.Builder().url(absolute(url)).apply { authHeader()?.let { header("Authorization", it) } }.build()
        val response = uploadClient.newCall(req).await()
        response.use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "", "")
            val body = res.body ?: throw IOException("empty body")
            withContext(Dispatchers.IO) {
                val total = body.contentLength()
                val tmp = File(target.path + ".part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            }
        }
    }

    private val uploadClient: OkHttpClient by lazy {
        http.newBuilder()
            .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .build()
    }

    companion object {
        const val PAGE = 50
    }
}

/** Тело запроса из потока (content:// или файл) с прогрессом. */
private class StreamBody(
    private val type: MediaType?,
    private val size: Long,
    private val open: () -> InputStream,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = if (size > 0) size else -1
    override fun writeTo(sink: BufferedSink) {
        open().source().use { source ->
            var done = 0L
            while (true) {
                val read = source.read(sink.buffer, 64 * 1024)
                if (read < 0) break
                done += read
                sink.flush()
                if (size > 0) onProgress((done.toFloat() / size).coerceIn(0f, 1f))
            }
        }
    }
}

/** Отменяемый вызов OkHttp для корутин. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
