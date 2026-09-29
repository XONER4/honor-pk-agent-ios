package com.honerai.app.ui.favorites

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.honerai.app.AppContainer
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.core.FavoritesLogic
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.Conversation
import com.honerai.app.data.MessageAttachment
import com.honerai.app.ui.chat.AttachmentPreviewDialog
import com.honerai.app.ui.chat.AttachmentTray
import com.honerai.app.ui.chat.StickerPickerSheet
import com.honerai.app.ui.common.AttachmentFileCard
import com.honerai.app.ui.common.AttachmentThumbnail
import com.honerai.app.ui.common.EmptyState
import com.honerai.app.ui.common.HonerCircleButton
import com.honerai.app.ui.common.PendingAttachmentChip
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.ToastBubble
import com.honerai.app.ui.common.copyToClipboard
import com.honerai.app.ui.common.rememberToastState
import com.honerai.app.ui.theme.HonerTheme
import com.honerai.app.device.SpeechService
import kotlinx.coroutines.launch

/**
 * Чат «Избранное» (Saved Messages): локальные заметки и пересланные сообщения.
 * Композер как в основном чате — текст, любые вложения (AttachmentTray) и голосовой ввод —
 * но ничего не уходит в нейросеть. Сообщения можно закреплять, копировать, пересылать в другую
 * папку избранного и удалять. Открывается поверх чата отдельным слоем.
 */
