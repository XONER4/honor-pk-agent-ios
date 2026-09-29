package com.honerai.app.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.data.MessageAttachment
import com.honerai.app.ui.common.HonerImages
import com.honerai.app.ui.common.HonerSheet
import com.honerai.app.ui.common.fileUri
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Недавнее фото или видео из галереи телефона. */
data class RecentMedia(val id: Long, val uri: Uri, val isVideo: Boolean, val durationMs: Long)

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaAccess(context: Context): Boolean = mediaPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Последние 14 фото и видео (запрос к MediaStore — в фоне). */
private suspend fun loadRecents(context: Context): List<RecentMedia> = withContext(Dispatchers.IO) {
    val result = ArrayList<RecentMedia>()
    val uri = MediaStore.Files.getContentUri("external")
    val withDuration = Build.VERSION.SDK_INT >= 29
    val projection = buildList {
        add(MediaStore.Files.FileColumns._ID)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        if (withDuration) add(MediaStore.MediaColumns.DURATION)
    }.toTypedArray()
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
    runCatching {
        context.contentResolver.query(uri, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val durationColumn = if (withDuration) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
            while (cursor.moveToNext() && result.size < 14) {
                val id = cursor.getLong(idColumn)
                val video = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
                result.add(RecentMedia(id, ContentUris.withAppendedId(base, id), video, duration))
            }
        }
    }
    result
}

/**
 * Панель вложений под полем ввода: недавние фото и видео, камера, альбом, любой файл, стикеры.
 * [onImport] копирует выбранный файл в приложение и прикрепляет к сообщению.
 */
@Composable
fun AttachmentTray(
    store: ChatStoreApi,
    english: Boolean,
    onImport: suspend (Uri) -> MessageAttachment?,
    onStickers: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun t(ru: String, en: String) = if (english) en else ru
    var loading by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(hasMediaAccess(context)) }
    var recents by remember { mutableStateOf<List<RecentMedia>>(emptyList()) }
    val imported = remember { mutableStateMapOf<Long, String>() }
    val pending by store.attachments.collectAsState()

    fun import(uri: Uri, recentId: Long? = null) {
        scope.launch {
            loading = true
            try {
                val attachment = onImport(uri)
                if (attachment != null && recentId != null) imported[recentId] = attachment.id
            } finally { loading = false }
        }
    }

    LifecycleResumeEffect(Unit) {
        access = hasMediaAccess(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(access) { if (access) recents = loadRecents(context) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        access = result.values.any { it } && hasMediaAccess(context) || result.values.all { it }
        if (!access) onError(t("Разрешите Honer AI показывать фото и видео в настройках телефона. Выбор через «Альбом» доступен и без этого разрешения.",
            "Allow Honer AI to show photos and videos in phone Settings. You can still choose items using Photos."))
    }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile
        if (saved && file != null && file.length() > 0) import(fileUri(context, file))
    }
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) import(uri)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import(uri)
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp).testTag("attachment.tray"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (access) {
            if (recents.isEmpty()) {
                Text(t("В медиатеке пока нет фото и видео", "No photos or videos in the library"), fontSize = 13.sp,
                    color = colors.secondary, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = 26.dp))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(78.dp)) {
                    items(recents, key = { it.id }) { media ->
                        val selectedId = imported[media.id]
                        val selected = selectedId != null && pending.any { it.id == selectedId }
                        RecentTile(media, selected, english, enabled = !loading) {
                            if (selected) {
                                store.removeAttachment(selectedId!!)
                                imported.remove(media.id)
                            } else import(media.uri, media.id)
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 74.dp).clip(RoundedCornerShape(18.dp))
                    .background(colors.foreground.copy(alpha = 0.05f))
                    .clickable { permissionLauncher.launch(mediaPermissions()) }
                    .padding(13.dp)
                    .testTag("attachment.recentsPermission"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = colors.foreground, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Недавние фото и видео", "Recent photos and videos"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.foreground)
                    Text(t("Разрешить доступ", "Allow access"), fontSize = 12.sp, color = colors.secondary)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary)
            }
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                Text(t("Обрабатываю вложение…", "Processing attachment…"), fontSize = 14.sp, color = colors.secondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TrayTile(Icons.Rounded.PhotoCamera, t("Камера", "Camera"), "attachment.camera", !loading, Modifier.weight(1f)) {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "Camera-${System.currentTimeMillis()}.jpg")
                cameraFile = file
                runCatching { cameraLauncher.launch(fileUri(context, file)) }.onFailure {
                    onError(t("Камера на этом устройстве недоступна.", "Camera is unavailable on this device."))
                }
            }
            TrayTile(Icons.Rounded.PhotoLibrary, t("Альбом", "Photos"), "attachment.album", !loading, Modifier.weight(1f)) {
                pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            TrayTile(Icons.Rounded.AttachFile, t("Файл", "File"), "attachment.file", !loading, Modifier.weight(1f)) {
                fileLauncher.launch(arrayOf("*/*"))
            }
            TrayTile(Icons.Outlined.EmojiEmotions, t("Стикеры", "Stickers"), "attachment.stickers", true, Modifier.weight(1f), onStickers)
        }
        Text(t("Фото · видео до 20 минут и 300 МБ · файлы", "Photos · video up to 20 min / 300 MB · files"),
            fontSize = 12.sp, color = colors.secondary)
    }
}

