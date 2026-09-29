package com.honerai.app.ui.cloud

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.util.Consumer
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.cloud.CloudConfig
import com.honerai.app.cloud.CloudManager
import com.honerai.app.cloud.CloudNotifier
import com.honerai.app.cloud.CloudSocket
import com.honerai.app.cloud.MessagePreview
import com.honerai.app.cloud.PresenceText
import com.honerai.app.ui.common.FullScreenLayer
import com.honerai.app.ui.common.HonerMark
import com.honerai.app.ui.common.copyToClipboard
import com.honerai.app.ui.settings.SettingsDivider
import com.honerai.app.ui.settings.SettingsGroup
import com.honerai.app.ui.settings.SettingsRow
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Красный «официальный» цвет администратора. */
val AdminRed = Color(0xFFE53935)
private val AdminRedDark = Color(0xFFB71C1C)

/** Какие экраны облака открыты (чат с администратором, уведомления). */
object CloudUi {
    private val _adminOpen = MutableStateFlow(false)
    private val _notificationsOpen = MutableStateFlow(false)
    val adminOpen = _adminOpen.asStateFlow()
    val notificationsOpen = _notificationsOpen.asStateFlow()

    fun openAdmin() { if (CloudConfig.isConfigured) _adminOpen.value = true }
    fun closeAdmin() { _adminOpen.value = false }
    fun openNotifications() { if (CloudConfig.isConfigured) _notificationsOpen.value = true }
    fun closeNotifications() { _notificationsOpen.value = false }
}

private fun t(english: Boolean, ru: String, en: String) = if (english) en else ru

// ---------------------------------------------------------------------------------------------
// Значки

/** Красная галочка «подтверждённый аккаунт». */
@Composable
fun VerifiedBadge(size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(AdminRed)
            .semantics { contentDescription = "verified" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.72f))
    }
}

/** Аватар администратора: красный круг со щитом и галочкой. */
@Composable
fun AdminAvatar(size: Dp, withBadge: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(AdminRed, AdminRedDark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        }
        if (withBadge) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(size * 0.42f).clip(CircleShape)
                    .background(HonerTheme.colors.background).padding(1.5.dp),
            ) { VerifiedBadge(size * 0.42f - 3.dp) }
        }
    }
}

/** Красный счётчик непрочитанных. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, color: Color = AdminRed) {
    if (count <= 0) return
    Box(
        modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(CircleShape).background(color).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Точки встраивания в экраны приложения

/** Колокольчик в шапке чата: вкладка «Уведомления» и число непрочитанных. */
@Composable
fun CloudBellButton(english: Boolean) {
    if (!CloudConfig.isConfigured) return
    val colors = HonerTheme.colors
    val entries by CloudManager.notifications.entries.collectAsState()
    val unread = remember(entries) { entries.count { !it.read } }
    Box(
        Modifier.size(width = 42.dp, height = 42.dp).clip(CircleShape).clickable { CloudUi.openNotifications() }
            .semantics { contentDescription = t(english, "Уведомления", "Notifications") + if (unread > 0) ", $unread" else "" }
            .testTag("chat.notifications"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Notifications, null, tint = colors.foreground, modifier = Modifier.size(21.dp))
        UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 4.dp))
    }
}

