package com.honerai.admin

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class AdminApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val container = AdminContainer.get(this)
        // Присутствие администратора и соединение следуют за видимостью приложения.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.onBackground()
        })
    }
}
