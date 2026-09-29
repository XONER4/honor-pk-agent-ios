package com.honerai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.data.WebSource
import com.honerai.app.ui.questions.QuestionsCard
import com.honerai.app.ui.theme.HonerTheme

/** Общие данные отрисовки одного ответа. Пересоздаётся только при смене темы, языка, поиска или источников. */
@Stable
internal class MarkdownRenderContext(
    val palette: InlinePalette,
    val sources: List<WebSource>,
    val sourcesKey: String,
    val findQuery: String,
    val english: Boolean,
    val onCitation: (number: String, url: String) -> Unit,
)

/** Сколько последних символов печатаемого текста проявляются плавно. */
private const val FADE_LENGTH = 18

/** Базовый размер текста ответа (sp) при масштабе шрифта 1. */
private const val BASE_FONT_SIZE = 17f

/**
 * Ответ нейросети с разметкой: заголовки, списки, таблицы, код, цитаты, карточки,
 * блоки вопросов ```ask, формулы, картинки и видео. [streaming] — текст ещё печатается.
 * [messageId] и [isLatest] нужны карточкам вопросов (таймер идёт только у последнего ответа).
 * [onAnswer] — ответ на вопрос уходит в чат; false — отправить сейчас нельзя.
 *
 * Пока ответ печатается, недописанная разметка в хвосте скрывается (LiveMarkdown),
 * разбирается только хвост (MarkdownParseMemo), а неизменившиеся блоки не перерисовываются:
 * у каждого блока устойчивый ключ и неизменяемая модель.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    streaming: Boolean = false,
    sources: List<WebSource> = emptyList(),
    messageId: String? = null,
    isLatest: Boolean = false,
    findQuery: String = "",
    onAnswer: (String) -> Boolean = { false },
) {
    val colors = HonerTheme.colors
    val english = rememberEnglish()
    val context = LocalContext.current
    val container = remember(context) { AppContainer.get(context) }
    val reduceMotion by container.settings.reduceMotion.collectAsState()
    val memo = remember { MarkdownParseMemo() }
    val shown = if (streaming) LiveMarkdown.displayable(text) else text
    val blocks = memo.blocks(shown, streaming)

    var citation by remember { mutableStateOf<Pair<String, String>?>(null) }
    val palette = remember(colors) { InlinePalette(colors.foreground, colors.secondary, colors.accent, colors.raised) }
    val renderContext = remember(palette, sources, findQuery, english) {
        MarkdownRenderContext(palette, sources, InlineMarkup.sourcesKey(sources), findQuery, english) { number, url ->
            citation = number to url
        }
    }
    val fontSize = BASE_FONT_SIZE * fontScale
    val lastId = blocks.lastOrNull()?.id ?: -1
    val fadeLength = if (streaming && !reduceMotion && !container.isLowEndDevice) FADE_LENGTH else 0
    // Устойчивая ссылка на обработчик: карточки вопросов не перерисовываются из-за новой лямбды.
    val answerState = rememberUpdatedState(onAnswer)
    val answer = remember { { value: String -> answerState.value(value) } }
    // Блоки идут пачками по 32: неизменившаяся пачка пропускается целиком одной проверкой,
    // поэтому на кадре печати длинного ответа Compose не обходит сотни блоков.
    val chunks = remember(blocks) { blocks.chunked(CHUNK_SIZE) { BlockChunk(it.toList()) } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chunks.forEachIndexed { index, chunk ->
            key(index) {
                BlockChunkView(
                    chunk = chunk,
                    fontSize = fontSize,
                    context = renderContext,
                    fadeId = if (index == chunks.lastIndex) lastId else -1,
                    fadeLength = fadeLength,
                    messageId = messageId,
                    isLatest = isLatest,
                    onAnswer = answer,
                )
            }
        }
    }

    citation?.let { (number, url) ->
        CitationDialog(number, url, sources, english) { citation = null }
    }
}

/** Пачка соседних блоков. Сравнивается по значению: у неизменившихся блоков те же объекты. */
@Immutable
internal data class BlockChunk(val blocks: List<MarkdownBlock>)

private const val CHUNK_SIZE = 32

