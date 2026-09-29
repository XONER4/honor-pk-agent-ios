package com.honerai.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageFeedback
import com.honerai.app.data.MessageInputKind
import com.honerai.app.data.MessageRole
import com.honerai.app.device.DeviceInfo
import com.honerai.app.device.ParentalControl
import com.honerai.app.device.SpeechService
import com.honerai.app.device.UpdateManager
import com.honerai.app.ui.common.FullScreenLayer
import com.honerai.app.ui.common.HonerCircleButton
import com.honerai.app.ui.common.HonerMark
import com.honerai.app.ui.common.LocalChatFontScale
import com.honerai.app.ui.common.MediaLinks
import com.honerai.app.ui.common.NewConversationSymbol
import com.honerai.app.ui.common.SelectableTextSheet
import com.honerai.app.ui.common.SelectionAction
import com.honerai.app.ui.common.SidebarSymbol
import com.honerai.app.ui.common.ToastBubble
import com.honerai.app.ui.common.copyToClipboard
import com.honerai.app.ui.common.openAppSettings
import com.honerai.app.ui.common.rememberHaptics
import com.honerai.app.ui.common.rememberToastState
import com.honerai.app.ui.common.shareText
import com.honerai.app.ui.games.GameHub
import com.honerai.app.ui.games.GameScreen
import com.honerai.app.ui.settings.SettingsScreen
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs

/** Сколько уже прочитано вслух из печатающегося ответа (не состояние Compose: не вызывает перерисовок). */
private class LiveSpeechHolder { var cursor = LiveSpeechCursor() }

