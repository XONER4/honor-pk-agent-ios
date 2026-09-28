package com.honerai.app.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Сколько строк кода подсвечиваются одним куском: готовые куски не пересчитываются при печати. */
private const val CODE_CHUNK_LINES = 40

/**
 * Блок кода: язык, номера строк, режим изменений, запуск JavaScript, тема подсветки,
 * копирование и «Поделиться». Длинный код сворачивается.
 */
@Composable
internal fun CodeBlockView(text: String, language: String, fontSize: Float, english: Boolean) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var showLineNumbers by remember { mutableStateOf(false) }
    var useLightTheme by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var runResult by remember { mutableStateOf<CodeRunner.Result?>(null) }

    val lines = remember(text) { text.split('\n') }
    val isLong = lines.size > 24
    val visible = if (isLong && !expanded) lines.subList(0, 18) else lines
    val theme = if (useLightTheme) SyntaxHighlighter.light else SyntaxHighlighter.dark
    val codeSize = fontSize * 0.82f
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = codeSize.sp,
        lineHeight = (codeSize * 1.45f).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (useLightTheme) Color.White else colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().background(colors.raised).padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.ifEmpty { tr(english, "код", "code") }, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.secondary)
            Spacer(Modifier.weight(1f))
            ToolbarIcon(Icons.AutoMirrored.Outlined.FormatListBulleted, tr(english, "Номера строк", "Line numbers")) {
                showLineNumbers = !showLineNumbers
            }
            ToolbarIcon(Icons.Outlined.Difference, tr(english, "Режим изменений", "Diff mode"), tint = if (showDiff) colors.accent else colors.secondary, tag = "message.code.diff") {
                showDiff = !showDiff
            }
            if (CodeRunner.canRun(language)) {
                ToolbarIcon(Icons.Outlined.PlayCircle, tr(english, "Запустить код", "Run code"), tint = if (running) colors.accent else colors.secondary, tag = "message.code.run") {
                    if (!running) {
                        running = true
                        scope.launch {
                            runResult = CodeRunner.run(context, text, language, english)
                            running = false
                        }
                    }
                }
            }
            ToolbarIcon(if (useLightTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, tr(english, "Тема подсветки", "Highlight theme")) {
                useLightTheme = !useLightTheme
            }
            ToolbarIcon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, tr(english, "Копировать код", "Copy code"), tint = if (copied) colors.accent else colors.secondary, tag = "message.code.copy") {
                RenderActions.copy(context, text)
                copied = true
            }
            ToolbarIcon(Icons.Outlined.Share, tr(english, "Поделиться кодом", "Share code")) {
                RenderActions.shareText(context, text, tr(english, "Поделиться кодом", "Share code"))
            }
        }

        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (showDiff) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    visible.forEach { line ->
                        val trimmed = line.trimWs()
                        val highlighted = remember(line, language, theme) {
                            SyntaxHighlighter.highlight(line.ifEmpty { " " }, language, theme)
                        }
                        Text(
                            highlighted, style = codeStyle, softWrap = false,
                            modifier = Modifier.background(diffBackground(trimmed, colors.accent)).padding(horizontal = 12.dp),
                        )
                    }
                }
            } else {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showLineNumbers) {
                        val numbers = remember(visible.size) { (1..visible.size).joinToString("\n") }
                        Text(numbers, style = codeStyle, color = colors.secondary, softWrap = false)
                    }
                    Column {
                        // Код рисуется кусками по 40 строк: при печати растёт только последний
                        // кусок, готовые не подсвечиваются заново и не перерисовываются.
                        val chunkCount = (visible.size + CODE_CHUNK_LINES - 1) / CODE_CHUNK_LINES
                        for (chunk in 0 until chunkCount) {
                            key(chunk) {
                                val from = chunk * CODE_CHUNK_LINES
                                val until = minOf(visible.size, from + CODE_CHUNK_LINES)
                                val chunkText = visible.subList(from, until).joinToString("\n")
                                CodeChunk(chunkText, language, theme, codeStyle)
                            }
                        }
                    }
                }
            }
        }
        if (isLong && !expanded) {
            Row(
                Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
                    .clickable { expanded = true }
                    .testTag("message.code.expand"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Text(
                    tr(english, "Показать весь код (${lines.size} строк)", "Show all code (${lines.size} lines)"),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.accent,
                )
            }
        }
        runResult?.let { result ->
            val tint = if (result.isError) Color(0xFFFF9F0A) else colors.secondary
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.raised.copy(alpha = 0.7f))
                    .padding(10.dp)
                    .testTag("message.code.output"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (result.isError) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle, null, tint = tint, modifier = Modifier.size(13.dp))
                    Text(
                        if (result.isError) tr(english, "Ошибка выполнения", "Runtime error") else tr(english, "Результат", "Result"),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint,
                    )
                    Spacer(Modifier.weight(1f))
                    ToolbarIcon(Icons.Outlined.ContentCopy, tr(english, "Копировать результат", "Copy result"), size = 30) {
                        RenderActions.copy(context, result.output)
                    }
                    ToolbarIcon(Icons.Outlined.Close, tr(english, "Скрыть результат", "Hide result"), size = 30) { runResult = null }
                }
                Text(
                    result.output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSize * 0.78f).sp,
                    color = if (result.isError) Color(0xFFFF9F0A) else colors.foreground,
                )
            }
        }
    }
}

