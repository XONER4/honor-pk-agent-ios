package com.honerai.app.ui.cloud

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.app.cloud.ChatEntry
import com.honerai.app.cloud.CloudAttachment
import com.honerai.app.cloud.CloudAudio
import com.honerai.app.cloud.CloudImages
import com.honerai.app.cloud.CloudMedia
import com.honerai.app.cloud.CloudMessage
import com.honerai.app.cloud.Delivery
import com.honerai.app.cloud.LocalAttachment
import com.honerai.app.cloud.MessagePreview
import com.honerai.app.cloud.PresenceText
import com.honerai.app.ui.common.HonerMark
import com.honerai.app.ui.markdown.MarkdownContent
import com.honerai.app.ui.theme.HonerTheme
import java.io.File

/** Реакции в меню сообщения. */
val CloudReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "👎")

/** Действия с сообщением (создаются один раз на экран). */
@Immutable
class BubbleActions(
    val onReply: (ChatEntry) -> Unit,
    val onEdit: (ChatEntry) -> Unit,
    val onCopy: (ChatEntry) -> Unit,
    val onReact: (ChatEntry, String) -> Unit,
    val onPin: (ChatEntry, Boolean) -> Unit,
    val onDelete: (ChatEntry, Boolean) -> Unit,
    val onRetry: (ChatEntry) -> Unit,
    val onOpenMedia: (CloudAttachment, String?) -> Unit,
    val onSave: (CloudAttachment, String?) -> Unit,
    val onOpenFile: (CloudAttachment, String?) -> Unit,
    val onJumpTo: (String) -> Unit,
)

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun formatSize(bytes: Long, english: Boolean): String = when {
    bytes >= 1024L * 1024 -> "%.1f %s".format(bytes / 1024.0 / 1024.0, if (english) "MB" else "МБ")
    bytes >= 1024 -> "%d %s".format(bytes / 1024, if (english) "KB" else "КБ")
    else -> "$bytes " + if (english) "B" else "Б"
}

/** Имя автора для цитаты и меню. */
fun senderName(message: CloudMessage, english: Boolean): String = when {
    message.fromUser -> if (english) "You" else "Вы"
    message.fromAi -> "Honer AI"
    else -> if (english) "Administrator" else "Администратор"
}

