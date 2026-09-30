package com.honerai.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Сеть приложения: один клиент OkHttp (общий пул соединений и диспетчер),
 * от которого производные клиенты берут только свои таймауты.
 */
object HonerHttp {
    const val MOBILE_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    /**
     * Сжимать ли тело запроса (gzip) к этому адресу. Ставится из настроек облака: сжимаем запросы к
     * серверу Honer, чтобы они пролезали под лимит оператора на размер загрузки к релею (Cloudflare
     * режет крупные загрузки). Релей распаковывает и отдаёт на сервер обычным телом.
     */
    @Volatile
    var gzipHostMatch: ((HttpUrl) -> Boolean)? = null

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(GzipRequestInterceptor)
            // Доверяем нашему серверу-ретранслятору (свой TLS-сертификат) + всем обычным сайтам.
            .sslSocketFactory(CloudTrust.sslSocketFactory, CloudTrust.trustManager)
            .hostnameVerifier(CloudTrust.hostnameVerifier)
            .build()
    }

    /**
     * Нейросеть: ждём следующий байт до 90 с (если поток замер — ошибка, а не вечное «Отвечаю…»),
     * а весь ответ целиком — до 5 минут (с запасом даже для длинных ответов и рассуждений; раньше было
     * 30 минут, из-за чего медленно «капающий» поток мог держать экран в ожидании очень долго).
     */
    val ai: OkHttpClient by lazy {
        base.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private val webClients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    /** Клиент для сайтов с общим лимитом времени на запрос. */
    fun web(timeoutSeconds: Long): OkHttpClient = webClients.getOrPut(timeoutSeconds) {
        base.newBuilder()
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 2, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Сжимает (gzip) JSON-тела запросов к серверу Honer. Оператор (МТС) режет крупные загрузки к релею,
 * а запрос к нейросети (системный промпт + история + инструменты) — крупный. Сжатый он в разы меньше и
 * пролезает; релей распаковывает. Медиа (фото/видео — уже сжаты) и запросы к другим сайтам не трогаем.
 */
object GzipRequestInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        val match = HonerHttp.gzipHostMatch
        val isJson = body?.contentType()?.subtype?.contains("json", ignoreCase = true) == true
        if (body == null || !isJson || request.header("Content-Encoding") != null || match == null || !match(request.url)) {
            return chain.proceed(request)
        }
        val gzipped = object : okhttp3.RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = -1L // длина после сжатия заранее неизвестна
            override fun writeTo(sink: okio.BufferedSink) {
                val gzip = okio.GzipSink(sink).buffer()
                body.writeTo(gzip)
                gzip.close()
            }
        }
        return chain.proceed(
            request.newBuilder().header("Content-Encoding", "gzip").method(request.method, gzipped).build(),
        )
    }
}

/** Запрос OkHttp как отменяемая suspend-функция: отмена корутины обрывает соединение. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/** Скачанный документ: байты, итоговый адрес (после переадресаций), код ответа и тип. */
class Fetched(val data: ByteArray, val url: String, val status: Int, val contentType: String) {
    val text: String get() = decodeText(data, contentType)
}

/** Загрузка не больше [maximumBytes] байт. Возвращает null при ошибке сети. */
suspend fun fetchBytes(
    url: String,
    maximumBytes: Int,
    timeoutSeconds: Long = 10,
    headers: Map<String, String> = emptyMap(),
    agent: String = HonerHttp.MOBILE_AGENT,
): Fetched? = withContext(Dispatchers.IO) {
    val request = runCatching {
        Request.Builder().url(url).header("User-Agent", agent)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.6")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
    }.getOrNull() ?: return@withContext null
    try {
        HonerHttp.web(timeoutSeconds).newCall(request).await().use { response ->
            val body = response.body ?: return@use null
            val input = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < maximumBytes) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size, maximumBytes - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            Fetched(out.toByteArray(), response.request.url.toString(), response.code, response.header("Content-Type").orEmpty())
        }
    } catch (e: IOException) {
        null
    }
}

/**
 * Текст страницы: кодировка из заголовка, иначе строгий UTF-8,
 * а если он не подходит — windows-1251 (частый случай у русских сайтов).
 */
fun decodeText(data: ByteArray, contentType: String = ""): String {
    val declared = Regex("(?i)charset=([\\w-]+)").find(contentType)?.groupValues?.get(1)
    if (declared != null) {
        runCatching { return String(data, Charset.forName(declared)) }
    }
    val strict = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        strict.decode(ByteBuffer.wrap(data)).toString()
    } catch (e: Exception) {
        // Обрезанный на середине символа хвост тоже вызывает ошибку — проверяем без него.
        val trimmed = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, 0, maxOf(0, data.size - 4))).toString()
        }.getOrNull()
        if (trimmed != null) String(data, Charsets.UTF_8)
        else runCatching { String(data, Charset.forName("windows-1251")) }.getOrElse { String(data, Charsets.UTF_8) }
    }
}

fun String.toWebUrl(): HttpUrl? = trim().toHttpUrlOrNull()

// ---- Удобное чтение JSON (аналог [String: Any] на iOS) ----

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray

/** Строковое значение, если это строка. */
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Целое число (JSON-число). */
val JsonElement?.int: Int? get() {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.longOrNull?.toInt() ?: p.doubleOrNull?.toInt()
}

val JsonElement?.dbl: Double? get() {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.doubleOrNull
}

val JsonElement?.bool: Boolean? get() {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.booleanOrNull
}

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Разбор JSON без исключений. */
fun parseJson(text: String): JsonElement? = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
