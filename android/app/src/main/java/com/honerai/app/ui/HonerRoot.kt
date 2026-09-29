package com.honerai.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.ui.chat.ChatRoot
import com.honerai.app.ui.common.LocalChatFontScale
import com.honerai.app.ui.common.LocalReduceMotion
import com.honerai.app.ui.onboarding.OnboardingScreen
import com.honerai.app.ui.parental.ParentalGate
import com.honerai.app.ui.theme.HonerAppTheme
import com.honerai.app.ui.theme.HonerTheme

/** Ключи уведомления «ответ готов», по которым открывается нужный чат. */
private val chatIdExtras = listOf("chatId", "honer.chatId", "chat_id", "conversationId")

private const val UI_PREFS = "honer.chat.ui"
private const val TEXT_SCALE = 0.93f
private const val ASKED_NOTIFICATIONS = "askedNotifications"

/** Системная настройка «Убрать анимацию» / масштаб анимации 0. */
private fun animationsDisabled(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/**
 * Корень интерфейса: тема, знакомство или чат (под родительским контролем),
 * профиль и язык для нейросети, учёт времени в приложении, разрешение на уведомления,
 * открытие чата из уведомления.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HonerRoot(activity: MainActivity) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val settings = container.settings
    val store = container.store
    val appearance by settings.appearance.collectAsState()
    val completed by settings.completedOnboarding.collectAsState()
    val language by settings.language.collectAsState()
    val fontScale by settings.fontScale.collectAsState()
    val reduceSetting by settings.reduceMotion.collectAsState()
    val displayName by settings.displayName.collectAsState()
    val birthday by settings.birthday.collectAsState()
    val autoDeleteDays by settings.autoDeleteDays.collectAsState()
    val notificationsEnabled by settings.notificationsEnabled.collectAsState()
    val reduce = reduceSetting || container.isLowEndDevice || remember { animationsDisabled(context) }

    // Нейросеть знает язык и профиль пользователя.
    LaunchedEffect(language) { store.setResponseLanguage(language) }
    LaunchedEffect(displayName, birthday) { store.setProfile(displayName, birthday) }
    // Автоудаление старых чатов по настройке.
    LaunchedEffect(autoDeleteDays) { if (autoDeleteDays > 0) store.purgeOldChats(autoDeleteDays) }

    // Время в приложении — для статистики; при уходе в фон история сохраняется сразу.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var startedAt = System.currentTimeMillis()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_STOP -> {
                    store.recordSessionTime((System.currentTimeMillis() - startedAt) / 1000.0)
                    store.persistNow()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Нажали уведомление «ответ готов» — открываем чат с ответом.
    DisposableEffect(activity) {
        fun handle(intent: Intent?) {
            val id = chatIdExtras.firstNotNullOfOrNull { intent?.getStringExtra(it) } ?: return
            if (store.conversations.value.any { it.id == id }) store.selectChat(id)
            chatIdExtras.forEach { intent?.removeExtra(it) }
        }
        handle(activity.intent)
        val listener = Consumer<Intent> { handle(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

    // Разрешение на уведомления (Android 13+) спрашиваем один раз, после знакомства.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(completed, notificationsEnabled) {
        if (!completed || !notificationsEnabled || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Текст чуть компактнее системного: на узких Android-экранах (360 dp) iOS-размеры выглядят крупно.
    // Системный масштаб шрифта пользователя сохраняется — умножаем, а не заменяем.
    val baseDensity = LocalDensity.current
    val density = remember(baseDensity) { Density(baseDensity.density, baseDensity.fontScale * TEXT_SCALE) }

    HonerAppTheme(appearance = appearance) {
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalReduceMotion provides reduce,
            LocalChatFontScale provides fontScale.toFloat(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(HonerTheme.colors.background)
                    // Идентификаторы testTag видны UI-тестам как resource-id (как accessibilityIdentifier на iOS).
                    .semantics { testTagsAsResourceId = true },
            ) {
                Crossfade(targetState = completed, animationSpec = tween(if (reduce) 0 else 350), label = "root") { done ->
                    if (done) {
                        ParentalGate { ChatRoot(activity) }
                    } else {
                        OnboardingScreen()
                    }
                }
            }
        }
    }
}