/**
 * Сообщение в ленте. Свои — справа (цвет приложения, ✓/✓✓), администратор — слева с красной
 * полосой, Honer AI — в стиле ответов приложения с подписью «Honer AI».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    entry: ChatEntry,
    replied: ChatEntry?,
    read: Boolean,
    progress: Float?,
    group: Boolean,
    english: Boolean,
    fontScale: Float,
    actions: BubbleActions,
) {
    val colors = HonerTheme.colors
    val message = entry.message
    var menuOpen by remember { mutableStateOf(false) }
    val mine = message.fromUser
    val ai = message.fromAi
    val maxWidth = if (ai) 560.dp else 330.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (ai) {
            HonerMark(26.dp, Modifier.padding(end = 6.dp, bottom = 2.dp))
        }
        Box {
            val shape = when {
                mine -> RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
                else -> RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
            }
            val background = when {
                mine -> colors.accent
                ai -> colors.surface
                else -> if (colors.isDark) Color(0xFF2A1F20) else Color(0xFFFFF1F0)
            }
            val content = if (mine) Color.White else colors.foreground
            val meta = if (mine) Color.White.copy(alpha = 0.78f) else colors.secondary
            Column(
                Modifier.widthIn(max = maxWidth).clip(shape).background(background)
                    .then(if (!mine && !ai) Modifier.border(0.7.dp, AdminRed.copy(alpha = 0.35f), shape) else Modifier)
                    .combinedClickable(onClick = { if (entry.delivery == Delivery.FAILED) actions.onRetry(entry) }, onLongClick = { menuOpen = true })
                    .padding(horizontal = 11.dp, vertical = 7.dp)
                    .testTag("admin.message." + entry.key),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (!mine && (group || ai)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(senderName(message, english), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (ai) colors.accent else AdminRed)
                        if (!ai) VerifiedBadge(12.dp)
                    }
                }
                if (replied != null || message.replyTo != null) {
                    ReplyQuote(replied?.message, english, if (mine) Color.White else AdminRed, content) { message.replyTo?.let(actions.onJumpTo) }
                }
                message.attachments.forEachIndexed { index, attachment ->
                    AttachmentView(attachment, entry.local.getOrNull(index)?.path, mine, english, actions)
                }
                if (message.attachments.isEmpty() && entry.local.isNotEmpty()) {
                    entry.local.forEach { local -> AttachmentView(local.asCloud(), local.path, mine, english, actions) }
                }
                if (progress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(progress = { progress }, color = content, trackColor = content.copy(alpha = 0.2f),
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text((progress * 100).toInt().toString() + "%", fontSize = 12.sp, color = meta)
                    }
                }
                if (message.text.isNotEmpty()) {
                    if (ai) {
                        MarkdownContent(text = message.text, modifier = Modifier.widthIn(max = maxWidth), fontScale = fontScale)
                    } else {
                        Text(message.text, fontSize = (16 * fontScale).sp, lineHeight = (21 * fontScale).sp, color = content)
                    }
                }
                Row(
                    Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (message.pinned) Icon(Icons.Outlined.PushPin, null, tint = meta, modifier = Modifier.size(11.dp))
                    if (message.editedAt != null) Text(if (english) "edited" else "изм.", fontSize = 11.sp, color = meta)
                    Text(PresenceText.clock(message.createdAt), fontSize = 11.sp, color = meta)
                    if (mine) DeliveryMark(entry.delivery, read, meta)
                }
            }
            MessageMenu(menuOpen, entry, english, actions) { menuOpen = false }
        }
    }
    if (message.reactions.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (ai) 44.dp else 14.dp, end = 14.dp, top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, if (mine) Alignment.End else Alignment.Start),
        ) {
            message.reactions.forEach { (emoji, who) ->
                val selectedByMe = CloudMessage.SENDER_USER in who
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (selectedByMe) colors.accent.copy(alpha = 0.22f) else colors.surface)
                        .border(0.7.dp, if (selectedByMe) colors.accent else colors.divider, RoundedCornerShape(12.dp))
                        .clickable { actions.onReact(entry, emoji) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(emoji, fontSize = 14.sp)
                    if (who.size > 1) Text("${who.size}", fontSize = 12.sp, color = colors.secondary)
                }
            }
        }
    }
}

private fun LocalAttachment.asCloud() = CloudAttachment(id = "", kind = kind, name = name, mime = mime, size = size,
    durationMs = durationMs, width = width, height = height, url = "")

/** ✓ отправлено, ✓✓ прочитано, часы — отправляется, «!» — не отправлено (нажать — повторить). */
@Composable
private fun DeliveryMark(delivery: Delivery, read: Boolean, tint: Color) {
    val (icon, color) = when {
        delivery == Delivery.SENDING -> Icons.Rounded.Schedule to tint
        delivery == Delivery.FAILED -> Icons.Rounded.ErrorOutline to Color(0xFFFFD0CC)
        read -> Icons.Rounded.DoneAll to Color.White
        else -> Icons.Rounded.Done to tint
    }
    Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
}

