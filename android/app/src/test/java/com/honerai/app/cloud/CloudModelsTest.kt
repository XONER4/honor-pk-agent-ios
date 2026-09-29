package com.honerai.app.cloud

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Модели облака против образцов из server/API.md и NOTES.md. */
class CloudModelsTest {
    private val messageSample = """
        {"id":"m1","clientId":"c1","chatId":"chat1","sender":"admin","text":"Привет",
         "attachments":[{"id":"a1","kind":"voice","name":"voice.m4a","mime":"audio/mp4","size":12345,
                         "durationMs":4200,"width":null,"height":null,"url":"/v1/media/a1"}],
         "replyTo":null,"createdAt":"2026-09-29T10:00:00.000Z","editedAt":null,"deleted":false,
         "reactions":{"👍":["admin","user"]},"pinned":true,"readByPeer":false}
    """.trimIndent()

    @Test
    fun messageRoundTrip() {
        val message = CloudJson.decodeFromString(CloudMessage.serializer(), messageSample)
        assertEquals("m1", message.id)
        assertEquals("c1", message.clientId)
        assertTrue(message.fromAdmin)
        assertEquals("voice", message.attachments.single().kind)
        assertEquals(4200L, message.attachments.single().durationMs)
        assertNull(message.attachments.single().width)
        assertEquals(listOf("admin", "user"), message.reactions["👍"])
        assertTrue(message.pinned)
        val again = CloudJson.decodeFromString(CloudMessage.serializer(), CloudJson.encodeToString(CloudMessage.serializer(), message))
        assertEquals(message, again)
    }

    @Test
    fun chatWithUnknownFieldsAndNulls() {
        val raw = """[{"id":"chat1","kind":"admin","deviceId":"d1","title":"Администратор","aiEnabled":true,
            "pinnedMessageId":null,"lastMessage":$messageSample,"unread":3,"peerTyping":false,"peerReadUpTo":"m0",
            "peerLastSeen":"2026-09-29T09:58:00Z","peerPresence":"background","futureField":42}]"""
        val chats = CloudJson.decodeFromString(ListSerializer(CloudChat.serializer()), raw)
        val chat = chats.single()
        assertTrue(chat.aiEnabled)
        assertEquals(3, chat.unread)
        assertEquals("m0", chat.peerReadUpTo)
        assertEquals("background", chat.peerPresence)
        assertEquals("m1", chat.lastMessage?.id)
        assertEquals(chat, CloudJson.decodeFromString(CloudChat.serializer(), CloudJson.encodeToString(CloudChat.serializer(), chat)))
    }

    @Test
    fun notificationAndRegisterRoundTrip() {
        val notification = CloudJson.decodeFromString(CloudNotification.serializer(),
            """{"id":"n1","title":"Новости","body":"Текст","createdAt":"2026-09-29T10:00:00Z","read":false,"chatId":null,"kind":"admin"}""")
        assertEquals("admin", notification.kind)
        assertNull(notification.chatId)

        val register = RegisterRequest(
            installId = "i1", deviceModel = "vivo V2250", deviceName = "Мой телефон", osVersion = "14", appVersion = "10.44.0",
            displayName = "Имя", birthday = null, language = "ru", licenseAcceptedAt = null, pushToken = null,
        )
        val json = CloudJson.parseToJsonElement(CloudJson.encodeToString(RegisterRequest.serializer(), register)).jsonObject
        assertEquals("android", json["platform"]!!.jsonPrimitive.content)
        assertEquals("vivo V2250", json["deviceModel"]!!.jsonPrimitive.content)
        // explicitNulls = false: пустые необязательные поля не отправляются.
        assertFalse(json.containsKey("birthday"))
        assertFalse(json.containsKey("pushToken"))

        val response = CloudJson.decodeFromString(RegisterResponse.serializer(),
            """{"deviceId":"d1","token":"d_abc","userId":"u1","adminChatId":"chat1"}""")
        assertEquals("d_abc", response.token)
        assertEquals("chat1", response.adminChatId)
    }

