package com.honerai.admin.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Модели «аналитики и управления» (server/API.md → Admin: metrics, AI switch, reports, history, notes, overrides).
 * Все поля с умолчаниями: старый сервер без новых полей не ломает разбор.
 */

@Serializable
data class SetupStatus(val hasAccount: Boolean = false)

@Serializable
data class LatencyStats(val p50: Long? = null, val p95: Long? = null, val avg: Long? = null, val count: Int = 0)

@Serializable
data class ModelState(
    val enabled: Boolean = true,
    val switchedOn: Boolean = true,
    val scheduled: Boolean = false,
    val configured: Boolean = true,
    val name: String = "",
)

/** Расход ИИ: токены (запрос/ответ), запросы и ошибки. */
@Serializable
data class Usage(
    val prompt: Long = 0,
    val completion: Long = 0,
    val total: Long = 0,
    val requests: Long = 0,
    val errors: Long = 0,
)

@Serializable
data class DeviceUsage(val today: Usage = Usage(), val total: Usage = Usage())

/** GET /v1/admin/metrics и WS-кадр {"t":"metrics"}. */
@Serializable
data class Metrics(
    val at: String = "",
    val online: Int = 0,
    val inBackground: Int = 0,
    val rps: Double = 0.0,
    val requests1m: Int = 0,
    val errors1m: Int = 0,
    val tokensToday: Long = 0,
    val tokensTotal: Long = 0,
    val aiRequestsToday: Long = 0,
    val aiErrorsToday: Long = 0,
    val reportsToday: Long = 0,
    val errorsToday: Long = 0,
    val aiLatencyMs: LatencyStats = LatencyStats(),
    val apiLatencyMs: LatencyStats = LatencyStats(),
    val model: ModelState = ModelState(),
)

/** Окно расписания ИИ: дни 1..7 (пн..вс; пусто — каждый день), время «HH:MM». */
@Serializable
data class AiWindow(val days: List<Int> = emptyList(), val from: String = "09:00", val to: String = "18:00")

@Serializable
data class AiSettings(
    val enabled: Boolean = true,
    val schedule: List<AiWindow> = emptyList(),
    val timezone: String = "Europe/Moscow",
    val effective: Boolean = true,
    val configured: Boolean = true,
)

/** Ошибка или падение из приложения пользователя. */
@Serializable
data class ClientReport(
    val id: String,
    val deviceId: String = "",
    val publicId: String? = null,
    val displayName: String? = null,
    val deviceModel: String? = null,
    val kind: String = ReportKinds.ERROR,
    val message: String = "",
    val stack: String? = null,
    val appVersion: String? = null,
    val at: String = "",
)

object ReportKinds {
    const val ERROR = "error"
    const val CRASH = "crash"
}

@Serializable
data class DeviceEvent(
    val id: String,
    val deviceId: String = "",
    val kind: String = "",
    val fromVersion: String? = null,
    val toVersion: String? = null,
    val at: String = "",
)

@Serializable
data class AdminNote(
    val id: String,
    val deviceId: String = "",
    val adminId: String? = null,
    val adminName: String? = null,
    val text: String = "",
    val createdAt: String = "",
    val updatedAt: String? = null,
)

@Serializable
data class AdminAction(
    val id: String,
    val adminId: String? = null,
    val adminName: String? = null,
    val deviceId: String? = null,
    val action: String = "",
    val detail: JsonElement? = null,
    val at: String = "",
)

/** Персональные ограничения пользователя (приложение применяет их само). */
@Serializable
data class Overrides(
    val forceLanguage: String? = null,
    val disableSearch: Boolean? = null,
    val maxMessagesPerDay: Int? = null,
)

@Serializable
data class OverridesResponse(val overrides: Overrides = Overrides())
