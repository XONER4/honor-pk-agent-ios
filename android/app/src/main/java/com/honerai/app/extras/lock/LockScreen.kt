package com.honerai.app.extras.lock

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.honerai.app.R
import com.honerai.app.ui.common.LocalReduceMotion
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

// Экран блокировки: логотип, точки PIN, своя цифровая клавиатура, тряска при ошибке,
// пауза 30 с после 5 ошибок и системное окно биометрии, если она включена.

private val ErrorRed = Color(0xFFFF453A)
private val WarnOrange = Color(0xFFFF9F0A)

/** Тряска по горизонтали: смещение в dp и запуск. */
@Composable
internal fun rememberLockShake(): Pair<Float, suspend () -> Unit> {
    val anim = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    val shake: suspend () -> Unit = {
        if (!reduce) {
            anim.snapTo(0f)
            anim.animateTo(1f, keyframes { durationMillis = 420 })
            anim.snapTo(0f)
        }
    }
    return (12f * sin(anim.value * Math.PI.toFloat() * 4)) to shake
}

/** Точки PIN: [total] мест, [filled] заполнено, красные при ошибке. */
@Composable
internal fun LockPinDots(total: Int, filled: Int, error: Boolean, offsetX: Float, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Row(
        modifier.offset { IntOffset((offsetX * density).roundToInt(), 0) }.semantics { contentDescription = "$filled / $total" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (i in 0 until total.coerceIn(PinHasher.MIN_LENGTH, PinHasher.MAX_LENGTH)) {
            val on = i < filled
            val color = if (error) ErrorRed else colors.accent
            val scale by animateFloatAsState(if (on) 1.15f else 1f, spring(0.55f, 600f), label = "lock.dot")
            Box(
                Modifier.size(14.dp).scale(scale).clip(CircleShape).background(if (on) color else Color.Transparent)
                    .border(1.5.dp, if (on) color else colors.secondary, CircleShape),
            )
        }
    }
}

/** Клавиша: цифра, «стереть» или значок биометрии. */
@Composable
private fun LockKey(label: String?, icon: ImageVector?, description: String, enabled: Boolean, size: Int, tag: String, onTap: () -> Unit) {
    val colors = HonerTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.6f, 800f), label = "lock.key")
    Box(
        Modifier.size(size.dp).scale(scale).clip(CircleShape)
            .background(if (label != null) colors.raised.copy(alpha = if (pressed) 1f else 0.65f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onTap)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val alpha = if (enabled) 1f else 0.35f
        if (label != null) Text(label, color = colors.foreground.copy(alpha = alpha), fontSize = (size * 0.4f).sp)
        else if (icon != null) {
            val tint = if (icon == Icons.AutoMirrored.Filled.Backspace) colors.foreground else colors.accent
            Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size((size * 0.36f).dp))
        }
    }
}

/**
 * Цифровая клавиатура 3×4: 1–9, слева внизу — биометрия (если есть), 0, «стереть».
 * [keySize] подбирается под высоту экрана.
 */
