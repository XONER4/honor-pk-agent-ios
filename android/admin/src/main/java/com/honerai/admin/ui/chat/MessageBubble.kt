package com.honerai.admin.ui.chat

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.MessageMerge
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Receipt
import com.honerai.admin.core.SendState
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.AttachmentRef
import com.honerai.admin.data.Message
import com.honerai.admin.data.Sender
import com.honerai.admin.ui.common.rememberHaptics
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import java.time.ZoneId

val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥")

/** Действия с сообщениями — один объект на экран (стабильный для Compose). */
@Stable
class BubbleActions(
    val onReply: (Message) -> Unit,
    val onEdit: (Message) -> Unit,
    val onCopy: (Message) -> Unit,
    val onReact: (Message, String) -> Unit,
    val onPin: (Message, Boolean) -> Unit,
    val onDelete: (Message, Boolean) -> Unit,
    val onRetry: (String) -> Unit,
    val onDiscard: (String) -> Unit,
    val onOpenAttachment: (AttachmentRef) -> Unit,
    val onQuoteClick: (String) -> Unit,
)

/** Короткое описание сообщения для цитаты, закрепа и уведомлений. */
fun messagePreview(message: Message, english: Boolean): String {
    if (message.deleted) return if (english) "Deleted message" else "Сообщение удалено"
    if (message.text.isNotBlank()) return message.text.replace('\n', ' ')
    val a = message.attachments.firstOrNull() ?: return ""
    return when (a.kind) {
        AttachmentKinds.IMAGE -> if (english) "Photo" else "Фото"
        AttachmentKinds.VIDEO -> if (english) "Video" else "Видео"
        AttachmentKinds.VOICE -> if (english) "Voice message" else "Голосовое сообщение"
        AttachmentKinds.AUDIO -> a.name.ifBlank { if (english) "Audio" else "Аудио" }
        else -> a.name.ifBlank { if (english) "File" else "Файл" }
    }
}

fun senderName(sender: String, english: Boolean): String = when (sender) {
    Sender.ADMIN -> if (english) "You" else "Вы"
    Sender.AI -> "Honer AI"
    else -> if (english) "User" else "Пользователь"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    row: ChatRow.Msg,
    replied: Message?,
    uploadProgress: Float?,
    container: AdminContainer,
    zone: ZoneId,
    actions: BubbleActions,
    highlighted: Boolean,
) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val haptics = rememberHaptics()
    val item = row.item
    val message = item.message
    val mine = message.sender == Sender.ADMIN
    val ai = message.sender == Sender.AI
    var menu by remember { mutableStateOf(false) }
    val big = 20.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (row.firstInGroup) big else small, small, big)
    } else {
        RoundedCornerShape(if (row.firstInGroup) big else small, big, big, small)
    }
    val bg = when {
        mine -> colors.outgoing
        ai -> colors.surface
        else -> colors.raised
    }
    val openMenu = { menu = true; haptics.medium() }

    Column(
        Modifier.fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = if (row.firstInGroup) 6.dp else 1.5.dp)
            .then(if (highlighted) Modifier.background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)) else Modifier),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            val maxBubble = (maxWidth * 0.82f).coerceAtMost(520.dp)
            Box {
                Column(
                    Modifier
                        .widthIn(max = maxBubble)
                        .clip(shape)
                        .background(bg)
                        .then(if (ai) Modifier.border(0.8.dp, colors.accent.copy(alpha = 0.45f), shape) else Modifier)
                        .combinedClickable(
                            onClick = { if (item.state == SendState.FAILED) openMenu() },
                            onLongClick = openMenu,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (ai && row.firstInGroup) AiBadge()
                    if (message.deleted) {
                        Text(tr("Сообщение удалено", "Message deleted"), fontStyle = FontStyle.Italic, fontSize = 15.sp, color = colors.secondary)
                    } else {
                        if (message.replyTo != null) ReplyQuote(replied, english) { actions.onQuoteClick(message.replyTo) }
                        message.attachments.forEach { ref ->
                            AttachmentView(ref, container, mine, maxBubble - 24.dp, onOpen = { actions.onOpenAttachment(ref) }, onLongPress = openMenu)
                        }
                        if (uploadProgress != null) {
                            LinearProgressIndicator(
                                progress = { uploadProgress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                                color = colors.accent, trackColor = colors.divider,
                            )
                        }
                        if (message.text.isNotEmpty()) {
                            Text(message.text, fontSize = 16.sp, lineHeight = 21.sp, color = colors.foreground)
                            // План админка п.1: входящие сообщения пользователя на другом языке переводим на русский.
                            if (!mine && !ai) TranslationBlock(message.text, container)
                        }
                    }
                    Footer(message, MessageMerge.receipt(item), row, zone, mine)
                }
                MessageMenu(menu, message, item.state, mine, english, actions) { menu = false }
            }
        }
        if (message.reactions.isNotEmpty() && !message.deleted) {
            Reactions(message, mine, actions)
        }
        if (item.state == SendState.FAILED) {
            Row(
                Modifier.padding(top = 3.dp, end = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { actions.onRetry(message.clientId) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = colors.danger, modifier = Modifier.size(14.dp))
                Text(" " + tr("Не отправлено · Повторить", "Not sent · Retry"), fontSize = 12.sp, color = colors.danger)
            }
        }
    }
}

