package com.honerai.admin.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.core.BlockTerm
import com.honerai.admin.core.HistoryText
import com.honerai.admin.core.Numbers
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.AdminAction
import com.honerai.admin.data.AdminNote
import com.honerai.admin.data.ClientReport
import com.honerai.admin.data.DeviceEvent
import com.honerai.admin.data.DeviceUsage
import com.honerai.admin.data.Overrides
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.SecondaryButton
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.home.ReportRow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId

/** Заголовок раздела карточки. */
@Composable
fun CardLabel(text: String) {
    Text(text, fontSize = 14.sp, color = HonerTheme.colors.secondary, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

/** Заголовок раздела с действием справа (например «Очистить»). */
@Composable
fun CardLabelWithAction(text: String, action: String?, onAction: () -> Unit) {
    val colors = HonerTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, color = colors.secondary, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, fontSize = 14.sp, color = colors.danger, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/** Кто и когда открывал карточку пользователя. */
@Composable
fun ProfileViewsCard(views: List<com.honerai.admin.data.ProfileView>, now: Instant, zone: ZoneId) {
    val english = LocalEnglish.current
    val colors = HonerTheme.colors
    SectionCard {
        if (views.isEmpty()) {
            Text(tr("Карточку ещё не открывали", "No one has opened the card yet"), fontSize = 15.sp, color = colors.secondary, modifier = Modifier.padding(16.dp))
        }
        views.take(50).forEachIndexed { i, v ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.adminName?.takeIf { it.isNotBlank() } ?: tr("Администратор", "Admin"), fontSize = 15.sp, color = colors.foreground)
                    if (v.adminRole == "developer") {
                        Spacer(Modifier.width(6.dp))
                        Text(tr("разработчик", "developer"), fontSize = 11.sp, color = Color(0xFF8B5CF6), fontWeight = FontWeight.Medium)
                    }
                }
                Text(Times.parse(v.at)?.let { PresenceText.ago(it, now, zone, english) }.orEmpty(), fontSize = 12.sp, color = colors.secondary)
            }
            if (i < minOf(views.size, 50) - 1) Divider()
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(HonerTheme.colors.divider))
}

@Composable
private fun KeyValue(label: String, value: String, valueColor: Color = HonerTheme.colors.foreground) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = HonerTheme.colors.secondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 15.sp, color = valueColor, textAlign = TextAlign.End)
    }
}

