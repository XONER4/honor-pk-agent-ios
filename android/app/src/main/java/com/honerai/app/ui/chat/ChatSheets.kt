package com.honerai.app.ui.chat

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.honerai.app.core.ChatStoreApi
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.ui.common.AttachmentThumbnail
import com.honerai.app.ui.common.EmptyState
import com.honerai.app.ui.common.HonerImages
import com.honerai.app.ui.common.HonerSheet
import com.honerai.app.ui.common.MediaLinks
import com.honerai.app.ui.common.MediaPlayerView
import com.honerai.app.ui.common.TimeText
import com.honerai.app.ui.common.attachmentIcon
import com.honerai.app.ui.common.copyToClipboard
import com.honerai.app.ui.common.extension
import com.honerai.app.ui.common.file
import com.honerai.app.ui.common.openFileExternally
import com.honerai.app.ui.common.openUrl
import com.honerai.app.ui.common.shareFile
import com.honerai.app.ui.editor.PhotoEditorScreen
import com.honerai.app.ui.editor.VideoEditorScreen
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// -------------------------------------------------------------------------------------------------
// Источники ответа.

@Composable
fun SourceDetailsSheet(selection: SourceSelection, english: Boolean, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val displayed = remember(selection) {
        selection.sources.withIndex().filter { !selection.readOnly || it.value.content != null }
    }
    HonerSheet(
        title = if (selection.readOnly) (if (english) "Read pages" else "Прочитанные страницы") else (if (english) "Sources" else "Источники"),
        onDismiss = onDismiss,
        doneLabel = if (english) "Done" else "Готово",
        doneTag = "sources.close",
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).background(colors.surface),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(27.dp),
        ) {
            if (displayed.isEmpty()) {
                item {
                    Text(if (english) "Full pages have not been retrieved. Search snippets are available."
                    else "Полный текст страниц пока не получен. Доступны поисковые фрагменты.",
                        color = colors.secondary, modifier = Modifier.padding(vertical = 25.dp))
                }
            }
            items(displayed, key = { it.value.id }) { (index, source) ->
                var expanded by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("sources.row." + source.id)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceSiteIcon(source.url, 24f)
                        Text(MediaLinks.host(source.url) ?: source.url, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.foreground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("[${index + 1}]", fontSize = 12.sp, color = colors.secondary)
                    }
                    SearchEngineChips(source.engines, english) // media: какие поисковики нашли страницу
                    Text(source.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                        modifier = Modifier.clickable { openUrl(context, source.url) }.testTag("message.source." + source.id))
                    Text(source.snippet, fontSize = 14.sp, color = colors.secondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    source.fetchedAt?.let {
                        Text((if (english) "Read " else "Прочитано ") + TimeText.dateTime(it, english), fontSize = 11.sp, color = colors.secondary)
                    }
                    val content = source.content
                    if (content != null) {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (english) "Retrieved text" else "Прочитанный текст", fontSize = 13.sp, color = colors.accent)
                            Icon(if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                null, tint = colors.accent, modifier = Modifier.size(16.dp))
                        }
                        AnimatedVisibility(expanded) {
                            SelectionContainer {
                                Text(content, fontSize = 13.sp, color = colors.secondary, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Загруженные файлы чата.

@Composable
fun ChatAttachmentsSheet(attachments: List<MessageAttachment>, english: Boolean, onOpen: (MessageAttachment) -> Unit, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    val unique = remember(attachments) { attachments.distinctBy { it.id } }
    HonerSheet(
        title = if (english) "Uploaded files" else "Загруженные файлы",
        onDismiss = onDismiss,
        doneLabel = if (english) "Done" else "Готово",
        doneTag = "chat.attachments.close",
    ) { close ->
        if (unique.isEmpty()) {
            EmptyState(Icons.Rounded.AttachFile, if (english) "No files in this conversation yet" else "В этом чате пока нет файлов",
                Modifier.padding(vertical = 60.dp).testTag("chat.attachments.empty"))
        } else {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(unique, key = { it.id }) { attachment ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { close(); onOpen(attachment) }
                            .padding(vertical = 6.dp).testTag("chat.attachments.file." + attachment.id),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (attachment.kind == AttachmentKind.IMAGE && attachment.file() != null) {
                            AttachmentThumbnail(attachment, Modifier.size(44.dp), height = 44.dp, maxWidth = 44.dp, corner = 8.dp)
                        } else if (attachment.kind == AttachmentKind.STICKER) {
                            Text(attachment.name, fontSize = 28.sp, modifier = Modifier.size(44.dp), textAlign = TextAlign.Center)
                        } else {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(attachmentIcon(attachment), null, tint = colors.foreground, modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(attachment.name, fontSize = 16.sp, color = colors.foreground, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Просмотр вложения на весь экран (с редактором фото и видео).

/**
 * Просмотр вложения: фото с увеличением, видео и аудио — плеером, PDF — страницами,
 * остальные файлы — текстом или во внешнем приложении. [onEdited] — изменённый файл
 * из редактора прикрепляется к новому сообщению (null — редактирование недоступно).
 */
@Composable
fun AttachmentPreviewDialog(attachment: MessageAttachment, english: Boolean, onEdited: ((File) -> Unit)?, onClose: () -> Unit) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    fun t(ru: String, en: String) = if (english) en else ru
    val file = remember(attachment.id) { attachment.file() }
    var editing by remember { mutableStateOf(false) }
    var showsText by remember { mutableStateOf(false) }
    val editable = (attachment.kind == AttachmentKind.IMAGE || attachment.kind == AttachmentKind.VIDEO) && file != null && onEdited != null
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            if (editing && file != null) {
                val save: (File) -> Unit = { edited -> editing = false; onEdited?.invoke(edited); onClose() }
                if (attachment.kind == AttachmentKind.VIDEO) VideoEditorScreen(file, save) { editing = false }
                else PhotoEditorScreen(file, save) { editing = false }
                return@Box
            }
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("attachment.preview.close")) {
                        Text(t("Готово", "Done"), color = colors.accent, fontWeight = FontWeight.SemiBold)
                    }
                    Text(attachment.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    if (editable) {
                        IconButton(onClick = { editing = true }, modifier = Modifier.testTag("attachment.preview.edit")) {
                            Icon(Icons.Rounded.Tune, t("Редактировать", "Edit"), tint = colors.accent)
                        }
                    }
                    if (attachment.extractedText.isNotEmpty() && attachment.kind != AttachmentKind.IMAGE) {
                        IconButton(onClick = { showsText = !showsText }, modifier = Modifier.testTag("attachment.preview.textToggle")) {
                            Icon(if (showsText) Icons.Rounded.Description else Icons.AutoMirrored.Rounded.Notes, t("Текст файла", "File text"),
                                tint = colors.accent)
                        }
                    }
                    if (file != null) {
                        IconButton(onClick = { shareFile(context, file) }, modifier = Modifier.testTag("attachment.preview.share")) {
                            Icon(Icons.Rounded.IosShare, t("Поделиться оригиналом", "Share original"), tint = colors.accent)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        attachment.kind == AttachmentKind.STICKER -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(attachment.name, fontSize = 120.sp)
                            TextButton(onClick = { copyToClipboard(context, attachment.name) }, modifier = Modifier.testTag("attachment.preview.copySticker")) {
                                Icon(Icons.Rounded.ContentCopy, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Text(" " + t("Копировать", "Copy"), color = colors.accent)
                            }
                        }
                        showsText && attachment.extractedText.isNotEmpty() -> PlainText(attachment.extractedText, "attachment.preview.extracted")
                        (attachment.kind == AttachmentKind.VIDEO || attachment.kind == AttachmentKind.AUDIO) && file != null ->
                            MediaPlayerView(Uri.fromFile(file), Modifier.fillMaxSize().testTag("attachment.preview.video"))
                        attachment.kind == AttachmentKind.IMAGE && file != null -> ZoomableImage(file)
                        file != null && attachment.extension() == "pdf" -> PdfPages(file)
                        file != null -> Column(Modifier.fillMaxSize().testTag("attachment.preview.original")) {
                            if (attachment.extractedText.isNotEmpty()) {
                                Box(Modifier.weight(1f)) { PlainText(attachment.extractedText, "attachment.preview.text") }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            TextButton(onClick = { openFileExternally(context, file) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Text(" " + t("Открыть в другом приложении", "Open in another app"), color = colors.accent)
                            }
                        }
                        attachment.extractedText.isNotEmpty() -> PlainText(attachment.extractedText, "attachment.preview.text")
                        else -> Text(t("Оригинал файла недоступен на этом устройстве.", "The original file is unavailable on this device."),
                            color = colors.secondary, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainText(text: String, tag: String) {
    SelectionContainer(Modifier.fillMaxSize().testTag(tag)) {
        Text(text, fontSize = 15.sp, color = HonerTheme.colors.foreground,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp))
    }
}

/** Фото с увеличением двумя пальцами; двойное касание — вернуть масштаб. */
@Composable
private fun ZoomableImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).size(2400).build(),
        imageLoader = HonerImages.loader(context),
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
            .testTag("attachment.preview.image"),
    )
}

/** PDF постранично (PdfRenderer): страницы рисуются в фоне по мере прокрутки. */
@Composable
private fun PdfPages(file: File) {
    val colors = HonerTheme.colors
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt().coerceIn(600, 1600)
    val lock = remember { Mutex() }
    val renderer by produceState<PdfRenderer?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
        }
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    val pdf = renderer
    if (pdf == null) {
        CircularProgressIndicator(color = colors.accent)
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("attachment.preview.pdf"), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(pdf.pageCount) { index ->
            val bitmap by produceState<Bitmap?>(null, index) {
                value = withContext(Dispatchers.IO) {
                    lock.withLock {
                        runCatching {
                            pdf.openPage(index).use { page ->
                                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }.getOrNull()
                    }
                }
            }
            val image = bitmap
            if (image != null) {
                Image(image.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height))
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White.copy(alpha = 0.08f)))
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// «Запомнить»: факт для памяти Honer AI.

private const val MAX_MEMORY_LENGTH = 1200
private const val MAX_MEMORY_COUNT = 5000

@Composable
fun MemoryEditorSheet(store: ChatStoreApi, initialText: String, english: Boolean, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val colors = HonerTheme.colors
    fun t(ru: String, en: String) = if (english) en else ru
    val memories by store.memories.collectAsState()
    var draft by remember { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }
    val cleaned = draft.trim()
    val full = memories.size >= MAX_MEMORY_COUNT
    val canSave = cleaned.isNotEmpty() && cleaned.length <= MAX_MEMORY_LENGTH && !full
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }
    HonerSheet(
        title = t("Запомнить", "Remember"),
        onDismiss = onDismiss,
        modifier = Modifier.testTag("memory.editor.page"),
        leading = { close ->
            TextButton(onClick = close, modifier = Modifier.testTag("memory.editor.cancel")) { Text(t("Отмена", "Cancel"), color = colors.accent) }
        },
        trailing = {
            TextButton(
                onClick = {
                    if (store.addMemory(cleaned)) { onSaved(); onDismiss() }
                    else error = store.errorMessage.value ?: t("Не удалось сохранить", "Could not save")
                },
                enabled = canSave,
                modifier = Modifier.testTag("memory.editor.save"),
            ) { Text(t("Сохранить", "Save"), color = if (canSave) colors.accent else colors.secondary, fontWeight = FontWeight.SemiBold) }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                textStyle = TextStyle(color = colors.foreground, fontSize = 17.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp).clip(shape).background(colors.surface)
                    .padding(12.dp).focusRequester(focus)
                    .semantics { contentDescription = t("Факт для памяти Honer AI", "Fact for Honer AI memory") }
                    .testTag("memory.editor.text"),
            )
            Text("${cleaned.length} / $MAX_MEMORY_LENGTH", fontSize = 12.sp,
                color = if (cleaned.length > MAX_MEMORY_LENGTH) Color(0xFFFF453A) else colors.secondary,
                modifier = Modifier.fillMaxWidth().testTag("memory.editor.count"), textAlign = TextAlign.End)
            Text(t("Оставьте только факт или предпочтение, которое стоит помнить. Honer AI будет учитывать его в следующих ответах, пока память включена. Сохранение — только по вашей кнопке.",
                "Keep only a fact or preference worth remembering. Honer AI will use it in future replies while memory is enabled. Nothing is saved until you tap Save."),
                fontSize = 12.sp, color = colors.secondary)
            error?.let { Text(it, color = Color(0xFFFF453A), fontSize = 14.sp, modifier = Modifier.testTag("memory.editor.error")) }
            if (full) {
                Text(t("Память заполнена. Удалите ненужный факт, чтобы добавить новый.",
                    "Memory is full. Delete an existing fact before adding another."), fontSize = 14.sp, color = colors.secondary)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
