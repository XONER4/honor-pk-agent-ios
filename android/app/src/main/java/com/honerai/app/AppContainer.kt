package com.honerai.app

import android.app.ActivityManager
import android.content.Context
import com.honerai.app.core.AppSettings
import com.honerai.app.core.ChatStore
import com.honerai.app.core.ChatStoreApi

/**
 * Общие объекты приложения: настройки и хранилище чатов.
 * Создаются один раз в [HonerApp] и доступны экранам через [AppContainer.get].
 */
class AppContainer(val context: Context) {
    val settings = AppSettings(context)
    val store: ChatStoreApi = ChatStore(context, settings)

    /** Слабый телефон: мало памяти — меньше анимаций и эффектов, чтобы не было подтормаживаний. */
    val isLowEndDevice: Boolean = run {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.isLowRamDevice || manager.memoryClass <= 192
    }

    companion object {
        @Volatile private var instance: AppContainer? = null
        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
    }
}
