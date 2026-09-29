package com.honerai.admin.ui.broadcast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.honerai.admin.core.UserFilter
import com.honerai.admin.core.UserList
import com.honerai.admin.data.title
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.Avatar
import com.honerai.admin.core.HistoryText
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.PrimaryButton
import com.honerai.admin.ui.common.ToastHost
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberToastState
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Уведомление одному пользователю или всем (POST /v1/admin/notifications). */
@Composable
fun BroadcastScreen(container: AdminContainer, navigator: Navigator, initialDeviceId: String?) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val users by container.repo.users.collectAsStateWithLifecycle()
    var toAll by rememberSaveable { mutableStateOf(initialDeviceId == null) }
    var deviceId by rememberSaveable { mutableStateOf(initialDeviceId) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val target = deviceId?.let { users.devices[it] }
    // Рассылка «всем» уходит незаблокированным пользователям.
    val recipients = users.devices.values.count { !it.blocked }
    val canSend = title.isNotBlank() && body.isNotBlank() && (toAll || deviceId != null) && !busy

    fun send() {
        busy = true; error = null
        scope.launch {
            try {
                val count = container.api.sendNotification(if (toAll) null else deviceId, title.trim(), body.trim())
                toast.show(
                    when {
                        toAll && count != null -> if (english) "Sent to $count devices" else "Отправлено устройств: $count"
                        toAll -> if (english) "Sent to everyone" else "Отправлено всем"
                        else -> if (english) "Sent" else "Отправлено"
                    },
                )
                body = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e, english)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(if (toAll) tr("Рассылка", "Broadcast") else tr("Уведомление", "Notification"), onBack = { navigator.pop() })
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(tr("Кому", "To"), fontSize = 14.sp, color = colors.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HonerPill(tr("Всем пользователям", "All users"), toAll, { toAll = true })
                        HonerPill(tr("Одному", "One user"), !toAll, { toAll = false })
                    }
                    if (!toAll) {
                        if (target != null) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface)
                                    .border(0.6.dp, colors.accent, RoundedCornerShape(18.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(target.title(english), target.deviceId, target.presence, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(target.title(english), fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(target.deviceModel, fontSize = 13.sp, color = colors.secondary, maxLines = 1)
                                }
                                Text(tr("Изменить", "Change"), color = colors.accent, fontSize = 14.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { deviceId = null }.padding(8.dp))
                            }
                        } else {
                            HonerField(search, { search = it }, tr("Найти пользователя", "Find user"))
                            val matches = remember(users.devices, search) { UserList.visible(users.devices.values, UserFilter.ALL, search).take(30) }
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface)) {
                                matches.forEach { d ->
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { deviceId = d.deviceId }.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Avatar(d.title(english), d.deviceId, d.presence, size = 36.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Text(d.title(english), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(d.deviceModel, fontSize = 13.sp, color = colors.secondary, maxLines = 1)
                                    }
                                }
                                if (matches.isEmpty()) {
                                    Text(tr("Никого не нашли", "Nobody found"), color = colors.secondary, modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                    }
                    HonerField(title, { title = it; error = null }, tr("Заголовок", "Title"))
                    HonerField(body, { body = it; error = null }, tr("Текст уведомления", "Notification text"), singleLine = false, minLines = 4)
                    error?.let { Text(it, color = colors.danger, fontSize = 14.sp) }
                    Spacer(Modifier.height(4.dp))
                    PrimaryButton(
                        tr("Отправить", "Send"),
                        { if (toAll) confirmAll = true else send() },
                        Modifier.fillMaxWidth(), enabled = canSend, busy = busy,
                        icon = { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = colors.background) },
                    )
                }
            }
        }
        ToastHost(toast, bottom = 40.dp)
    }

    if (confirmAll) {
        ConfirmDialog(
            title = tr("Отправить ", "Send to ") + HistoryText.usersDative(recipients, english) + "?",
            text = tr("Уведомление получат все пользователи Honer AI.", "Every Honer AI user will receive this notification."),
            confirm = tr("Отправить", "Send"),
            onDismiss = { confirmAll = false },
            onConfirm = { confirmAll = false; send() },
        )
    }
}
