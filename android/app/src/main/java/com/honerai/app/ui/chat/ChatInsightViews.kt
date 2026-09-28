package com.honerai.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.device.DeviceInfo
import com.honerai.app.ui.common.AttachmentFileCard
import com.honerai.app.ui.common.AttachmentThumbnail
import com.honerai.app.ui.common.EmptyState
import com.honerai.app.ui.common.HonerImages
import com.honerai.app.ui.common.HonerSegmented
import com.honerai.app.ui.common.HonerSheet
import com.honerai.app.ui.common.MediaLinks
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.VideoPlayerDialog
import com.honerai.app.ui.common.attachmentIcon
import com.honerai.app.ui.common.copyToClipboard
import com.honerai.app.ui.common.openUrl
import com.honerai.app.ui.theme.HonerTheme

private enum class InsightTab { OVERVIEW, MEDIA, FILES, LINKS, TIMELINE }

private fun timelineIcon(symbol: String): ImageVector = when {
    symbol == "mic" -> Icons.Rounded.Mic
    symbol == "person" -> Icons.Rounded.Person
    symbol == "brain" -> Icons.Rounded.Psychology
    symbol == "sparkles" -> Icons.Rounded.AutoAwesome
    symbol.startsWith("step:") -> stepIcon(symbol.removePrefix("step:"))
    symbol.startsWith("attachment:") -> {
        val kind = runCatching { AttachmentKind.valueOf(symbol.removePrefix("attachment:")) }.getOrDefault(AttachmentKind.DOCUMENT)
        attachmentIcon(MessageAttachment(name = "", kind = kind))
    }
    else -> Icons.Rounded.Schedule
}

