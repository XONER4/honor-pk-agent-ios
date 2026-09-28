package com.honerai.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush as GradientBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.media.CropAspect
import com.honerai.app.media.EditBackground
import com.honerai.app.media.EditColors
import com.honerai.app.media.EditFilter
import com.honerai.app.media.TextStyle
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/** Поставить содержимое центром в точку (x, y) внутри родителя. */
internal fun Modifier.centerAt(x: () -> Float, y: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place((x() - placeable.width / 2f).roundToInt(), (y() - placeable.height / 2f).roundToInt())
    }
}

/** Фоторедактор (порт PhotoEditorView): фильтры, настройки, обрезка, фон, текст, стикеры, рисование. */
@Composable
internal fun PhotoEditor(imageFile: File, onSave: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val language by container.settings.language.collectAsState()
    val english = language == "en"
    val t: (String, String) -> String = { ru, en -> if (english) en else ru }
    val scope = rememberCoroutineScope()
    val model = remember(imageFile) { PhotoEditorModel(context.applicationContext, container.isLowEndDevice) }
    model.english = english
    val colors = HonerTheme.colors
    var tool by remember { mutableStateOf(PhotoTool.FILTERS) }
    var comparing by remember { mutableStateOf(false) }
    val currentOnSave by rememberUpdatedState(onSave)

    LaunchedEffect(model) { model.load(imageFile) }
    LaunchedEffect(model) { model.runPreviewLoop() }
    LaunchedEffect(model) { model.runThumbnailLoop() }
    DisposableEffect(model) { onDispose { model.dispose() } }
    BackHandler { onCancel() }

    fun select(item: PhotoTool) {
        if (item == tool) return
        val leaving = tool
        scope.launch {
            model.flush(leaving)
            tool = item
        }
    }

    fun save() {
        scope.launch { model.exportFinal()?.let { currentOnSave(it) } }
    }

    Box(Modifier.fillMaxSize().background(colors.background).testTag("editor.photo")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                EditorTopBar(
                    title = t("Редактор", "Editor"), cancel = t("Отмена", "Cancel"), done = t("Готово", "Done"),
                    doneEnabled = model.base != null && !model.isWorking, onCancel = onCancel, onDone = ::save,
                )
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        CanvasArea(model, tool, comparing, { comparing = it }, t, Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(380.dp).fillMaxHeight().background(colors.surface)) {
                            ToolPanel(model, tool, t, Modifier.weight(1f))
                            ToolTabs(tool, t, ::select)
                        }
                    }
                } else {
                    CanvasArea(model, tool, comparing, { comparing = it }, t, Modifier.weight(1f).fillMaxWidth())
                    ToolPanel(model, tool, t, Modifier.height(186.dp))
                    ToolTabs(tool, t, ::select)
                }
            }
        }
        EditorBusyOverlay(model.busyMessage)
    }

    model.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { model.errorMessage = null },
            confirmButton = { TextButton(onClick = { model.errorMessage = null }) { Text("OK") } },
            title = { Text(t("Ошибка", "Error")) },
            text = { Text(message) },
        )
    }
}

