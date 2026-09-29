package com.honerai.app.extras.device

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.honerai.app.extras.lock.AppLock
import com.honerai.app.ui.settings.SettingsDivider
import com.honerai.app.ui.settings.SettingsGroup
import com.honerai.app.ui.settings.SettingsRow
import com.honerai.app.ui.settings.SettingsToggleRow
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.rememberOnResume

/** Настройки → Разрешения: доступ ИИ к данным телефона и статистике использования. */
@Composable
fun DeviceAccessSettingsGroup() {
    val context = LocalContext.current
    val settings = appSettings()
    val enabled by DeviceAccess.enabled.collectAsState()
    val usageGranted = rememberOnResume { PhoneDataProbe.hasUsageAccess(context) }
    fun t(ru: String, en: String) = settings.text(ru, en)
    SettingsGroup(
        t("Телефон", "Phone"),
        footer = t(
            "Когда включено, Honer AI по вашей просьбе делает скриншоты и запись экрана (каждый раз с подтверждением Android), проверяет заряд, память, сеть и нагрев, смотрит список приложений, число контактов и экранное время. Эти данные уходят нейросети только вместе с вашим вопросом. Будильник и таймер работают всегда — их подтверждаете вы в «Часах». Звонки и SMS приложение не читает.",
            "When on, Honer AI can take screenshots and screen recordings at your request (Android asks every time), check battery, memory, network and heat, and see the app list, contact count and screen time. This data goes to the AI only with your question. Alarms and timers always work — you confirm them in Clock. Calls and SMS are never read.",
        ),
    ) {
        SettingsToggleRow(
            t("Доступ ИИ к данным и состоянию телефона", "AI access to phone data and status"), enabled,
            { DeviceAccess.setEnabled(context, it) },
            icon = Icons.Outlined.Smartphone, tag = "permissions.deviceAccess",
        )
        SettingsDivider()
        SettingsRow(
            Icons.Outlined.QueryStats, t("Статистика использования", "Usage access"),
            value = if (usageGranted) t("Разрешено", "Allowed") else t("Открыть", "Open"),
            subtitle = t("Для вопроса «сколько я сегодня в телефоне»", "For \"how long was I on my phone today\""),
            tag = "permissions.usageAccess",
        ) {
            AppLock.allowTrip(10 * 60_000L)
            runCatching { context.startActivity(PhoneDataProbe.usageAccessIntent(context)) }
                .onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}
