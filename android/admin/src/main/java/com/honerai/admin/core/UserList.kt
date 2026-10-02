package com.honerai.admin.core

import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.Overview
import com.honerai.admin.data.Presence

/** Фильтры списка пользователей: Все / В сети / В фоне / Заблокированы / Удалившие. */
enum class UserFilter { ALL, ONLINE, BACKGROUND, BLOCKED, DELETED }

/** Порядок списка: по активности (как раньше) или по расходу токенов ИИ. */
enum class UserSort { ACTIVITY, TOKENS }

/** Чистая логика списка пользователей и живых счётчиков — тестируется без Android. */
object UserList {

    /**
     * Фильтр, поиск по имени/модели/ID («0427» или «#0427») и порядок: непрочитанные, в сети, в фоне,
     * затем по времени визита; [sort] = TOKENS — сначала те, кто потратил больше токенов.
     */
    fun visible(
        devices: Collection<DeviceSummary>, filter: UserFilter, query: String,
        sort: UserSort = UserSort.ACTIVITY, pinned: Set<String> = emptySet(),
    ): List<DeviceSummary> {
        val needle = query.trim().lowercase()
        val idNeedle = needle.removePrefix("#").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        return devices.asSequence()
            .filter {
                when (filter) {
                    // Удалившие приложение отделены от активных пользователей (у которых оно установлено).
                    UserFilter.ALL -> !it.deleted
                    UserFilter.ONLINE -> it.presence == Presence.FOREGROUND && !it.blocked && !it.deleted
                    UserFilter.BACKGROUND -> it.presence == Presence.BACKGROUND && !it.blocked && !it.deleted
                    UserFilter.BLOCKED -> it.blocked
                    UserFilter.DELETED -> it.deleted
                }
            }
            .filter {
                needle.isEmpty() || it.displayName.lowercase().contains(needle) || it.deviceModel.lowercase().contains(needle) ||
                    it.deviceName.lowercase().contains(needle) || it.deviceId.lowercase().startsWith(needle) ||
                    (idNeedle != null && it.publicId?.contains(idNeedle) == true)
            }
            .sortedWith(
                // Закреплённые — всегда вверху; дальше обычный порядок.
                compareByDescending<DeviceSummary> { it.deviceId in pinned }.then(
                    if (sort == UserSort.TOKENS) {
                        compareByDescending<DeviceSummary> { it.aiTokens }
                            .thenBy { presenceRank(it.presence) }
                    } else {
                        compareByDescending<DeviceSummary> { it.unreadForAdmin > 0 }
                            .thenBy { presenceRank(it.presence) }
                            .thenByDescending { Times.parse(it.lastSeen)?.toEpochMilli() ?: 0L }
                    },
                ),
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
