package com.honerai.app.device.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/** Android-помощник: включена ли служба агента в системе и как открыть её экран настроек. */
object AgentAccessibility {
    /** Служба Honer AI включена пользователем в «Специальные возможности». */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context.packageName, DeviceAgentService::class.java.name)
        val expectedFlat = expected.flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        if (enabled.isEmpty()) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expectedFlat, ignoreCase = true) ||
                component.equals(expected.flattenToShortString(), ignoreCase = true)) return true
        }
        return false
    }

    /** Открыть системный экран «Специальные возможности» (Android покажет собственное предупреждение). */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