@Composable
private fun RecentTile(media: RecentMedia, selected: Boolean, english: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(17.dp)
    Box(
        Modifier.size(76.dp).clip(shape).background(Color.Gray.copy(alpha = 0.15f)).clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (english) (if (media.isVideo) "Attach video" else "Attach photo")
                else (if (media.isVideo) "Прикрепить видео" else "Прикрепить фото")
            }
            .testTag("attachment.recent.${media.id}"),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(media.uri).size(180).build(),
            imageLoader = HonerImages.loader(context),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center) {
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                tint = if (selected) HonerTheme.colors.accent else Color.White, modifier = Modifier.size(20.dp))
        }
        if (media.isVideo && media.durationMs > 0) {
            val seconds = media.durationMs / 1000
            Text(
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TrayTile(icon: ImageVector, title: String, tag: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Column(
        modifier
            .height(92.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (colors.isDark) Color(0xFF2E2E2E) else Color(0xFFE8E8E8))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = colors.foreground, modifier = Modifier.size(26.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    }
}

// -------------------------------------------------------------------------------------------------
// Стикеры.

/** Набор стикеров-эмодзи по смыслу. Стикер уходит обычным сообщением и остаётся в истории. */
object StickerCatalog {
    data class Group(val id: String, val titleRu: String, val titleEn: String, val stickers: List<String>)

    val groups = listOf(
        Group("reactions", "Реакции", "Reactions", listOf(
            "👍", "👎", "🔥", "❤️", "😂", "🤣", "😮", "😱", "🤔", "🙄", "😴", "🥱",
            "👀", "🙈", "🤯", "😎", "🤝", "🙏", "👏", "💪", "🫡", "🤌")),
        Group("emotions", "Эмоции", "Emotions", listOf(
            "😀", "😃", "😄", "😁", "😊", "🙂", "😉", "😍", "🥰", "😘", "😜", "🤪",
            "😇", "🥳", "😏", "😢", "😭", "😤", "😡", "🤬", "😰", "😳", "🤗", "🫠")),
        Group("status", "Статус", "Status", listOf(
            "✅", "❌", "⚠️", "💡", "📌", "🎯", "🚀", "⭐", "🏆", "🥇", "💯", "🔥",
            "⏳", "⌛", "🔄", "🛑", "❗", "❓", "📈", "📉", "🧠", "💾", "🧩", "🛠")),
        Group("fun", "Разное", "Misc", listOf(
            "🎉", "🎊", "🍕", "☕", "🍺", "🎮", "🎧", "🎬", "📚", "✈️", "🏠", "💤",
            "🐱", "🐶", "🦊", "🐼", "🤖", "👻", "🎃", "🌈", "☀️", "🌙", "⚡", "💎")),
    )
}

/** Панель выбора стикера: категории и крупные кнопки. */
@Composable
fun StickerPickerSheet(english: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    HonerSheet(
        title = if (english) "Stickers" else "Стикеры",
        onDismiss = onDismiss,
        doneLabel = if (english) "Done" else "Готово",
        doneTag = "stickers.close",
        fullHeight = false,
        modifier = Modifier.testTag("stickers.sheet"),
    ) { close ->
        var group by remember { mutableIntStateOf(0) }
        ScrollableTabRow(
            selectedTabIndex = group,
            containerColor = colors.background,
            contentColor = colors.accent,
            edgePadding = 14.dp,
            divider = {},
            indicator = { positions ->
                if (group < positions.size) {
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[group]), color = colors.accent)
                }
            },
        ) {
            StickerCatalog.groups.forEachIndexed { index, item ->
                Tab(selected = group == index, onClick = { group = index },
                    text = { Text(if (english) item.titleEn else item.titleRu, fontWeight = FontWeight.SemiBold) },
                    selectedContentColor = colors.accent, unselectedContentColor = colors.secondary)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(56.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 18.dp),
        ) {
            items(StickerCatalog.groups[group].stickers, key = { it }) { sticker ->
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
                        .border(0.6.dp, colors.divider, RoundedCornerShape(12.dp))
                        .clickable { onSelect(sticker); close() }
                        .semantics { contentDescription = (if (english) "Sticker " else "Стикер ") + sticker }
                        .testTag("sticker.$sticker"),
                    contentAlignment = Alignment.Center,
                ) { Text(sticker, fontSize = 34.sp) }
            }
        }
    }
}
