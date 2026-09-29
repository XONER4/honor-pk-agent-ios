package com.honerai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.app.core.TypingPacer
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.GenerationStep
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageFeedback
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.data.WebSource
import com.honerai.app.device.ParentalControl
import com.honerai.app.ui.common.AttachmentFileCard
import com.honerai.app.ui.common.AttachmentThumbnail
import com.honerai.app.ui.common.HonerActionButton
import com.honerai.app.ui.common.HonerImages
import com.honerai.app.ui.common.MediaLinks
import com.honerai.app.ui.common.QuoteChip
import com.honerai.app.ui.common.StatusText
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.rememberHaptics
import com.honerai.app.ui.markdown.MarkdownContent
import com.honerai.app.ui.tables.ChatTableCards
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay

/** Текст с подсветкой найденного («Найти в чате»). */
fun highlighted(text: String, query: String, color: Color): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val index = text.indexOf(needle, from, ignoreCase = true)
            if (index < 0) break
            addStyle(SpanStyle(background = color), index, index + needle.length)
            from = index + needle.length
        }
    }
}

fun menuIcon(action: MessageMenuAction): ImageVector = when (action) {
    MessageMenuAction.COPY -> Icons.Rounded.ContentCopy
    MessageMenuAction.SELECT -> Icons.Rounded.TextFields
    MessageMenuAction.QUOTE -> Icons.Rounded.FormatQuote
    MessageMenuAction.EDIT -> Icons.Rounded.Edit
    MessageMenuAction.SHARE -> Icons.Rounded.IosShare
    MessageMenuAction.RETRY -> Icons.Rounded.Refresh
    MessageMenuAction.LIKE -> Icons.Outlined.ThumbUp
    MessageMenuAction.DISLIKE -> Icons.Outlined.ThumbDown
    MessageMenuAction.SPEAK -> Icons.AutoMirrored.Rounded.VolumeUp
    MessageMenuAction.FORK -> Icons.AutoMirrored.Rounded.CallSplit
    MessageMenuAction.REMEMBER -> Icons.Rounded.BookmarkBorder
    MessageMenuAction.PIN_INSTRUCTION -> Icons.Outlined.PushPin
}

/**
 * Одно сообщение переписки. Строка не следит за печатью сама — за ней следит только
 * вложенный [LiveAssistantBody], поэтому каждый кадр печати перерисовывает одну строку.
 */
@Composable
fun MessageRow(
    message: ChatMessage,
    streaming: Boolean,
    typing: Boolean,
    status: String?,
    pacer: TypingPacer,
    isLatest: Boolean,
    findQuery: String,
    selectedMatch: Boolean,
    english: Boolean,
    fontScale: Float,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    val menuLabel = if (english) "Message actions" else "Действия с сообщением"
    val role = if (message.role == MessageRole.USER) "user" else "assistant"
    Box(
        modifier
            .fillMaxWidth()
            .then(if (selectedMatch) Modifier.border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)) else Modifier)
            .pointerInput(message.id) {
                detectTapGestures(onLongPress = { offset ->
                    menuAnchor = offset
                    menuOpen = true
                    haptics.light()
                    actions.onMenuOpened()
                })
            }
            .semantics {
                customActions = listOf(CustomAccessibilityAction(menuLabel) { menuOpen = true; actions.onMenuOpened(); true })
            }
            .testTag("message.$role.${message.id}"),
    ) {
        if (message.role == MessageRole.USER) {
            UserMessage(message, findQuery, english, fontScale, actions)
        } else {
            AssistantMessage(message, streaming, typing, status, pacer, isLatest, findQuery, english, fontScale, actions)
        }
        Box(Modifier.offset { IntOffset(menuAnchor.x.toInt(), menuAnchor.y.toInt()) }.size(1.dp)) {
            MessageMenu(
                message = message,
                expanded = menuOpen,
                english = english,
                fontScale = fontScale,
                onDismiss = { menuOpen = false },
                onAction = { action ->
                    menuOpen = false
                    actions.onMenuAction(message, action)
                },
            )
        }
    }
}