/** Окно «Информация о чате»: обзор, медиа, файлы, ссылки и ход работы с поиском и фильтром автора. */
@Composable
fun ChatInsightSheet(store: ChatStoreApi, english: Boolean, onPreview: (MessageAttachment) -> Unit, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val conversations by store.conversations.collectAsState()
    val selectedId by store.selectedConversationId.collectAsState()
    val chat = remember(conversations, selectedId) { conversations.firstOrNull { it.id == selectedId } }
    val messages = chat?.messages.orEmpty()
    var tab by remember { mutableStateOf(InsightTab.OVERVIEW) }
    var author by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var video by remember { mutableStateOf<String?>(null) }
    val authorFilter = ChatInsight.Author.entries[author]
    val media = remember(messages) { ChatInsight.media(messages) }
    val links = remember(messages) { ChatInsight.links(messages) }

    HonerSheet(
        title = t("Информация о чате", "Chat information"),
        onDismiss = onDismiss,
        doneLabel = t("Готово", "Done"),
        doneTag = "chat.info.close",
        modifier = Modifier.testTag("chat.info.sheet"),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HonerSegmented(
                listOf(t("Обзор", "Overview"), t("Медиа", "Media"), t("Файлы", "Files"), t("Ссылки", "Links"), t("Ход", "Timeline")),
                tab.ordinal, { tab = InsightTab.entries[it] }, Modifier.padding(horizontal = 16.dp), tag = "chat.info.tabs",
            )
            if (tab != InsightTab.OVERVIEW) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            if (query.isEmpty()) Text(t("Поиск", "Search"), color = colors.secondary, fontSize = 15.sp)
                            BasicTextField(query, { query = it }, singleLine = true,
                                textStyle = TextStyle(color = colors.foreground, fontSize = 15.sp), cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth().testTag("chat.info.search"))
                        }
                        if (query.isNotEmpty()) {
                            Icon(Icons.Rounded.Cancel, null, tint = colors.secondary, modifier = Modifier.size(18.dp).clickable { query = "" })
                        }
                    }
                    HonerSegmented(listOf(t("Все", "All"), t("Вы", "You"), "Honer AI"), author, { author = it }, tag = "chat.info.author")
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    InsightTab.OVERVIEW -> Overview(chat, messages, media, links, english) { tab = it }
                    InsightTab.MEDIA -> {
                        val items = media.filter {
                            (it.kind == ChatInsight.MediaKind.PHOTO || it.kind == ChatInsight.MediaKind.VIDEO) &&
                                ChatInsight.passes(authorFilter, query, it.author, listOf(it.title, it.subtitle))
                        }
                        if (items.isEmpty()) EmptyState(Icons.Rounded.PhotoLibrary, t("Фото и видео пока нет", "No photos or videos yet"))
                        LazyVerticalGrid(
                            GridCells.Adaptive(104.dp),
                            Modifier.fillMaxSize().testTag("chat.info.media"),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(items, key = { it.id }) { item ->
                                MediaTile(item, english) {
                                    val attachment = item.attachment
                                    val url = item.url
                                    if (attachment != null) onPreview(attachment)
                                    else if (url != null && item.kind == ChatInsight.MediaKind.VIDEO) video = url
                                    else if (url != null) openUrl(it, url)
                                }
                            }
                        }
                    }
                    InsightTab.FILES -> {
                        val items = media.filter {
                            (it.kind == ChatInsight.MediaKind.AUDIO || it.kind == ChatInsight.MediaKind.FILE) &&
                                ChatInsight.passes(authorFilter, query, it.author, listOf(it.title, it.subtitle))
                        }
                        LazyColumn(Modifier.fillMaxSize().testTag("chat.info.files"), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (items.isEmpty()) item { EmptyState(Icons.Rounded.Description, t("Файлов и голосовых пока нет", "No files or voice messages yet")) }
                            items(items, key = { it.id }) { item ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    item.attachment?.let { attachment ->
                                        AttachmentFileCard(attachment, english, Modifier.weight(1f).clickable { onPreview(attachment) }, scale = 0.95f)
                                    }
                                    AuthorBadge(item.author, english)
                                }
                            }
                        }
                    }
                    InsightTab.LINKS -> {
                        val context = LocalContext.current
                        val items = links.filter {
                            ChatInsight.passes(authorFilter, query, if (it.origin == "user") MessageRole.USER else MessageRole.ASSISTANT,
                                listOf(it.title, it.url, it.snippet))
                        }
                        LazyColumn(Modifier.fillMaxSize().testTag("chat.info.links"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            if (items.isEmpty()) item { EmptyState(Icons.Rounded.Link, t("Ссылок пока нет", "No links yet")) }
                            items(items, key = { it.url }) { item -> LinkRow(item, english, onOpen = { openUrl(context, item.url) },
                                onCopy = { copyToClipboard(context, item.url) }) }
                        }
                    }
                    InsightTab.TIMELINE -> {
                        val items = remember(messages, english) { ChatInsight.timeline(messages, english) }
                            .filter { ChatInsight.passes(authorFilter, query, it.author, listOf(it.title, it.detail)) }
                        LazyColumn(Modifier.fillMaxSize().testTag("chat.info.timeline"), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (items.isEmpty()) item { EmptyState(Icons.Rounded.Schedule, t("Пока пусто", "Nothing yet")) }
                            items(items, key = { it.id }) { item ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
                                        Icon(timelineIcon(item.symbol), null, modifier = Modifier.size(14.dp),
                                            tint = if (item.author == MessageRole.USER) colors.secondary else colors.accent)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Row {
                                            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                                                modifier = Modifier.weight(1f))
                                            Text(TimeText.clock(item.date), fontSize = 11.sp, color = colors.secondary)
                                        }
                                        if (item.detail.isNotEmpty()) {
                                            Text(item.detail, fontSize = 13.sp, color = colors.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    video?.let { url -> VideoPlayerDialog(url, "", onClose = { video = null }) }
}

@Composable
private fun Overview(
    chat: com.honerai.app.data.Conversation?,
    messages: List<com.honerai.app.data.ChatMessage>,
    media: List<ChatInsight.MediaItem>,
    links: List<ChatInsight.LinkItem>,
    english: Boolean,
    onTab: (InsightTab) -> Unit,
) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    LazyColumn(Modifier.fillMaxSize().testTag("chat.info.overview"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Group(t("Чат", "Chat")) }
        item { KeyValue(t("Название", "Title"), chat?.title ?: "—") }
        chat?.createdAt?.let { item { KeyValue(t("Создан", "Created"), TimeText.dateTime(it, english)) } }
        item { KeyValue(t("Сообщений", "Messages"), messages.size.toString()) }
        item {
            KeyValue(t("Ваших / Honer AI", "Yours / Honer AI"),
                "${messages.count { it.role == MessageRole.USER }} / ${messages.count { it.role == MessageRole.ASSISTANT }}")
        }
        item { KeyValue(t("Голосовых сообщений", "Voice messages"), messages.count { it.inputKind == MessageInputKind.VOICE }.toString()) }
        item { KeyValue(t("Таблиц", "Tables"), (chat?.tables?.size ?: 0).toString()) }
        item { KeyValue(t("Закреплённых инструкций", "Pinned instructions"), (chat?.instructions?.size ?: 0).toString()) }
        item { Group(t("Содержимое", "Content")) }
        item { CountRow(Icons.Rounded.PhotoLibrary, t("Фото", "Photos"), media.count { it.kind == ChatInsight.MediaKind.PHOTO }) { onTab(InsightTab.MEDIA) } }
        item { CountRow(Icons.Rounded.SmartDisplay, t("Видео", "Videos"), media.count { it.kind == ChatInsight.MediaKind.VIDEO }) { onTab(InsightTab.MEDIA) } }
        item { CountRow(Icons.Rounded.GraphicEq, t("Голосовые и музыка", "Voice and music"), media.count { it.kind == ChatInsight.MediaKind.AUDIO }) { onTab(InsightTab.FILES) } }
        item { CountRow(Icons.Rounded.Description, t("Файлы", "Files"), media.count { it.kind == ChatInsight.MediaKind.FILE }) { onTab(InsightTab.FILES) } }
        item { CountRow(Icons.Rounded.Link, t("Ссылки и сайты", "Links and sites"), links.size) { onTab(InsightTab.LINKS) } }
        item { Group(t("Устройство", "Device")) }
        item { KeyValue(t("Модель", "Model"), DeviceInfo.modelName) }
        item { KeyValue(t("Система", "System"), DeviceInfo.osDescription) }
        item { KeyValue(t("Приложение", "App"), "Honer AI " + DeviceInfo.appVersion) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun Group(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HonerTheme.colors.secondary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun KeyValue(key: String, value: String) {
    val colors = HonerTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = 15.sp, color = colors.foreground, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CountRow(icon: ImageVector, title: String, count: Int, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface)
        .clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Text(title, fontSize = 15.sp, color = colors.foreground, modifier = Modifier.weight(1f))
        Text(count.toString(), fontSize = 15.sp, color = colors.secondary)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AuthorBadge(role: MessageRole, english: Boolean) {
    val colors = HonerTheme.colors
    Text(if (role == MessageRole.USER) (if (english) "You" else "Вы") else "AI", fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = if (role == MessageRole.USER) colors.secondary else colors.accent,
        modifier = Modifier.clip(CircleShape).background(colors.raised).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
private fun MediaTile(item: ChatInsight.MediaItem, english: Boolean, onOpen: (android.content.Context) -> Unit) {
    val context = LocalContext.current
    val colors = HonerTheme.colors
    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface).clickable { onOpen(context) }) {
        val attachment = item.attachment
        if (attachment != null) {
            AttachmentThumbnail(attachment, Modifier.fillMaxSize(), height = 110.dp, maxWidth = 600.dp, corner = 10.dp)
        } else if (item.url != null) {
            val source = if (item.kind == ChatInsight.MediaKind.VIDEO) MediaLinks.videoThumbnail(item.url) ?: item.url else item.url
            AsyncImage(source, null, HonerImages.loader(context), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (item.kind == ChatInsight.MediaKind.VIDEO) {
            Icon(Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(30.dp).align(Alignment.Center))
        }
        Text(if (item.author == MessageRole.USER) (if (english) "You" else "Вы") else "AI", fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRow(item: ChatInsight.LinkItem, english: Boolean, onOpen: () -> Unit, onCopy: () -> Unit) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val origin = when (item.origin) {
        "source" -> if (english) "read by Honer AI" else "прочитано Honer AI"
        "visit" -> if (english) "visited by Honer AI" else "открывал Honer AI"
        "user" -> if (english) "your link" else "ваша ссылка"
        else -> if (english) "in the answer" else "в ответе"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onOpen, onLongClick = onCopy)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(MediaLinks.favicon(item.url), null, HonerImages.loader(context),
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title.ifEmpty { MediaLinks.host(item.url) ?: item.url }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(MediaLinks.host(item.url) ?: item.url, fontSize = 12.sp, color = colors.accent, maxLines = 1)
            if (item.snippet.isNotEmpty()) Text(item.snippet, fontSize = 12.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(origin + " · " + TimeText.shortDateTime(item.date, english), fontSize = 11.sp, color = colors.secondary)
        }
    }
}
