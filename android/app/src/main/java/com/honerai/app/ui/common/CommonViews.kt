package com.honerai.app.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import com.honerai.app.device.ParentalControl
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Меньше анимаций: слабый телефон или выбор пользователя. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Масштаб шрифта чата из настроек (0.85…1.4). */
val LocalChatFontScale = staticCompositionLocalOf { 1f }

// ---------------------------------------------------------------------------------------------
// Тактильный отклик — как UIImpactFeedbackGenerator на iPhone.

class Haptics(private val view: View) {
    fun light() { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    fun medium() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun selection() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun success() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

// ---------------------------------------------------------------------------------------------
// Картинки: один загрузчик Coil на приложение — с кадрами видео и GIF.

object HonerImages {
    @Volatile private var instance: ImageLoader? = null

    fun loader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components {
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
            .also { instance = it }
    }
}

// ---------------------------------------------------------------------------------------------
// Системные действия: копировать, поделиться, открыть ссылку.

fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Honer AI", text))
}

private fun Context.launch(intent: Intent) {
    if (this !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { }
}

fun shareText(context: Context, text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.launch(Intent.createChooser(send, title))
}

/** Ссылка на файл приложения для других приложений (FileProvider из манифеста). */
fun fileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

fun mimeType(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

fun shareFile(context: Context, file: File, title: String? = null) {
    val uri = runCatching { fileUri(context, file) }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).setType(mimeType(file.name)).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.launch(Intent.createChooser(send, title))
}

/** Открыть файл во внешнем приложении (просмотр документов, которых нет в Honer AI). */
fun openFileExternally(context: Context, file: File): Boolean {
    val uri = runCatching { fileUri(context, file) }.getOrNull() ?: return false
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(file.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        if (context !is android.app.Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view); true
    } catch (_: ActivityNotFoundException) { false }
}

/** Открыть ссылку в браузере (если родительский контроль разрешает). */
fun openUrl(context: Context, url: String): Boolean {
    if (!ParentalControl.canOpenLinks || !ParentalControl.isUrlAllowed(url)) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    context.launch(Intent(Intent.ACTION_VIEW, uri))
    return true
}

fun openAppSettings(context: Context) {
    context.launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)))
}

// ---------------------------------------------------------------------------------------------
// Всплывающая подсказка над полем ввода.

@Stable
class ToastState(private val scope: CoroutineScope) {
    var message by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun show(text: String) {
        job?.cancel()
        message = text
        job = scope.launch {
            delay(1800)
            message = null
        }
    }
}

@Composable
fun rememberToastState(): ToastState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ToastState(scope) }
}

@Composable
fun ToastBubble(text: String, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colors.foreground,
        modifier = modifier
            .clip(CircleShape)
            .background(colors.raised.copy(alpha = 0.96f))
            .border(0.7.dp, colors.divider, CircleShape)
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag("chat.toast"),
    )
}

// ---------------------------------------------------------------------------------------------
// Знаки Honer AI.

/** Фирменный знак: две ленты крест-накрест (как HonorMark на iPhone). */
@Composable
fun HonerMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).semantics { contentDescription = "Honer AI" }) {
        val w = this.size.width
        val h = this.size.height
        val ribbon = Path().apply {
            moveTo(w * 0.06f, h * 0.04f)
            lineTo(w * 0.33f, h * 0.04f)
            lineTo(w * 0.94f, h * 0.96f)
            lineTo(w * 0.67f, h * 0.96f)
            close()
        }
        scale(scaleX = -1f, scaleY = 1f) {
            drawPath(ribbon, Brush.verticalGradient(listOf(Color(0xFFDBF0FF), Color(0xFF7A96F0))))
        }
        drawPath(ribbon, Brush.linearGradient(listOf(Color(0xFF47B0FF), Color(0xFF386EFA)),
            start = Offset.Zero, end = Offset(w, h)))
    }
}