/** Всплывающее меню долгого нажатия: копировать, выделить и спросить, цитировать, ветка… */
@Composable
private fun MessageMenu(
    message: ChatMessage,
    expanded: Boolean,
    english: Boolean,
    fontScale: Float,
    onDismiss: () -> Unit,
    onAction: (MessageMenuAction) -> Unit,
) {
    val colors = HonerTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.sidebar,
        border = androidx.compose.foundation.BorderStroke(0.8.dp, colors.divider),
        shadowElevation = 12.dp,
        modifier = Modifier.width(244.dp).testTag("message.menu.scroll"),
    ) {
        val hasText = message.content.isNotBlank()
        MessageMenuAction.available(message.role).forEach { action ->
            val selected = (action == MessageMenuAction.LIKE && message.feedback == MessageFeedback.LIKE) ||
                (action == MessageMenuAction.DISLIKE && message.feedback == MessageFeedback.DISLIKE)
            val tint = if (selected) colors.accent else colors.foreground
            DropdownMenuItem(
                text = {
                    Text(action.title(english), fontSize = (17 * fontScale).sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = { Icon(menuIcon(action), null, modifier = Modifier.size(21.dp)) },
                enabled = !(action.requiresContent && !hasText),
                onClick = { onAction(action) },
                colors = MenuDefaults.itemColors(
                    textColor = tint, leadingIconColor = tint,
                    disabledTextColor = colors.secondary.copy(alpha = 0.5f),
                    disabledLeadingIconColor = colors.secondary.copy(alpha = 0.5f),
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 21.dp),
                modifier = Modifier.heightIn(min = maxOf(45f, 36f * fontScale + 8f).dp).testTag("message.menu." + action.raw),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Сообщение пользователя.

@Composable
private fun UserMessage(message: ChatMessage, findQuery: String, english: Boolean, fontScale: Float, actions: MessageActions) {
    val colors = HonerTheme.colors
    val bubbleShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 4.dp)
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.End) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(35.dp))
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .clip(bubbleShape)
                    .background(colors.bubble)
                    .padding(horizontal = 15.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val quote = message.quote
                if (!quote.isNullOrEmpty()) QuoteChip(quote, scale = fontScale)
                MessageAttachments(message.attachments, english, fontScale, actions)
                if (message.content.isNotEmpty()) {
                    val highlight = remember(message.content, findQuery) {
                        highlighted(message.content, findQuery, Color(0x59FFD60A))
                    }
                    Text(highlight, fontSize = (18 * fontScale).sp, lineHeight = (24 * fontScale).sp, color = colors.foreground)
                }
            }
        }
        if (message.inputKind == MessageInputKind.VOICE || message.assistantReaction != null) {
            Row(Modifier.padding(top = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (message.inputKind == MessageInputKind.VOICE) {
                    // Пометка, что сообщение наговорено голосом, а не набрано.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("message.voice." + message.id)) {
                        Icon(Icons.Rounded.Mic, null, tint = colors.secondary, modifier = Modifier.size(12.dp))
                        Text(if (english) " By voice" else " Голосом", fontSize = 11.sp, color = colors.secondary)
                    }
                }
                message.assistantReaction?.let { reaction ->
                    // Реакция Honer AI на сообщение пользователя.
                    Text(
                        reaction, fontSize = 16.sp,
                        modifier = Modifier.clip(CircleShape).background(colors.raised).border(0.6.dp, colors.divider, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .semantics { contentDescription = (if (english) "Honer AI reaction: " else "Реакция Honer AI: ") + reaction }
                            .testTag("message.assistant.reaction." + message.id),
                    )
                }
            }
        }
    }
}

/** Вложения сообщения: стикер крупно, фото и видео миниатюрой, файлы карточкой. */
@Composable
private fun MessageAttachments(attachments: List<MessageAttachment>, english: Boolean, fontScale: Float, actions: MessageActions) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        attachments.forEach { attachment ->
            when (attachment.kind) {
                AttachmentKind.STICKER -> Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { actions.onAttachment(attachment) }
                        .semantics { contentDescription = (if (english) "Sticker " else "Стикер ") + attachment.name }
                        .testTag("message.sticker." + attachment.id)
                        .widthIn(min = 64.dp).heightIn(min = 64.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(attachment.name, fontSize = 54.sp) }
                AttachmentKind.IMAGE -> AttachmentThumbnail(
                    attachment,
                    Modifier.clickable { actions.onAttachment(attachment) }
                        .semantics { contentDescription = (if (english) "Photo " else "Фото ") + attachment.name }
                        .testTag("message.photo." + attachment.id),
                )
                AttachmentKind.VIDEO -> Box(
                    Modifier.clickable { actions.onAttachment(attachment) }
                        .semantics { contentDescription = (if (english) "Video " else "Видео ") + attachment.name }
                        .testTag("message.video." + attachment.id),
                    contentAlignment = Alignment.Center,
                ) {
                    AttachmentThumbnail(attachment)
                    Icon(Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(46.dp))
                }
                else -> AttachmentFileCard(
                    attachment, english,
                    Modifier.clickable { actions.onAttachment(attachment) }.testTag("message.attachment." + attachment.id),
                    scale = fontScale.coerceIn(0.9f, 1.25f),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Ответ Honer AI.

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    streaming: Boolean,
    typing: Boolean,
    status: String?,
    pacer: TypingPacer,
    isLatest: Boolean,
    findQuery: String,
    english: Boolean,
    fontScale: Float,
    actions: MessageActions,
) {
    val colors = HonerTheme.colors
    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(findQuery) {
        if (findQuery.isNotBlank() && message.reasoning.contains(findQuery.trim(), ignoreCase = true)) reasoningOpen = true
    }
    val visibleContent = remember(message.content) {
        if (ParentalControl.enabled) ParentalControl.filterOutput(message.content) else message.content
    }
    val steps = message.activity.orEmpty()
    val onAnswer = actions.onAnswer
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (typing) {
            LiveAssistantBody(pacer, message, streaming, status, english, fontScale, findQuery, reasoningOpen,
                { reasoningOpen = !reasoningOpen }, isLatest, actions)
        } else {
            if (message.reasoning.isNotEmpty() || streaming || steps.isNotEmpty()) {
                val title = when {
                    streaming && visibleContent.isEmpty() -> {
                        val base = status?.let { StatusText.localized(it, english) } ?: if (english) "Thinking…" else "Размышляю…"
                        if (message.reasoningSeconds > 0) base + " " + TimeText.secondsText(message.reasoningSeconds, english) else base
                    }
                    message.reasoning.isEmpty() && steps.isNotEmpty() -> StatusText.localized(ActivityInfo.summary(steps), english)
                    else -> TimeText.finishedReasoningTitle(message.reasoningSeconds, message.reasoningWasTranslated == true, english)
                }
                ReasoningDisclosure(title, reasoningOpen, busy = streaming && visibleContent.isEmpty(), message.id, english, fontScale) {
                    reasoningOpen = !reasoningOpen
                }
                AnimatedVisibility(reasoningOpen && (message.reasoning.isNotEmpty() || steps.isNotEmpty()),
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ReasoningDetail(message, message.reasoning, steps, english, fontScale, findQuery, actions)
                }
            }
            if (visibleContent.isNotEmpty()) {
                MarkdownContent(
                    text = visibleContent,
                    modifier = Modifier.fillMaxWidth().testTag("message.content." + message.id),
                    fontScale = fontScale,
                    streaming = false,
                    sources = message.sources,
                    messageId = message.id,
                    isLatest = isLatest,
                    findQuery = findQuery,
                    onAnswer = onAnswer,
                )
            }
            MessageAttachments(message.attachments, english, fontScale, actions)
            val tables = message.tableIDs
            if (!tables.isNullOrEmpty()) ChatTableCards(tables, fontScale.coerceIn(0.9f, 1.25f))
        }
        message.error?.let { error ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("message.error." + message.id)) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp).padding(top = 1.dp))
                Text(error, fontSize = (14 * fontScale).sp, color = Color(0xFFFF9F0A))
            }
            RetryLink(english, message.id, 14f) { actions.onRetry(message.id) }
        }
        if (message.continuesInBackground == true) {
            LabelRow(Icons.Rounded.Downloading,
                if (english) "Finishing in the background — you'll get a notification" else "Ответ дописывается в фоне — придёт уведомление",
                fontScale, "message.background." + message.id)
        }
        if (message.isInterrupted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (english) "Response stopped" else "Ответ остановлен", fontSize = (12 * fontScale).sp, color = colors.secondary,
                    modifier = Modifier.testTag("message.stopped." + message.id))
                // Остановленный ответ без текста не должен быть тупиком: даём повторить.
                if (visibleContent.isBlank() && !streaming) RetryLink(english, message.id, 13f) { actions.onRetry(message.id) }
            }
        }
        if (message.searchFailed && message.role == MessageRole.ASSISTANT) {
            LabelRow(Icons.Rounded.WifiOff,
                if (english) "No fresh web data: pages did not open, answered from Honer AI knowledge"
                else "Без свежих данных из интернета: страницы не открылись, ответ по знаниям Honer AI",
                fontScale, "message.search.failed." + message.id)
        }
        if (message.sources.isNotEmpty()) SourcesCard(message, english, actions)
        if (!streaming && !typing && message.content.isNotEmpty()) ActionRow(message, english, actions)
    }
}

