package com.honerai.app.cloud

import com.honerai.app.core.HonerHttp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import java.util.concurrent.TimeUnit

/** Клиенты OkHttp облака — производные от общего клиента приложения (общий пул соединений). */
object CloudHttp {
    /** Обычные запросы API. */
    val api: OkHttpClient by lazy {
        HonerHttp.base.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Загрузка и скачивание файлов до 100 МБ. */
    val media: OkHttpClient by lazy {
        HonerHttp.base.newBuilder().readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES).build()
    }

    /** WebSocket: без таймаута чтения (сервер молчит между событиями), живость — ping/pong. */
    val socket: OkHttpClient by lazy {
        HonerHttp.base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS).build()
    }
}

/**
 * HTTP-клиент Honer Cloud (сторона устройства из API.md). Методы блокирующие —
 * вызываются из Dispatchers.IO. Ошибки сервера — [CloudHttpException].
 */
class CloudApi(
    private val base: String,
    private val token: () -> String?,
    private val http: OkHttpClient = CloudHttp.api,
    private val mediaHttp: OkHttpClient = CloudHttp.media,
) {
    private val jsonType: MediaType = "application/json; charset=utf-8".toMediaType()

    fun register(request: RegisterRequest): RegisterResponse =
        call("POST", "/v1/devices/register", CloudJson.encodeToString(RegisterRequest.serializer(), request), RegisterResponse.serializer(), auth = false)

    /** Есть ли на сервере ключ нейросети (GET /health → ai). Ошибка связи — null. */
    fun serverAiReady(): Boolean? = runCatching {
        http.newCall(Request.Builder().url(CloudUrls.api(base, "/health")).get().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            Regex("\"ai\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)?.toBoolean() ?: false
        }
    }.getOrNull()

    fun patchMe(fields: JsonObject) { exec("PATCH", "/v1/devices/me", fields.toString()) }

    fun stats(stats: StatsRequest) { exec("POST", "/v1/devices/me/stats", CloudJson.encodeToString(StatsRequest.serializer(), stats)) }

    fun chats(): List<CloudChat> = call("GET", "/v1/chats", null, ListSerializer(CloudChat.serializer()))

    fun messages(chatId: String, before: String?, limit: Int = PAGE): List<CloudMessage> {
        val query = buildString {
            append("?limit=").append(limit)
            if (before != null) append("&before=").append(enc(before))
        }
        return call("GET", "/v1/chats/${enc(chatId)}/messages$query", null, ListSerializer(CloudMessage.serializer()))
    }

    fun send(chatId: String, body: SendMessageRequest): CloudMessage =
        call("POST", "/v1/chats/${enc(chatId)}/messages", CloudJson.encodeToString(SendMessageRequest.serializer(), body), CloudMessage.serializer())

    /** Реакция (null — снять); сервер возвращает обновлённое сообщение. */
    fun react(chatId: String, messageId: String, emoji: String?): CloudMessage {
        val body = buildJsonObject { put("emoji", emoji?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }
        return call("POST", "/v1/chats/${enc(chatId)}/messages/${enc(messageId)}/reaction", body.toString(), CloudMessage.serializer())
    }

    fun pin(chatId: String, messageId: String, pinned: Boolean): CloudMessage =
        call("POST", "/v1/chats/${enc(chatId)}/messages/${enc(messageId)}/pin", buildJsonObject { put("pinned", pinned) }.toString(), CloudMessage.serializer())

    /** Изменить своё сообщение (расширение сервера: PATCH …/messages/:id). */
    fun edit(chatId: String, messageId: String, text: String): CloudMessage =
        call("PATCH", "/v1/chats/${enc(chatId)}/messages/${enc(messageId)}", buildJsonObject { put("text", text) }.toString(), CloudMessage.serializer())

    /** «Прочитано до» через REST — когда живого соединения нет (аналог кадра read). */
    fun read(chatId: String, messageId: String) {
        exec("POST", "/v1/chats/${enc(chatId)}/read", buildJsonObject { put("messageId", messageId) }.toString())
    }

    fun delete(chatId: String, messageId: String, everyone: Boolean) {
        exec("DELETE", "/v1/chats/${enc(chatId)}/messages/${enc(messageId)}?scope=" + (if (everyone) "everyone" else "me"), null)
    }

    fun clear(chatId: String) { exec("POST", "/v1/chats/${enc(chatId)}/clear?scope=me", "{}") }

    fun notifications(after: String?): List<CloudNotification> =
        call("GET", "/v1/notifications" + (after?.let { "?after=" + enc(it) } ?: ""), null, ListSerializer(CloudNotification.serializer()))

    fun markNotificationsRead(ids: List<String>) {
        if (ids.isEmpty()) return
        val body = buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }
        exec("POST", "/v1/notifications/read", body.toString())
    }

    /** Загрузка файла (multipart «file»), [progress] — отправлено/всего байт. */
    fun upload(file: File, local: LocalAttachment, progress: (Long, Long) -> Unit = { _, _ -> }): CloudAttachment {
        val part = ProgressBody(file, local.mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType(), progress)
        // Необязательные поля формы: сервер сохраняет вид (voice), имя, длительность и размеры.
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("kind", local.kind)
            addFormDataPart("name", local.name)
            local.durationMs?.let { addFormDataPart("durationMs", it.toString()) }
            local.width?.let { addFormDataPart("width", it.toString()) }
            local.height?.let { addFormDataPart("height", it.toString()) }
            addFormDataPart("file", local.name, part)
        }.build()
        val request = authorized(Request.Builder().url(CloudUrls.api(base, "/v1/media"))).post(body).build()
        return mediaHttp.newCall(request).execute().use { response ->
            val raw = checked(response)
            CloudJson.decodeFromString(CloudAttachment.serializer(), raw)
        }
    }

    /** Скачать файл облака в [target] (через временный файл: оборванная загрузка не остаётся «готовой»). */
    fun download(url: String, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val request = authorized(Request.Builder().url(CloudUrls.media(base, url))).get().build()
        mediaHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) checked(response)
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (!temp.renameTo(target)) { target.delete(); if (!temp.renameTo(target)) throw IOException("rename failed") }
        }
    }

    /** Заголовок авторизации для запросов к файлам облака (Coil, ExoPlayer). */
    fun authHeader(): String? = token()?.let { "Bearer $it" }

    private fun authorized(builder: Request.Builder): Request.Builder {
        val value = token() ?: throw CloudHttpException(401, "no_token", "")
        return builder.header("Authorization", "Bearer $value")
    }

    private fun exec(method: String, path: String, json: String?) {
        call(method, path, json, null as KSerializer<Unit>?)
    }

    private fun <T> call(method: String, path: String, json: String?, serializer: KSerializer<T>?, auth: Boolean = true): T {
        val builder = Request.Builder().url(CloudUrls.api(base, path)).header("Accept", "application/json")
        if (auth) authorized(builder)
        val body: RequestBody? = json?.toRequestBody(jsonType)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (body != null) builder.delete(body) else builder.delete()
            else -> builder.method(method, body ?: "{}".toRequestBody(jsonType))
        }
        http.newCall(builder.build()).execute().use { response ->
            val raw = checked(response)
            @Suppress("UNCHECKED_CAST")
            return if (serializer == null) Unit as T else CloudJson.decodeFromString(serializer, raw)
        }
    }

    /** Тело успешного ответа или [CloudHttpException] с кодом и текстом ошибки сервера. */
    private fun checked(response: Response): String {
        val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        if (!response.isSuccessful) {
            val (code, message) = CloudHttpException.parseBody(raw)
            throw CloudHttpException(response.code, code, message)
        }
        return raw
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Тело файла с отчётом о ходе отправки. */
    private class ProgressBody(private val file: File, private val type: MediaType, private val progress: (Long, Long) -> Unit) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            file.source().use { source ->
                while (true) {
                    val read = source.read(sink.buffer, 64 * 1024)
                    if (read < 0) break
                    sent += read
                    sink.flush()
                    progress(sent, total)
                }
            }
        }
    }

    companion object {
        const val PAGE = 50
    }
}