/**
 * Экран чата (порт ChatRootView): шапка, панель истории, лента сообщений, поле ввода,
 * голосовой ввод, чтение вслух, окна и слои поверх чата.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatRoot(activity: MainActivity) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val store = container.store
    val settings = container.settings
    val language by settings.language.collectAsState()
    val english = language == "en"
    // Язык читается в момент вызова: подписи в обработчиках не устаревают после смены языка.
    fun t(ru: String, en: String) = if (settings.isEnglish) en else ru
    val colors = HonerTheme.colors
    val fontScale = LocalChatFontScale.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val toast = rememberToastState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    val speech = remember { SpeechService(context.applicationContext) }
    DisposableEffect(speech) {
        onDispose {
            speech.cancelRecording()
            speech.stopSpeaking()
            speech.release()
        }
    }

    // ---- Состояние хранилища ----
    val rawMessages by store.messages.collectAsState()
    val messages = remember(rawMessages) { rawMessages.filter { it.role != MessageRole.TOOL } }
    val conversations by store.conversations.collectAsState()
    val selectedId by store.selectedConversationId.collectAsState()
    val chat = remember(conversations, selectedId) { conversations.firstOrNull { it.id == selectedId } }
    val generating by store.isGenerating.collectAsState()
    val typingId by store.typingMessageId.collectAsState()
    val status by store.generationStatus.collectAsState()
    val loadingHistory by store.isLoadingHistory.collectAsState()
    val autoRead by settings.autoRead.collectAsState()
    val recording by speech.isRecording.collectAsState()
    val transcript by speech.transcript.collectAsState()
    val speaking by speech.isSpeaking.collectAsState()
    val speechError by speech.errorMessage.collectAsState()

    // ---- Состояние экрана ----
    var drawerOpen by remember { mutableStateOf(false) }
    var sidebarVisible by remember { mutableStateOf(true) }
    var attachmentsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var memoryDraft by remember { mutableStateOf<String?>(null) }
    var galleryOpen by remember { mutableStateOf(false) }
    var previewAttachment by remember { mutableStateOf<MessageAttachment?>(null) }
    var sourceSheet by remember { mutableStateOf<SourceSelection?>(null) }
    var chatInfoOpen by remember { mutableStateOf(false) }
    var stickersOpen by remember { mutableStateOf(false) }
    var deleteChatConfirm by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findIndex by remember { mutableIntStateOf(0) }
    var scrollRequest by remember { mutableStateOf<ScrollRequest?>(null) }
    var instructionsTarget by remember { mutableStateOf<String?>(null) }
    var gameHubOpen by remember { mutableStateOf(false) }
    var activeGame by remember { mutableStateOf<String?>(null) }
    // appui: чат «Избранное», библиотека вложений и пересылка сообщения в избранное.
    var favoritesFolderId by remember { mutableStateOf<String?>(null) }
    var libraryOpen by remember { mutableStateOf(false) }
    var libraryScopeChatId by remember { mutableStateOf<String?>(null) }
    var forwardFavoritesMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var needsPermissionSettings by remember { mutableStateOf(false) }
    var toolsMenuOpen by remember { mutableStateOf(false) }
    var composerFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    var speakingContent by remember { mutableStateOf<String?>(null) }
    val live = remember { LiveSpeechHolder() }

    // Голосовой ввод.
    var voiceMode by remember { mutableStateOf(false) }
    var voiceHolding by remember { mutableStateOf(false) }
    var voiceFinalizing by remember { mutableStateOf(false) }
    var voiceSession by remember { mutableStateOf<Long?>(null) }
    var voiceOriginalDraft by remember { mutableStateOf("") }
    var voiceWatchdog by remember { mutableStateOf<Job?>(null) }

    fun showToast(text: String) = toast.show(text)

    fun focusComposer() {
        scope.launch {
            delay(120)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    fun cancelVoice() {
        voiceWatchdog?.cancel(); voiceWatchdog = null
        voiceSession = null
        voiceHolding = false
        voiceFinalizing = false
        speech.cancelRecording()
    }

    fun send(kind: MessageInputKind = MessageInputKind.TEXT) {
        cancelVoice()
        speech.stopSpeaking()
        store.send(kind)
        focusManager.clearFocus()
        attachmentsOpen = false
    }

    var startVoiceAfterPermission by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startVoiceAfterPermission = true
        } else {
            needsPermissionSettings = !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            deviceError = t("Разрешите Honer AI доступ к микрофону, чтобы говорить голосом.",
                "Allow Honer AI to use the microphone for voice input.")
        }
    }

    fun beginVoice() {
        if (voiceHolding || voiceFinalizing) return
        if (store.isGenerating.value) {
            showToast(t("Дождитесь конца ответа или остановите его", "Wait for the answer to finish or stop it"))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceMode = true
        // Страховка: если предыдущая запись осталась «висеть», начинаем с чистого состояния.
        if (!speech.isRecording.value) speech.cancelRecording()
        val session = System.nanoTime()
        voiceSession = session
        voiceOriginalDraft = store.draft.value
        voiceHolding = true
        focusManager.clearFocus()
        speech.stopSpeaking()
        haptics.light()
        speech.startRecording(settings.speechLanguage.value)
        voiceWatchdog?.cancel()
        // Сторож: если запись так и не началась за 10 секунд — снимаем залипшее состояние.
        voiceWatchdog = scope.launch {
            val started = withTimeoutOrNull(10_000) { speech.isRecording.first { it } } != null
            if (voiceSession != session) return@launch
            if (!started) { cancelVoice(); voiceMode = false }
        }
    }

    LaunchedEffect(startVoiceAfterPermission) {
        if (startVoiceAfterPermission) {
            startVoiceAfterPermission = false
            beginVoice()
        }
    }

    fun finishVoice() {
        voiceWatchdog?.cancel(); voiceWatchdog = null
        val session = voiceSession ?: return
        if (!speech.isRecording.value) { cancelVoice(); voiceMode = false; return }
        voiceHolding = false
        voiceFinalizing = true
        scope.launch {
            val text = runCatching { speech.stopRecording() }.getOrDefault("").trim()
            voiceFinalizing = false
            if (voiceSession != session) return@launch
            voiceSession = null
            if (text.isEmpty()) { voiceMode = false; return@launch }
            store.draft.value = if (voiceOriginalDraft.isEmpty()) text else "$voiceOriginalDraft $text"
            voiceMode = false
            send(MessageInputKind.VOICE)
        }
    }

    fun speak(original: String) {
        val content = if (ParentalControl.enabled) ParentalControl.filterOutput(original) else original
        if (speech.isSpeaking.value && speakingContent == content) {
            speech.stopSpeaking()
            speakingContent = null
        } else {
            speech.speak(content, settings.voiceGender.value, settings.voiceRate.value, settings.voiceIdentifier.value)
            speakingContent = content
        }
    }

    /** Чтение вслух во время печати: законченные предложения уходят в озвучку сразу. */
    fun feedLiveSpeech(final: Boolean) {
        if (!settings.autoRead.value) return
        val id = store.typingMessageId.value ?: live.cursor.messageId ?: return
        if (live.cursor.messageId != id) {
            if (final) return
            live.cursor = LiveSpeechCursor(id, 0)
            speakingContent = null
        }
        val text = if (final) {
            val message = store.messages.value.firstOrNull { it.id == id } ?: return
            if (message.error != null || message.isInterrupted) return
            message.content
        } else store.pacer.content.value
        val consumed = live.cursor.consumed
        if (consumed > text.length) return
        val rest = text.substring(consumed)
        val cut = SpeechText.speakableEnd(rest, final) ?: return
        val chunk = rest.substring(0, cut)
        live.cursor = live.cursor.copy(consumed = consumed + cut)
        val spoken = if (ParentalControl.enabled) ParentalControl.filterOutput(chunk) else chunk
        speech.append(spoken, settings.voiceGender.value, settings.voiceRate.value, settings.voiceIdentifier.value)
    }

    fun copy(text: String) {
        copyToClipboard(context, text)
        haptics.light()
        showToast(t("Скопировано", "Copied"))
    }

    suspend fun importUri(uri: Uri): MessageAttachment? {
        val result = store.importAttachment(uri)
        return result.fold(
            onSuccess = { attachment ->
                if (store.attachments.value.none { it.id == attachment.id }) store.addAttachment(attachment)
                attachment
            },
            onFailure = { error ->
                deviceError = error.message?.takeIf { it.isNotBlank() && error !is UnsupportedOperationException }
                    ?: t("Не удалось прикрепить файл.", "Could not attach the file.")
                null
            },
        )
    }

    fun openDrawer() {
        focusManager.clearFocus()
        cancelVoice()
        drawerOpen = true
        attachmentsOpen = false
    }

    fun closeDrawer() { drawerOpen = false }

    fun performMenuAction(message: ChatMessage, action: MessageMenuAction) {
        when (action) {
            MessageMenuAction.COPY -> copy(message.content)
            MessageMenuAction.SELECT -> {
                // Даём меню закрыться, потом открываем отдельное окно выделения.
                val value = message.content
                scope.launch { delay(220); selectedText = value }
            }
            MessageMenuAction.QUOTE -> { store.quote(message.content); focusComposer() }
            MessageMenuAction.EDIT -> { store.edit(message.id); focusComposer() }
            MessageMenuAction.SHARE -> shareText(context, message.content)
            MessageMenuAction.RETRY -> store.regenerate(message.id)
            MessageMenuAction.LIKE -> store.setFeedback(message.id, if (message.feedback == MessageFeedback.LIKE) null else MessageFeedback.LIKE)
            MessageMenuAction.DISLIKE -> store.setFeedback(message.id, if (message.feedback == MessageFeedback.DISLIKE) null else MessageFeedback.DISLIKE)
            MessageMenuAction.SPEAK -> speak(message.content)
            MessageMenuAction.FORK -> {
                cancelVoice(); speech.stopSpeaking()
                if (store.forkConversation(message.id) != null) {
                    showToast(t("Новая ветка создана", "New branch created"))
                    focusComposer()
                }
            }
            MessageMenuAction.REMEMBER -> memoryDraft = message.content
            MessageMenuAction.FORWARD_TO_FAVORITES -> {
                // appui: гарантируем существование папки избранного и открываем выбор куда переслать.
                store.ensureDefaultFavorites()
                forwardFavoritesMessage = message
            }
            MessageMenuAction.PIN_INSTRUCTION -> {
                if (store.pinInstruction(message.id)) {
                    haptics.success()
                    showToast(t("Закреплено как инструкция — Honer AI видит её в каждом ответе", "Pinned as an instruction"))
                } else store.errorMessage.value?.let { showToast(it) }
            }
        }
    }

    val latestPerform by rememberUpdatedState(::performMenuAction)
    val actions = remember {
        MessageActions(
            onCopy = { copy(it) },
            onSelect = { selectedText = it },
            onShare = { shareText(context, it) },
            onSpeak = { speak(it) },
            onAttachment = { previewAttachment = it },
            onSources = { sourceSheet = it },
            onRetry = { store.regenerate(it) },
            onFeedback = { id, value -> store.setFeedback(id, value) },
            onReaction = { id, emoji -> store.setReaction(id, emoji) },
            onAnswer = { answer ->
                // Ответ на вопрос агента уходит обычным сообщением. Если отправить нельзя —
                // объясняем и не фиксируем выбор, чтобы можно было нажать снова.
                if (store.isGenerating.value || store.isLoadingHistory.value) {
                    showToast(t("Дождитесь конца ответа и нажмите ещё раз", "Wait for the answer to finish, then tap again"))
                    false
                } else {
                    val before = store.messages.value.size
                    store.draft.value = answer
                    store.send(MessageInputKind.SUGGESTION)
                    store.messages.value.size > before
                }
            },
            onMenuOpened = { focusManager.clearFocus() },
            onMenuAction = { message, action -> latestPerform(message, action) },
        )
    }

    // ---- Реакции на изменения ----
    LaunchedEffect(voiceMode) { if (!voiceMode) cancelVoice() }
    LaunchedEffect(speechError) {
        val error = speechError ?: return@LaunchedEffect
        deviceError = error
        if (voiceHolding && !speech.isRecording.value) { cancelVoice(); voiceMode = false }
    }
    LaunchedEffect(speaking) { if (!speaking) speakingContent = null }
    LaunchedEffect(selectedId) {
        cancelVoice(); speech.stopSpeaking()
        findOpen = false; findQuery = ""; findIndex = 0
        attachmentsOpen = false
    }
    var wasGenerating by remember { mutableStateOf(generating) }
    LaunchedEffect(generating) {
        if (wasGenerating && !generating && settings.autoRead.value) {
            val last = store.messages.value.lastOrNull()
            if (last != null && last.role == MessageRole.ASSISTANT && last.content.isNotEmpty() && last.error == null &&
                !last.isInterrupted && live.cursor.messageId != last.id) speak(last.content)
        }
        wasGenerating = generating
    }
    var previousTyping by remember { mutableStateOf(typingId) }
    LaunchedEffect(typingId) {
        if (previousTyping != null && typingId == null) feedLiveSpeech(final = true)
        previousTyping = typingId
    }
    LaunchedEffect(store.pacer) { store.pacer.grew.collect { feedLiveSpeech(final = false) } }

    // Игра, которую попросила открыть нейросеть: открываем, когда она допечатает приглашение.
    val requestedGame by store.requestedGame.collectAsState()
    LaunchedEffect(requestedGame) {
        val game = requestedGame ?: return@LaunchedEffect
        store.consumeRequestedGame()
        focusManager.clearFocus()
        delay(700)
        activeGame = game
    }

    // «Поделиться» из другого приложения: файл — вложением, текст — в черновик.
    val shared by activity.sharedIntent.collectAsState()
    LaunchedEffect(shared) {
        val intent = shared ?: return@LaunchedEffect
        activity.consumeSharedIntent()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null && importUri(stream) != null) showToast(t("Файл прикреплён — отправьте его", "File attached — send it"))
        if (!text.isNullOrBlank()) {
            val draft = store.draft.value
            store.draft.value = if (draft.isBlank()) text else "$draft\n$text"
            focusComposer()
        }
    }

    // ---- Поиск в чате ----
    val matches = remember(messages, findOpen, findQuery) { if (findOpen) FindRules.matchingIds(messages, findQuery) else emptyList() }
    val selectedMatch = if (matches.isEmpty()) null else matches[minOf(findIndex, matches.size - 1)]

    // ---- Кнопка «Назад»: сначала закрывается верхнее ----
    BackHandler(enabled = attachmentsOpen) { attachmentsOpen = false }
    BackHandler(enabled = findOpen) { findOpen = false; findQuery = "" }
    BackHandler(enabled = voiceMode) { cancelVoice(); voiceMode = false }
    BackHandler(enabled = drawerOpen) { closeDrawer() }

    val imeVisible = WindowInsets.isImeVisible

    // ---- Раскладка ----
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.sidebar)) {
        val wide = maxWidth >= 840.dp
        val drawerWidth = if (wide) 320.dp else minOf(maxWidth * 0.79f, 360.dp)
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val edgePx = WindowInsets.systemGestures.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr) + with(density) { 40.dp.toPx() }

        val mainScreen: @Composable (Modifier) -> Unit = { modifier ->
            MainScreen(
                modifier = modifier,
                english = english,
                fontScale = fontScale,
                messages = messages,
                chatTitle = chat?.title.orEmpty(),
                hasChat = selectedId != null,
                chat = chat,
                conversationsTitle = { id -> conversations.firstOrNull { it.id == id }?.title },
                generating = generating,
                typingId = typingId,
                status = status,
                autoRead = autoRead,
                findOpen = findOpen,
                findQuery = findQuery,
                findFocus = findFocus,
                matches = matches,
                findIndex = findIndex,
                selectedMatch = selectedMatch,
                scrollRequest = scrollRequest,
                composing = composerFocused && imeVisible,
                actions = actions,
                toastMessage = toast.message,
                attachmentsOpen = attachmentsOpen,
                voice = VoiceUi(voiceMode, voiceHolding, recording, voiceFinalizing, transcript),
                focusRequester = focusRequester,
                toolsMenuOpen = toolsMenuOpen,
                onToolsMenu = { toolsMenuOpen = it },
                onSidebar = { if (wide) sidebarVisible = !sidebarVisible else openDrawer() },
                onToggleAutoRead = {
                    settings.setAutoRead(!settings.autoRead.value)
                    if (!settings.autoRead.value) speech.stopSpeaking()
                },
                onNewChat = { cancelVoice(); speech.stopSpeaking(); store.newChat(); attachmentsOpen = false },
                onFindQuery = { findQuery = it; findIndex = 0 },
                onFindPrevious = { if (matches.isNotEmpty()) { focusManager.clearFocus(); findIndex = (findIndex - 1 + matches.size) % matches.size } },
                onFindNext = { if (matches.isNotEmpty()) { focusManager.clearFocus(); findIndex = (findIndex + 1) % matches.size } },
                onFindClose = { focusManager.clearFocus(); findQuery = ""; findOpen = false },
                onTool = { tool ->
                    toolsMenuOpen = false
                    when (tool) {
                        "games" -> { focusManager.clearFocus(); gameHubOpen = true }
                        "instructions" -> { focusManager.clearFocus(); instructionsTarget = selectedId }
                        "share" -> chat?.let { shareText(context, ChatTranscript.build(it, english)) }
                        "pin" -> selectedId?.let { store.togglePin(setOf(it)) }
                        "attachments" -> { focusManager.clearFocus(); galleryOpen = true }
                        "library" -> { focusManager.clearFocus(); libraryScopeChatId = selectedId; libraryOpen = true } // appui: вложения этого чата
                        "find" -> {
                            focusManager.clearFocus(); findOpen = true
                            scope.launch { delay(150); runCatching { findFocus.requestFocus() } }
                        }
                        "info" -> { focusManager.clearFocus(); chatInfoOpen = true }
                        "archive" -> selectedId?.let {
                            focusManager.clearFocus(); cancelVoice(); store.archiveChat(it)
                            showToast(t("Чат перемещён в архив", "Conversation archived"))
                        }
                        "delete" -> deleteChatConfirm = true
                    }
                },
                onSelectParent = { store.selectChat(it) },
                onInstructions = { focusManager.clearFocus(); instructionsTarget = selectedId },
                onJump = { scrollRequest = ScrollRequest(System.nanoTime(), it) },
                onBranch = { id ->
                    cancelVoice(); speech.stopSpeaking()
                    if (store.forkConversation(id) != null) {
                        showToast(t("Новая ветка: продолжайте с этого сообщения", "New branch: continue from this message"))
                        focusComposer()
                    }
                },
                onComposerFocus = { focused ->
                    composerFocused = focused
                    if (focused && attachmentsOpen) attachmentsOpen = false
                },
                onToggleAttachments = {
                    focusManager.clearFocus(); cancelVoice()
                    attachmentsOpen = !attachmentsOpen
                },
                onStickers = { focusManager.clearFocus(); cancelVoice(); stickersOpen = true },
                onSend = { send() },
                onVoiceTap = { if (voiceHolding) finishVoice() else beginVoice() },
                onVoiceCancel = { cancelVoice(); voiceMode = false },
                onImport = { importUri(it) },
                onError = { deviceError = it },
                onWelcomeTap = { focusManager.clearFocus() },
            )
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(sidebarVisible) {
                    Row {
                        HistoryDrawer(store, settings, english, fontScale, onClose = {},
                            onSettings = { cancelVoice(); speech.stopSpeaking(); settingsOpen = true },
                            onInstructions = { instructionsTarget = it },
                            onOpenFavorites = { cancelVoice(); speech.stopSpeaking(); favoritesFolderId = it },
                            onAllAttachments = { libraryScopeChatId = null; libraryOpen = true },
                            modifier = Modifier.width(drawerWidth).fillMaxHeight())
                        VerticalDivider(color = colors.divider)
                    }
                }
                mainScreen(Modifier.weight(1f).fillMaxHeight().background(colors.background))
            }
        } else {
            val reduce = com.honerai.app.ui.common.LocalReduceMotion.current
            val progress by animateFloatAsState(
                if (drawerOpen) 1f else 0f,
                if (reduce) tween(0) else spring(dampingRatio = 0.91f, stiffness = Spring.StiffnessMediumLow),
                label = "drawer",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(drawerOpen) {
                        // Свайп вправо от левого края открывает историю, влево — закрывает.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var last = start
                            var consumedByChild = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed && change.isConsumed) consumedByChild = true
                                last = change.position
                                if (!change.pressed) break
                            }
                            if (consumedByChild) return@awaitEachGesture
                            val dx = last.x - start.x
                            val dy = last.y - start.y
                            if (abs(dx) <= abs(dy) * 1.6f) return@awaitEachGesture
                            if (drawerOpen && dx < -60.dp.toPx()) drawerOpen = false
                            else if (!drawerOpen && start.x < edgePx && dx > 80.dp.toPx()) openDrawer()
                        }
                    },
            ) {
                if (progress > 0.001f) {
                    HistoryDrawer(store, settings, english, fontScale, onClose = ::closeDrawer,
                        onSettings = { cancelVoice(); speech.stopSpeaking(); closeDrawer(); settingsOpen = true },
                        onInstructions = { instructionsTarget = it },
                        onOpenFavorites = { cancelVoice(); speech.stopSpeaking(); closeDrawer(); favoritesFolderId = it },
                        onAllAttachments = { closeDrawer(); libraryScopeChatId = null; libraryOpen = true },
                        modifier = Modifier.width(drawerWidth).fillMaxHeight().graphicsLayer { alpha = progress })
                }
                mainScreen(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = drawerWidthPx * progress
                            if (progress > 0f) {
                                shape = RoundedCornerShape((28 * progress).dp)
                                clip = true
                            } else {
                                shape = RectangleShape
                                clip = false
                            }
                        }
                        .background(colors.background),
                )
                if (drawerOpen || progress > 0.001f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = drawerWidthPx * progress }
                            .background(Color.Black.copy(alpha = 0.13f * progress))
                            .pointerInput(Unit) { detectTapGestures { drawerOpen = false } }
                            .semantics { contentDescription = t("Закрыть историю", "Close history") },
                    )
                }
            }
        }

        // Загрузка истории: ввод недоступен, пока чаты не прочитаны.
        if (loadingHistory) {
            Box(
                Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { detectTapGestures { } }.testTag("chat.loading"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(t("Загружаю чаты…", "Loading conversations…"), fontSize = 14.sp, color = colors.secondary)
                }
            }
        }

        // Слои на весь экран: настройки, витрина игр, игра.
        FullScreenLayer(visible = settingsOpen, onBack = { settingsOpen = false }) {
            SettingsScreen(onClose = { settingsOpen = false })
        }
        FullScreenLayer(visible = gameHubOpen, onBack = { gameHubOpen = false }) {
            GameHub(
                onPlay = { kind ->
                    gameHubOpen = false
                    scope.launch { delay(350); activeGame = kind }
                },
                onClose = { gameHubOpen = false },
            )
        }
        FullScreenLayer(visible = activeGame != null, onBack = { activeGame = null }) {
            val kind = activeGame
            if (kind != null) {
                GameScreen(kind = kind, onResult = { store.postGameResult(it) }, onClose = { activeGame = null })
            }
        }
        // appui: чат «Избранное» поверх всего.
        FullScreenLayer(visible = favoritesFolderId != null, onBack = { favoritesFolderId = null }) {
            favoritesFolderId?.let { id ->
                com.honerai.app.ui.favorites.FavoritesScreen(store, english, id, onClose = { favoritesFolderId = null })
            }
        }
        // appui: библиотека вложений (текущего чата или всех).
        FullScreenLayer(visible = libraryOpen, onBack = { libraryOpen = false }) {
            com.honerai.app.ui.library.AttachmentLibraryScreen(store, english, libraryScopeChatId,
                onClose = { libraryOpen = false }, onMessage = { showToast(it) })
        }
        com.honerai.app.ui.cloud.CloudLayers(activity) // cloud: чат с администратором, уведомления, плашка
    }

    // ---- Окна ----
    selectedText?.let { content ->
        SelectableTextSheet(
            content = content, english = english, fontScale = fontScale,
            onAction = { fragment, action ->
                store.quote(fragment)
                when (action) {
                    SelectionAction.ASK -> scope.launch { delay(450); runCatching { focusRequester.requestFocus() }; keyboard?.show() }
                    SelectionAction.EXPLAIN, SelectionAction.SIMPLER -> {
                        store.draft.value = if (action == SelectionAction.EXPLAIN) t("Расскажи подробнее об этом", "Tell me more about this")
                        else t("Объясни это проще", "Explain this in simpler words")
                        send()
                    }
                }
            },
            onDismiss = { selectedText = null },
        )
    }
    memoryDraft?.let { text ->
        MemoryEditorSheet(store, text, english, onSaved = { showToast(t("Сохранено в память Honer AI", "Saved to Honer AI memory")) },
            onDismiss = { memoryDraft = null })
    }
    if (galleryOpen) {
        ChatAttachmentsSheet(messages.flatMap { it.attachments }, english, onOpen = { previewAttachment = it }, onDismiss = { galleryOpen = false })
    }
    sourceSheet?.let { selection -> SourceDetailsSheet(selection, english, onDismiss = { sourceSheet = null }) }
    if (chatInfoOpen) {
        ChatInsightSheet(store, english, onPreview = { previewAttachment = it }, onDismiss = { chatInfoOpen = false })
    }
    if (stickersOpen) {
        StickerPickerSheet(english, onSelect = { sticker ->
            // Стикер уходит отдельным сообщением-вложением.
            store.addAttachment(MessageAttachment(name = sticker, kind = AttachmentKind.STICKER))
        }, onDismiss = { stickersOpen = false })
    }
    instructionsTarget?.let { id -> InstructionsSheet(store, id, english, onDismiss = { instructionsTarget = null }) }
    previewAttachment?.let { attachment ->
        AttachmentPreviewDialog(
            attachment = attachment,
            english = english,
            onEdited = { file: File ->
                // Готовый файл из редактора прикрепляется к новому сообщению.
                scope.launch {
                    if (importUri(Uri.fromFile(file)) != null) {
                        showToast(t("Изменённый файл прикреплён — отправьте его", "Edited file attached — send it"))
                    }
                }
            },
            onClose = { previewAttachment = null },
        )
    }
    if (deleteChatConfirm) {
        AlertDialog(
            onDismissRequest = { deleteChatConfirm = false },
            title = { Text(t("Удалить этот чат?", "Delete this conversation?")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteChatConfirm = false
                    focusManager.clearFocus(); cancelVoice()
                    selectedId?.let { store.deleteChats(setOf(it)) }
                }, modifier = Modifier.testTag("chat.delete.confirm")) { Text(t("Удалить", "Delete"), color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteChatConfirm = false }, modifier = Modifier.testTag("chat.delete.cancel")) {
                    Text(t("Отмена", "Cancel"), color = colors.accent)
                }
            },
            containerColor = colors.surface,
        )
    }
    forwardFavoritesMessage?.let { message ->
        // appui: выбор папки избранного для пересылки сообщения из обычного чата.
        val folders = remember(conversations) { com.honerai.app.core.FavoritesLogic.ordered(conversations) }
        AlertDialog(
            onDismissRequest = { forwardFavoritesMessage = null },
            title = { Text(t("Переслать в Избранное", "Forward to Saved")) },
            text = {
                Column {
                    folders.forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    store.forwardToFavorites(folder.id, message)
                                    forwardFavoritesMessage = null
                                    showToast(t("Переслано в Избранное", "Forwarded to Saved"))
                                }.padding(horizontal = 8.dp).testTag("chat.forwardFavorites." + folder.id),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.PushPin, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Text(folder.title, fontSize = 16.sp, color = colors.foreground)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardFavoritesMessage = null }) { Text(t("Отмена", "Cancel"), color = colors.accent) } },
            containerColor = colors.surface,
        )
    }
    deviceError?.let { error ->
        AlertDialog(
            onDismissRequest = { deviceError = null; needsPermissionSettings = false },
            title = { Text(t("Не удалось выполнить действие", "Unable to complete action")) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { deviceError = null; needsPermissionSettings = false },
                    modifier = Modifier.testTag("device.error.dismiss")) { Text("OK", color = colors.accent) }
            },
            dismissButton = if (needsPermissionSettings) {
                {
                    TextButton(onClick = {
                        openAppSettings(context); deviceError = null; needsPermissionSettings = false
                    }, modifier = Modifier.testTag("device.openSettings")) { Text(t("Настройки телефона", "Phone Settings"), color = colors.accent) }
                }
            } else null,
            containerColor = colors.surface,
        )
    }
}