@Composable
private fun LabelRow(icon: ImageVector, text: String, fontScale: Float, tag: String) {
    val colors = HonerTheme.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(tag)) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(15.dp).padding(top = 1.dp))
        Text(text, fontSize = (12 * fontScale).sp, color = colors.secondary)
    }
}

@Composable
private fun RetryLink(english: Boolean, id: String, size: Float, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).heightIn(min = 36.dp)
            .padding(vertical = 4.dp).testTag("message.action.retry.$id"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Rounded.Refresh, null, tint = colors.accent, modifier = Modifier.size((size + 2).dp))
        Text(if (english) "Try again" else "Повторить запрос", fontSize = size.sp, fontWeight = FontWeight.Medium, color = colors.accent)
    }
}

/** Карточка «Источники ответа»: сколько страниц прочитано, значки сайтов и названия. */
@Composable
private fun SourcesCard(message: ChatMessage, english: Boolean, actions: MessageActions) {
    val colors = HonerTheme.colors
    val summary = remember(message.sources) { SourceText.summary(message, english) }
    val titles = remember(message.sources) { SourceText.titles(message) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(0.7.dp, colors.divider, shape)
            .clickable { actions.onSources(SourceSelection(message.sources, readOnly = false)) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics {
                contentDescription = if (english) "Answer sources, $summary. Tap to open the pages."
                else "Источники ответа, $summary. Нажмите, чтобы открыть страницы."
            }
            .testTag("message.sources." + message.id),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Link, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(if (english) "Answer sources" else "Источники ответа", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = colors.foreground)
            Spacer(Modifier.weight(1f))
            Text(summary, fontSize = 12.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceSiteMarks(message.sources)
            Text(titles, fontSize = 12.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
        }
        Text(if (english) "Tap to open the pages and quotes I read" else "Нажмите, чтобы открыть страницы и цитаты, которые я прочитал",
            fontSize = 11.sp, color = colors.secondary.copy(alpha = 0.9f))
    }
}

/** Значки первых сайтов-источников, чуть наложенные друг на друга. */
@Composable
fun SourceSiteMarks(sources: List<WebSource>, size: Float = 19f) {
    Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
        sources.take(4).forEach { source -> SourceSiteIcon(source.url, size) }
    }
}

@Composable
fun SourceSiteIcon(url: String, size: Float = 19f) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val host = remember(url) { MediaLinks.displayHost(url) }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(colors.raised).border(1.dp, colors.background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(host.take(1).uppercase().ifEmpty { "W" }, fontSize = (size * 0.53f).sp, fontWeight = FontWeight.SemiBold, color = colors.secondary)
        AsyncImage(model = MediaLinks.favicon(url, 64), imageLoader = HonerImages.loader(context), contentDescription = null,
            modifier = Modifier.size(size.dp).padding((size / 6).dp).clip(CircleShape))
    }
}

