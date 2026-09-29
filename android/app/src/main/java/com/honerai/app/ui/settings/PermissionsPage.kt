package com.honerai.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.honerai.app.ui.theme.HonerTheme

// «Разрешения» (порт PermissionsPage из PermissionsCenter.swift): что уже открыто и как открыть остальное.

internal enum class PermissionKind { NOTIFICATIONS, MICROPHONE, CAMERA, PHOTOS, CONTACTS, LOCATION }

internal enum class PermissionState { GRANTED, DENIED, NOT_ASKED, LIMITED, NOT_NEEDED }

/** Системные разрешения за каждым пунктом (зависит от версии Android). */
internal fun permissionsFor(kind: PermissionKind): Array<String> = when (kind) {
    PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    PermissionKind.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
    PermissionKind.CAMERA -> arrayOf(Manifest.permission.CAMERA)
    PermissionKind.PHOTOS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    PermissionKind.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
    PermissionKind.LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun declaredPermissions(context: Context): Set<String> = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    info.requestedPermissions?.toSet().orEmpty()
}.getOrDefault(emptySet())

private const val PREFS = "honer.permissions"

internal fun permissionState(context: Context, kind: PermissionKind): PermissionState {
    if (kind == PermissionKind.NOTIFICATIONS && !notificationsAllowed(context)) {
        // Уведомления могут быть выключены в настройках и без runtime-разрешения (до Android 13).
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(kind.name, false)
        return if (Build.VERSION.SDK_INT < 33 || asked) PermissionState.DENIED else PermissionState.NOT_ASKED
    }
    val permissions = permissionsFor(kind)
    if (permissions.isEmpty()) return PermissionState.GRANTED
    // Камера: без разрешения в манифесте снимок делает системное приложение «Камера» — доступ не нужен.
    if (kind == PermissionKind.CAMERA && Manifest.permission.CAMERA !in declaredPermissions(context)) return PermissionState.NOT_NEEDED
    val granted = permissions.map { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (granted.all { it }) return PermissionState.GRANTED
    if (kind == PermissionKind.PHOTOS && Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
    ) return PermissionState.LIMITED
    if (granted.any { it }) return PermissionState.LIMITED
    val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(kind.name, false)
    return if (asked) PermissionState.DENIED else PermissionState.NOT_ASKED
}

/** Настройки приложения в системе. */
internal fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
internal fun PermissionsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    var refresh by remember { mutableIntStateOf(0) }
    // Состояния пересчитываются при возвращении из системных настроек и после ответа на запрос.
    val resumeTick = rememberOnResume { System.nanoTime() }
    val states = remember(refresh, resumeTick) { PermissionKind.entries.associateWith { permissionState(context, it) } }
    var pending by remember { mutableStateOf<PermissionKind?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(it.name, true).apply() }
        pending = null
        refresh++
    }
    fun t(ru: String, en: String) = settings.text(ru, en)

    fun request(kind: PermissionKind) {
        val state = states[kind]
        val permissions = permissionsFor(kind)
        val activity = context.findActivity()
        // Отклонено «навсегда» (система больше не показывает запрос) — открываем настройки приложения.
        val canAsk = activity != null && permissions.isNotEmpty() && (state == PermissionState.NOT_ASKED ||
            permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
        if (!canAsk || (kind == PermissionKind.NOTIFICATIONS && Build.VERSION.SDK_INT < 33)) {
            if (kind == PermissionKind.NOTIFICATIONS) openNotificationSettings(context) else openAppSettings(context)
            return
        }
        pending = kind
        runCatching { launcher.launch(permissions) }.onFailure { openAppSettings(context) }
    }

    SettingsPageScaffold(t("Разрешения", "Permissions"), "settings.page.permissions", onBack) {
        item(key = "list") {
            SettingsGroup(
                footer = t(
                    "Разрешения нужны только для функций, которыми вы пользуетесь. Геопозиция помогает Honer AI знать ваш город и местное время, контакты — находить людей по имени. Отключить доступ можно в настройках Android.",
                    "Permissions are used only for features you use. You can revoke them in Android settings.",
                ),
            ) {
                PermissionKind.entries.forEachIndexed { index, kind ->
                    if (index > 0) SettingsDivider(56)
                    PermissionRow(kind, states[kind] ?: PermissionState.NOT_ASKED) { request(kind) }
                }
            }
        }
        item(key = "extras.device") { com.honerai.app.extras.device.DeviceAccessSettingsGroup() } // extras
        item(key = "open") {
            SettingsGroup {
                SettingsButtonRow(t("Открыть настройки Android", "Open Android settings"), tag = "permissions.openSettings") { openAppSettings(context) }
            }
        }
    }
}

@Composable
private fun PermissionRow(kind: PermissionKind, state: PermissionState, onRequest: () -> Unit) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = settings.text(ru, en)
    val (icon: ImageVector, title, detail) = when (kind) {
        PermissionKind.NOTIFICATIONS -> Triple(Icons.Outlined.NotificationsActive, t("Уведомления", "Notifications"), t("Сообщить, что ответ готов", "Tell you when an answer is ready"))
        PermissionKind.MICROPHONE -> Triple(Icons.Outlined.Mic, t("Микрофон", "Microphone"), t("Голосовой ввод", "Voice input"))
        PermissionKind.CAMERA -> Triple(Icons.Outlined.CameraAlt, t("Камера", "Camera"), t("Снимок для вопроса", "Take a photo for a question"))
        PermissionKind.PHOTOS -> Triple(Icons.Outlined.PhotoLibrary, t("Фото и видео", "Photos and videos"), t("Выбор фото и видео", "Pick photos and videos"))
        PermissionKind.CONTACTS -> Triple(Icons.Outlined.AccountCircle, t("Контакты", "Contacts"), t("Найти человека по имени", "Find a person by name"))
        PermissionKind.LOCATION -> Triple(Icons.Outlined.LocationOn, t("Геопозиция", "Location"), t("Ваш город и местное время", "Your city and local time"))
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp).testTag("permissions.row.${kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.width(28.dp).size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.foreground, fontSize = 16.sp)
            Text(
                if (state == PermissionState.NOT_NEEDED) t("Снимок через приложение «Камера» — разрешение не нужно", "Photos via the Camera app — no permission needed") else detail,
                color = colors.secondary, fontSize = 12.sp,
            )
        }
        when (state) {
            PermissionState.GRANTED, PermissionState.NOT_NEEDED -> StatusLabel(Icons.Filled.CheckCircle, t("Разрешено", "Allowed"), SuccessGreen)
            PermissionState.LIMITED -> StatusLabel(Icons.Filled.Contrast, t("Частично", "Limited"), WarningOrange)
            PermissionState.DENIED, PermissionState.NOT_ASKED -> Text(
                if (state == PermissionState.DENIED) t("Открыть", "Open") else t("Разрешить", "Allow"),
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(colors.accent).clickable(onClick = onRequest)
                    .padding(horizontal = 14.dp, vertical = 7.dp).testTag("permissions.request.${kind.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun StatusLabel(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
