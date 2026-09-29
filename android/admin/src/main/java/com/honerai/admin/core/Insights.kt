package com.honerai.admin.core

import com.honerai.admin.data.AiWindow
import com.honerai.admin.data.Metrics
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Точка истории метрик для мини-графиков (время — мс, значения — из кадра metrics). */
data class MetricsSample(
    val atMs: Long,
    val online: Int,
    val rps: Double,
    val tokensToday: Long,
    val errorsToday: Long,
    val aiP50: Long?,
)

/** Последние [max] снимков метрик (по кадру раз в 5 с — это 5 минут). Неизменяемая: add возвращает новую. */
data class MetricsHistory(val samples: List<MetricsSample> = emptyList(), val max: Int = 60) {
    fun add(m: Metrics, atMs: Long): MetricsHistory {
        val sample = MetricsSample(atMs, m.online, m.rps, m.tokensToday, m.errorsToday, m.aiLatencyMs.p50)
        // Кадр и ответ REST могут прийти почти одновременно — второй заменяет первый.
        val base = if (samples.isNotEmpty() && atMs - samples.last().atMs < 1_000) samples.dropLast(1) else samples
        return copy(samples = (base + sample).takeLast(max))
    }

    fun series(pick: (MetricsSample) -> Double): List<Double> = samples.map(pick)
}

object Numbers {
    /** 950 → «950», 12 345 → «12,3 тыс.» / «12.3K», 4 200 000 → «4,2 млн» / «4.2M». */
    fun compact(value: Long, english: Boolean): String {
        val abs = kotlin.math.abs(value)
        fun one(v: Double): String {
            val s = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
            return if (english) s else s.replace('.', ',')
        }
        return when {
            abs < 10_000 -> value.toString()
            abs < 1_000_000 -> one(value / 1_000.0) + if (english) "K" else " тыс."
            abs < 1_000_000_000 -> one(value / 1_000_000.0) + if (english) "M" else " млн"
            else -> one(value / 1_000_000_000.0) + if (english) "B" else " млрд"
        }
    }

    /** Запросов в секунду: «0», «0,35», «12». */
    fun rate(value: Double, english: Boolean): String {
        val s = when {
            value == 0.0 -> "0"
            value < 10 -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.0f", value)
        }
        return if (english) s else s.replace('.', ',')
    }

    /** Задержка: «850 мс», «2,4 с», null → «—». */
    fun latency(ms: Long?, english: Boolean): String = when {
        ms == null -> "—"
        ms < 1_000 -> "$ms " + if (english) "ms" else "мс"
        else -> {
            val s = String.format(Locale.US, "%.1f", ms / 1_000.0).removeSuffix(".0")
            (if (english) s else s.replace('.', ',')) + if (english) " s" else " с"
        }
    }
}

/** Расписание ИИ: проверка и подписи. */
object ScheduleText {
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val RU = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun isValidTime(value: String): Boolean = TIME.matches(value)

    fun dayName(day: Int, english: Boolean): String = (if (english) EN else RU).getOrElse(day - 1) { "?" }

    /** [1,2,3,4,5] → «Пн–Пт»; [6,7] → «Сб, Вс»; пусто или все → «Каждый день». */
    fun days(days: List<Int>, english: Boolean): String {
        val sorted = days.distinct().sorted()
        if (sorted.isEmpty() || sorted.size == 7) return if (english) "Every day" else "Каждый день"
        val runs = mutableListOf<IntRange>()
        for (d in sorted) {
            val last = runs.lastOrNull()
            if (last != null && last.last == d - 1) runs[runs.lastIndex] = last.first..d else runs.add(d..d)
        }
        return runs.joinToString(", ") { r ->
            when {
                r.first == r.last -> dayName(r.first, english)
                r.last - r.first == 1 -> dayName(r.first, english) + ", " + dayName(r.last, english)
                else -> dayName(r.first, english) + "–" + dayName(r.last, english)
            }
        }
    }

    /** «Пн–Пт, 09:00–18:00»; from == to → «весь день». */
    fun window(w: AiWindow, english: Boolean): String {
        val time = if (w.from == w.to) (if (english) "all day" else "весь день") else "${w.from}–${w.to}"
        return days(w.days, english) + ", " + time
    }
}

/** Срок блокировки из диалога. */
enum class BlockTerm(val duration: Duration?) {
    HOUR(Duration.ofHours(1)),
    DAY(Duration.ofDays(1)),
    WEEK(Duration.ofDays(7)),
    MONTH(Duration.ofDays(30)),
    FOREVER(null);

    /** ISO-время окончания или null (навсегда). */
    fun until(now: Instant): String? = duration?.let { now.plus(it).toString() }

    fun label(english: Boolean): String = when (this) {
        HOUR -> if (english) "1 hour" else "1 час"
        DAY -> if (english) "1 day" else "1 день"
        WEEK -> if (english) "7 days" else "7 дней"
        MONTH -> if (english) "30 days" else "30 дней"
        FOREVER -> if (english) "Forever" else "Навсегда"
    }
}

/** Подписи событий и действий. */
object HistoryText {
    fun event(kind: String, from: String?, to: String?, english: Boolean): String = when (kind) {
        "install" -> (if (english) "Installed" else "Установка") + (to?.let { " v$it" } ?: "")
        "update" -> (if (english) "Update" else "Обновление") + " " + listOfNotNull(from?.let { "v$it" }, to?.let { "v$it" }).joinToString(" → ")
        "uninstall" -> if (english) "Probably uninstalled" else "Вероятно, удалено"
        "open" -> if (english) "Opened" else "Открыто"
        else -> kind
    }.trim()

    fun action(action: String, english: Boolean): String = when (action) {
        "block" -> if (english) "Blocked" else "Заблокирован"
        "unblock" -> if (english) "Unblocked" else "Разблокирован"
        "unblock_auto" -> if (english) "Block expired" else "Срок блокировки истёк"
        "notify" -> if (english) "Notification sent" else "Отправлено уведомление"
        "broadcast" -> if (english) "Broadcast" else "Рассылка всем"
        "overrides" -> if (english) "Restrictions changed" else "Изменены ограничения"
        "ai_settings" -> if (english) "AI settings changed" else "Изменены настройки ИИ"
        "admin_setup" -> if (english) "Admin account created" else "Создан аккаунт администратора"
        else -> action
    }

    /** «пользователям» с числом: 1 пользователю, 2 пользователям, 5 пользователям. */
    fun usersDative(count: Int, english: Boolean): String =
        if (english) "$count " + (if (count == 1) "user" else "users")
        else "$count " + (if (count % 10 == 1 && count % 100 != 11) "пользователю" else "пользователям")
}
