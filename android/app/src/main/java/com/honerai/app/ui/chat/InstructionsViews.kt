package com.honerai.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.data.ChatInstruction
import com.honerai.app.data.MessageRole
import com.honerai.app.data.SavedInstruction
import com.honerai.app.ui.common.AddIconButton
import com.honerai.app.ui.common.HonerSheet
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay

/** Текст инструкции в одну строку для плашки. */
fun instructionPreview(text: String): String = text.replace("\n", " ").replace("**", "").trim()

/**
 * Плашка закреплённой инструкции над перепиской — как закреплённое сообщение в Telegram.
 * Показывает, что нейросеть видит инструкцию в каждом ответе; нажатие открывает список.
 */
@Composable
fun PinnedInstructionBar(instructions: List<ChatInstruction>, english: Boolean, onOpen: () -> Unit) {
    val colors = HonerTheme.colors
    val title = if (instructions.size > 1) {
        if (english) "Instructions · ${instructions.size}" else "Инструкции · ${instructions.size}"
    } else if (english) "Instruction" else "Инструкция"
    val last = instructions.lastOrNull()?.text.orEmpty()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(colors.surface)
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { contentDescription = (if (english) "Pinned instruction: " else "Закреплённая инструкция: ") + last }
                .testTag("chat.instructions.bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(34.dp).clip(CircleShape).background(colors.accent))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PushPin, null, tint = colors.accent, modifier = Modifier.size(12.dp))
                    Text(" $title", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
                }
                Text(instructionPreview(last), fontSize = 14.sp, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
    }
}

private data class EditorTarget(val instructionId: String?, val text: String)

/** Инструкции чата: закреплённые и сохранённые («черновики»). */
@Composable
fun InstructionsSheet(store: ChatStoreApi, chatId: String, english: Boolean, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val conversations by store.conversations.collectAsState()
    val library by store.instructionLibrary.collectAsState()
    val pinned = remember(conversations, chatId) { conversations.firstOrNull { it.id == chatId }?.instructions.orEmpty() }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }

    HonerSheet(
        title = t("Инструкции чата", "Chat instructions"),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("instructions.sheet"),
        leading = { close ->
            TextButton(onClick = close, modifier = Modifier.testTag("instructions.done")) {
                Text(t("Готово", "Done"), color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        trailing = {
            AddIconButton(t("Новая инструкция", "New instruction"), "instructions.add") { editor = EditorTarget(null, "") }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionHeader(t("Закреплено в этом чате", "Pinned in this chat")) }
            if (pinned.isEmpty()) {
                item {
                    Text(
                        t("Здесь пока пусто. Зажмите любое сообщение в чате — своё или ответ Honer AI — и выберите «Закрепить как инструкцию». Или нажмите «+», чтобы написать инструкцию.",
                            "Nothing here yet. Touch and hold any message and choose “Pin as instruction”, or tap + to write one."),
                        fontSize = 14.sp, color = colors.secondary, modifier = Modifier.testTag("instructions.empty"),
                    )
                }
            }
            items(pinned, key = { "p" + it.id }) { item ->
                PinnedRow(item, english,
                    onEdit = { editor = EditorTarget(item.id, item.text) },
                    onUnpin = { store.unpinInstruction(chatId, item.id) },
                    onDelete = { store.deleteInstruction(chatId, item.id) })
            }
            item {
                Text(t("Honer AI видит закреплённые инструкции в каждом ответе этого чата и понимает, что их закрепили вы. Это не память: память — это факты о вас, а инструкции — правила ответа.",
                    "Honer AI sees pinned instructions in every answer of this chat. Memory holds facts; instructions are rules."),
                    fontSize = 12.sp, color = colors.secondary)
            }
            item { Spacer(Modifier.height(10.dp)); SectionHeader(t("Сохранённые инструкции", "Saved instructions")) }
            if (library.isEmpty()) {
                item {
                    Text(t("Откреплённые инструкции сохраняются сюда — их можно закрепить снова в любом чате.",
                        "Unpinned instructions are kept here to reuse in any chat."), fontSize = 14.sp, color = colors.secondary)
                }
            }
            items(library, key = { "s" + it.id }) { saved ->
                SavedRow(saved, english, alreadyPinned = pinned.any { it.text == saved.text },
                    onApply = { store.applySavedInstruction(saved.id, chatId) },
                    onDelete = { store.deleteSavedInstruction(saved.id) })
            }
        }
    }

    editor?.let { target ->
        InstructionEditor(target.instructionId == null, target.text, english, onDismiss = { editor = null }) { value, saveCopy ->
            if (target.instructionId != null) store.updateInstruction(chatId, target.instructionId, value)
            else store.addInstruction(chatId, value)
            if (saveCopy) store.saveInstructionToLibrary(value)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HonerTheme.colors.secondary,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun CardBox(tag: String, content: @Composable () -> Unit) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(0.6.dp, colors.divider, shape)
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag)) { content() }
}

@Composable
private fun PinnedRow(item: ChatInstruction, english: Boolean, onEdit: () -> Unit, onUnpin: () -> Unit, onDelete: () -> Unit) {
    val colors = HonerTheme.colors
    CardBox("instructions.row." + item.id) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PushPin, null, tint = colors.accent, modifier = Modifier.size(12.dp))
                Text(
                    " " + if (item.author == MessageRole.ASSISTANT) (if (english) "Honer AI answer · pinned by you" else "Ответ Honer AI · закрепили вы")
                    else (if (english) "Your text · pinned by you" else "Ваш текст · закрепили вы"),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accent,
                )
            }
            Text(item.text, fontSize = 15.sp, color = colors.foreground, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkButton(if (english) "Edit" else "Изменить", "instructions.edit." + item.id, onEdit)
                LinkButton(if (english) "Unpin" else "Открепить", "instructions.unpin." + item.id, onUnpin)
                Spacer(Modifier.weight(1f))
                TrashButton(if (english) "Delete" else "Удалить", "instructions.delete." + item.id, onDelete)
            }
        }
    }
}

