package com.honerai.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.honerai.app.R
import com.honerai.app.core.AppSettings
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.core.FavoritesLogic
import com.honerai.app.data.Conversation
import com.honerai.app.ui.common.HonerCircleButton
import com.honerai.app.ui.common.HonerImages
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.rememberHaptics
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Панель истории чатов: группы «Закреплено / Сегодня / Вчера / 7 дней / Ранее», поиск
 * по содержимому всех чатов (в фоне, с задержкой), меню чата, выбор нескольких чатов
 * и перетаскивание долгим нажатием: брошенный на закреплённый чат закрепляется на его месте,
 * закреплённый, брошенный на обычный, — открепляется.
 */
@Composable
fun HistoryDrawer(
    store: ChatStoreApi,
    settings: AppSettings,
    english: Boolean,
    fontScale: Float,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onInstructions: (String) -> Unit,
    // appui: открыть чат «Избранное» и библиотеку всех вложений.
    onOpenFavorites: (String) -> Unit = {},
    onAllAttachments: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = HonerTheme.colors
    val haptics = rememberHaptics()
    val focusManager = LocalFocusManager.current
    fun t(ru: String, en: String) = if (english) en else ru

    val conversations by store.conversations.collectAsState()
    val selectedId by store.selectedConversationId.collectAsState()
    // appui: чат «Избранное» по умолчанию создаётся один раз после загрузки истории.
    val loadingHistory by store.isLoadingHistory.collectAsState()
    LaunchedEffect(loadingHistory) { if (!loadingHistory) store.ensureDefaultFavorites() }
    val favorites = remember(conversations) { FavoritesLogic.ordered(conversations) }
    val displayName by settings.displayName.collectAsState()
    val photoPath by settings.profilePhotoPath.collectAsState()

    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<List<Conversation>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dropHint by remember { mutableStateOf<String?>(null) }
    var menuChat by remember { mutableStateOf<String?>(null) }

    // Перетаскивание.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<String?>(null) }
    val rowBounds = remember { HashMap<String, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    // «2 минуты назад» обновляется раз в минуту.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }

    val sorted = remember(conversations) { store.sortedConversations() }
    val revision = remember(conversations) { HistorySearchRules.revision(conversations) }
    LaunchedEffect(search, revision) {
        if (search.isBlank()) { searchResult = null; searching = false; return@LaunchedEffect }
        searching = true
        delay(250)
        val snapshot = store.sortedConversations()
        val query = search
        searchResult = withContext(Dispatchers.Default) { HistorySearchRules.filter(snapshot, query) }
        searching = false
    }
    val groups = remember(sorted, searchResult, search.isBlank(), english, now) {
        HistoryGrouping.group(if (search.isBlank()) sorted else searchResult.orEmpty(), english, now)
    }
    val selectedSet = selectedIds.filterValues { it }.keys
    val allSelectedPinned = selectedSet.isNotEmpty() && conversations.filter { it.id in selectedSet }.all { it.pinned }

    fun finishSelection() { selecting = false; selectedIds.clear() }
    fun handleDrop(id: String, onto: String) {
        if (id == onto || selecting) return
        val wasPinned = conversations.firstOrNull { it.id == id }?.pinned ?: false
        val targetPinned = conversations.firstOrNull { it.id == onto }?.pinned ?: false
        if (store.moveChat(id, onto)) {
            haptics.medium()
        } else if (!wasPinned && !targetPinned) {
            dropHint = t("Обычные чаты идут по времени. Бросьте чат на закреплённый, чтобы закрепить его на этом месте.",
                "Regular chats are sorted by time. Drop a chat onto a pinned one to pin it there.")
        }
    }

    Box(modifier.fillMaxSize().background(colors.sidebar).onGloballyPositioned { rootOrigin = it.boundsInWindow().topLeft }) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))) {
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Выберите чаты", "Select chats"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.foreground, modifier = Modifier.weight(1f))
                    HonerCircleButton(t("Отмена", "Cancel"), ::finishSelection, Modifier.testTag("history.selection.cancel"), diameter = 36.dp) {
                        Icon(Icons.Rounded.Close, null, tint = colors.foreground, modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                // Шапка: логотип приложения и кнопка нового чата.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.honer_logo), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        .border(0.6.dp, colors.divider, RoundedCornerShape(8.dp)))
                    Text("Honer AI", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = colors.foreground, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable {
                            focusManager.clearFocus()
                            store.newChat()
                            finishSelection()
                            onClose()
                        }.semantics { contentDescription = t("Новый чат", "New chat") }.testTag("history.new.chat"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.EditNote, null, tint = colors.foreground, modifier = Modifier.size(24.dp)) }
                }
                SearchField(search, { search = it }, searching, english)
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 7.dp, end = 7.dp, bottom = 10.dp),
            ) {
                // appui: чаты «Избранное» — закреплены в самом верху, отдельным акцентным блоком.
                if (!selecting && search.isBlank() && favorites.isNotEmpty()) {
                    items(favorites, key = { "fav." + it.id }) { fav ->
                        FavoritesDrawerRow(fav, english) { focusManager.clearFocus(); onOpenFavorites(fav.id) }
                    }
                    item(key = "favorites.divider") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).height(0.5.dp)
                            .background(colors.divider.copy(alpha = 0.7f)))
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (search.isEmpty()) Icons.Rounded.Forum else Icons.Rounded.Search, null, tint = colors.secondary,
                                modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(if (search.isEmpty()) t("Ваши чаты появятся здесь", "Your conversations appear here") else t("Ничего не найдено", "No results"),
                                fontSize = 15.sp, color = colors.secondary, textAlign = TextAlign.Center)
                        }
                    }
                }
                groups.forEachIndexed { groupIndex, group ->
                    item(key = "group." + group.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 29.dp).padding(start = 14.dp, end = 6.dp, top = if (groupIndex == 0) 0.dp else 17.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(group.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.secondary, modifier = Modifier.weight(1f))
                            if (groupIndex == 0 && !selecting) {
                                Box(Modifier.size(width = 40.dp, height = 32.dp).clip(CircleShape).clickable {
                                    focusManager.clearFocus(); selecting = true
                                }.semantics { contentDescription = t("Выбрать чаты", "Select chats") }.testTag("history.select"),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Checklist, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    group.chats.forEach { chat ->
                        item(key = chat.id) {
                            val isSelected = selectedIds[chat.id] == true
                            HistoryRow(
                                chat = chat,
                                now = now,
                                english = english,
                                fontScale = fontScale,
                                current = selectedId == chat.id && !selecting,
                                selecting = selecting,
                                checked = isSelected,
                                dropTarget = dropTarget == chat.id,
                                dragging = draggingId == chat.id,
                                menuOpen = menuChat == chat.id,
                                onBounds = { rowBounds[chat.id] = it },
                                onTap = {
                                    if (selecting) selectedIds[chat.id] = !isSelected
                                    else {
                                        focusManager.clearFocus()
                                        store.selectChat(chat.id)
                                        onClose()
                                    }
                                },
                                onLongPress = { haptics.medium() },
                                onDragStart = { pointer -> draggingId = chat.id; dragPointer = pointer },
                                onDrag = { pointer ->
                                    dragPointer = pointer
                                    dropTarget = rowBounds.entries.firstOrNull { it.key != chat.id && it.value.contains(pointer) }?.key
                                },
                                onDragEnd = { moved ->
                                    val target = dropTarget
                                    draggingId = null
                                    dropTarget = null
                                    if (!moved) menuChat = chat.id
                                    else if (target != null) handleDrop(chat.id, target)
                                },
                                onMenu = { menuChat = chat.id },
                                onDismissMenu = { menuChat = null },
                                onMoveUp = { store.movePinned(chat.id, -1) },
                                onMoveDown = { store.movePinned(chat.id, 1) },
                                actions = {
                                    HistoryMenuItem(if (chat.pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin,
                                        if (chat.pinned) t("Открепить", "Unpin") else t("Закрепить", "Pin"), "history.menu.pin") {
                                        menuChat = null; store.togglePin(setOf(chat.id))
                                    }
                                    HistoryMenuItem(Icons.Rounded.PushPin, t("Инструкции чата", "Chat instructions"), "history.menu.prompt") {
                                        menuChat = null; onInstructions(chat.id)
                                    }
                                    HistoryMenuItem(Icons.Rounded.Edit, t("Переименовать", "Rename"), "history.menu.rename") {
                                        menuChat = null; renameTarget = chat
                                    }
                                    HistoryMenuItem(Icons.Outlined.Archive, t("В архив", "Archive"), "history.menu.archive") {
                                        menuChat = null; store.archiveChat(chat.id)
                                    }
                                    HistoryMenuItem(Icons.Rounded.CheckCircle, t("Выбрать", "Select"), "history.menu.select") {
                                        menuChat = null; focusManager.clearFocus(); selectedIds.clear(); selectedIds[chat.id] = true; selecting = true
                                    }
                                    HistoryMenuItem(Icons.Outlined.Delete, t("Удалить", "Delete"), "history.menu.delete", destructive = true) {
                                        menuChat = null; deleteTargets = setOf(chat.id)
                                    }
                                },
                            )
                        }
                    }
                    if (group.id == "pinned") {
                        item(key = "pinned.divider") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).height(0.5.dp)
                                .background(colors.divider.copy(alpha = 0.7f)))
                        }
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                if (selecting) {
                    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        val enabled = selectedSet.isNotEmpty()
                        BulkButton(if (allSelectedPinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin,
                            if (allSelectedPinned) t("Открепить", "Unpin") else t("Закрепить", "Pin"), enabled, "history.bulk.pin", colors.foreground) {
                            store.togglePin(selectedSet.toSet()); finishSelection()
                        }
                        Spacer(Modifier.weight(1f))
                        BulkButton(Icons.Outlined.Delete, t("Удалить", "Delete"), enabled, "history.bulk.delete", Color(0xFFFF453A)) {
                            deleteTargets = selectedSet.toSet()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                    // appui: «Все вложения» — библиотека вложений по всем чатам.
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).clickable {
                            focusManager.clearFocus(); onAllAttachments()
                        }.padding(horizontal = 15.dp).testTag("sidebar.allAttachments"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, null, tint = colors.secondary, modifier = Modifier.size(22.dp))
                        Text(t("Все вложения", "All attachments"), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = colors.foreground, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                    }
                    // appui: строка «Техподдержка» (чат с администратором из облачного модуля) перенесена вниз.
                    // appui: needs cloud row label "Техподдержка" (метка задаётся в модуле cloud/ui.cloud).
                    Box(Modifier.padding(horizontal = 7.dp)) {
                        com.honerai.app.ui.cloud.AdminChatDrawerRow(english, onOpened = { focusManager.clearFocus(); onClose() })
                    }
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onSettings).padding(horizontal = 15.dp)
                            .semantics { contentDescription = t("Профиль и настройки", "Profile and settings") }
                            .testTag("sidebar.settings"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val photo = remember(photoPath) { photoPath.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() } }
                        if (photo != null) {
                            AsyncImage(photo, null, HonerImages.loader(LocalContext.current), Modifier.size(30.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Rounded.AccountCircle, null, tint = colors.secondary, modifier = Modifier.size(30.dp))
                        }
                        Text(displayName.ifEmpty { t("Ваш профиль", "Your profile") }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.MoreHoriz, null, tint = colors.secondary, modifier = Modifier.size(22.dp))
                    }
                    }
                }
            }
        }

        // Перетаскиваемый чат следует за пальцем.
        val dragged = draggingId?.let { id -> conversations.firstOrNull { it.id == id } }
        if (dragged != null) {
            val local = dragPointer - rootOrigin
            Row(
                Modifier
                    .offset { IntOffset((local.x - 60.dp.toPx()).roundToInt(), (local.y - 44.dp.toPx()).roundToInt()) }
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (dragged.pinned) Icons.Rounded.PushPin else Icons.AutoMirrored.Rounded.Chat, null, tint = colors.accent,
                    modifier = Modifier.size(16.dp))
                Text(dragged.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
    }

    dropHint?.let { hint ->
        AlertDialog(
            onDismissRequest = { dropHint = null },
            title = { Text(t("Перетаскивание чатов", "Moving chats")) },
            text = { Text(hint) },
            confirmButton = { TextButton(onClick = { dropHint = null }) { Text("OK", color = colors.accent) } },
            containerColor = colors.surface,
        )
    }
    renameTarget?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(target.id) { delay(200); runCatching { focus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(t("Переименовать чат", "Rename chat")) },
            text = {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(t("Название", "Title")) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.accent, cursorColor = colors.accent,
                        focusedLabelColor = colors.accent),
                    modifier = Modifier.focusRequester(focus).testTag("history.rename.field"))
            },
            confirmButton = {
                TextButton(onClick = { store.renameChat(target.id, title.trim()); renameTarget = null }, enabled = title.isNotBlank(),
                    modifier = Modifier.testTag("history.rename.save")) { Text(t("Сохранить", "Save"), color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }, modifier = Modifier.testTag("history.rename.cancel")) {
                    Text(t("Отмена", "Cancel"), color = colors.accent)
                }
            },
            containerColor = colors.surface,
        )
    }
    if (deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteTargets = emptySet() },
            title = { Text(if (deleteTargets.size > 1 || selecting) t("Удалить выбранные чаты?", "Delete selected chats?") else t("Удалить этот чат?", "Delete this conversation?")) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteChats(deleteTargets)
                    deleteTargets.forEach { selectedIds.remove(it) }
                    deleteTargets = emptySet()
                    if (selectedIds.none { it.value }) selecting = false
                }, modifier = Modifier.testTag("history.delete.confirm")) { Text(t("Удалить", "Delete"), color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = emptySet() }, modifier = Modifier.testTag("history.delete.cancel")) {
                    Text(t("Отмена", "Cancel"), color = colors.accent)
                }
            },
            containerColor = colors.surface,
        )
    }
}

