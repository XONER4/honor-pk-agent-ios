package com.honerai.app.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.honerai.app.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val WALLPAPER_FILE = "chat_wallpaper.jpg"

/**
 * Обои чата (#17): выбрать картинку-фон или убрать. Картинка масштабируется и хранится в files/.
 * Путь — в AppSettings.wallpaperPath; ChatScreen рисует её за лентой сообщений, ИИ знает, что обои стоят.
 */
@Composable
internal fun WallpaperRows(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val english = settings.isEnglish
    val wallpaper by settings.wallpaperPath.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val bitmap = decodeScaled(context, uri, 1440) ?: return@withContext null
                runCatching {
                    val file = File(context.filesDir, WALLPAPER_FILE)
                    val tmp = File(context.filesDir, "$WALLPAPER_FILE.tmp")
                    tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                    file.path // ChatScreen грузит как File — Coil ключует по пути+времени, перезапись сбрасывает кэш.
                }.getOrNull()
            }
            busy = false
            if (path != null) settings.setWallpaperPath(path)
        }
    }

    val value = when {
        busy -> if (english) "saving…" else "сохраняю…"
        wallpaper.isNotEmpty() -> if (english) "custom" else "своя"
        else -> if (english) "none" else "нет"
    }
    SettingsRow(
        Icons.Outlined.Wallpaper,
        if (english) "Chat wallpaper" else "Обои чата",
        value, tag = "settings.wallpaper",
    ) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (wallpaper.isNotEmpty()) {
        SettingsDivider()
        SettingsRow(
            Icons.Outlined.Delete,
            if (english) "Remove wallpaper" else "Убрать обои",
            tag = "settings.wallpaper.clear", chevron = false, titleColor = Color(0xFFE5484D),
        ) {
            settings.setWallpaperPath("")
            scope.launch(Dispatchers.IO) { runCatching { File(context.filesDir, WALLPAPER_FILE).delete() } }
        }
    }
}