@Composable
fun FavoritesScreen(store: ChatStoreApi, english: Boolean, initialFolderId: String, onClose: () -> Unit) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val settings = remember { AppContainer.get(context).settings }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    fun t(ru: String, en: String) = if (english) en else ru

    val conversations by store.conversations.collectAsState()
    val folders = remember(conversations) { FavoritesLogic.ordered(conversations) }
    var folderId by remember { mutableStateOf(initialFolderId) }
    // Если папку удалили — переключаемся на первую доступную.
    LaunchedEffect(folders) { if (folders.none { it.id == folderId }) folders.firstOrNull()?.let { folderId = it.id } }
    val folder = folders.firstOrNull { it.id == folderId } ?: folders.firstOrNull()
    val messages = folder?.messages.orEmpty()
    val pinned = remember(messages) { messages.filter { it.pinnedInChat } }

    val pendingAttachments by store.attachments.collectAsState()
    var draft by remember { mutableStateOf("") }
    var attachmentsOpen by remember { mutableStateOf(false) }
    var stickersOpen by remember { mutableStateOf(false) }
    var previewAttachment by remember { mutableStateOf<MessageAttachment?>(null) }
    var forwardMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var deleteFolderConfirm by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Голосовой ввод — как в основном композере (SpeechService).
    val speech = remember { SpeechService(context.applicationContext) }
    val recording by speech.isRecording.collectAsState()
    val transcript by speech.transcript.collectAsState()
    var voiceHolding by remember { mutableStateOf(false) }
    var baseDraft by remember { mutableStateOf("") }
    DisposableEffect(speech) { onDispose { speech.cancelRecording(); speech.release() } }
    LaunchedEffect(transcript) { if (voiceHolding && transcript.isNotEmpty()) draft = if (baseDraft.isEmpty()) transcript else "$baseDraft $transcript" }
    LaunchedEffect(speech) { speech.errorMessage.collect { it?.let { e -> deviceError = e; voiceHolding = false } } }

    // Композер избранного не смешивается с полем основного чата: чистим вложения на входе и выходе.
    DisposableEffect(Unit) { store.clearPendingAttachments(); onDispose { store.clearPendingAttachments() } }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startFavoriteVoice(speech, settings.speechLanguage.value) { voiceHolding = it; baseDraft = draft }
        else deviceError = t("Разрешите доступ к микрофону, чтобы диктовать заметки.", "Allow microphone access to dictate notes.")
    }

    fun toggleVoice() {
        if (voiceHolding) {
            voiceHolding = false
            scope.launch {
                val text = runCatching { speech.stopRecording() }.getOrDefault("").trim()
                if (text.isNotEmpty()) draft = if (baseDraft.isEmpty()) text else "$baseDraft $text"
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                baseDraft = draft
                startFavoriteVoice(speech, settings.speechLanguage.value) { voiceHolding = it; baseDraft = draft }
            }
        }
    }

    suspend fun importUri(uri: Uri): MessageAttachment? = store.importAttachment(uri).fold(
        onSuccess = { it },
        onFailure = { error ->
            deviceError = error.message?.takeIf { it.isNotBlank() } ?: t("Не удалось прикрепить файл.", "Could not attach the file.")
            null
        },
    )

    fun send() {
        val id = folder?.id ?: return
        if (draft.isBlank() && pendingAttachments.isEmpty()) return
        store.addFavoriteNote(id, draft, pendingAttachments)
        draft = ""
        store.clearPendingAttachments()
        attachmentsOpen = false
        focusManager.clearFocus()
        scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    }

    BackHandler(enabled = attachmentsOpen) { attachmentsOpen = false }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing.only(
        androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        // Шапка.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HonerCircleButton(t("Назад", "Back"), onClose, Modifier.testTag("favorites.close")) {
                Icon(Icons.Rounded.Close, null, tint = colors.foreground, modifier = Modifier.size(20.dp))
            }
            Icon(Icons.Rounded.Bookmark, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text(folder?.title ?: t("Избранное", "Saved messages"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { overflowOpen = true }
                    .semantics { contentDescription = t("Действия", "Actions") }.testTag("favorites.menu"),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = colors.foreground, modifier = Modifier.size(24.dp))
                }
                DropdownMenu(overflowOpen, { overflowOpen = false }, shape = RoundedCornerShape(18.dp), containerColor = colors.surface) {
                    FavMenuItem(Icons.Rounded.Edit, t("Переименовать", "Rename"), "favorites.menu.rename") { overflowOpen = false; renameOpen = true }
                    FavMenuItem(Icons.Rounded.AddCircleOutline, t("Новая папка", "New folder"), "favorites.menu.newFolder") { overflowOpen = false; createFolderOpen = true }
                    FavMenuItem(Icons.Rounded.DeleteSweep, t("Очистить историю", "Clear history"), "favorites.menu.clear") { overflowOpen = false; clearConfirm = true }
                    FavMenuItem(Icons.Outlined.Delete, t("Удалить папку", "Delete folder"), "favorites.menu.delete", destructive = true) { overflowOpen = false; deleteFolderConfirm = true }
                }
            }
        }
        // Полоса папок избранного.
        if (folders.size > 1 || true) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(folders, key = { it.id }) { f ->
                    FolderChip(f, selected = f.id == folderId, english) { folderId = f.id }
                }
                item(key = "add.folder") {
                    Box(Modifier.height(34.dp).clip(CircleShape).background(colors.surface).border(0.7.dp, colors.divider, CircleShape)
                        .clickable { createFolderOpen = true }.padding(horizontal = 12.dp).testTag("favorites.addFolder"),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, t("Новая папка", "New folder"), tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Закреплённая панель — над лентой.
        AnimatedVisibility(pinned.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            PinnedBar(pinned, english)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(Icons.Rounded.Bookmark,
                    t("Здесь ваши заметки и пересланные сообщения", "Your notes and forwarded messages live here"),
                    Modifier.padding(top = 70.dp).testTag("favorites.empty"))
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(messages.asReversed(), key = { it.id }) { message ->
                        FavoriteNote(message, english, onOpenAttachment = { previewAttachment = it },
                            onPin = { store.toggleFavoriteMessagePin(folderId, message.id) },
                            onCopy = { copyToClipboard(context, message.content); toast.show(t("Скопировано", "Copied")) },
                            onForward = { forwardMessage = message },
                            onDelete = { store.deleteFavoriteMessage(folderId, message.id) })
                    }
                }
            }
        }

        // Низ: тост, композер, панель вложений.
        Column(Modifier.fillMaxWidth().background(colors.background)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(toast.message != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                ToastBubble(toast.message.orEmpty(), Modifier.padding(bottom = 10.dp))
            }
            FavoriteComposer(
                english = english, draft = draft, onDraft = { draft = it }, attachments = pendingAttachments,
                voiceRecording = voiceHolding, transcript = if (voiceHolding) transcript else "",
                attachmentsOpen = attachmentsOpen, onRemoveAttachment = { store.removeAttachment(it) },
                onToggleAttachments = { focusManager.clearFocus(); attachmentsOpen = !attachmentsOpen },
                onVoice = { toggleVoice() }, onSend = { send() },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
            AnimatedVisibility(attachmentsOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                AttachmentTray(store, english, onImport = { importUri(it) }, onStickers = { stickersOpen = true },
                    onError = { deviceError = it }, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }

    // Диалоги.
    previewAttachment?.let { attachment ->
        AttachmentPreviewDialog(attachment, english, onEdited = null, onClose = { previewAttachment = null })
    }
    if (stickersOpen) {
        StickerPickerSheet(english, onSelect = { store.addAttachment(MessageAttachment(name = it, kind = AttachmentKind.STICKER)) },
            onDismiss = { stickersOpen = false })
    }
    forwardMessage?.let { message ->
        FavoritesFolderPicker(folders.filter { it.id != folderId }, english,
            onPick = { targetId -> store.forwardToFavorites(targetId, message); forwardMessage = null; toast.show(t("Переслано", "Forwarded")) },
            onDismiss = { forwardMessage = null })
    }
    if (renameOpen && folder != null) {
        TextInputDialog(t("Переименовать папку", "Rename folder"), folder.title, english, "favorites.rename") { value ->
            renameOpen = false
            if (value != null) store.renameChat(folder.id, value)
        }
    }
    if (createFolderOpen) {
        TextInputDialog(t("Новая папка избранного", "New favorites folder"), "", english, "favorites.createFolder") { value ->
            createFolderOpen = false
            if (value != null) { val id = store.createFavoritesFolder(value); if (id.isNotEmpty()) folderId = id }
        }
    }
    if (clearConfirm && folder != null) {
        ConfirmDialog(t("Очистить историю?", "Clear history?"), t("Все заметки в этой папке будут удалены.", "All notes in this folder will be removed."),
            t("Очистить", "Clear"), english) { yes -> clearConfirm = false; if (yes) store.clearFavoritesHistory(folder.id) }
    }
    if (deleteFolderConfirm && folder != null) {
        val canDelete = FavoritesLogic.canDeleteFolder(conversations, folder.id)
        ConfirmDialog(
            if (canDelete) t("Удалить папку?", "Delete folder?") else t("Очистить папку?", "Clear folder?"),
            if (canDelete) t("Папка и её заметки будут удалены.", "The folder and its notes will be removed.")
            else t("Это последняя папка избранного — она будет очищена, а не удалена.", "This is the last favorites folder — it will be cleared, not deleted."),
            if (canDelete) t("Удалить", "Delete") else t("Очистить", "Clear"), english,
        ) { yes -> deleteFolderConfirm = false; if (yes) store.deleteFavoritesFolder(folder.id) }
    }
    deviceError?.let { error ->
        AlertDialog(onDismissRequest = { deviceError = null }, title = { Text(t("Не удалось выполнить действие", "Unable to complete action")) },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { deviceError = null }) { Text("OK", color = colors.accent) } },
            containerColor = colors.surface)
    }
}

