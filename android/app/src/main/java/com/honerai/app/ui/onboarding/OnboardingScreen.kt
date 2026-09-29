package com.honerai.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.R
import com.honerai.app.ui.common.HonerSegmented
import com.honerai.app.ui.common.LocalReduceMotion
import com.honerai.app.ui.common.rememberHaptics
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Дата рождения хранится как на iPhone: «yyyy-MM-dd». */
internal val birthdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * Первый экран — знакомство: живой цветной фон, логотип Honer AI, имя, язык,
 * дата рождения по желанию и восстановление из резервной копии.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val settings = container.settings
    val store = container.store
    val colors = HonerTheme.colors
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val language by settings.language.collectAsState()
    val english = language == "en"
    fun t(ru: String, en: String) = if (english) en else ru

    var name by rememberSaveable { mutableStateOf(settings.displayName.value) }
    val savedBirthday = remember { runCatching { LocalDate.parse(settings.birthday.value, birthdayFormat) }.getOrNull() }
    var hasBirthday by rememberSaveable { mutableStateOf(savedBirthday != null) }
    var birthday by rememberSaveable { mutableStateOf((savedBirthday ?: LocalDate.now().minusYears(20)).toString()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var restoreBusy by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appear by animateFloatAsState(if (appeared) 1f else 0f, if (reduce) tween(0) else spring(dampingRatio = 0.8f, stiffness = 60f), label = "appear")
    val trimmed = name.trim()

    fun complete() {
        if (trimmed.isEmpty()) return
        focusManager.clearFocus()
        haptics.success()
        val birthdayText = if (hasBirthday) birthday else ""
        settings.setDisplayName(trimmed)
        settings.setBirthday(birthdayText)
        store.setProfile(trimmed, birthdayText)
        settings.setCompletedOnboarding(true)
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        restoreBusy = true
        restoreStatus = null
        scope.launch {
            val result = store.importData(uri)
            restoreBusy = false
            result.onSuccess {
                if (settings.displayName.value.isBlank()) {
                    name = ""
                    restoreStatus = t("Чаты восстановлены. Введите имя, чтобы продолжить.", "Chats restored. Enter your name to continue.")
                } else {
                    haptics.success()
                    settings.setCompletedOnboarding(true)
                }
            }.onFailure { error ->
                restoreStatus = error.message?.takeIf { it.isNotBlank() && error !is UnsupportedOperationException }
                    ?: t("Не удалось прочитать резервную копию.", "Could not read the backup.")
            }
        }
    }

    Box(Modifier.fillMaxSize().testTag("onboarding.page")) {
        OnboardingBackground(colors.isDark, animated = !reduce)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically),
            ) {
                Spacer(Modifier.height(20.dp))
                AnimatedLogo(!reduce, Modifier.size(150.dp).graphicsLayer {
                    alpha = appear; scaleX = 0.7f + 0.3f * appear; scaleY = 0.7f + 0.3f * appear
                })
                Column(
                    Modifier.graphicsLayer { alpha = appear; translationY = (1 - appear) * 18.dp.toPx() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Honer AI",
                        style = TextStyle(
                            brush = Brush.linearGradient(listOf(Color(0xFF5C9EFF), Color(0xFF9E73FF), Color(0xFF33D4F2))),
                            fontSize = 36.sp, fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                    Text(t("Давайте познакомимся", "Let's get acquainted"), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.foreground, textAlign = TextAlign.Center)
                    Text(t("Как к вам обращаться? Подойдёт имя, ник или позывной.",
                        "What should I call you? Use your name, nickname or callsign."),
                        fontSize = 16.sp, color = colors.secondary, textAlign = TextAlign.Center)
                }
                val cardShape = RoundedCornerShape(28.dp)
                Column(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = appear; translationY = (1 - appear) * 30.dp.toPx() }
                        .clip(cardShape)
                        .background(colors.surface.copy(alpha = if (colors.isDark) 0.72f else 0.82f))
                        .border(1.dp, Color.White.copy(alpha = if (colors.isDark) 0.1f else 0.5f), cardShape)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Язык приложения и ответов нейросети. По умолчанию — русский.
                    HonerSegmented(listOf("Русский", "English"), if (english) 1 else 0, { settings.setLanguage(if (it == 1) "en" else "ru") },
                        tag = "onboarding.language")
                    val fieldShape = RoundedCornerShape(20.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(fieldShape)
                            .background(colors.background.copy(alpha = if (colors.isDark) 0.55f else 0.8f))
                            .border(if (nameFocused) 1.5.dp else 0.8.dp, if (nameFocused) colors.accent else colors.divider, fieldShape)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.AccountCircle, null, tint = colors.accent, modifier = Modifier.size(26.dp))
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) Text(t("Имя, ник или позывной", "Name, nickname or callsign"), fontSize = 17.sp, color = colors.secondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it.take(60) },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.foreground, fontSize = 18.sp),
                                cursorBrush = SolidColor(colors.accent),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { complete() }),
                                modifier = Modifier.fillMaxWidth().onFocusChanged { nameFocused = it.isFocused }.testTag("onboarding.name"),
                            )
                        }
                    }
                    // Дата рождения — по желанию: нейросеть учтёт возраст и поздравит с праздником.
                    Column(
                        Modifier.fillMaxWidth().clip(fieldShape)
                            .background(colors.background.copy(alpha = if (colors.isDark) 0.55f else 0.8f))
                            .border(0.8.dp, colors.divider, fieldShape)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CardGiftcard, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                            Text("  " + t("Дата рождения", "Birthday"), fontSize = 17.sp, fontWeight = FontWeight.Medium,
                                color = colors.foreground, modifier = Modifier.weight(1f))
                            Switch(hasBirthday, { hasBirthday = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                                modifier = Modifier.testTag("onboarding.birthday.toggle"))
                        }
                        AnimatedVisibility(hasBirthday, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("Когда родились", "Date of birth"), fontSize = 16.sp, color = colors.foreground, modifier = Modifier.weight(1f))
                                val date = runCatching { LocalDate.parse(birthday) }.getOrNull() ?: LocalDate.now().minusYears(20)
                                Text(
                                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(if (english) Locale.ENGLISH else Locale("ru", "RU")).format(date),
                                    fontSize = 16.sp, color = colors.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.raised)
                                        .clickable { pickerOpen = true }.padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("onboarding.birthday"),
                                )
                            }
                        }
                    }
                    val enabled = trimmed.isNotEmpty()
                    ContinueButton(t("Начать общение", "Start chatting"), enabled, animated = !reduce && enabled, onClick = ::complete)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !restoreBusy) {
                        restoreLauncher.launch(arrayOf("application/json", "*/*"))
                    }.padding(horizontal = 10.dp, vertical = 8.dp).graphicsLayer { alpha = appear }.testTag("onboarding.restore"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (restoreBusy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.CloudDownload, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Text(t("Восстановить из резервной копии", "Restore from backup"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.accent)
                }
                restoreStatus?.let {
                    Text(it, fontSize = 13.sp, color = Color(0xFFFF9F0A), textAlign = TextAlign.Center, modifier = Modifier.testTag("onboarding.restore.status"))
                }
                Text(t("Имя появится в профиле, и Honer AI будет учитывать его в разговоре. Изменить его можно в настройках.",
                    "Your name will appear in your profile and Honer AI will use it in conversation. You can change it in Settings."),
                    fontSize = 13.sp, color = colors.secondary, textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = appear })
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (pickerOpen) {
        val today = LocalDate.now()
        val initial = runCatching { LocalDate.parse(birthday) }.getOrNull() ?: today.minusYears(20)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = (today.year - 120)..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= Instant.now().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        birthday = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(birthdayFormat)
                    }
                    pickerOpen = false
                }) { Text("OK", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text(t("Отмена", "Cancel"), color = colors.accent) } },
            colors = DatePickerDefaults.colors(containerColor = colors.surface),
        ) {
            DatePicker(state, colors = DatePickerDefaults.colors(containerColor = colors.surface, selectedDayContainerColor = colors.accent,
                todayDateBorderColor = colors.accent, selectedYearContainerColor = colors.accent))
        }
    }
}

