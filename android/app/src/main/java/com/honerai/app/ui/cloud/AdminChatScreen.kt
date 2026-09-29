package com.honerai.app.ui.cloud

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.honerai.app.AppContainer
import com.honerai.app.cloud.ChatEntry
import com.honerai.app.cloud.ChatMerge
import com.honerai.app.cloud.CloudAttachment
import com.honerai.app.cloud.CloudAudio
import com.honerai.app.cloud.CloudManager
import com.honerai.app.cloud.CloudMedia
import com.honerai.app.cloud.LocalAttachment
import com.honerai.app.cloud.MessagePreview
import com.honerai.app.cloud.PresenceText
import com.honerai.app.cloud.VoiceRecorder
import com.honerai.app.ui.common.LocalChatFontScale
import com.honerai.app.ui.common.ToastBubble
import com.honerai.app.ui.common.copyToClipboard
import com.honerai.app.ui.common.openFileExternally
import com.honerai.app.ui.common.rememberHaptics
import com.honerai.app.ui.common.rememberToastState
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Строка ленты: разделитель дня или сообщение. */
private sealed class ChatRow {
    abstract val key: String
    data class Day(override val key: String, val title: String) : ChatRow()
    data class Item(val entry: ChatEntry, val sameAuthorAsPrevious: Boolean) : ChatRow() {
        override val key: String get() = entry.key
    }
}

/** Лента в хронологическом порядке с разделителями дней. */
private fun buildRows(entries: List<ChatEntry>, english: Boolean, zone: ZoneId): List<ChatRow> {
    val today = LocalDate.now(zone)
    val rows = ArrayList<ChatRow>(entries.size + 8)
    var lastDay: LocalDate? = null
    var lastSender: String? = null
    for (entry in entries) {
        val time = entry.timeMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        if (day != lastDay) {
            rows.add(ChatRow.Day("day:$day", PresenceText.dayTitle(day, today, english)))
            lastDay = day
            lastSender = null
        }
        rows.add(ChatRow.Item(entry, lastSender == entry.message.sender))
        lastSender = entry.message.sender
    }
    return rows
}

/**
 * Официальный чат с администратором Honer AI (как в Telegram): шапка с красной галочкой и статусом,
 * закреплённое сообщение, ответы, реакции, вложения, голосовые, ✓/✓✓, отметки «прочитано».
 */
