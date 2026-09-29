package com.honerai.admin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.LocalAttachment
import com.honerai.admin.core.PresenceText
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.Message
import com.honerai.admin.ui.common.rememberHaptics
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.delay
import java.io.File

enum class AttachSource { MEDIA, FILE, AUDIO }

/**
 * Поле ввода чата: ответ на сообщение, выбранные вложения, «+» (фото/видео, файл, аудио),
 * отправка; при пустом поле — кнопка микрофона: удерживайте для записи, отпустите — отправить,
 * проведите влево — отмена.
 */
@Composable
fun ChatComposer(
    container: AdminContainer,
    text: String,
    onText: (String) -> Unit,
    replyTo: Message?,
    onCancelReply: () -> Unit,
    editing: Message?,
    onCancelEdit: () -> Unit,
    attachments: List<LocalAttachment>,
    onRemoveAttachment: (LocalAttachment) -> Unit,
    onAttach: (AttachSource) -> Unit,
    onSend: () -> Unit,
    recorder: VoiceRecorder,
    hasMicPermission: () -> Boolean,
    requestMicPermission: () -> Unit,
    onVoice: (File, Long) -> Unit,
    onHint: (String) -> Unit,
) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val haptics = rememberHaptics()
    var attachMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var slide by remember { mutableFloatStateOf(0f) }
    val cancelPx = with(LocalDensity.current) { 110.dp.toPx() }
    val canSend = text.isNotBlank() || attachments.isNotEmpty()

    val latestOnVoice by rememberUpdatedState(onVoice)
    val latestHint by rememberUpdatedState(onHint)
    val latestHasPermission by rememberUpdatedState(hasMicPermission)
    val latestRequest by rememberUpdatedState(requestMicPermission)
    val shortHint = tr("Удерживайте, чтобы записать голосовое", "Hold to record a voice message")
    val failHint = tr("Не удалось включить микрофон", "Could not start the microphone")

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs()
            delay(100)
        }
    }

    Column(
        Modifier.fillMaxWidth().background(colors.background).windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.divider.copy(alpha = 0.6f)))
        AnimatedVisibility(replyTo != null || editing != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val r = editing ?: replyTo
            val isEdit = editing != null
            if (r != null) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (isEdit) Icons.Rounded.Edit else Icons.AutoMirrored.Rounded.Reply, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.accent))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (isEdit) tr("Редактирование", "Editing") else tr("Ответ: ", "Reply to ") + senderName(r.sender, english), fontSize = 13.sp, color = colors.accent,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(messagePreview(r, english), fontSize = 13.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = if (isEdit) onCancelEdit else onCancelReply), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, if (isEdit) tr("Отменить правку", "Cancel edit") else tr("Отменить ответ", "Cancel reply"),
                            tint = colors.secondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (attachments.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(attachments, key = { it.localId }) { a -> PendingChip(container, a) { onRemoveAttachment(a) } }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!recording) {
                if (editing == null) Box {
                    CircleIcon(Icons.Rounded.Add, tr("Прикрепить", "Attach"), colors.surface, colors.foreground) { attachMenu = true }
                    DropdownMenu(
                        expanded = attachMenu, onDismissRequest = { attachMenu = false },
                        shape = RoundedCornerShape(22.dp), containerColor = colors.sidebar, border = BorderStroke(0.8.dp, colors.divider),
                    ) {
                        AttachItem(Icons.Rounded.Image, tr("Фото или видео", "Photo or video")) { attachMenu = false; onAttach(AttachSource.MEDIA) }
                        AttachItem(Icons.AutoMirrored.Rounded.InsertDriveFile, tr("Файл", "File")) { attachMenu = false; onAttach(AttachSource.FILE) }
                        AttachItem(Icons.Rounded.AudioFile, tr("Аудио", "Audio")) { attachMenu = false; onAttach(AttachSource.AUDIO) }
                    }
                }
                if (editing == null) Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(colors.surface)
                        .border(0.7.dp, colors.divider, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(tr("Сообщение", "Message"), color = colors.secondary, fontSize = 16.sp)
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        textStyle = TextStyle(color = colors.foreground, fontSize = 16.sp, lineHeight = 21.sp),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                // Идёт запись: мигающая точка, время и подсказка отмены.
                val pulse = rememberInfiniteTransition(label = "rec")
                val alpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
                Row(
                    Modifier.weight(1f).height(44.dp).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(colors.danger))
                    Spacer(Modifier.width(10.dp))
                    Text(PresenceText.mmss(elapsed).let { if (elapsed < 500) "0:00" else it }, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, color = colors.foreground)
                    Spacer(Modifier.width(16.dp))
                    val cancelling = slide < -cancelPx
                    Text(
                        if (cancelling) tr("Отпустите — отмена", "Release to cancel") else tr("‹ Влево — отмена", "‹ Slide to cancel"),
                        fontSize = 14.sp, color = if (cancelling) colors.danger else colors.secondary, maxLines = 1,
                        modifier = Modifier.alpha((1f + slide / (cancelPx * 2)).coerceIn(0.4f, 1f)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (editing != null) {
                CircleIcon(Icons.Rounded.Check, tr("Сохранить", "Save"), colors.accent, Color.White, onSend)
            } else if (canSend && !recording) {
                CircleIcon(Icons.Rounded.ArrowUpward, tr("Отправить", "Send"), colors.accent, Color.White, onSend)
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .scale(if (recording) 1.25f else 1f)
                        .clip(CircleShape)
                        .background(if (recording) colors.danger else colors.surface)
                        .border(0.7.dp, if (recording) colors.danger else colors.divider, CircleShape)
                        .semantics { contentDescription = if (english) "Hold to record voice" else "Удерживайте для записи голосового" }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!latestHasPermission()) {
                                    latestRequest()
                                    return@awaitEachGesture
                                }
                                if (!recorder.start()) {
                                    latestHint(failHint)
                                    return@awaitEachGesture
                                }
                                container.audio.pause()
                                recording = true
                                slide = 0f
                                haptics.medium()
                                var cancelled = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: run { cancelled = true; null } ?: break
                                    slide = (change.position.x - down.position.x).coerceAtMost(0f)
                                    if (!change.pressed) break
                                    change.consume()
                                }
                                recording = false
                                if (cancelled || slide < -cancelPx) {
                                    recorder.cancel()
                                    haptics.light()
                                } else {
                                    val result = recorder.stop()
                                    if (result != null) latestOnVoice(result.first, result.second) else latestHint(shortHint)
                                }
                                slide = 0f
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = if (recording) Color.White else colors.foreground, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, label: String, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun AttachItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = colors.foreground, leadingIconColor = colors.accent),
    )
}

@Composable
private fun PendingChip(container: AdminContainer, a: LocalAttachment, onRemove: () -> Unit) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    Box(Modifier.size(width = if (a.kind == AttachmentKinds.IMAGE || a.kind == AttachmentKinds.VIDEO) 72.dp else 168.dp, height = 72.dp)) {
        if (a.kind == AttachmentKinds.IMAGE || a.kind == AttachmentKinds.VIDEO) {
            AsyncImage(
                model = a.uri, imageLoader = container.imageLoader, contentDescription = a.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface),
            )
            if (a.kind == AttachmentKinds.VIDEO) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(26.dp))
            }
        } else {
            Row(
                Modifier.size(width = 168.dp, height = 72.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface)
                    .border(0.6.dp, colors.divider, RoundedCornerShape(14.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (a.kind == AttachmentKinds.AUDIO) Icons.Rounded.AudioFile else Icons.AutoMirrored.Rounded.InsertDriveFile, null,
                    tint = colors.accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontSize = 13.sp, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (a.size > 0) Text(PresenceText.fileSize(a.size, english), fontSize = 11.sp, color = colors.secondary)
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, tr("Убрать", "Remove"), tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}
