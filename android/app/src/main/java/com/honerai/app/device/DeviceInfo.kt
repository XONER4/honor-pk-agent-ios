package com.honerai.app.device

import android.content.Context
import android.os.Build

// ЗАГОТОВКА (модуль «Устройство»): сведения об устройстве для нейросети.

object DeviceInfo {
    /** «Samsung Galaxy S24 Ultra», «Xiaomi 14». */
    val modelName: String get() = Build.MANUFACTURER + " " + Build.MODEL
    val osDescription: String get() = "Android " + Build.VERSION.RELEASE
    val appVersion: String get() = com.honerai.app.BuildConfig.VERSION_NAME

    /** Блок для системной инструкции: устройство, город (если разрешён), часовой пояс, язык. */
    fun summary(context: Context): String = "\nУстройство: $modelName, $osDescription"
}
