package com.honerai.app.ui.parental

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.honerai.app.device.ParentalControl
import com.honerai.app.device.ParentalMath
import com.honerai.app.ui.settings.SettingsTopBar
import com.honerai.app.ui.settings.WarningOrange
import com.honerai.app.ui.settings.DestructiveRed
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.pageBackground
import com.honerai.app.ui.settings.rememberReduceMotion
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

// Ввод PIN родителя: клавиатура, точки, тряска при ошибке, отсчёт блокировки после перебора.

internal val ParentalAccentGradient = Brush.linearGradient(listOf(Color(0xFF5C8CFF), Color(0xFF8C61FA)))
internal val ParentalMutedGradient = Brush.linearGradient(listOf(Color(0xB38E8E93), Color(0x738E8E93)))

/** Тряска по горизонтали: значение 0→1 даёт два колебания. */
@Composable
internal fun rememberShake(): Pair<Float, suspend () -> Unit> {
    val anim = remember { Animatable(0f) }
    val reduce = rememberReduceMotion()
    val shake: suspend () -> Unit = {
        if (!reduce) {
            anim.snapTo(0f)
            anim.animateTo(1f, keyframes { durationMillis = 420 })
            anim.snapTo(0f)
        }
    }
    return (10f * sin(anim.value * Math.PI.toFloat() * 4)) to shake
}

/** Большой круглый значок со значком-галочкой (герой экрана). */
@Composable
internal fun ParentalHeroIcon(icon: ImageVector, badge: ImageVector? = null) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val reduce = rememberReduceMotion()
    val progress by animateFloatAsState(if (appeared || reduce) 1f else 0f, spring(0.65f, 300f), label = "hero")
    Box(Modifier.size(100.dp).scale(0.7f + 0.3f * progress), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(96.dp).shadow(16.dp, CircleShape, ambientColor = HonerTheme.colors.accent, spotColor = HonerTheme.colors.accent)
                .clip(CircleShape).background(ParentalAccentGradient),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(38.dp).clip(CircleShape).background(HonerTheme.colors.background).padding(3.dp)
                    .clip(CircleShape).background(Color(0xFF30D158)),
                contentAlignment = Alignment.Center,
            ) { Icon(badge, null, tint = Color.White, modifier = Modifier.size(19.dp)) }
        }
    }
}