/** Реакция, копировать, нравится/не нравится, читать вслух, поделиться, повторить. */
@Composable
private fun ActionRow(message: ChatMessage, english: Boolean, actions: MessageActions) {
    val colors = HonerTheme.colors
    var reactionsOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable { reactionsOpen = true }
                    .semantics { contentDescription = if (english) "Reaction" else "Реакция" }
                    .testTag("message.reaction." + message.id),
                contentAlignment = Alignment.Center,
            ) {
                Text(message.reaction ?: "☺︎", fontSize = if (message.reaction == null) 16.sp else 18.sp,
                    color = if (message.reaction == null) colors.secondary else Color.Unspecified)
            }
            DropdownMenu(
                expanded = reactionsOpen,
                onDismissRequest = { reactionsOpen = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = colors.sidebar,
            ) {
                ReactionEmojis.chunked(4).forEach { row ->
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        row.forEach { emoji ->
                            Box(
                                Modifier.size(46.dp).clip(CircleShape)
                                    .background(if (message.reaction == emoji) colors.accent.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        reactionsOpen = false
                                        actions.onReaction(message.id, if (message.reaction == emoji) null else emoji)
                                    }
                                    .testTag("message.reaction.option.$emoji"),
                                contentAlignment = Alignment.Center,
                            ) { Text(emoji, fontSize = 24.sp) }
                        }
                    }
                }
                if (message.reaction != null) {
                    DropdownMenuItem(
                        text = { Text(if (english) "Remove reaction" else "Убрать реакцию", color = Color(0xFFFF453A)) },
                        onClick = { reactionsOpen = false; actions.onReaction(message.id, null) },
                    )
                }
            }
        }
        HonerActionButton(Icons.Rounded.ContentCopy, if (english) "Copy" else "Копировать", { actions.onCopy(message.content) },
            Modifier.testTag("message.action.copy." + message.id))
        val liked = message.feedback == MessageFeedback.LIKE
        val disliked = message.feedback == MessageFeedback.DISLIKE
        HonerActionButton(if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, if (english) "Like" else "Нравится",
            { actions.onFeedback(message.id, if (liked) null else MessageFeedback.LIKE) },
            Modifier.testTag("message.action.like." + message.id), selected = liked)
        HonerActionButton(if (disliked) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, if (english) "Dislike" else "Не нравится",
            { actions.onFeedback(message.id, if (disliked) null else MessageFeedback.DISLIKE) },
            Modifier.testTag("message.action.dislike." + message.id), selected = disliked)
        HonerActionButton(Icons.AutoMirrored.Rounded.VolumeUp, if (english) "Read aloud" else "Читать вслух",
            { actions.onSpeak(message.content) }, Modifier.testTag("message.action.speak." + message.id))
        HonerActionButton(Icons.Rounded.IosShare, if (english) "Share" else "Поделиться", { actions.onShare(message.content) },
            Modifier.testTag("message.action.share." + message.id))
        Spacer(Modifier.weight(1f))
        if (message.error == null) {
            HonerActionButton(Icons.Rounded.Refresh, if (english) "Retry" else "Повторить", { actions.onRetry(message.id) },
                Modifier.offset(x = 15.dp).testTag("message.action.retry." + message.id))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Рассуждение и живой ответ.

/** Кнопка «Размышлял N секунд»: открывает и сворачивает рассуждение. */
@Composable
private fun ReasoningDisclosure(title: String, open: Boolean, busy: Boolean, messageId: String, english: Boolean,
                                fontScale: Float, onToggle: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = title + ", " + if (english) (if (open) "Expanded" else "Collapsed") else (if (open) "Развёрнуто" else "Свёрнуто")
            }
            .testTag("message.reasoning.$messageId"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = colors.secondary, strokeWidth = 1.8.dp)
        } else {
            Icon(Icons.Rounded.AutoAwesome, null, tint = colors.secondary, modifier = Modifier.size(15.dp))
        }
        Text(title, fontSize = (17 * fontScale).sp, fontWeight = FontWeight.Medium, color = colors.secondary)
        Icon(if (open) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            tint = colors.secondary, modifier = Modifier.size(16.dp))
    }
}

