package com.honerai.app.cloud

import com.honerai.app.data.DeepSeekConfiguration
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/** Подписи «был(а)…», журнал уведомлений, выбор маршрута нейросети, паузы переподключения, кадры «печатает». */
class CloudLogicTest {
    @get:Rule val folder = TemporaryFolder()
    private val zone: ZoneId = ZoneOffset.ofHours(3)
    private val now: Instant = Instant.parse("2026-09-29T12:00:00Z") // 15:00 по Москве

    // ---- Статус собеседника ----

    @Test
    fun presenceSubtitlePriority() {
        assertEquals("печатает…", PresenceText.subtitle("foreground", true, true, null, now, false, zone))
        assertEquals("Honer AI печатает…", PresenceText.subtitle("offline", false, true, null, now, false, zone))
        assertEquals("в сети", PresenceText.subtitle("foreground", false, false, now.minusSeconds(3600), now, false, zone))
        assertEquals("online", PresenceText.subtitle("foreground", false, false, null, now, true, zone))
        assertEquals("был(а) недавно", PresenceText.subtitle("offline", false, false, null, now, false, zone))
    }

    @Test
    fun russianLastSeen() {
        fun ago(seconds: Long) = PresenceText.lastSeenText(now.minusSeconds(seconds), now, false, zone)
        assertEquals("был(а) только что", ago(20))
        assertEquals("был(а) 1 минуту назад", ago(60))
        assertEquals("был(а) 2 минуты назад", ago(120))
        assertEquals("был(а) 5 минут назад", ago(300))
        assertEquals("был(а) 11 минут назад", ago(11 * 60))
        assertEquals("был(а) 21 минуту назад", ago(21 * 60))
        assertEquals("был(а) 44 минуты назад", ago(44 * 60))
        assertEquals("был(а) 1 час назад", ago(3600))
        assertEquals("был(а) 3 часа назад", ago(3 * 3600))
        assertEquals("был(а) 5 часов назад", ago(5 * 3600))
        // Вчера и раньше — дата и время.
        assertEquals("был(а) вчера в 22:30", PresenceText.lastSeenText(Instant.parse("2026-09-28T19:30:00Z"), now, false, zone))
        assertEquals("был(а) 20.09.2026", PresenceText.lastSeenText(Instant.parse("2026-09-20T10:00:00Z"), now, false, zone))
        assertEquals("last seen 5 min ago", PresenceText.lastSeenText(now.minusSeconds(300), now, true, zone))
    }

    @Test
    fun pluralAndDayTitles() {
        assertEquals("минуту", PresenceText.plural(101, "минуту", "минуты", "минут"))
        assertEquals("минут", PresenceText.plural(112, "минуту", "минуты", "минут"))
        assertEquals("минуты", PresenceText.plural(23, "минуту", "минуты", "минут"))
        val today = LocalDate.of(2026, 9, 29)
        assertEquals("Сегодня", PresenceText.dayTitle(today, today, false))
        assertEquals("Вчера", PresenceText.dayTitle(today.minusDays(1), today, false))
        assertEquals("12 сентября", PresenceText.dayTitle(LocalDate.of(2026, 9, 12), today, false))
        assertEquals("31 декабря 2025", PresenceText.dayTitle(LocalDate.of(2025, 12, 31), today, false))
        assertEquals("September 12", PresenceText.dayTitle(LocalDate.of(2026, 9, 12), today, true))
        assertEquals("13:00", PresenceText.clock("2026-09-29T10:00:00Z", zone))
        assertEquals("", PresenceText.clock("", zone))
    }

    // ---- Журнал уведомлений ----

    private fun entry(id: String, at: String, read: Boolean = false, serverId: String? = null, kind: String = "admin", chatId: String? = null) =
        NotificationEntry(id, kind, "T$id", "B$id", Instant.parse(at).toEpochMilli(), read, chatId = chatId, serverId = serverId)

    @Test
    fun storeDedupesOrdersAndPersists() {
        val file = File(folder.root, "n/notifications.json")
        val store = NotificationsStore(file, async = false)
        assertTrue(store.add(entry("1", "2026-09-29T08:00:00Z", serverId = "s1")))
        assertTrue(store.add(entry("2", "2026-09-29T11:00:00Z")))
        assertFalse(store.add(entry("1", "2026-09-29T08:00:00Z")))
        assertTrue(store.add(entry("3", "2026-09-28T20:00:00Z", read = true)))
        assertEquals(listOf("2", "1", "3"), store.entries.value.map { it.id })
        assertEquals(2, store.unreadCount)
        // Перезапуск: читается с диска.
        val reloaded = NotificationsStore(file, async = false)
        assertEquals(listOf("2", "1", "3"), reloaded.entries.value.map { it.id })
        // «Прочитать все» возвращает серверные id для POST /v1/notifications/read.
        assertEquals(listOf("s1"), reloaded.markAllRead())
        assertEquals(0, reloaded.unreadCount)
        reloaded.remove("2")
        assertEquals(listOf("1", "3"), NotificationsStore(file, async = false).entries.value.map { it.id })
    }

