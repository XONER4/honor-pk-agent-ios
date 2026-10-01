package com.honerai.admin.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.HonerField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.core.AiState
import com.honerai.admin.core.MetricsState
import com.honerai.admin.core.Numbers
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.ReportsState
import com.honerai.admin.core.ScheduleText
import com.honerai.admin.core.Times
import com.honerai.admin.data.ClientReport
import com.honerai.admin.data.ReportKinds
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Мини-график: линия по значениям (масштаб от 0 или минимума до максимума). */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max()
        val min = minOf(0.0, values.min())
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / span * (size.height - 2f)).toFloat() - 1f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.last()
        drawCircle(color, radius = 2.4.dp.toPx(),
            center = Offset(size.width, size.height - ((last - min) / span * (size.height - 2f)).toFloat() - 1f))
    }
}

/** Карточка метрики: крупное значение, подпись, второстепенная строка и мини-график. */
@Composable
private fun MetricCard(label: String, value: String, hint: String?, series: List<Double>, accent: Color, modifier: Modifier) {
    val colors = HonerTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 12.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        Text(hint.orEmpty(), fontSize = 11.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Sparkline(series, accent.copy(alpha = 0.85f), Modifier.fillMaxWidth().height(26.dp))
    }
}

/** Живые метрики: онлайн, запросы/с, токены, ошибки, задержка ИИ. Обновляются кадром раз в 5 с. */
@Composable
fun MetricsGrid(state: MetricsState, onRetry: () -> Unit) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val m = state.metrics
    val h = state.history
    val cards = listOf(
        MetricData(tr("Онлайн", "Online"), m?.online?.toString() ?: "—",
            m?.let { tr("в фоне: ", "background: ") + it.inBackground }, h.series { it.online.toDouble() }, colors.online),
        MetricData(tr("Запросов/с", "Requests/s"), m?.let { Numbers.rate(it.rps, english) } ?: "—",
            m?.let { tr("за минуту: ", "last min: ") + it.requests1m }, h.series { it.rps }, colors.accent),
        MetricData(tr("Токены сегодня", "Tokens today"), m?.let { Numbers.compact(it.tokensToday, english) } ?: "—",
            m?.let { tr("всего: ", "total: ") + Numbers.compact(it.tokensTotal, english) }, h.series { it.tokensToday.toDouble() }, colors.foreground),
        MetricData(tr("Ошибки сегодня", "Errors today"), m?.errorsToday?.toString() ?: "—",
            m?.let { tr("ИИ: ", "AI: ") + it.aiErrorsToday + tr(" · отчёты: ", " · reports: ") + it.reportsToday },
            h.series { it.errorsToday.toDouble() }, if ((m?.errorsToday ?: 0) > 0) colors.danger else colors.foreground),
        MetricData(tr("Ответ ИИ", "AI latency"), Numbers.latency(m?.aiLatencyMs?.avg, english),
            m?.let { "p50 " + Numbers.latency(it.aiLatencyMs.p50, english) + " · p95 " + Numbers.latency(it.aiLatencyMs.p95, english) },
            h.series { (it.aiP50 ?: 0L).toDouble() }, colors.away),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val columns = if (maxWidth >= 560.dp) 5 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c -> MetricCard(c.label, c.value, c.hint, c.series, c.accent, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (state.error != null && m == null) {
                Text(state.error + tr(" · Повторить", " · Retry"), color = colors.danger, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(6.dp))
            }
        }
    }
}

private data class MetricData(val label: String, val value: String, val hint: String?, val series: List<Double>, val accent: Color)

/** Пароль на выключение нейросети (защита от случайного/чужого выключения). */
private const val AI_OFF_PASSWORD = "1639"