/** Кэш переводов по тексту (перевод не меняется) — чтобы не переводить повторно при прокрутке. */
private val translationCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * Авто-перевод входящего сообщения на русский (план админка п.1). Текст, в основном на кириллице,
 * не переводится. Перевод кэшируется; под оригиналом показывается блок «🌐 Перевод».
 */
@Composable
private fun TranslationBlock(text: String, container: AdminContainer) {
    val colors = HonerTheme.colors
    val looksRussian = remember(text) {
        val cyr = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val letters = text.count { it.isLetter() }
        letters == 0 || cyr.toDouble() / letters > 0.5
    }
    if (looksRussian) return
    var result by remember(text) { mutableStateOf(translationCache[text]) }
    LaunchedEffect(text) {
        if (result == null) {
            val r = runCatching { container.api.translate(text) }.getOrNull()
            val out = if (r != null && r.translated) r.text else ""
            translationCache[text] = out
            result = out
        }
    }
    val value = result
    if (value == null) {
        Text(tr("Перевод…", "Translating…"), fontSize = 12.sp, color = colors.secondary)
    } else if (value.isNotBlank()) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Column {
                Text("🌐 " + tr("Перевод", "Translation"), fontSize = 11.sp, color = colors.secondary)
                Text(value, fontSize = 15.sp, lineHeight = 20.sp, color = colors.foreground.copy(alpha = 0.92f))
            }
        }
    }
}

@Composable
private fun AiBadge() {
    val colors = HonerTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF47B0FF), Color(0xFF386EFA)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
        Spacer(Modifier.width(6.dp))
        Text("Honer AI", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
        Spacer(Modifier.width(6.dp))
        Text("AI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.accent,
            modifier = Modifier.border(0.8.dp, colors.accent, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp))
    }
}

