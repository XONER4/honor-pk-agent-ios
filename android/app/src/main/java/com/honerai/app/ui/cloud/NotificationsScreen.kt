package com.honerai.app.ui.cloud

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.cloud.CloudManager
import com.honerai.app.cloud.NotificationEntry
import com.honerai.app.cloud.NotificationGrouping
import com.honerai.app.ui.common.EmptyState
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Вкладка «Уведомления»: рассылки администратора, сообщения в чате с ним и «Ответ готов»,
 * по дням; смахнуть — удалить; «Прочитать все»; нажатие открывает связанный чат.
 */
@Composable
fun NotificationsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val english = container.settings.isEnglish
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val entries by CloudManager.notifications.entries.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }
    LaunchedEffect(Unit) { CloudManager.refreshNotifications() }
    val days = remember(entries, now, english) { NotificationGrouping.group(entries, now, english) }
    val hasUnread = entries.any { !it.read }

    fun open(entry: NotificationEntry) {
        CloudManager.markNotificationRead(entry)
        when {
            entry.kind == NotificationEntry.KIND_ANSWER && entry.localChatId != null -> {
                val id = entry.localChatId
                if (container.store.conversations.value.any { it.id == id }) container.store.selectChat(id)
                onClose()
            }
            entry.kind == NotificationEntry.KIND_MESSAGE || entry.chatId != null -> CloudUi.openAdmin()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose)
                    .semantics { contentDescription = t("Назад", "Back") }.testTag("notifications.back"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = colors.foreground) }
            Text(t("Уведомления", "Notifications"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (hasUnread) {
                TextButton(onClick = { CloudManager.markAllNotificationsRead() }, modifier = Modifier.testTag("notifications.readAll")) {
                    Text(t("Прочитать все", "Mark all read"), color = colors.accent, fontSize = 15.sp)
                }
            }
        }
        if (days.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.NotificationsNone, t("Здесь появятся уведомления", "Notifications will appear here"))
            }
            return@Column
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("notifications.list"),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                days.forEach { day ->
                    item(key = "day." + day.key) {
                        Text(day.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.secondary,
                            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp))
                    }
                    items(day.items, key = { it.id }) { entry ->
                        NotificationRow(entry, english, onOpen = { open(entry) }, onDelete = { CloudManager.notifications.remove(entry.id) })
                    }
                }
                item(key = "bottom") {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationRow(entry: NotificationEntry, english: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = HonerTheme.colors
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) onDelete()
        value != SwipeToDismissBoxValue.Settled
    })
    var expanded by remember(entry.id) { mutableStateOf(false) }
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(vertical = 3.dp),
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFF453A)).padding(horizontal = 20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Icon(Icons.Outlined.Delete, if (english) "Delete" else "Удалить", tint = Color.White) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (entry.read) colors.surface else colors.raised)
                .clickable {
                    if (entry.kind == NotificationEntry.KIND_MESSAGE || entry.kind == NotificationEntry.KIND_ANSWER || entry.chatId != null) onOpen()
                    else { expanded = !expanded; onOpen() }
                }
                .animateContentSize()
                .padding(12.dp)
                .testTag("notification." + entry.id),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (entry.kind) {
                NotificationEntry.KIND_MESSAGE -> AdminAvatar(40.dp, withBadge = false)
                NotificationEntry.KIND_ANSWER -> Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.TaskAlt, null, tint = colors.accent, modifier = Modifier.size(22.dp)) }
                else -> Box(Modifier.size(40.dp).clip(CircleShape).background(if (entry.kind == "admin") AdminRed else colors.accent),
                    contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Campaign, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Название с галочкой занимает всё свободное место до времени (не половину строки).
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(entry.title, fontSize = 15.sp, fontWeight = if (entry.read) FontWeight.Medium else FontWeight.Bold,
                            color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (entry.kind == NotificationEntry.KIND_MESSAGE || entry.kind == "admin") VerifiedBadge(13.dp)
                    }
                    Text(DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(entry.createdAtMs).atZone(ZoneId.systemDefault())),
                        fontSize = 12.sp, color = colors.secondary)
                    if (!entry.read) Box(Modifier.size(8.dp).clip(CircleShape).background(AdminRed))
                }
                if (entry.body.isNotBlank()) {
                    Text(entry.body, fontSize = 14.sp, lineHeight = 19.sp, color = colors.secondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
