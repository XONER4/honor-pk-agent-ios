package com.honerai.admin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.Presence
import com.honerai.admin.data.title
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.Avatar
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import java.time.ZoneId

/** Вкладка «Поддержка»: переписки с пользователями (непрочитанные вверху), тап — открыть чат. */
@Composable
fun SupportChatsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val users by container.repo.users.collectAsStateWithLifecycle()

    val chats = remember(users.devices) {
        users.devices.values
            .filter { !it.deleted }
            .sortedWith(
                compareByDescending<DeviceSummary> { it.unreadForAdmin > 0 }
                    .thenByDescending { it.unreadForAdmin }
                    .thenByDescending { Times.parse(it.lastSeen)?.toEpochMilli() ?: 0L },
            )
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Поддержка", "Support"))
        if (chats.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Rounded.Chat, tr("Переписок пока нет", "No conversations yet"))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                items(chats, key = { it.deviceId }) { d ->
                    ConversationRow(d, now, zone, english) {
                        val chatId = d.adminChatId.takeIf { it.isNotEmpty() } ?: return@ConversationRow
                        navigator.push(Route.Chat(chatId, d.deviceId))
                    }
                    Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(0.6.dp).background(colors.divider))
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(d: DeviceSummary, now: java.time.Instant, zone: ZoneId, english: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val name = d.title(english)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, d.deviceId, if (d.blocked) null else d.presence, size = 50.dp, blocked = d.blocked)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (d.typingIn != null) tr("печатает…", "typing…")
            else PresenceText.status(d.presence, Times.parse(d.lastSeen), now, zone, english)
            Text(sub, fontSize = 13.sp,
                color = if (d.typingIn != null || d.presence != Presence.OFFLINE) colors.accent else colors.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (d.unreadForAdmin > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (d.unreadForAdmin > 99) "99+" else d.unreadForAdmin.toString(),
                    fontSize = 11.sp, color = colors.background, fontWeight = FontWeight.Bold)
            }
        }
    }
}