@Composable
private fun BlockChunkView(
    chunk: BlockChunk,
    fontSize: Float,
    context: MarkdownRenderContext,
    fadeId: Int,
    fadeLength: Int,
    messageId: String?,
    isLatest: Boolean,
    onAnswer: (String) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (block in chunk.blocks) {
            key(block.id) {
                MarkdownBlockView(
                    block = block,
                    fontSize = fontSize,
                    context = context,
                    fade = if (block.id == fadeId) fadeLength else 0,
                    messageId = messageId,
                    isLatest = isLatest,
                    onAnswer = onAnswer,
                )
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    fontSize: Float,
    context: MarkdownRenderContext,
    fade: Int,
    messageId: String?,
    isLatest: Boolean,
    onAnswer: (String) -> Boolean,
) {
    val colors = HonerTheme.colors
    when (val kind = block.kind) {
        is BlockKind.Heading -> InlineContent(
            text = block.text,
            fontSize = fontSize * headingScale(kind.level),
            weight = if (kind.level <= 2) FontWeight.SemiBold else FontWeight.Medium,
            context = context,
            fade = fade,
            modifier = Modifier.padding(top = if (kind.level <= 2) 8.dp else 4.dp).semantics { heading() },
        )
        BlockKind.Paragraph -> InlineContent(block.text, fontSize, FontWeight.Normal, context, fade)
        is BlockKind.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            kind.items.forEachIndexed { position, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        text = if (kind.ordered) "${position + 1}." else "•",
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.4f).sp,
                        fontWeight = if (kind.ordered) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (kind.ordered) colors.accent else colors.secondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = if (kind.ordered) 22.dp else 12.dp),
                    )
                    InlineContent(
                        item, fontSize, FontWeight.Normal, context,
                        fade = if (position == kind.items.size - 1) fade else 0,
                    )
                }
            }
        }
        is BlockKind.Checklist -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val iconSize = with(LocalDensity.current) { (fontSize * 1.1f).sp.toDp() }
            kind.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(
                        if (item.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (item.done) colors.accent else colors.secondary,
                        modifier = Modifier.padding(top = 2.dp).size(iconSize),
                    )
                    InlineText(
                        item.text, fontSize, FontWeight.Normal, context,
                        color = if (item.done) colors.secondary else colors.foreground,
                    )
                }
            }
        }
        BlockKind.Quote -> Row(
            Modifier.height(IntrinsicSize.Min).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent.copy(alpha = 0.55f)))
            InlineText(block.text, fontSize * 0.96f, FontWeight.Normal, context, fade = fade, color = colors.secondary)
        }
        // media: блок ```app — кнопка «Открыть <приложение>».
        is BlockKind.Code -> if (kind.language.trim().equals("app", true)) AppActionCard(block.text, fontSize, context.english)
            else CodeBlockView(block.text, kind.language, fontSize, context.english)
        BlockKind.CopyBlock -> CopyBlockView(block.text, fontSize, context.english)
        is BlockKind.Card -> CardBlockView(kind.style, block.text, fontSize, context)
        is BlockKind.Ask -> QuestionsCard(
            questions = kind.questions,
            rawBody = block.text,
            blockId = block.id,
            fontSize = fontSize,
            stillStreaming = kind.open,
            messageId = messageId,
            isLatest = isLatest,
            onAnswer = onAnswer,
        )
        is BlockKind.MathBlock -> MathBlockView(kind.expression, fontSize * 1.1f)
        is BlockKind.Diagram -> MermaidDiagramView(kind.source, fontSize)
        is BlockKind.Table -> {
            // Таблица без заголовков и строк не рисуется: осталась бы одна панель фильтра.
            if (kind.rows.isNotEmpty() || kind.headers.any { it.isNotBlank() }) {
                MarkdownTableView(kind.headers, kind.alignments, kind.rows, fontSize, context)
            }
        }
        BlockKind.Divider -> Box(
            Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(colors.divider)
        )
    }
}

private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.36f
    2 -> 1.22f
    3 -> 1.1f
    4 -> 1.04f
    else -> 1f
}

