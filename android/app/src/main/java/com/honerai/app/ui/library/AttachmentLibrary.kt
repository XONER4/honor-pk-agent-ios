package com.honerai.app.ui.library

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayCircle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.Conversation
import com.honerai.app.data.MessageAttachment
import com.honerai.app.ui.chat.AttachmentPreviewDialog
import com.honerai.app.ui.common.AttachmentThumbnail
import com.honerai.app.ui.common.EmptyState
import com.honerai.app.ui.common.HonerCircleButton
import com.honerai.app.ui.common.SidebarSymbol
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.attachmentIcon
import com.honerai.app.ui.common.file
import com.honerai.app.ui.common.shareFile
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Один элемент библиотеки вложений: файл, чат-источник, дата и вкладка. */
data class LibraryItem(
    val attachment: MessageAttachment,
    val chatId: String,
    val chatTitle: String,
    val date: Instant,
    val tab: LibraryTab,
)

/** Вкладки библиотеки: Фото, Видео, Музыка/аудио, Голосовые, Файлы. */
enum class LibraryTab {
    PHOTOS, VIDEOS, MUSIC, VOICE, FILES;

    fun title(english: Boolean): String = when (this) {
        PHOTOS -> if (english) "Photos" else "Фото"
        VIDEOS -> if (english) "Videos" else "Видео"
        MUSIC -> if (english) "Music" else "Музыка"
        VOICE -> if (english) "Voice" else "Голосовые"
        FILES -> if (english) "Files" else "Файлы"
    }
}

/** Построение индекса библиотеки (без Android — проверяется тестами). */
object AttachmentLibraryIndex {
    private val voiceMarkers = listOf("голос", "voice", "запись", "recording", "диктоф", "аудиозапис")

    fun tabFor(attachment: MessageAttachment): LibraryTab? = when (attachment.kind) {
        AttachmentKind.IMAGE -> LibraryTab.PHOTOS
        AttachmentKind.VIDEO -> LibraryTab.VIDEOS
        AttachmentKind.AUDIO -> if (isVoice(attachment)) LibraryTab.VOICE else LibraryTab.MUSIC
        AttachmentKind.DOCUMENT, AttachmentKind.TEXT -> LibraryTab.FILES
        AttachmentKind.STICKER -> null
    }

    private fun isVoice(attachment: MessageAttachment): Boolean {
        val haystack = (attachment.name + " " + (attachment.summary ?: "")).lowercase()
        return voiceMarkers.any { haystack.contains(it) }
    }

    /** Все вложения из истории (или одного чата), сгруппированные по вкладкам и отсортированные по дате (свежие сверху). */
    fun build(conversations: List<Conversation>, scopeChatId: String?): Map<LibraryTab, List<LibraryItem>> {
        val items = ArrayList<LibraryItem>()
        val seen = HashSet<String>()
        for (chat in conversations) {
            if (scopeChatId != null && chat.id != scopeChatId) continue
            for (message in chat.messages) {
                for (attachment in message.attachments) {
                    val tab = tabFor(attachment) ?: continue
                    if (!seen.add(attachment.id)) continue
                    items.add(LibraryItem(attachment, chat.id, chat.title, message.createdAt, tab))
                }
            }
        }
        return items.groupBy { it.tab }.mapValues { (_, list) -> list.sortedByDescending { it.date } }
    }

    /** Порядок вкладок для показа (только непустые, если есть хоть что-то). */
    fun tabsWithContent(index: Map<LibraryTab, List<LibraryItem>>): List<LibraryTab> =
        LibraryTab.entries.filter { !index[it].isNullOrEmpty() }
}

/**
 * Экран «Вложения»: все фото, видео, музыка, голосовые и файлы из истории (или одного чата),
 * по вкладкам. Индекс строится в фоне и запоминается. Просмотр — существующим окном вложения
 * с «Поделиться»; «Скачать» сохраняет файл в общую папку загрузок.
 */
