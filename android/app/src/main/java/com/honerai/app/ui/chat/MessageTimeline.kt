package com.honerai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.TypingPacer
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageRole
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE = 40

/**
 * Лента сообщений: окно из последних 40 сообщений («Показать более ранние»),
 * сопровождение печатающегося ответа и кнопка «К последнему сообщению».
 *
 * Лента едет за печатью ([TypingPacer.grew] и каждый кадр, пока печатается ответ),
 * пока пользователь не отлистал вверх. После открытия чата ещё ~1,2 с досматриваем
 * до конца: высоты ленивых элементов становятся известны не сразу.
 */
@Composable
fun MessageTimeline(
    messages: List<ChatMessage>,
    chatId: String?,
    generating: Boolean,
    typingId: String?,
    status: String?,
    pacer: TypingPacer,
    findQuery: String,
    selectedMatch: String?,
    scrollRequest: ScrollRequest?,
    composing: Boolean,
    english: Boolean,
    fontScale: Float,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    var visibleLimit by remember(chatId) { mutableIntStateOf(PAGE) }
    val shown = remember(messages, visibleLimit) { if (messages.size > visibleLimit) messages.takeLast(visibleLimit) else messages }
    val hidden = messages.size - shown.size
    val headerCount = 1 + if (hidden > 0) 1 else 0
    val latestId = messages.lastOrNull()?.id
    val tailId = latestId

    val follow by rememberUpdatedState(followLatest)
    val query by rememberUpdatedState(findQuery)

    suspend fun toBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0 && listState.canScrollForward) listState.scrollToItem(last)
    }

    // Пользователь потянул ленту — перестаём ехать за ответом; вернулся к концу — снова едем.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followLatest = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (!listState.canScrollForward) followLatest = true
            }
        }
    }
    // Лента остановилась у самого конца (в том числе после броска) — снова едем за ответом.
    LaunchedEffect(listState) {
        snapshotFlow { !listState.isScrollInProgress && !listState.canScrollForward }
            .collect { if (it) followLatest = true }
    }

    // Открыли чат: к концу и ещё 1,2 с досматриваем, пока высоты строк уточняются.
    LaunchedEffect(chatId) {
        followLatest = true
        toBottom()
        val deadline = System.nanoTime() + 1_200_000_000L
        while (System.nanoTime() < deadline) {
            withFrameNanos { }
            if (!follow) break
            toBottom()
        }
    }

    // Сигнал печати «текст вырос» (и шаги работы).
    LaunchedEffect(pacer) {
        pacer.grew.collect { if (follow && query.isEmpty()) toBottom() }
    }

    // Пока ответ печатается — каждый кадр: текст едет вместе с набором, без рывков.
    LaunchedEffect(typingId, followLatest, findQuery) {
        if (!followLatest || findQuery.isNotEmpty()) return@LaunchedEffect
        if (typingId != null) {
            while (true) {
                withFrameNanos { }
                toBottom()
            }
        } else {
            // Печать закончилась: итоговая разметка может перестроиться — ещё немного следим.
            val deadline = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < deadline) {
                withFrameNanos { }
                toBottom()
            }
        }
    }

    // Новое сообщение пользователя — снова едем к концу.
    LaunchedEffect(messages.size) {
        val last = messages.lastOrNull()
        val previous = messages.getOrNull(messages.size - 2)
        if (last?.role == MessageRole.USER || previous?.role == MessageRole.USER) followLatest = true
        if (followLatest && findQuery.isEmpty()) toBottom()
    }

    // Начался ответ: один мягкий переход к нему.
    LaunchedEffect(generating) {
        if (!generating || !followLatest || findQuery.isNotEmpty()) return@LaunchedEffect
        delay(90)
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0 && follow) listState.animateScrollToItem(last)
    }

    // Поднялась клавиатура — конец ответа должен остаться на виду.
    LaunchedEffect(composing) {
        if (!composing) return@LaunchedEffect
        followLatest = true
        val deadline = System.nanoTime() + 700_000_000L
        while (System.nanoTime() < deadline) {
            withFrameNanos { }
            if (!follow) break
            toBottom()
        }
    }

    // Переход к найденному сообщению.
    LaunchedEffect(selectedMatch) {
        val id = selectedMatch ?: return@LaunchedEffect
        followLatest = false
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        if (index < messages.size - visibleLimit) {
            visibleLimit = messages.size - index + 6
            withFrameNanos { }
        }
        val position = indexOfMessage(id, messages, visibleLimit)
        val viewport = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem(position, -viewport / 3)
    }

    // Переход по линиям навигации справа.
    LaunchedEffect(scrollRequest) {
        val request = scrollRequest ?: return@LaunchedEffect
        followLatest = false
        val index = messages.indexOfFirst { it.id == request.messageId }
        if (index < 0) return@LaunchedEffect
        if (index < messages.size - visibleLimit) {
            // Сообщение старше показанного окна: сначала показываем его, прокручиваем на следующем кадре.
            visibleLimit = messages.size - index + 6
            delay(120)
        }
        listState.animateScrollToItem(indexOfMessage(request.messageId, messages, visibleLimit))
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("chat.messages"),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(25.dp),
        ) {
            item(key = "disclaimer", contentType = "disclaimer") {
                Text(
                    if (english) "AI-generated answers are for reference." else "Сгенерированный ИИ ответ, только для справки.",
                    fontSize = (13 * fontScale).sp, fontWeight = FontWeight.Medium, color = colors.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 23.dp, bottom = 3.dp),
                )
            }
            if (hidden > 0) {
                item(key = "earlier", contentType = "earlier") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .border(0.7.dp, colors.divider, CircleShape)
                            .clickable { visibleLimit = minOf(visibleLimit + 60, messages.size) }
                            .testTag("chat.load.earlier"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, null, tint = colors.secondary, modifier = Modifier.size(15.dp))
                        Text(
                            (if (english) " Show earlier ($hidden)" else " Показать более ранние ($hidden)"),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.secondary,
                        )
                    }
                }
            }
            items(shown, key = { it.id }, contentType = { it.role }) { message ->
                MessageRow(
                    message = message,
                    streaming = generating && message.id == tailId,
                    typing = typingId == message.id,
                    status = if (generating && message.id == tailId) status else null,
                    pacer = pacer,
                    isLatest = message.id == latestId && !generating,
                    findQuery = findQuery,
                    selectedMatch = selectedMatch == message.id,
                    english = english,
                    fontScale = fontScale,
                    actions = actions,
                )
            }
            item(key = "bottom", contentType = "bottom") { Spacer(Modifier.height(8.dp)) }
        }

        val showJump by remember { derivedStateOf { listState.canScrollForward } }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        AnimatedVisibility(
            visible = !followLatest && showJump,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 6.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable {
                        followLatest = true
                        scope.launch {
                            val last = listState.layoutInfo.totalItemsCount - 1
                            if (last >= 0) listState.animateScrollToItem(last)
                        }
                    }
                    .semantics { contentDescription = if (english) "Jump to latest message" else "К последнему сообщению" }
                    .testTag("chat.scroll.latest"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(38.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(colors.raised)
                        .border(0.7.dp, colors.divider, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ArrowDownward, null, tint = colors.foreground, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

/** Номер элемента ленты для сообщения: учитывает строку-пояснение и кнопку «Показать более ранние». */
private fun indexOfMessage(id: String, messages: List<ChatMessage>, limit: Int): Int {
    val shown = if (messages.size > limit) messages.takeLast(limit) else messages
    val hidden = messages.size - shown.size
    val header = 1 + if (hidden > 0) 1 else 0
    return header + shown.indexOfFirst { it.id == id }.coerceAtLeast(0)
}