/** Абзац с картинками и видео внутри: `![подпись](ссылка)` рисуется картинкой. */
@Composable
internal fun InlineContent(
    text: String,
    fontSize: Float,
    weight: FontWeight,
    context: MarkdownRenderContext,
    fade: Int = 0,
    modifier: Modifier = Modifier,
) {
    val parts = remember(text) { ParagraphParts.split(text) }
    if (parts.size == 1 && parts[0].imageUrl == null) {
        InlineText(parts[0].text ?: "", fontSize, weight, context, fade = fade, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            key(part.id) {
                val url = part.imageUrl
                when {
                    // media: картинка, видео или аудио с кнопками «Скачать»/«Поделиться» и подписью на языке интерфейса.
                    url != null -> WebMediaCard(url, part.caption, fontSize, context.english)
                    else -> InlineText(
                        part.text ?: "", fontSize, weight, context,
                        fade = if (index == parts.size - 1) fade else 0,
                    )
                }
            }
        }
    }
}

/**
 * Текст со строчной разметкой. Разбор берётся из кэша; нажатия на источники и
 * спойлеры обрабатываются здесь; хвост печати проявляется плавно.
 */
@Composable
internal fun InlineText(
    text: String,
    fontSize: Float,
    weight: FontWeight,
    context: MarkdownRenderContext,
    fade: Int = 0,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    val base = remember(text, context.palette, context.findQuery, context.sourcesKey, fade > 0) {
        InlineMarkup.cached(text, context.palette, context.sources, context.sourcesKey, context.findQuery, store = fade == 0)
    }
    var revealed by remember { mutableStateOf(emptySet<Int>()) }
    val onCitation by rememberUpdatedState(context.onCitation)
    val listener = remember {
        LinkInteractionListener { link ->
            val tag = (link as? LinkAnnotation.Clickable)?.tag ?: return@LinkInteractionListener
            when {
                tag.startsWith(InlineMarkup.TAG_SPOILER + ":") ->
                    tag.substringAfter(':').toIntOrNull()?.let { revealed = revealed + it }
                tag.startsWith(InlineMarkup.TAG_CITATION + ":") -> {
                    val payload = tag.substringAfter(':')
                    onCitation(payload.substringBefore('\u001F'), payload.substringAfter('\u001F'))
                }
            }
        }
    }
    val spoilerColor = colors.secondary.copy(alpha = 0.45f)
    val interactive = remember(base, revealed, spoilerColor, colors.accent) {
        InlineMarkup.interactive(base, revealed, spoilerColor, colors.accent, listener)
    }
    val textColor = if (color == Color.Unspecified) colors.foreground else color
    val shown = if (fade > 0) InlineMarkup.fadeTail(interactive, fade, textColor) else interactive
    Text(
        text = shown,
        modifier = modifier,
        color = textColor,
        fontSize = fontSize.sp,
        fontWeight = weight,
        lineHeight = (fontSize * 1.4f).sp,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Источник по нажатию на сноску [1]: название, сайт и кнопки «Открыть», «Копировать ссылку». */
@Composable
private fun CitationDialog(number: String, url: String, sources: List<WebSource>, english: Boolean, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val source = sources.firstOrNull { it.url == url } ?: number.toIntOrNull()?.let { sources.getOrNull(it - 1) }
    val host = VideoLinks.host(url).ifEmpty { url }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                source?.title?.takeIf { it.isNotBlank() } ?: host,
                color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("[$number] $host", color = colors.accent, fontSize = 13.sp)
                val snippet = source?.snippet.orEmpty()
                if (snippet.isNotBlank()) {
                    Text(snippet, color = colors.secondary, fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { RenderActions.open(context, url); onDismiss() }, modifier = Modifier.testTag("citation.open")) {
                Text(tr(english, "Открыть", "Open"), color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = { RenderActions.copy(context, url); onDismiss() }) {
                Text(tr(english, "Копировать ссылку", "Copy link"), color = colors.secondary)
            }
        },
        modifier = Modifier.testTag("citation.dialog"),
    )
}

/** Выравнивание ячейки таблицы → выравнивание текста. */
internal fun TableAlignment.textAlign(): TextAlign = when (this) {
    TableAlignment.LEADING -> TextAlign.Start
    TableAlignment.CENTER -> TextAlign.Center
    TableAlignment.TRAILING -> TextAlign.End
}

internal fun TableAlignment.boxAlignment(): Alignment = when (this) {
    TableAlignment.LEADING -> Alignment.TopStart
    TableAlignment.CENTER -> Alignment.TopCenter
    TableAlignment.TRAILING -> Alignment.TopEnd
}
