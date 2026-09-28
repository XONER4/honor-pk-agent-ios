package com.honerai.app.device

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ЗАГОТОВКА (модуль «Устройство»): автообновление.

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

object UpdateManager {
    /** Найденное обновление (или null). */
    val available: StateFlow<UpdateInfo?> = MutableStateFlow(null)
    /** Ход загрузки 0…1 или null. */
    val progress: StateFlow<Float?> = MutableStateFlow(null)
    suspend fun checkNow(context: Context): UpdateInfo? = null
    /** Скачать и установить; приложение перезапустится после обновления. */
    suspend fun installNow(context: Context) {}
}
