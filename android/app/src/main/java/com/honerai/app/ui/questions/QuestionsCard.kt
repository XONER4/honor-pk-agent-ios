package com.honerai.app.ui.questions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.honerai.app.AppContainer
import com.honerai.app.ui.markdown.QuickQuestion
import com.honerai.app.ui.markdown.RemoteImage
import com.honerai.app.ui.markdown.RenderActions
import com.honerai.app.ui.markdown.VideoCard
import com.honerai.app.ui.markdown.rememberEnglish
import com.honerai.app.ui.markdown.tr
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9500)

/**
 * Вопросы Honer AI (порт QuestionsCardView): по одному на экране, с вариантами,
 * таймером и итогом. До 30 вопросов; в тесте — подсветка правильных ответов и счёт.
 * Таймер идёт только у последнего сообщения, когда ответ дописан, карточка на экране,
 * приложение открыто и пользователь не вписывает свой ответ.
 */
@Composable
fun QuestionsCard(
    questions: List<QuickQuestion>,
    rawBody: String,
    blockId: Int,
    fontSize: Float,
    stillStreaming: Boolean,
    messageId: String?,
    isLatest: Boolean,
    onAnswer: (String) -> Boolean,
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val english = rememberEnglish()
    val lowEnd = remember(context) { AppContainer.get(context).isLowEndDevice }
    remember(context) { QuestionnaireStore.init(context); true }
    val entries by QuestionnaireStore.entries.collectAsState()
    val header = remember(rawBody) { QuestionnaireHeader.parse(rawBody) }
    val isQuiz = header.quiz || questions.any { it.correct.isNotEmpty() }
    val count = questions.size
    val key = (messageId ?: "preview") + "#" + blockId
    val entry = entries[key]?.let { saved ->
        if (saved.answers.size < count) saved.copy(answers = saved.answers + List(count - saved.answers.size) { null }) else saved
    } ?: QuestionnaireStore.Entry(answers = List(count) { null })
    val currentIndex = min(entry.current, max(0, count - 1))

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val active = messageId != null && isLatest && !stillStreaming && !entry.finished && questions.isNotEmpty() && resumed
    // Карточка в старом сообщении, на которую так и не ответили. Последнее сообщение чата,
    // которое ещё печатается, закрытым не считается: варианты видны сразу.
    val store = remember(context) { AppContainer.get(context).store }
    val messages by store.messages.collectAsState()
    val lastInChat = messageId != null && messages.lastOrNull()?.id == messageId
    val closed = messageId != null && !isLatest && !lastInChat && !entry.finished
    var visible by remember { mutableStateOf(false) }
    var customFocused by remember { mutableStateOf(false) }
    val timerRunning = header.timer > 0 && active && visible && !customFocused

    val onAnswerState = rememberUpdatedState(onAnswer)
    val englishState = rememberUpdatedState(english)
    val questionsState = rememberUpdatedState(questions)

    fun sendResults() {
        val latest = QuestionnaireStore.entry(key, count)
        if (latest.sent) return
        val message = QuestionnaireReport.message(latest.answers, questionsState.value, isQuiz, header.title, englishState.value)
        if (message.isEmpty() || !onAnswerState.value(message)) return
        QuestionnaireStore.update(key, count) { it.copy(sent = true) }
    }

    fun advance(index: Int) {
        val now = QuestionnaireStore.entry(key, count)
        if (now.current != index || now.finished) return
        if (index + 1 < count) {
            QuestionnaireStore.update(key, count) { it.copy(current = index + 1) }
        } else {
            QuestionnaireStore.update(key, count) { it.copy(finished = true) }
            sendResults()
        }
    }

    fun pauseTimer() {
        val now = QuestionnaireStore.entry(key, count)
        val deadline = now.deadline ?: return
        if (now.finished) return
        val remaining = max(1.0, (deadline - System.currentTimeMillis()) / 1000.0)
        QuestionnaireStore.update(key, count) { it.copy(pausedRemaining = remaining, deadline = null) }
    }

    fun choose(value: String) {
        val now = QuestionnaireStore.entry(key, count)
        if (now.finished || questions.isEmpty() || stillStreaming) return
        val index = min(now.current, count - 1)
        if (now.answers.getOrNull(index) != null) return
        QuestionnaireStore.update(key, count) { item ->
            item.copy(answers = item.answers.toMutableList().also { it[index] = value }, deadline = null, pausedRemaining = null)
        }
        RenderActions.haptic(view, RenderActions.Haptic.LIGHT)
        val question = questions[index]
        if (isQuiz && value.isNotEmpty() && question.correct.isNotEmpty()) {
            val right = QuestionnaireReport.isCorrect(value, question)
            RenderActions.haptic(view, if (right) RenderActions.Haptic.SUCCESS else RenderActions.Haptic.ERROR)
        }
    }

    // Ответ выбран (или время вышло) — через мгновение следующий вопрос. Переход идёт
    // в общей области хранилища: он не теряется, если карточка ушла с экрана.
    val answeredCurrent = entry.answers.getOrNull(currentIndex)
    LaunchedEffect(key, currentIndex, answeredCurrent != null, entry.finished) {
        if (answeredCurrent == null || entry.finished || closed || stillStreaming) return@LaunchedEffect
        val index = currentIndex
        val wait = when {
            answeredCurrent.isEmpty() -> 450L
            isQuiz -> 1000L
            else -> 350L
        }
        QuestionnaireStore.scope.launch {
            delay(wait)
            advance(index)
        }
    }

    // Таймер вопроса: дедлайн хранится в записи, пауза запоминает остаток.
    LaunchedEffect(key, currentIndex, timerRunning) {
        if (!timerRunning) {
            pauseTimer()
            return@LaunchedEffect
        }
        val index = currentIndex
        val start = QuestionnaireStore.entry(key, count)
        val deadline = start.deadline ?: run {
            val remaining = start.pausedRemaining ?: header.timer.toDouble()
            val end = System.currentTimeMillis() + (remaining * 1000).toLong()
            QuestionnaireStore.update(key, count) { it.copy(deadline = end, pausedRemaining = null) }
            end
        }
        val wait = deadline - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val now = QuestionnaireStore.entry(key, count)
        if (now.current != index || now.finished || now.answers.getOrNull(index) != null) return@LaunchedEffect
        // Время вышло: вопрос закрывается, решение остаётся за Honer AI.
        RenderActions.haptic(view, RenderActions.Haptic.WARNING)
        QuestionnaireStore.update(key, count) { item ->
            item.copy(answers = item.answers.toMutableList().also { it[index] = "" }, deadline = null)
        }
    }

    DisposableEffect(key) {
        onDispose { pauseTimer() }
    }

    val titleText = when {
        header.title.isNotEmpty() -> header.title
        isQuiz -> tr(english, "Тест", "Quiz")
        count == 1 -> tr(english, "Вопрос Honer AI", "Question from Honer AI")
        else -> tr(english, "Вопросы Honer AI", "Questions from Honer AI")
    }
    val counterText = if (count > 1 && !entry.finished) "${currentIndex + 1}/$count" else ""
    val shape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val onScreen = bounds.width > 1f && bounds.height > 1f
                if (onScreen != visible) visible = onScreen
            }
            .clip(shape)
            .background(colors.surface)
            .border(if (active) 1.2.dp else 0.9.dp, colors.accent.copy(alpha = if (active) 0.55f else 0.3f), shape)
            .padding(15.dp)
            .testTag("message.questions"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TitleBar(
            title = titleText,
            counter = counterText,
            quiz = isQuiz,
            fontSize = fontSize,
            streaming = stillStreaming,
            deadline = if (timerRunning) entry.deadline else null,
            total = header.timer.toDouble(),
            lowEnd = lowEnd,
        )
        when {
            entry.finished -> SummaryView(questions, entry.answers, isQuiz, entry.sent, fontSize, english) { sendResults() }
            closed -> Row(
                Modifier.testTag("question.closed"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Lock, null, tint = colors.secondary, modifier = Modifier.size(13.dp))
                Text(
                    tr(english, "Вопросы закрыты: разговор пошёл дальше", "Questions closed: the conversation moved on"),
                    fontSize = (fontSize * 0.78f).sp, color = colors.secondary,
                )
            }
            questions.isNotEmpty() -> {
                if (count > 1) ProgressBar(currentIndex, count)
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        if (lowEnd) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                        else (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                    },
                    label = "question",
                ) { index ->
                    val question = questions.getOrNull(index) ?: return@AnimatedContent
                    QuestionPage(
                        question = question,
                        answer = entry.answers.getOrNull(index),
                        quiz = isQuiz,
                        fontSize = fontSize,
                        enabled = !stillStreaming,
                        english = english,
                        onChoose = { choose(it) },
                    )
                }
                val current = questions[currentIndex]
                if (current.allowsCustom || current.options.isEmpty()) {
                    CustomAnswerField(fontSize, english, onFocus = { customFocused = it }) { choose(it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (header.timer > 0 && active) {
                        Text(
                            tr(english, "На ответ ${header.timer} с — потом решу сам", "${header.timer} s to answer, then I'll decide"),
                            fontSize = (fontSize * 0.66f).sp, color = colors.secondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (active && count > 1) {
                        Text(
                            tr(english, "Пропустить", "Skip"),
                            fontSize = (fontSize * 0.72f).sp, fontWeight = FontWeight.Medium, color = colors.secondary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { choose("") }
                                .padding(horizontal = 6.dp, vertical = 4.dp).testTag("question.skip"),
                        )
                    }
                }
            }
        }
    }
}

/** Заголовок: значок, название, счётчик и кольцо таймера. */
@Composable
private fun TitleBar(
    title: String,
    counter: String,
    quiz: Boolean,
    fontSize: Float,
    streaming: Boolean,
    deadline: Long?,
    total: Double,
    lowEnd: Boolean,
) {
    val colors = HonerTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (quiz) Icons.Outlined.Verified else Icons.Outlined.QuestionAnswer, null, tint = colors.accent, modifier = Modifier.size(17.dp))
        Text(
            title, fontSize = (fontSize * 0.8f).sp, fontWeight = FontWeight.SemiBold, color = colors.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (streaming) CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.weight(1f))
        if (counter.isNotEmpty()) {
            Text(counter, fontSize = (fontSize * 0.72f).sp, fontWeight = FontWeight.SemiBold, color = colors.secondary)
        }
        AnimatedVisibility(visible = deadline != null && total > 0) {
            if (deadline != null) CountdownRing(deadline, total, lowEnd)
        }
    }
}

