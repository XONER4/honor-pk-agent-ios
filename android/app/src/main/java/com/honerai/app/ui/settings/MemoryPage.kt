package com.honerai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.honerai.app.data.HonorMemory
import com.honerai.app.ui.theme.HonerTheme
import java.time.ZoneId

// «Память Honer AI» (порт MemorySettingsPage + MemoryEditorSheet).

internal const val MAX_MEMORY_COUNT = 5000
internal const val MAX_MEMORY_LENGTH = 1200

/** Что редактируется: новый факт (memory == null) или существующий. */
private data class MemoryEditorRequest(val memory: HonorMemory?)

@Composable
internal fun MemorySettingsPage(onBack: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    val store = container.store
    val colors = HonerTheme.colors
    val memories by store.memories.collectAsState()
    val memoryEnabled by store.memoryEnabled.collectAsState()
    val crossChat by settings.crossChatMemoryEnabled.collectAsState()
    val fontScale by settings.fontScale.collectAsState()
    var editor by remember { mutableStateOf<MemoryEditorRequest?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HonorMemory?>(null) }
    fun t(ru: String, en: String) = settings.text(ru, en)

    SettingsPageScaffold(t("Память Honer AI", "Honer AI memory"), "settings.page.memory", onBack) {
        item(key = "toggle") {
            SettingsGroup {
                SettingsToggleRow(t("Использовать память", "Use memory"), memoryEnabled, { store.setMemoryEnabled(it) }, tag = "memory.enabled")
                SettingsDivider(16)
                SettingsToggleRow(
                    t("Память между чатами", "Memory across chats"), crossChat, { settings.setCrossChatMemoryEnabled(it) },
                    subtitle = t("Honer AI учитывает важное из других ваших чатов.", "Honer AI takes into account what matters from your other chats."),
                    enabled = memoryEnabled, tag = "memory.crossChat",
                )
            }
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    if (memoryEnabled) t("Память включена", "Memory enabled") else t("Память выключена", "Memory disabled"),
                    color = colors.secondary, fontSize = 13.sp, modifier = Modifier.testTag("memory.status"),
                )
                Text(
                    t("Honer AI использует только факты, которые вы сохранили сами. При выключении они остаются в списке, но не добавляются в следующие запросы.",
                        "Honer AI uses only the facts you explicitly save. When turned off, memories stay in this list but are not included in future requests."),
                    color = colors.secondary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }
        item(key = "header") {
            Column(Modifier.padding(top = 20.dp)) {
                Text(t("Сохранённые факты", "Saved facts"), color = colors.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = if (memories.isEmpty()) 26.dp else 0.dp, bottomEnd = if (memories.isEmpty()) 26.dp else 0.dp)).background(cardBackground)) {
                    SettingsButtonRow(t("Добавить в память", "Add memory"), Icons.Outlined.Add, enabled = memories.size < MAX_MEMORY_COUNT, tag = "memory.add") {
                        editor = MemoryEditorRequest(null)
                    }
                    if (memories.isEmpty()) {
                        SettingsDivider(16)
                        Text(
                            t("Пока ничего не сохранено. Добавьте предпочтение или важный факт — либо выберите «Запомнить» в меню сообщения.",
                                "No memories yet. Add a preference or useful fact, or choose Remember from a message menu."),
                            color = colors.secondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp).testTag("memory.empty"),
                        )
                    }
                }
            }
        }
        itemsIndexed(memories, key = { _, m -> m.id }) { index, memory ->
            val last = index == memories.lastIndex
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = if (last) 26.dp else 0.dp, bottomEnd = if (last) 26.dp else 0.dp))
                    .background(cardBackground),
            ) {
                SettingsDivider(16)
                Row(
                    Modifier.fillMaxWidth().clickable { editor = MemoryEditorRequest(memory) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
                        .testTag("memory.row.${memory.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(memory.text, color = colors.foreground, fontSize = (16 * fontScale).sp)
                        Text(dateText(settings.isEnglish, memory.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()), color = colors.secondary, fontSize = 12.sp)
                    }
                    IconButton(onClick = { pendingDelete = memory }, modifier = Modifier.testTag("memory.delete.${memory.id}")) {
                        Icon(Icons.Outlined.Delete, contentDescription = t("Удалить", "Delete"), tint = colors.secondary)
                    }
                }
            }
        }
        item(key = "count") {
            SettingsFootnote("${memories.size} / $MAX_MEMORY_COUNT")
        }
        if (memories.isNotEmpty()) {
            item(key = "clear") {
                SettingsGroup {
                    SettingsButtonRow(t("Очистить память", "Clear memory"), destructive = true, tag = "memory.clear") { confirmClear = true }
                }
            }
        }
    }

    editor?.let { request -> MemoryEditorDialog(request.memory) { editor = null } }
    if (confirmClear) {
        ConfirmDialog(
            t("Очистить память Honer AI?", "Clear Honer AI memory?"),
            t("Все сохранённые факты будут удалены. Переписка останется.", "All saved facts will be deleted. Your conversations will be kept."),
            t("Очистить", "Clear"), t("Отмена", "Cancel"), confirmTag = "memory.clear.confirm",
            onConfirm = { store.clearMemories() }, onDismiss = { confirmClear = false },
        )
    }
    pendingDelete?.let { memory ->
        ConfirmDialog(
            t("Удалить этот факт?", "Delete this fact?"), memory.text.take(200), t("Удалить", "Delete"), t("Отмена", "Cancel"),
            onConfirm = { store.deleteMemory(memory.id) }, onDismiss = { pendingDelete = null },
        )
    }
}

