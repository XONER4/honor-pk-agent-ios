package com.honerai.admin.core

import com.honerai.admin.data.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class PresenceTextTest {
    private val zone: ZoneId = ZoneOffset.ofHours(3)

    // 2026-09-28 12:00 по Москве.
    private val now: Instant = Instant.parse("2026-09-28T09:00:00Z")

    @Test
    fun justNow() {
        assertEquals("был(а) только что", PresenceText.lastSeen(now.minusSeconds(20), now, zone, false))
        assertEquals("last seen just now", PresenceText.lastSeen(now.minusSeconds(20), now, zone, true))
    }

    @Test
    fun minutesAgo() {
        assertEquals("был(а) 5 мин назад", PresenceText.lastSeen(now.minusSeconds(5 * 60 + 10), now, zone, false))
        assertEquals("5 мин назад", PresenceText.ago(now.minusSeconds(5 * 60), now, zone, false))
        assertEquals("59 мин назад", PresenceText.ago(now.minusSeconds(59 * 60 + 59), now, zone, false))
    }

    @Test
    fun todayAndYesterday() {
        // 08:15 по Москве того же дня.
        assertEquals("был(а) сегодня в 08:15", PresenceText.lastSeen(Instant.parse("2026-09-28T05:15:00Z"), now, zone, false))
        // Вчера в 21:04 по Москве = 18:04 UTC 27-го.
        assertEquals("был(а) вчера в 21:04", PresenceText.lastSeen(Instant.parse("2026-09-27T18:04:00Z"), now, zone, false))
        assertEquals("вчера в 21:04", PresenceText.ago(Instant.parse("2026-09-27T18:04:00Z"), now, zone, false))
        assertEquals("last seen yesterday at 21:04", PresenceText.lastSeen(Instant.parse("2026-09-27T18:04:00Z"), now, zone, true))
    }

    @Test
    fun dayBoundaryUsesLocalZone() {
        // 23:30 UTC 27-го — это уже 02:30 28-го по Москве: «сегодня», а не «вчера».
        assertEquals("сегодня в 02:30", PresenceText.ago(Instant.parse("2026-09-27T23:30:00Z"), now, zone, false))
    }

    @Test
    fun olderDates() {
        assertEquals("был(а) 3 мар. в 09:15", PresenceText.lastSeen(Instant.parse("2026-03-03T06:15:00Z"), now, zone, false))
        assertEquals("был(а) 03.03.2025 в 09:15", PresenceText.lastSeen(Instant.parse("2025-03-03T06:15:00Z"), now, zone, false))
        assertEquals("был(а) давно", PresenceText.lastSeen(null, now, zone, false))
    }

    @Test
    fun statusByPresence() {
        assertEquals("в сети", PresenceText.status(Presence.FOREGROUND, null, now, zone, false))
        assertEquals("в фоне", PresenceText.status(Presence.BACKGROUND, null, now, zone, false))
        assertEquals("был(а) 5 мин назад", PresenceText.status(Presence.OFFLINE, now.minusSeconds(300), now, zone, false))
    }

    @Test
    fun dayHeaders() {
        val today = LocalDate.of(2026, 9, 28)
        assertEquals("Сегодня", PresenceText.dayHeader(today, today, false))
        assertEquals("Вчера", PresenceText.dayHeader(today.minusDays(1), today, false))
        assertEquals("1 мая", PresenceText.dayHeader(LocalDate.of(2026, 5, 1), today, false))
    }

    @Test
    fun durationsAndSizes() {
        assertEquals("45 мин", PresenceText.duration(45 * 60 + 30, false))
        assertEquals("2 ч 1 мин", PresenceText.duration(7260, false))
        assertEquals("0:05", PresenceText.mmss(4_600))
        assertEquals("1:05", PresenceText.mmss(65_000))
        assertEquals("512 Б", PresenceText.fileSize(512, false))
        assertEquals("12 КБ", PresenceText.fileSize(12 * 1024 + 100, false))
        assertEquals("1,5 МБ", PresenceText.fileSize(1536 * 1024, false))
    }

    @Test
    fun parseIso() {
        assertEquals(Instant.parse("2026-09-28T09:00:00Z"), Times.parse("2026-09-28T12:00:00+03:00"))
        assertEquals(Instant.parse("2026-09-28T09:00:00.123Z"), Times.parse("2026-09-28T09:00:00.123Z"))
        assertNull(Times.parse("вчера"))
        assertNull(Times.parse(null))
    }
}
