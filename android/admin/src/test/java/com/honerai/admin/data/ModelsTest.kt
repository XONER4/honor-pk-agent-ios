package com.honerai.admin.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Модели и кадры против примеров из server/API.md: разбор и сериализация туда-обратно. */
class ModelsTest {

    private val attachmentJson = """
        {"id":"m1","kind":"voice","name":"voice.m4a","mime":"audio/mp4","size":12345,"durationMs":4200,
         "width":null,"height":null,"url":"/v1/media/m1"}
    """.trimIndent()

    private val messageJson = """
        {"id":"msg-2","clientId":"c-2","chatId":"chat-1","sender":"admin","text":"Привет! 👋",
         "attachments":[$attachmentJson],"replyTo":"msg-1","createdAt":"2026-09-28T18:04:05.123Z","editedAt":null,
         "deleted":false,"reactions":{"👍":["admin","user"]},"pinned":true,"readByPeer":true}
    """.trimIndent()

    private fun <T> roundTrip(serializer: KSerializer<T>, json: String): T {
        val first = AdminJson.decodeFromString(serializer, json)
        val encoded = AdminJson.encodeToString(serializer, first)
        val second = AdminJson.decodeFromString(serializer, encoded)
        assertEquals(first, second)
        return first
    }

    @Test
    fun attachmentRef() {
        val a = roundTrip(AttachmentRef.serializer(), attachmentJson)
        assertEquals(AttachmentKinds.VOICE, a.kind)
        assertEquals(4200L, a.durationMs)
        assertNull(a.width)
        assertEquals("/v1/media/m1", a.url)
    }

    @Test
    fun message() {
        val m = roundTrip(Message.serializer(), messageJson)
        assertEquals("msg-2", m.id)
        assertEquals("c-2", m.clientId)
        assertEquals(Sender.ADMIN, m.sender)
        assertEquals("Привет! 👋", m.text)
        assertEquals("msg-1", m.replyTo)
        assertEquals(listOf("admin", "user"), m.reactions["👍"])
        assertTrue(m.pinned)
        assertTrue(m.readByPeer)
        assertNull(m.editedAt)
        assertEquals(1, m.attachments.size)
    }

    @Test
    fun chatWithLastMessageAndUnknownFields() {
        val json = """
            {"id":"chat-1","kind":"admin","deviceId":"dev-1","title":"Аня","aiEnabled":true,"pinnedMessageId":"msg-2",
             "lastMessage":$messageJson,"unread":3,"peerTyping":false,"peerReadUpTo":"msg-2",
             "peerLastSeen":"2026-09-28T18:00:00Z","peerPresence":"background","somethingNew":42}
        """.trimIndent()
        val c = roundTrip(Chat.serializer(), json)
        assertTrue(c.aiEnabled)
        assertEquals("msg-2", c.pinnedMessageId)
        assertEquals(3, c.unread)
        assertEquals(Presence.BACKGROUND, c.peerPresence)
        assertEquals("msg-2", c.lastMessage?.id)
    }

    @Test
    fun notificationAndOverview() {
        val n = roundTrip(
            Notification.serializer(),
            """{"id":"n1","title":"Обновление","body":"Текст","createdAt":"2026-09-28T10:00:00Z","read":false,"chatId":null,"kind":"admin"}""",
        )
        assertEquals("admin", n.kind)
        assertNull(n.chatId)
        val o = roundTrip(Overview.serializer(), """{"users":120,"installs":150,"online":7,"inBackground":3,"blocked":2,"messagesToday":88}""")
        assertEquals(Overview(120, 150, 7, 3, 2, 88), o)
    }

    @Test
    fun deviceSummaryListAndDetail() {
        val summary = """
            {"deviceId":"dev-1","userId":"u-1","displayName":"Аня","deviceModel":"vivo V2250","deviceName":"Аня телефон",
             "platform":"android","appVersion":"10.44.0","installedAt":"2026-09-01T12:00:00Z","lastSeen":"2026-09-28T18:00:00Z",
             "presence":"foreground","typingIn":"chat-1","blocked":false,"messagesSent":57,"secondsInApp":7260,
             "unreadForAdmin":2,"adminChatId":"chat-1"}
        """.trimIndent()
        val list = AdminJson.decodeFromString(ListSerializer(DeviceSummary.serializer()), "[$summary]")
        assertEquals(1, list.size)
        assertEquals("chat-1", list[0].typingIn)
        assertEquals(7260L, list[0].secondsInApp)
        roundTrip(DeviceSummary.serializer(), summary)

        val detailJson = summary.removeSuffix("}") +
            ""","birthday":"2008-05-01","language":"ru","osVersion":"14","licenseAcceptedAt":"2026-09-01T12:01:00Z","blockReason":null,"installs":2}"""
        val d = roundTrip(DeviceDetail.serializer(), detailJson)
        assertEquals("2008-05-01", d.birthday)
        assertEquals(2, d.installs)
        assertEquals("14", d.osVersion)
        assertEquals(list[0], d.summary())
    }