@Composable
internal fun LockKeypad(
    enabled: Boolean,
    english: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    biometricIcon: ImageVector? = null,
    onBiometric: (() -> Unit)? = null,
    keySize: Int = 74,
) {
    val haptics = LocalHapticFeedback.current
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy((keySize * 0.2f).dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy((keySize * 0.32f).dp)) {
                row.forEach { digit ->
                    LockKey(digit.toString(), null, digit.toString(), enabled, keySize, "lock.key.$digit") {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigit(digit)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy((keySize * 0.32f).dp)) {
            if (biometricIcon != null && onBiometric != null) {
                LockKey(null, biometricIcon, if (english) "Biometrics" else "Биометрия", enabled, keySize, "lock.key.biometric", onBiometric)
            } else {
                Spacer(Modifier.size(keySize.dp))
            }
            LockKey("0", null, "0", enabled, keySize, "lock.key.0") {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDigit('0')
            }
            LockKey(null, Icons.AutoMirrored.Filled.Backspace, if (english) "Delete" else "Стереть", enabled, keySize, "lock.key.delete", onDelete)
        }
    }
}

/** «0:27». */
internal fun lockCountdown(ms: Long): String {
    val seconds = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Значок биометрии по виду датчика. */
internal fun biometricIcon(kind: BiometricKind): ImageVector = when (kind) {
    BiometricKind.FACE -> Icons.Filled.Face
    else -> Icons.Filled.Fingerprint
}

/**
 * Экран блокировки поверх всего приложения. Касания до чата под ним не доходят;
 * «Назад» сворачивает приложение, а не открывает чат.
 */
@Composable
fun AppLockScreen(activity: FragmentActivity, english: Boolean) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val mode by AppLock.mode.collectAsState()
    val pinLength by AppLock.pinLength.collectAsState()
    val attempts by AppLock.attempts.collectAsState()
    val (shakeOffset, shake) = rememberLockShake()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val option = remember(mode) { if (mode == LockMode.BIOMETRIC) BiometricSupport.option(activity) else null }
    val biometricReady = option?.available == true
    fun t(ru: String, en: String) = if (english) en else ru

    val lockedOut = PinAttemptPolicy.isLocked(attempts, now)
    // Обратный отсчёт паузы обновляется раз в секунду.
    LaunchedEffect(attempts.lockedUntil) {
        while (PinAttemptPolicy.isLocked(AppLock.attempts.value, System.currentTimeMillis())) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }

    fun showBiometric() {
        if (!biometricReady) return
        BiometricSupport.authenticate(
            activity,
            title = t("Разблокировка Honer AI", "Unlock Honer AI"),
            subtitle = BiometricChoice.label(option.kind, english),
            negative = t("Ввести PIN", "Use PIN"),
            authenticators = option.authenticators,
        ) { ok -> if (ok) AppLock.unlock() }
    }

    // Биометрия показывается сама, как только экран виден, и снова после каждого возвращения из фона.
    // STARTED, а не RESUMED: окно биометрии ставит приложение на паузу — иначе оно открывалось бы по кругу.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(biometricReady) {
        if (!biometricReady) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(250)
            if (AppLock.locked.value && !PinAttemptPolicy.isLocked(AppLock.attempts.value, System.currentTimeMillis())) showBiometric()
            kotlinx.coroutines.awaitCancellation()
        }
    }

    fun submit(entered: String) {
        checking = true
        scope.launch {
            when (val result = AppLock.checkPin(entered)) {
                PinCheck.Ok -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    AppLock.unlock()
                }
                is PinCheck.Wrong -> {
                    error = t("Неверный PIN. Осталось попыток: ${result.attemptsLeft}", "Wrong PIN. Attempts left: ${result.attemptsLeft}")
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    launch { shake() }
                }
                is PinCheck.LockedOut -> {
                    error = null
                    now = System.currentTimeMillis()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    launch { shake() }
                }
            }
            pin = ""
            checking = false
        }
    }

    BackHandler { activity.moveTaskToBack(true) }

    val status = when {
        lockedOut -> t(
            "Слишком много неверных попыток.\nПовторите через ${lockCountdown(PinAttemptPolicy.remainingMs(attempts, now))}",
            "Too many wrong attempts.\nTry again in ${lockCountdown(PinAttemptPolicy.remainingMs(attempts, now))}",
        )
        error != null -> error!!
        else -> t("Введите PIN-код", "Enter your PIN")
    }
    val statusColor = when {
        lockedOut -> WarnOrange
        error != null -> ErrorRed
        else -> colors.secondary
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.background)
            // Касания не проходят к чату под экраном.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("lock.screen"),
        contentAlignment = Alignment.Center,
    ) {
        // Низкие экраны (320 dp, альбомная ориентация) — клавиши меньше, всё прокручивается.
        val keySize = when {
            maxHeight < 520.dp -> 54
            maxHeight < 640.dp -> 64
            else -> 74
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(
                painterResource(R.drawable.honer_logo), null,
                Modifier.size(if (keySize < 70) 56.dp else 72.dp).clip(RoundedCornerShape(18.dp)),
            )
            Text("Honer AI", color = colors.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LockPinDots(if (pinLength in PinHasher.MIN_LENGTH..PinHasher.MAX_LENGTH) pinLength else PinHasher.MIN_LENGTH,
                pin.length, error != null && !lockedOut, shakeOffset, Modifier.testTag("lock.dots"))
            Text(
                status, color = statusColor, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 40.dp).widthIn(max = 360.dp).testTag("lock.status"),
            )
            Spacer(Modifier.height(4.dp))
            LockKeypad(
                enabled = !lockedOut && !checking,
                english = english,
                keySize = keySize,
                onDigit = { digit ->
                    val max = if (pinLength in PinHasher.MIN_LENGTH..PinHasher.MAX_LENGTH) pinLength else PinHasher.MAX_LENGTH
                    if (pin.length < max) {
                        pin += digit
                        error = null
                        if (pin.length == max) submit(pin)
                    }
                },
                onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                biometricIcon = if (biometricReady) biometricIcon(option.kind) else null,
                onBiometric = if (biometricReady) ({ showBiometric() }) else null,
            )
        }
    }
}