/** Кнопка «Начать общение»: градиент и пробегающий блик. */
@Composable
private fun ContinueButton(title: String, enabled: Boolean, animated: Boolean, onClick: () -> Unit) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .shadow(if (enabled) 16.dp else 0.dp, CircleShape, ambientColor = Color(0xFF5973FF), spotColor = Color(0xFF5973FF))
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF4D8CFF), Color(0xFF8C66FF))))
            .drawWithContent {
                drawContent()
                if (animated) {
                    val cycle = (time % 2.8f) / 2.8f
                    val x = size.width * (cycle * 1.8f - 0.4f)
                    val band = size.width * 0.35f
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                        startX = x, endX = x + band))
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 17.dp)
            .testTag("onboarding.continue"),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Живой фон: мягкие цветные пятна медленно плывут и переливаются. Пятна рисуются
 * радиальными градиентами (без размытия) — это дёшево и работает на любых Android.
 */
@Composable
private fun OnboardingBackground(dark: Boolean, animated: Boolean) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // 30 кадров в секунду достаточно для медленного фона.
                if (now - last >= 33_000_000L) { time = (now - start) / 1_000_000_000f; last = now }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(if (dark) Color(0xFF090B14) else Color(0xFFF2F5FF))
        val blobs = listOf(
            Triple(Color(0xFF4080FF), 0.55f, 0f),
            Triple(Color(0xFF9959FF), 0.48f, 2.1f),
            Triple(Color(0xFF1AD9F2), 0.42f, 4.2f),
            Triple(Color(0xFFFF73BF), 0.3f, 5.3f),
        )
        val speed = 0.18f
        for ((color, radius, phase) in blobs) {
            val x = size.width * (0.5f + 0.34f * cos(time * speed + phase))
            val y = size.height * (0.45f + 0.28f * sin(time * speed * 1.3f + phase * 1.7f))
            val r = max(size.width, size.height) * radius * 0.75f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (dark) 0.6f else 0.45f), Color.Transparent),
                center = Offset(x, y), radius = r), radius = r, center = Offset(x, y))
        }
        drawRect((if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.25f else 0.35f))
    }
}

