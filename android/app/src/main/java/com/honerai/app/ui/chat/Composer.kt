package com.honerai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.ui.common.LocalReduceMotion
import com.honerai.app.ui.common.PendingAttachmentChip
import com.honerai.app.ui.common.QuoteChip
import com.honerai.app.ui.theme.HonerTheme

/** Состояние голосового ввода для поля ввода. */
data class VoiceUi(
    val mode: Boolean = false,
    val holding: Boolean = false,
    val recording: Boolean = false,
    val finalizing: Boolean = false,
    val transcript: String = "",
)

/**
 * Поле ввода: текст, режимы «Рассуждение» и «Поиск», вложения, цитата, редактирование,
 * голосовой ввод (касание — начать, второе касание — остановить и отправить) и «Стоп».
 */
@Composable
fun Composer(
    store: ChatStoreApi,
    english: Boolean,
    fontScale: Float,
    showError: Boolean,
    attachmentsOpen: Boolean,
    voice: VoiceUi,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onToggleAttachments: () -> Unit,
    onStickers: () -> Unit,
    onSend: () -> Unit,
    onVoiceTap: () -> Unit,
    onVoiceCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val error by store.errorMessage.collectAsState()
    val editing by store.editingMessageId.collectAsState()
    val quote by store.quotedFragment.collectAsState()
    val attachments by store.attachments.collectAsState()
    val reasoning by store.reasoningEnabled.collectAsState()
    val search by store.searchEnabled.collectAsState()
    val generating by store.isGenerating.collectAsState()
    val storeDraft by store.draft.collectAsState()
    // agent: пока агент ждёт подтверждения, поле ввода активно, а вместо «Стоп» показываем «Отправить».
    val pendingAgentAction by store.pendingAgentAction.collectAsState()

    // Своё значение поля (с курсором) синхронизируется с черновиком хранилища.
    var field by remember { mutableStateOf(TextFieldValue(store.draft.value, TextRange(store.draft.value.length))) }
    LaunchedEffect(storeDraft) {
        // Применяем только актуальный черновик: запоздалое значение не должно откатывать набор.
        if (store.draft.value == storeDraft && field.text != storeDraft) {
            field = TextFieldValue(storeDraft, TextRange(storeDraft.length))
        }
    }
    val canSend = remember(storeDraft, attachments, generating) { store.canSend || (attachments.isNotEmpty() && !generating) }
    val shape = RoundedCornerShape(27.dp)
    val reduce = LocalReduceMotion.current

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(0.7.dp, colors.divider, shape)
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 5.dp)
            .then(if (reduce) Modifier else Modifier.animateContentSize(tween(200))),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val errorText = error
        if (errorText != null && showError) {
            Row(Modifier.padding(horizontal = 4.dp).padding(top = 4.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp))
                Text(errorText, fontSize = 12.sp, color = colors.secondary, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Close, t("Закрыть ошибку", "Dismiss error"), tint = colors.secondary,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { store.clearError() }.padding(5.dp)
                        .testTag("chat.error.dismiss"))
            }
        }
        if (editing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                Text(" " + t("Редактирование сообщения", "Editing message"), fontSize = 12.sp, color = colors.accent,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Cancel, t("Отменить редактирование", "Cancel editing"), tint = colors.accent,
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable { store.cancelEditing() }.padding(6.dp)
                        .testTag("composer.edit.cancel"))
            }
        }
        AnimatedVisibility(quote != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("composer.quote")) {
                QuoteChip(quote.orEmpty(), Modifier.weight(1f))
                Icon(Icons.Rounded.Cancel, t("Убрать цитату", "Remove quote"), tint = colors.secondary,
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable { store.clearQuote() }.padding(6.dp)
                        .testTag("composer.quote.remove"))
            }
        }
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(attachments, key = { it.id }) { attachment ->
                    PendingAttachmentChip(attachment, t("Удалить вложение ", "Remove attachment ") + attachment.name, english) {
                        store.removeAttachment(attachment.id)
                    }
                }
            }
        }
        if (voice.mode) {
            VoicePanel(voice, english, onVoiceCancel)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
        }
        Row(verticalAlignment = Alignment.Top) {
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (store.draft.value != it.text) store.draft.value = it.text
                },
                textStyle = TextStyle(color = colors.foreground, fontSize = (19 * fontScale).sp, lineHeight = (25 * fontScale).sp),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .padding(horizontal = 5.dp)
                    .padding(top = 6.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .semantics { contentDescription = t("Сообщение", "Message") }
                    .testTag("chat.composer"),
                decorationBox = { inner ->
                    Box {
                        if (field.text.isEmpty()) {
                            Text(t("Напишите сообщение…", "Message Honer AI…"), color = colors.secondary,
                                fontSize = (19 * fontScale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            IconBox(Icons.Outlined.EmojiEmotions, t("Стикеры", "Stickers"), "composer.stickers", colors.foreground, 26, onStickers)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModePill(Icons.Rounded.Psychology, t("Рассуждение", "Reason"), reasoning, english, "composer.reasoning") {
                store.setReasoningEnabled(!reasoning)
            }
            ModePill(Icons.Rounded.Language, t("Поиск", "Search"), search, english, "composer.search") {
                store.setSearchEnabled(!search)
            }
            Spacer(Modifier.weight(1f))
            IconBox(
                if (attachmentsOpen) Icons.Rounded.HighlightOff else Icons.Rounded.AddCircleOutline,
                if (attachmentsOpen) t("Закрыть вложения", "Close attachments") else t("Добавить вложение", "Add attachment"),
                "composer.attachments", colors.foreground, 27, onToggleAttachments,
            )
            // agent: во время ожидания подтверждения даём «Отправить» (для ответа «да»/«отмена»).
            val awaitingConfirm = pendingAgentAction != null
            if (generating && !awaitingConfirm) {
                Box(
                    Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable { store.stop() }
                        .semantics { contentDescription = t("Остановить ответ", "Stop response") }.testTag("chat.stop"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(colors.foreground), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Stop, null, tint = colors.background, modifier = Modifier.size(16.dp))
                    }
                }
            } else if ((awaitingConfirm || canSend) && !voice.mode) {
                Box(
                    Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable(onClick = onSend)
                        .semantics { contentDescription = t("Отправить", "Send") }.testTag("chat.send"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            // Микрофон уступает место «Отправить», когда есть текст (на узком экране иначе сплющивается);
            // в голосовом режиме он виден всегда — из него всегда есть выход.
            if (voice.mode || ((generating || !canSend) && !awaitingConfirm)) IconBox(
                if (voice.holding) Icons.Rounded.StopCircle else Icons.Rounded.MicNone,
                if (voice.holding) t("Остановить и отправить", "Stop and send") else t("Голосовой ввод", "Voice input"),
                "chat.voice", if (voice.holding) colors.accent else colors.foreground, 28, onVoiceTap,
            )
        }
    }
}

@Composable
private fun IconBox(icon: ImageVector, label: String, tag: String, tint: Color, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size.dp)) }
}

@Composable
private fun ModePill(icon: ImageVector, label: String, selected: Boolean, english: Boolean, tag: String, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (selected) colors.accent else colors.secondary
    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(32.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                .border(0.8.dp, if (selected) colors.accent.copy(alpha = 0.27f) else colors.divider, CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = if (english) (if (selected) "On" else "Off") else (if (selected) "Включено" else "Выключено")
                }
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
        }
    }
}

/** Идёт голосовой ввод: распознанный текст виден сразу, отмена — отдельной кнопкой. */
@Composable
private fun VoicePanel(voice: VoiceUi, english: Boolean, onCancel: () -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val title = when {
        voice.finalizing -> t("Распознаю…", "Finishing…")
        voice.holding && !voice.recording -> t("Подключаю микрофон…", "Starting microphone…")
        else -> t("Говорите…", "Listening…")
    }
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(1f, 0.45f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("chat.voice.hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (voice.recording) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, null, tint = colors.accent,
            modifier = Modifier.size(24.dp).graphicsLayer { alpha = if (voice.recording) pulse else 1f })
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
            Text(
                voice.transcript.ifEmpty { t("Нажмите ■, чтобы отправить, или «Отмена».", "Tap ■ to send or Cancel.") },
                fontSize = 13.sp, color = colors.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat.voice.transcript"),
            )
        }
        Text(
            t("Отмена", "Cancel"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.secondary,
            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel)
                .padding(horizontal = 6.dp, vertical = 12.dp).testTag("chat.voice.cancel"),
        )
    }
}