/** Развёрнутое рассуждение: шаги, пометки о переводе, сам текст и прочитанные источники. */
@Composable
private fun ReasoningDetail(
    message: ChatMessage,
    reasoning: String,
    steps: List<GenerationStep>,
    english: Boolean,
    fontScale: Float,
    findQuery: String,
    actions: MessageActions,
) {
    val colors = HonerTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (steps.isNotEmpty()) {
            ActivityTimeline(steps, english, 14 * fontScale, Modifier.padding(bottom = if (reasoning.isEmpty()) 0.dp else 6.dp))
        }
        if (message.reasoningWasTranslated == true) {
            Text(if (english) "Translated into Russian" else "Переведено на русский", fontSize = 11.sp, color = colors.secondary,
                modifier = Modifier.testTag("message.reasoning.translated." + message.id))
        } else if (message.reasoningStayedForeign) {
            Text(if (english) "Translation unavailable, showing the model's own wording" else "Перевод недоступен, показан текст на языке модели",
                fontSize = 11.sp, color = colors.secondary, modifier = Modifier.testTag("message.reasoning.foreign." + message.id))
        }
        if (reasoning.isNotEmpty()) {
            val bar = colors.divider
            Box(Modifier.fillMaxWidth().drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }.padding(start = 13.dp)) {
                CompositionLocalProvider(LocalContentColor provides colors.secondary) {
                    MarkdownContent(
                        text = reasoning,
                        modifier = Modifier.fillMaxWidth().testTag("message.reasoning.text." + message.id),
                        fontScale = fontScale * 16f / 21f,
                        findQuery = findQuery,
                    )
                }
            }
        }
        if (message.sources.isNotEmpty()) {
            val read = message.sources.count { it.content != null }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SourceLine(Icons.Rounded.Search,
                    if (english) "Found ${message.sources.size} web pages" else "Найдено ${message.sources.size} веб-страниц",
                    "message.sources.found." + message.id, message.sources) {
                    actions.onSources(SourceSelection(message.sources, readOnly = false))
                }
                SourceLine(Icons.AutoMirrored.Rounded.Article, if (english) "Read $read pages" else "Прочитано $read страниц",
                    "message.sources.read." + message.id, null) {
                    actions.onSources(SourceSelection(message.sources, readOnly = true))
                }
            }
        }
    }
}