    @Test
    fun loginResponseAndError() {
        val r = roundTrip(LoginResponse.serializer(), """{"token":"a_abc","email":"admin@example.com","name":"Admin"}""")
        assertTrue(r.token.startsWith("a_"))
        val e = AdminJson.decodeFromString(ApiError.serializer(), """{"error":"blocked","message":"Нарушение правил"}""")
        assertEquals("blocked", e.error)
    }

    @Test
    fun sendBodyOmitsNullReply() {
        val body = SendMessageBody("c1", "hi", emptyList(), null)
        val obj = Json.parseToJsonElement(AdminJson.encodeToString(SendMessageBody.serializer(), body)).jsonObject
        assertEquals("c1", obj["clientId"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("replyTo"))
        assertTrue(obj.containsKey("attachments"))
    }

    // --- Кадры WebSocket -------------------------------------------------------------------------------

    @Test
    fun serverFrames() {
        val msg = ServerFrame.parse("""{"t":"message","chatId":"chat-1","message":$messageJson}""")
        assertTrue(msg is ServerFrame.NewMessage)
        assertEquals("msg-2", (msg as ServerFrame.NewMessage).message.id)

        val upd = ServerFrame.parse("""{"t":"message.updated","chatId":"chat-1","message":{"id":"msg-2","sender":"user","deleted":true}}""")
        assertTrue((upd as ServerFrame.MessageUpdated).message.deleted)
        assertEquals("chat-1", upd.message.chatId)

        assertEquals(ServerFrame.ChatCleared("chat-1"), ServerFrame.parse("""{"t":"chat.cleared","chatId":"chat-1"}"""))
        assertEquals(ServerFrame.Typing("chat-1", "ai", true), ServerFrame.parse("""{"t":"typing","chatId":"chat-1","who":"ai","typing":true}"""))
        assertEquals(ServerFrame.Read("chat-1", "user", "msg-2"), ServerFrame.parse("""{"t":"read","chatId":"chat-1","who":"user","messageId":"msg-2"}"""))
        assertEquals(
            ServerFrame.PresenceChanged("dev-1", "offline", null, "2026-09-28T18:00:00Z"),
            ServerFrame.parse("""{"t":"presence","deviceId":"dev-1","state":"offline","typingIn":null,"lastSeen":"2026-09-28T18:00:00Z"}"""),
        )
        assertEquals(ServerFrame.Blocked("spam"), ServerFrame.parse("""{"t":"blocked","message":"spam"}"""))
        val n = ServerFrame.parse(
            """{"t":"notification","notification":{"id":"n1","title":"T","body":"B","createdAt":"2026-09-28T10:00:00Z","read":false,"chatId":"chat-1","kind":"system"}}""",
        )
        assertEquals("chat-1", (n as ServerFrame.NotificationFrame).notification.chatId)
        assertEquals(ServerFrame.Pong, ServerFrame.parse("""{"t":"pong"}"""))
        assertEquals(ServerFrame.Unknown("future"), ServerFrame.parse("""{"t":"future","x":1}"""))
        assertNull(ServerFrame.parse("not json"))
        assertNull(ServerFrame.parse("""{"t":"message","chatId":"c"}"""))
    }

    @Test
    fun clientFrames() {
        fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
        val typing = obj(ClientFrames.typing("chat-1", true))
        assertEquals("typing", typing["t"]!!.jsonPrimitive.content)
        assertEquals("true", typing["typing"]!!.jsonPrimitive.content)
        val read = obj(ClientFrames.read("chat-1", "msg-9"))
        assertEquals("msg-9", read["messageId"]!!.jsonPrimitive.content)
        assertFalse(read["chatId"] is JsonNull)
        assertEquals("background", obj(ClientFrames.presence(false))["state"]!!.jsonPrimitive.content)
        assertEquals("ping", obj(ClientFrames.ping())["t"]!!.jsonPrimitive.content)
    }
}