@Composable
private fun CanvasArea(
    model: PhotoEditorModel, tool: PhotoTool, comparing: Boolean, setComparing: (Boolean) -> Unit,
    t: (String, String) -> String, modifier: Modifier,
) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    Box(modifier) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            val image = if (comparing) model.originalPreview ?: model.preview else model.preview
            when {
                image != null -> {
                    val fitted = EditorGeometry.fitted(image.width, image.height, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    val density = LocalDensity.current
                    val width = with(density) { fitted.width.toDp() }
                    val height = with(density) { fitted.height.toDp() }
                    Box(
                        Modifier.size(width, height)
                            .shadow(10.dp, RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        if (model.previewHasTransparency && !comparing) EditorCheckerboard(Modifier.fillMaxSize())
                        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        if (!comparing) {
                            StickerLayer(model, fitted)
                            if (tool == PhotoTool.TEXT) TextOverlay(model, fitted)
                            if (tool == PhotoTool.DRAW) DrawingLayer(model, fitted)
                            if (tool == PhotoTool.CROP && model.cropRect != null) CropOverlay(model, fitted)
                        }
                    }
                }
                model.loadFailed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Photo, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(40.dp))
                    Text(t("Не удалось открыть фото", "Could not open the photo"), color = colors.secondary, fontSize = 14.sp)
                }
                else -> CircularProgressIndicator(color = colors.accent)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val undoEnabled = model.canUndo && !model.isWorking
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.raised)
                    .clickable(enabled = undoEnabled) { scope.launch { model.undo() } }
                    .semantics { contentDescription = t("Отменить", "Undo") }
                    .testTag("editor.undo"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null,
                    tint = colors.foreground.copy(alpha = if (model.canUndo) 1f else 0.4f), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            // Сравнить: пока палец на кнопке, показывается исходное фото.
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(50))
                    .background(if (comparing) colors.accent else colors.raised)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            setComparing(true)
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            setComparing(false)
                        }
                    }
                    .padding(horizontal = 16.dp)
                    .testTag("editor.compare"),
                contentAlignment = Alignment.Center,
            ) {
                Text(t("Сравнить", "Compare"), color = if (comparing) Color.White else colors.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Надпись в выбранном стиле (как EditorStyledCaption). */
@Composable
internal fun StyledCaption(text: String, color: Int, style: TextStyle, pointSizePx: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontSize = with(density) { pointSizePx.toSp() }
    val base = ComposeTextStyle(fontSize = fontSize, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = fontSize * 1.15f)
    when (style) {
        TextStyle.PLAIN -> Text(
            text, modifier = modifier,
            style = base.copy(color = Color(color), shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, pointSizePx * 0.04f), pointSizePx * 0.12f)),
        )
        TextStyle.OUTLINE -> Box(modifier) {
            Text(text, style = base.copy(color = Color(EditColors.contrasting(color)),
                drawStyle = Stroke(width = pointSizePx * 0.08f, join = StrokeJoin.Round)))
            Text(text, style = base.copy(color = Color(color)))
        }
        TextStyle.BANNER -> {
            val corner = with(density) { (pointSizePx * 0.3f).toDp() }
            Text(
                text, style = base.copy(color = Color(EditColors.contrasting(color))),
                modifier = modifier.background(Color(color), RoundedCornerShape(corner))
                    .padding(horizontal = with(density) { (pointSizePx * 0.45f).toDp() }, vertical = with(density) { (pointSizePx * 0.2f).toDp() }),
            )
        }
        TextStyle.NEON -> Text(
            text, modifier = modifier,
            style = base.copy(color = Color(EditColors.neonCore(color)), shadow = Shadow(Color(color), Offset.Zero, pointSizePx * 0.5f)),
        )
    }
}

@Composable
private fun TextOverlay(model: PhotoEditorModel, size: Size) {
    val text = model.pendingText
    if (text.isEmpty()) return
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        StyledCaption(
            text, model.textColor, model.textStyle, max(6f, model.textSize * size.width),
            Modifier
                .centerAt({ model.textPosition.x * size.width }, { model.textPosition.y * size.height })
                .widthIn(max = with(density) { (size.width * 0.92f).toDp() })
                .pointerInput(size) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val p = model.textPosition
                        model.textPosition = Offset(
                            (p.x + drag.x / max(size.width, 1f)).coerceIn(0f, 1f),
                            (p.y + drag.y / max(size.height, 1f)).coerceIn(0f, 1f),
                        )
                    }
                },
        )
    }
}

@Composable
private fun StickerLayer(model: PhotoEditorModel, size: Size) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        for (sticker in model.stickers) {
            key(sticker.id) {
                val fontSize = with(density) { max(8f, size.width * 0.18f * sticker.scale).toSp() }
                Text(
                    sticker.emoji, fontSize = fontSize,
                    modifier = Modifier
                        .centerAt({ sticker.position.x * size.width }, { sticker.position.y * size.height })
                        .pointerInput(sticker.id, size) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val p = sticker.position
                                model.moveSticker(sticker, Offset(p.x + pan.x / max(size.width, 1f), p.y + pan.y / max(size.height, 1f)))
                                if (zoom != 1f) model.scaleSticker(sticker, sticker.scale * zoom)
                            }
                        }
                        .pointerInput(sticker.id) { detectTapGestures(onDoubleTap = { model.removeSticker(sticker) }) }
                        .semantics { contentDescription = sticker.emoji },
                )
            }
        }
    }
}