/** Основной экран: шапка, плашки, лента или приветствие, поле ввода и панель вложений. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(
    modifier: Modifier,
    english: Boolean,
    fontScale: Float,
    messages: List<ChatMessage>,
    chatTitle: String,
    hasChat: Boolean,
    chat: com.honerai.app.data.Conversation?,
    conversationsTitle: (String) -> String?,
    generating: Boolean,
    typingId: String?,
    status: String?,
    autoRead: Boolean,
    findOpen: Boolean,
    findQuery: String,
    findFocus: FocusRequester,
    matches: List<String>,
    findIndex: Int,
    selectedMatch: String?,
    scrollRequest: ScrollRequest?,
    composing: Boolean,
    actions: MessageActions,
    toastMessage: String?,
    attachmentsOpen: Boolean,
    voice: VoiceUi,
    focusRequester: FocusRequester,
    toolsMenuOpen: Boolean,
    onToolsMenu: (Boolean) -> Unit,
    onSidebar: () -> Unit,
    onToggleAutoRead: () -> Unit,
    onNewChat: () -> Unit,
    onFindQuery: (String) -> Unit,
    onFindPrevious: () -> Unit,
    onFindNext: () -> Unit,
    onFindClose: () -> Unit,
    onTool: (String) -> Unit,
    onSelectParent: (String) -> Unit,
    onInstructions: () -> Unit,
    onJump: (String) -> Unit,
    onBranch: (String) -> Unit,
    onComposerFocus: (Boolean) -> Unit,
    onToggleAttachments: () -> Unit,
    onStickers: () -> Unit,
    onSend: () -> Unit,
    onVoiceTap: () -> Unit,
    onVoiceCancel: () -> Unit,
    onImport: suspend (Uri) -> MessageAttachment?,
    onError: (String) -> Unit,
    onWelcomeTap: () -> Unit,
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val store = remember { AppContainer.get(context).store }
    fun t(ru: String, en: String) = if (english) en else ru
    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        // Шапка.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HonerCircleButton(t("История чатов", "Chat history"), onSidebar, Modifier.testTag("chat.sidebar")) {
                SidebarSymbol(colors.foreground)
            }
            Text(chatTitle, fontSize = (17 * fontScale).sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            Row(
                Modifier.clip(CircleShape).background(colors.surface.copy(alpha = 0.65f)).border(0.7.dp, colors.divider, CircleShape)
                    .padding(horizontal = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 42.dp, height = 42.dp).clip(CircleShape).clickable(onClick = onToggleAutoRead)
                        .semantics {
                            contentDescription = if (autoRead) t("Выключить озвучивание", "Disable read aloud") else t("Включить озвучивание", "Enable read aloud")
                            stateDescription = if (autoRead) t("Включено", "On") else t("Выключено", "Off")
                        }
                        .testTag("chat.autoread"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (autoRead) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, null,
                        tint = colors.foreground, modifier = Modifier.size(21.dp))
                }
                Box(
                    Modifier.size(width = 42.dp, height = 42.dp).clip(CircleShape).clickable(onClick = onNewChat)
                        .semantics { contentDescription = t("Новый чат", "New chat") }.testTag("chat.new"),
                    contentAlignment = Alignment.Center,
                ) { NewConversationSymbol(colors.foreground) }
                // cloud: вкладка «Уведомления» — в той же капсуле, чтобы не отнимать место у названия чата.
                com.honerai.app.ui.cloud.CloudBellButton(english)
            }
            if (hasChat) {
                Box {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable { onToolsMenu(true) }
                            .semantics { contentDescription = t("Действия с чатом", "Conversation actions") }.testTag("chat.tools"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.MoreHoriz, null, tint = colors.foreground, modifier = Modifier.size(24.dp)) }
                    ChatToolsMenu(toolsMenuOpen, chat?.pinned == true, english, onDismiss = { onToolsMenu(false) }, onTool = onTool)
                }
            }
        }
        UpdateBanner(english)
        // Ветка разговора: ссылка на исходный чат.
        chat?.parentConversationID?.let { parentId ->
            val parentTitle = conversationsTitle(parentId)
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 3.dp).testTag("branch.banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.CallSplit, null, tint = colors.secondary, modifier = Modifier.size(14.dp))
                if (parentTitle != null) {
                    Row(
                        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelectParent(parentId) }
                            .semantics { contentDescription = t("Вернуться к исходному чату: ", "Return to original conversation: ") + parentTitle }
                            .testTag("branch.back"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("Ветка: ", "Branch: ") + parentTitle, fontSize = (12 * fontScale).sp, color = colors.secondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Rounded.NorthWest, null, tint = colors.secondary, modifier = Modifier.size(11.dp))
                    }
                } else {
                    Text(t("Ветка разговора", "Conversation branch"), fontSize = (12 * fontScale).sp, color = colors.secondary)
                }
            }
        }
        val pinned = chat?.instructions.orEmpty()
        AnimatedVisibility(pinned.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            PinnedInstructionBar(pinned, english, onInstructions)
        }
        AnimatedVisibility(findOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            FindBar(findQuery, matches, findIndex, english, findFocus, onFindQuery, onFindPrevious, onFindNext, onFindClose)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (messages.isEmpty()) {
                Welcome(english, fontScale, onWelcomeTap)
            } else {
                MessageTimeline(
                    messages = messages,
                    chatId = chat?.id,
                    generating = generating,
                    typingId = typingId,
                    status = status,
                    pacer = store.pacer,
                    findQuery = if (findOpen) findQuery else "",
                    selectedMatch = selectedMatch,
                    scrollRequest = scrollRequest,
                    composing = composing,
                    english = english,
                    fontScale = fontScale,
                    actions = actions,
                    modifier = Modifier.fillMaxSize(),
                )
                if (messages.size >= 2 && !findOpen) {
                    MessageNavigationStrip(messages, english, onJump = onJump, onBranch = onBranch)
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = voice.holding || voice.finalizing,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                VoiceRecordingOverlay(voice.recording, voice.finalizing, english)
            }
        }
        // Низ: подсказка, поле ввода и панель вложений — над клавиатурой и системной панелью.
        Column(
            Modifier.fillMaxWidth().background(colors.background)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.animation.AnimatedVisibility(toastMessage != null,
                enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                ToastBubble(toastMessage.orEmpty(), Modifier.padding(bottom = 10.dp))
            }
            // agent: карточка подтверждения важного действия агента над полем ввода.
            val pendingAgentAction by store.pendingAgentAction.collectAsState()
            pendingAgentAction?.let { pending ->
                com.honerai.app.ui.agent.ConfirmationCard(
                    action = pending,
                    english = english,
                    fontScale = fontScale,
                    onConfirm = { store.confirmPendingAction(true) },
                    onCancel = { store.confirmPendingAction(false) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
            val lastError = messages.lastOrNull()?.error
            Composer(
                store = store,
                english = english,
                fontScale = fontScale,
                showError = lastError == null,
                attachmentsOpen = attachmentsOpen,
                voice = voice,
                focusRequester = focusRequester,
                onFocusChanged = onComposerFocus,
                onToggleAttachments = onToggleAttachments,
                onStickers = onStickers,
                onSend = onSend,
                onVoiceTap = onVoiceTap,
                onVoiceCancel = onVoiceCancel,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
            androidx.compose.animation.AnimatedVisibility(attachmentsOpen,
                enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                AttachmentTray(store, english, onImport = onImport, onStickers = onStickers, onError = onError,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }
}

/** Меню «•••»: игры, инструкции, поделиться, закрепить, файлы, найти, информация, архив, удалить. */
@Composable
private fun ChatToolsMenu(expanded: Boolean, pinned: Boolean, english: Boolean, onDismiss: () -> Unit, onTool: (String) -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp), containerColor = colors.surface,
        modifier = Modifier.width(260.dp)) {
        ToolItem(Icons.Rounded.SportsEsports, t("Игры с Honer AI", "Games with Honer AI"), "chat.tools.games") { onTool("games") }
        ToolItem(Icons.Rounded.PushPin, t("Инструкции чата", "Chat instructions"), "chat.tools.instructions") { onTool("instructions") }
        HorizontalDivider(color = colors.divider)
        ToolItem(Icons.Rounded.IosShare, t("Поделиться чатом", "Share conversation"), "chat.tools.share") { onTool("share") }
        ToolItem(if (pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin, if (pinned) t("Открепить", "Unpin") else t("Закрепить", "Pin"),
            "chat.tools.pin") { onTool("pin") }
        ToolItem(Icons.Rounded.AttachFile, t("Загруженные файлы", "Uploaded files"), "chat.tools.attachments") { onTool("attachments") }
        ToolItem(Icons.Rounded.PhotoLibrary, t("Вложения этого чата", "Attachments in this chat"), "chat.tools.library") { onTool("library") }
        ToolItem(Icons.Rounded.Search, t("Найти в чате", "Find in conversation"), "chat.tools.find") { onTool("find") }
        ToolItem(Icons.Outlined.Info, t("Информация о чате", "Chat information"), "chat.tools.info") { onTool("info") }
        HorizontalDivider(color = colors.divider)
        ToolItem(Icons.Outlined.Archive, t("В архив", "Archive"), "chat.tools.archive") { onTool("archive") }
        ToolItem(Icons.Outlined.Delete, t("Удалить", "Delete"), "chat.tools.delete", destructive = true) { onTool("delete") }
    }
}