/** Точки PIN (4–8), красные при ошибке. */
@Composable
internal fun ParentalPinDots(count: Int, error: Boolean, offsetX: Float) {
    val colors = HonerTheme.colors
    val slots = count.coerceIn(4, 8)
    Row(
        Modifier.offset { IntOffset((offsetX * density).roundToInt(), 0) }.semantics { contentDescription = "$count" }.testTag("parental.pin.dots"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (i in 0 until slots) {
            val filled = i < count
            val color = if (error) DestructiveRed else colors.accent
            val scale by animateFloatAsState(if (filled) 1.12f else 1f, spring(0.6f, 500f), label = "dot")
            Box(
                Modifier.size(14.dp).scale(scale).clip(CircleShape).background(if (filled) color else Color.Transparent)
                    .border(1.5.dp, if (filled) color else colors.secondary, CircleShape),
            )
        }
    }
}

/** Цифровая клавиатура: 1–9, «ок», 0, «стереть». Ввод автоматически отправляется на 8-й цифре. */
@Composable
internal fun PinPad(pin: String, onPin: (String) -> Unit, onSubmit: () -> Unit, enabled: Boolean = true, minLength: Int = 4, maxLength: Int = 8) {
    val haptics = LocalHapticFeedback.current
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("ok", "0", "del"))
    Column(
        Modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { key ->
                    PinKey(key, canSubmit = pin.length >= minLength, enabled = enabled) {
                        when (key) {
                            "del" -> if (pin.isNotEmpty()) onPin(pin.dropLast(1))
                            "ok" -> if (pin.length >= minLength) onSubmit()
                            else -> if (pin.length < maxLength) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val next = pin + key
                                onPin(next)
                                if (next.length == maxLength) onSubmit()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(key: String, canSubmit: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val colors = HonerTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.6f, 800f), label = "key")
    val filled = key != "del" && key != "ok"
    val tag = when (key) { "del" -> "parental.pin.delete"; "ok" -> "parental.pin.ok"; else -> "parental.pin.key.$key" }
    val keyEnabled = enabled && (key != "ok" || canSubmit)
    Box(
        Modifier.size(74.dp).scale(scale).clip(CircleShape)
            .background(if (filled) colors.raised.copy(alpha = if (pressed) 1f else 0.65f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = keyEnabled, onClick = onTap)
            .semantics { contentDescription = when (key) { "del" -> "Delete"; "ok" -> "OK"; else -> key } }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        when (key) {
            "del" -> Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = colors.foreground.copy(alpha = alpha), modifier = Modifier.size(24.dp))
            "ok" -> Icon(Icons.Filled.CheckCircle, null, tint = (if (canSubmit) colors.accent else colors.secondary).copy(alpha = alpha), modifier = Modifier.size(34.dp))
            else -> Text(key, color = colors.foreground.copy(alpha = alpha), fontSize = 30.sp)
        }
    }
}

/**
 * Панель ввода PIN с точками, тряской при ошибке и обратным отсчётом при блокировке перебора.
 * [onSubmit] возвращает true, если PIN подошёл.
 */
@Composable
internal fun ParentalPinEntryPanel(title: String, subtitle: String, icon: ImageVector, onSubmit: (String) -> Boolean) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val snap by ParentalControl.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val (shakeOffset, shake) = rememberShake()
    var pin by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedUntil = snap.lockedUntilMillis
    // Отсчёт до конца блокировки обновляется раз в секунду.
    LaunchedEffect(lockedUntil) {
        while (lockedUntil != null && lockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
        if (lockedUntil != null) ParentalControl.refresh()
    }
    val locked = lockedUntil != null && lockedUntil > now
    val status = when {
        locked -> {
            val time = ParentalMath.countdownString(((lockedUntil!! - now + 999) / 1000).toInt())
            settings.text("Слишком много неверных попыток. Повторите через $time.", "Too many wrong attempts. Try again in $time.")
        }
        failed -> {
            val left = ParentalControl.remainingAttempts
            settings.text("Неверный PIN. Осталось попыток: $left.", "Wrong PIN. Attempts left: $left.")
        }
        else -> subtitle
    }
    val statusColor = when {
        locked -> WarningOrange
        failed -> DestructiveRed
        else -> colors.secondary
    }
    fun submit() {
        if (pin.isEmpty()) return
        val entered = pin
        pin = ""
        if (onSubmit(entered)) {
            failed = false
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            failed = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { shake() }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ParentalHeroIcon(icon)
        Text(title, color = colors.foreground, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        ParentalPinDots(pin.length, failed, shakeOffset)
        Text(
            status, color = statusColor, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 38.dp).widthIn(max = 360.dp).testTag("parental.pin.status"),
        )
        PinPad(pin, { pin = it; if (it.isNotEmpty()) failed = false }, { submit() }, enabled = !locked)
    }
}

/** Создание PIN: ввести дважды. При несовпадении — тряска и повтор с начала. */
@Composable
internal fun ParentalPinSetupFlow(icon: ImageVector, confirmIcon: ImageVector, onComplete: (String) -> Unit) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val (shakeOffset, shake) = rememberShake()
    var first by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }
    val title = if (confirming) settings.text("Повторите PIN", "Repeat the PIN") else settings.text("Придумайте PIN родителя", "Create a parent PIN")
    val subtitle = when {
        mismatch -> settings.text("PIN не совпал. Попробуйте ещё раз.", "PINs didn't match. Try again.")
        confirming -> settings.text("Введите тот же PIN ещё раз.", "Enter the same PIN once more.")
        else -> settings.text("От 4 до 8 цифр. Не говорите его ребёнку.", "4 to 8 digits. Don't tell it to your child.")
    }
    fun submit() {
        if (!ParentalMath.isValidPIN(pin)) return
        if (!confirming) {
            first = pin; pin = ""; mismatch = false; confirming = true
            return
        }
        if (pin == first) { onComplete(pin); return }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        pin = ""; first = ""; mismatch = true; confirming = false
        scope.launch { shake() }
    }
    Column(
        Modifier.fillMaxWidth().testTag("parental.setup"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ParentalHeroIcon(if (confirming) confirmIcon else icon)
        Text(title, color = colors.foreground, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(subtitle, color = if (mismatch) DestructiveRed else colors.secondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 36.dp))
        ParentalPinDots(pin.length, mismatch, shakeOffset)
        PinPad(pin, { pin = it }, { submit() })
    }
}

/** Полноэкранное окно с вводом PIN для подтверждения действия (выключение, сброс, открыть на сегодня). */
@Composable
internal fun ParentalPinPromptDialog(title: String, subtitle: String, icon: ImageVector, onDismiss: () -> Unit, action: (String) -> Boolean) {
    val settings = appSettings()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(pageBackground)) {
            SettingsTopBar(title = "", onBack = null, trailing = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("parental.prompt.cancel")) {
                    Text(settings.text("Отмена", "Cancel"), color = HonerTheme.colors.accent, fontSize = 16.sp)
                }
            })
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp), contentAlignment = Alignment.TopCenter) {
                ParentalPinEntryPanel(title, subtitle, icon) { pin ->
                    val ok = action(pin)
                    if (ok) onDismiss()
                    ok
                }
            }
        }
    }
}

/** Окно смены PIN (только в открытой сессии). */
@Composable
internal fun ParentalChangePinDialog(icon: ImageVector, confirmIcon: ImageVector, onDismiss: () -> Unit) {
    val settings = appSettings()
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(pageBackground)) {
            SettingsTopBar(title = settings.text("Новый PIN", "New PIN"), onBack = null, trailing = {
                TextButton(onClick = onDismiss) { Text(settings.text("Отмена", "Cancel"), color = HonerTheme.colors.accent, fontSize = 16.sp) }
            })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ParentalPinSetupFlow(icon, confirmIcon) { pin ->
                    if (ParentalControl.setPIN(pin)) onDismiss() else failed = true
                }
                if (failed) {
                    Text(
                        settings.text("Сессия истекла. Закройте окно и снова введите PIN.", "Session expired. Close this and enter the PIN again."),
                        color = DestructiveRed, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