/** Токены ИИ за сегодня и всего, запросы, ошибки. */
@Composable
fun UsageCard(usage: DeviceUsage?, reports: Int) {
    val english = LocalEnglish.current
    val colors = HonerTheme.colors
    val u = usage ?: DeviceUsage()
    fun tokens(p: Long, c: Long, t: Long) =
        Numbers.compact(t, english) + " (" + Numbers.compact(p, english) + " + " + Numbers.compact(c, english) + ")"
    SectionCard {
        KeyValue(tr("Токены сегодня", "Tokens today"), tokens(u.today.prompt, u.today.completion, u.today.total))
        Divider()
        KeyValue(tr("Токены всего", "Tokens total"), tokens(u.total.prompt, u.total.completion, u.total.total))
        Divider()
        KeyValue(tr("Запросов к ИИ", "AI requests"), tr("сегодня ", "today ") + u.today.requests + tr(" · всего ", " · total ") + u.total.requests)
        Divider()
        KeyValue(tr("Ошибок ИИ", "AI errors"), tr("сегодня ", "today ") + u.today.errors + tr(" · всего ", " · total ") + u.total.errors,
            if (u.today.errors > 0) colors.danger else colors.foreground)
        Divider()
        KeyValue(tr("Отчётов об ошибках", "Error reports"), reports.toString(), if (reports > 0) colors.away else colors.foreground)
    }
    Text(tr("Токены: запрос + ответ.", "Tokens: prompt + completion."), fontSize = 12.sp, color = colors.secondary,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

/**
 * Ограничения пользователя. [onChange] получает патч (только меняемый ключ; JsonNull — снять).
 * Приложение применяет их само; сервер только хранит и доставляет.
 */
@Composable
fun OverridesCard(overrides: Overrides, busy: Boolean, onChange: (JsonObject) -> Unit) {
    val colors = HonerTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    var limitDialog by remember { mutableStateOf(false) }
    // Какое ограничение настраиваем: "muteAi" | "blockSupport" | null.
    var restrictKey by remember { mutableStateOf<String?>(null) }

    fun restrictionSubtitle(r: com.honerai.admin.data.Restriction, base: String): String {
        if (!r.active) return base
        val parts = buildList {
            r.reason?.takeIf { it.isNotBlank() }?.let { add(tr("причина: ", "reason: ") + it) }
            Times.parse(r.until)?.let { add(tr("до ", "until ") + PresenceText.dateTime(it, zone)) } ?: add(tr("навсегда", "permanently"))
        }
        return parts.joinToString(" · ")
    }
    SectionCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Язык интерфейса", "Interface language"), fontSize = 15.sp, color = colors.foreground)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HonerPill(tr("Как выбрал пользователь", "User's choice"), overrides.forceLanguage == null,
                    { if (!busy) onChange(JsonObject(mapOf("forceLanguage" to kotlinx.serialization.json.JsonNull))) })
                HonerPill("Русский", overrides.forceLanguage == "ru", { if (!busy) onChange(JsonObject(mapOf("forceLanguage" to JsonPrimitive("ru")))) })
                HonerPill("English", overrides.forceLanguage == "en", { if (!busy) onChange(JsonObject(mapOf("forceLanguage" to JsonPrimitive("en")))) })
            }
        }
        Divider()
        ToggleRow(
            title = tr("Запретить поиск в интернете", "Disable web search"),
            subtitle = null,
            checked = overrides.disableSearch == true, busy = busy,
            onChange = { onChange(JsonObject(mapOf("disableSearch" to if (it) JsonPrimitive(true) else kotlinx.serialization.json.JsonNull))) },
        )
        Divider()
        ToggleRow(
            title = tr("Отключить ИИ", "Disable AI"),
            subtitle = restrictionSubtitle(overrides.mute, tr("Сервер не пустит запросы к нейросети.", "The server blocks all AI requests.")),
            checked = overrides.mute.active, busy = busy,
            onChange = { if (it) restrictKey = "muteAi" else onChange(JsonObject(mapOf("muteAi" to kotlinx.serialization.json.JsonNull))) },
        )
        Divider()
        ToggleRow(
            title = tr("Запретить писать в поддержку", "Disable support chat"),
            subtitle = restrictionSubtitle(overrides.support, tr("Сервер отклонит сообщения пользователя в поддержку.", "The server rejects the user's messages to support.")),
            checked = overrides.support.active, busy = busy,
            onChange = { if (it) restrictKey = "blockSupport" else onChange(JsonObject(mapOf("blockSupport" to kotlinx.serialization.json.JsonNull))) },
        )
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !busy) { limitDialog = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Сообщений в день", "Messages per day"), fontSize = 15.sp, color = colors.foreground, modifier = Modifier.weight(1f))
            Text(overrides.maxMessagesPerDay?.toString() ?: tr("без ограничений", "unlimited"), fontSize = 15.sp,
                color = if (overrides.maxMessagesPerDay != null) colors.away else colors.secondary)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Edit, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
        }
    }
    Text(tr("«Отключить ИИ» и «поддержку» действуют сразу на сервере; язык, поиск и лимит — при следующем подключении приложения.",
        "“Disable AI” and support take effect immediately on the server; language, search and the daily limit apply on the app's next connection."),
        fontSize = 12.sp, color = colors.secondary, modifier = Modifier.padding(start = 6.dp, top = 6.dp))

    if (limitDialog) {
        var value by remember { mutableStateOf(overrides.maxMessagesPerDay?.toString().orEmpty()) }
        val parsed = value.trim().toIntOrNull()
        ConfirmDialog(
            title = tr("Сообщений в день", "Messages per day"),
            text = tr("Пусто — без ограничений.", "Empty — unlimited."),
            confirm = tr("Сохранить", "Save"),
            onDismiss = { limitDialog = false },
            onConfirm = {
                if (value.isBlank() || (parsed != null && parsed in 1..100_000)) {
                    limitDialog = false
                    onChange(JsonObject(mapOf("maxMessagesPerDay" to (parsed?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))))
                }
            },
            content = {
                HonerField(value, { value = it.filter(Char::isDigit).take(6) }, tr("Число (1–100000)", "Number (1–100000)"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
        )
    }

    restrictKey?.let { key ->
        val isMute = key == "muteAi"
        val current = if (isMute) overrides.mute else overrides.support
        var reason by remember(key) { mutableStateOf(current.reason.orEmpty()) }
        var term by remember(key) { mutableStateOf(BlockTerm.FOREVER) }
        ConfirmDialog(
            title = if (isMute) tr("Отключить ИИ пользователю", "Disable AI for user") else tr("Запретить писать в поддержку", "Disable support chat"),
            text = tr("Укажите причину и срок. Пользователь увидит причину.", "Set a reason and duration. The user will see the reason."),
            confirm = tr("Применить", "Apply"), destructive = true,
            onDismiss = { restrictKey = null },
            onConfirm = {
                val until = term.until(Instant.now())
                val payload = JsonObject(mapOf(
                    "until" to (until?.let { JsonPrimitive(it.toString()) } ?: kotlinx.serialization.json.JsonNull),
                    "reason" to (reason.trim().takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
                ))
                onChange(JsonObject(mapOf(key to payload)))
                restrictKey = null
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HonerField(reason, { reason = it }, tr("Причина (необязательно)", "Reason (optional)"), singleLine = false, minLines = 2)
                    BlockTermPicker(term) { term = it }
                }
            },
        )
    }
}

/** Строка-переключатель в карточке ограничений: заголовок, пояснение и Switch (включённое — красным). */
@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    val colors = HonerTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, color = colors.foreground)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = colors.secondary)
        }
        Switch(
            checked = checked, enabled = !busy, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.danger, checkedThumbColor = Color.White),
        )
    }
}