/** Закреплённая строка «Администратор Honer AI» вверху истории чатов. */
@Composable
fun AdminChatDrawerRow(english: Boolean, onOpened: () -> Unit) {
    if (!CloudConfig.isConfigured) return
    val colors = HonerTheme.colors
    val entries by CloudManager.entries.collectAsState()
    val chat by CloudManager.chat.collectAsState()
    val unread by CloudManager.unread.collectAsState()
    val typing by CloudManager.peerTyping.collectAsState()
    val last = entries.lastOrNull()?.message ?: chat?.lastMessage
    val subtitle = when {
        typing -> t(english, "печатает…", "typing…")
        last != null -> (if (last.fromUser) t(english, "Вы: ", "You: ") else "") + MessagePreview.text(last, english)
        else -> t(english, "Официальный чат поддержки", "Official support chat")
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(if (unread > 0) AdminRed.copy(alpha = 0.10f) else colors.surface.copy(alpha = 0.6f))
            .clickable { CloudUi.openAdmin(); onOpened() }
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag("history.adminChat"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AdminAvatar(40.dp, withBadge = false)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(t(english, "Администратор", "Administrator"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                VerifiedBadge(15.dp)
            }
            Text(subtitle, fontSize = 13.sp, color = if (typing) AdminRed else colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (unread > 0) UnreadBadge(unread)
        else if (last != null) Text(PresenceText.clock(last.createdAt), fontSize = 12.sp, color = colors.secondary)
    }
}

/** Группа «Honer Cloud» в настройках (только когда облако настроено). */
@Composable
fun CloudSettingsGroup() {
    if (!CloudConfig.isConfigured) return
    val context = LocalContext.current
    val settings = remember { AppContainer.get(context).settings }
    val english = settings.isEnglish
    val registered by CloudManager.registered.collectAsState()
    val socket by CloudManager.socketState.collectAsState()
    val deviceId by CloudManager.deviceId.collectAsState()
    val unread by CloudManager.unread.collectAsState()
    val notificationEntries by CloudManager.notifications.entries.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val status = when {
        !registered -> t(english, "не подключено", "not connected")
        socket == CloudSocket.State.OPEN -> t(english, "подключено", "connected")
        socket == CloudSocket.State.CONNECTING -> t(english, "подключение…", "connecting…")
        else -> t(english, "подключено (без связи)", "connected (offline)")
    }
    SettingsGroup(
        "Honer Cloud",
        footer = t(english, "Облако Honer AI: связь с администратором, уведомления и защищённый доступ к нейросети.",
            "Honer AI cloud: administrator chat, notifications and protected access to the AI."),
    ) {
        SettingsRow(Icons.Outlined.Cloud, t(english, "Статус", "Status"), status, chevron = false, tag = "settings.cloud.status")
        SettingsDivider()
        SettingsRow(
            Icons.Outlined.Fingerprint, t(english, "ID устройства", "Device ID"),
            if (copied) t(english, "скопировано", "copied") else deviceId?.take(8)?.plus("…") ?: "—",
            chevron = false, tag = "settings.cloud.deviceId",
        ) {
            deviceId?.let { copyToClipboard(context, it); copied = true }
        }
        SettingsDivider()
        SettingsRow(Icons.AutoMirrored.Outlined.Chat, t(english, "Чат с администратором", "Administrator chat"),
            if (unread > 0) "$unread" else "", tag = "settings.cloud.adminChat") { CloudUi.openAdmin() }
        SettingsDivider()
        val unreadNotifications = notificationEntries.count { !it.read }
        SettingsRow(Icons.Outlined.Notifications, t(english, "Уведомления", "Notifications"),
            if (unreadNotifications > 0) "$unreadNotifications" else "", tag = "settings.cloud.notifications") { CloudUi.openNotifications() }
    }
}

/**
 * Слои облака поверх чата: «Уведомления», чат с администратором, всплывающая плашка;
 * открытие из системного уведомления.
 */
@Composable
fun CloudLayers(activity: MainActivity) {
    if (!CloudConfig.isConfigured) return
    val adminOpen by CloudUi.adminOpen.collectAsState()
    val notificationsOpen by CloudUi.notificationsOpen.collectAsState()

    // Нажали системное уведомление облака — открываем нужный экран.
    DisposableEffect(activity) {
        fun handle(intent: Intent?) {
            when (intent?.getStringExtra(CloudNotifier.EXTRA_OPEN)) {
                CloudNotifier.OPEN_ADMIN -> CloudUi.openAdmin()
                CloudNotifier.OPEN_NOTIFICATIONS -> CloudUi.openNotifications()
                else -> return
            }
            intent.removeExtra(CloudNotifier.EXTRA_OPEN)
        }
        handle(activity.intent)
        val listener = Consumer<Intent> { handle(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        FullScreenLayer(visible = notificationsOpen, onBack = { CloudUi.closeNotifications() }) {
            NotificationsScreen(onClose = { CloudUi.closeNotifications() })
        }
        FullScreenLayer(visible = adminOpen, onBack = { CloudUi.closeAdmin() }) {
            AdminChatScreen(onBack = { CloudUi.closeAdmin() })
        }
        InAppBanner(Modifier.align(Alignment.TopCenter))
    }
}

/** Плашка сверху: новое сообщение администратора или рассылка, пока пользователь в другом экране. */
@Composable
private fun InAppBanner(modifier: Modifier) {
    val colors = HonerTheme.colors
    val banner by CloudManager.banner.collectAsState()
    val adminOpen by CloudUi.adminOpen.collectAsState()
    var last by remember { mutableStateOf(banner) }
    if (banner != null) last = banner
    val shown = banner != null && !(adminOpen && banner?.target == CloudNotifier.OPEN_ADMIN)
    AnimatedVisibility(
        visible = shown, modifier = modifier,
        enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
    ) {
        val value = last ?: return@AnimatedVisibility
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 10.dp, vertical = 6.dp)
                .widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)).background(colors.raised)
                .border(0.7.dp, colors.divider, RoundedCornerShape(18.dp))
                .pointerInputDismiss { CloudManager.dismissBanner() }
                .clickable {
                    CloudManager.dismissBanner()
                    if (value.target == CloudNotifier.OPEN_ADMIN) CloudUi.openAdmin() else CloudUi.openNotifications()
                }
                .padding(12.dp)
                .testTag("cloud.banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (value.target == CloudNotifier.OPEN_ADMIN && !value.fromAi) AdminAvatar(38.dp, withBadge = false)
            else if (value.fromAi) HonerMark(34.dp)
            else Box(Modifier.size(38.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Notifications, null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(value.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1)
                    if (value.target == CloudNotifier.OPEN_ADMIN && !value.fromAi) VerifiedBadge(13.dp)
                }
                Text(value.text, fontSize = 13.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Смахнуть плашку вверх — скрыть. */
private fun Modifier.pointerInputDismiss(onDismiss: () -> Unit): Modifier =
    pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag < -12f) onDismiss() } }

// ---------------------------------------------------------------------------------------------
// Блокировка

/**
 * Экран «Доступ ограничен» поверх всего приложения, пока сервер считает устройство заблокированным.
 * Снимается сам, как только блокировку сняли (проверка при открытии приложения и каждые 20 с).
 */
@Composable
fun BlockedOverlay() {
    if (!CloudConfig.isConfigured) return
    val reason by CloudManager.blocked.collectAsState()
    val value = reason ?: return
    val context = LocalContext.current
    val english = remember { AppContainer.get(context).settings.isEnglish }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Поле ввода под экраном блокировки теряет фокус, иначе клавиатура остаётся поверх.
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true); keyboard?.hide() }
    // «Назад» не пускает в приложение — только сворачивает его.
    BackHandler { (context as? android.app.Activity)?.moveTaskToBack(true) }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0B0D))
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("cloud.blocked"),
        contentAlignment = Alignment.Center,
    ) {
        BlockedContent(value, english, checking) {
            checking = true
            scope.launch {
                CloudManager.refreshChat()
                checking = false
            }
        }
    }
}