    @Test
    fun storeMarksChatReadAndKeepsLimit() {
        val store = NotificationsStore(File(folder.root, "limit.json"), async = false)
        store.add(entry("m1", "2026-09-29T08:00:00Z", kind = NotificationEntry.KIND_MESSAGE, chatId = "chat"))
        store.add(entry("b1", "2026-09-29T08:01:00Z", kind = "admin", chatId = null))
        store.markChatRead("chat")
        assertEquals(mapOf("m1" to true, "b1" to false), store.entries.value.associate { it.id to it.read })
        repeat(NotificationsStore.LIMIT + 20) { i -> store.add(entry("x$i", Instant.parse("2026-09-01T00:00:00Z").plusSeconds(i * 60L).toString())) }
        assertEquals(NotificationsStore.LIMIT, store.entries.value.size)
        store.clearAll()
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun groupingByDay() {
        val list = listOf(
            entry("a", "2026-09-29T11:00:00Z"), entry("b", "2026-09-29T06:00:00Z"),
            entry("c", "2026-09-28T20:30:00Z"), // 23:30 вчера по Москве
            entry("d", "2026-09-28T21:30:00Z"), // 00:30 сегодня по Москве
            entry("e", "2026-09-12T10:00:00Z"),
        )
        val days = NotificationGrouping.group(list, now, english = false, zone = zone)
        assertEquals(listOf("Сегодня", "Вчера", "12 сентября"), days.map { it.title })
        assertEquals(listOf("a", "b", "d"), days[0].items.map { it.id })
        assertEquals(listOf("c"), days[1].items.map { it.id })
        assertTrue(NotificationGrouping.group(emptyList(), now, false, zone).isEmpty())
    }

    // ---- Маршрут нейросети ----

    @Test
    fun routeSelection() {
        val direct = AiRoute.select("", null, "https://api.deepseek.com/", "sk-embedded")
        assertEquals(AiRoute.Direct("https://api.deepseek.com/chat/completions", "sk-embedded"), direct)
        val cloud = AiRoute.select("https://honer.example.app", "d_token", "https://api.deepseek.com", "sk-embedded")
        assertEquals(AiRoute.Cloud("https://honer.example.app/v1/ai/chat/completions", "d_token"), cloud)
        // Облако настроено, но токена ещё нет — встроенный ключ НЕ используется.
        assertNull(AiRoute.select("https://honer.example.app", null, "https://api.deepseek.com", "sk-embedded"))
        assertNull(AiRoute.select("https://honer.example.app", " ", "https://api.deepseek.com", "sk-embedded"))
    }

    @Test
    fun proxyInactiveInTestsGoesDirect() {
        assertFalse(AiProxy.active)
        val route = AiProxy.route(DeepSeekConfiguration(apiKey = "k"))
        assertEquals("https://api.deepseek.com/chat/completions", route.url)
        assertEquals("k", route.bearer)
        val request = Request.Builder().url("https://api.deepseek.com/chat/completions").build()
        assertNull(AiProxy.failure(request, 403, """{"error":"blocked","message":"x"}"""))
    }

    @Test
    fun cloudUrls() {
        assertEquals("", CloudUrls.normalize("  "))
        assertEquals("https://honer.up.railway.app", CloudUrls.normalize("honer.up.railway.app/"))
        assertEquals("http://10.0.2.2:8080", CloudUrls.normalize("http://10.0.2.2:8080"))
        assertEquals("wss://h.app/v1/ws?token=d_a%2Bb", CloudUrls.webSocket("https://h.app", "d_a+b"))
        assertEquals("ws://10.0.2.2:8080/v1/ws?token=d_x", CloudUrls.webSocket("http://10.0.2.2:8080", "d_x"))
        assertEquals("https://h.app/v1/media/abc", CloudUrls.media("https://h.app", "/v1/media/abc"))
        assertEquals("https://cdn.example/x.jpg", CloudUrls.media("https://h.app", "https://cdn.example/x.jpg"))
        assertTrue(CloudUrls.isOwnUrl("https://h.app", "https://h.app/v1/media/1"))
        assertFalse(CloudUrls.isOwnUrl("https://h.app", "https://h.app.evil.com/v1/media/1"))
        assertFalse(CloudUrls.isOwnUrl("", "https://h.app/v1"))
    }

    // ---- Переподключение и «печатает» ----

    @Test
    fun backoffSchedule() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (0..7).map { Backoff.delayFor(it) })
        assertEquals(30_000L, Backoff.delayFor(1000))
        assertEquals(1_000L, Backoff.delayFor(-1))
        val random = Random(7)
        repeat(200) {
            val value = Backoff.withJitter(10_000, random)
            assertTrue(value in 8_000..12_000)
        }
    }

    @Test
    fun typingThrottle() {
        val throttle = TypingThrottle(intervalMs = 3_000, idleMs = 4_000)
        assertTrue(throttle.onInput(0))          // первый символ — typing=true
        assertFalse(throttle.onInput(1_000))     // дальше не чаще раза в 3 с
        assertFalse(throttle.onTick(2_000))
        assertTrue(throttle.onInput(3_100))      // повтор, чтобы индикатор у админа не погас (сервер гасит через 8 с)
        assertFalse(throttle.onTick(6_000))
        assertTrue(throttle.onTick(7_200))       // пауза 4 с — typing=false
        assertFalse(throttle.isTyping)
        assertFalse(throttle.onTick(9_000))
        assertTrue(throttle.onInput(9_500))
        assertTrue(throttle.stop())              // отправили сообщение — typing=false сразу
        assertFalse(throttle.stop())
    }
}
