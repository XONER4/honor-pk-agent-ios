package com.honerai.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.honerai.app.ui.HonerRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    /** Файлы и текст, присланные в Honer AI через «Поделиться». */
    private val _sharedIntent = MutableStateFlow<Intent?>(null)
    val sharedIntent: StateFlow<Intent?> get() = _sharedIntent

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferHighestRefreshRate()
        handleIntent(intent)
        setContent { HonerRoot(activity = this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    fun consumeSharedIntent() { _sharedIntent.value = null }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) _sharedIntent.value = intent
    }

    /**
     * Самая высокая частота обновления экрана, какую умеет телефон (90/120/144 Гц),
     * при том же разрешении — печать ответа и прокрутка идут плавнее.
     * На слабых телефонах и при экономии заряда система сама ограничит частоту.
     */
    @Suppress("DEPRECATION")
    private fun preferHighestRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay) ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.refreshRate > current.refreshRate + 1) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }
}
