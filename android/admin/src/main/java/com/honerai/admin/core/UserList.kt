package com.honerai.admin.core

import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.Overview
import com.honerai.admin.data.Presence

/** Фильтры списка пользователей: Все / В сети / В фоне / Заблокированы. */
enum class UserFilter { ALL, ONLINE, BACKGROUND, BLOCKED }

/** Чистая логика списка пользователей и живых счётчиков — тестируется без Android. */
object UserList {

    /** Фильтр, поиск по имени/модели и порядок: непрочитанные, в сети, в фоне, затем по времени визита. */
    fun visible(devices: Collection<DeviceSummary>, filter: UserFilter, query: String): List<DeviceSummary> {
        val needle = query.trim().lowercase()
        return devices.asSequence()
            .filter {
                when (filter) {
                    UserFilter.ALL -> true
                    UserFilter.ONLINE -> it.presence == Presence.FOREGROUND && !it.blocked
                    UserFilter.BACKGROUND -> it.presence == Presence.BACKGROUND && !it.blocked
                    UserFilter.BLOCKED -> it.blocked
                }
            }
            .filter {
                needle.isEmpty() || it.displayName.lowercase().contains(needle) || it.deviceModel.lowercase().contains(needle) ||
                    it.deviceName.lowercase().contains(needle) || it.deviceId.lowercase().startsWith(needle)
            }
            .sortedWith(
                compareByDescending<DeviceSummary> { it.unreadForAdmin > 0 }
                    .thenBy { presenceRank(it.presence) }
                    .thenByDescending { Times.parse(it.lastSeen)?.toEpochMilli() ?: 0L },
            )
            .toList()
    }

    private fun presenceRank(presence: String): Int = when (presence) {
        Presence.FOREGROUND -> 0
        Presence.BACKGROUND -> 1
        else -> 2
    }

    /** Живая поправка счётчиков «в сети» / «в фоне», когда пользователь сменил состояние. */
    fun adjustOverview(overview: Overview, from: String, to: String): Overview {
        if (from == to) return overview
        var online = overview.online
        var background = overview.inBackground
        when (from) {
            Presence.FOREGROUND -> online--
            Presence.BACKGROUND -> background--
        }
        when (to) {
            Presence.FOREGROUND -> online++
            Presence.BACKGROUND -> background++
        }
        return overview.copy(online = online.coerceAtLeast(0), inBackground = background.coerceAtLeast(0))
    }
}
