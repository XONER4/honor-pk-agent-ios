package com.honerai.app.core

import android.view.Choreographer
import com.honerai.app.data.GenerationStep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Скорость печати: сколько символов показать в очередном кадре (перенос StreamPace с iOS).
 * Печать держится примерно на [targetLatency] секунд позади потока, скорость меняется
 * плавно — пачки текста из сети превращаются в ровный набор без рывков.
 */
data class StreamPace(
    val minimumRate: Double = 110.0,
    val maximumRate: Double = 3000.0,
    val targetLatency: Double = 0.35,
    val closingLatency: Double = 0.18,
    val responsiveness: Double = 8.0,
    val maximumTail: Double = 1.0,
    val closingMinimumRate: Double = 260.0,
) {
    fun step(state: StreamPaceState, available: Int, elapsed: Double, streamOpen: Boolean): Int {
        // Задержавшийся кадр не должен давать рывок.
        val dt = max(0.0, min(elapsed, 1.0 / 20.0))
        if (!streamOpen) state.sinceClose += dt
        val lag = available - state.shown
        if (lag <= 0) {
            state.shown = min(state.shown, available.toDouble())
            return state.shown.toInt()
        }
        if (!streamOpen && state.sinceClose >= maximumTail) {
            state.shown = available.toDouble()
            return available
        }
        val latency = if (streamOpen) targetLatency else closingLatency
        val floor = if (streamOpen) minimumRate else max(minimumRate, closingMinimumRate)
        val desired = min(max(lag / latency, floor), maximumRate)
        if (state.rate <= 0) state.rate = minimumRate
        val blend = 1 - exp(-responsiveness * dt)
        state.rate += (desired - state.rate) * blend
        state.shown = min(state.shown + state.rate * dt, available.toDouble())
        return state.shown.toInt()
    }
}

class StreamPaceState {
    var shown = 0.0
    var rate = 0.0
    var sinceClose = 0.0
    fun restart() { shown = 0.0; rate = 0.0; sinceClose = 0.0 }
}

/**
 * Печатаемый текст: полученная часть и показанная часть (её начало).
 * Длины — в UTF-16; разрез никогда не попадает внутрь суррогатной пары (эмодзи).
 */
class TypedText {
    var target: String = ""; private set
    var shown: String = ""; private set
    val targetCount: Int get() = target.length
    val shownCount: Int get() = shown.length
    val isComplete: Boolean get() = shown.length >= target.length

    fun isTarget(text: String): Boolean = text.length == target.length && text == target

    /** Новая версия полученного текста. Возвращает, сколько символов уже видно. */
    fun setTarget(text: String): Int {
        if (text == target) return shown.length
        if (text.startsWith(target)) {
            target = text
            return shown.length
        }
        // Текст заменили (перевод, служебная строка): видимым остаётся общее начало.
        var common = 0
        val limit = min(text.length, shown.length)
        while (common < limit && text[common] == shown[common]) common++
        if (common > 0 && Character.isHighSurrogate(text[common - 1])) common--
        target = text
        shown = text.substring(0, common)
        return common
    }

    fun reveal(upTo: Int) {
        var end = min(upTo, target.length)
        if (end <= shown.length) return
        if (end < target.length && end > 0 && Character.isHighSurrogate(target[end - 1])) end++
        shown = target.substring(0, end)
    }

    fun revealAll() { shown = target }
    fun clear() { target = ""; shown = "" }
}

/**
 * Плавная печать текущего ответа — и рассуждения, и итогового текста.
 * Живёт на главном потоке; кадры берутся от Choreographer (60/90/120/144 Гц —
 * сколько умеет экран), поэтому печать идёт в такт обновлению дисплея.
 */
class TypingPacer {
    private val _messageId = MutableStateFlow<String?>(null)
    private val _content = MutableStateFlow("")
    private val _reasoning = MutableStateFlow("")
    private val _steps = MutableStateFlow<List<GenerationStep>>(emptyList())
    private val _reasoningEndedAt = MutableStateFlow<Long?>(null)
    private val _grew = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ответ, который сейчас печатается. */
    val messageId: StateFlow<String?> = _messageId.asStateFlow()
    /** Видимая часть ответа. */
    val content: StateFlow<String> = _content.asStateFlow()
    /** Видимая часть рассуждения. */
    val reasoning: StateFlow<String> = _reasoning.asStateFlow()
    /** Шаги работы: поиск, чтение страниц, рисование. */
    val steps: StateFlow<List<GenerationStep>> = _steps.asStateFlow()
    /** Когда закончилось рассуждение (миллисекунды), для счётчика «Размышлял N с». */
    val reasoningEndedAt: StateFlow<Long?> = _reasoningEndedAt.asStateFlow()
    /** Сигнал «текст вырос» несколько раз в секунду — по нему лента едет вслед за ответом. */
    val grew: SharedFlow<Unit> = _grew.asSharedFlow()

