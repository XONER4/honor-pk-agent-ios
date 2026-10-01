package com.honerai.admin.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.HistoryText
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.ActivityItem
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/** Общая лента: входы администраторов, их действия с пользователями и события приложений. */
@Composable
fun ActivityScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val items = remember { mutableStateListOf<ActivityItem>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = null
        try {
            val page = container.api.activity(limit = 120)
            items.clear(); items.addAll(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e, english)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Лента действий", "Activity feed"), onBack = { navigator.pop() },
            actions = { IconCircle(Icons.Rounded.Refresh, tr("Обновить", "Refresh"), { reload++ }) })
        when {
            loading && items.isEmpty() -> LoadingBox()
            error != null && items.isEmpty() -> ErrorPanel(error.orEmpty(), { reload++ }, Modifier.padding(top = 40.dp))
            items.isEmpty() -> EmptyState(Icons.Rounded.History, tr("Пока ничего не происходило", "Nothing has happened yet"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                itemsIndexed(items, key = { i, a -> "$i:${a.kind}:${a.at}" }) { _, a ->
                    ActivityRow(a, now, zone, english) { a.deviceId?.let { navigator.push(Route.User(it)) } }
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(colors.divider))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(a: ActivityItem, now: java.time.Instant, zone: ZoneId, english: Boolean, onOpenUser: () -> Unit) {
    val colors = HonerTheme.colors
    val icon = when (a.kind) {
        "login" -> Icons.Rounded.Login
        "device" -> Icons.Rounded.Person
        else -> Icons.Rounded.AdminPanelSettings
    }
    val tint = when (a.kind) {
        "login" -> colors.away
        "device" -> colors.accent
        else -> Color8B5CF6
    }
    val clickable = a.deviceId != null
    Row(
        Modifier.fillMaxWidth().let { if (clickable) it.clickable(onClick = onOpenUser) else it }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label(a, english), fontSize = 15.sp, color = colors.foreground)
            val target = a.target?.takeIf { it.isNotBlank() } ?: a.publicId?.let { "#$it" }
            val meta = listOfNotNull(
                a.who.takeIf { it.isNotBlank() },
                target,
                Times.parse(a.at)?.let { PresenceText.ago(it, now, zone, english) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, fontSize = 12.sp, color = colors.secondary)
            }
        }
    }
}

/** Человеческое описание строки ленты. */
private fun label(a: ActivityItem, english: Boolean): String = when (a.kind) {
    "login" -> if (a.action == "fail") (if (english) "Failed sign-in" else "Неудачный вход") else (if (english) "Signed in" else "Вход в админку")
    "device" -> HistoryText.event(a.action, null, null, english)
    else -> HistoryText.action(a.action, english)
}

private val Color8B5CF6 = androidx.compose.ui.graphics.Color(0xFF8B5CF6)
