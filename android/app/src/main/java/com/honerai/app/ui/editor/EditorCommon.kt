package com.honerai.app.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.media.EditColors
import com.honerai.app.ui.theme.HonerTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Палитра редактора (как EditorPalette на iOS). */
internal object EditorPalette {
    val colors: List<Int> = listOf(
        EditColors.WHITE, EditColors.BLACK,
        EditColors.rgb(0.93, 0.22, 0.21), EditColors.rgb(1.0, 0.58, 0.1), EditColors.rgb(1.0, 0.84, 0.1),
        EditColors.rgb(0.2, 0.75, 0.35), EditColors.rgb(0.3, 0.8, 0.95), EditColors.rgb(0.18, 0.42, 0.95),
        EditColors.rgb(0.58, 0.32, 0.9), EditColors.rgb(1.0, 0.45, 0.7),
    )

    val gradients: List<List<Int>> = listOf(
        listOf(EditColors.rgb(1.0, 0.45, 0.6), EditColors.rgb(1.0, 0.72, 0.3)),
        listOf(EditColors.rgb(0.3, 0.45, 1.0), EditColors.rgb(0.72, 0.35, 0.95)),
        listOf(EditColors.rgb(0.1, 0.75, 0.7), EditColors.rgb(0.55, 0.9, 0.4)),
        listOf(EditColors.rgb(0.12, 0.12, 0.2), EditColors.rgb(0.35, 0.3, 0.55)),
    )

    val emojis: List<String> = listOf(
        "😀", "😂", "🥰", "😍", "😎", "🤩", "🥳", "😜", "🤔", "😴", "😭", "😡",
        "👍", "👏", "🙌", "💪", "🙏", "✌️", "👀", "💯", "🔥", "✨", "⭐️", "🌟",
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "🎉", "🎁", "🎈", "🎂",
        "🌸", "🌈", "☀️", "🌙", "⚡️", "❄️", "🐱", "🐶", "🦄", "🍕", "☕️", "🚀",
    )

    fun color(index: Int): Int = colors[index.coerceIn(0, colors.size - 1)]
}

internal object EditorGeometry {
    /** Размер картинки, вписанной в контейнер (в пикселях). */
    fun fitted(width: Int, height: Int, containerWidth: Float, containerHeight: Float): Size {
        if (width <= 0 || height <= 0 || containerWidth <= 0 || containerHeight <= 0) return Size.Zero
        val scale = min(containerWidth / width, containerHeight / height)
        return Size(floor(width * scale), floor(height * scale))
    }

    fun timeString(seconds: Double): String {
        val value = if (seconds.isFinite()) max(0.0, seconds) else 0.0
        val minutes = (value / 60).toInt()
        val rest = value - minutes * 60
        return String.format(java.util.Locale.US, "%d:%04.1f", minutes, rest)
    }
}

/** Шахматка под прозрачными участками фото. */
@Composable
internal fun EditorCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White)) {
        val tile = 10.dp.toPx()
        val columns = ceil(size.width / tile).toInt()
        val rows = ceil(size.height / tile).toInt()
        val gray = Color(0xFFD1D1D1)
        for (row in 0 until rows) for (column in 0 until columns) {
            if ((row + column) % 2 == 0) drawRect(gray, Offset(column * tile, row * tile), Size(tile, tile))
        }
    }
}

@Composable
internal fun EditorSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onBegin: () -> Unit = {},
    format: (Float) -> String = { (it * 100).toInt().toString() },
) {
    val colors = HonerTheme.colors
    // Начало жеста (для записи в историю отмены) — один раз на перетаскивание.
    val began = remember { booleanArrayOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
        Slider(
            value = value, valueRange = range, enabled = enabled,
            onValueChange = {
                if (!began[0]) { began[0] = true; onBegin() }
                onChange(it)
            },
            onValueChangeFinished = { began[0] = false },
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.raised),
            modifier = Modifier.weight(1f).height(36.dp),
        )
        Text(format(value), color = colors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}

@Composable
internal fun EditorColorRow(selection: Int, onSelect: (Int) -> Unit, label: String) {
    val colors = HonerTheme.colors
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(EditorPalette.colors) { index, color ->
            val selected = selection == index
            Box(
                Modifier.size(36.dp)
                    .border(if (selected) 3.dp else 0.dp, if (selected) colors.accent else Color.Transparent, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, colors.divider, CircleShape)
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = "$label ${index + 1}" },
            )
        }
    }
}

@Composable
internal fun EditorChip(title: String, icon: ImageVector?, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val content = if (selected) Color.White else colors.foreground
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.accent else colors.raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(title, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Затемнение с индикатором на время тяжёлой правки или сохранения. */
@Composable
internal fun EditorBusyOverlay(message: String?, progress: Double? = null) {
    val colors = HonerTheme.colors
    val lastMessage = remember { arrayOf("") }
    if (message != null) lastMessage[0] = message
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                // Перехватываем касания, пока идёт работа.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(min = 180.dp).clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat().coerceIn(0f, 1f) }, color = colors.accent, trackColor = colors.raised,
                        modifier = Modifier.width(160.dp),
                    )
                    Text("${(progress * 100).toInt()}%", color = colors.secondary, fontSize = 13.sp)
                } else {
                    CircularProgressIndicator(color = colors.accent)
                }
                Text(message ?: lastMessage[0], color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Верхняя панель редактора: «Отмена», заголовок, «Готово». */
@Composable
internal fun EditorTopBar(title: String, cancel: String, done: String, doneEnabled: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.testTag("editor.cancel")) {
            Text(cancel, color = colors.foreground, fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(title, color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.padding(end = 8.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accent.copy(alpha = if (doneEnabled) 1f else 0.5f))
                .clickable(enabled = doneEnabled, onClick = onDone)
                .padding(horizontal = 16.dp)
                .testTag("editor.save"),
            contentAlignment = Alignment.Center,
        ) {
            Text(done, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
