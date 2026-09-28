package com.honerai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageRole
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.rememberHaptics
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Линии навигации по сообщениям у правого края чата. Каждая линия — одно сообщение
 * (длинная — ваше, короткая — ответ Honer AI). Касание — переход к сообщению; если
 * зажать полосу и вести пальцем, над ней появляется превью. Отпустили — чат прокручивается
 * к сообщению, а в превью остаётся «Продолжить отсюда»: разговор продолжится в новой ветке.
 */
@Composable
fun MessageNavigationStrip(
    messages: List<ChatMessage>,
    english: Boolean,
    onJump: (String) -> Unit,
    onBranch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    val haptics = rememberHaptics()
    val items = remember(messages) {
        messages.filter { it.role != MessageRole.TOOL && (it.content.isNotEmpty() || it.attachments.isNotEmpty()) }
    }
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    var shownIndex by remember { mutableStateOf<Int?>(null) }
    var hideNonce by remember { mutableStateOf(0) }
    LaunchedEffect(hideNonce) {
        if (hideNonce == 0) return@LaunchedEffect
        delay(5000)
        shownIndex = null
    }
    val currentItems by rememberUpdatedState(items)
    val jump by rememberUpdatedState(onJump)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val layout = NavigationLayout(items.size, heightPx, with(density) { 12.dp.toPx() })
        val highlighted = activeIndex ?: shownIndex

        Canvas(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, message ->
                val active = index == highlighted
                val length = when {
                    active -> 22.dp.toPx()
                    message.role == MessageRole.USER -> 13.dp.toPx()
                    else -> 8.dp.toPx()
                }
                val thickness = if (active) 3.5.dp.toPx() else 2.5.dp.toPx()
                val color = when {
                    active -> colors.accent
                    message.role == MessageRole.USER -> colors.secondary.copy(alpha = 0.55f)
                    else -> colors.secondary.copy(alpha = 0.9f)
                }
                val right = size.width - 8.dp.toPx()
                val y = layout.y(index)
                drawLine(color, Offset(right - length, y), Offset(right, y), thickness, StrokeCap.Round)
            }
        }

        // Полоса касаний у правого края.
        val touchTop = with(density) { (layout.top - 12.dp.toPx()).toDp() }
        val touchHeight = with(density) { (layout.height + 24.dp.toPx()).toDp() }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = touchTop)
                .width(34.dp)
                .height(touchHeight)
                .semantics {
                    contentDescription = if (english) "Message navigation" else "Навигация по сообщениям"
                    stateDescription = if (english) "${items.size} messages" else "${items.size} сообщений"
                }
                .testTag("chat.nav.strip")
                .pointerInput(heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val current = NavigationLayout(currentItems.size, heightPx, 12.dp.toPx())
                        val offsetTop = current.top - 12.dp.toPx()
                        fun indexAt(y: Float) = current.index(y + offsetTop)
                        var index = indexAt(down.position.y)
                        activeIndex = index
                        shownIndex = null
                        haptics.selection()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val next = indexAt(change.position.y)
                            if (next != index) {
                                index = next
                                activeIndex = next
                                haptics.selection()
                            }
                        }
                        val list = currentItems
                        if (index in list.indices) {
                            jump(list[index].id)
                            shownIndex = index
                            hideNonce++
                        }
                        activeIndex = null
                    }
                },
        )

        val previewIndex = highlighted
        val visible = previewIndex != null && previewIndex in items.indices
        val cardWidth = minOf(270.dp, maxWidth - 64.dp)
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val y = if (previewIndex != null) layout.y(previewIndex).coerceIn(with(density) { 70.dp.toPx() }, heightPx - with(density) { 70.dp.toPx() }) else 0f
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            modifier = Modifier.offset {
                IntOffset((widthPx - with(density) { 34.dp.toPx() } - cardWidthPx).roundToInt(),
                    (y - with(density) { 60.dp.toPx() }).roundToInt().coerceAtLeast(0))
            },
        ) {
            val message = items.getOrNull(previewIndex ?: -1)
            if (message != null) {
                NavigationPreview(message, english, branchable = activeIndex == null, modifier = Modifier.width(cardWidth),
                    onBranch = { shownIndex = null; onBranch(message.id) }, onClose = { shownIndex = null })
            }
        }
    }
}

@Composable
private fun NavigationPreview(
    message: ChatMessage,
    english: Boolean,
    branchable: Boolean,
    modifier: Modifier,
    onBranch: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val text = remember(message.id, message.content) { SpeechText.preview(message) }
    Column(
        modifier
            .shadow(14.dp, shape)
            .clip(shape)
            .background(colors.sidebar)
            .border(0.7.dp, colors.divider, shape)
            .padding(12.dp)
            .testTag("chat.nav.preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (message.role == MessageRole.USER) Icons.Rounded.Person else Icons.Rounded.AutoAwesome, null,
                tint = colors.accent, modifier = Modifier.size(13.dp))
            Text(if (message.role == MessageRole.USER) (if (english) "You" else "Вы") else "Honer AI",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            Spacer(Modifier.weight(1f))
            Text(TimeText.clock(message.createdAt), fontSize = 11.sp, color = colors.secondary)
        }
        Text(text, fontSize = 14.sp, color = colors.foreground, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (branchable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)).clickable(onClick = onBranch)
                        .padding(horizontal = 10.dp, vertical = 7.dp).testTag("chat.nav.branch"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.CallSplit, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                    Text(if (english) "Continue from here" else "Продолжить отсюда", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.accent)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClose)
                        .semantics { contentDescription = if (english) "Close" else "Закрыть" }.testTag("chat.nav.preview.close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, null, tint = colors.secondary, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}
