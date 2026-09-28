package com.honerai.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.common.LocalReduceMotion
import com.honerai.app.ui.theme.HonerTheme
import kotlin.math.abs
import kotlin.math.sin

/**
 * Полоса записи над полем ввода: подсказка и «волна» голоса. Уровень громкости
 * служба речи не отдаёт, поэтому волна рисуется плавной анимацией, пока идёт запись.
 */
@Composable
fun VoiceRecordingOverlay(recording: Boolean, finalizing: Boolean, english: Boolean, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    val reduce = LocalReduceMotion.current
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(recording, reduce) {
        if (!recording || reduce) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
        }
    }
    val title = when {
        finalizing -> if (english) "Finishing…" else "Распознаю…"
        !recording -> if (english) "Starting microphone…" else "Подключаю микрофон…"
        else -> if (english) "Speak. Tap ■ to send" else "Говорите. Нажмите ■, чтобы отправить"
    }
    Column(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0f), colors.background.copy(alpha = 0.95f), Color(0xFF263045))))
            .testTag("chat.voice.recording.overlay"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 20.dp))
        val tint = colors.accent
        Canvas(Modifier.padding(top = 28.dp).widthIn(max = 220.dp).fillMaxWidth().height(55.dp)) {
            val count = 48
            val bar = 2.dp.toPx()
            val gap = (size.width - bar * count) / (count - 1)
            val level = if (recording) 0.45f + 0.35f * abs(sin(time * 2.3f)) else 0.08f
            for (i in 0 until count) {
                val weight = 0.3f + 0.7f * abs(sin(i * 1.7f + time * 3f))
                val h = 4.dp.toPx() + 42.dp.toPx() * level * weight
                val x = i * (bar + gap)
                drawRoundRect(tint, Offset(x, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2, bar / 2))
            }
        }
    }
}
