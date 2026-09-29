package com.honerai.admin.core

import com.honerai.admin.data.AiSettings
import com.honerai.admin.data.AiWindow
import com.honerai.admin.data.ClientReport
import com.honerai.admin.data.DeviceDetail
import com.honerai.admin.data.Metrics
import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.Overview
import com.honerai.admin.data.Sender
import com.honerai.admin.data.ServerFrame
import com.honerai.admin.net.ApiClient
import com.honerai.admin.net.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsersState(
    val devices: Map<String, DeviceSummary> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class OverviewState(val overview: Overview? = null, val loading: Boolean = false, val error: String? = null)

/** Живые метрики: последний снимок, история для мини-графиков, ошибка запроса. */
data class MetricsState(val metrics: Metrics? = null, val history: MetricsHistory = MetricsHistory(), val error: String? = null)

/** Общий выключатель ИИ и расписание. */
data class AiState(val settings: AiSettings? = null, val busy: Boolean = false, val error: String? = null)

/** Последние ошибки/падения из приложений (главный экран). */
data class ReportsState(val reports: List<ClientReport> = emptyList(), val loaded: Boolean = false, val error: String? = null)

/**
 * Пользователи и счётчики админки. Первичные данные — REST, дальше живые поправки из кадров
 * presence / message / typing / read; раз в несколько секунд после всплеска событий — сверка с сервером.
 */
class AdminRepository(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val settings: AdminSettings,
) {
    private val _users = MutableStateFlow(UsersState())
    val users: StateFlow<UsersState> get() = _users

    private val _overview = MutableStateFlow(OverviewState())
    val overview: StateFlow<OverviewState> get() = _overview

    private val _metrics = MutableStateFlow(MetricsState())
    val metrics: StateFlow<MetricsState> get() = _metrics

    private val _ai = MutableStateFlow(AiState())
    val ai: StateFlow<AiState> get() = _ai

    private val _reports = MutableStateFlow(ReportsState())
    val reports: StateFlow<ReportsState> get() = _reports

    /** Открытый сейчас на экране чат: его сообщения не считаются непрочитанными. */
    @Volatile var visibleChatId: String? = null

    private var syncJob: Job? = null
    private var usersJob: Job? = null
    private var lastQuery = ""

    private val english get() = settings.english.value

    fun refreshAll(query: String = lastQuery) {
        refreshOverview()
        refreshUsers(query)
        refreshMetrics()
        refreshAi()
        refreshReports()
    }

    fun refreshMetrics() {
        scope.launch {
            try {
                onMetrics(api.metrics())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _metrics.update { it.copy(error = friendlyError(e, english)) }
            }
        }
    }

    private fun onMetrics(m: Metrics) {
        _metrics.update { it.copy(metrics = m, history = it.history.add(m, System.currentTimeMillis()), error = null) }
        // Счётчики «в сети»/«в фоне» заодно сверяем с сервером.
        _overview.update { s -> s.copy(overview = s.overview?.copy(online = m.online, inBackground = m.inBackground)) }
        val current = _ai.value.settings
        // Расписание переключило ИИ — перечитываем настройки, чтобы переключатель на экране был верным.
        if (current != null && (current.effective && current.configured) != m.model.enabled && !_ai.value.busy) refreshAi()
    }

    fun refreshAi() {
        scope.launch {
            try {
                val settings = api.aiSettings()
                _ai.update { it.copy(settings = settings, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ai.update { it.copy(error = friendlyError(e, english)) }
            }
        }
    }

    /** Включить/выключить ИИ и/или сохранить расписание; результат — ошибка для показа или null. */
    suspend fun updateAi(enabled: Boolean? = null, schedule: List<AiWindow>? = null): String? {
        _ai.update { it.copy(busy = true) }
        return try {
            val settings = api.updateAiSettings(enabled, schedule)
            _ai.value = AiState(settings)
            refreshMetrics()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = friendlyError(e, english)
            _ai.update { it.copy(busy = false, error = message) }
            message
        }
    }

    fun refreshReports() {
        scope.launch {
            try {
                val list = api.reports(limit = 5)
                _reports.value = ReportsState(list, loaded = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reports.update { it.copy(error = friendlyError(e, english)) }
            }
        }
    }

    fun refreshOverview() {
        _overview.update { it.copy(loading = true) }
        scope.launch {
            try {
                val o = api.overview()
                _overview.value = OverviewState(o)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _overview.update { it.copy(loading = false, error = friendlyError(e, english)) }
            }
        }
    }

    fun refreshUsers(query: String = lastQuery) {
        lastQuery = query
        _users.update { it.copy(loading = true) }
        usersJob?.cancel()
        usersJob = scope.launch {
            try {
                val list = api.devices(query.trim())
                // Поиск на сервере сужает выдачу; но уже известных пользователей не теряем — их отфильтрует экран.
                _users.update { state ->
                    val merged = if (query.isBlank()) list.associateBy { it.deviceId } else state.devices + list.associateBy { it.deviceId }
                    state.copy(devices = merged, loading = false, loaded = true, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _users.update { it.copy(loading = false, error = friendlyError(e, english)) }
            }
        }
    }

    fun device(deviceId: String): DeviceSummary? = _users.value.devices[deviceId]

    fun deviceByChat(chatId: String): DeviceSummary? = _users.value.devices.values.firstOrNull { it.adminChatId == chatId }

    /** Свежая карточка с сервера также обновляет строку списка. */
    fun putDetail(detail: DeviceDetail) {
        val summary = detail.summary()
        _users.update { it.copy(devices = it.devices + (summary.deviceId to summary)) }
    }

    fun markChatRead(chatId: String) = updateByChat(chatId) { it.copy(unreadForAdmin = 0) }

    fun setBlockedLocally(deviceId: String, blocked: Boolean) {
        val before = device(deviceId)?.blocked
        updateDevice(deviceId) { it.copy(blocked = blocked) }
        if (before != null && before != blocked) {
            _overview.update { s -> s.copy(overview = s.overview?.let { it.copy(blocked = (it.blocked + if (blocked) 1 else -1).coerceAtLeast(0)) }) }
        }
    }

    fun onFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.PresenceChanged -> {
                val current = device(frame.deviceId)
                if (current == null) {
                    scheduleSync(); return
                }
                _overview.update { s -> s.copy(overview = s.overview?.let { UserList.adjustOverview(it, current.presence, frame.state) }) }
                updateDevice(frame.deviceId) {
                    it.copy(presence = frame.state, typingIn = frame.typingIn, lastSeen = frame.lastSeen ?: it.lastSeen)
                }
            }
            is ServerFrame.NewMessage -> {
                _overview.update { s -> s.copy(overview = s.overview?.let { it.copy(messagesToday = it.messagesToday + 1) }) }
                if (frame.message.sender == Sender.USER) {
                    val known = deviceByChat(frame.chatId)
                    if (known == null) scheduleSync()
                    else updateByChat(frame.chatId) {
                        it.copy(
                            unreadForAdmin = if (visibleChatId == frame.chatId) 0 else it.unreadForAdmin + 1,
                            messagesSent = it.messagesSent + 1,
                            typingIn = null,
                        )
                    }
                }
            }
            is ServerFrame.Typing -> if (frame.who == Sender.USER) {
                updateByChat(frame.chatId) { it.copy(typingIn = if (frame.typing) frame.chatId else null) }
            }
            is ServerFrame.Read -> if (frame.who == Sender.ADMIN) markChatRead(frame.chatId)
            is ServerFrame.MetricsFrame -> onMetrics(frame.metrics)
            is ServerFrame.AiChanged -> _ai.update { it.copy(settings = frame.settings, error = null) }
            else -> Unit
        }
    }

    /** Сверка с сервером через 4 с после последнего непонятного события (новый пользователь и т. п.). */
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(4_000)
            refreshAll()
        }
    }

    private fun updateDevice(deviceId: String, change: (DeviceSummary) -> DeviceSummary) {
        _users.update { state ->
            val d = state.devices[deviceId] ?: return@update state
            state.copy(devices = state.devices + (deviceId to change(d)))
        }
    }

    private fun updateByChat(chatId: String, change: (DeviceSummary) -> DeviceSummary) {
        _users.update { state ->
            val d = state.devices.values.firstOrNull { it.adminChatId == chatId } ?: return@update state
            state.copy(devices = state.devices + (d.deviceId to change(d)))
        }
    }

    fun clear() {
        syncJob?.cancel()
        _users.value = UsersState()
        _overview.value = OverviewState()
        _metrics.value = MetricsState()
        _ai.value = AiState()
        _reports.value = ReportsState()
        visibleChatId = null
    }
}
