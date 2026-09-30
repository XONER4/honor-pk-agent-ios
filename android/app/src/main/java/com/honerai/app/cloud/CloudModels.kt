package com.honerai.app.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// Модели Honer Cloud — поле в поле как в server/API.md (контракт v1).
// Даты — строки ISO-8601 UTC, как их прислал сервер: разбираются только там, где нужны.

/** Кодек облака: неизвестные поля пропускаются (сервер может добавлять новые), null не отправляются. */
val CloudJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

@Serializable
data class CloudAttachment(
    val id: String = "",
    /** image | video | audio | voice | file */
    val kind: String = "file",
    val name: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val url: String = "",
)

@Serializable
data class CloudMessage(
    val id: String,
    val clientId: String? = null,
    val chatId: String = "",
    /** admin | user | ai */
    val sender: String = "user",
    val text: String = "",
    val attachments: List<CloudAttachment> = emptyList(),
    val replyTo: String? = null,
    val createdAt: String = "",
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap(),
    val pinned: Boolean = false,
    val readByPeer: Boolean = false,
) {
    val fromUser: Boolean get() = sender == SENDER_USER
    val fromAdmin: Boolean get() = sender == SENDER_ADMIN
    val fromAi: Boolean get() = sender == SENDER_AI

    companion object {
        const val SENDER_USER = "user"
        const val SENDER_ADMIN = "admin"
        const val SENDER_AI = "ai"
    }
}

@Serializable
data class CloudChat(
    val id: String,
    val kind: String = "admin",
    val deviceId: String? = null,
    val title: String = "",
    val aiEnabled: Boolean = false,
    val pinnedMessageId: String? = null,
    val lastMessage: CloudMessage? = null,
    val unread: Int = 0,
    val peerTyping: Boolean = false,
    val peerReadUpTo: String? = null,
    val peerLastSeen: String? = null,
    /** foreground | background | offline */
    val peerPresence: String = "offline",
)

@Serializable
data class CloudNotification(
    val id: String,
    val title: String = "",
    val body: String = "",
    val createdAt: String = "",
    val read: Boolean = false,
    val chatId: String? = null,
    /** admin | system | ai */
    val kind: String = "system",
)

@Serializable
data class RegisterRequest(
    val installId: String,
    val platform: String = "android",
    val deviceModel: String,
    val deviceName: String,
    val osVersion: String,
    val appVersion: String,
    val displayName: String,
    val birthday: String? = null,
    val language: String,
    val licenseAcceptedAt: String? = null,
    val pushToken: String? = null,
    /** Стабильный ID устройства (Android ID): переустановка не плодит новых «пользователей» в админке. */
    val hardwareId: String? = null,
)

@Serializable
data class RegisterResponse(
    val deviceId: String,
    val token: String,
    val userId: String = "",
    val adminChatId: String = "",
    /** Короткий публичный ID пользователя («0427»); старый сервер его не присылает. */
    val publicId: String? = null,
    val overrides: CloudOverrides? = null,
)

/** Ответ PATCH /v1/devices/me. */
@Serializable
data class MeResponse(val ok: Boolean = true, val publicId: String? = null, val overrides: CloudOverrides? = null)

/**
 * Персональные ограничения от администратора (server/API.md → overrides). Сервер их не применяет —
 * это делает приложение; ключ отсутствует — ограничения нет.
 */
@Serializable
data class CloudOverrides(
    /** "ru" | "en" — язык интерфейса, выбранный администратором. */
    val forceLanguage: String? = null,
    /** true — поиск в интернете выключен. */
    val disableSearch: Boolean? = null,
    /** Лимит сообщений нейросети в день. */
    val maxMessagesPerDay: Int? = null,
) {
    val isEmpty: Boolean get() = forceLanguage == null && disableSearch != true && maxMessagesPerDay == null
}

/** Отчёт об ошибке или падении (POST /v1/devices/me/report). kind: error | crash. */
@Serializable
data class ReportRequest(
    val kind: String,
    val message: String,
    val stack: String? = null,
    val appVersion: String? = null,
    val at: String? = null,
)

