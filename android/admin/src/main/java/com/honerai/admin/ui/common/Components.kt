package com.honerai.admin.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.data.Presence
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- Тактильный отклик ----------------------------------------------------------------------------------

class Haptics(private val view: View) {
    fun light() { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    fun medium() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Honer Admin", text))
}

/** Текущее время, обновляемое раз в [periodMs] — для подписей «5 мин назад». */
@Composable
fun rememberNow(periodMs: Long = 30_000): java.time.Instant {
    val now by produceState(java.time.Instant.now()) {
        while (true) {
            delay(periodMs)
            value = java.time.Instant.now()
        }
    }
    return now
}

// --- Всплывающая подсказка --------------------------------------------------------------------------------

@Stable
class ToastState(private val scope: CoroutineScope) {
    var text by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun show(message: String) {
        job?.cancel()
        text = message
        job = scope.launch { delay(2_600); text = null }
    }
}

@Composable
fun rememberToastState(): ToastState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ToastState(scope) }
}

@Composable
fun BoxScope.ToastHost(state: ToastState, bottom: Dp = 96.dp) {
    AnimatedVisibility(
        visible = state.text != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 24.dp, end = 24.dp),
    ) {
        val colors = HonerTheme.colors
        Text(
            state.text.orEmpty(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(colors.raised.copy(alpha = 0.97f))
                .border(0.7.dp, colors.divider, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// --- Шапка экрана ----------------------------------------------------------------------------------------

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = HonerTheme.colors.secondary,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = HonerTheme.colors
    Column(modifier.fillMaxWidth().background(colors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconCircle(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад", "Back"), onBack)
            } else Spacer(Modifier.width(12.dp))
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(
                Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onTitleClick) else Modifier)
                    .padding(vertical = 4.dp),
            ) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, fontSize = 13.sp, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.divider.copy(alpha = 0.6f)))
    }
}

/** Круглая кнопка-значок 44 dp (как HonerActionButton). */
@Composable
fun IconCircle(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = HonerTheme.colors.foreground, enabled: Boolean = true) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
    }
}

// --- Присутствие и аватар --------------------------------------------------------------------------------

@Composable
fun presenceColor(presence: String): Color = when (presence) {
    Presence.FOREGROUND -> HonerTheme.colors.online
    Presence.BACKGROUND -> HonerTheme.colors.away
    else -> HonerTheme.colors.secondary.copy(alpha = 0.7f)
}

/** Кружок с инициалами и точкой присутствия (зелёная — в приложении, жёлтая — в фоне, серая — не в сети). */
@Composable
fun Avatar(name: String, seed: String, presence: String?, size: Dp = 48.dp, blocked: Boolean = false) {
    val colors = HonerTheme.colors
    val palette = remember {
        listOf(Color(0xFF5B7FE0), Color(0xFF8E5BE0), Color(0xFFE05B8E), Color(0xFFE0935B), Color(0xFF3FAF8A), Color(0xFF4AA3C9), Color(0xFFB0A04A))
    }
    val bg = palette[(seed.hashCode() and 0x7fffffff) % palette.size]
    val initials = remember(name) {
        name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    }
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (blocked) colors.raised else bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
        }
        if (presence != null) {
            val dot = presenceColor(presence)
            Canvas(Modifier.size(size * 0.3f).align(Alignment.BottomEnd)) {
                drawCircle(colors.background)
                drawCircle(dot, radius = this.size.minDimension / 2 - 2.5.dp.toPx(), center = Offset(this.size.width / 2, this.size.height / 2))
            }
        }
    }
}

// --- Пилюли и карточки -----------------------------------------------------------------------------------

@Composable
fun HonerPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null) {
    val colors = HonerTheme.colors
    Row(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) colors.foreground else colors.surface)
            .border(0.7.dp, if (selected) colors.foreground else colors.divider, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) colors.background else colors.foreground, maxLines = 1)
        if (count != null && count > 0) {
            Text(count.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.background.copy(alpha = 0.7f) else colors.secondary)
        }
    }
}

/** Главная кнопка-пилюля (белая на тёмном — как кнопки Honer AI). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false,
                  icon: (@Composable () -> Unit)? = null, destructive: Boolean = false) {
    val colors = HonerTheme.colors
    val bg = when {
        destructive -> colors.danger
        else -> colors.foreground
    }
    Row(
        modifier
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = if (destructive) Color.White else colors.background, strokeWidth = 2.dp)
        } else {
            if (icon != null) { icon(); Spacer(Modifier.width(8.dp)) }
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (destructive) Color.White else colors.background,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
                    tint: Color = HonerTheme.colors.foreground, enabled: Boolean = true) {
    val colors = HonerTheme.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(0.7.dp, colors.divider, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) tint else tint.copy(alpha = 0.4f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = HonerTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(20.dp)),
    ) { content() }
}

@Composable
fun HonerField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true,
               minLines: Int = 1, keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
               visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
    val colors = HonerTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent, unfocusedBorderColor = colors.divider,
            focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
            cursorColor = colors.accent, focusedLabelColor = colors.accent, unfocusedLabelColor = colors.secondary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

// --- Состояния загрузки и ошибок ------------------------------------------------------------------------

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HonerTheme.colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
    }
}

/** Понятная ошибка и кнопка «Повторить». */
@Composable
fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, tint = colors.secondary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.foreground, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        SecondaryButton(tr("Повторить", "Retry"), onRetry)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 15.sp, color = colors.secondary, textAlign = TextAlign.Center)
    }
}

/** Подтверждение действия. [content] — необязательное поле (причина блокировки). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = HonerTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = colors.foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (text.isNotEmpty()) Text(text, color = colors.secondary, fontSize = 15.sp)
                content?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) colors.danger else colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel"), color = colors.secondary) }
        },
    )
}

/** Небольшая плашка «Соединение…», пока WebSocket переподключается. */
@Composable
fun ConnectionBanner(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val colors = HonerTheme.colors
        Row(
            Modifier.clip(CircleShape).background(colors.raised).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(12.dp), color = colors.secondary, strokeWidth = 1.5.dp)
            Text(tr("Соединение…", "Connecting…"), fontSize = 12.sp, color = colors.secondary)
        }
    }
}