/** История версий: установка, обновления (с какой на какую), вероятное удаление. */
@Composable
fun EventsCard(events: List<DeviceEvent>, zone: ZoneId) {
    val english = LocalEnglish.current
    val colors = HonerTheme.colors
    SectionCard {
        if (events.isEmpty()) {
            Text(tr("Пока нет событий", "No events yet"), fontSize = 15.sp, color = colors.secondary, modifier = Modifier.padding(16.dp))
        }
        events.take(30).forEachIndexed { i, e ->
            KeyValue(HistoryText.event(e.kind, e.fromVersion, e.toVersion, english), PresenceText.dateTime(Times.parse(e.at), zone),
                if (e.kind == "uninstall") colors.danger else colors.foreground)
            if (i < minOf(events.size, 30) - 1) Divider()
        }
    }
}

/** Заметки администраторов: добавить, изменить, удалить. */
@Composable
fun NotesCard(notes: List<AdminNote>, busy: Boolean, zone: ZoneId, onAdd: (String) -> Unit, onEdit: (AdminNote, String) -> Unit, onDelete: (AdminNote) -> Unit) {
    val colors = HonerTheme.colors
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AdminNote?>(null) }
    var deleting by remember { mutableStateOf<AdminNote?>(null) }
    SectionCard {
        notes.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(n.text, fontSize = 15.sp, color = colors.foreground)
                    Text(
                        listOfNotNull(n.adminName, PresenceText.dateTime(Times.parse(n.updatedAt ?: n.createdAt), zone)).joinToString(" · ") +
                            (if (n.updatedAt != null) tr(" (изменено)", " (edited)") else ""),
                        fontSize = 12.sp, color = colors.secondary,
                    )
                }
                Icon(Icons.Rounded.Edit, tr("Изменить", "Edit"), tint = colors.secondary,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { editing = n }.padding(10.dp))
                Icon(Icons.Rounded.DeleteOutline, tr("Удалить", "Delete"), tint = colors.danger,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { deleting = n }.padding(10.dp))
            }
            Divider()
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HonerField(draft, { draft = it }, tr("Новая заметка", "New note"), singleLine = false, minLines = 2)
            SecondaryButton(tr("Добавить заметку", "Add note"), {
                if (draft.isNotBlank()) { onAdd(draft.trim()); draft = "" }
            }, Modifier.fillMaxWidth(), enabled = !busy && draft.isNotBlank())
        }
    }
    editing?.let { note ->
        var text by remember(note.id) { mutableStateOf(note.text) }
        ConfirmDialog(
            title = tr("Изменить заметку", "Edit note"), text = "", confirm = tr("Сохранить", "Save"),
            onDismiss = { editing = null },
            onConfirm = { if (text.isNotBlank()) { editing = null; onEdit(note, text.trim()) } },
            content = { HonerField(text, { text = it }, tr("Заметка", "Note"), singleLine = false, minLines = 3) },
        )
    }
    deleting?.let { note ->
        ConfirmDialog(
            title = tr("Удалить заметку?", "Delete the note?"), text = note.text.take(200), confirm = tr("Удалить", "Delete"),
            destructive = true, onDismiss = { deleting = null }, onConfirm = { deleting = null; onDelete(note) },
        )
    }
}