/** Редактор факта: новый или существующий. Сохранение — только по кнопке. */
@Composable
private fun MemoryEditorDialog(memory: HonorMemory?, onDismiss: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    val store = container.store
    val colors = HonerTheme.colors
    val memories by store.memories.collectAsState()
    val fontScale by settings.fontScale.collectAsState()
    var draft by remember { mutableStateOf(memory?.text.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val cleaned = draft.trim()
    val canSave = cleaned.isNotEmpty() && cleaned.length <= MAX_MEMORY_LENGTH && (memory != null || memories.size < MAX_MEMORY_COUNT)
    fun t(ru: String, en: String) = settings.text(ru, en)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(pageBackground).imePadding().testTag("memory.editor.page")) {
            SettingsTopBar(
                title = if (memory == null) t("Запомнить", "Remember") else t("Изменить память", "Edit memory"),
                onBack = null,
                trailing = {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("memory.editor.cancel")) { Text(t("Отмена", "Cancel"), color = colors.accent) }
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val ok = if (memory != null) store.updateMemory(memory.id, cleaned) else store.addMemory(cleaned)
                            if (ok) onDismiss() else validationError = store.errorMessage.value ?: t("Не удалось сохранить.", "Could not save.")
                        },
                        modifier = Modifier.testTag("memory.editor.save"),
                    ) { Text(t("Сохранить", "Save"), color = if (canSave) colors.accent else colors.secondary, fontWeight = FontWeight.SemiBold) }
                },
            )
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 190.dp).clip(RoundedCornerShape(20.dp)).background(cardBackground)
                            .border(0.7.dp, colors.divider, RoundedCornerShape(20.dp)).padding(16.dp),
                    ) {
                        if (draft.isEmpty()) Text(t("Факт для памяти Honer AI", "Fact for Honer AI memory"), color = colors.secondary, fontSize = (17 * fontScale).sp)
                        BasicTextField(
                            value = draft, onValueChange = { draft = it; validationError = null },
                            textStyle = TextStyle(color = colors.foreground, fontSize = (17 * fontScale).sp, lineHeight = (23 * fontScale).sp),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).focusRequester(focus).testTag("memory.editor.text"),
                        )
                    }
                    Text(
                        "${cleaned.length} / $MAX_MEMORY_LENGTH", color = if (cleaned.length > MAX_MEMORY_LENGTH) DestructiveRed else colors.secondary,
                        fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().testTag("memory.editor.count"),
                    )
                    SettingsFootnote(
                        t("Оставьте только факт или предпочтение, которое стоит помнить. Honer AI будет учитывать его в следующих ответах, пока память включена. Сохранение — только по вашей кнопке.",
                            "Keep only a fact or preference worth remembering. Honer AI will use it in future replies while memory is enabled. Nothing is saved until you tap Save."),
                    )
                    validationError?.let { Text(it, color = DestructiveRed, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp).testTag("memory.editor.error")) }
                    if (memory == null && memories.size >= MAX_MEMORY_COUNT) {
                        SettingsFootnote(t("Память заполнена. Удалите ненужный факт, чтобы добавить новый.", "Memory is full. Delete an existing fact before adding another."))
                    }
                    if (memory != null) {
                        SettingsGroup {
                            SettingsButtonRow(t("Удалить этот факт", "Delete this fact"), Icons.Outlined.Delete, destructive = true, tag = "memory.editor.delete") { confirmDelete = true }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && memory != null) {
        ConfirmDialog(
            t("Удалить этот факт?", "Delete this fact?"), null, t("Удалить", "Delete"), t("Отмена", "Cancel"), confirmTag = "memory.editor.delete.confirm",
            onConfirm = { store.deleteMemory(memory.id); onDismiss() }, onDismiss = { confirmDelete = false },
        )
    }
}
