package com.honerai.app

import android.app.Application
import androidx.work.Configuration
import com.honerai.app.device.HonerNotifications
import com.honerai.app.device.UpdateScheduler

class HonerApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        val container = AppContainer.get(this)
        HonerNotifications.createChannels(this)
        UpdateScheduler.schedule(this, enabled = container.settings.autoUpdate.value)
    }
}
