package com.honerai.app.cloud

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Подзаголовок собеседника: «в сети», «печатает…», «был(а) 5 минут назад» (чистые функции). */
object PresenceText {
    fun subtitle(
        presence: String,
        typing: Boolean,
        aiTyping: Boolean,
        lastSeen: Instant?,
        now: Instant,
        english: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = when {
        typing -> if (english) "typing…" else "печатает…"
        aiTyping -> if (english) "Honer AI is typing…" else "Honer AI печатает…"
        presence == "foreground" -> if (english) "online" else "в сети"
        lastSeen != null -> lastSeenText(lastSeen, now, english, zone)
        presence == "background" -> if (english) "recently" else "был(а) недавно"
        else -> if (english) "last seen recently" else "был(а) недавно"
    }

    fun lastSeenText(lastSeen: Instant, now: Instant, english: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val seconds = maxOf(0L, now.epochSecond - lastSeen.epochSecond)
        val minutes = seconds / 60
        val hours = minutes / 60
        val time = DateTimeFormatter.ofPattern("HH:mm").format(lastSeen.atZone(zone))
        val day = lastSeen.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return if (english) when {
            seconds < 60 -> "last seen just now"
            minutes < 60 -> "last seen $minutes min ago"
            hours < 12 && day == today -> "last seen $hours h ago"
            day == today -> "last seen today at $time"
            day == today.minusDays(1) -> "last seen yesterday at $time"
            else -> "last seen " + DateTimeFormatter.ofPattern("dd.MM.yyyy").format(day)
        } else when {
            seconds < 60 -> "был(а) только что"
            minutes < 60 -> "был(а) $minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
            hours < 12 && day == today -> "был(а) $hours ${plural(hours, "час", "часа", "часов")} назад"
            day == today -> "был(а) сегодня в $time"
            day == today.minusDays(1) -> "был(а) вчера в $time"
            else -> "был(а) " + DateTimeFormatter.ofPattern("dd.MM.yyyy").format(day)
        }
    }

    /** Русское склонение: 1 минуту, 2 минуты, 5 минут, 11 минут, 21 минуту. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /** Время сообщения «14:05». */
    fun clock(iso: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val ms = ChatMerge.timeOf(iso)
        if (ms == 0L) return ""
        return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    /** Разделитель дня в ленте: «Сегодня», «Вчера», «12 сентября», «12 сентября 2025». */
    fun dayTitle(day: LocalDate, today: LocalDate, english: Boolean): String = when {
        day == today -> if (english) "Today" else "Сегодня"
        day == today.minusDays(1) -> if (english) "Yesterday" else "Вчера"
        else -> {
            val month = if (english) englishMonths[day.monthValue - 1] else russianMonths[day.monthValue - 1]
            val base = if (english) "$month ${day.dayOfMonth}" else "${day.dayOfMonth} $month"
            if (day.year == today.year) base else "$base ${day.year}"
        }
    }

    private val russianMonths = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
        "сентября", "октября", "ноября", "декабря")
    private val englishMonths = listOf("January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December")
}