private fun startFavoriteVoice(speech: SpeechService, language: String, onHolding: (Boolean) -> Unit) {
    speech.cancelRecording()
    speech.startRecording(language)
    onHolding(true)
}

@Composable
private fun FolderChip(folder: Conversation, selected: Boolean, english: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(Modifier.height(34.dp).clip(CircleShape)
        .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.surface)
        .border(0.7.dp, if (selected) colors.accent.copy(alpha = 0.4f) else colors.divider, CircleShape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp).testTag("favorites.folder." + folder.id),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(Icons.Rounded.Bookmark, null, tint = if (selected) colors.accent else colors.secondary, modifier = Modifier.size(14.dp))
        Text(folder.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) colors.accent else colors.foreground, modifier = Modifier.widthIn(max = 140.dp))
    }
}

@Composable
private fun PinnedBar(pinned: List<ChatMessage>, english: Boolean) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
        .border(0.7.dp, colors.divider, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag("favorites.pinnedBar")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.PushPin, null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Text(if (english) "Pinned (${pinned.size})" else "Закреплено (${pinned.size})", fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, color = colors.accent)
        }
        pinned.take(3).forEach { message ->
            Text(message.content.ifBlank { message.attachments.joinToString(", ") { it.name } },
                fontSize = 13.sp, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun FavoriteNote(
    message: ChatMessage, english: Boolean, onOpenAttachment: (MessageAttachment) -> Unit,
    onPin: () -> Unit, onCopy: () -> Unit, onForward: () -> Unit, onDelete: () -> Unit,
) {
    val colors = HonerTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp).clip(shape).background(colors.bubble)
            .clickable { menuOpen = true }.padding(horizontal = 13.dp, vertical = 10.dp)
            .testTag("favorites.note." + message.id), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FavoriteAttachments(message.attachments, english, onOpenAttachment)
            if (message.content.isNotEmpty()) {
                SelectionContainer { Text(message.content, fontSize = 16.sp, color = colors.foreground) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.pinnedInChat) Icon(Icons.Rounded.PushPin, null, tint = colors.accent, modifier = Modifier.size(11.dp))
                Text(TimeText.dateTime(message.createdAt, english), fontSize = 11.sp, color = colors.secondary)
            }
        }
        Box {
            DropdownMenu(menuOpen, { menuOpen = false }, shape = RoundedCornerShape(18.dp), containerColor = colors.sidebar) {
                FavMenuItem(if (message.pinnedInChat) Icons.Outlined.PushPin else Icons.Rounded.PushPin,
                    if (message.pinnedInChat) (if (english) "Unpin" else "Открепить") else (if (english) "Pin" else "Закрепить"),
                    "favorites.note.pin") { menuOpen = false; onPin() }
                if (message.content.isNotBlank()) FavMenuItem(Icons.Rounded.ContentCopy, if (english) "Copy" else "Копировать", "favorites.note.copy") { menuOpen = false; onCopy() }
                FavMenuItem(Icons.Rounded.Forward, if (english) "Forward to folder" else "Переслать в папку", "favorites.note.forward") { menuOpen = false; onForward() }
                FavMenuItem(Icons.Outlined.Delete, if (english) "Delete" else "Удалить", "favorites.note.delete", destructive = true) { menuOpen = false; onDelete() }
            }
        }
    }
}