/** Журнал действий администраторов с этим пользователем. */
@Composable
fun ActionsCard(actions: List<AdminAction>, now: Instant, zone: ZoneId) {
    val english = LocalEnglish.current
    val colors = HonerTheme.colors
    SectionCard {
        if (actions.isEmpty()) {
            Text(tr("Действий ещё не было", "No actions yet"), fontSize = 15.sp, color = colors.secondary, modifier = Modifier.padding(16.dp))
        }
        actions.take(30).forEachIndexed { i, a ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text(HistoryText.action(a.action, english) + actionDetail(a, english, zone), fontSize = 15.sp, color = colors.foreground)
                Text(listOfNotNull(a.adminName, Times.parse(a.at)?.let { PresenceText.ago(it, now, zone, english) }).joinToString(" · "),
                    fontSize = 12.sp, color = colors.secondary)
            }
            if (i < minOf(actions.size, 30) - 1) Divider()
        }
    }
}

/** Подробности действия: причина и срок блокировки, заголовок уведомления. */
private fun actionDetail(a: AdminAction, english: Boolean, zone: ZoneId): String {
    val d = a.detail as? JsonObject ?: return ""
    fun str(key: String) = (d[key] as? JsonPrimitive)?.contentOrNull
    val parts = listOfNotNull(
        str("reason")?.takeIf { it.isNotBlank() },
        str("until")?.let { Times.parse(it) }?.let { (if (english) "until " else "до ") + PresenceText.dateTime(it, zone) },
        str("title")?.let { "«$it»" },
    )
    return if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
}

/** Последние отчёты этого пользователя; нажатие раскрывает стек. */
@Composable
fun UserReportsCard(reports: List<ClientReport>, now: Instant) {
    val colors = HonerTheme.colors
    var expanded by remember { mutableStateOf<String?>(null) }
    SectionCard {
        if (reports.isEmpty()) {
            Text(tr("Ошибок нет", "No errors"), fontSize = 15.sp, color = colors.secondary, modifier = Modifier.padding(16.dp))
        }
        reports.forEachIndexed { i, r ->
            ReportRow(r, now, compact = false, expanded = expanded == r.id, onClick = { expanded = if (expanded == r.id) null else r.id })
            if (i < reports.lastIndex) Divider()
        }
    }
}

/** Выбор срока блокировки в диалоге. */
@Composable
fun BlockTermPicker(term: BlockTerm, onChange: (BlockTerm) -> Unit) {
    val english = LocalEnglish.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Срок", "Duration"), fontSize = 13.sp, color = HonerTheme.colors.secondary, fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockTerm.entries.forEach { t -> HonerPill(t.label(english), t == term, { onChange(t) }) }
        }
    }
}