@Composable
private fun BoxScope.BlockedContent(reason: String, english: Boolean, checking: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.widthIn(max = 460.dp).padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(AdminRed.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Block, null, tint = AdminRed, modifier = Modifier.size(46.dp))
        }
        Text(t(english, "Доступ ограничен", "Access restricted"), fontSize = 24.sp, fontWeight = FontWeight.Bold,
            color = Color.White, textAlign = TextAlign.Center)
        Text(
            // Сервер без причины присылает код ошибки «blocked» — показываем человеческий текст.
            reason.takeIf { it.isNotBlank() && !it.equals("blocked", ignoreCase = true) }
                ?: t(english, "Администратор ограничил доступ к Honer AI.", "The administrator has restricted access to Honer AI."),
            fontSize = 16.sp, lineHeight = 22.sp, color = Color(0xFFE0E0E3), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            t(english,
                "По лицензионному соглашению Honer AI доступ может быть ограничен без объяснения причин. Если ограничение снимут, приложение откроется само.",
                "Under the Honer AI license agreement, access may be restricted without explanation. If the restriction is lifted, the app will open automatically."),
            fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center,
        )
        if (checking) CircularProgressIndicator(color = Color(0xFF8E8E93), modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        else TextButton(onClick = onRetry, modifier = Modifier.testTag("cloud.blocked.retry")) {
            Text(t(english, "Проверить снова", "Check again"), color = Color(0xFF8E8E93))
        }
    }
}