/** Круглое облачко с хвостиком и плюсом — значок «Новый чат». */
@Composable
fun NewConversationSymbol(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(25.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.65.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val radius = w * 0.405f
        val center = Offset(w / 2, h * 0.46f)
        val end = 112.0 * Math.PI / 180
        val arcEnd = Offset(center.x + radius * cos(end).toFloat(), center.y + radius * sin(end).toFloat())
        val control = Offset(w * 0.22f, h * 0.77f)
        val target = Offset(w * 0.12f, h * 0.89f)
        val path = Path().apply {
            arcTo(androidx.compose.ui.geometry.Rect(center, radius), 139f, 333f, true)
            // Квадратичная кривая хвостика через эквивалентную кубическую.
            val c1 = arcEnd + (control - arcEnd) * (2f / 3f)
            val c2 = target + (control - target) * (2f / 3f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, target.x, target.y)
        }
        drawPath(path, color, style = stroke)
        val plus = w * 0.2f
        val cx = center.x + 0.5.dp.toPx()
        val cy = center.y - 0.5.dp.toPx()
        val line = 1.9.dp.toPx()
        drawLine(color, Offset(cx - plus, cy), Offset(cx + plus, cy), line, StrokeCap.Round)
        drawLine(color, Offset(cx, cy - plus), Offset(cx, cy + plus), line, StrokeCap.Round)
    }
}

/** Три полоски убывающей длины — кнопка панели чатов. */
@Composable
fun SidebarSymbol(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val line = 1.8.dp.toPx()
        val ys = listOf(size.height * 0.25f, size.height * 0.5f, size.height * 0.75f)
        val widths = listOf(1f, 0.64f, 0.3f)
        for (i in 0..2) {
            val half = w * widths[i] / 2
            drawLine(color, Offset(w / 2 - half, ys[i]), Offset(w / 2 + half, ys[i]), line, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Кнопки.

@Composable
fun HonerCircleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 42.dp,
    icon: @Composable () -> Unit,
) {
    val colors = HonerTheme.colors
    Box(
        modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(colors.surface.copy(alpha = 0.65f))
                .border(0.7.dp, colors.divider, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

@Composable
fun HonerActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = HonerTheme.colors
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) colors.accent else colors.secondary,
            modifier = Modifier.size(20.dp))
    }
}

/** Процитированный фрагмент: полоска слева и текст в две-три строки. */
@Composable
fun QuoteChip(text: String, modifier: Modifier = Modifier, scale: Float = 1f) {
    val colors = HonerTheme.colors
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.raised.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("message.quote"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent))
        Text(text, fontSize = (14 * scale).sp, color = colors.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------------------------
// Окна: нижняя панель с заголовком и слой на весь экран.

/**
 * Нижняя панель в стиле iOS-листа: заголовок, «Готово» справа, необязательные кнопки слева.
 * Закрытие кнопкой анимируется, системная «Назад» закрывает панель.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HonerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    doneLabel: String? = null,
    doneTag: String? = null,
    fullHeight: Boolean = true,
    leading: (@Composable (close: () -> Unit) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val colors = HonerTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = colors.background,
        contentColor = colors.foreground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.divider) },
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { leading?.invoke(close) }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                if (doneLabel != null) {
                    TextButton(onClick = close, modifier = if (doneTag != null) Modifier.testTag(doneTag) else Modifier) {
                        Text(doneLabel, color = colors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        content(close)
    }
}

/** Слой на весь экран поверх чата (настройки, игры, редакторы); «Назад» закрывает его. */
@Composable
fun FullScreenLayer(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = HonerTheme.colors.background,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) fadeIn() else fadeIn() + slideInVertically { it / 10 },
        exit = if (reduce) fadeOut() else fadeOut() + slideOutVertically { it / 10 },
    ) {
        BackHandler(onBack = onBack)
        Box(
            modifier
                .fillMaxSize()
                .background(background)
                // Касания не проходят к чату под слоем.
                .pointerInput(Unit) { detectTapGestures { } },
            content = content,
        )
    }
}

/** Пустое состояние списка: значок и подпись. */
@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 15.sp, color = colors.secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Кнопка-«плюс» для заголовков листов. */
@Composable
fun AddIconButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.material3.ripple(bounded = false), onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Add, null, tint = HonerTheme.colors.accent) }
}

/** Сегментированный переключатель в стиле iOS (вкладки, язык, автор). */
@Composable
fun HonerSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(colors.raised.copy(alpha = 0.8f))
            .padding(2.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        options.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) (if (colors.isDark) Color(0xFF5A5A5E) else Color.White) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .semantics { this.selected = active }
                    .then(if (tag != null) Modifier.testTag("$tag.$index") else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