@Composable
private fun ToolItem(icon: ImageVector, title: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (destructive) Color(0xFFFF453A) else colors.foreground
    DropdownMenuItem(
        text = { Text(title, color = tint, fontSize = 16.sp) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/** Поиск по открытому чату: поле, «2 / 5», переход к предыдущему/следующему совпадению. */
@Composable
private fun FindBar(
    query: String,
    matches: List<String>,
    index: Int,
    english: Boolean,
    focus: FocusRequester,
    onQuery: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = HonerTheme.colors
    val focusManager = LocalFocusManager.current
    fun t(ru: String, en: String) = if (english) en else ru
    val counter = FindRules.counter(index, matches.size)
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(colors.surface).padding(start = 12.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(t("Найти в чате", "Find in conversation"), fontSize = 15.sp, color = colors.secondary)
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = TextStyle(color = colors.foreground, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("chat.find.field"),
            )
        }
        Text(counter, fontSize = 12.sp, color = colors.secondary,
            modifier = Modifier.semantics { contentDescription = t("Найденные сообщения", "Matching messages"); stateDescription = counter }
                .testTag("chat.find.count"))
        FindButton(Icons.Rounded.KeyboardArrowUp, t("Предыдущее совпадение", "Previous match"), "chat.find.previous", matches.isNotEmpty(), onPrevious)
        FindButton(Icons.Rounded.KeyboardArrowDown, t("Следующее совпадение", "Next match"), "chat.find.next", matches.isNotEmpty(), onNext)
        FindButton(Icons.Rounded.Close, t("Закрыть поиск", "Close find"), "chat.find.close", true, onClose)
    }
}

@Composable
private fun FindButton(icon: ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Box(
        Modifier.size(width = 32.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) colors.foreground else colors.secondary.copy(alpha = 0.5f), modifier = Modifier.size(20.dp)) }
}

/** Приветствие пустого чата: знак, вопрос и версия приложения (видно, что установилась новая сборка). */
@Composable
private fun Welcome(english: Boolean, fontScale: Float, onTap: () -> Unit) {
    val colors = HonerTheme.colors
    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 30.dp).align(Alignment.Center).offset(y = -maxHeight * 0.07f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(23.dp),
        ) {
            HonerMark(47.dp)
            Text(
                if (english) "Hi! What would you like\nto talk about today?" else "Привет! О чём хотите\nпоговорить сегодня?",
                fontSize = (21 * fontScale).sp, lineHeight = (28 * fontScale).sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("welcomeMessage"),
            )
            val version = DeviceInfo.appVersion
            Text(if (version.isEmpty()) "Honer AI" else "Honer AI $version", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = colors.secondary, modifier = Modifier.testTag("welcomeVersion"))
        }
    }
}

