package com.honerai.app.extras.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import com.honerai.app.ui.settings.SettingsButtonRow
import com.honerai.app.ui.settings.SettingsCheckRow
import com.honerai.app.ui.settings.SettingsDivider
import com.honerai.app.ui.settings.SettingsGroup
import com.honerai.app.ui.settings.SettingsMenuRow
import com.honerai.app.ui.settings.SettingsPageScaffold
import com.honerai.app.ui.settings.SettingsRow
import com.honerai.app.ui.settings.SettingsToggleRow
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.rememberOnResume
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Настройки → «Блокировка приложения»: выключена / PIN / биометрия, задержка, «скрывать в недавних».

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** Что сейчас делает окно ввода PIN. */
private enum class PinFlow { SETUP, VERIFY }

/** Подпись режима для строки на главной странице настроек. */
fun appLockSummary(context: Context, english: Boolean): String = when (AppLock.mode.value) {
    LockMode.OFF -> if (english) "off" else "выключена"
    LockMode.PIN -> if (english) "PIN" else "PIN-код"
    LockMode.BIOMETRIC -> BiometricChoice.label(BiometricSupport.option(context).kind, english)
}

@Composable
fun AppLockSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    val english = settings.isEnglish
    val scope = rememberCoroutineScope()
    val mode by AppLock.mode.collectAsState()
    val delayValue by AppLock.delay.collectAsState()
    val hide by AppLock.hideInRecents.collectAsState()
    // Биометрию могли добавить в системных настройках — пересчитываем при возвращении.
    val option = rememberOnResume { BiometricSupport.option(context) }
    val label = BiometricChoice.label(option.kind, english)
    fun t(ru: String, en: String) = settings.text(ru, en)

    // Окно PIN: что делать после успешного ввода.
    var flow by remember { mutableStateOf<PinFlow?>(null) }
    var afterPin by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    fun askPin(kind: PinFlow, then: suspend () -> Unit) { afterPin = then; flow = kind }

    fun confirmBiometric(then: () -> Unit) {
        val activity = context.findFragmentActivity() ?: return
        BiometricSupport.authenticate(
            activity, t("Включить: $label", "Turn on: $label"),
            t("Подтвердите, что это вы", "Confirm it's you"), t("Отмена", "Cancel"), option.authenticators,
        ) { ok -> if (ok) then() }
    }

    fun choose(target: LockMode) {
        if (target == mode) return
        when (target) {
            LockMode.OFF -> askPin(PinFlow.VERIFY) { AppLock.disable() }
            LockMode.PIN -> if (AppLock.hasPin()) AppLock.setMode(LockMode.PIN) else askPin(PinFlow.SETUP) { AppLock.setMode(LockMode.PIN) }
            LockMode.BIOMETRIC -> {
                val enable = { confirmBiometric { AppLock.setMode(LockMode.BIOMETRIC) } }
                if (AppLock.hasPin()) enable() else askPin(PinFlow.SETUP) { AppLock.setMode(LockMode.PIN); enable() }
            }
        }
    }

    SettingsPageScaffold(t("Блокировка приложения", "App lock"), "settings.page.applock", onBack) {
        item(key = "mode") {
            SettingsGroup(
                t("Способ", "Method"),
                footer = t(
                    "Honer AI попросит PIN-код или биометрию, когда вы вернётесь в приложение. PIN хранится только на телефоне в виде хеша — его нельзя прочитать. Если забыли PIN, переустановите приложение (чаты можно сохранить резервной копией).",
                    "Honer AI asks for a PIN or biometrics when you come back to the app. The PIN is stored on the phone only, as a hash. If you forget it, reinstall the app (keep a backup of your chats).",
                ),
            ) {
                SettingsCheckRow(t("Выключена", "Off"), mode == LockMode.OFF, tag = "applock.mode.off") { choose(LockMode.OFF) }
                SettingsDivider(16)
                SettingsCheckRow(t("PIN-код", "PIN"), mode == LockMode.PIN, tag = "applock.mode.pin",
                    subtitle = t("4–6 цифр", "4–6 digits")) { choose(LockMode.PIN) }
                if (option.available) {
                    SettingsDivider(16)
                    SettingsCheckRow(label, mode == LockMode.BIOMETRIC, tag = "applock.mode.biometric",
                        subtitle = t("PIN-код остаётся запасным способом", "PIN stays as a fallback")) { choose(LockMode.BIOMETRIC) }
                } else if (option.needsEnrollment) {
                    SettingsDivider(16)
                    SettingsRow(
                        icon = biometricIcon(option.kind), title = label, tag = "applock.enroll",
                        subtitle = t("Не настроено на телефоне — нажмите, чтобы добавить", "Not set up on this phone — tap to add"),
                    ) {
                        AppLock.allowTrip(10 * 60_000L)
                        runCatching { context.startActivity(BiometricSupport.enrollIntent()) }
                    }
                }
            }
        }
        if (mode != LockMode.OFF) {
            item(key = "options") {
                SettingsGroup(
                    t("Параметры", "Options"),
                    footer = t(
                        "«Скрывать в недавних» заменяет снимок Honer AI в списке недавних приложений пустым экраном. На Android 12 и старше при этом также запрещаются снимки экрана внутри приложения.",
                        "\"Hide in recents\" replaces the Honer AI preview in recent apps with a blank screen. On Android 12 and older, screenshots inside the app are blocked too.",
                    ),
                ) {
                    SettingsMenuRow(
                        Icons.Outlined.Timer, t("Блокировать", "Lock"),
                        listOf(
                            LockDelay.IMMEDIATE to t("Сразу", "Immediately"),
                            LockDelay.ONE_MINUTE to t("Через 1 мин", "After 1 min"),
                            LockDelay.FIVE_MINUTES to t("Через 5 мин", "After 5 min"),
                            LockDelay.FIFTEEN_MINUTES to t("Через 15 мин", "After 15 min"),
                        ),
                        delayValue, { AppLock.setDelay(it) }, tag = "applock.delay",
                    )
                    SettingsDivider()
                    SettingsToggleRow(t("Скрывать в недавних", "Hide in recents"), hide, { AppLock.setHideInRecents(it) },
                        icon = Icons.Outlined.VisibilityOff, tag = "applock.hideRecents")
                    SettingsDivider()
                    SettingsButtonRow(t("Сменить PIN-код", "Change PIN"), Icons.Outlined.Password, tag = "applock.changePin") {
                        askPin(PinFlow.VERIFY) { askPin(PinFlow.SETUP) {} }
                    }
                }
            }
        }
    }

    flow?.let { current ->
        // Смена «проверка → новый PIN» начинает окно с чистого листа.
        key(current) { PinEntryDialog(
            setup = current == PinFlow.SETUP,
            english = english,
            onDismiss = { flow = null; afterPin = null },
            onDone = {
                val next = afterPin
                flow = null
                afterPin = null
                scope.launch { next?.invoke() }
            },
            onSetPin = { AppLock.setPin(it) },
        ) }
    }
}

