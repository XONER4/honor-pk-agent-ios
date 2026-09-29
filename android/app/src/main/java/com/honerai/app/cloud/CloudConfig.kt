package com.honerai.app.cloud

import com.honerai.app.BuildConfig
import kotlin.random.Random

/**
 * Настройка облака: адрес сервера приходит при сборке (HONER_CLOUD_URL).
 * Пустой адрес — облака нет: ни сети, ни пунктов интерфейса, приложение работает как раньше.
 */
object CloudConfig {
    val baseUrl: String = CloudUrls.normalize(BuildConfig.HONER_CLOUD_URL)
    val isConfigured: Boolean get() = baseUrl.isNotEmpty()
}

/** Адреса сервера (чистые функции — проверяются тестами). */
object CloudUrls {
    fun normalize(raw: String): String {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    fun api(base: String, path: String): String = base + (if (path.startsWith("/")) path else "/$path")

    /** ws(s)://…/v1/ws?token=… */
    fun webSocket(base: String, token: String): String {
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/v1/ws?token=" + java.net.URLEncoder.encode(token, "UTF-8")
    }

    /** Адрес файла: «/v1/media/<id>» → полный адрес на нашем сервере; полный адрес — как есть. */
    fun media(base: String, url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.isEmpty() -> ""
        else -> api(base, url)
    }

    /** Токен устройства прикладывается только к запросам на наш сервер. */
    fun isOwnUrl(base: String, url: String): Boolean = base.isNotEmpty() && (url == base || url.startsWith("$base/"))
}

/** Куда идёт запрос к нейросети. */
sealed class AiRoute {
    abstract val url: String
    abstract val bearer: String

    /** Через сервер Honer Cloud: ключ DeepSeek остаётся на сервере. */
    data class Cloud(override val url: String, override val bearer: String) : AiRoute()
    /** Напрямую в DeepSeek со встроенным ключом — только когда облако не настроено. */
    data class Direct(override val url: String, override val bearer: String) : AiRoute()

    companion object {
        /**
         * Выбор маршрута. [cloudBase] пуст — напрямую. Облако настроено — только через него
         * (без токена устройства — null: сначала нужна регистрация).
         */
        fun select(cloudBase: String, deviceToken: String?, directBase: String, apiKey: String): AiRoute? {
            if (cloudBase.isEmpty()) return Direct(directBase.trimEnd('/') + "/chat/completions", apiKey)
            val token = deviceToken?.takeIf { it.isNotBlank() } ?: return null
            return Cloud(CloudUrls.api(cloudBase, "/v1/ai/chat/completions"), token)
        }
    }
}

/**
 * Пауза перед повторным подключением: 1, 2, 4, 8, 16 с, дальше 30 с.
 * Разброс ±20 % — чтобы тысячи телефонов после сбоя сервера не стучались одновременно.
 */
object Backoff {
    private const val BASE_MS = 1_000L
    const val MAX_MS = 30_000L

    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return BASE_MS
        val shift = minOf(attempt, 5)
        return minOf(BASE_MS shl shift, MAX_MS)
    }

    fun withJitter(delay: Long, random: Random = Random.Default): Long {
        val spread = (delay * 0.2).toLong()
        if (spread <= 0) return delay
        return delay - spread + random.nextLong(spread * 2 + 1)
    }
}

/**
 * Кадры «печатает»: true — не чаще раза в [intervalMs], false — один раз после паузы в наборе
 * или при отправке. Время передаётся снаружи (тестируется без часов).
 */
class TypingThrottle(private val intervalMs: Long = 3_000, private val idleMs: Long = 4_000) {
    private var lastSentTrue = Long.MIN_VALUE / 2
    private var lastInput = Long.MIN_VALUE / 2
    var isTyping = false
        private set

    /** Пользователь ввёл символ. true — нужно отправить кадр typing=true. */
    fun onInput(now: Long): Boolean {
        lastInput = now
        if (!isTyping || now - lastSentTrue >= intervalMs) {
            isTyping = true
            lastSentTrue = now
            return true
        }
        return false
    }

    /** Проверка паузы: true — нужно отправить typing=false. */
    fun onTick(now: Long): Boolean {
        if (isTyping && now - lastInput >= idleMs) {
            isTyping = false
            return true
        }
        return false
    }

    /** Отправка сообщения или очистка поля: true — нужно отправить typing=false. */
    fun stop(): Boolean {
        if (!isTyping) return false
        isTyping = false
        return true
    }
}
