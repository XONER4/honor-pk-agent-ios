package com.honerai.admin.core

import com.honerai.admin.data.Presence
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Разбор времени ISO-8601 с сервера: «…Z» и «…+03:00»; мусор → null. */
object Times {
    fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    fun nowIso(): String = Instant.now().toString()
}

/**
 * Подписи присутствия и времени «как в Telegram»: «в сети», «в фоне», «был(а) 5 мин назад»,
 * «был(а) вчера в 21:04». Чистые функции: время и пояс передаются, поэтому легко тестируются.
 */
object PresenceText {
    private val ruMonths = arrayOf("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    /** Статус в шапке чата и в карточке. */
    fun status(presence: String, lastSeen: Instant?, now: Instant, zone: ZoneId, english: Boolean): String = when (presence) {
        Presence.FOREGROUND -> if (english) "online" else "в сети"
        Presence.BACKGROUND -> if (english) "app in background" else "в фоне"
        else -> lastSeen(lastSeen, now, zone, english)
    }

    /** «был(а) только что», «был(а) 5 мин назад», «был(а) сегодня в 14:05», «был(а) вчера в 21:04», «был(а) 3 мар. в 09:15». */
    fun lastSeen(lastSeen: Instant?, now: Instant, zone: ZoneId, english: Boolean): String {
        if (lastSeen == null) return if (english) "last seen a long time ago" else "был(а) давно"
        val prefix = if (english) "last seen " else "был(а) "
        return prefix + ago(lastSeen, now, zone, english)
    }

    /** Относительное время без префикса: «только что», «5 мин назад», «вчера в 21:04». */
    fun ago(time: Instant, now: Instant, zone: ZoneId, english: Boolean): String {
        val seconds = now.epochSecond - time.epochSecond
        if (seconds < 60) return if (english) "just now" else "только что"
        val minutes = seconds / 60
        if (minutes < 60) return if (english) "$minutes min ago" else "$minutes мин назад"
        val day = time.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val at = clock.format(time.atZone(zone))
        return when (day) {
            today -> if (english) "today at $at" else "сегодня в $at"
            today.minusDays(1) -> if (english) "yesterday at $at" else "вчера в $at"
            else -> (if (english) "${shortDate(day, today, true)} at $at" else "${shortDate(day, today, false)} в $at")
        }
    }

    /** Дата «3 мар.» (в этом году) или «03.03.2024». */
    fun shortDate(day: LocalDate, today: LocalDate, english: Boolean): String = when {
        day.year != today.year -> DateTimeFormatter.ofPattern("dd.MM.yyyy").format(day)
        english -> DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).format(day)
        else -> "${day.dayOfMonth} ${ruMonths[day.monthValue - 1]}"
    }

    /** Время сообщения в пузыре: «21:04». */
    fun clockOf(time: Instant?, zone: ZoneId): String = time?.let { clock.format(it.atZone(zone)) } ?: ""

    /** Разделитель дней в чате: «Сегодня», «Вчера», «3 мар.», «03.03.2024». */
    fun dayHeader(day: LocalDate, today: LocalDate, english: Boolean): String = when (day) {
        today -> if (english) "Today" else "Сегодня"
        today.minusDays(1) -> if (english) "Yesterday" else "Вчера"
        else -> shortDate(day, today, english)
    }

    /** Полные дата и время для карточки: «12.03.2026, 14:05». */
    fun dateTime(time: Instant?, zone: ZoneId): String =
        time?.let { DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").format(it.atZone(zone)) } ?: "—"

    /** Время в приложении: секунды → «45 мин», «3 ч 5 мин». */
    fun duration(seconds: Long, english: Boolean): String {
        val minutes = seconds / 60
        if (minutes < 60) return if (english) "$minutes min" else "$minutes мин"
        val h = minutes / 60
        val m = minutes % 60
        return if (english) "$h h $m min" else "$h ч $m мин"
    }

    /** Размер файла: «340 КБ», «12,4 МБ». */
    fun fileSize(bytes: Long, english: Boolean): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            bytes < 1024 -> if (english) "$bytes B" else "$bytes Б"
            kb < 1024 -> if (english) "${kb.toInt()} KB" else "${kb.toInt()} КБ"
            else -> {
                val text = String.format(Locale.US, "%.1f", mb).let { if (english) it else it.replace('.', ',') }
                if (english) "$text MB" else "$text МБ"
            }
        }
    }

    /** Длительность голосового: 65 000 мс → «1:05». */
    fun mmss(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
