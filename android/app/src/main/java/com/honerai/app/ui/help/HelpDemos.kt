package com.honerai.app.ui.help

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.honerai.app.ui.settings.appContainer
import com.honerai.app.ui.settings.rememberReduceMotion
import com.honerai.app.ui.theme.HonerTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

// Мини-видео руководства: сцены рисуются Compose по времени t (секунды цикла).
// Кадры идут через withFrameNanos, только пока ролик на экране и приложение активно;
// при «Меньше анимаций» показывается статичный кадр.

// MARK: - Время

/** 0 до [start], 1 после [end], линейно между ними. */
internal fun ramp(t: Double, start: Double, end: Double): Double =
    if (end <= start) (if (t >= end) 1.0 else 0.0) else ((t - start) / (end - start)).coerceIn(0.0, 1.0)

/** Плавное начало и конец (smoothstep). */
internal fun ease(x: Double): Double { val v = x.coerceIn(0.0, 1.0); return v * v * (3 - 2 * v) }

internal fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

/** Первые символы строки — эффект «печатается». */
internal fun typedPrefix(text: String, fraction: Double): String =
    text.take(floor(text.length * fraction.coerceIn(0.0, 1.0)).toInt())

/** Виден ли элемент в окне (с учётом обрезки прокруткой). */
internal fun Modifier.onVisibilityChanged(onChange: (Boolean) -> Unit): Modifier = this.onGloballyPositioned { coords ->
    val bounds = coords.boundsInWindow()
    onChange(coords.isAttached && bounds.width > 0f && bounds.height > 0f)
}

// MARK: - Плеер

