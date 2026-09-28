package com.honerai.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import java.io.File

/** Файл вложения на диске (если он есть). */
fun MessageAttachment.file(): File? = localPath?.let { File(it) }?.takeIf { it.exists() }

/** Первый кадр видео (сохранён при импорте) или сам файл видео. */
fun MessageAttachment.previewFile(): File? =
    videoFramePaths.orEmpty().asSequence().map { File(it) }.firstOrNull { it.exists() } ?: file()

fun MessageAttachment.extension(): String = name.substringAfterLast('.', "").lowercase()

fun attachmentIcon(attachment: MessageAttachment): ImageVector = when (attachment.kind) {
    AttachmentKind.IMAGE -> Icons.Rounded.Image
    AttachmentKind.VIDEO -> Icons.Rounded.SmartDisplay
    AttachmentKind.AUDIO -> Icons.Rounded.GraphicEq
    AttachmentKind.STICKER -> Icons.Rounded.SentimentSatisfied
    AttachmentKind.TEXT -> Icons.AutoMirrored.Rounded.Article
    AttachmentKind.DOCUMENT -> documentIcon(attachment.extension())
}

private fun documentIcon(ext: String): ImageVector = when (ext) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "xlsx", "xlsm", "xls", "csv", "tsv", "ods", "numbers" -> Icons.Rounded.TableChart
    "pptx", "ppt", "odp", "key" -> Icons.Rounded.Slideshow
    "docx", "doc", "rtf", "odt", "pages", "txt", "md" -> Icons.Rounded.Description
    "kt", "swift", "py", "js", "ts", "java", "c", "cpp", "h", "json", "xml", "html", "css", "ipynb" -> Icons.Rounded.Code
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** Вид документа по расширению: «Документ Word», «Таблица Excel»… */
fun documentKind(ext: String, english: Boolean): String {
    val ru = when (ext) {
        "docx", "docm", "dotx", "doc" -> "Документ Word"
        "xlsx", "xlsm", "xltx", "xls" -> "Таблица Excel"
        "pptx", "pptm", "ppt" -> "Презентация PowerPoint"
        "odt" -> "Документ OpenDocument"
        "ods" -> "Таблица OpenDocument"
        "odp" -> "Презентация OpenDocument"
        "rtf" -> "Документ RTF"
        "html", "htm", "xhtml" -> "Веб-страница"
        "epub" -> "Книга EPUB"
        "csv" -> "Таблица CSV"
        "tsv" -> "Таблица TSV"
        "ipynb" -> "Блокнот Jupyter"
        "md", "markdown" -> "Markdown"
        "srt", "vtt" -> "Субтитры"
        "ics" -> "Календарь"
        "vcf" -> "Контакты"
        "log" -> "Журнал"
        "pdf" -> "Документ PDF"
        "txt" -> "Текст"
        "kt", "swift", "py", "js", "ts", "java", "c", "cpp", "h", "json", "xml", "css", "sh", "go", "rs" -> "Код"
        else -> "Файл"
    }
    if (!english) return ru
    return mapOf(
        "Документ Word" to "Word document", "Таблица Excel" to "Excel spreadsheet",
        "Презентация PowerPoint" to "PowerPoint presentation", "Документ OpenDocument" to "OpenDocument text",
        "Таблица OpenDocument" to "OpenDocument spreadsheet", "Презентация OpenDocument" to "OpenDocument presentation",
        "Документ RTF" to "RTF document", "Веб-страница" to "Web page", "Книга EPUB" to "EPUB book",
        "Таблица CSV" to "CSV table", "Таблица TSV" to "TSV table", "Блокнот Jupyter" to "Jupyter notebook",
        "Субтитры" to "Subtitles", "Календарь" to "Calendar", "Контакты" to "Contacts", "Журнал" to "Log",
        "Документ PDF" to "PDF document", "Текст" to "Text", "Код" to "Code", "Файл" to "File",
    )[ru] ?: ru
}

private fun fileTint(attachment: MessageAttachment, accent: Color): Color = when (attachment.extension()) {
    "xlsx", "xlsm", "xls", "csv", "tsv", "ods", "numbers" -> Color(0xFF269E59)
    "docx", "doc", "rtf", "odt", "pages" -> Color(0xFF3373E6)
    "pptx", "ppt", "odp", "key" -> Color(0xFFEB7333)
    "pdf" -> Color(0xFFE04040)
    else -> when (attachment.kind) {
        AttachmentKind.AUDIO -> Color(0xFF9959F2)
        AttachmentKind.TEXT -> Color(0xFF596680)
        else -> accent
    }
}

