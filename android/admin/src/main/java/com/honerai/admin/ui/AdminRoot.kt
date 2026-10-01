package com.honerai.admin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.MainActivity
import com.honerai.admin.ui.broadcast.BroadcastScreen
import com.honerai.admin.ui.chat.ChatScreen
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.home.HomeScreen
import com.honerai.admin.ui.insights.AiSettingsScreen
import com.honerai.admin.ui.insights.ReportsScreen
import com.honerai.admin.ui.login.LoginScreen
import com.honerai.admin.ui.settings.SettingsScreen
import com.honerai.admin.ui.theme.HonerAdminTheme
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.tr
import com.honerai.admin.ui.user.UserCardScreen

/** Экраны админки. */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data class User(val deviceId: String) : Route { override val key = "user:$deviceId" }
    data class Chat(val chatId: String, val deviceId: String?) : Route { override val key = "chat:$chatId" }
    data class Broadcast(val deviceId: String?) : Route { override val key = "broadcast:${deviceId ?: "all"}" }
    data object Settings : Route { override val key = "settings" }
    data object Ai : Route { override val key = "ai" }
    data object Reports : Route { override val key = "reports" }
    data object Logins : Route { override val key = "logins" }
    data object Activity : Route { override val key = "activity" }
}

/** Простой стек экранов; «Назад» снимает верхний. */
@Stable
class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home)
    val top: Route get() = stack.last()

    fun push(route: Route) {
        if (top == route) return
        // Тот же экран глубже в стеке — возвращаемся к нему, а не плодим копии.
        val existing = stack.indexOfFirst { it.key == route.key }
        if (existing > 0) {
            while (stack.size > existing + 1) stack.removeAt(stack.lastIndex)
            stack[existing] = route
            return
        }
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset() {
        stack.clear(); stack.add(Route.Home)
    }
}

@Composable
fun AdminRoot(activity: MainActivity) {
    val container = remember { AdminContainer.get(activity) }
    val english by container.settings.english.collectAsStateWithLifecycle()
    val session by container.session.session.collectAsStateWithLifecycle()
    HonerAdminTheme(english = english) {
        Box(Modifier.fillMaxSize().background(HonerTheme.colors.background)) {
            if (session == null) {
                LoginScreen(container)
            } else {
                AdminMain(activity, container)
            }
        }
    }
}

@Composable
private fun AdminMain(activity: MainActivity, container: AdminContainer) {
    val navigator = remember { Navigator() }
    val holder = rememberSaveableStateHolder()
    val openChat by activity.openChat.collectAsStateWithLifecycle()
    LaunchedEffect(openChat) {
        val request = openChat ?: return@LaunchedEffect
        navigator.push(Route.Chat(request.chatId, request.deviceId))
        activity.consumeOpenChat()
    }
    NotificationPermission()
    BackHandler(enabled = navigator.stack.size > 1) { navigator.pop() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            // Планшет / раскладной: список слева, выбранный экран справа.
            val listWidth = if (maxWidth >= 1100.dp) 420.dp else 360.dp
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) {
                    holder.SaveableStateProvider(Route.Home.key) { HomeScreen(container, navigator) }
                }
                Box(Modifier.width(0.6.dp).fillMaxHeight().background(HonerTheme.colors.divider))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val top = navigator.top
                    if (top == Route.Home) {
                        EmptyState(Icons.AutoMirrored.Rounded.Chat, tr("Выберите пользователя слева", "Select a user on the left"),
                            Modifier.align(Alignment.Center))
                    } else {
                        RouteContent(top, container, navigator, holder)
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = navigator.top,
                transitionSpec = {
                    val forward = navigator.stack.contains(initialState)
                    if (forward) {
                        (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(160)))
                    }
                },
                contentKey = { it.key },
                label = "route",
            ) { route ->
                RouteContent(route, container, navigator, holder)
            }
        }
    }
}

@Composable
private fun RouteContent(route: Route, container: AdminContainer, navigator: Navigator, holder: SaveableStateHolder) {
    holder.SaveableStateProvider(route.key) {
        when (route) {
            Route.Home -> HomeScreen(container, navigator)
            is Route.User -> UserCardScreen(container, navigator, route.deviceId)
            is Route.Chat -> ChatScreen(container, navigator, route.chatId, route.deviceId)
            is Route.Broadcast -> BroadcastScreen(container, navigator, route.deviceId)
            Route.Settings -> SettingsScreen(container, navigator)
            Route.Ai -> AiSettingsScreen(container, navigator)
            Route.Reports -> ReportsScreen(container, navigator)
            Route.Logins -> com.honerai.admin.ui.insights.LoginsScreen(container, navigator)
            Route.Activity -> com.honerai.admin.ui.insights.ActivityScreen(container, navigator)
        }
    }
}

/** Android 13+: разрешение на уведомления о новых сообщениях — один раз после входа. */
@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("admin.settings", android.content.Context.MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