@Composable
private fun ReplyQuote(replied: Message?, english: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.height(IntrinsicSize.Min).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.18f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(replied?.let { senderName(it.sender, english) } ?: tr("Сообщение", "Message"), fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = colors.accent, maxLines = 1)
            Text(replied?.let { messagePreview(it, english) } ?: tr("Сообщение не загружено", "Message not loaded"),
                fontSize = 13.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColumnScope.Footer(message: Message, receipt: Receipt, row: ChatRow.Msg, zone: ZoneId, mine: Boolean) {
    val colors = HonerTheme.colors
    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (message.pinned) {
            Icon(Icons.Outlined.PushPin, tr("Закреплено", "Pinned"), tint = colors.secondary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        if (message.editedAt != null && !message.deleted) {
            Text(tr("изм. ", "edited "), fontSize = 11.sp, color = colors.secondary)
        }
        Text(PresenceText.clockOf(row.time, zone), fontSize = 11.sp, color = if (mine) colors.foreground.copy(alpha = 0.6f) else colors.secondary)
        if (mine) {
            Spacer(Modifier.width(4.dp))
            when (receipt) {
                Receipt.SENDING -> Icon(Icons.Rounded.AccessTime, tr("Отправляется", "Sending"), tint = colors.foreground.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                Receipt.FAILED -> Icon(Icons.Rounded.ErrorOutline, tr("Ошибка", "Failed"), tint = colors.danger, modifier = Modifier.size(14.dp))
                Receipt.SENT -> Icon(Icons.Rounded.Done, tr("Отправлено", "Sent"), tint = colors.foreground.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                Receipt.READ -> Icon(Icons.Rounded.DoneAll, tr("Прочитано", "Read"), tint = Color(0xFF8FD3FF), modifier = Modifier.size(15.dp))
                Receipt.NONE -> Unit
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentView(
    ref: AttachmentRef,
    container: AdminContainer,
    mine: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    when (ref.kind) {
        AttachmentKinds.IMAGE -> {
            val ratio = if ((ref.width ?: 0) > 0 && (ref.height ?: 0) > 0) (ref.width!!.toFloat() / ref.height!!).coerceIn(0.5f, 2.2f) else 4f / 3f
            AsyncImage(
                model = ref.resolvedUrl(container),
                imageLoader = container.imageLoader,
                contentDescription = ref.name.ifBlank { tr("Фото", "Photo") },
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(minOf(maxWidth, 280.dp)).aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
                    .background(colors.background.copy(alpha = 0.4f))
                    .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            )
        }
        AttachmentKinds.VIDEO -> {
            val ratio = if ((ref.width ?: 0) > 0 && (ref.height ?: 0) > 0) (ref.width!!.toFloat() / ref.height!!).coerceIn(0.56f, 1.9f) else 16f / 9f
            Box(
                Modifier.width(minOf(maxWidth, 280.dp)).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                    .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
                contentAlignment = Alignment.Center,
            ) {
                // Кадр из локального видео (черновик) — Coil умеет только для файлов на телефоне.
                if (ref.isLocal()) {
                    AsyncImage(model = ref.url, imageLoader = container.imageLoader, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, tr("Смотреть видео", "Play video"), tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Text(
                    listOfNotNull(ref.durationMs?.let { PresenceText.mmss(it) }, ref.size.takeIf { it > 0 }?.let { PresenceText.fileSize(it, english) })
                        .joinToString(" · "),
                    fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        AttachmentKinds.VOICE, AttachmentKinds.AUDIO -> {
            VoicePlayer(container, ref, tint = colors.accent)
        }
        else -> FileCard(ref, english, onOpen, onLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(ref: AttachmentRef, english: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.widthIn(min = 180.dp, max = 280.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(ref.name.ifBlank { tr("Файл", "File") }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.foreground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(PresenceText.fileSize(ref.size, english), fontSize = 12.sp, color = colors.secondary)
        }
    }
}

@Composable
private fun Reactions(message: Message, mine: Boolean, actions: BubbleActions) {
    val colors = HonerTheme.colors
    Row(
        Modifier.padding(top = 3.dp, start = if (mine) 0.dp else 4.dp, end = if (mine) 4.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        message.reactions.forEach { (emoji, who) ->
            val byMe = Sender.ADMIN in who
            Row(
                Modifier.clip(CircleShape).background(if (byMe) colors.accent.copy(alpha = 0.22f) else colors.surface)
                    .border(0.7.dp, if (byMe) colors.accent else colors.divider, CircleShape)
                    .clickable { actions.onReact(message, emoji) }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 15.sp)
                if (who.size > 1) Text(" ${who.size}", fontSize = 12.sp, color = colors.foreground)
            }
        }
    }
}

@Composable
private fun MessageMenu(
    expanded: Boolean,
    message: Message,
    state: SendState,
    mine: Boolean,
    english: Boolean,
    actions: BubbleActions,
    onDismiss: () -> Unit,
) {
    val colors = HonerTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = colors.sidebar,
        border = BorderStroke(0.8.dp, colors.divider),
        shadowElevation = 12.dp,
        modifier = Modifier.width(250.dp),
    ) {
        if (state != SendState.SENT) {
            if (state == SendState.FAILED) MenuItem(Icons.Rounded.Refresh, tr("Повторить отправку", "Retry sending")) { onDismiss(); actions.onRetry(message.clientId) }
            MenuItem(Icons.Rounded.Delete, tr("Удалить черновик", "Discard"), danger = true) { onDismiss(); actions.onDiscard(message.clientId) }
            return@DropdownMenu
        }
        if (!message.deleted) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                QuickReactions.forEach { emoji ->
                    val selected = message.reactions[emoji]?.contains(Sender.ADMIN) == true
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (selected) colors.accent.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onDismiss(); actions.onReact(message, emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 19.sp) }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.divider))
            MenuItem(Icons.AutoMirrored.Rounded.Reply, tr("Ответить", "Reply")) { onDismiss(); actions.onReply(message) }
            if (message.text.isNotBlank()) MenuItem(Icons.Rounded.ContentCopy, tr("Копировать", "Copy")) { onDismiss(); actions.onCopy(message) }
            if (mine && message.text.isNotBlank()) MenuItem(Icons.Rounded.Edit, tr("Изменить", "Edit")) { onDismiss(); actions.onEdit(message) }
            MenuItem(Icons.Outlined.PushPin, if (message.pinned) tr("Открепить", "Unpin") else tr("Закрепить", "Pin")) {
                onDismiss(); actions.onPin(message, !message.pinned)
            }
        }
        MenuItem(Icons.Rounded.Delete, tr("Удалить у себя", "Delete for me"), danger = true) { onDismiss(); actions.onDelete(message, false) }
        if (!message.deleted) {
            MenuItem(Icons.Rounded.DeleteForever, tr("Удалить у всех", "Delete for everyone"), danger = true) { onDismiss(); actions.onDelete(message, true) }
        }
        if (mine && message.readByPeer) {
            Text(tr("Прочитано пользователем", "Read by the user"), fontSize = 12.sp, color = colors.secondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (danger) colors.danger else colors.foreground
    DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = tint, leadingIconColor = tint),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = Modifier.heightIn(min = 46.dp),
    )
}

/** Индикатор «печатает…» в ленте — три прыгающие точки в пузыре. */
@Composable
fun TypingBubble(who: String) {
    val colors = HonerTheme.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900)), label = "phase",
    )
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
            .background(if (who == Sender.AI) colors.surface else colors.raised).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (who == Sender.AI) {
            Text("Honer AI", fontSize = 12.sp, color = colors.accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(2.dp))
        }
        for (i in 0..2) {
            val local = ((phase * 3f - i) % 3f + 3f) % 3f
            val lift = if (local < 1f) kotlin.math.sin(local * Math.PI).toFloat() else 0f
            Box(
                Modifier.padding(bottom = (lift * 5).dp).size(7.dp).clip(CircleShape)
                    .background(colors.secondary.copy(alpha = 0.5f + lift * 0.5f)),
            )
        }
    }
}
