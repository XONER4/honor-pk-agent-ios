package com.honerai.admin.core

import com.honerai.admin.data.AdminJson
import com.honerai.admin.data.AiWindow
import com.honerai.admin.data.DeviceDetail
import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.LatencyStats
import com.honerai.admin.data.Metrics
import com.honerai.admin.data.ServerFrame
import com.honerai.admin.data.idLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** admin2: метрики, форматирование, расписание, срок блокировки, поиск по ID, адрес сервера. */
class InsightsTest {

    @Test
    fun metricsFrameAndRest() {
        val raw = """{"t":"metrics","at":"2026-09-29T10:00:00.000Z","online":3,"inBackground":2,"rps":1.25,"requests1m":75,
            "errors1m":0,"tokensToday":12345,"tokensTotal":999999,"tokens":{"today":{"prompt":1}},"aiRequestsToday":7,
            "aiErrorsToday":1,"reportsToday":2,"errorsToday":3,"aiLatencyMs":{"p50":850,"p95":2400,"avg":1000,"count":7},
            "apiLatencyMs":{"p50":null,"p95":null,"avg":null,"count":0},
            "model":{"enabled":false,"switchedOn":false,"scheduled":false,"configured":true,"name":"deepseek-flash"}}"""
        val frame = ServerFrame.parse(raw) as ServerFrame.MetricsFrame
        val m = frame.metrics
        assertEquals(3, m.online)
        assertEquals(1.25, m.rps, 0.0001)
        assertEquals(12345L, m.tokensToday)
        assertEquals(850L, m.aiLatencyMs.p50)
        assertNull(m.apiLatencyMs.p50)
        assertFalse(m.model.enabled)

        val ai = ServerFrame.parse("""{"t":"ai","enabled":true,"schedule":[{"days":[1,2],"from":"09:00","to":"18:00"}],
            "timezone":"Europe/Moscow","effective":false,"configured":true}""") as ServerFrame.AiChanged
        assertEquals(listOf(1, 2), ai.settings.schedule.single().days)
        assertFalse(ai.settings.effective)
    }

    @Test
    fun deviceFieldsFromNewServer() {
        val d = AdminJson.decodeFromString(DeviceDetail.serializer(), """{"deviceId":"d1","publicId":"0427","blockedUntil":"2026-10-01T00:00:00Z",
            "aiTokens":4000,"reports":2,"overrides":{"forceLanguage":"en"},"usage":{"today":{"prompt":10,"completion":5,"total":15,"requests":1,"errors":0},
            "total":{"total":4000}}}""")
        assertEquals("0427", d.publicId)
        assertEquals("en", d.overrides.forceLanguage)
        assertEquals(15L, d.usage!!.today.total)
        assertEquals(4000L, d.usage!!.total.total)
        val s = d.summary()
        assertEquals("#0427", s.idLabel())
        assertEquals(4000L, s.aiTokens)
        assertEquals("2026-10-01T00:00:00Z", s.blockedUntil)
        assertEquals("", DeviceSummary("x").idLabel())
    }

    @Test
    fun metricsHistoryKeepsLastAndMergesCloseSamples() {
        var h = MetricsHistory(max = 3)
        for (i in 0 until 5) h = h.add(Metrics(online = i), atMs = i * 5_000L)
        assertEquals(listOf(2.0, 3.0, 4.0), h.series { it.online.toDouble() })
        h = h.add(Metrics(online = 9), atMs = 20_500L) // через 0,5 с после прошлого — замена, не новая точка
        assertEquals(listOf(2.0, 3.0, 9.0), h.series { it.online.toDouble() })
        h = h.add(Metrics(aiLatencyMs = LatencyStats(p50 = 700)), atMs = 30_000L)
        assertEquals(700L, h.samples.last().aiP50)
    }

    @Test
    fun numbers() {
        assertEquals("950", Numbers.compact(950, false))
        assertEquals("12,3 тыс.", Numbers.compact(12_345, false))
        assertEquals("12.3K", Numbers.compact(12_345, true))
        assertEquals("4,2 млн", Numbers.compact(4_200_000, false))
        assertEquals("10 тыс.", Numbers.compact(10_000, false))
        assertEquals("0", Numbers.rate(0.0, false))
        assertEquals("0,35", Numbers.rate(0.35, false))
        assertEquals("12", Numbers.rate(12.4, true))
        assertEquals("—", Numbers.latency(null, false))
        assertEquals("850 мс", Numbers.latency(850, false))
        assertEquals("2,4 с", Numbers.latency(2_400, false))
        assertEquals("2 s", Numbers.latency(2_000, true))
    }

