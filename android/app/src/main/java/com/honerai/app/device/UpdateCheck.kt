package com.honerai.app.device

// Сопоставление результата проверки обновлений с состоянием для экрана «О программе».
// Чистый Kotlin — проверяется JVM-тестами.

/** Итог проверки обновлений. */
enum class UpdateCheckStatus { UP_TO_DATE, UPDATE_AVAILABLE, CHECK_FAILED }

object UpdateCheck {
    /** По найденному выпуску: нет выпуска или он не новее — последняя версия; новее — есть обновление. */
    fun status(latest: UpdateInfo?, currentCode: Int, currentName: String): UpdateCheckStatus = when {
        latest == null -> UpdateCheckStatus.UP_TO_DATE
        UpdateReleases.isNewer(latest, currentCode, currentName) -> UpdateCheckStatus.UPDATE_AVAILABLE
        else -> UpdateCheckStatus.UP_TO_DATE
    }

    /** По результату сетевой проверки (например, [UpdateManager.checkNow]): ошибка — «не удалось проверить». */
    fun status(result: Result<UpdateInfo?>, currentCode: Int, currentName: String): UpdateCheckStatus =
        result.fold({ status(it, currentCode, currentName) }, { UpdateCheckStatus.CHECK_FAILED })
}