/** Кольцо обратного отсчёта: плавно убывает, к концу краснеет. */
@Composable
private fun CountdownRing(deadline: Long, total: Double, lowEnd: Boolean) {
    val colors = HonerTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= deadline) break
            delay(if (lowEnd) 100 else 33)
        }
    }
    val remaining = max(0.0, (deadline - now) / 1000.0)
    val fraction = min(1.0, remaining / max(total, 1.0)).toFloat()
    val tint = when {
        remaining <= 3 -> Red
        remaining <= 6 -> Orange
        else -> colors.accent
    }
    val seconds = ceil(remaining).toInt()
    Box(
        Modifier.size(30.dp).testTag("question.timer").semantics { contentDescription = seconds.toString() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(1.5.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(colors.divider, style = Stroke(stroke))
            drawArc(tint, -90f, 360f * fraction, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(seconds.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

/** Полоса прогресса по вопросам. */
@Composable
private fun ProgressBar(index: Int, count: Int) {
    val colors = HonerTheme.colors
    val target = (index + 1).toFloat() / max(count, 1)
    val fraction by animateFloatAsState(target, label = "progress")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.raised).testTag("question.progress")
    ) {
        Box(
            Modifier
                .width(maxWidth * fraction)
                .height(5.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(colors.accent, Color(0xFF9973FF))))
        )
    }
}

private enum class Mark { NONE, SELECTED, CORRECT, WRONG, DIMMED }

/** Один вопрос: текст, медиа и варианты ответа. */
@Composable
private fun QuestionPage(
    question: QuickQuestion,
    answer: String?,
    quiz: Boolean,
    fontSize: Float,
    enabled: Boolean,
    english: Boolean,
    onChoose: (String) -> Unit,
) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            question.text, fontSize = fontSize.sp, lineHeight = (fontSize * 1.35f).sp, fontWeight = FontWeight.SemiBold,
            color = colors.foreground, modifier = Modifier.testTag("question.text"),
        )
        question.media.forEach { QuestionMediaView(it, fontSize, english) }
        question.options.forEachIndexed { optionIndex, option ->
            val mark = when {
                answer.isNullOrEmpty() ->
                    if (answer == "" && quiz && optionIndex in question.correct) Mark.CORRECT else Mark.NONE
                quiz && question.correct.isNotEmpty() -> when {
                    optionIndex in question.correct -> Mark.CORRECT
                    answer == option -> Mark.WRONG
                    else -> Mark.DIMMED
                }
                answer == option -> Mark.SELECTED
                else -> Mark.DIMMED
            }
            OptionButton(option, letter(optionIndex, english), mark, fontSize, enabled && answer == null) { onChoose(option) }
        }
        AnimatedVisibility(visible = answer == "") {
            Row(
                Modifier.testTag("question.timeout"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.HourglassBottom, null, tint = Orange, modifier = Modifier.size(14.dp))
                Text(
                    tr(english, "Без ответа — Honer AI решит сам", "No answer — Honer AI will decide"),
                    fontSize = (fontSize * 0.74f).sp, color = Orange,
                )
            }
        }
    }
}

