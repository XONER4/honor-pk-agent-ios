package com.honerai.app.ui.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.honerai.app.device.agent.AgentAccessibility
import com.honerai.app.ui.settings.SettingsDivider
import com.honerai.app.ui.settings.SettingsGroup
import com.honerai.app.ui.settings.SettingsRow
import com.honerai.app.ui.settings.SettingsToggleRow
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.rememberOnResume

/**
 * agent: Настройки → Разрешения → «Действия в приложениях». Главный переключатель функции и переход
 * к системному экрану «Специальные возможности», где включается служба (Android покажет своё предупреждение).
 */
@Composable
fun AgentSettingsGroup() {
    val context = LocalContext.current
    val settings = appSettings()
    val enabled by settings.agentEnabled.collectAsState()
    // Пересчитываем при возвращении из системных настроек.
    val serviceEnabled = rememberOnResume { AgentAccessibility.isServiceEnabled(context) }
    fun t(ru: String, en: String) = settings.text(ru, en)
    SettingsGroup(
        t("Действия в приложениях", "Actions in apps"),
        footer = t(
            "Когда включено, Honer AI по вашей просьбе выполняет задачи в приложениях, куда вы уже вошли сами: ищет товары, заполняет заказы, открывает чаты и меню. Honer AI никогда не вводит пароли и коды и всегда спрашивает подтверждение перед оплатой, отправкой, публикацией и удалением. Управление идёт через службу специальных возможностей Android — при её включении система покажет собственное предупреждение, это нормально.",
            "When on, Honer AI performs tasks at your request inside apps you are already signed into: searching products, filling orders, opening chats and menus. Honer AI never enters passwords or codes and always asks for confirmation before paying, sending, posting or deleting. It works through Android's accessibility service — Android shows its own warning when you enable it, which is expected.",
        ),
    ) {
        SettingsToggleRow(
            t("Действия в приложениях (агент)", "Actions in apps (agent)"), enabled,
            { settings.setAgentEnabled(it) },
            icon = Icons.Outlined.TouchApp, tag = "permissions.agentEnabled",
        )
        SettingsDivider()
        SettingsRow(
            Icons.Outlined.Accessibility, t("Служба специальных возможностей", "Accessibility service"),
            value = if (serviceEnabled) t("Включена", "Enabled") else t("Включить", "Enable"),
            subtitle = t("Нужна, чтобы Honer AI действовал в приложениях", "Lets Honer AI act inside apps"),
            enabled = enabled,
            tag = "permissions.agentAccessibility",
        ) {
            AgentAccessibility.openSettings(context)
        }
    }
}
