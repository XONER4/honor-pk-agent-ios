package com.honerai.admin.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.ScheduleText
import com.honerai.admin.data.AiWindow
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.SecondaryButton
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.common.ToastHost
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberToastState
import com.honerai.admin.ui.home.AiSwitchCard
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.launch

/** ИИ: общий выключатель и расписание (окна, когда ИИ работает). */
@Composable
fun AiSettingsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val repo = container.repo
    val state by repo.ai.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    var editing by remember { mutableStateOf<Pair<Int, AiWindow>?>(null) } // индекс (-1 — новое окно) и черновик
    val schedule = state.settings?.schedule.orEmpty()

    LaunchedEffect(Unit) { repo.refreshAi() }

    fun save(next: List<AiWindow>) {
        scope.launch {
            val error = repo.updateAi(schedule = next)
            toast.show(error ?: if (english) "Schedule saved" else "Расписание сохранено")
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(tr("Нейросеть", "AI"), onBack = { navigator.pop() })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AiSwitchCard(state, onToggle = { repo.updateAi(enabled = it) }, onOpen = {}, onError = { toast.show(it) })
                    Text(
                        tr("Когда ИИ выключен, пользователи видят «ИИ временно отключён администратором», а групповой ИИ в чатах молчит.",
                            "When the AI is off, users see “AI is temporarily disabled by the administrator” and the group AI stays silent."),
                        fontSize = 13.sp, color = colors.secondary, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(tr("Расписание", "Schedule"), fontSize = 14.sp, color = colors.secondary, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        SectionCard {
                            if (schedule.isEmpty()) {
                                Text(tr("Расписания нет — ИИ работает всегда, пока включён.", "No schedule — the AI works whenever it is on."),
                                    fontSize = 15.sp, color = colors.secondary, modifier = Modifier.padding(16.dp))
                            }
                            schedule.forEachIndexed { index, w ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { editing = index to w }.padding(start = 16.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(ScheduleText.window(w, english), fontSize = 15.sp, color = colors.foreground, modifier = Modifier.weight(1f))
                                    IconCircle(Icons.Rounded.DeleteOutline, tr("Удалить", "Delete"),
                                        { save(schedule.filterIndexed { i, _ -> i != index }) }, tint = colors.danger)
                                }
                            }
                        }
                    }
                    Text(
                        tr("ИИ работает только внутри этих промежутков. Время — по часовому поясу ", "The AI works only inside these windows. Time zone: ") +
                            (state.settings?.timezone ?: "Europe/Moscow") + ".",
                        fontSize = 12.sp, color = colors.secondary, modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    SecondaryButton(tr("Добавить промежуток", "Add window"), { editing = -1 to AiWindow(listOf(1, 2, 3, 4, 5), "09:00", "21:00") },
                        Modifier.padding(horizontal = 12.dp).fillMaxWidth(), icon = Icons.Rounded.Add, enabled = state.settings != null && !state.busy)
                }
            }
        }
        ToastHost(toast, bottom = 40.dp)
    }

    editing?.let { (index, draft) ->
        WindowDialog(draft, onDismiss = { editing = null }) { w ->
            editing = null
            save(if (index < 0) schedule + w else schedule.mapIndexed { i, old -> if (i == index) w else old })
        }
    }
}

/** Редактор промежутка: дни недели и время «с — до». */
@Composable
private fun WindowDialog(initial: AiWindow, onDismiss: () -> Unit, onSave: (AiWindow) -> Unit) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    var days by remember { mutableStateOf(initial.days.toSet()) }
    var from by remember { mutableStateOf(initial.from) }
    var to by remember { mutableStateOf(initial.to) }
    val valid = ScheduleText.isValidTime(from) && ScheduleText.isValidTime(to)
    ConfirmDialog(
        title = tr("Когда ИИ работает", "When the AI works"),
        text = "",
        confirm = tr("Сохранить", "Save"),
        onDismiss = onDismiss,
        onConfirm = { if (valid) onSave(AiWindow(days.sorted(), from, to)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in 1..7) {
                        HonerPill(ScheduleText.dayName(d, english), d in days, { days = if (d in days) days - d else days + d })
                    }
                }
                Text(tr("Ни один день не выбран — каждый день.", "No day selected — every day."), fontSize = 12.sp, color = colors.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HonerField(from, { from = it.take(5) }, tr("С (ЧЧ:ММ)", "From (HH:MM)"), Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    HonerField(to, { to = it.take(5) }, tr("До (ЧЧ:ММ)", "To (HH:MM)"), Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (!valid) {
                    Text(tr("Время в формате 09:00", "Time as 09:00"), fontSize = 12.sp, color = colors.danger)
                } else {
                    Text(
                        if (from == to) tr("Весь день", "All day")
                        else if (to < from) tr("Через полночь: с $from до $to следующего дня", "Across midnight: $from to $to next day")
                        else "$from–$to",
                        fontSize = 12.sp, color = colors.secondary, fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(1.dp))
            }
        },
    )
}