/** Кусок подсвеченного кода: подсветка пересчитывается, только если кусок изменился. */
@Composable
private fun CodeChunk(chunk: String, language: String, theme: SyntaxHighlighter.Theme, style: TextStyle) {
    val highlighted = remember(chunk, language, theme) { SyntaxHighlighter.highlight(chunk, language, theme) }
    Text(highlighted, style = style, softWrap = false)
}

private fun diffBackground(line: String, accent: Color): Color = when {
    line.startsWith("+") && !line.startsWith("+++") -> Color(0x2934C759)
    line.startsWith("-") && !line.startsWith("---") -> Color(0x29FF3B30)
    line.startsWith("@@") -> accent.copy(alpha = 0.12f)
    else -> Color.Transparent
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    description: String,
    tint: Color = HonerTheme.colors.secondary,
    tag: String? = null,
    size: Int = 38,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = size.dp, height = if (size >= 38) 34.dp else size.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(if (size >= 38) 18.dp else 15.dp))
    }
}

/** Блок ```copy — фрагмент, который копируется одной кнопкой. */
@Composable
internal fun CopyBlockView(text: String, fontSize: Float, english: Boolean) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4f).sp, color = colors.foreground)
        val tint = if (copied) colors.accent else colors.secondary
        Row(
            Modifier
                .clip(CircleShape)
                .border(BorderStroke(0.7.dp, colors.divider), CircleShape)
                .clickable { RenderActions.copy(context, text); copied = true }
                .defaultMinSize(minHeight = 30.dp)
                .padding(horizontal = 12.dp)
                .testTag("message.copy.block"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                if (copied) tr(english, "Скопировано", "Copied") else tr(english, "Копировать", "Copy"),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint,
            )
        }
    }
}

/** Блок ```card:info|success|warn|error|quote — цветная карточка с полосой слева. */
@Composable
internal fun CardBlockView(style: String, text: String, fontSize: Float, context: MarkdownRenderContext) {
    val colors = HonerTheme.colors
    val normalized = style.lowercase()
    val accent = when (normalized) {
        "success", "ok", "успех" -> Color(0xFF3DC773)
        "warn", "warning", "предупреждение" -> Color(0xFFFFB83D)
        "error", "danger", "ошибка" -> Color(0xFFFF5C5C)
        "quote", "цитата" -> colors.secondary
        else -> colors.accent
    }
    val icon = when (normalized) {
        "success", "ok", "успех" -> Icons.Filled.CheckCircle
        "warn", "warning", "предупреждение" -> Icons.Filled.Warning
        "error", "danger", "ошибка" -> Icons.Filled.Error
        "quote", "цитата" -> Icons.Outlined.FormatQuote
        else -> Icons.Filled.Info
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(0.7.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .height(IntrinsicSize.Min)
            .testTag("message.card.$normalized"),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
            InlineText(text, fontSize, FontWeight.Normal, context)
        }
    }
}