@Composable
internal fun HelpDemoView(demo: HelpDemo, english: Boolean) {
    val colors = HonerTheme.colors
    val reduceMotion = rememberReduceMotion()
    val lowEnd = appContainer().isLowEndDevice
    var playing by rememberSaveable(demo) { mutableStateOf(true) }
    var visible by remember { mutableStateOf(false) }
    var time by remember { mutableDoubleStateOf(0.0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animating = playing && visible && !reduceMotion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // На слабых телефонах 30 кадров в секунду, на остальных — частота экрана.
    val minFrameNanos = if (lowEnd) 33_000_000L else 0L

    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        val base = time
        var start = -1L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                if (now - last >= minFrameNanos) {
                    last = now
                    time = (base + (now - start) / 1e9) % demo.duration
                }
            }
        }
    }
    val t = if (reduceMotion) demo.staticTime else time
    val toggle = { if (!reduceMotion) playing = !playing }

    Column(Modifier.fillMaxWidth().testTag("help.demo.${demo.name}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.SmartDisplay, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(if (english) "Mini-video" else "Мини-видео", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("· " + demo.title(english), color = colors.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(22.dp)).background(colors.surface)
                .border(0.7.dp, colors.divider, RoundedCornerShape(22.dp))
                .onVisibilityChanged { visible = it },
        ) {
            Box(
                Modifier.fillMaxSize().clipToBounds()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 52.dp),
            ) { DemoStage(demo, t, english) }
            if (!playing && !reduceMotion) {
                Icon(
                    Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.Center).size(58.dp).shadow(10.dp, CircleShape),
                )
            }
            DemoControls(playing && !reduceMotion, t, demo.duration, !reduceMotion, english, toggle, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun DemoControls(playing: Boolean, time: Double, duration: Double, enabled: Boolean, english: Boolean, toggle: () -> Unit, modifier: Modifier) {
    val colors = HonerTheme.colors
    val label = "0:%02d / 0:%02d".format(floor(time).toInt(), kotlin.math.ceil(duration).toInt())
    Row(
        modifier.padding(8.dp).fillMaxWidth().clip(CircleShape).background(colors.background.copy(alpha = 0.82f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(30.dp).alpha(if (enabled) 1f else 0.45f).clip(CircleShape).background(colors.accent)
                .clickable(enabled = enabled, onClick = toggle).testTag("help.demo.play"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) (if (english) "Pause" else "Пауза") else (if (english) "Play" else "Воспроизвести"),
                tint = Color.White, modifier = Modifier.size(16.dp),
            )
        }
        DemoProgressBar((time / duration).toFloat(), Modifier.weight(1f))
        Text(label, color = colors.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DemoProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Box(
        modifier.height(3.dp).clip(CircleShape).background(colors.divider).drawBehind {
            drawRoundRect(colors.accent, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
        },
    )
}

@Composable
private fun DemoStage(demo: HelpDemo, t: Double, english: Boolean) {
    when (demo) {
        HelpDemo.typing -> TypingDemo(t, english)
        HelpDemo.questionTimer -> QuestionTimerDemo(t, english)
        HelpDemo.quiz -> QuizDemo(t, english)
        HelpDemo.dragChat -> DragChatDemo(t, english)
        HelpDemo.selectAsk -> SelectAskDemo(t, english)
        HelpDemo.table -> TableDemo(t, english)
        HelpDemo.webResearch -> WebResearchDemo(t, english)
        HelpDemo.photoEditor -> PhotoEditorDemo(t, english)
        HelpDemo.parental -> ParentalDemo(t, english)
        HelpDemo.voice -> VoiceDemo(t, english)
    }
}

// MARK: - Общие детали макетов

@Composable
private fun MockUserBubble(text: String) {
    Text(
        text, color = HonerTheme.colors.foreground, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(HonerTheme.colors.bubble).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun MockPill(text: String, tint: Color = HonerTheme.colors.accent, filled: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text, color = if (filled) Color.White else tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(if (filled) tint else tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun RightAligned(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f).widthIn(min = 40.dp)); content() }
}

// MARK: 1. Живой ответ

@Composable
private fun TypingDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val question = if (english) "Why is the sky blue?" else "Почему небо голубое?"
    val reasoning = if (english) "Scattering of light… shorter wavelengths scatter more… so blue dominates."
    else "Рассеяние света… короткие волны рассеиваются сильнее… значит, синий преобладает."
    val answer = if (english) "Sunlight scatters on air molecules. Blue light has a shorter wavelength, so it scatters much more — that's why we see a blue sky."
    else "Солнечный свет рассеивается на молекулах воздуха. У синего света короче длина волны, поэтому он рассеивается сильнее — и небо кажется голубым."
    val thinkingDone = t >= 3.8
    val thinkingSeconds = floor((t - 0.8).coerceIn(0.0, 3.0)).toInt()
    val header = if (thinkingDone) (if (english) "Thought for 3 seconds" else "Размышлял 3 секунды")
    else (if (english) "Thinking… $thinkingSeconds s" else "Размышляю… $thinkingSeconds с")
    val cursor = t > 3.9 && t < 7.8 && (t * 3).toInt() % 2 == 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.alpha(ramp(t, 0.0, 0.4).toFloat())) { RightAligned { MockUserBubble(question) } }
        Row(Modifier.alpha(if (t > 0.8) 1f else 0f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(header, color = colors.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(if (thinkingDone) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null, tint = colors.secondary, modifier = Modifier.size(14.dp))
        }
        if (!thinkingDone && t > 0.8) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(2.dp).height(36.dp).background(colors.divider))
                Text(typedPrefix(reasoning, ramp(t, 0.9, 3.5)), color = colors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            buildAnnotatedString {
                append(typedPrefix(answer, ramp(t, 4.0, 7.6)))
                if (cursor) withStyle(SpanStyle(color = colors.accent)) { append("▍") }
            },
            color = colors.foreground, fontSize = 15.sp, lineHeight = 21.sp,
        )
    }
}

// MARK: 2. Вопрос с таймером

@Composable
private fun QuestionTimerDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val remaining = (10 - (t - 0.6).coerceAtLeast(0.0)).coerceAtLeast(0.0)
    val closed = t >= 10.6
    val letters = if (english) listOf("A", "B", "C") else listOf("А", "Б", "В")
    val options = if (english) listOf("Briefly", "In detail", "With examples") else listOf("Кратко", "Подробно", "С примерами")
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .graphicsLayer {
                    alpha = if (closed) 0f else ramp(t, 0.0, 0.6).toFloat()
                    val s = if (closed) 0.92f else 1f
                    scaleX = s; scaleY = s
                }
                .clip(RoundedCornerShape(18.dp)).background(colors.raised).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (english) "Question 1 of 3" else "Вопрос 1 из 3", color = colors.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                CountdownRing((remaining / 10).toFloat(), kotlin.math.ceil(remaining).toInt())
            }
            Text(if (english) "How detailed should the answer be?" else "Насколько подробно ответить?", color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            for (i in 0 until 3) {
                Row(
                    Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Text(letters[i], color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(options[i], color = colors.foreground, fontSize = 14.sp)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(28.dp).border(0.8.dp, colors.divider, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(if (english) "Your own answer…" else "Свой ответ…", color = colors.secondary, fontSize = 13.sp) }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 50.dp).alpha(ramp(t, 10.7, 11.2).toFloat()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Timer, null, tint = HelpTint.orange.color(), modifier = Modifier.size(38.dp))
            Text(if (english) "Time's up — the question closed" else "Время вышло — вопрос закрыт", color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (english) "Honer AI decided by itself: “In detail”" else "Honer AI решил сам: «Подробно»", color = colors.secondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CountdownRing(fraction: Float, seconds: Int) {
    val colors = HonerTheme.colors
    val ringColor = if (fraction < 0.3f) HelpTint.red.color() else colors.accent
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.5.dp.toPx()
            val inset = stroke / 2
            drawCircle(colors.divider, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            drawArc(
                ringColor, -90f, 360f * fraction.coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text("$seconds", color = ringColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// MARK: 3. Тест

@Composable
private fun QuizDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val questions = if (english) listOf("2 + 2 × 2 = ?", "Capital of Australia?", "H₂O is…") else listOf("2 + 2 × 2 = ?", "Столица Австралии?", "H₂O — это…")
    val answers = if (english) listOf("6", "Sydney", "Water") else listOf("6", "Сидней", "Вода")
    val corrections = if (english) listOf("", "Canberra", "") else listOf("", "Канберра", "")
    val correct = listOf(true, false, true)
    val starts = listOf(0.8, 2.8, 4.8)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 3) {
            val judged = t >= starts[i] + 1.0
            val tint = if (!judged) colors.accent else if (correct[i]) HelpTint.green.color() else HelpTint.red.color()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (judged) tint.copy(alpha = 0.14f) else colors.raised)
                    .border(1.dp, if (judged) tint.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${i + 1}", color = colors.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(questions[i], color = colors.secondary, fontSize = 13.sp)
                    Row(Modifier.alpha(ramp(t, starts[i], starts[i] + 0.3).toFloat()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            answers[i], color = colors.foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            textDecoration = if (judged && !correct[i]) TextDecoration.LineThrough else null,
                        )
                        if (judged && corrections[i].isNotEmpty()) {
                            Text("→ " + corrections[i], color = HelpTint.green.color(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Icon(
                    if (correct[i]) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = tint,
                    modifier = Modifier.size(22.dp).alpha(if (judged) 1f else 0f),
                )
            }
        }
        val appear = ramp(t, 7.0, 7.5)
        Row(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = appear.toFloat()
                val s = (0.9 + 0.1 * ease(appear)).toFloat()
                scaleX = s; scaleY = s
            }.clip(RoundedCornerShape(14.dp)).background(colors.accent.copy(alpha = 0.14f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Star, null, tint = HelpTint.yellow.color(), modifier = Modifier.size(20.dp))
            Text(if (english) "Score: 2 of 3" else "Итог: 2 из 3", color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (english) "Let's review Q2" else "Разберём вопрос 2", color = colors.secondary, fontSize = 12.sp)
        }
    }
}

// MARK: 4. Перетаскивание чата

@Composable
private fun DragChatDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val lift = ramp(t, 0.8, 1.3) - ramp(t, 4.2, 4.7)
    val travel = ease(ramp(t, 1.4, 4.2))
    val dropped = t >= 4.7
    val rowY = lerp(190.0, 56.0, travel)
    val slotHighlighted = travel > 0.55 && !dropped
    val fade = 1 - 0.7 * ramp(t, 8.0, 8.5)
    Box(Modifier.fillMaxWidth().height(224.dp).alpha(fade.toFloat())) {
        SidebarHeader(if (english) "Pinned" else "Закреплено", Modifier.offset(y = 0.dp))
        SidebarRow(if (english) "Project ideas" else "Идеи для проекта", true, Modifier.offset(y = 20.dp))
        Box(
            Modifier.offset(y = 56.dp).fillMaxWidth().height(32.dp).alpha(if (slotHighlighted) 1f else 0f)
                .clip(RoundedCornerShape(10.dp)).background(colors.accent.copy(alpha = 0.08f))
                .drawBehind {
                    drawRoundRect(
                        colors.accent, cornerRadius = CornerRadius(10.dp.toPx()),
                        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                },
        )
        Box(Modifier.offset(y = 96.dp).fillMaxWidth().height(0.7.dp).background(colors.divider))
        SidebarHeader(if (english) "Today" else "Сегодня", Modifier.offset(y = 102.dp))
        SidebarRow(if (english) "Choosing a phone" else "Выбор смартфона", false, Modifier.offset(y = 122.dp))
        SidebarRow(if (english) "Pizza recipe" else "Рецепт пиццы", false, Modifier.offset(y = 156.dp))
        SidebarRow(
            if (english) "Trip plan" else "План поездки", dropped,
            Modifier.offset(y = rowY.dp)
                .graphicsLayer {
                    val s = (1 + 0.05 * lift).toFloat()
                    scaleX = s; scaleY = s
                    shadowElevation = (10 * lift).toFloat() * density
                    shape = RoundedCornerShape(10.dp)
                    clip = false
                }
                .clip(RoundedCornerShape(10.dp))
                .background(if (lift > 0.01) colors.raised else Color.Transparent),
        )
        Icon(
            Icons.Filled.TouchApp, null, tint = colors.foreground,
            modifier = Modifier.offset(x = 190.dp, y = (rowY + 14).dp).size(28.dp)
                .alpha((ramp(t, 0.5, 0.8) - ramp(t, 4.8, 5.2)).toFloat().coerceIn(0f, 1f)),
        )
    }
}

@Composable
private fun SidebarHeader(text: String, modifier: Modifier) {
    Text(text, color = HonerTheme.colors.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(start = 10.dp))
}

@Composable
private fun SidebarRow(title: String, pinned: Boolean, modifier: Modifier) {
    val colors = HonerTheme.colors
    Row(
        modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = colors.foreground, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (pinned) Icon(Icons.Filled.PushPin, null, tint = colors.accent, modifier = Modifier.size(13.dp))
        Icon(Icons.Filled.MoreHoriz, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
    }
}

// MARK: 5. «Спросить Honer AI» о фрагменте

@Composable
private fun SelectAskDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val lineOne = if (english) "Photosynthesis is the process in which" else "Фотосинтез — это процесс, в котором"
    val lineTwoStart = if (english) "plants " else "растения "
    val selected = if (english) "turn light into energy" else "превращают свет в энергию"
    val lineThree = if (english) "and release oxygen." else "и выделяют кислород."
    val question = if (english) "Explain this more simply" else "Объясни это проще"
    val selection = ease(ramp(t, 1.0, 2.6)).toFloat()
    val menuShown = t >= 2.8 && t < 4.8
    val askPressed = t >= 4.1 && t < 4.8
    val quoteShown = ramp(t, 5.0, 5.5)
    val typed = typedPrefix(question, ramp(t, 5.8, 7.8))
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(top = 58.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(lineOne, color = colors.foreground, fontSize = 15.sp, maxLines = 1)
                Row {
                    Text(lineTwoStart, color = colors.foreground, fontSize = 15.sp, maxLines = 1)
                    Text(
                        selected, color = colors.foreground, fontSize = 15.sp, maxLines = 1,
                        modifier = Modifier.drawBehind {
                            drawRect(colors.accent.copy(alpha = 0.32f), size = Size(size.width * selection, size.height))
                            val handle = Size(2.5.dp.toPx(), size.height + 4.dp.toPx())
                            if (selection > 0.02f) drawRect(colors.accent, Offset(0f, -2.dp.toPx()), handle)
                            if (selection > 0.97f) drawRect(colors.accent, Offset(size.width - handle.width, -2.dp.toPx()), handle)
                        },
                    )
                }
                Text(lineThree, color = colors.foreground, fontSize = 15.sp, maxLines = 1)
            }
            Row(
                Modifier.align(Alignment.TopCenter).graphicsLayer {
                    alpha = if (menuShown) 1f else 0f
                    val s = if (menuShown) 1f else 0.85f
                    scaleX = s; scaleY = s
                }.shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292929)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuItem(if (english) "Copy" else "Копировать", false)
                Box(Modifier.width(0.7.dp).height(22.dp).background(Color.White.copy(alpha = 0.2f)))
                MenuItem(if (english) "✦ Ask Honer AI" else "✦ Спросить Honer AI", askPressed)
            }
        }
        Spacer(Modifier.weight(1f).height(8.dp))
        MockComposer(if (quoteShown > 0) selected else null, quoteShown.toFloat(), typed, if (english) "Message" else "Сообщение", ramp(t, 8.3, 8.6) - ramp(t, 8.8, 9.1))
    }
}

@Composable
private fun MenuItem(text: String, highlighted: Boolean) {
    Text(
        text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (highlighted) HonerTheme.colors.accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun MockComposer(quote: String?, quoteOpacity: Float, text: String, placeholder: String, sendPulse: Double) {
    val colors = HonerTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.raised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (quote != null) {
            Row(Modifier.alpha(quoteOpacity), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(3.dp).height(18.dp).background(colors.accent))
                Text("«$quote»", color = colors.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text.ifEmpty { placeholder }, color = if (text.isEmpty()) colors.secondary else colors.foreground, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(28.dp).scale((1 + 0.25 * sendPulse).toFloat()).clip(CircleShape)
                    .background(if (text.isEmpty()) colors.secondary else colors.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

// MARK: 6. Таблица

private fun tableHeaders(english: Boolean) = if (english) listOf("Item", "Plan", "Actual") else listOf("Статья", "План", "Факт")

private fun tableRows(english: Boolean, editedValue: String, includeNewRow: Boolean): List<List<String>> = buildList {
    add(listOf(if (english) "Tickets" else "Билеты", "300", "280"))
    add(listOf(if (english) "Hotel" else "Отель", "400", editedValue))
    add(listOf(if (english) "Food" else "Еда", "150", "170"))
    if (includeNewRow) add(listOf(if (english) "Souvenirs" else "Сувениры", "40", ""))
}

@Composable
private fun TableDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val expand = ease(ramp(t, 2.2, 3.0)).toFloat()
    val openPressed = t >= 1.6 && t < 2.2
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(top = 20.dp).fillMaxWidth().alpha(1 - expand).clip(RoundedCornerShape(16.dp)).background(colors.raised).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (english) "📊 Trip budget" else "📊 Бюджет поездки", color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            TableGrid(tableRows(english, "360", false), tableHeaders(english), -1, false)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                MockPill(if (english) "Open" else "Открыть", filled = openPressed, modifier = Modifier.scale(if (openPressed) 1.1f else 1f))
            }
        }
        Box(
            Modifier.graphicsLayer {
                alpha = expand
                val s = 0.55f + 0.45f * expand
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        ) { TableFullScreen(t, english) }
    }
}

@Composable
private fun TableFullScreen(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val editing = t >= 3.6 && t < 6.0
    val cursor = editing && (t * 3).toInt() % 2 == 0
    val editedValue = when {
        t < 4.3 -> "360"
        t < 4.7 -> ""
        else -> typedPrefix("420", ramp(t, 4.7, 5.4))
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.background)
            .border(0.7.dp, colors.divider, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (english) "Trip budget" else "Бюджет поездки", color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Share, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Icon(Icons.Filled.Close, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MockPill(if (english) "+ Row" else "+ Строка", filled = t >= 5.9 && t < 6.3)
            MockPill(if (english) "+ Column" else "+ Столбец")
        }
        TableGrid(tableRows(english, editedValue, t >= 6.2), tableHeaders(english), if (editing) 1 else -1, cursor)
        Row(Modifier.alpha(ramp(t, 7.0, 7.4).toFloat()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.CheckCircle, null, tint = HelpTint.green.color(), modifier = Modifier.size(16.dp))
            Text(if (english) "Honer AI sees your edits" else "Honer AI видит ваши правки", color = colors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TableGrid(rows: List<List<String>>, headers: List<String>, editingRow: Int, cursor: Boolean) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(0.7.dp, colors.divider, RoundedCornerShape(8.dp))) {
        Row { headers.forEach { TableCell(it, true, false, false, Modifier.weight(1f)) } }
        rows.forEachIndexed { r, row ->
            Row {
                row.forEachIndexed { c, text ->
                    val isEditing = r == editingRow && c == 2
                    TableCell(text, false, isEditing, cursor && isEditing, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, isHeader: Boolean, isEditing: Boolean, cursor: Boolean, modifier: Modifier) {
    val colors = HonerTheme.colors
    Row(
        modifier.height(26.dp)
            .background(if (isHeader) colors.raised else if (isEditing) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(if (isEditing) 1.4.dp else 0.35.dp, if (isEditing) colors.accent else colors.divider)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = colors.foreground, fontSize = 12.sp, fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        if (cursor) Box(Modifier.width(1.5.dp).height(13.dp).background(colors.accent))
    }
}

// MARK: 7. Исследование сайтов

private val faviconLetters = listOf("W", "H", "i", "N", "D", "▶", "R", "4", "V", "C")
private val faviconPalette = listOf(HelpTint.blue, HelpTint.orange, HelpTint.red, HelpTint.purple, HelpTint.green, HelpTint.red, HelpTint.orange, HelpTint.teal, HelpTint.indigo, HelpTint.pink)
private fun faviconColor(index: Int): Color = faviconPalette[((index % faviconPalette.size) + faviconPalette.size) % faviconPalette.size].color()

@Composable
private fun WebResearchDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val total = 500
    val count = floor(total * ease(ramp(t, 0.4, 8.6))).toInt()
    val done = t >= 8.7
    val domains = listOf("wikipedia.org", "habr.com", "ixbt.com", "notebookcheck.net", "dns-shop.ru", "youtube.com", "reddit.com", "4pda.to", "theverge.com", "citilink.ru")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (done) Icons.Filled.Verified else Icons.Outlined.Public, null,
                tint = if (done) HelpTint.green.color() else colors.accent,
                modifier = Modifier.size(20.dp).rotate(if (done) 0f else (t * 90).toFloat()),
            )
            Text(
                if (done) (if (english) "Research complete" else "Исследование готово") else (if (english) "Researching: best laptops" else "Исследую: лучшие ноутбуки"),
                color = colors.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(if (english) "Read $count of $total sites" else "Прочитано $count из $total сайтов", color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        DemoProgressBar(count.toFloat() / total, Modifier.fillMaxWidth())
        // Лента значков сайтов бежит влево.
        val spacing = 46.0
        val shift = t * 70
        val base = (shift / spacing).toInt()
        val offset = -(shift % spacing)
        Box(Modifier.fillMaxWidth().height(38.dp).clipToBounds()) {
            Row(Modifier.offset(x = offset.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (slot in 0 until 9) {
                    val index = base + slot
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(faviconColor(index)), contentAlignment = Alignment.Center) {
                        Text(faviconLetters[index % faviconLetters.size], color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0 until 3) {
                val index = ((t * 2.5).toInt() + row) % domains.size
                Row(
                    Modifier.alpha(if (done) 0.4f else (1 - row * 0.28f)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(faviconColor(index)))
                    Text((if (english) "Reading · " else "Читаю · ") + domains[index], color = colors.secondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
    }
}

// MARK: 8. Редактор фото

@Composable
private fun PhotoEditorDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val sweep = ramp(t, 1.0, 3.2).toFloat()
    val replaced = ramp(t, 3.8, 4.6).toFloat()
    val filterIndex = when {
        t < 5.6 -> 0
        t < 7.2 -> 1
        else -> 2
    }
    val filters = if (english) listOf("Original", "B&W", "Warm", "Vintage") else listOf("Оригинал", "Ч/Б", "Тёплый", "Винтаж")
    val status = when {
        t < 1.0 -> if (english) "Background → Remove" else "Фон → Удалить фон"
        t < 3.2 -> if (english) "Removing background on the phone…" else "Удаляю фон на телефоне…"
        t < 3.8 -> if (english) "Background removed ✓" else "Фон удалён ✓"
        t < 5.6 -> if (english) "New background: gradient" else "Новый фон: градиент"
        else -> if (english) "Filters" else "Фильтры"
    }
    // Фильтры применяются к цветам макета: Ч/Б — яркость, «Тёплый» — умножение на тёплый тон.
    fun f(c: Color): Color = when (filterIndex) {
        1 -> { val l = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue; Color(l, l, l, c.alpha) }
        2 -> Color(c.red, c.green * 0.88f, c.blue * 0.72f, c.alpha)
        else -> c
    }
    val accent = colors.accent
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier.size(200.dp, 150.dp).shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).drawBehind {
                val cell = 12.dp.toPx()
                drawRect(f(Color.White.copy(alpha = 0.92f)))
                var y = 0
                while (y * cell < size.height) {
                    var x = 0
                    while (x * cell < size.width) {
                        if ((x + y) % 2 == 0) drawRect(f(Color.Gray.copy(alpha = 0.35f)), Offset(x * cell, y * cell), Size(cell, cell))
                        x++
                    }
                    y++
                }
                // Исходный фон исчезает слева направо вслед за линией.
                clipRect(left = size.width * sweep) {
                    drawRect(Brush.verticalGradient(listOf(f(Color(0xFFFF9E59)), f(Color(0xFFED5C8C)))))
                    drawCircle(f(Color(0xFFFFD60A).copy(alpha = 0.85f)), 18.dp.toPx(), Offset(size.width - 34.dp.toPx(), 34.dp.toPx()))
                }
                if (replaced > 0f) drawRect(Brush.linearGradient(listOf(f(Color(0xFF4080FF)), f(Color(0xFF4DD9F2)))), alpha = replaced)
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, null, tint = f(Color(0xFF2E3342)), modifier = Modifier.size(130.dp).offset(y = 24.dp))
            if (sweep > 0f && sweep < 1f) {
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = (200 * sweep).dp - 1.5.dp).width(3.dp).fillMaxHeight()
                        .shadow(6.dp, RoundedCornerShape(1.dp), ambientColor = accent, spotColor = accent).background(Color.White),
                )
            }
        }
        Row(Modifier.alpha(ramp(t, 4.8, 5.2).toFloat()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            filters.forEachIndexed { index, name -> MockPill(name, filled = index == filterIndex && t >= 5.0) }
        }
    }
}

// MARK: 9. Родительский контроль

@Composable
private fun ParentalDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val pressTimes = listOf(0.8, 1.35, 1.9, 2.45)
    val pressedDigits = listOf("2", "5", "8", "0")
    val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
    val filled = pressTimes.count { t >= it }
    val padOpacity = 1 - ramp(t, 3.2, 3.8)
    val shield = ease(ramp(t, 3.6, 4.4)).toFloat()
    val fade = 1 - ramp(t, 8.5, 9.0)
    fun isPressed(key: String) = pressTimes.indices.any { pressedDigits[it] == key && t >= pressTimes[it] && t < pressTimes[it] + 0.22 }
    Box(Modifier.fillMaxWidth().alpha(fade.toFloat()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.alpha(padOpacity.toFloat()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (english) "Create a parent PIN" else "Придумайте PIN-код родителя", color = colors.foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0 until 4) {
                    Box(Modifier.size(13.dp).clip(CircleShape).background(if (i < filled) colors.accent else Color.Transparent).border(1.4.dp, colors.accent, CircleShape))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { label ->
                            val pressed = isPressed(label)
                            Box(
                                Modifier.size(56.dp, 30.dp).scale(if (pressed) 0.94f else 1f).clip(RoundedCornerShape(9.dp))
                                    .background(if (pressed) colors.accent else if (label.isEmpty()) Color.Transparent else colors.raised),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = if (pressed) Color.White else colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.graphicsLayer { alpha = shield; val s = 0.6f + 0.4f * shield; scaleX = s; scaleY = s },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Security, null, tint = HelpTint.green.color(), modifier = Modifier.padding(top = 8.dp).size(64.dp))
            Text(if (english) "Parental controls are on" else "Родительский контроль включён", color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MockPill(if (english) "18+ hidden" else "18+ скрыто", HelpTint.red.color(), modifier = Modifier.alpha(ramp(t, 5.0, 5.4).toFloat()))
                MockPill(if (english) "1 h a day" else "1 ч в день", HelpTint.orange.color(), modifier = Modifier.alpha(ramp(t, 5.6, 6.0).toFloat()))
                MockPill(if (english) "Quiet 22–07" else "Тихие 22–07", HelpTint.indigo.color(), modifier = Modifier.alpha(ramp(t, 6.2, 6.6).toFloat()))
            }
        }
    }
}

// MARK: 10. Голосовой ввод

@Composable
private fun VoiceDemo(t: Double, english: Boolean) {
    val colors = HonerTheme.colors
    val phrase = if (english) "Remind me to buy milk and bread" else "Напомни купить молоко и хлеб"
    val reply = if (english) "Sure! I'll remind you at 6 pm 🛒" else "Хорошо! Напомню в 18:00 🛒"
    val recording = t >= 0.9 && t < 5.3
    val sent = t >= 5.5
    val transcript = if (sent) "" else typedPrefix(phrase, ramp(t, 1.3, 4.8))
    val hint = when {
        recording -> if (english) "Speak. Tap ■ to send" else "Говорите. Нажмите ■, чтобы отправить"
        sent -> if (english) "Sent" else "Отправлено"
        else -> if (english) "Tap the microphone" else "Нажмите на микрофон"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().height(70.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.alpha(ramp(t, 5.5, 5.9).toFloat())) { RightAligned { MockUserBubble(phrase) } }
            Text(typedPrefix(reply, ramp(t, 6.3, 7.4)), color = colors.foreground, fontSize = 14.sp)
        }
        Spacer(Modifier.weight(1f))
        Waveform(t, recording, Modifier.alpha(if (recording) 1f else 0.25f))
        Text(hint, color = colors.secondary, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.raised).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                transcript.ifEmpty { if (english) "Message" else "Сообщение" },
                color = if (transcript.isEmpty()) colors.secondary else colors.foreground, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (recording) Text(if (english) "Cancel" else "Отмена", color = colors.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            MicButton(t, recording)
        }
    }
}

@Composable
private fun Waveform(t: Double, active: Boolean, modifier: Modifier) {
    val accent = HonerTheme.colors.accent
    Canvas(modifier.fillMaxWidth().height(32.dp)) {
        val bar = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val count = 28
        val total = count * bar + (count - 1) * gap
        var x = (size.width - total) / 2
        for (i in 0 until count) {
            val h = if (!active) 4.0 else {
                val position = i / 27.0
                val envelope = 0.35 + 0.65 * sin(position * Math.PI)
                val wave = abs(sin(t * 6.0 + i * 0.55)) * 0.7 + abs(sin(t * 3.7 + i * 1.3)) * 0.3
                4 + 26 * envelope * wave
            }
            val hp = h.toFloat() * density
            drawRoundRect(accent, Offset(x, (size.height - hp) / 2), Size(bar, hp), CornerRadius(bar / 2))
            x += bar + gap
        }
    }
}

@Composable
private fun MicButton(t: Double, recording: Boolean) {
    val colors = HonerTheme.colors
    val pulse = ((t * 1.2) % 1).toFloat()
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(Modifier.size(34.dp).scale(1 + 0.6f * pulse).border(2.dp, colors.accent.copy(alpha = 0.6f * (1 - pulse)), CircleShape))
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (recording) colors.accent else colors.background)
                .border(if (recording) 0.dp else 1.dp, if (recording) Color.Transparent else colors.divider, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = if (recording) Color.White else colors.foreground, modifier = Modifier.size(16.dp))
        }
    }
}
