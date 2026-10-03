package com.honerai.admin.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.AdminNotifications
import com.honerai.admin.core.LocalAttachment
import com.honerai.admin.core.MessageMerge
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.AttachmentRef
import com.honerai.admin.data.Message
import com.honerai.admin.data.Presence
import com.honerai.admin.data.Sender
import com.honerai.admin.data.title
import com.honerai.admin.net.ConnectionState
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.Avatar
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.ToastHost
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.copyToClipboard
import com.honerai.admin.ui.common.presenceColor
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.common.rememberToastState
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

private const val MAX_BYTES = 100L * 1024 * 1024
private const val MAX_ATTACHMENTS = 10

/** Переписка с пользователем (как в Telegram): лента, ответы, реакции, закреп, вложения, голосовые, ИИ в чате. */
@Composable
fun ChatScreen(container: AdminContainer, navigator: Navigator, chatId: String, deviceIdHint: String?) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val controller = remember(chatId) { container.chat(chatId) }
    val state by controller.state.collectAsStateWithLifecycle()
    val users by container.repo.users.collectAsStateWithLifecycle()
    val connection by container.realtime.state.collectAsStateWithLifecycle()
    val device = users.devices[deviceIdHint ?: ""] ?: users.devices.values.firstOrNull { it.adminChatId == chatId }
    val deviceId = device?.deviceId ?: deviceIdHint ?: state.chat?.deviceId?.takeIf { it.isNotEmpty() }
    val name = device?.title(english) ?: state.chat?.title?.takeIf { it.isNotBlank() } ?: tr("Пользователь", "User")
    val zone = remember { ZoneId.systemDefault() }
    val now = rememberNow()

    // Свой adminId — чтобы отличать «моё» обращение от чужого (п.16). Запрашиваем один раз.
    var myAdminId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { myAdminId = runCatching { container.api.account().adminId }.getOrNull() }
    val assignedId = state.chat?.assignedAdminId
    val assignedName = state.chat?.assignedAdminName
    val assignedAt = state.chat?.assignedAt
    val assignedToMe = assignedId != null && assignedId == myAdminId
    val assignedToOther = assignedId != null && assignedId != myAdminId

    var text by rememberSaveable(chatId) { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var draftBeforeEdit by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<LocalAttachment>() }
    var viewer by remember { mutableStateOf<AttachmentRef?>(null) }
    var confirmClear by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    var menu by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<String?>(null) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val listState = rememberLazyListState()

    LaunchedEffect(chatId) {
        controller.load()
        AdminNotifications.cancel(context, chatId)
    }
    LaunchedEffect(controller) { controller.toasts.collect { toast.show(it) } }
    DisposableEffect(chatId) {
        onDispose {
            controller.stopTyping()
            recorder.cancel()
        }
    }

    // Лента: строки строятся вне композиции (разбор дат), первая сборка — сразу.
    val today = remember(now) { now.atZone(zone).toLocalDate() }
    var rows by remember { mutableStateOf(ChatRows.build(state.items, zone, today, english)) }
    LaunchedEffect(state.items, today, english) {
        rows = withContext(Dispatchers.Default) { ChatRows.build(state.items, zone, today, english) }
    }
    val reversed = remember(rows) { rows.asReversed() }
    val byId = remember(state.items) { state.items.associateBy { it.message.id } }
    val typingWho = when {
        state.aiTyping -> Sender.AI
        state.userTyping -> Sender.USER
        else -> null
    }
    val typingOffset = if (typingWho != null) 1 else 0

    // Прочтения и «открытый чат» — только пока экран действительно на виду.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }
    val lastPeer = remember(state.items) { MessageMerge.lastPeerMessageId(state.items) }
    LaunchedEffect(lastPeer, atBottom, resumed, connection) {
        if (atBottom && resumed && state.loaded) controller.markRead()
    }
    DisposableEffect(resumed) {
        if (resumed) container.repo.visibleChatId = chatId
        onDispose { if (container.repo.visibleChatId == chatId) container.repo.visibleChatId = null }
    }

    // Новое сообщение внизу: если были внизу (или это наше) — прокручиваем к нему.
    val newest = rows.lastOrNull()
    LaunchedEffect(newest?.key, typingWho) {
        val mineNewest = (newest as? ChatRow.Msg)?.item?.message?.sender == Sender.ADMIN
        if (listState.firstVisibleItemIndex <= 3 || mineNewest) listState.animateScrollToItem(0)
    }
    // Подгрузка старых сообщений у верхнего края.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }.distinctUntilChanged().filter { it }.collect { controller.loadOlder() }
    }

    fun scrollToMessage(id: String) {
        val index = reversed.indexOfFirst { it is ChatRow.Msg && it.item.message.id == id }
        if (index < 0) {
            toast.show(if (english) "The message is not loaded yet" else "Сообщение ещё не загружено")
            return
        }
        scope.launch {
            listState.animateScrollToItem(index + typingOffset)
            highlight = id
            kotlinx.coroutines.delay(1_400)
            highlight = null
        }
    }

    fun addPicked(uris: List<Uri>, kind: String? = null) {
        if (uris.isEmpty()) return
        scope.launch {
            for (uri in uris) {
                if (attachments.size >= MAX_ATTACHMENTS) {
                    toast.show(if (english) "Up to $MAX_ATTACHMENTS attachments" else "Не больше $MAX_ATTACHMENTS вложений")
                    break
                }
                val local = resolveLocal(context, uri, kind)
                when {
                    local == null -> toast.show(if (english) "Cannot read the file" else "Не удалось прочитать файл")
                    local.size > MAX_BYTES -> toast.show(if (english) "The file is larger than 100 MB" else "Файл больше 100 МБ")
                    else -> attachments += local
                }
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { addPicked(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addPicked(it) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addPicked(it, AttachmentKinds.AUDIO) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        toast.show(
            if (granted) (if (english) "Now hold the mic button to record" else "Теперь удерживайте кнопку микрофона")
            else (if (english) "Microphone access denied" else "Нет доступа к микрофону"),
        )
    }

    val actions = remember(controller) {
        BubbleActions(
            onReply = { replyTo = it; editing = null },
            onEdit = { m ->
                if (editing == null) draftBeforeEdit = text
                editing = m; replyTo = null; text = m.text
            },
            onCopy = { copyToClipboard(context, it.text); toast.show(if (english) "Copied" else "Скопировано") },
            onReact = { m, e -> controller.react(m, e) },
            onPin = { m, p -> controller.pin(m, p) },
            onDelete = { m, everyone -> if (everyone) confirmDelete = m else controller.delete(m, false) },
            onRetry = { controller.retry(it) },
            onDiscard = { controller.discard(it) },
            onOpenAttachment = { ref ->
                when (ref.kind) {
                    AttachmentKinds.IMAGE, AttachmentKinds.VIDEO -> viewer = ref
                    AttachmentKinds.VOICE, AttachmentKinds.AUDIO -> Unit
                    else -> scope.launch {
                        toast.show(if (english) "Opening…" else "Открываю…")
                        val ok = runCatching { openAttachment(context, container, ref) { } }.getOrElse {
                            toast.show(com.honerai.admin.net.friendlyError(it, english)); return@launch
                        }
                        if (!ok) toast.show(if (english) "No app to open this file" else "Нет приложения, чтобы открыть этот файл")
                    }
                }
            },
            onQuoteClick = { scrollToMessage(it) },
        )
    }

    fun send() {
        editing?.let { m ->
            controller.edit(m, text)
            editing = null
            text = draftBeforeEdit
            draftBeforeEdit = ""
            return
        }
        controller.send(text, attachments.toList(), replyTo?.id)
        text = ""
        attachments.clear()
        replyTo = null
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // --- Шапка -----------------------------------------------------------------------------------
            val presence = device?.presence ?: state.chat?.peerPresence ?: Presence.OFFLINE
            val lastSeen = device?.lastSeen ?: state.chat?.peerLastSeen
            val (subtitle, subtitleColor) = when {
                state.aiTyping -> tr("Honer AI печатает…", "Honer AI is typing…") to colors.accent
                state.userTyping || device?.typingIn == chatId -> tr("печатает…", "typing…") to colors.accent
                connection != ConnectionState.CONNECTED -> tr("соединение…", "connecting…") to colors.secondary
                else -> PresenceText.status(presence, Times.parse(lastSeen), now, zone, english) to
                    (if (presence == Presence.OFFLINE) colors.secondary else presenceColor(presence))
            }
            val aiEnabled = state.chat?.aiEnabled == true
            TopBar(
                title = name,
                subtitle = subtitle,
                subtitleColor = subtitleColor,
                onBack = { navigator.pop() },
                leading = { Avatar(name, deviceId ?: chatId, if (device?.blocked == true) null else presence, size = 38.dp, blocked = device?.blocked == true) },
                onTitleClick = deviceId?.let { id -> { navigator.push(Route.User(id)) } },
                actions = {
                    IconCircle(Icons.Rounded.AutoAwesome,
                        if (aiEnabled) tr("Убрать ИИ из чата", "Remove AI from chat") else tr("Добавить ИИ в чат", "Add AI to chat"),
                        { controller.setAi(!aiEnabled) }, tint = if (aiEnabled) colors.accent else colors.secondary, enabled = !state.aiBusy && state.loaded)
                    Box {
                        IconCircle(Icons.Rounded.MoreVert, tr("Ещё", "More"), { menu = true })
                        DropdownMenu(
                            expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(22.dp),
                            containerColor = colors.sidebar, border = BorderStroke(0.8.dp, colors.divider),
                        ) {
                            if (deviceId != null) HeaderItem(Icons.Rounded.Person, tr("Карточка пользователя", "User info")) {
                                menu = false; navigator.push(Route.User(deviceId))
                            }
                            HeaderItem(Icons.Rounded.AutoAwesome, if (aiEnabled) tr("Убрать ИИ из чата", "Remove AI from chat") else tr("Добавить ИИ в чат", "Add AI to chat")) {
                                menu = false; controller.setAi(!aiEnabled)
                            }
                            HeaderItem(Icons.Rounded.DeleteSweep, tr("Очистить у себя", "Clear for me")) { menu = false; confirmClear = false }
                            HeaderItem(Icons.Rounded.DeleteSweep, tr("Очистить у всех", "Clear for everyone"), danger = true) { menu = false; confirmClear = true }
                        }
                    }
                },
            )
            if (aiEnabled) GroupBanner()
            // Назначение обращения (п.16): кто взял в работу, таймер, кнопка «Взять/Освободить/Перехватить».
            if (state.loaded) AssignBanner(
                assignedName = assignedName,
                assignedAt = assignedAt,
                assignedToMe = assignedToMe,
                assignedToOther = assignedToOther,
                now = now,
                english = english,
                onTake = { controller.assign(true) },
                onRelease = { controller.assign(false) },
            )
            val pinned = state.chat?.pinnedMessageId?.let { byId[it]?.message }
                ?: state.items.lastOrNull { it.message.pinned && !it.message.deleted }?.message
            if (pinned != null) PinnedBar(pinned, english, onClick = { scrollToMessage(pinned.id) }, onUnpin = { controller.pin(pinned, false) })

            // --- Лента -----------------------------------------------------------------------------------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.loaded && state.error != null -> ErrorPanel(state.error.orEmpty(), { controller.load(force = true) }, Modifier.align(Alignment.Center))
                    !state.loaded -> LoadingBox()
                    state.items.isEmpty() && typingWho == null -> EmptyState(Icons.AutoMirrored.Rounded.Chat,
                        tr("Сообщений пока нет. Напишите первым!", "No messages yet. Say hi!"), Modifier.align(Alignment.Center))
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    ) {
                        if (typingWho != null) item(key = "typing", contentType = "typing") { TypingBubble(typingWho) }
                        items(reversed, key = { it.key }, contentType = { if (it is ChatRow.Day) 1 else 2 }) { row ->
                            when (row) {
                                is ChatRow.Day -> DayChip(row.label)
                                is ChatRow.Msg -> MessageBubble(
                                    row = row,
                                    replied = row.item.message.replyTo?.let { byId[it]?.message },
                                    uploadProgress = state.uploads[row.item.message.clientId],
                                    container = container,
                                    zone = zone,
                                    actions = actions,
                                    highlighted = highlight == row.item.message.id,
                                )
                            }
                        }
                        if (state.loadingOlder) item(key = "older", contentType = "older") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.accent)
                            }
                        }
                    }
                }
                val showDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
                androidx.compose.animation.AnimatedVisibility(showDown, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(colors.raised).border(0.7.dp, colors.divider, CircleShape)
                            .clickable { scope.launch { listState.animateScrollToItem(0) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.KeyboardArrowDown, tr("Вниз", "Scroll down"), tint = colors.foreground) }
                }
            }

            // --- Ввод ------------------------------------------------------------------------------------
            if (assignedToOther) ReadOnlyAssignedBar(assignedName, english) { controller.assign(true) }
            else ChatComposer(
                container = container,
                text = text,
                onText = { text = it; if (editing == null) controller.onInput(it) },
                replyTo = replyTo,
                onCancelReply = { replyTo = null },
                editing = editing,
                onCancelEdit = { editing = null; text = draftBeforeEdit; draftBeforeEdit = "" },
                attachments = attachments,
                onRemoveAttachment = { attachments.remove(it) },
                onAttach = { source ->
                    runCatching {
                        when (source) {
                            AttachSource.MEDIA -> mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            AttachSource.FILE -> filePicker.launch(arrayOf("*/*"))
                            AttachSource.AUDIO -> audioPicker.launch(arrayOf("audio/*"))
                        }
                    }.onFailure { toast.show(if (english) "No app to pick files" else "Нет приложения для выбора файлов") }
                },
                onSend = ::send,
                recorder = recorder,
                hasMicPermission = {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                },
                requestMicPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onVoice = { file: File, duration: Long ->
                    val voice = LocalAttachment(Uri.fromFile(file).toString(), "voice.m4a", "audio/mp4", file.length(), AttachmentKinds.VOICE, duration)
                    controller.send("", listOf(voice), replyTo?.id)
                    replyTo = null
                },
                onHint = { toast.show(it) },
            )
        }
        ToastHost(toast, bottom = 110.dp)
    }

    viewer?.let { ref ->
        if (ref.kind == AttachmentKinds.VIDEO) VideoPlayerDialog(container, ref) { viewer = null }
        else ImageViewerDialog(container, ref) { viewer = null }
    }
    confirmClear?.let { everyone ->
        ConfirmDialog(
            title = if (everyone) tr("Очистить чат у всех?", "Clear chat for everyone?") else tr("Очистить чат у себя?", "Clear chat for you?"),
            text = if (everyone) tr("Сообщения исчезнут и у пользователя. Это нельзя отменить.", "Messages will disappear for the user too. This can't be undone.")
            else tr("Сообщения пропадут только в админке.", "Messages will be removed only in the admin app."),
            confirm = tr("Очистить", "Clear"), destructive = true,
            onDismiss = { confirmClear = null },
            onConfirm = { confirmClear = null; controller.clear(everyone) },
        )
    }
    confirmDelete?.let { m ->
        ConfirmDialog(
            title = tr("Удалить у всех?", "Delete for everyone?"),
            text = tr("Сообщение исчезнет и у пользователя.", "The message will be removed for the user too."),
            confirm = tr("Удалить", "Delete"), destructive = true,
            onDismiss = { confirmDelete = null },
            onConfirm = { confirmDelete = null; controller.delete(m, true) },
        )
    }
}