@Composable
private fun DrawingLayer(model: PhotoEditorModel, size: Size) {
    val density = LocalDensity.current
    Canvas(
        Modifier.fillMaxSize().pointerInput(size) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = max(size.width, 1f)
                val h = max(size.height, 1f)
                fun normalized(p: Offset) = Offset((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
                val widthPx = with(density) { model.drawWidth.dp.toPx() }
                if (model.erasing) {
                    val radius = max(widthPx, with(density) { 14.dp.toPx() }) / w
                    model.erase(normalized(down.position), radius, h / w)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        model.erase(normalized(change.position), radius, h / w)
                        change.consume()
                    }
                } else {
                    val stroke = DrawStroke(model.drawColor, widthPx / w)
                    stroke.points.add(normalized(down.position))
                    model.strokes.add(stroke)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        stroke.points.add(normalized(change.position))
                        change.consume()
                    }
                }
            }
        },
    ) {
        for (stroke in model.strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            val path = Path()
            path.moveTo(points[0].x * this.size.width, points[0].y * this.size.height)
            if (points.size == 1) path.lineTo(points[0].x * this.size.width + 0.01f, points[0].y * this.size.height)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                path.quadraticTo(a.x * this.size.width, a.y * this.size.height,
                    (a.x + b.x) / 2 * this.size.width, (a.y + b.y) / 2 * this.size.height)
            }
            path.lineTo(points.last().x * this.size.width, points.last().y * this.size.height)
            drawPath(path, Color(stroke.color),
                style = Stroke(width = stroke.widthFraction * this.size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Свободная обрезка: рамка с углами, двигается и меняет размер. */
@Composable
private fun CropOverlay(model: PhotoEditorModel, size: Size) {
    val density = LocalDensity.current
    val grab = with(density) { 32.dp.toPx() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(size) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val rect = model.cropRect ?: return@awaitEachGesture
                val w = max(size.width, 1f)
                val h = max(size.height, 1f)
                val corners = listOf(Offset(rect.left * w, rect.top * h), Offset(rect.right * w, rect.top * h),
                    Offset(rect.left * w, rect.bottom * h), Offset(rect.right * w, rect.bottom * h))
                val corner = corners.indices.minByOrNull { (corners[it] - down.position).getDistance() }
                    ?.takeIf { (corners[it] - down.position).getDistance() < grab } ?: -1
                val inside = rect.contains(Offset(down.position.x / w, down.position.y / h))
                if (corner < 0 && !inside) return@awaitEachGesture
                down.consume()
                val minSide = 0.06f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.position - change.previousPosition
                    val dx = delta.x / w
                    val dy = delta.y / h
                    val r = model.cropRect ?: break
                    model.cropRect = when (corner) {
                        0 -> Rect((r.left + dx).coerceIn(0f, r.right - minSide), (r.top + dy).coerceIn(0f, r.bottom - minSide), r.right, r.bottom)
                        1 -> Rect(r.left, (r.top + dy).coerceIn(0f, r.bottom - minSide), (r.right + dx).coerceIn(r.left + minSide, 1f), r.bottom)
                        2 -> Rect((r.left + dx).coerceIn(0f, r.right - minSide), r.top, r.right, (r.bottom + dy).coerceIn(r.top + minSide, 1f))
                        3 -> Rect(r.left, r.top, (r.right + dx).coerceIn(r.left + minSide, 1f), (r.bottom + dy).coerceIn(r.top + minSide, 1f))
                        else -> {
                            val mx = dx.coerceIn(-r.left, 1f - r.right)
                            val my = dy.coerceIn(-r.top, 1f - r.bottom)
                            r.translate(mx, my)
                        }
                    }
                    change.consume()
                }
            }
        },
    ) {
        val r = model.cropRect ?: return@Canvas
        val left = r.left * this.size.width
        val top = r.top * this.size.height
        val right = r.right * this.size.width
        val bottom = r.bottom * this.size.height
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset.Zero, Size(this.size.width, top))
        drawRect(dim, Offset(0f, bottom), Size(this.size.width, this.size.height - bottom))
        drawRect(dim, Offset(0f, top), Size(left, bottom - top))
        drawRect(dim, Offset(right, top), Size(this.size.width - right, bottom - top))
        val stroke = 2.dp.toPx()
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(stroke))
        // Сетка третей.
        val thin = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            val x = left + (right - left) * i / 3
            val y = top + (bottom - top) * i / 3
            drawLine(thin, Offset(x, top), Offset(x, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            drawLine(thin, Offset(left, y), Offset(right, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        val handle = 18.dp.toPx()
        val thick = 4.dp.toPx()
        for ((cx, cy) in listOf(left to top, right to top, left to bottom, right to bottom)) {
            val sx = if (cx == left) 1f else -1f
            val sy = if (cy == top) 1f else -1f
            drawLine(Color.White, Offset(cx, cy), Offset(cx + sx * handle, cy), thick, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + sy * handle), thick, cap = StrokeCap.Round)
        }
    }
}

private fun toolIcon(tool: PhotoTool) = when (tool) {
    PhotoTool.FILTERS -> Icons.Filled.Filter
    PhotoTool.ADJUST -> Icons.Filled.Tune
    PhotoTool.CROP -> Icons.Filled.CropRotate
    PhotoTool.BACKGROUND -> Icons.Filled.AutoFixHigh
    PhotoTool.TEXT -> Icons.Filled.TextFields
    PhotoTool.STICKERS -> Icons.Filled.EmojiEmotions
    PhotoTool.DRAW -> Icons.Filled.Brush
}

@Composable
private fun ToolTabs(tool: PhotoTool, t: (String, String) -> String, onSelect: (PhotoTool) -> Unit) {
    val colors = HonerTheme.colors
    Box(Modifier.fillMaxWidth().background(colors.background)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
        Row(
            Modifier.fillMaxWidth().height(60.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (item in PhotoTool.entries) {
                val selected = item == tool
                Column(
                    Modifier.widthIn(min = 58.dp).height(52.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(item) }
                        .padding(horizontal = 6.dp)
                        .testTag("editor.tab." + item.raw),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val tint = if (selected) colors.accent else colors.secondary
                    Icon(toolIcon(item), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(t(item.russian, item.english), color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ToolPanel(model: PhotoEditorModel, tool: PhotoTool, t: (String, String) -> String, modifier: Modifier) {
    val colors = HonerTheme.colors
    AnimatedContent(
        targetState = tool,
        transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith fadeOut() },
        modifier = modifier.fillMaxWidth().background(colors.surface),
        label = "toolPanel",
    ) { current ->
        Box(Modifier.fillMaxSize()) {
            when (current) {
                PhotoTool.FILTERS -> FiltersPanel(model, t)
                PhotoTool.ADJUST -> AdjustPanel(model, t)
                PhotoTool.CROP -> CropPanel(model, t)
                PhotoTool.BACKGROUND -> BackgroundPanel(model, t)
                PhotoTool.TEXT -> TextPanel(model, t)
                PhotoTool.STICKERS -> StickersPanel(model, t)
                PhotoTool.DRAW -> DrawPanel(model, t)
            }
        }
    }
}

@Composable
private fun FiltersPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(EditFilter.entries, key = { it.raw }) { filter ->
                val selected = model.look.filter == filter
                val scale by animateFloatAsState(if (selected) 1.05f else 1f, label = "filterScale")
                Column(
                    Modifier.width(70.dp).clickable { model.selectFilter(filter) }.testTag("editor.filter." + filter.raw),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(64.dp).scale(scale).clip(RoundedCornerShape(12.dp)).background(colors.raised)
                            .border(if (selected) 2.5.dp else 0.dp, if (selected) colors.accent else Color.Transparent, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = model.thumbnails[filter]
                        if (thumb != null) {
                            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                        }
                    }
                    Text(t(filter.title, filter.englishTitle), fontSize = 11.sp, maxLines = 1,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) colors.accent else colors.secondary)
                }
            }
        }
        if (model.look.filter != EditFilter.ORIGINAL) {
            EditorSliderRow(
                t("Сила", "Strength"), model.look.intensity.toFloat(), 0f..1f,
                onChange = { model.look = model.look.copy(intensity = it.toDouble()) },
                onBegin = { model.pushUndo() },
            )
        }
    }
}

@Composable
private fun AdjustPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    val look = model.look
    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { model.resetAdjustments() }, enabled = look.hasAdjustments, modifier = Modifier.testTag("editor.adjust.reset")) {
                Text(t("Сбросить", "Reset"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (look.hasAdjustments) colors.accent else colors.secondary)
            }
        }
        EditorSliderRow(t("Яркость", "Brightness"), look.brightness.toFloat(), -1f..1f,
            onChange = { model.look = model.look.copy(brightness = it.toDouble()) }, onBegin = { model.pushUndo() })
        EditorSliderRow(t("Контраст", "Contrast"), look.contrast.toFloat(), -1f..1f,
            onChange = { model.look = model.look.copy(contrast = it.toDouble()) }, onBegin = { model.pushUndo() })
        EditorSliderRow(t("Насыщенность", "Saturation"), look.saturation.toFloat(), -1f..1f,
            onChange = { model.look = model.look.copy(saturation = it.toDouble()) }, onBegin = { model.pushUndo() })
        EditorSliderRow(t("Теплота", "Warmth"), look.warmth.toFloat(), -1f..1f,
            onChange = { model.look = model.look.copy(warmth = it.toDouble()) }, onBegin = { model.pushUndo() })
    }
}

@Composable
private fun CropPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val aspects = listOf(CropAspect.SQUARE, CropAspect.PORTRAIT_4X5, CropAspect.STORY_9X16, CropAspect.LANDSCAPE_16X9)
    Column(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (model.cropRect != null) {
            Text(t("Потяните рамку или её углы", "Drag the frame or its corners"), color = colors.secondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EditorChip(t("Отмена", "Cancel"), null, false, Modifier.weight(1f)) { model.cropRect = null }
                EditorChip(t("Обрезать", "Crop"), Icons.Filled.Check, true, Modifier.weight(1f).testTag("editor.crop.apply")) {
                    scope.launch { model.applyFreeCrop() }
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (aspect in aspects) {
                val ratio = aspect.ratio?.toFloat() ?: 1f
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(colors.raised)
                        .clickable { scope.launch { model.crop(aspect) } }
                        .padding(vertical = 8.dp)
                        .testTag("editor.crop." + aspect.raw),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (ratio >= 1) 26.dp else 26.dp * ratio, if (ratio >= 1) 26.dp / ratio else 26.dp)
                            .border(2.dp, colors.foreground, RoundedCornerShape(4.dp)))
                    }
                    Text(aspect.label, color = colors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(t(aspect.title, aspect.englishTitle), color = colors.secondary, fontSize = 10.sp, maxLines = 1)
                }
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(colors.raised)
                    .clickable { model.startFreeCrop() }
                    .padding(vertical = 8.dp)
                    .testTag("editor.crop.free"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Crop, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(30.dp))
                Text("⤢", color = colors.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(t("Свободно", "Free"), color = colors.secondary, fontSize = 10.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorChip(t("Влево", "Left"), Icons.AutoMirrored.Filled.RotateLeft, false, Modifier.weight(1f).testTag("editor.crop.rotateLeft")) {
                scope.launch { model.rotate(false) }
            }
            EditorChip(t("Вправо", "Right"), Icons.AutoMirrored.Filled.RotateRight, false, Modifier.weight(1f).testTag("editor.crop.rotateRight")) {
                scope.launch { model.rotate(true) }
            }
            EditorChip(t("Зеркало", "Mirror"), Icons.Filled.Flip, false, Modifier.weight(1f).testTag("editor.crop.flipHorizontal")) {
                scope.launch { model.flip(false) }
            }
            EditorChip(t("Вверх-вниз", "Flip"), Icons.Filled.Flip, false, Modifier.weight(1f).testTag("editor.crop.flipVertical")) {
                scope.launch { model.flip(true) }
            }
        }
    }
}

@Composable
private fun BackgroundPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { model.applyPictureBackground(uri) }
    }
    Column(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(50))
                .background(colors.accent)
                .clickable(enabled = !model.isWorking) { scope.launch { model.removeBackground() } }
                .testTag("editor.removeBackground"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (model.hasCutout) Icons.Filled.CheckCircle else Icons.Filled.AutoFixHigh, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(if (model.hasCutout) t("Фон убран", "Background removed") else t("Убрать фон", "Remove background"),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(t("Новый фон", "New background"), color = colors.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp))
        val option: @Composable (String, String, () -> Unit, @Composable () -> Unit) -> Unit = { tag, label, onClick, content ->
            Box(
                Modifier.size(46.dp).clip(CircleShape).border(1.dp, colors.divider, CircleShape)
                    .clickable(enabled = !model.isWorking, onClick = onClick)
                    .semantics { contentDescription = label }
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                option("editor.background.transparent", t("Прозрачный", "Transparent"), { scope.launch { model.applyBackground(null) } }) {
                    EditorCheckerboard(Modifier.fillMaxSize())
                }
            }
            itemsIndexed(EditorPalette.colors) { index, color ->
                option("editor.background.color.$index", t("Цвет", "Color") + " ${index + 1}",
                    { scope.launch { model.applyBackground(EditBackground.Color(color)) } }) {
                    Box(Modifier.fillMaxSize().background(Color(color)))
                }
            }
            item {
                option("editor.background.blur", t("Размытие", "Blur"), { scope.launch { model.applyBackground(EditBackground.Blur(30.0)) } }) {
                    Box(Modifier.fillMaxSize().background(GradientBrush.verticalGradient(listOf(colors.raised, colors.secondary))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = Color.White)
                    }
                }
            }
            itemsIndexed(EditorPalette.gradients) { index, pair ->
                option("editor.background.gradient.$index", t("Градиент", "Gradient") + " ${index + 1}",
                    { scope.launch { model.applyBackground(EditBackground.Gradient(pair)) } }) {
                    Box(Modifier.fillMaxSize().background(GradientBrush.linearGradient(pair.map { Color(it) })))
                }
            }
            item {
                option("editor.background.photo", t("Своё фото", "Your photo"), {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Box(Modifier.fillMaxSize().background(colors.raised), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = colors.foreground)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = model.textDraft, onValueChange = { model.textDraft = it },
                placeholder = { Text(t("Введите текст", "Type text"), fontSize = 15.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                keyboardActions = KeyboardActions(onDone = { scope.launch { model.commitText() } }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.raised, unfocusedContainerColor = colors.raised,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = colors.foreground, unfocusedTextColor = colors.foreground, cursorColor = colors.accent,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).height(52.dp).testTag("editor.text.field"),
            )
            val enabled = model.pendingText.isNotEmpty()
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = if (enabled) 1f else 0.4f))
                    .clickable(enabled = enabled) { scope.launch { model.commitText() } }
                    .semantics { contentDescription = t("Добавить на фото", "Add to photo") }
                    .testTag("editor.text.apply"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
        }
        EditorColorRow(model.textColorIndex, { model.textColorIndex = it }, t("Цвет", "Color"))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp).testTag("editor.text.style")) {
            TextStyle.entries.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = model.textStyle == style, onClick = { model.textStyle = style },
                    shape = SegmentedButtonDefaults.itemShape(index, TextStyle.entries.size),
                    icon = {},
                ) { Text(t(style.title, style.englishTitle), fontSize = 12.sp, maxLines = 1) }
            }
        }
        EditorSliderRow(t("Размер", "Size"), model.textSize, 0.03f..0.2f, onChange = { model.textSize = it })
    }
}