@Composable
private fun FavoriteAttachments(attachments: List<MessageAttachment>, english: Boolean, onOpen: (MessageAttachment) -> Unit) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            when (attachment.kind) {
                AttachmentKind.STICKER -> Text(attachment.name, fontSize = 48.sp,
                    modifier = Modifier.clickable { onOpen(attachment) }.testTag("favorites.sticker." + attachment.id))
                AttachmentKind.IMAGE -> AttachmentThumbnail(attachment, Modifier.clickable { onOpen(attachment) }
                    .testTag("favorites.image." + attachment.id))
                AttachmentKind.VIDEO -> Box(contentAlignment = Alignment.Center,
                    modifier = Modifier.clickable { onOpen(attachment) }.testTag("favorites.video." + attachment.id)) {
                    AttachmentThumbnail(attachment)
                    Icon(Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(44.dp))
                }
                else -> AttachmentFileCard(attachment, english, Modifier.clickable { onOpen(attachment) }
                    .testTag("favorites.file." + attachment.id))
            }
        }
    }
}

@Composable
private fun FavoriteComposer(
    english: Boolean, draft: String, onDraft: (String) -> Unit, attachments: List<MessageAttachment>,
    voiceRecording: Boolean, transcript: String, attachmentsOpen: Boolean, onRemoveAttachment: (String) -> Unit,
    onToggleAttachments: () -> Unit, onVoice: () -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val shape = RoundedCornerShape(27.dp)
    val canSend = draft.isNotBlank() || attachments.isNotEmpty()
    Column(modifier.fillMaxWidth().clip(shape).background(colors.surface).border(0.7.dp, colors.divider, shape)
        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(attachments, key = { it.id }) { attachment ->
                    PendingAttachmentChip(attachment, t("Удалить вложение ", "Remove attachment ") + attachment.name, english) {
                        onRemoveAttachment(attachment.id)
                    }
                }
            }
        }
        if (voiceRecording) {
            Text(transcript.ifEmpty { t("Говорите… нажмите ■, чтобы вставить", "Listening… tap ■ to insert") },
                fontSize = 13.sp, color = colors.secondary, modifier = Modifier.testTag("favorites.voice.hint"))
        }
        BasicTextField(
            value = draft, onValueChange = onDraft,
            textStyle = TextStyle(color = colors.foreground, fontSize = 18.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(colors.accent), maxLines = 6,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 5.dp, vertical = 4.dp)
                .semantics { contentDescription = t("Заметка", "Note") }.testTag("favorites.composer"),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) Text(t("Заметка, файл, фото…", "Note, file, photo…"), color = colors.secondary, fontSize = 18.sp)
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ComposerIcon(if (attachmentsOpen) Icons.Rounded.HighlightOff else Icons.Rounded.AddCircleOutline,
                if (attachmentsOpen) t("Закрыть вложения", "Close attachments") else t("Добавить вложение", "Add attachment"),
                "favorites.attach", colors.foreground, onToggleAttachments)
            Spacer(Modifier.weight(1f))
            if (canSend) {
                Box(Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable(onClick = onSend)
                    .semantics { contentDescription = t("Сохранить в избранное", "Save to favorites") }.testTag("favorites.send"),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                ComposerIcon(if (voiceRecording) Icons.Rounded.StopCircle else Icons.Rounded.MicNone,
                    if (voiceRecording) t("Остановить и вставить", "Stop and insert") else t("Голосом", "Voice"),
                    "favorites.voice", if (voiceRecording) colors.accent else colors.foreground, onVoice)
            }
        }
    }
}