@Composable
private fun HeaderItem(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (danger) colors.danger else colors.foreground
    DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = tint, leadingIconColor = tint),
    )
}

@Composable
private fun DayChip(label: String) {
    val colors = HonerTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.secondary,
            modifier = Modifier.clip(CircleShape).background(colors.surface).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** Строка назначения обращения (п.16): состояние + действие. */
@Composable
private fun AssignBanner(
    assignedName: String?,
    assignedAt: String?,
    assignedToMe: Boolean,
    assignedToOther: Boolean,
    now: java.time.Instant,
    english: Boolean,
    onTake: () -> Unit,
    onRelease: () -> Unit,
) {
    val colors = HonerTheme.colors
    val timer = remember(assignedAt, now) { elapsedSince(assignedAt, now, english) }
    val (tint, label, action, onAction) = when {
        assignedToMe -> Quad(colors.online,
            (if (english) "You took this chat" else "Вы взяли обращение") + (if (timer.isNotEmpty()) " · $timer" else ""),
            tr("Освободить", "Release"), onRelease)
        assignedToOther -> Quad(colors.danger,
            (if (english) "In work: " else "В работе: ") + (assignedName ?: (if (english) "another admin" else "другой админ")) + (if (timer.isNotEmpty()) " · $timer" else ""),
            tr("Перехватить", "Take over"), onTake)
        else -> Quad(colors.secondary, tr("Обращение свободно", "Chat is unassigned"),
            tr("Взять в работу", "Take"), onTake)
    }
    Row(
        Modifier.fillMaxWidth().background(tint.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Вместо композера, если обращение в работе у другого админа: только чтение + «Перехватить». */
@Composable
private fun ReadOnlyAssignedBar(assignedName: String?, english: Boolean, onTakeOver: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Person, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            (if (english) "Only reading — in work: " else "Только чтение — в работе: ") + (assignedName ?: (if (english) "another admin" else "другой админ")),
            fontSize = 13.sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(tr("Перехватить", "Take over"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTakeOver).padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

private data class Quad(val tint: androidx.compose.ui.graphics.Color, val label: String, val action: String, val onAction: () -> Unit)

/** «5 мин», «1 ч 20 м» с момента assignedAt. Пусто, если нет времени. */
private fun elapsedSince(iso: String?, now: java.time.Instant, english: Boolean): String {
    val start = Times.parse(iso) ?: return ""
    val mins = java.time.Duration.between(start, now).toMinutes().coerceAtLeast(0)
    return when {
        mins < 1L -> if (english) "just now" else "только что"
        mins < 60L -> if (english) "$mins min" else "$mins мин"
        else -> {
            val h = mins / 60; val m = mins % 60
            if (english) "${h}h ${m}m" else "${h}ч ${m}м"
        }
    }
}

@Composable
private fun GroupBanner() {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.accent.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr("Группа: вы + пользователь + Honer AI", "Group: you + user + Honer AI"), fontSize = 13.sp, color = colors.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PinnedBar(message: Message, english: Boolean, onClick: () -> Unit, onUnpin: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.surface).clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Закреплённое сообщение", "Pinned message"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            Text(messagePreview(message, english), fontSize = 14.sp, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onUnpin), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, tr("Открепить", "Unpin"), tint = colors.secondary, modifier = Modifier.size(18.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.divider.copy(alpha = 0.6f)))
}