@Serializable
data class StatsRequest(val messagesSent: Int, val secondsInApp: Int)

@Serializable
data class SendMessageRequest(
    val clientId: String,
    val text: String,
    val attachments: List<CloudAttachment> = emptyList(),
    val replyTo: String? = null,
)

/** Кадры WebSocket от сервера. */
sealed class CloudFrame {
    data class Message(val chatId: String, val message: CloudMessage) : CloudFrame()
    data class MessageUpdated(val chatId: String, val message: CloudMessage) : CloudFrame()
    data class ChatCleared(val chatId: String) : CloudFrame()
    data class Typing(val chatId: String, val who: String, val typing: Boolean) : CloudFrame()
    data class Read(val chatId: String, val who: String, val messageId: String) : CloudFrame()
    data class Presence(val deviceId: String?, val state: String, val lastSeen: String?) : CloudFrame()
    data class Blocked(val message: String) : CloudFrame()
    data class Notification(val notification: CloudNotification) : CloudFrame()
    /** Администратор изменил ограничения этого пользователя. */
    data class Overrides(val overrides: CloudOverrides) : CloudFrame()
    object Pong : CloudFrame()

    companion object {
        /** Разбор кадра; неизвестный или битый кадр — null (не роняет соединение). */
        fun parse(text: String): CloudFrame? = runCatching {
            val json = CloudJson.parseToJsonElement(text).jsonObject
            fun str(key: String): String? = (json[key] as? JsonPrimitive)?.contentOrNull
            fun message(): CloudMessage = CloudJson.decodeFromJsonElement(CloudMessage.serializer(), json["message"]!!)
            when (str("t")) {
                "message" -> Message(str("chatId").orEmpty(), message())
                "message.updated" -> MessageUpdated(str("chatId").orEmpty(), message())
                "chat.cleared" -> ChatCleared(str("chatId").orEmpty())
                "typing" -> Typing(str("chatId").orEmpty(), str("who").orEmpty(), (json["typing"] as? JsonPrimitive)?.booleanOrNull ?: false)
                "read" -> Read(str("chatId").orEmpty(), str("who").orEmpty(), str("messageId") ?: return null)
                "presence" -> Presence(str("deviceId"), str("state") ?: "offline", str("lastSeen"))
                "blocked" -> Blocked(str("message").orEmpty())
                "notification" -> Notification(CloudJson.decodeFromJsonElement(CloudNotification.serializer(), json["notification"] as JsonObject))
                "overrides" -> Overrides(CloudJson.decodeFromJsonElement(CloudOverrides.serializer(), json["overrides"] as JsonObject))
                "pong" -> Pong
                else -> null
            }
        }.getOrNull()
    }
}

/** Кадры клиента → сервер (строки JSON). */
object ClientFrames {
    private fun obj(vararg pairs: Pair<String, Any?>): String = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }).toString()

    fun presence(foreground: Boolean) = obj("t" to "presence", "state" to if (foreground) "foreground" else "background")
    fun typing(chatId: String, typing: Boolean) = obj("t" to "typing", "chatId" to chatId, "typing" to typing)
    fun read(chatId: String, messageId: String) = obj("t" to "read", "chatId" to chatId, "messageId" to messageId)
    fun ping() = obj("t" to "ping")
}

/** Ошибка сервера: HTTP-код и {"error","message"}. */
class CloudHttpException(val status: Int, val code: String, val serverMessage: String) :
    java.io.IOException("Honer Cloud $status $code ${serverMessage.take(200)}") {
    val isBlocked: Boolean get() = status == 403 && code == "blocked"
    val isUnauthorized: Boolean get() = status == 401
    /** ИИ выключен администратором (глобально или по расписанию). */
    val isAiDisabled: Boolean get() = status == 503 && code == "ai_disabled"

    companion object {
        /** Тело ошибки → (code, message); непонятное тело — пустые строки. */
        fun parseBody(raw: String): Pair<String, String> = runCatching {
            val json = CloudJson.parseToJsonElement(raw).jsonObject
            ((json["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()) to ((json["message"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }.getOrDefault("" to "")
    }
}