/**
 * Полноэкранное окно PIN. [setup] — придумать и повторить новый PIN (4–6 цифр, кнопка «Готово»
 * для PIN короче 6); иначе — проверить текущий (с паузой после 5 ошибок).
 */
@Composable
private fun PinEntryDialog(
    setup: Boolean,
    english: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onSetPin: suspend (String) -> Unit,
) {
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val pinLength by AppLock.pinLength.collectAsState()
    val attempts by AppLock.attempts.collectAsState()
    val (shakeOffset, shake) = rememberLockShake()
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    fun t(ru: String, en: String) = if (english) en else ru
    val now = remember(tick, attempts) { System.currentTimeMillis() }
    val lockedOut = !setup && PinAttemptPolicy.isLocked(attempts, now)
    LaunchedEffect(lockedOut) {
        while (lockedOut && PinAttemptPolicy.isLocked(AppLock.attempts.value, System.currentTimeMillis())) { delay(500); tick++ }
        tick++
    }
    val verifyLength = if (pinLength in PinHasher.MIN_LENGTH..PinHasher.MAX_LENGTH) pinLength else PinHasher.MAX_LENGTH
    val maxLength = if (setup) first?.length ?: PinHasher.MAX_LENGTH else verifyLength

    fun fail(message: String?) {
        error = message
        pin = ""
        scope.launch { shake() }
    }

    fun submit() {
        val entered = pin
        if (setup) {
            val chosen = first
            if (chosen == null) {
                if (!PinHasher.isValidPin(entered)) return fail(t("PIN — от 4 до 6 цифр", "PIN must be 4–6 digits"))
                first = entered
                pin = ""
                error = null
            } else if (chosen != entered) {
                first = null
                fail(t("PIN-коды не совпали. Придумайте заново", "PINs didn't match. Try again"))
            } else {
                busy = true
                scope.launch { onSetPin(entered); busy = false; onDone() }
            }
        } else {
            busy = true
            scope.launch {
                when (val result = AppLock.checkPin(entered)) {
                    PinCheck.Ok -> onDone()
                    is PinCheck.Wrong -> fail(t("Неверный PIN. Осталось попыток: ${result.attemptsLeft}", "Wrong PIN. Attempts left: ${result.attemptsLeft}"))
                    is PinCheck.LockedOut -> fail(null)
                }
                busy = false
            }
        }
    }

    val title = when {
        !setup -> t("Введите текущий PIN-код", "Enter your current PIN")
        first == null -> t("Придумайте PIN-код", "Create a PIN")
        else -> t("Повторите PIN-код", "Repeat the PIN")
    }
    val status = when {
        lockedOut -> t("Слишком много неверных попыток. Повторите через ${lockCountdown(PinAttemptPolicy.remainingMs(attempts, System.currentTimeMillis()))}",
            "Too many wrong attempts. Try again in ${lockCountdown(PinAttemptPolicy.remainingMs(attempts, System.currentTimeMillis()))}")
        error != null -> error!!
        setup && first == null -> t("От 4 до 6 цифр", "4 to 6 digits")
        else -> ""
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onDismiss)
        BoxWithConstraints(
            Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing).testTag("applock.pinDialog"),
            contentAlignment = Alignment.Center,
        ) {
            val keySize = when {
                maxHeight < 520.dp -> 54
                maxHeight < 640.dp -> 64
                else -> 74
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, color = colors.foreground, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                LockPinDots(if (setup) maxOf(PinHasher.MIN_LENGTH, if (first != null) maxLength else maxOf(pin.length, PinHasher.MIN_LENGTH)) else verifyLength,
                    pin.length, error != null, shakeOffset)
                Text(
                    status, color = if (error != null || lockedOut) Color(0xFFFF453A) else colors.secondary, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 36.dp).widthIn(max = 360.dp),
                )
                LockKeypad(
                    enabled = !busy && !lockedOut,
                    english = english,
                    keySize = keySize,
                    onDigit = { digit ->
                        if (pin.length < maxLength) {
                            pin += digit
                            error = null
                            // Проверяем сразу: при вводе текущего PIN и при повторе нового длина известна.
                            if (pin.length == maxLength && (!setup || first != null || maxLength == PinHasher.MAX_LENGTH)) submit()
                        }
                    },
                    onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                )
                Spacer(Modifier.height(4.dp))
                val canConfirm = setup && first == null && pin.length >= PinHasher.MIN_LENGTH && !busy
                TextButton(onClick = { submit() }, enabled = canConfirm, modifier = Modifier.testTag("applock.pin.done")) {
                    Text(t("Готово", "Done"), color = if (canConfirm) colors.accent else Color.Transparent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("applock.pin.cancel")) {
                    Text(t("Отмена", "Cancel"), color = colors.accent, fontSize = 16.sp)
                }
            }
        }
    }
}
