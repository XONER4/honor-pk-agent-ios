package com.honerai.app.cloud

import com.honerai.app.data.DeepSeekConfiguration
import okhttp3.Request

/** Ошибка облачного прокси нейросети — текст для пользователя (сервер присылает его по-русски). */
class CloudAiError(message: String) : Exception(message)

/**
 * Прокси нейросети: при настроенном облаке запросы DeepSeekClient идут на
 * `${HONER_CLOUD_URL}/v1/ai/chat/completions` с токеном устройства (ключ DeepSeek — только на сервере).
 * Тело и разбор SSE те же; поток байтов сервер пересылает без буферизации.
 */
object AiProxy {
    /** Облако запущено ([CloudManager.start]). В модульных тестах — false: запросы идут напрямую. */
    @Volatile var installed = false

    val active: Boolean get() = installed && CloudConfig.isConfigured

    /** Адрес и ключ запроса. Облако без токена — регистрация (в фоне, не дольше 20 с). */
    fun route(configuration: DeepSeekConfiguration): AiRoute {
        if (!active) return AiRoute.select("", null, configuration.baseURL, configuration.apiKey)!!
        val token = CloudManager.tokenBlocking()
        return AiRoute.select(CloudConfig.baseUrl, token, configuration.baseURL, configuration.apiKey)
            ?: throw CloudAiError(
                if (english()) "No connection to the Honer AI server. Check the internet and try again."
                else "Нет связи с сервером Honer AI. Проверьте интернет и повторите запрос.",
            )
    }

    /**
     * Ответ с ошибкой от облака: 403 blocked — экран блокировки, 401 — новая регистрация.
     * Возвращает ошибку для пользователя или null, если запрос шёл напрямую в DeepSeek.
     */
    fun failure(request: Request, status: Int, raw: String): Exception? {
        if (!active || !CloudUrls.isOwnUrl(CloudConfig.baseUrl, request.url.toString())) return null
        val (code, message) = CloudHttpException.parseBody(raw)
        val english = english()
        return when {
            status == 403 && (code == "blocked" || code.isEmpty()) -> {
                CloudManager.onBlocked(message)
                CloudAiError(message.ifBlank { if (english) "The administrator has restricted access to Honer AI." else "Администратор ограничил доступ к Honer AI." })
            }
            status == 401 -> {
                CloudManager.invalidateToken()
                CloudAiError(if (english) "The session has expired. Send the message again." else "Сеанс устарел. Отправьте сообщение ещё раз.")
            }
            status == 429 -> CloudAiError(message.ifBlank {
                if (english) "Too many requests. Wait a few minutes and try again." else "Слишком много запросов. Подождите несколько минут и повторите."
            })
            status in 500..599 -> CloudAiError(message.ifBlank {
                if (english) "The Honer AI server is temporarily unavailable ($status). Try again." else "Сервер Honer AI временно недоступен ($status). Повторите запрос."
            })
            message.isNotBlank() -> CloudAiError(message)
            else -> null
        }
    }

    private fun english(): Boolean = runCatching {
        com.honerai.app.AppContainer.get(CloudManagerContext.context ?: return false).settings.isEnglish
    }.getOrDefault(false)
}

/** Контекст приложения для мест без Context (выбор языка сообщений об ошибках). */
internal object CloudManagerContext {
    @Volatile var context: android.content.Context? = null
}