/** Логотип со светящимся вращающимся кольцом и мягким «парением». */
@Composable
private fun AnimatedLogo(animated: Boolean, modifier: Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    val float = sin(time * 1.4f) * 6f
    val pulse = 1f + 0.04f * sin(time * 2f)
    Box(
        modifier.graphicsLayer { translationY = float * density }.semantics { contentDescription = "Honer AI" }.testTag("onboarding.logo"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF598CFF).copy(alpha = 0.55f), Color.Transparent), center = center,
                radius = 80.dp.toPx() * pulse * 1.15f), radius = 80.dp.toPx() * pulse * 1.15f)
            rotate(time * 40f) {
                drawCircle(
                    Brush.sweepGradient(listOf(Color(0xFF4D99FF), Color(0xFFA666FF), Color(0xFF26E6F2), Color(0xFF4D99FF)), center = center),
                    radius = 64.dp.toPx(), style = Stroke(width = 3.5.dp.toPx()),
                )
            }
        }
        Box(Modifier.size(112.dp).shadow(18.dp, CircleShape, spotColor = Color(0xFF4D73FF)).clip(CircleShape)
            .background(HonerTheme.colors.surface.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.honer_logo), null, Modifier.size(78.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(RoundedCornerShape(18.dp)))
        }
    }
}