    /** Когда начался ответ (миллисекунды). */
    var startedAt: Long = System.currentTimeMillis(); private set

    /** Печать закончилась: весь текст показан, поток закрыт. */
    var onFinished: ((String) -> Unit)? = null

    private val contentText = TypedText()
    private val reasoningText = TypedText()
    private val contentPace = StreamPaceState()
    private val reasoningPace = StreamPaceState()
    private var streamOpen = false
    private var running = false
    private var lastFrameNanos = 0L
    private var lastGrowthNanos = 0L
    private val rule = StreamPace()
    /** Рассуждение печатается быстрее: его читают вполглаза. */
    private val reasoningRule = StreamPace(minimumRate = 160.0, targetLatency = 0.25, closingLatency = 0.12, maximumTail = 0.6)

    val isActive: Boolean get() = _messageId.value != null

    fun begin(messageId: String) {
        stopFrames()
        contentText.clear(); reasoningText.clear()
        contentPace.restart(); reasoningPace.restart()
        _content.value = ""; _reasoning.value = ""
        startedAt = System.currentTimeMillis()
        _reasoningEndedAt.value = null
        _steps.value = emptyList()
        streamOpen = true
        _messageId.value = messageId
    }

    fun setSteps(value: List<GenerationStep>) {
        if (_messageId.value == null || value == _steps.value) return
        _steps.value = value
        _grew.tryEmit(Unit)
    }

    fun update(content: String, reasoning: String) {
        if (_messageId.value == null) return
        if (!reasoningText.isTarget(reasoning)) {
            val kept = reasoningText.setTarget(reasoning)
            if (kept < reasoningPace.shown) { reasoningPace.shown = kept.toDouble(); _reasoning.value = reasoningText.shown }
        }
        if (!contentText.isTarget(content)) {
            if (_reasoningEndedAt.value == null && content.isNotEmpty()) _reasoningEndedAt.value = System.currentTimeMillis()
            val kept = contentText.setTarget(content)
            if (kept < contentPace.shown) { contentPace.shown = kept.toDouble(); _content.value = contentText.shown }
        }
        startFramesIfNeeded()
    }

    fun close(content: String, reasoning: String) {
        if (_messageId.value == null) return
        update(content, reasoning)
        streamOpen = false
        contentPace.sinceClose = 0.0
        reasoningPace.sinceClose = 0.0
        if (contentText.isComplete && reasoningText.isComplete) finish() else startFramesIfNeeded()
    }

    /** Остановить печать сразу и показать всё полученное. */
    fun cancel() {
        if (_messageId.value == null) return
        contentText.revealAll(); reasoningText.revealAll()
        _content.value = contentText.shown; _reasoning.value = reasoningText.shown
        streamOpen = false
        finish()
    }

    private fun finish() {
        stopFrames()
        val id = _messageId.value ?: return
        streamOpen = false
        _messageId.value = null
        _grew.tryEmit(Unit)
        onFinished?.invoke(id)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick(frameTimeNanos)
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFramesIfNeeded() {
        if (running || _messageId.value == null) return
        if (contentText.isComplete && reasoningText.isComplete && streamOpen) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrames() {
        if (running) Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
        lastFrameNanos = 0L
    }

    private fun tick(now: Long) {
        val elapsed = if (lastFrameNanos == 0L) 1.0 / 60.0 else (now - lastFrameNanos) / 1_000_000_000.0
        lastFrameNanos = now
        var changed = false
        if (!reasoningText.isComplete) {
            val count = reasoningRule.step(reasoningPace, reasoningText.targetCount, elapsed, streamOpen)
            if (count > reasoningText.shownCount) {
                reasoningText.reveal(count)
                _reasoning.value = reasoningText.shown
                changed = true
            }
        }
        // Ответ печатается, когда рассуждение уже показано целиком.
        if ((reasoningText.isComplete || !streamOpen) && !contentText.isComplete) {
            val count = rule.step(contentPace, contentText.targetCount, elapsed, streamOpen)
            if (count > contentText.shownCount) {
                contentText.reveal(count)
                _content.value = contentText.shown
                changed = true
            }
        }
        if (changed && now - lastGrowthNanos >= 50_000_000L) {
            lastGrowthNanos = now
            _grew.tryEmit(Unit)
        }
        if (contentText.isComplete && reasoningText.isComplete) {
            if (streamOpen) stopFrames() else finish()
        }
    }
}