@Composable
fun AdminChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val english = container.settings.isEnglish
    fun t(ru: String, en: String) = if (english) en else ru
    val colors = HonerTheme.colors
    val fontScale = LocalChatFontScale.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val toast = rememberToastState()
    val density = LocalDensity.current

    val entries by CloudManager.entries.collectAsState()
    val chat by CloudManager.chat.collectAsState()
    val peerTyping by CloudManager.peerTyping.collectAsState()
    val aiTyping by CloudManager.aiTyping.collectAsState()
    val presence by CloudManager.peerPresence.collectAsState()
    val lastSeen by CloudManager.peerLastSeen.collectAsState()
    val peerReadUpTo by CloudManager.peerReadUpTo.collectAsState()
    val uploads by CloudManager.uploads.collectAsState()
    val hasMore by CloudManager.hasMoreHistory.collectAsState()
    val loadingOlder by CloudManager.loadingOlder.collectAsState()
    val loading by CloudManager.loading.collectAsState()
    val unread by CloudManager.unread.collectAsState()

    var draft by rememberSaveable { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatEntry?>(null) }
    var editing by remember { mutableStateOf<ChatEntry?>(null) }
    val picked = remember { mutableStateListOf<LocalAttachment>() }
    var viewer by remember { mutableStateOf<ViewerItem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<Pair<ChatEntry, Boolean>?>(null) }
    var infoVisible by remember { mutableStateOf(!CloudManager.credentials.infoBannerDismissed) }
    var importing by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    DisposableEffect(Unit) {
        CloudManager.setChatVisible(true)
        onDispose {
            CloudManager.setChatVisible(false)
            CloudAudio.stop()
        }
    }
    LaunchedEffect(Unit) { CloudManager.refreshChat() }

    val zone = remember { ZoneId.systemDefault() }
    val rows = remember(entries, english) { buildRows(entries, english, zone).asReversed() }
    val byId = remember(entries) { entries.associateBy { it.message.id } }
    val listState = rememberLazyListState()
    val pinned = remember(entries, chat?.pinnedMessageId) { ChatMerge.pinned(entries, chat?.pinnedMessageId) }

    // Подгрузка старых сообщений у верхнего края ленты.
    val nearTop by remember { derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
    } }
    LaunchedEffect(nearTop, hasMore, entries.size) { if (nearTop && hasMore && entries.isNotEmpty()) CloudManager.loadOlder() }

    // Видимые сообщения администратора — прочитаны (кадр read уходит один раз на новое сообщение).
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                val newest = indices.mapNotNull { latestRows.getOrNull(it) as? ChatRow.Item }
                    .map { it.entry }.filter { !it.pending && !it.message.fromUser }
                    .maxByOrNull { it.timeMs } ?: return@collect
                CloudManager.markReadUpTo(newest)
            }
    }
    // Пришло новое сообщение — если пользователь внизу, лента остаётся внизу (reverseLayout), иначе кнопка «вниз».
    LaunchedEffect(entries.lastOrNull()?.key) {
        val last = entries.lastOrNull() ?: return@LaunchedEffect
        if (last.message.fromUser || listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
        if (!last.message.fromUser && listState.firstVisibleItemIndex <= 1) CloudManager.markReadUpTo(last)
    }

    fun jumpTo(messageId: String) {
        val index = rows.indexOfFirst { it is ChatRow.Item && it.entry.message.id == messageId }
        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
        else toast.show(t("Сообщение выше в истории — прокрутите вверх", "The message is further up — scroll up"))
    }

    val save = rememberSaver(english) { toast.show(it) }
    val actions = remember {
        BubbleActions(
            onReply = { editing = null; replyTo = it },
            onEdit = { entry -> replyTo = null; picked.clear(); editing = entry; draft = entry.message.text },
            onCopy = { copyToClipboard(context, it.message.text); toast.show(if (english) "Copied" else "Скопировано") },
            onReact = { entry, emoji -> haptics.light(); CloudManager.react(entry, emoji) },
            onPin = { entry, value -> CloudManager.setPinned(entry, value) },
            onDelete = { entry, everyone -> deleteRequest = entry to everyone },
            onRetry = { entry -> entry.message.clientId?.let { CloudManager.retry(it) } },
            onOpenMedia = { attachment, local -> viewer = ViewerItem(attachment, local) },
            onSave = { attachment, local -> save(attachment, local) },
            onOpenFile = { attachment, local ->
                scope.launch {
                    toast.show(if (english) "Opening…" else "Открываю…")
                    val file = runCatching { CloudMedia.localFile(attachment, local) }.getOrNull()
                    if (file == null || !openFileExternally(context, file)) {
                        toast.show(if (english) "No app to open this file" else "Нет приложения, чтобы открыть этот файл")
                    }
                }
            },
            onJumpTo = { jumpTo(it) },
        )
    }

    // ---- Выбор файлов ----
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importing = true
        scope.launch {
            for (uri in uris.take(10)) {
                val result = runCatching { CloudMedia.importUri(context, uri) }
                result.onSuccess { picked.add(it) }.onFailure { error ->
                    toast.show(if (error.message == "too_large") t("Файл больше 100 МБ", "The file is larger than 100 MB")
                    else t("Не удалось прикрепить файл", "Could not attach the file"))
                }
            }
            importing = false
        }
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { import(it) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }

    // ---- Голосовые ----
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var slide by remember { mutableStateOf(0f) }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording) {
        while (recording) { elapsed = recorder.elapsedMs(); delay(100) }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        toast.show(if (granted) t("Удерживайте микрофон, чтобы записать", "Hold the mic to record")
        else t("Разрешите доступ к микрофону для голосовых", "Allow microphone access for voice messages"))
    }
    val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    fun send() {
        editing?.let { target ->
            CloudManager.edit(target, draft)
            editing = null
            draft = ""
            CloudManager.stopTyping()
            return
        }
        val text = draft
        if (text.isBlank() && picked.isEmpty()) return
        CloudManager.send(text, picked.toList(), replyTo?.message?.id)
        draft = ""
        picked.clear()
        replyTo = null
        scope.launch { listState.animateScrollToItem(0) }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            // ---- Шапка ----
            Row(
                Modifier.fillMaxWidth().background(colors.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .height(60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onBack)
                        .semantics { contentDescription = t("Назад", "Back") }.testTag("admin.back"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = colors.foreground) }
                AdminAvatar(40.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(t("Администратор", "Administrator"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                        VerifiedBadge(16.dp)
                    }
                    val subtitle = PresenceText.subtitle(presence, peerTyping, aiTyping, lastSeen, now, english)
                    val live = peerTyping || aiTyping || presence == "foreground"
                    Text(subtitle, fontSize = 13.sp, color = if (live) colors.accent else colors.secondary, maxLines = 1,
                        modifier = Modifier.testTag("admin.subtitle"))
                }
                Box {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable { menuOpen = true }
                            .semantics { contentDescription = t("Ещё", "More") }.testTag("admin.menu"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.MoreVert, null, tint = colors.foreground) }
                    DropdownMenu(menuOpen, { menuOpen = false }, shape = RoundedCornerShape(16.dp), containerColor = colors.surface) {
                        DropdownMenuItem(
                            text = { Text(t("Очистить историю у меня", "Clear history for me"), color = Color(0xFFFF453A)) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = Color(0xFFFF453A)) },
                            onClick = { menuOpen = false; clearConfirm = true },
                            modifier = Modifier.testTag("admin.menu.clear"),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider, thickness = 0.6.dp)

            // Группа: администратор добавил Honer AI в чат.
            AnimatedVisibility(chat?.aiEnabled == true, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp)
                            .testTag("admin.group"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.Groups, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                        Text(t("Группа: вы, администратор и Honer AI", "Group: you, the administrator and Honer AI"),
                            fontSize = 12.sp, color = colors.accent, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // Закреплённое сообщение.
            AnimatedVisibility(pinned != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val value = pinned
                if (value != null) {
                    Row(
                        Modifier.fillMaxWidth().background(colors.surface).clickable { jumpTo(value.message.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp).testTag("admin.pinned"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.width(3.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(AdminRed))
                        Column(Modifier.weight(1f)) {
                            Text(t("Закреплённое сообщение", "Pinned message"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AdminRed)
                            Text(MessagePreview.text(value.message, english), fontSize = 13.sp, color = colors.foreground, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.PushPin, t("Открепить", "Unpin"), tint = colors.secondary,
                            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { CloudManager.setPinned(value, false) }.padding(5.dp))
                    }
                }
            }

            // Разовая плашка о безопасности.
            AnimatedVisibility(infoVisible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    InfoBanner(english) {
                        infoVisible = false
                        CloudManager.credentials.infoBannerDismissed = true
                    }
                }
            }

            // ---- Лента ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().testTag("admin.list"),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp),
                ) {
                    items(rows, key = { it.key }, contentType = { if (it is ChatRow.Day) 0 else 1 }) { row ->
                        when (row) {
                            is ChatRow.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(row.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.secondary,
                                    modifier = Modifier.clip(CircleShape).background(colors.surface).padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                            is ChatRow.Item -> Column(Modifier.padding(top = gap(row.sameAuthorAsPrevious))) {
                                val entry = row.entry
                                MessageBubble(
                                    entry = entry,
                                    replied = entry.message.replyTo?.let { byId[it] },
                                    read = ChatMerge.isReadByPeer(entry, peerReadUpTo),
                                    progress = entry.message.clientId?.let { uploads[it] },
                                    group = chat?.aiEnabled == true,
                                    english = english,
                                    fontScale = fontScale,
                                    actions = actions,
                                )
                            }
                        }
                    }
                    // Верх ленты (reverseLayout: последний элемент — сверху): плашка и загрузка истории.
                    item(key = "top") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (loadingOlder) CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            if (entries.isEmpty() && !loading) {
                                Spacer(Modifier.height(24.dp))
                                AdminAvatar(64.dp)
                                Spacer(Modifier.height(12.dp))
                                Text(t("Напишите администратору Honer AI — он ответит здесь.",
                                    "Write to the Honer AI administrator — the answer will appear here."),
                                    fontSize = 14.sp, color = colors.secondary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                if (loading && entries.isEmpty()) CircularProgressIndicator(color = colors.accent, modifier = Modifier.align(Alignment.Center))
                // Кнопка «вниз» с числом непрочитанных.
                val away by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
                androidx.compose.animation.AnimatedVisibility(away, modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp), enter = fadeIn(), exit = fadeOut()) {
                    Box {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(colors.raised).border(0.7.dp, colors.divider, CircleShape)
                                .clickable { scope.launch { listState.animateScrollToItem(0) } }
                                .semantics { contentDescription = t("К последним сообщениям", "Scroll to latest") },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = colors.foreground) }
                        UnreadBadge(unread, Modifier.align(Alignment.TopEnd))
                    }
                }
                androidx.compose.animation.AnimatedVisibility(toast.message != null, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    enter = fadeIn(), exit = fadeOut()) {
                    ToastBubble(toast.message.orEmpty())
                }
            }

            // ---- Низ: ответ, выбранные файлы, поле ввода ----
            Column(
                Modifier.fillMaxWidth().background(colors.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
            ) {
                HorizontalDivider(color = colors.divider, thickness = 0.6.dp)
                editing?.let { target ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp).testTag("admin.editing"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Edit, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("Редактирование", "Editing"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
                            Text(target.message.text, fontSize = 13.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.Close, t("Отменить", "Cancel"), tint = colors.secondary,
                            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { editing = null; draft = "" }.padding(7.dp))
                    }
                }
                replyTo?.let { target ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("В ответ: ", "Reply to ") + senderName(target.message, english), fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.accent)
                            Text(MessagePreview.text(target.message, english), fontSize = 13.sp, color = colors.secondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.Close, t("Отменить ответ", "Cancel reply"), tint = colors.secondary,
                            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { replyTo = null }.padding(7.dp))
                    }
                }
                if (picked.isNotEmpty()) PendingAttachmentsTray(picked, english) { picked.remove(it) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (recording) {
                        RecordingStrip(elapsed, slide, english, Modifier.weight(1f).heightIn(min = 46.dp))
                    } else {
                        Box {
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).clickable { attachMenu = true }
                                    .semantics { contentDescription = t("Прикрепить", "Attach") }.testTag("admin.attach"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (importing) CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.AttachFile, null, tint = colors.secondary)
                            }
                            DropdownMenu(attachMenu, { attachMenu = false }, shape = RoundedCornerShape(16.dp), containerColor = colors.surface) {
                                DropdownMenuItem(text = { Text(t("Фото и видео", "Photos and videos")) },
                                    leadingIcon = { Icon(Icons.Rounded.Photo, null, tint = colors.accent) },
                                    onClick = {
                                        attachMenu = false
                                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    }, modifier = Modifier.testTag("admin.attach.media"))
                                DropdownMenuItem(text = { Text(t("Музыка и аудио", "Music and audio")) },
                                    leadingIcon = { Icon(Icons.Rounded.MusicNote, null, tint = colors.accent) },
                                    onClick = { attachMenu = false; documentPicker.launch(arrayOf("audio/*")) },
                                    modifier = Modifier.testTag("admin.attach.audio"))
                                DropdownMenuItem(text = { Text(t("Файл", "File")) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = colors.accent) },
                                    onClick = { attachMenu = false; documentPicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.testTag("admin.attach.file"))
                            }
                        }
                        Box(
                            Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(colors.background)
                                .border(0.7.dp, colors.divider, RoundedCornerShape(23.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (draft.isEmpty()) Text(t("Сообщение", "Message"), fontSize = (16 * fontScale).sp, color = colors.secondary)
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it; CloudManager.onComposerInput(it) },
                                textStyle = TextStyle(color = colors.foreground, fontSize = (16 * fontScale).sp, lineHeight = (21 * fontScale).sp),
                                cursorBrush = SolidColor(colors.accent),
                                maxLines = 6,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                modifier = Modifier.fillMaxWidth().testTag("admin.composer"),
                            )
                        }
                    }
                    val canSend = draft.isNotBlank() || picked.isNotEmpty()
                    if (canSend && !recording) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(colors.accent).clickable { send() }
                                .semantics { contentDescription = t("Отправить", "Send") }.testTag("admin.send"),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
                    } else {
                        MicButton(
                            recording = recording,
                            english = english,
                            cancelDistancePx = with(density) { 110.dp.toPx() },
                            hasPermission = hasMic,
                            onNeedPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            onStart = {
                                CloudAudio.stop()
                                if (recorder.start()) { recording = true; slide = 0f; elapsed = 0L; haptics.medium() }
                                else toast.show(t("Микрофон недоступен", "The microphone is unavailable"))
                            },
                            onSlide = { slide = it },
                            onFinish = { cancelled ->
                                if (!recording) return@MicButton
                                recording = false
                                if (cancelled) { recorder.cancel(); haptics.light(); toast.show(t("Запись отменена", "Recording cancelled")) }
                                else {
                                    val voice = recorder.stop()
                                    if (voice == null) toast.show(t("Удерживайте кнопку, чтобы записать голосовое", "Hold the button to record a voice message"))
                                    else { CloudManager.send("", listOf(voice), replyTo?.message?.id); replyTo = null
                                        scope.launch { listState.animateScrollToItem(0) } }
                                }
                            },
                        )
                    }
                }
            }
        }

        viewer?.let { item ->
            CloudMediaViewer(item, english, onSave = { attachment, local -> save(attachment, local) }, onClose = { viewer = null })
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(t("Очистить историю?", "Clear history?")) },
            text = { Text(t("Сообщения удалятся только у вас. У администратора переписка останется.",
                "Messages are removed only for you. The administrator keeps the conversation.")) },
            confirmButton = {
                TextButton(onClick = { clearConfirm = false; CloudManager.clearForMe() }, modifier = Modifier.testTag("admin.clear.confirm")) {
                    Text(t("Очистить", "Clear"), color = Color(0xFFFF453A))
                }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(t("Отмена", "Cancel"), color = colors.accent) } },
            containerColor = colors.surface,
        )
    }
    deleteRequest?.let { (entry, everyone) ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text(if (everyone) t("Удалить у всех?", "Delete for everyone?") else t("Удалить у вас?", "Delete for you?")) },
            text = { Text(if (everyone) t("Сообщение исчезнет и у администратора.", "The message will also disappear for the administrator.")
                else t("Сообщение удалится только в этом приложении.", "The message is deleted only in this app.")) },
            confirmButton = {
                TextButton(onClick = { deleteRequest = null; CloudManager.delete(entry, everyone) }, modifier = Modifier.testTag("admin.delete.confirm")) {
                    Text(t("Удалить", "Delete"), color = Color(0xFFFF453A))
                }
            },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text(t("Отмена", "Cancel"), color = colors.accent) } },
            containerColor = colors.surface,
        )
    }
}