private fun letter(index: Int, english: Boolean): String {
    val alphabet = if (english) "ABCDEFGHIJKLMNOPQRST" else "АБВГДЕЖЗИКЛМНОПРСТУ"
    return if (index < alphabet.length) alphabet[index].toString() else "${index + 1}"
}

/** Вариант ответа: буква, текст и цвет состояния. */
@Composable
private fun OptionButton(option: String, letter: String, mark: Mark, fontSize: Float, enabled: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = when (mark) {
        Mark.CORRECT -> Green
        Mark.WRONG -> Red
        Mark.SELECTED -> colors.accent
        Mark.NONE, Mark.DIMMED -> colors.divider
    }
    val highlighted = mark == Mark.CORRECT || mark == Mark.WRONG || mark == Mark.SELECTED
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (mark == Mark.DIMMED) 0.55f else 1f)
            .clip(shape)
            .background(if (highlighted) tint.copy(alpha = 0.16f) else colors.raised)
            .border(1.dp, if (highlighted) tint.copy(alpha = 0.75f) else colors.divider.copy(alpha = 0.7f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("question.option.$option")
            .semantics { contentDescription = "$letter. $option" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (highlighted) tint else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, fontSize = (fontSize * 0.74f).sp, fontWeight = FontWeight.Bold, color = if (highlighted) Color.White else colors.secondary)
        }
        Text(option, fontSize = (fontSize * 0.96f).sp, color = colors.foreground, modifier = Modifier.weight(1f))
        when (mark) {
            Mark.CORRECT -> Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(20.dp))
            Mark.WRONG -> Icon(Icons.Filled.Cancel, null, tint = Red, modifier = Modifier.size(20.dp))
            Mark.SELECTED -> Icon(Icons.Filled.Check, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            else -> Unit
        }
    }
}

