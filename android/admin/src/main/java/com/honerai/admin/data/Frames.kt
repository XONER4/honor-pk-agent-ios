package com.honerai.admin.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Кадры WebSocket от сервера (API.md → «Server → client frames»). */
sealed interface ServerFrame {
    data class NewMessage(val chatId: String, val message: Message) : ServerFrame
    data class MessageUpdated(val chatId: String, val message: Message) : ServerFrame
    data class ChatCleared(val chatId: String) : ServerFrame
    data class Typing(val chatId: String, val who: String, val typing: Boolean) : ServerFrame
    data class Read(val chatId: String, val who: String, val messageId: String) : ServerFrame
    data class PresenceChanged(val deviceId: String, val state: String, val typingIn: String?, val lastSeen: String?) : ServerFrame
    data class Blocked(val message: String) : ServerFrame
    data class NotificationFrame(val notification: Notification) : ServerFrame
    data object Pong : ServerFrame
    data class Unknown(val type: String) : ServerFrame

    companion object {
        /** Разбор кадра; битый JSON → null (соединение не рвём из-за одного кадра). */
        fun parse(text: String): ServerFrame? {
            val obj = runCatching { AdminJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return runCatching { parse(obj) }.getOrNull()
        }

        fun parse(obj: JsonObject): ServerFrame {
            val type = obj.str("t") ?: return Unknown("")
            val chatId = obj.str("chatId").orEmpty()
            return when (type) {
                "message" -> NewMessage(chatId, decodeMessage(obj, chatId))
                "message.updated" -> MessageUpdated(chatId, decodeMessage(obj, chatId))
                "chat.cleared" -> ChatCleared(chatId)
                "typing" -> Typing(chatId, obj.str("who") ?: Sender.USER, obj.bool("typing") ?: false)
                "read" -> Read(chatId, obj.str("who") ?: Sender.USER, obj.str("messageId").orEmpty())
                "presence" -> PresenceChanged(
                    deviceId = obj.str("deviceId").orEmpty(),
                    state = obj.str("state") ?: Presence.OFFLINE,
                    typingIn = obj.str("typingIn"),
                    lastSeen = obj.str("lastSeen"),
                )
                "blocked" -> Blocked(obj.str("message").orEmpty())
                "notification" -> NotificationFrame(
                    AdminJson.decodeFromJsonElement(Notification.serializer(), obj.getValue("notification")),
                )
                "pong" -> Pong
                else -> Unknown(type)
            }
        }

        private fun decodeMessage(obj: JsonObject, chatId: String): Message {
            val message = AdminJson.decodeFromJsonElement(Message.serializer(), obj.getValue("message"))
            return if (message.chatId.isEmpty()) message.copy(chatId = chatId) else message
        }
    }
}

private fun JsonObject.str(key: String): String? {
    val element: JsonElement = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

/** Кадры клиента → сервер. */
object ClientFrames {
    fun presence(foreground: Boolean): String = buildJsonObject {
        put("t", "presence"); put("state", if (foreground) Presence.FOREGROUND else Presence.BACKGROUND)
    }.toString()

    fun typing(chatId: String, typing: Boolean): String = buildJsonObject {
        put("t", "typing"); put("chatId", chatId); put("typing", typing)
    }.toString()

    fun read(chatId: String, messageId: String): String = buildJsonObject {
        put("t", "read"); put("chatId", chatId); put("messageId", messageId)
    }.toString()

    fun ping(): String = """{"t":"ping"}"""
}