@Composable
private fun SavedRow(saved: SavedInstruction, english: Boolean, alreadyPinned: Boolean, onApply: () -> Unit, onDelete: () -> Unit) {
    val colors = HonerTheme.colors
    CardBox("instructions.saved.row." + saved.id) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(saved.text, fontSize = 15.sp, color = colors.foreground, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkButton(if (english) "Pin in this chat" else "Закрепить в этом чате", "instructions.saved.apply." + saved.id,
                    onApply, enabled = !alreadyPinned)
                Spacer(Modifier.weight(1f))
                TrashButton(if (english) "Delete saved" else "Удалить из сохранённых", "instructions.saved.delete." + saved.id, onDelete)
            }
        }
    }
}

@Composable
private fun LinkButton(title: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = HonerTheme.colors
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        color = if (enabled) colors.accent else colors.secondary.copy(alpha = 0.6f),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp).testTag(tag))
}

@Composable
private fun TrashButton(label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Delete, null, tint = Color(0xFFFF453A), modifier = Modifier.size(19.dp))
    }
}

/** Редактор одной инструкции. */
@Composable
private fun InstructionEditor(isNew: Boolean, initial: String, english: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    var value by remember { mutableStateOf(initial) }
    var saveCopy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }
    HonerSheet(
        title = if (isNew) t("Новая инструкция", "New instruction") else t("Изменить инструкцию", "Edit instruction"),
        onDismiss = onDismiss,
        leading = { close ->
            TextButton(onClick = close) { Text(t("Отмена", "Cancel"), color = colors.accent) }
        },
        trailing = {
            val enabled = value.isNotBlank()
            TextButton(onClick = { onSave(value.trim(), saveCopy); onDismiss() }, enabled = enabled,
                modifier = Modifier.testTag("instructions.editor.save")) {
                Text(t("Закрепить", "Pin"), color = if (enabled) colors.accent else colors.secondary, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("Honer AI будет соблюдать эту инструкцию в каждом ответе этого чата.",
                "Honer AI will follow this instruction in every answer of this chat."), fontSize = 13.sp, color = colors.secondary)
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(color = colors.foreground, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).clip(shape).background(colors.surface)
                    .border(0.7.dp, colors.divider, shape).padding(12.dp).focusRequester(focus).testTag("instructions.editor"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Также сохранить в «Сохранённые»", "Also keep in Saved"), fontSize = 15.sp, color = colors.foreground,
                    modifier = Modifier.weight(1f))
                Switch(checked = saveCopy, onCheckedChange = { saveCopy = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                    modifier = Modifier.testTag("instructions.editor.saveCopy"))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