@Composable
private fun StickersPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Двигайте и масштабируйте пальцами, двойное касание — удалить", "Drag and pinch to adjust, double-tap to remove"),
                color = colors.secondary, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
            if (model.stickers.isNotEmpty()) {
                TextButton(onClick = { model.clearStickers() }) {
                    Text(t("Очистить", "Clear"), color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(EditorPalette.emojis, key = { it }) { emoji ->
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).clickable { model.addSticker(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 26.sp) }
            }
        }
    }
}

@Composable
private fun DrawPanel(model: PhotoEditorModel, t: (String, String) -> String) {
    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EditorChip(t("Кисть", "Pen"), Icons.Filled.Brush, !model.erasing, Modifier.testTag("editor.draw.pen")) { model.erasing = false }
            EditorChip(t("Ластик", "Eraser"), Icons.Filled.AutoFixOff, model.erasing, Modifier.testTag("editor.draw.eraser")) { model.erasing = true }
            Spacer(Modifier.weight(1f))
            EditorChip(t("Очистить", "Clear"), Icons.Filled.Delete, false, Modifier.testTag("editor.draw.clear")) { model.clearDrawing() }
        }
        EditorColorRow(model.drawColorIndex, { model.drawColorIndex = it }, t("Цвет", "Color"))
        EditorSliderRow(t("Толщина", "Width"), model.drawWidth, 2f..40f, onChange = { model.drawWidth = it },
            format = { it.roundToInt().toString() })
    }
}