/** Разовая плашка о безопасности официального чата. */
@Composable
private fun InfoBanner(english: Boolean, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(AdminRed.copy(alpha = 0.09f)).border(0.7.dp, AdminRed.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp).testTag("admin.info"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.VerifiedUser, null, tint = AdminRed, modifier = Modifier.size(20.dp))
            Text(if (english) "Official chat" else "Официальный чат", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
        }
        Text(
            if (english) "This is the official chat with the Honer AI administrator. The administrator will never ask for passwords or codes."
            else "Это официальный чат с администратором Honer AI. Администратор никогда не попросит пароли и коды.",
            fontSize = 14.sp, lineHeight = 19.sp, color = colors.foreground,
        )
        Text(if (english) "Got it" else "Понятно", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AdminRed,
            modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp).testTag("admin.info.dismiss"))
    }
}

/** Полоса записи: мигающая точка, таймер, «влево — отмена». */
@Composable
private fun RecordingStrip(elapsed: Long, slide: Float, english: Boolean, modifier: Modifier) {
    val colors = HonerTheme.colors
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "rec.dot",
    )
    Row(modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(AdminRed))
        Text(formatDuration(elapsed), fontSize = 16.sp, color = colors.foreground, fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("admin.record.timer"))
        Text(
            if (english) "‹ Slide left to cancel" else "‹ Влево — отмена",
            fontSize = 14.sp, color = colors.secondary, maxLines = 1,
            modifier = Modifier.weight(1f).graphicsLayer { translationX = slide.coerceAtMost(0f) * 0.6f; alpha = (1f + slide / 220f).coerceIn(0.2f, 1f) },
            textAlign = TextAlign.Center,
        )
    }
}