/** Цитата сообщения, на которое ответили; нажатие — прокрутка к нему. */
@Composable
fun ReplyQuote(replied: CloudMessage?, english: Boolean, accent: Color, content: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(end = 8.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(start = 7.dp, top = 3.dp, bottom = 3.dp)) {
            Text(replied?.let { senderName(it, english) } ?: if (english) "Message" else "Сообщение",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
            Text(replied?.let { MessagePreview.text(it, english) } ?: if (english) "Message not loaded" else "Сообщение не загружено",
                fontSize = 13.sp, color = content.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Меню долгого нажатия: реакции, ответить, копировать, закрепить, сохранить, удалить. */
@Composable
private fun MessageMenu(expanded: Boolean, entry: ChatEntry, english: Boolean, actions: BubbleActions, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val message = entry.message
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp), containerColor = colors.surface) {
        if (!entry.pending) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CloudReactions.forEach { emoji ->
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { onDismiss(); actions.onReact(entry, emoji) }
                            .semantics { contentDescription = emoji },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 20.sp) }
                }
            }
            HorizontalDivider(color = colors.divider)
            MenuItem(Icons.AutoMirrored.Rounded.Reply, t("Ответить", "Reply"), "admin.menu.reply") { onDismiss(); actions.onReply(entry) }
        }
        if (entry.delivery == Delivery.FAILED) {
            MenuItem(Icons.Rounded.Refresh, t("Отправить ещё раз", "Send again"), "admin.menu.retry") { onDismiss(); actions.onRetry(entry) }
        }
        if (message.text.isNotEmpty()) {
            MenuItem(Icons.Outlined.ContentCopy, t("Копировать", "Copy"), "admin.menu.copy") { onDismiss(); actions.onCopy(entry) }
        }
        if (message.fromUser && !entry.pending && message.text.isNotEmpty()) {
            MenuItem(Icons.Outlined.Edit, t("Изменить", "Edit"), "admin.menu.edit") { onDismiss(); actions.onEdit(entry) }
        }
        if (!entry.pending) {
            MenuItem(Icons.Outlined.PushPin, if (message.pinned) t("Открепить", "Unpin") else t("Закрепить", "Pin"), "admin.menu.pin") {
                onDismiss(); actions.onPin(entry, !message.pinned)
            }
        }
        message.attachments.firstOrNull()?.let { attachment ->
            MenuItem(Icons.Rounded.Download, t("Сохранить", "Save"), "admin.menu.save") {
                onDismiss(); actions.onSave(attachment, entry.local.firstOrNull()?.path)
            }
        }
        HorizontalDivider(color = colors.divider)
        MenuItem(Icons.Outlined.Delete, t("Удалить у меня", "Delete for me"), "admin.menu.deleteMe", destructive = true) {
            onDismiss(); actions.onDelete(entry, false)
        }
        if (message.fromUser && !entry.pending) {
            MenuItem(Icons.Outlined.DeleteForever, t("Удалить у всех", "Delete for everyone"), "admin.menu.deleteAll", destructive = true) {
                onDismiss(); actions.onDelete(entry, true)
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (destructive) Color(0xFFFF453A) else colors.foreground
    DropdownMenuItem(
        text = { Text(title, color = tint, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** Вложение в пузыре: фото и видео — картинкой, голосовые и музыка — проигрывателем, файлы — карточкой. */
@Composable
fun AttachmentView(attachment: CloudAttachment, localPath: String?, mine: Boolean, english: Boolean, actions: BubbleActions) {
    when (attachment.kind) {
        "image" -> MediaThumb(attachment, localPath, video = false) { actions.onOpenMedia(attachment, localPath) }
        "video" -> MediaThumb(attachment, localPath, video = true) { actions.onOpenMedia(attachment, localPath) }
        "voice", "audio" -> AudioPlayerRow(attachment, localPath, mine, english)
        else -> FileRow(attachment, localPath, mine, english) { actions.onOpenFile(attachment, localPath) }
    }
}

/** Фото/видео с пропорциями из размеров (0,6…1,8), не шире 260 dp. */
@Composable
private fun MediaThumb(attachment: CloudAttachment, localPath: String?, video: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = HonerTheme.colors
    val w = attachment.width ?: 0
    val h = attachment.height ?: 0
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.6f, 1.8f) else if (video) 16f / 9f else 1f
    val local = localPath?.let { File(it) }?.takeIf { it.exists() }
    Box(
        Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(colors.raised)
            .clickable(onClick = onClick)
            .semantics { contentDescription = attachment.name },
        contentAlignment = Alignment.Center,
    ) {
        // Кадр видео — только из локального файла (скачивать всё видео ради превью не стоит).
        if (!video || local != null) {
            AsyncImage(
                model = if (video) local else attachmentModel(attachment, localPath),
                imageLoader = CloudImages.loader(context),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (video) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            attachment.durationMs?.let { duration ->
                Text(formatDuration(duration), color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

/** Голосовое/аудио: кнопка, полоса хода (нажать — перемотать), время. */
@Composable
private fun AudioPlayerRow(attachment: CloudAttachment, localPath: String?, mine: Boolean, english: Boolean) {
    val colors = HonerTheme.colors
    val state by CloudAudio.state.collectAsState()
    val key = attachment.id.ifEmpty { localPath ?: attachment.name }
    val active = state.key == key
    val duration = if (active && state.durationMs > 0) state.durationMs else attachment.durationMs ?: 0
    val position = if (active) state.positionMs else 0
    val tint = if (mine) Color.White else if (attachment.kind == "voice") AdminRed else colors.accent
    val track = if (mine) Color.White.copy(alpha = 0.3f) else colors.divider
    Row(
        Modifier.widthIn(min = 220.dp, max = 280.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (mine) Color.White.copy(alpha = 0.2f) else tint.copy(alpha = 0.15f))
                .clickable { CloudAudio.toggle(key) { CloudMedia.localFile(attachment, localPath) } }
                .semantics { contentDescription = if (active && state.playing) (if (english) "Pause" else "Пауза") else (if (english) "Play" else "Слушать") }
                .testTag("admin.audio.play"),
            contentAlignment = Alignment.Center,
        ) {
            when {
                active && state.loading -> CircularProgressIndicator(color = tint, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                active && state.playing -> Icon(Icons.Rounded.Pause, null, tint = tint)
                attachment.kind == "audio" && !active -> Icon(Icons.Rounded.MusicNote, null, tint = tint)
                else -> Icon(Icons.Rounded.PlayArrow, null, tint = tint)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (attachment.kind == "audio") {
                Text(attachment.name, fontSize = 14.sp, color = if (mine) Color.White else colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.fillMaxWidth().height(14.dp)
                    .pointerInput(key, active) {
                        detectTapGestures { offset -> if (active) CloudAudio.seek(key, offset.x / size.width) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(track))
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(tint))
            }
            Text(
                if (active && position > 0) formatDuration(position) + " / " + formatDuration(duration) else formatDuration(duration),
                fontSize = 12.sp, color = if (mine) Color.White.copy(alpha = 0.8f) else colors.secondary,
            )
        }
    }
}

/** Файл: значок, имя, размер; нажатие — скачать и открыть. */
@Composable
private fun FileRow(attachment: CloudAttachment, localPath: String?, mine: Boolean, english: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.widthIn(min = 200.dp, max = 280.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(if (mine) Color.White.copy(alpha = 0.2f) else colors.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = if (mine) Color.White else colors.accent) }
        Column(Modifier.weight(1f)) {
            Text(attachment.name.ifBlank { if (english) "File" else "Файл" }, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (mine) Color.White else colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatSize(attachment.size, english), fontSize = 12.sp, color = if (mine) Color.White.copy(alpha = 0.8f) else colors.secondary)
        }
    }
}

/** Выбранные, но ещё не отправленные файлы над полем ввода. */
@Composable
fun PendingAttachmentsTray(items: List<LocalAttachment>, english: Boolean, onRemove: (LocalAttachment) -> Unit) {
    val context = LocalContext.current
    val colors = HonerTheme.colors
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.size, key = { items[it].path }) { index ->
            val item = items[index]
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(colors.raised)) {
                if (item.kind == "image" || item.kind == "video") {
                    AsyncImage(model = File(item.path), imageLoader = CloudImages.loader(context), contentDescription = item.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (item.kind == "audio") Icons.Rounded.MusicNote else Icons.AutoMirrored.Rounded.InsertDriveFile, null,
                            tint = colors.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(item.name, fontSize = 10.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        .clickable { onRemove(item) }.semantics { contentDescription = if (english) "Remove" else "Убрать" },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 11.sp) }
            }
        }
    }
}

/** Отступ между строками: внутри группы одного автора — плотнее. */
fun gap(sameAuthorAsPrevious: Boolean): Dp = if (sameAuthorAsPrevious) 2.dp else 9.dp