@Composable
private fun SourceLine(icon: ImageVector, title: String, tag: String, sources: List<WebSource>?, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
        Text(title, fontSize = 14.sp, color = colors.secondary)
        if (sources != null) SourceSiteMarks(sources)
    }
}

/**
 * Ответ, который печатается прямо сейчас. Только это представление следит за печатью
 * ([TypingPacer]): каждый кадр перерисовывается одна строка чата, а не весь список.
 */
@Composable
private fun LiveAssistantBody(
    pacer: TypingPacer,
    message: ChatMessage,
    streaming: Boolean,
    status: String?,
    english: Boolean,
    fontScale: Float,
    findQuery: String,
    reasoningOpen: Boolean,
    onToggleReasoning: () -> Unit,
    isLatest: Boolean,
    actions: MessageActions,
) {
    val content by pacer.content.collectAsState()
    val reasoning by pacer.reasoning.collectAsState()
    val steps by pacer.steps.collectAsState()
    val reasoningEndedAt by pacer.reasoningEndedAt.collectAsState()
    val waiting = content.isEmpty() && streaming
    Column(Modifier.fillMaxWidth().animateContentSize(tween(250)), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (waiting) {
            ThinkingHeader(
                status?.let { StatusText.localized(it, english) } ?: if (english) "Thinking…" else "Размышляю…",
                pacer.startedAt, english, fontScale, message.id,
            )
            if (steps.isNotEmpty()) ActivityTimeline(steps, english, 14 * fontScale, Modifier.padding(start = 2.dp), live = true)
            if (reasoning.isNotEmpty()) ThinkingPreview(reasoning, 15 * fontScale)
        } else if (reasoning.isNotEmpty() || steps.isNotEmpty()) {
            val title = if (reasoning.isEmpty()) {
                StatusText.localized(ActivityInfo.summary(steps), english)
            } else {
                var seconds = message.reasoningSeconds
                if (seconds <= 0) seconds = (((reasoningEndedAt ?: System.currentTimeMillis()) - pacer.startedAt) / 1000.0).let { Math.round(it).toInt() }
                TimeText.finishedReasoningTitle(seconds, message.reasoningWasTranslated == true, english)
            }
            ReasoningDisclosure(title, reasoningOpen, busy = false, message.id, english, fontScale, onToggleReasoning)
            if (reasoningOpen) ReasoningDetail(message, reasoning, steps, english, fontScale, findQuery, actions)
        }
        if (content.isNotEmpty()) {
            MarkdownContent(
                text = content,
                modifier = Modifier.fillMaxWidth().testTag("message.content." + message.id),
                fontScale = fontScale,
                streaming = true,
                sources = message.sources,
                messageId = message.id,
                isLatest = isLatest,
                findQuery = findQuery,
                onAnswer = actions.onAnswer,
            )
        }
    }
}

