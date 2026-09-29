package com.honerai.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.honerai.app.core.AppIntegrations
import com.honerai.app.core.Integrations

// media: интеграции с приложениями на телефоне — Google, Яндекс, кошельки, установленные программы.

/** Группа «Приложения на телефоне» на странице «Интеграции» (тот же вид, что у интернет-интеграций). */
@Composable
internal fun AppIntegrationsGroup() {
    val context = LocalContext.current
    val settings = appSettings()
    fun t(ru: String, en: String) = settings.text(ru, en)
    var disabled by remember { mutableStateOf(Integrations.disabled(context)) }
    var immediately by remember { mutableStateOf(AppIntegrations.opensImmediately(context)) }
    SettingsGroup(
        title = t("Приложения на телефоне", "Apps on the phone"),
        footer = t(
            "Нейросеть готовит кнопку «Открыть …» — приложение открывается только по вашему нажатию. Кошельки и банки только открываются: платить и переводить деньги нейросеть не может. Работает и без кнопки «Поиск».",
            "The AI prepares an “Open …” button — the app opens only when you tap it. Wallets and banks are only opened: the AI can never pay or transfer money. Works without the Search button.",
        ),
    ) {
        AppIntegrations.services.forEachIndexed { index, service ->
            if (index > 0) SettingsDivider(62)
            val (icon, tint) = when (service.id) {
                AppIntegrations.GOOGLE -> Icons.Outlined.TravelExplore to Color(0xFF4285F4)
                AppIntegrations.YANDEX -> Icons.Outlined.Map to Color(0xFFFC3F1D)
                AppIntegrations.PAYMENTS -> Icons.Outlined.AccountBalanceWallet to Color(0xFF34A853)
                else -> Icons.Outlined.Apps to Color(0xFF8E8E93)
            }
            SettingsToggleRow(
                if (settings.isEnglish) service.nameEN else service.nameRU, service.id !in disabled,
                { on ->
                    Integrations.setEnabled(context, service.id, on)
                    disabled = Integrations.disabled(context)
                },
                icon = icon, iconTint = tint,
                subtitle = if (settings.isEnglish) service.descriptionEN else service.descriptionRU,
                tag = "integration.${service.id}",
            )
        }
        SettingsDivider(62)
        SettingsToggleRow(
            t("Открывать сразу", "Open immediately"), immediately,
            { on ->
                AppIntegrations.setOpensImmediately(context, on)
                immediately = on
            },
            icon = Icons.AutoMirrored.Outlined.OpenInNew, iconTint = Color(0xFFFF9500),
            subtitle = t("Не ждать нажатия на кнопку «Открыть …» (кроме кошельков — их всегда открываете вы).",
                "Don't wait for a tap on “Open …” (except wallets — you always open those yourself)."),
            tag = "integration.open_immediately",
        )
    }
}
