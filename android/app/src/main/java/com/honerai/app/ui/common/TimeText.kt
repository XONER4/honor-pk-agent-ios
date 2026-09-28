package com.honerai.app.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Подписи времени как на iPhone: «2 минуты назад», «Размышлял 5 секунд». */
object TimeText {
    /** Русская форма числительного: 1 минуту/минута, 2 минуты, 5 минут. */
    fun russianPlural(value: Long, one: String, few: String, many: String): String {
        val n = kotlin.math.abs(value)
        val last = n % 10
        val lastTwo = n % 100
        return when {
            last == 1L && lastTwo != 11L -> one
            last in 2L..4L && lastTwo !in 12L..14L -> few
            else -> many
        }
    }

    /** «1 секунду», «2 секунды», «5 секунд» — для счётчика рассуждения. */
    fun secondsWord(seconds: Int): String = russianPlural(seconds.toLong(), "секунду", "секунды", "секунд")

    fun secondsText(seconds: Int, english: Boolean): String =
        if (english) "${seconds}s" else "$seconds ${secondsWord(seconds)}"

    /** «Размышлял 5 секунд» / «Thought for 5s»; переведённое рассуждение подписывается иначе. */
    fun finishedReasoningTitle(seconds: Int, translated: Boolean, english: Boolean): String {
        if (translated) return if (english) "Reasoning description · translation" else "Описание рассуждения · перевод"
        val value = maxOf(seconds, 1)
        return if (english) "Thought for ${value}s" else "Размышлял $value ${secondsWord(value)}"
    }

    /**
     * Время последнего сообщения в списке чатов: «только что», «2 минуты назад»,
     * «3 часа назад», «12 дней назад» — одна крупнейшая единица, как у RelativeDateTimeFormatter.
     */
    fun relative(date: Instant, now: Instant = Instant.now(), english: Boolean = false,
                 zone: ZoneId = ZoneId.systemDefault()): String {
        val seconds = Duration.between(date, now).seconds
        if (seconds < 45) return if (english) "just now" else "только что"
        val from = date.atZone(zone)
        val to = now.atZone(zone)
        val years = ChronoUnit.YEARS.between(from, to)
        val months = ChronoUnit.MONTHS.between(from, to)
        val days = ChronoUnit.DAYS.between(from, to)
        val hours = ChronoUnit.HOURS.between(from, to)
        val minutes = ChronoUnit.MINUTES.between(from, to)
        return when {
            years > 0 -> unit(years, english, "год", "года", "лет", "year")
            months > 0 -> unit(months, english, "месяц", "месяца", "месяцев", "month")
            days >= 7 -> unit(days / 7, english, "неделю", "недели", "недель", "week")
            days > 0 -> unit(days, english, "день", "дня", "дней", "day")
            hours > 0 -> unit(hours, english, "час", "часа", "часов", "hour")
            minutes > 0 -> unit(minutes, english, "минуту", "минуты", "минут", "minute")
            else -> unit(seconds, english, "секунду", "секунды", "секунд", "second")
        }
    }

    private fun unit(value: Long, english: Boolean, one: String, few: String, many: String, en: String): String =
        if (english) "$value $en${if (value == 1L) "" else "s"} ago"
        else "$value ${russianPlural(value, one, few, many)} назад"

    /** Время «14:05» в часовом поясе телефона. */
    fun clock(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm").format(date.atZone(zone))

    /** Дата и время «12 мар. 2026 г., 14:05» на языке приложения. */
    fun dateTime(date: Instant, english: Boolean, style: FormatStyle = FormatStyle.MEDIUM): String =
        DateTimeFormatter.ofLocalizedDateTime(style, FormatStyle.SHORT)
            .withLocale(if (english) Locale.ENGLISH else Locale("ru", "RU"))
            .format(date.atZone(ZoneId.systemDefault()))

    fun shortDateTime(date: Instant, english: Boolean): String = dateTime(date, english, FormatStyle.SHORT)
}
