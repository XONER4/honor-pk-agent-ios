package com.honerai.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.honerai.app.device.UpdateManager

/**
 * Настройки → Разрешения → «Работа в фоне и обновления».
 * Системные возможности, которые нельзя выдать обычным запросом — их пользователь включает на
 * системном экране (кнопка ведёт прямо туда). Нужны, чтобы связь, уведомления и агент не «засыпали»
 * в фоне (особенно на vivo/Xiaomi, где фон агрессивно выгружается) и чтобы приложение само ставило обновления.
 */
@Composable
fun SystemAccessSettingsGroup() {
    val context = LocalContext.current
    val settings = appSettings()
    fun t(ru: String, en: String) = settings.text(ru, en)

    // Пересчитываем при возвращении с системного экрана.
    val installAllowed = rememberOnResume { UpdateManager.refreshInstallPermission(context) }
    val batteryUnrestricted = rememberOnResume { isBatteryUnrestricted(context) }

    SettingsGroup(
        t("Работа в фоне и обновления", "Background & updates"),
        footer = t(
            "Эти возможности включаются на системном экране — нажмите, и Android откроет нужное окно, где вы подтвердите. Без работы в фоне телефон может «усыплять» Honer AI: ответы и уведомления начнут приходить с задержкой. Автозапуск и снятие ограничений батареи особенно важны на vivo, Xiaomi и Huawei.",
            "These are enabled on a system screen — tap and Android opens the right dialog to confirm. Without background access the phone may sleep Honer AI, delaying answers and notifications. Autostart and battery are especially important on vivo, Xiaomi and Huawei.",
        ),
    ) {
        SettingsRow(
            Icons.Outlined.BatteryChargingFull, t("Работа в фоне без ограничений", "No background restrictions"),
            value = if (batteryUnrestricted) t("Включено", "On") else t("Включить", "Turn on"),
            subtitle = t("Связь, уведомления и агент не засыпают", "Keeps connection, notifications and agent awake"),
            tag = "permissions.battery",
        ) { openBatteryUnrestricted(context) }

        SettingsDivider()
        SettingsRow(
            Icons.Outlined.RestartAlt, t("Автозапуск", "Autostart"),
            value = t("Открыть", "Open"),
            subtitle = t("Чтобы приложение работало после перезагрузки (vivo/Xiaomi)", "So the app runs after a reboot (vivo/Xiaomi)"),
            tag = "permissions.autostart",
        ) { openAutostart(context) }

        SettingsDivider()
        SettingsRow(
            Icons.Outlined.SystemUpdate, t("Установка обновлений", "Install updates"),
            value = if (installAllowed) t("Разрешено", "Allowed") else t("Разрешить", "Allow"),
            subtitle = t("Чтобы Honer AI сам ставил новые версии", "So Honer AI can install new versions itself"),
            tag = "permissions.installUpdates",
        ) {
            runCatching { context.startActivity(UpdateManager.installPermissionIntent(context)) }
                .onFailure { openAppSettings(context) }
        }
    }
}

/** Приложение исключено из оптимизации батареи (может работать в фоне без «засыпания»). */
private fun isBatteryUnrestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** Диалог «разрешить работу в фоне без ограничений»; при отказе системы — общий экран настройки батареи. */
private fun openBatteryUnrestricted(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { openAppSettings(context); return }
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct); true }.getOrDefault(false)) return
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(list) }.onFailure { openAppSettings(context) }
}

/** Экран «Автозапуск» у разных производителей; если его нет — настройки приложения. */
private fun openAutostart(context: Context) {
    // Известные экраны менеджеров автозапуска (vivo, Xiaomi/MIUI, Huawei, Oppo/Realme, Letv, Samsung).
    val candidates = listOf(
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )
    for ((pkg, cls) in candidates) {
        val intent = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
    openAppSettings(context)
}
