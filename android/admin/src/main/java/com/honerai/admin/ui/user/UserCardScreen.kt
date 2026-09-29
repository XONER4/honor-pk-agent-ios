package com.honerai.admin.ui.user

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.DeviceDetail
import com.honerai.admin.data.Presence
import com.honerai.admin.data.title
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.Avatar
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.PrimaryButton
import com.honerai.admin.ui.common.SecondaryButton
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.common.ToastHost
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.copyToClipboard
import com.honerai.admin.ui.common.presenceColor
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.common.rememberToastState
import com.honerai.admin.ui.home.TypingLabel
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Карточка пользователя: все сведения, блокировка, «Написать», «Отправить уведомление». */
@Composable
fun UserCardScreen(container: AdminContainer, navigator: Navigator, deviceId: String) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val users by container.repo.users.collectAsStateWithLifecycle()
    val summary = users.devices[deviceId]
    var detail by remember { mutableStateOf<DeviceDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var blockDialog by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var blockBusy by remember { mutableStateOf(false) }
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(deviceId, reload) {
        error = null
        try {
            val d = container.api.device(deviceId)
            detail = d
            container.repo.putDetail(d)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e, english)
        }
    }

    // Живые поля (присутствие, «печатает») берём из списка — он обновляется кадрами WebSocket.
    val d = detail
    val presence = summary?.presence ?: d?.presence ?: Presence.OFFLINE
    val lastSeen = summary?.lastSeen ?: d?.lastSeen
    val blocked = summary?.blocked ?: d?.blocked ?: false
    val name = summary?.title(english) ?: d?.summary()?.title(english) ?: ""
    val chatId = summary?.adminChatId?.takeIf { it.isNotEmpty() } ?: d?.adminChatId

    fun setBlocked(value: Boolean) {
        blockBusy = true
        scope.launch {
            try {
                val updated = container.api.setBlocked(deviceId, value, reason.takeIf { value })
                container.repo.setBlockedLocally(deviceId, value)
                if (updated != null) {
                    detail = updated
                    container.repo.putDetail(updated)
                } else {
                    detail = detail?.copy(blocked = value, blockReason = if (value) reason.trim().ifEmpty { null } else null)
                }
                toast.show(if (value) (if (english) "User blocked" else "Пользователь заблокирован") else (if (english) "User unblocked" else "Пользователь разблокирован"))
                reason = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(friendlyError(e, english))
            } finally {
                blockBusy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = name.ifEmpty { tr("Пользователь", "User") },
                onBack = { navigator.pop() },
                actions = { IconCircle(Icons.Rounded.Refresh, tr("Обновить", "Refresh"), { reload++ }) },
            )
            when {
                d == null && error != null -> ErrorPanel(error.orEmpty(), { reload++ }, Modifier.padding(top = 40.dp))
                d == null && summary == null -> LoadingBox()
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(20.dp))
                        Avatar(name, deviceId, if (blocked) null else presence, size = 88.dp, blocked = blocked)
                        Spacer(Modifier.height(12.dp))
                        Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.foreground, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        if (summary?.typingIn != null) {
                            TypingLabel(tr("печатает", "typing"), fontSize = 14)
                        } else {
                            Text(PresenceText.status(presence, Times.parse(lastSeen), now, zone, english), fontSize = 14.sp,
                                color = if (presence == Presence.OFFLINE) colors.secondary else presenceColor(presence))
                        }
                        if (blocked) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Block, null, tint = colors.danger, modifier = Modifier.size(16.dp))
                                Text(
                                    " " + tr("Заблокирован", "Blocked") + (d?.blockReason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                                    color = colors.danger, fontSize = 14.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(tr("Написать", "Message"), {
                                chatId?.let { navigator.push(Route.Chat(it, deviceId)) }
                            }, Modifier.weight(1f), enabled = chatId != null, icon = {
                                Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = colors.background, modifier = Modifier.size(19.dp))
                            })
                            SecondaryButton(tr("Уведомление", "Notify"), { navigator.push(Route.Broadcast(deviceId)) },
                                Modifier.weight(1f), icon = Icons.Rounded.NotificationsActive)
                        }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton(
                            if (blocked) tr("Разблокировать", "Unblock") else tr("Заблокировать", "Block"),
                            { blockDialog = true }, Modifier.fillMaxWidth(),
                            icon = if (blocked) Icons.Rounded.LockOpen else Icons.Rounded.Block,
                            tint = if (blocked) colors.online else colors.danger, enabled = !blockBusy,
                        )
                        Spacer(Modifier.height(20.dp))
                        InfoCard(d, summary?.messagesSent, summary?.secondsInApp, lastSeen, english, now, zone) { label, value ->
                            copyToClipboard(context, value)
                            toast.show((if (english) "Copied: " else "Скопировано: ") + label)
                        }
                        if (d == null && error != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(error.orEmpty(), color = colors.danger, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        ToastHost(toast, bottom = 40.dp)
    }

    if (blockDialog) {
        ConfirmDialog(
            title = if (blocked) tr("Разблокировать пользователя?", "Unblock user?") else tr("Заблокировать пользователя?", "Block user?"),
            text = if (blocked) tr("Пользователь снова сможет пользоваться приложением.", "The user will be able to use the app again.")
            else tr("Приложение пользователя покажет экран блокировки.", "The user's app will show the blocked screen."),
            confirm = if (blocked) tr("Разблокировать", "Unblock") else tr("Заблокировать", "Block"),
            destructive = !blocked,
            onDismiss = { blockDialog = false },
            onConfirm = { blockDialog = false; setBlocked(!blocked) },
            content = if (blocked) null else {
                { HonerField(reason, { reason = it }, tr("Причина (необязательно)", "Reason (optional)"), singleLine = false, minLines = 2) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoCard(
    d: DeviceDetail?,
    liveMessages: Long?,
    liveSeconds: Long?,
    lastSeen: String?,
    english: Boolean,
    now: java.time.Instant,
    zone: ZoneId,
    onCopy: (String, String) -> Unit,
) {
    val colors = HonerTheme.colors
    val none = "—"
    val rows = buildList {
        add(tr("Установлено", "Installed") to PresenceText.dateTime(Times.parse(d?.installedAt), zone))
        add(tr("Устройство", "Device") to listOfNotNull(d?.deviceModel?.takeIf { it.isNotBlank() }, d?.deviceName?.takeIf { it.isNotBlank() && it != d.deviceModel })
            .joinToString(" · ").ifEmpty { none })
        add(tr("Система", "OS") to listOfNotNull(d?.platform?.replaceFirstChar { it.uppercase() }, d?.osVersion).joinToString(" ").ifEmpty { none })
        add(tr("Версия приложения", "App version") to (d?.appVersion?.takeIf { it.isNotBlank() } ?: none))
        add(tr("Сообщений отправлено", "Messages sent") to (liveMessages ?: d?.messagesSent)?.toString().orEmpty().ifEmpty { none })
        add(tr("Время в приложении", "Time in app") to ((liveSeconds ?: d?.secondsInApp)?.let { PresenceText.duration(it, english) } ?: none))
        add(tr("Последний визит", "Last seen") to (Times.parse(lastSeen)?.let { PresenceText.ago(it, now, zone, english) } ?: none))
        add(tr("День рождения", "Birthday") to (d?.birthday?.let { formatBirthday(it, english) } ?: none))
        add(tr("Язык", "Language") to when (d?.language) {
            "ru" -> tr("Русский", "Russian")
            "en" -> tr("Английский", "English")
            null, "" -> none
            else -> d.language
        })
        add(tr("Лицензия принята", "License accepted") to PresenceText.dateTime(Times.parse(d?.licenseAcceptedAt), zone))
        add(tr("Установок (загрузок)", "Installs (downloads)") to (d?.installs?.toString() ?: none))
        add(tr("ID устройства", "Device ID") to (d?.deviceId ?: none))
    }
    SectionCard {
        rows.forEachIndexed { index, (label, value) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .combinedClickable(onClick = {}, onLongClick = { if (value != none) onCopy(label, value) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontSize = 15.sp, color = colors.secondary, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(12.dp))
                Text(value, fontSize = 15.sp, color = colors.foreground, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
            }
            if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(colors.divider))
        }
    }
}

/** «2008-05-01» → «01.05.2008 (17 лет)». */
private fun formatBirthday(raw: String, english: Boolean): String {
    val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
    val today = LocalDate.now()
    var age = today.year - date.year
    if (today.dayOfYear < date.withYear(today.year).dayOfYear) age--
    val word = when {
        english -> if (age == 1) "year" else "years"
        age % 10 == 1 && age % 100 != 11 -> "год"
        age % 10 in 2..4 && age % 100 !in 12..14 -> "года"
        else -> "лет"
    }
    return "%02d.%02d.%d (%d %s)".format(date.dayOfMonth, date.monthValue, date.year, age, word)
}