@Composable
private fun ComposerIcon(icon: ImageVector, label: String, tag: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable(onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun FavMenuItem(icon: ImageVector, title: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (destructive) Color(0xFFFF453A) else colors.foreground
    DropdownMenuItem(text = { Text(title, color = tint, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }, onClick = onClick,
        modifier = Modifier.testTag(tag))
}

/** Выбор папки избранного для пересылки. */
@Composable
private fun FavoritesFolderPicker(folders: List<Conversation>, english: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Переслать в папку", "Forward to folder")) },
        text = {
            if (folders.isEmpty()) {
                Text(t("Создайте ещё одну папку избранного, чтобы пересылать между ними.", "Create another favorites folder to forward between them."))
            } else {
                Column {
                    folders.forEach { f ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(f.id) }
                            .padding(horizontal = 8.dp).testTag("favorites.forward." + f.id), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Bookmark, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Text(f.title, fontSize = 16.sp, color = colors.foreground)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Закрыть", "Close"), color = colors.accent) } },
        containerColor = colors.surface,
    )
}

@Composable
private fun TextInputDialog(title: String, initial: String, english: Boolean, tag: String, onResult: (String?) -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, singleLine = true, label = { Text(t("Название", "Title")) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.accent, cursorColor = colors.accent, focusedLabelColor = colors.accent),
                modifier = Modifier.focusRequester(focus).testTag("$tag.field"))
        },
        confirmButton = { TextButton(onClick = { onResult(value.trim().ifEmpty { null }) }, enabled = value.isNotBlank(),
            modifier = Modifier.testTag("$tag.save")) { Text(t("Сохранить", "Save"), color = colors.accent) } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text(t("Отмена", "Cancel"), color = colors.accent) } },
        containerColor = colors.surface,
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, english: Boolean, onResult: (Boolean) -> Unit) {
    val colors = HonerTheme.colors
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text(confirmLabel, color = Color(0xFFFF453A)) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text(if (english) "Cancel" else "Отмена", color = colors.accent) } },
        containerColor = colors.surface,
    )
}