/** Кнопка микрофона: удерживать — запись, отпустить — отправить, увести влево — отменить. */
@Composable
private fun MicButton(
    recording: Boolean,
    english: Boolean,
    cancelDistancePx: Float,
    hasPermission: () -> Boolean,
    onNeedPermission: () -> Unit,
    onStart: () -> Unit,
    onSlide: (Float) -> Unit,
    onFinish: (cancelled: Boolean) -> Unit,
) {
    val colors = HonerTheme.colors
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val slide by rememberUpdatedState(onSlide)
    val permission by rememberUpdatedState(hasPermission)
    val needPermission by rememberUpdatedState(onNeedPermission)
    Box(
        Modifier.size(if (recording) 58.dp else 46.dp).clip(CircleShape)
            .background(if (recording) AdminRed else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!permission()) { needPermission(); return@awaitEachGesture }
                    down.consume()
                    start()
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        if (!change.pressed) break
                        change.consume()
                        if (!cancelled) slide(dx)
                        if (!cancelled && dx < -cancelDistancePx) {
                            cancelled = true
                            finish(true)
                        }
                    }
                    if (!cancelled) finish(false)
                }
            }
            .semantics { contentDescription = if (english) "Hold to record a voice message" else "Удерживайте, чтобы записать голосовое" }
            .testTag("admin.mic"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, null, tint = if (recording) Color.White else colors.secondary, modifier = Modifier.size(if (recording) 28.dp else 24.dp))
    }
}
