package com.honerai.admin.ui.chat

import com.honerai.admin.core.ChatItem
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Строки ленты: разделитель дня или сообщение с отметками группы (подряд от одного отправителя). */
sealed interface ChatRow {
    val key: String

    data class Day(val date: LocalDate, val label: String) : ChatRow {
        override val key: String get() = "day:$date"
    }

    data class Msg(val item: ChatItem, val firstInGroup: Boolean, val lastInGroup: Boolean, val time: Instant?) : ChatRow {
        override val key: String get() = "m:" + item.key
    }
}

object ChatRows {
    private const val GROUP_GAP_SECONDS = 180

    /** Строки в хронологическом порядке (для reverseLayout их переворачивают). */
    fun build(items: List<ChatItem>, zone: ZoneId, today: LocalDate, english: Boolean): List<ChatRow> {
        val rows = ArrayList<ChatRow>(items.size + 8)
        var lastDay: LocalDate? = null
        val times = items.map { Times.parse(it.message.createdAt) }
        items.forEachIndexed { i, item ->
            val time = times[i]
            val day = (time ?: Instant.now()).atZone(zone).toLocalDate()
            if (day != lastDay) {
                rows += ChatRow.Day(day, PresenceText.dayHeader(day, today, english))
                lastDay = day
            }
            val prev = items.getOrNull(i - 1)
            val next = items.getOrNull(i + 1)
            val first = prev == null || !sameGroup(prev, times[i - 1], item, time, zone)
            val last = next == null || !sameGroup(item, time, next, times[i + 1], zone)
            rows += ChatRow.Msg(item, first, last, time)
        }
        return rows
    }

    private fun sameGroup(a: ChatItem, ta: Instant?, b: ChatItem, tb: Instant?, zone: ZoneId): Boolean {
        if (a.message.sender != b.message.sender) return false
        if (ta == null || tb == null) return true
        if (ta.atZone(zone).toLocalDate() != tb.atZone(zone).toLocalDate()) return false
        return kotlin.math.abs(tb.epochSecond - ta.epochSecond) <= GROUP_GAP_SECONDS
    }
}
