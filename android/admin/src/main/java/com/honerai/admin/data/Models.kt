package com.honerai.admin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Модели API Honer AI Cloud (server/API.md, раздел Models). Время — строки ISO-8601 как пришли с сервера:
 * так JSON проходит туда-обратно без потерь, а разбор делается там, где время нужно показать ([Times]).
 */
val AdminJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
}

object Sender {
    const val ADMIN = "admin"
    const val USER = "user"
    const val AI = "ai"
}

object Presence {
    const val FOREGROUND = "foreground"
    const val BACKGROUND = "background"
    const val OFFLINE = "offline"
}

object AttachmentKinds {
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val VOICE = "voice"
    const val FILE = "file"
}

@Serializable
data class AttachmentRef(
    val id: String,
    val kind: String = AttachmentKinds.FILE,
    val name: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val url: String = "/v1/media/$id",
)

@Serializable
data class Message(
    val id: String,
    val clientId: String = "",
    val chatId: String = "",
    val sender: String = Sender.USER,
    val text: String = "",
    val attachments: List<AttachmentRef> = emptyList(),
    val replyTo: String? = null,
    val createdAt: String = "",
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap(),
    val pinned: Boolean = false,
    val readByPeer: Boolean = false,
)

@Serializable
data class Chat(
    val id: String,
    val kind: String = "admin",
    val deviceId: String = "",
    val title: String = "",
    val aiEnabled: Boolean = false,
    val pinnedMessageId: String? = null,
    val lastMessage: Message? = null,
    val unread: Int = 0,
    val peerTyping: Boolean = false,
    val peerReadUpTo: String? = null,
    val peerLastSeen: String? = null,
    val peerPresence: String = Presence.OFFLINE,
)

@Serializable
data class Notification(
    val id: String,
    val title: String = "",
    val body: String = "",
    val createdAt: String = "",
    val read: Boolean = false,
    val chatId: String? = null,
    val kind: String = "admin",
)

@Serializable
data class Overview(
    val users: Int = 0,
    val installs: Int = 0,
    val online: Int = 0,
    val inBackground: Int = 0,
    val blocked: Int = 0,
    val messagesToday: Int = 0,
)

/** Строка списка пользователей (GET /v1/admin/devices). */
@Serializable
data class DeviceSummary(
    val deviceId: String,
    val userId: String = "",
    val displayName: String = "",
    val deviceModel: String = "",
    val deviceName: String = "",
    val platform: String = "android",
    val appVersion: String = "",
    val installedAt: String? = null,
    val lastSeen: String? = null,
    val presence: String = Presence.OFFLINE,
    val typingIn: String? = null,
    val blocked: Boolean = false,
    val messagesSent: Long = 0,
    val secondsInApp: Long = 0,
    val unreadForAdmin: Int = 0,
    val adminChatId: String = "",
)

/** Карточка пользователя (GET /v1/admin/devices/:id) — DeviceSummary + подробности. */
@Serializable
data class DeviceDetail(
    val deviceId: String,
    val userId: String = "",
    val displayName: String = "",
    val deviceModel: String = "",
    val deviceName: String = "",
    val platform: String = "android",
    val appVersion: String = "",
    val installedAt: String? = null,
    val lastSeen: String? = null,
    val presence: String = Presence.OFFLINE,
    val typingIn: String? = null,
    val blocked: Boolean = false,
    val messagesSent: Long = 0,
    val secondsInApp: Long = 0,
    val unreadForAdmin: Int = 0,
    val adminChatId: String = "",
    val birthday: String? = null,
    val language: String? = null,
    val osVersion: String? = null,
    val licenseAcceptedAt: String? = null,
    val blockReason: String? = null,
    val installs: Int = 0,
) {
    fun summary(): DeviceSummary = DeviceSummary(
        deviceId, userId, displayName, deviceModel, deviceName, platform, appVersion, installedAt, lastSeen,
        presence, typingIn, blocked, messagesSent, secondsInApp, unreadForAdmin, adminChatId,
    )
}

@Serializable
data class LoginResponse(val token: String, val email: String = "", val name: String = "")

@Serializable
data class BroadcastResult(val ok: Boolean = true, val count: Int? = null)

@Serializable
data class ApiError(val error: String = "", val message: String = "")

@Serializable
data class SendMessageBody(
    val clientId: String,
    val text: String,
    val attachments: List<AttachmentRef> = emptyList(),
    val replyTo: String? = null,
)

/** Имя для показа: displayName, иначе модель телефона. */
fun DeviceSummary.title(english: Boolean): String =
    displayName.ifBlank { deviceName.ifBlank { deviceModel.ifBlank { if (english) "No name" else "Без имени" } } }