/**
 * Миниатюра вложения в чате: фото или кадр видео. Картинка уменьшается при чтении,
 * файл, который ещё дописывается, пробуется открыть несколько раз.
 */
@Composable
fun AttachmentThumbnail(
    attachment: MessageAttachment,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    maxWidth: Dp = 280.dp,
    corner: Dp = 14.dp,
) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    var attempt by remember(attachment.id) { mutableIntStateOf(0) }
    var failed by remember(attachment.id) { mutableStateOf(false) }
    val source = remember(attachment.id, attempt) {
        if (attachment.kind == AttachmentKind.VIDEO) attachment.previewFile() else attachment.file()
    }
    LaunchedEffect(failed, attempt) {
        if (failed && attempt < 2) {
            delay(400)
            failed = false
            attempt++
        }
    }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .widthIn(max = maxWidth)
            .height(height)
            .clip(shape)
            .background(colors.raised)
            .border(0.6.dp, colors.divider, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (source == null || (failed && attempt >= 2)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(attachmentIcon(attachment), null, tint = colors.secondary, modifier = Modifier.size(22.dp))
                Text(attachment.name, fontSize = 11.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(source)
                    .size(900)
                    .setParameter("attempt", attempt, memoryCacheKey = null)
                    .build(),
                imageLoader = HonerImages.loader(context),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Карточка файла в сообщении: значок по типу, имя и краткое описание
 * («Таблица Excel: 2 листа», «Аудио 1:24 · расшифровано»).
 */
@Composable
fun AttachmentFileCard(attachment: MessageAttachment, english: Boolean, modifier: Modifier = Modifier, scale: Float = 1f) {
    val colors = HonerTheme.colors
    val tint = fileTint(attachment, colors.accent)
    val subtitle = attachment.summary?.takeIf { it.isNotEmpty() }
        ?: if (attachment.kind == AttachmentKind.TEXT || attachment.kind == AttachmentKind.DOCUMENT) documentKind(attachment.extension(), english)
        else if (english) "File" else "Файл"
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(colors.surface)
            .border(0.8.dp, colors.divider, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size((42 * scale).dp).clip(RoundedCornerShape(11.dp))
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(attachmentIcon(attachment), null, tint = Color.White, modifier = Modifier.size((20 * scale).dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(attachment.name, fontSize = (14.5f * scale).sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = (12 * scale).sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (attachment.kind == AttachmentKind.AUDIO) {
            Icon(Icons.Rounded.PlayCircle, null, tint = tint, modifier = Modifier.size((28 * scale).dp))
        } else {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size((18 * scale).dp))
        }
    }
}

/** Вложение над полем ввода до отправки: фото и видео — миниатюрой, файлы — карточкой. */
@Composable
fun PendingAttachmentChip(attachment: MessageAttachment, removeLabel: String, english: Boolean, onRemove: () -> Unit) {
    Box(Modifier.padding(top = 8.dp, end = 8.dp)) {
        when (attachment.kind) {
            AttachmentKind.IMAGE, AttachmentKind.VIDEO -> Box(contentAlignment = Alignment.Center,
                modifier = Modifier.semantics { contentDescription = attachment.name }) {
                AttachmentThumbnail(attachment, Modifier.size(64.dp), height = 64.dp, maxWidth = 64.dp, corner = 12.dp)
                if (attachment.kind == AttachmentKind.VIDEO) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp).shadow(2.dp, CircleShape))
                }
            }
            AttachmentKind.STICKER -> Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Text(attachment.name, fontSize = 40.sp)
            }
            else -> AttachmentFileCard(attachment, english, Modifier.widthIn(max = 230.dp), scale = 0.85f)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-10).dp)
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = removeLabel }
                .testTag("attachment.remove." + attachment.id),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cancel, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Крупный стикер-эмодзи в сообщении. */
@Composable
fun StickerView(emoji: String, modifier: Modifier = Modifier) {
    Box(modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 54.sp)
    }
}