/** appui: акцентная строка чата «Избранное» в самом верху истории. */
@Composable
private fun FavoritesDrawerRow(favorite: Conversation, english: Boolean, onOpen: () -> Unit) {
    val colors = HonerTheme.colors
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(colors.accent.copy(alpha = 0.14f))
            .border(0.7.dp, colors.accent.copy(alpha = 0.3f), shape).clickable(onClick = onOpen)
            .padding(horizontal = 13.dp, vertical = 6.dp)
            .semantics { contentDescription = (if (english) "Saved messages: " else "Избранное: ") + favorite.title }
            .testTag("history.favorites." + favorite.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Bookmark, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Text(favorite.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.accent.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, searching: Boolean, english: Boolean) {
    val colors = HonerTheme.colors
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 13.dp).fillMaxWidth().height(44.dp)
            .clip(CircleShape).background(colors.surface.copy(alpha = 0.4f)).border(0.7.dp, colors.divider, CircleShape)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.secondary, modifier = Modifier.size(19.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(if (english) "Search conversations…" else "Поиск в содержимом…", fontSize = 16.sp, color = colors.secondary)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = colors.foreground, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth().testTag("history.search"))
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Rounded.Cancel, if (english) "Clear search" else "Очистить поиск", tint = colors.secondary,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") }.testTag("history.clear"))
        }
        if (searching) {
            CircularProgressIndicator(Modifier.size(16.dp).testTag("history.searching"), color = colors.secondary, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun HistoryRow(
    chat: Conversation,
    now: Instant,
    english: Boolean,
    fontScale: Float,
    current: Boolean,
    selecting: Boolean,
    checked: Boolean,
    dropTarget: Boolean,
    dragging: Boolean,
    menuOpen: Boolean,
    onBounds: (Rect) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (moved: Boolean) -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val colors = HonerTheme.colors
    val timestamp = remember(chat.lastMessageAt, now, english) { TimeText.relative(chat.lastMessageAt, now, english) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val borderWidth by animateFloatAsState(if (dropTarget) 2f else 0f, label = "drop")
    val shape = RoundedCornerShape(15.dp)
    val tint = if (current) colors.accent else colors.foreground
    val currentBounds = remember { arrayOf(Rect.Zero) }
    currentBounds[0] = bounds
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    current -> colors.accent.copy(alpha = 0.21f)
                    pressed -> colors.foreground.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .then(if (borderWidth > 0f) Modifier.border(borderWidth.dp, colors.accent, shape) else Modifier)
            .graphicsLayer { alpha = if (dragging) 0.45f else 1f }
            .onGloballyPositioned { bounds = it.boundsInWindow(); onBounds(bounds) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .pointerInput(chat.id, selecting) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        var cancelled = false
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation().also { if (it == null) cancelled = true }
                        }
                        if (up != null) {
                            pressed = false
                            up.consume()
                            tap()
                            return@awaitEachGesture
                        }
                        if (cancelled) { pressed = false; return@awaitEachGesture }
                        // Долгое нажатие: перетаскивание (или меню, если палец не сдвинулся).
                        longPress()
                        if (selecting) {
                            pressed = false
                            do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                            tap()
                            return@awaitEachGesture
                        }
                        val origin = currentBounds[0].topLeft
                        val start = origin + down.position
                        var pointer = start
                        var moved = false
                        dragStart(pointer)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                pointer += delta
                                if ((pointer - start).getDistance() > viewConfiguration.touchSlop) moved = true
                                change.consume()
                                drag(pointer)
                            }
                        }
                        pressed = false
                        dragEnd(moved)
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = chat.title
                    stateDescription = timestamp + if (chat.pinned) (if (english) ", pinned" else ", закреплён") else ""
                    selected = if (selecting) checked else current
                    onClick { onTap(); true }
                    customActions = listOf(CustomAccessibilityAction(if (english) "Actions" else "Действия") { onMenu(); true })
                }
                .testTag("history.row." + chat.id)
                .padding(start = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selecting) {
                Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    tint = if (checked) colors.accent else colors.secondary.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
            }
            Text(chat.title, fontSize = (16 * fontScale).sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(timestamp, fontSize = 11.sp, color = colors.secondary, maxLines = 1)
            if (chat.pinned && !selecting) {
                Icon(Icons.Rounded.PushPin, null, tint = colors.accent.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
            }
        }
        if (!selecting) {
            if (chat.pinned) {
                // Закреплённые чаты можно менять местами между собой.
                Column {
                    SmallArrow(Icons.Rounded.KeyboardArrowUp, if (english) "Move pinned up" else "Выше среди закреплённых",
                        "history.pin.up." + chat.id, onMoveUp)
                    SmallArrow(Icons.Rounded.KeyboardArrowDown, if (english) "Move pinned down" else "Ниже среди закреплённых",
                        "history.pin.down." + chat.id, onMoveDown)
                }
            }
            Box {
                Box(
                    Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable(onClick = onMenu)
                        .semantics { contentDescription = (if (english) "Actions for " else "Действия с чатом ") + chat.title }
                        .testTag("history.actions." + chat.id),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MoreHoriz, null, tint = colors.secondary, modifier = Modifier.size(20.dp)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu, shape = RoundedCornerShape(18.dp),
                    containerColor = colors.surface) { actions() }
            }
        }
    }
}

@Composable
private fun SmallArrow(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = HonerTheme.colors.secondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun HistoryMenuItem(icon: ImageVector, title: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (destructive) Color(0xFFFF453A) else colors.foreground
    DropdownMenuItem(
        text = { Text(title, color = tint, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun BulkButton(icon: ImageVector, title: String, enabled: Boolean, tag: String, tint: Color, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val color = if (enabled) tint else colors.secondary
    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}