/** Плашка «Доступно обновление»: установка в один тап, ход загрузки. */
@Composable
private fun UpdateBanner(english: Boolean) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by UpdateManager.available.collectAsState()
    val progress by UpdateManager.progress.collectAsState()
    var dismissed by remember { mutableStateOf<String?>(null) }
    val info = update ?: return
    if (dismissed == info.versionName && progress == null) return
    fun t(ru: String, en: String) = if (english) en else ru
    Column(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(colors.surface).border(0.7.dp, colors.divider, RoundedCornerShape(14.dp)).padding(12.dp)
            .testTag("chat.update.banner"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Text(t("Доступно обновление ", "Update available ") + info.versionName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = colors.foreground, modifier = Modifier.weight(1f))
            if (progress == null) {
                TextButton(onClick = { scope.launch { UpdateManager.installNow(context) } }, modifier = Modifier.testTag("chat.update.install")) {
                    Text(t("Обновить", "Update"), color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Rounded.Close, t("Скрыть", "Hide"), tint = colors.secondary,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { dismissed = info.versionName }.padding(5.dp))
            }
        }
        progress?.let { value ->
            LinearProgressIndicator(progress = { value }, color = colors.accent, trackColor = colors.divider, modifier = Modifier.fillMaxWidth())
        }
    }
}