@Composable
fun AttachmentLibraryScreen(
    store: ChatStoreApi,
    english: Boolean,
    scopeChatId: String?,
    onClose: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun t(ru: String, en: String) = if (english) en else ru
    val conversations by store.conversations.collectAsState()

    var index by remember { mutableStateOf<Map<LibraryTab, List<LibraryItem>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<MessageAttachment?>(null) }

    // Индекс строится вне главного потока; пересобирается при изменении истории.
    val revision = remember(conversations) { conversations.size * 31 + conversations.sumOf { it.messages.size } }
    LaunchedEffect(revision, scopeChatId) {
        loading = true
        val snapshot = conversations
        index = withContext(Dispatchers.Default) { AttachmentLibraryIndex.build(snapshot, scopeChatId) }
        loading = false
    }

    val tabs = remember(index) { AttachmentLibraryIndex.tabsWithContent(index) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val currentTab = tabs.getOrNull(selectedTab)

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HonerCircleButton(t("Назад", "Back"), onClose, Modifier.testTag("library.close")) {
                Icon(Icons.Rounded.Close, null, tint = colors.foreground, modifier = Modifier.size(20.dp))
            }
            Text(if (scopeChatId != null) t("Вложения этого чата", "Attachments in this chat") else t("Все вложения", "All attachments"),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.testTag("library.loading"))
            }
            tabs.isEmpty() -> EmptyState(Icons.Rounded.Download,
                t("Здесь пока нет вложений", "No attachments here yet"),
                Modifier.padding(top = 80.dp).testTag("library.empty"))
            else -> {
                if (selectedTab >= tabs.size) selectedTab = 0
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.coerceIn(0, tabs.size - 1),
                    containerColor = colors.background,
                    contentColor = colors.accent,
                    edgePadding = 14.dp,
                    divider = {},
                    indicator = { positions ->
                        val i = selectedTab.coerceIn(0, tabs.size - 1)
                        if (i < positions.size) TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[i]), color = colors.accent)
                    },
                ) {
                    tabs.forEachIndexed { i, tab ->
                        val count = index[tab]?.size ?: 0
                        Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                            text = { Text("${tab.title(english)} · $count", fontWeight = FontWeight.SemiBold) },
                            selectedContentColor = colors.accent, unselectedContentColor = colors.secondary,
                            modifier = Modifier.testTag("library.tab.${tab.name}"))
                    }
                }
                val items = currentTab?.let { index[it] }.orEmpty()
                val visual = currentTab == LibraryTab.PHOTOS || currentTab == LibraryTab.VIDEOS
                if (visual) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        modifier = Modifier.fillMaxSize().testTag("library.grid"),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items, key = { it.attachment.id }) { item ->
                            VisualTile(item, english) { preview = item.attachment }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("library.list"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it.attachment.id }) { item ->
                            FileRow(item, english, onOpen = { preview = item.attachment },
                                onShare = { shareItem(context, item, onMessage, english) },
                                onDownload = { scope.launch { downloadItem(context, item, onMessage, english) } })
                        }
                    }
                }
            }
        }
    }

    preview?.let { attachment ->
        AttachmentPreviewDialog(attachment = attachment, english = english, onEdited = null, onClose = { preview = null })
    }
}

@Composable
private fun VisualTile(item: LibraryItem, english: Boolean, onOpen: () -> Unit) {
    val colors = HonerTheme.colors
    Column(Modifier.testTag("library.item." + item.attachment.id)) {
        Box(Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .semantics { contentDescription = item.attachment.name }, contentAlignment = Alignment.Center) {
            AttachmentThumbnail(item.attachment, Modifier.fillMaxWidth(), height = 110.dp, maxWidth = 200.dp, corner = 12.dp)
            if (item.tab == LibraryTab.VIDEOS) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(34.dp))
            }
        }
        Text(item.chatTitle, fontSize = 11.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp))
        Text(TimeText.dateTime(item.date, english), fontSize = 10.sp, color = colors.secondary.copy(alpha = 0.8f), maxLines = 1)
    }
}

@Composable
private fun FileRow(item: LibraryItem, english: Boolean, onOpen: () -> Unit, onShare: () -> Unit, onDownload: () -> Unit) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(colors.surface)
            .border(0.7.dp, colors.divider, shape).clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("library.item." + item.attachment.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(attachmentIcon(item.attachment), null, tint = colors.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(item.attachment.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.chatTitle + " · " + TimeText.dateTime(item.date, english), fontSize = 12.sp, color = colors.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val hasFile = item.attachment.file() != null
        if (hasFile) {
            LibraryIconButton(Icons.Rounded.Download, if (english) "Download" else "Скачать",
                "library.download." + item.attachment.id, onDownload)
            LibraryIconButton(Icons.Rounded.IosShare, if (english) "Share" else "Поделиться",
                "library.share." + item.attachment.id, onShare)
        }
    }
}

@Composable
private fun LibraryIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
    }
}

private fun shareItem(context: Context, item: LibraryItem, onMessage: (String) -> Unit, english: Boolean) {
    val file = item.attachment.file()
    if (file == null) { onMessage(if (english) "The original file is unavailable." else "Оригинал файла недоступен."); return }
    shareFile(context, file)
}

/** Сохранение файла вложения в общую папку «Загрузки». */
private suspend fun downloadItem(context: Context, item: LibraryItem, onMessage: (String) -> Unit, english: Boolean) {
    val file = item.attachment.file()
    if (file == null) { onMessage(if (english) "The original file is unavailable." else "Оригинал файла недоступен."); return }
    val ok = withContext(Dispatchers.IO) { runCatching { saveToDownloads(context, file, item.attachment.name) }.getOrDefault(false) }
    onMessage(if (ok) (if (english) "Saved to Downloads" else "Сохранено в «Загрузки»")
    else (if (english) "Could not save the file" else "Не удалось сохранить файл"))
}

private fun saveToDownloads(context: Context, source: File, displayName: String): Boolean {
    val name = displayName.ifBlank { source.name }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Honer AI")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: return false
        return true
    }
    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Honer AI").apply { mkdirs() }
    val target = File(dir, name)
    source.copyTo(target, overwrite = true)
    return target.exists()
}
