package com.honerai.admin

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.honerai.admin.core.AdminNotifications
import com.honerai.admin.ui.AdminRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Куда перейти по нажатию на уведомление. */
data class OpenChatRequest(val chatId: String, val deviceId: String?)

class MainActivity : ComponentActivity() {
    private val _openChat = MutableStateFlow<OpenChatRequest?>(null)
    val openChat: StateFlow<OpenChatRequest?> get() = _openChat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferHighestRefreshRate()
        handleIntent(intent)
        setContent { AdminRoot(activity = this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    fun consumeOpenChat() { _openChat.value = null }

    private fun handleIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(AdminNotifications.EXTRA_CHAT_ID) ?: return
        _openChat.value = OpenChatRequest(chatId, intent.getStringExtra(AdminNotifications.EXTRA_DEVICE_ID))
        AdminNotifications.cancel(this, chatId)
    }

    /** Самая высокая частота экрана (90/120/144 Гц) при том же разрешении — прокрутка плавнее. */
    @Suppress("DEPRECATION")
    private fun preferHighestRefreshRate() {
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