    @Test
    fun sendRequestCarriesClientIdAndRefs() {
        val body = SendMessageRequest("c9", "текст", listOf(CloudAttachment(id = "a1", kind = "image")), replyTo = "m1")
        val json = CloudJson.parseToJsonElement(CloudJson.encodeToString(SendMessageRequest.serializer(), body)).jsonObject
        assertEquals("c9", json["clientId"]!!.jsonPrimitive.content)
        assertEquals("m1", json["replyTo"]!!.jsonPrimitive.content)
        assertEquals("a1", (json["attachments"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content)
        val stats = CloudJson.encodeToString(StatsRequest.serializer(), StatsRequest(12, 3600))
        assertEquals("""{"messagesSent":12,"secondsInApp":3600}""", stats)
    }

    @Test
    fun serverFramesParse() {
        val message = CloudFrame.parse("""{"t":"message","chatId":"chat1","message":$messageSample}""")
        assertTrue(message is CloudFrame.Message && message.chatId == "chat1" && message.message.id == "m1")
        val updated = CloudFrame.parse("""{"t":"message.updated","chatId":"chat1","message":{"id":"m1","deleted":true,"text":"","attachments":[]}}""")
        assertTrue(updated is CloudFrame.MessageUpdated && updated.message.deleted)
        assertEquals(CloudFrame.ChatCleared("chat1"), CloudFrame.parse("""{"t":"chat.cleared","chatId":"chat1"}"""))
        assertEquals(CloudFrame.Typing("chat1", "ai", true), CloudFrame.parse("""{"t":"typing","chatId":"chat1","who":"ai","typing":true}"""))
        assertEquals(CloudFrame.Read("chat1", "admin", "m5"), CloudFrame.parse("""{"t":"read","chatId":"chat1","who":"admin","messageId":"m5"}"""))
        assertEquals(CloudFrame.Blocked("Нарушение правил"), CloudFrame.parse("""{"t":"blocked","message":"Нарушение правил"}"""))
        val presence = CloudFrame.parse("""{"t":"presence","deviceId":"d1","state":"offline","typingIn":null,"lastSeen":"2026-09-29T10:00:00Z"}""")
        assertTrue(presence is CloudFrame.Presence && presence.state == "offline")
        val notification = CloudFrame.parse("""{"t":"notification","notification":{"id":"n1","title":"T","body":"B","createdAt":"2026-09-29T10:00:00Z","read":false,"chatId":null,"kind":"admin"}}""")
        assertTrue(notification is CloudFrame.Notification && notification.notification.id == "n1")
        assertEquals(CloudFrame.Pong, CloudFrame.parse("""{"t":"pong"}"""))
        // Неизвестные и битые кадры не роняют соединение.
        assertNull(CloudFrame.parse("""{"t":"future.frame"}"""))
        assertNull(CloudFrame.parse("not json"))
        assertNull(CloudFrame.parse("""{"t":"read","chatId":"c"}"""))
    }

    @Test
    fun clientFramesMatchContract() {
        fun parse(text: String): JsonObject = CloudJson.parseToJsonElement(text).jsonObject
        assertEquals("foreground", parse(ClientFrames.presence(true))["state"]!!.jsonPrimitive.content)
        assertEquals("background", parse(ClientFrames.presence(false))["state"]!!.jsonPrimitive.content)
        val typing = parse(ClientFrames.typing("chat1", true))
        assertEquals("typing", typing["t"]!!.jsonPrimitive.content)
        assertEquals("true", typing["typing"]!!.jsonPrimitive.content)
        val read = parse(ClientFrames.read("chat1", "m9"))
        assertEquals("m9", read["messageId"]!!.jsonPrimitive.content)
        assertEquals("""{"t":"ping"}""", ClientFrames.ping())
    }

    @Test
    fun errorBodies() {
        assertEquals("blocked" to "Причина", CloudHttpException.parseBody("""{"error":"blocked","message":"Причина"}"""))
        assertEquals("" to "", CloudHttpException.parseBody("<html>"))
        assertTrue(CloudHttpException(403, "blocked", "").isBlocked)
        assertFalse(CloudHttpException(403, "forbidden", "").isBlocked)
        assertTrue(CloudHttpException(401, "unauthorized", "").isUnauthorized)
    }

    @Test
    fun messagePreviews() {
        val base = CloudMessage(id = "m", sender = "admin")
        assertEquals("Привет", MessagePreview.text(base.copy(text = "  Привет "), false))
        assertEquals("📷 Фото", MessagePreview.text(base.copy(attachments = listOf(CloudAttachment(kind = "image"))), false))
        assertEquals("🎤 Voice message", MessagePreview.text(base.copy(attachments = listOf(CloudAttachment(kind = "voice"))), true))
        assertEquals("📎 отчёт.pdf", MessagePreview.text(base.copy(attachments = listOf(CloudAttachment(kind = "file", name = "отчёт.pdf"))), false))
    }

    @Test
    fun licenseDateInAnyFormat() {
        assertEquals("2026-09-29T10:00:00Z", LicenseDate.toIso("2026-09-29T10:00:00Z"))
        assertEquals("2026-09-29T10:00:00Z", LicenseDate.toIso(1_790_676_000_000L))
        assertEquals("2026-09-29T10:00:00Z", LicenseDate.toIso(1_790_676_000L))
        assertEquals("2026-09-29T10:00:00Z", LicenseDate.toIso("1790676000000"))
        assertEquals("2026-09-29T07:00:00Z", LicenseDate.toIso("2026-09-29T10:00:00+03:00"))
        assertNull(LicenseDate.toIso(null))
        assertNull(LicenseDate.toIso(""))
        assertNull(LicenseDate.toIso(0L))
        assertNull(LicenseDate.toIso(true))
    }
}