    @Test
    fun scheduleText() {
        assertEquals("Пн–Пт", ScheduleText.days(listOf(5, 1, 2, 3, 4), false))
        assertEquals("Сб, Вс", ScheduleText.days(listOf(6, 7), false))
        assertEquals("Каждый день", ScheduleText.days(emptyList(), false))
        assertEquals("Every day", ScheduleText.days((1..7).toList(), true))
        assertEquals("Пн, Ср–Пт", ScheduleText.days(listOf(1, 3, 4, 5), false))
        assertEquals("Пн–Пт, 09:00–18:00", ScheduleText.window(AiWindow(listOf(1, 2, 3, 4, 5), "09:00", "18:00"), false))
        assertEquals("Каждый день, весь день", ScheduleText.window(AiWindow(emptyList(), "00:00", "00:00"), false))
        assertTrue(ScheduleText.isValidTime("23:59"))
        assertFalse(ScheduleText.isValidTime("24:00"))
        assertFalse(ScheduleText.isValidTime("9:00"))
    }

    @Test
    fun blockTermAndPlurals() {
        val now = Instant.parse("2026-09-29T10:00:00Z")
        assertEquals("2026-09-29T11:00:00Z", BlockTerm.HOUR.until(now))
        assertEquals("2026-10-06T10:00:00Z", BlockTerm.WEEK.until(now))
        assertNull(BlockTerm.FOREVER.until(now))
        assertEquals("1 пользователю", HistoryText.usersDative(1, false))
        assertEquals("21 пользователю", HistoryText.usersDative(21, false))
        assertEquals("11 пользователям", HistoryText.usersDative(11, false))
        assertEquals("5 users", HistoryText.usersDative(5, true))
        assertEquals("Обновление v10.44.0 → v10.45.0", HistoryText.event("update", "10.44.0", "10.45.0", false))
        assertEquals("Установка v10.44.0", HistoryText.event("install", null, "10.44.0", false))
    }

    @Test
    fun searchByPublicIdAndSortByTokens() {
        val a = DeviceSummary("a", displayName = "Анна", publicId = "0427", aiTokens = 10)
        val b = DeviceSummary("b", displayName = "Борис", publicId = "1234", aiTokens = 500)
        val c = DeviceSummary("c", displayName = "Вера", publicId = null, aiTokens = 50)
        val all = listOf(a, b, c)
        assertEquals(listOf("a"), UserList.visible(all, UserFilter.ALL, "0427").map { it.deviceId })
        assertEquals(listOf("a"), UserList.visible(all, UserFilter.ALL, "#042").map { it.deviceId })
        assertEquals(listOf("b"), UserList.visible(all, UserFilter.ALL, "борис").map { it.deviceId })
        assertEquals(listOf("b", "c", "a"), UserList.visible(all, UserFilter.ALL, "", UserSort.TOKENS).map { it.deviceId })
    }

    @Test
    fun serverUrlMigration() {
        val relay = "https://honer.xoner4.deno.net"
        assertEquals(relay, SessionStore.resolveServerUrl(null, relay))
        assertEquals(relay, SessionStore.resolveServerUrl("https://honer-cloud.up.railway.app", relay))
        assertEquals(relay, SessionStore.resolveServerUrl("http://localhost:3000", "$relay/"))
        assertEquals(relay, SessionStore.resolveServerUrl("http://10.0.2.2:3000", relay))
        assertEquals("https://my.server.ru", SessionStore.resolveServerUrl("https://my.server.ru", relay))
        // В сборке нет адреса — сохранённый (даже Railway) остаётся.
        assertEquals("https://honer-cloud.up.railway.app", SessionStore.resolveServerUrl("https://honer-cloud.up.railway.app", ""))
        assertTrue(SessionStore.isObsoleteUrl("https://x.up.railway.app"))
        assertFalse(SessionStore.isObsoleteUrl(relay))
    }
}
