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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.honerai.admin.data.Chat
import com.honerai.admin.data.Presence
import com.honerai.admin.data.Sender
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.chat.messagePreview
import com.honerai.admin.ui.common.Avatar
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import java.time.ZoneId

/** Вкладка «Поддержка»: переписки с пользователями (превью последнего сообщения, непрочитанные вверху). */
@Composable
fun SupportChatsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val users by container.repo.users.collectAsStateWithLifecycle()
    var chats by remember { mutableStateOf<List<Chat>?>(null) }
    var staffUnread by remember { mutableStateOf(0) }

    // Сигнал новых сообщений: суммарный unread из репозитория (обновляется по WebSocket) — перечитываем список.
    val unreadSignal = users.devices.values.sumOf { it.unreadForAdmin }
    androidx.compose.runtime.LaunchedEffect(unreadSignal) {
        runCatching { container.api.chats() }.onSuccess { chats = it }
    }
    // Непрочитанные в чате команды — опрашиваем периодически.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            runCatching { container.api.staffUnread() }.onSuccess { staffUnread = it.unread }
            kotlinx.coroutines.delay(5000)
        }
    }

    // Живые unread/присутствие берём из репозитория (реальное время), превью — из загруженного списка.
    val merged = remember(chats, users.devices) {
        (chats ?: emptyList()).map { c ->
            val d = users.devices[c.deviceId]
            if (d == null) c else c.copy(
                unread = d.unreadForAdmin,
                peerPresence = d.presence,
                peerLastSeen = d.lastSeen ?: c.peerLastSeen,
                peerTyping = d.typingIn == c.id || c.peerTyping,
            )
        }.sortedWith(
            compareByDescending<Chat> { it.unread > 0 }
                .thenByDescending { Times.parse(it.lastMessage?.createdAt)?.toEpochMilli() ?: 0L },
        )
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Поддержка", "Support"))
        StaffPinnedRow(staffUnread) { navigator.push(Route.StaffChat) }
        Box(Modifier.fillMaxWidth().height(6.dp).background(colors.background))
        when {
            chats == null -> LoadingBox()
            merged.isEmpty() -> EmptyState(Icons.AutoMirrored.Rounded.Chat, tr("Переписок пока нет", "No conversations yet"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                items(merged, key = { it.id }) { c ->
                    ConversationRow(c, now, zone, english) { navigator.push(Route.Chat(c.id, c.deviceId)) }
                    Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(0.6.dp).background(colors.divider))
                }
            }
        }
    }
}

/** Закреплённая строка «Чат команды» вверху списка. */
@Composable
private fun StaffPinnedRow(unread: Int, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("👥", fontSize = 24.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Чат команды", "Team chat"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
            Text(tr("Админы и разработчик · закреплён", "Admins and developer · pinned"), fontSize = 13.sp, color = colors.secondary)
        }
        if (unread > 0) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Text(if (unread > 99) "99+" else unread.toString(), fontSize = 11.sp, color = colors.background, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Chat, now: java.time.Instant, zone: ZoneId, english: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val blocked = false
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.title, c.deviceId, if (c.peerPresence == Presence.OFFLINE) null else c.peerPresence, size = 52.dp, blocked = blocked)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title.ifBlank { tr("Пользователь", "User") }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                c.lastMessage?.createdAt?.let { Times.parse(it) }?.let { t ->
                    Spacer(Modifier.width(8.dp))
                    Text(PresenceText.ago(t, now, zone, english), fontSize = 12.sp, color = colors.secondary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = previewText(c, english)
                Text(preview, fontSize = 14.sp,
                    color = if (c.peerTyping) colors.accent else colors.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        Text(if (c.unread > 99) "99+" else c.unread.toString(),
                            fontSize = 11.sp, color = colors.background, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Превью последнего сообщения: «печатает…», либо «Вы/ИИ: текст», либо текст пользователя. */
@Composable
private fun previewText(c: Chat, english: Boolean): String {
    if (c.peerTyping) return tr("печатает…", "typing…")
    val m = c.lastMessage ?: return tr("Нет сообщений", "No messages")
    val body = messagePreview(m, english)
    return when (m.sender) {
        Sender.ADMIN -> (if (english) "You: " else "Вы: ") + body
        Sender.AI -> "Honer AI: " + body
        else -> body
    }
}