/** Большой переключатель «ИИ включён/выключен» и краткое расписание; нажатие на карточку — экран расписания. */
@Composable
fun AiSwitchCard(state: AiState, onToggle: suspend (Boolean) -> String?, onOpen: () -> Unit, onError: (String) -> Unit) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var passError by remember { mutableStateOf(false) }

    fun applyToggle(value: Boolean) {
        pending = value
        scope.launch {
            val error = onToggle(value)
            pending = null
            if (error != null) onError(error)
        }
    }
    val switchedOn = pending ?: s?.enabled ?: true
    val working = s != null && s.effective && s.configured
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(colors.surface).border(0.6.dp, if (working) colors.online.copy(alpha = 0.5f) else colors.divider, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (working) colors.online.copy(alpha = 0.18f) else colors.raised),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = if (working) colors.online else colors.secondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    s == null -> tr("ИИ", "AI")
                    working -> tr("ИИ включён", "AI is on")
                    !s.configured -> tr("ИИ: нет ключа на сервере", "AI: no key on the server")
                    s.enabled -> tr("ИИ выключен по расписанию", "AI is off by schedule")
                    else -> tr("ИИ выключен", "AI is off")
                },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
            )
            Text(
                when {
                    s == null -> state.error ?: tr("Загрузка…", "Loading…")
                    s.schedule.isEmpty() -> tr("Без расписания · Расписание →", "No schedule · Schedule →")
                    else -> s.schedule.joinToString("; ") { ScheduleText.window(it, english) }
                },
                fontSize = 13.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = switchedOn,
            enabled = s != null && !state.busy && pending == null,
            onCheckedChange = { value ->
                // Выключение ИИ защищено паролем; включение — сразу.
                if (!value) { pass = ""; passError = false; askPassword = true } else applyToggle(true)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.online, checkedThumbColor = Color.White),
        )
    }

    if (askPassword) {
        ConfirmDialog(
            title = tr("Выключить ИИ", "Turn off the AI"),
            text = tr("Введите пароль, чтобы выключить нейросеть для всех пользователей.",
                "Enter the password to turn the AI off for everyone."),
            confirm = tr("Выключить", "Turn off"),
            onDismiss = { askPassword = false },
            onConfirm = {
                if (pass.trim() == AI_OFF_PASSWORD) { askPassword = false; applyToggle(false) } else passError = true
            },
            content = {
                Column {
                    HonerField(
                        pass, { pass = it; passError = false }, tr("Пароль", "Password"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (passError) {
                        Text(tr("Неверный пароль", "Wrong password"), color = colors.danger, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
        )
    }
}

/** Последние ошибки и падения из приложений (главный экран). */
@Composable
fun RecentReports(state: ReportsState, now: Instant, onOpenAll: () -> Unit, onOpenUser: (String) -> Unit) {
    val colors = HonerTheme.colors
    if (!state.loaded || state.reports.isEmpty()) return
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenAll).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Последние ошибки и падения", "Recent errors and crashes"), fontSize = 14.sp, color = colors.secondary, modifier = Modifier.weight(1f))
            Text(tr("Все", "All"), fontSize = 14.sp, color = colors.accent)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface).border(0.6.dp, colors.divider, RoundedCornerShape(18.dp))) {
            state.reports.take(3).forEach { r -> ReportRow(r, now, compact = true, onClick = { onOpenUser(r.deviceId) }) }
        }
    }
}

/** Строка отчёта: значок (падение/ошибка), текст, кто и когда. */
@Composable
fun ReportRow(report: ClientReport, now: Instant, compact: Boolean, onClick: () -> Unit, expanded: Boolean = false) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val crash = report.kind == ReportKinds.CRASH
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Icon(if (crash) Icons.Rounded.BugReport else Icons.Rounded.ErrorOutline, null,
            tint = if (crash) colors.danger else colors.away, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(report.message, fontSize = 14.sp, color = colors.foreground, maxLines = if (expanded) 20 else if (compact) 1 else 3,
                overflow = TextOverflow.Ellipsis)
            val who = listOfNotNull(
                report.publicId?.let { "#$it" },
                report.displayName?.takeIf { it.isNotBlank() } ?: report.deviceModel,
                report.appVersion?.let { "v$it" },
                Times.parse(report.at)?.let { PresenceText.ago(it, now, ZoneId.systemDefault(), english) },
            ).joinToString(" · ")
            Text((if (crash) tr("Падение", "Crash") else tr("Ошибка", "Error")) + " · " + who,
                fontSize = 12.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded && !report.stack.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(report.stack, fontSize = 11.sp, color = colors.secondary, lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.background).padding(8.dp))
            }
        }
    }
}