/**
 * Заголовок, пока ответа ещё нет: индикатор и живой счётчик секунд. Счётчик идёт
 * от собственного таймера, а не от кусков потока — при долгом ожидании он не замирает.
 */
@Composable
private fun ThinkingHeader(status: String, startedAt: Long, english: Boolean, fontScale: Float, messageId: String) {
    val colors = HonerTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - (now - startedAt).mod(1000L))
        }
    }
    val seconds = ((now - startedAt) / 1000).toInt().coerceAtLeast(0)
    Row(
        Modifier.heightIn(min = 30.dp).testTag("message.reasoning.$messageId"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(15.dp), color = colors.secondary, strokeWidth = 1.8.dp)
        Text(
            if (seconds > 0) status + " " + TimeText.secondsText(seconds, english) else status,
            fontSize = (17 * fontScale).sp, fontWeight = FontWeight.Medium, color = colors.secondary,
        )
    }
}

/**
 * Живой ход мысли: последние абзацы рассуждения в невысоком окошке. Низ всегда
 * на виду, верх мягко растворяется; абзац не режется посередине.
 */
@Composable
private fun ThinkingPreview(text: String, fontSize: Float) {
    val colors = HonerTheme.colors
    val paragraphs = remember(text) { thinkingParagraphs(text) }
    val long = text.length > 220
    val bar = colors.divider
    Box(Modifier.fillMaxWidth().heightIn(max = 136.dp).drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }) {
        Box(
            Modifier
                .padding(start = 13.dp)
                .fillMaxWidth()
                .heightIn(max = 136.dp)
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (long) {
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black, 1f to Color.Black),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                paragraphs.forEach { (id, paragraph) ->
                    androidx.compose.runtime.key(id) {
                        Text(paragraph, fontSize = fontSize.sp, lineHeight = (fontSize + 5).sp, color = colors.secondary)
                    }
                }
            }
        }
    }
}

/** Последние абзацы рассуждения (не больше ~900 символов); номер абзаца — его место в тексте. */
internal fun thinkingParagraphs(text: String): List<Pair<Int, String>> {
    val start = maxOf(0, text.length - 3000)
    val picked = ArrayList<Pair<Int, String>>()
    var total = 0
    var end = text.length
    while (end > start) {
        val lineStart = text.lastIndexOf('\n', end - 1).let { if (it < start) start else it + 1 }
        val part = text.substring(lineStart, end)
        end = if (lineStart <= start) start else lineStart - 1
        val cleaned = part.replace("**", "").replace("`", "").trim()
        if (cleaned.isEmpty()) continue
        picked.add(0, lineStart to cleaned)
        total += cleaned.length
        if (total >= 900) break
    }
    return picked
}
