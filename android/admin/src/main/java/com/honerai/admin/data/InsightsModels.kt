package com.honerai.admin.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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

/** Персональные ограничения пользователя (приложение применяет их само, мут/поддержку — сервер). */
@Serializable
data class Overrides(
    val forceLanguage: String? = null,
    val disableSearch: Boolean? = null,
    val maxMessagesPerDay: Int? = null,
    // muteAi/blockSupport могут прийти как boolean (true=навсегда) или объект {until, reason}.
    val muteAi: JsonElement? = null,
    val blockSupport: JsonElement? = null,
) {
    val mute: Restriction get() = Restriction.of(muteAi)
    val support: Restriction get() = Restriction.of(blockSupport)
}

/** Состояние ограничения (мут/запрет поддержки): активно ли, до когда, причина. */
data class Restriction(val active: Boolean, val until: String? = null, val reason: String? = null) {
    companion object {
        fun of(value: JsonElement?): Restriction {
            if (value == null || value is JsonNull) return Restriction(false)
            (value as? JsonPrimitive)?.let { p ->
                return if (p.booleanOrNull == true || (p.isString && p.content.isNotBlank())) Restriction(true) else Restriction(false)
            }
            (value as? JsonObject)?.let { o ->
                val until = (o["until"] as? JsonPrimitive)?.contentOrNull
                val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull
                return Restriction(true, until, reason)
            }
            return Restriction(false)
        }
    }
}

@Serializable
data class OverridesResponse(val overrides: Overrides = Overrides())

/** Статус поддержки (GET /v1/admin/support-stats): в сети + среднее время ответа. */
@Serializable
data class SupportStats(
    val online: Boolean = false,
    val lastOnlineAt: String? = null,
    val avgResponseSeconds: Int? = null,
    val samples: Int = 0,
)

/** Результат перевода (POST /v1/admin/translate). */
@Serializable
data class TranslateResult(val text: String = "", val translated: Boolean = false)

/** Сообщение общего чата команды (GET/POST /v1/admin/staff/messages). */
@Serializable
data class StaffMessage(
    val id: String = "",
    val seq: Long = 0,
    val adminId: String = "",
    val adminName: String = "",
    val adminRole: String = "admin",
    val text: String = "",
    val createdAt: String = "",
    val mine: Boolean = false,
)

/** Непрочитанные в чате команды. */
@Serializable
data class StaffUnread(val unread: Int = 0, val lastSeq: Long = 0)

/** Аккаунт администратора (GET /v1/admin/account, /v1/admin/admins). */
@Serializable
data class AdminAccount(
    val adminId: String = "",
    val email: String = "",
    val name: String = "",
    val login: String? = null,
    val role: String = "admin",
    val hasPassword: Boolean = false,
    val lastLoginAt: String? = null,
    val createdAt: String? = null,
    val presence: String? = null,
)

/** Запись журнала входов в админку (GET /v1/admin/logins). */
@Serializable
data class AdminLogin(
    val id: String,
    val adminId: String? = null,
    val adminName: String? = null,
    val loginTried: String? = null,
    val success: Boolean = false,
    val method: String? = null,
    val ip: String? = null,
    val userAgent: String? = null,
    val at: String = "",
)

/** Кто смотрел карточку пользователя (GET /v1/admin/devices/:id/profile-views). */
@Serializable
data class ProfileView(
    val id: String,
    val adminId: String = "",
    val adminName: String? = null,
    val adminRole: String? = null,
    val deviceId: String = "",
    val at: String = "",
)

/** Строка общей ленты действий (GET /v1/admin/activity). kind: admin | device | login. */
@Serializable
data class ActivityItem(
    val kind: String = "",
    val at: String = "",
    val action: String = "",
    val who: String = "",
    val deviceId: String? = null,
    val publicId: String? = null,
    val target: String? = null,
    val detail: JsonElement? = null,
)