/** Поле «Свой ответ»: пока в нём пишут, таймер стоит на паузе. */
@Composable
private fun CustomAnswerField(fontSize: Float, english: Boolean, onFocus: (Boolean) -> Unit, onSend: (String) -> Unit) {
    val colors = HonerTheme.colors
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf("") }
    val canSend = draft.isNotBlank()
    val send = {
        val value = draft.trim()
        if (value.isNotEmpty()) {
            draft = ""
            focusManager.clearFocus()
            onSend(value)
        }
    }
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 42.dp)
                .clip(shape)
                .background(colors.raised)
                .border(0.7.dp, colors.divider, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) {
                Text(tr(english, "Свой ответ", "Your answer"), fontSize = (fontSize * 0.95f).sp, color = colors.secondary)
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(fontSize = (fontSize * 0.95f).sp, color = colors.foreground),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) }.testTag("question.custom.field"),
            )
        }
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (canSend) colors.accent else colors.accent.copy(alpha = 0.4f))
                .clickable(enabled = canSend) { send() }
                .testTag("question.custom.send")
                .semantics { contentDescription = tr(english, "Отправить", "Send") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Картинка, звук, видео или файл в вопросе. */
@Composable
private fun QuestionMediaView(media: QuestionMedia, fontSize: Float, english: Boolean) {
    val colors = HonerTheme.colors
    val url = media.url
    when (media.kind) {
        QuestionMedia.Kind.IMAGE -> if (url != null) RemoteImage(url, "", fontSize * 0.8f, english, maxHeight = 240)
        QuestionMedia.Kind.VIDEO -> if (url != null) VideoCard(url, "", fontSize * 0.8f, english)
        QuestionMedia.Kind.AUDIO -> if (url != null) QuestionAudioPlayer(url, english)
        QuestionMedia.Kind.FILE -> Row(
            Modifier.clip(CircleShape).background(colors.raised).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Description, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(media.value, fontSize = (fontSize * 0.8f).sp, fontWeight = FontWeight.Medium, color = colors.foreground)
        }
    }
}

/** Проигрыватель звука внутри вопроса (ExoPlayer создаётся только по нажатию). */
@Composable
private fun QuestionAudioPlayer(url: String, english: Boolean) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    DisposableEffect(url) {
        onDispose {
            player?.release()
            player = null
            playing = false
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            val current = player ?: break
            val duration = current.duration
            if (duration > 0) progress = (current.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            if (current.playbackState == Player.STATE_ENDED) {
                progress = 1f
                playing = false
            }
            delay(200)
        }
    }
    val toggle = {
        val instance = player ?: ExoPlayer.Builder(context).build().also {
            it.setMediaItem(MediaItem.fromUri(url))
            it.prepare()
            player = it
        }
        if (playing) {
            instance.pause()
        } else {
            if (progress >= 0.999f) { instance.seekTo(0); progress = 0f }
            instance.play()
        }
        playing = !playing
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.raised).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(colors.accent).clickable { toggle() }
                .testTag("question.audio.play")
                .semantics { contentDescription = if (playing) tr(english, "Пауза", "Pause") else tr(english, "Слушать", "Play") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(colors.divider)) {
            Box(Modifier.width(maxWidth * progress).height(4.dp).background(colors.accent))
        }
        Icon(Icons.Filled.GraphicEq, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
    }
}

/** Итог: счёт теста или список ответов и отметка об отправке. */
@Composable
private fun SummaryView(
    questions: List<QuickQuestion>,
    answers: List<String?>,
    quiz: Boolean,
    sent: Boolean,
    fontSize: Float,
    english: Boolean,
    onResend: () -> Unit,
) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxWidth().testTag("question.summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (quiz && QuestionnaireReport.isGradable(questions)) {
            val score = QuestionnaireReport.score(answers, questions)
            val fraction = score.toFloat() / max(questions.size, 1)
            val ringColor = when {
                fraction >= 0.7f -> Green
                fraction >= 0.4f -> Orange
                else -> Red
            }
            Row(Modifier.testTag("question.score"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                        val stroke = 6.dp.toPx()
                        drawCircle(colors.divider, style = Stroke(stroke))
                        drawArc(ringColor, -90f, 360f * fraction, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Text("$score/${questions.size}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = colors.foreground)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(tr(english, "Результат", "Result"), fontSize = (fontSize * 0.9f).sp, fontWeight = FontWeight.Bold, color = colors.foreground)
                    Text(
                        when {
                            fraction >= 0.9f -> tr(english, "Отлично!", "Excellent!")
                            fraction >= 0.7f -> tr(english, "Хороший результат", "Good job")
                            fraction >= 0.4f -> tr(english, "Неплохо — разберём ошибки", "Not bad — let's review mistakes")
                            else -> tr(english, "Давайте разберём вместе", "Let's go through it together")
                        },
                        fontSize = (fontSize * 0.75f).sp, color = colors.secondary,
                    )
                }
            }
        }
        questions.forEachIndexed { index, question ->
            val answer = answers.getOrNull(index) ?: ""
            val right = QuestionnaireReport.isCorrect(answer, question)
            val graded = quiz && question.correct.isNotEmpty()
            val (icon, tint) = when {
                answer.isEmpty() -> Icons.Filled.HourglassBottom to Orange
                graded && right -> Icons.Filled.CheckCircle to Green
                graded -> Icons.Filled.Cancel to Red
                else -> Icons.Outlined.CheckCircle to colors.accent
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(question.text, fontSize = (fontSize * 0.8f).sp, fontWeight = FontWeight.Medium, color = colors.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(answer.ifEmpty { tr(english, "нет ответа", "no answer") }, fontSize = (fontSize * 0.74f).sp, color = colors.secondary)
                    if (graded && !right) {
                        Text(
                            tr(english, "Правильно: ", "Correct: ") + QuestionnaireReport.correctText(question),
                            fontSize = (fontSize * 0.74f).sp, fontWeight = FontWeight.Medium, color = Green,
                        )
                    }
                }
            }
        }
        if (sent) {
            Row(Modifier.testTag("question.sent"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                Text(
                    if (questions.size == 1) tr(english, "Ответ отправлен Honer AI", "Answer sent to Honer AI")
                    else tr(english, "Ответы отправлены Honer AI", "Answers sent to Honer AI"),
                    fontSize = (fontSize * 0.74f).sp, color = colors.accent,
                )
            }
        } else {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.15f))
                    .clickable(onClick = onResend)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("question.send"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                Text(tr(english, "Отправить ответы", "Send answers"), fontSize = (fontSize * 0.8f).sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            }
        }
    }
}
