package com.honerai.app.device

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.newId
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Ошибки вложений — тексты как на iPhone (русский / английский). */
enum class AttachmentError(val text: String) {
    TOO_LARGE("Размер файла — до 20 МБ / Maximum file size is 20 MB."),
    AUDIO_TOO_LARGE("Аудиофайл должен быть не больше 150 МБ / Maximum audio size is 150 MB."),
    TOO_MUCH_TEXT("В документе больше 160 000 символов. Прикрепите меньший фрагмент. / Document exceeds 160,000 characters."),
    TOO_MANY_PAGES("PDF должен содержать не больше 80 страниц / PDF must contain no more than 80 pages."),
    INVALID_IMAGE("Не удалось прочитать изображение / Could not read this image."),
    UNSUPPORTED("Этот тип файла не поддерживается. Можно: фото, видео, аудио, PDF, Word, Excel, PowerPoint, CSV, код и текст / Unsupported file type."),
    UNREADABLE_TEXT("Не удалось прочитать текст файла / Could not decode this text file."),
    EMPTY_DOCUMENT("В документе не удалось распознать текст / No readable text found in this document."),
    LOCKED_PDF("PDF повреждён или защищён паролем / PDF is invalid or password protected."),
    VIDEO_TOO_LARGE("Видео должно быть не больше 300 МБ / Maximum video size is 300 MB."),
    VIDEO_TOO_LONG("Видео должно быть не длиннее 20 минут / Maximum video duration is 20 minutes."),
    INVALID_VIDEO("Не удалось прочитать видео. Выберите MP4, MOV или M4V. / Could not read the video. Choose MP4, MOV or M4V."),
    UNREADABLE_FILE("Не удалось открыть файл / Could not open the file."),
}

class AttachmentException(val error: AttachmentError) : Exception(error.text)

/**
 * Вложения: фото, видео, аудио, документы (порт AttachmentService.swift).
 * Файл копируется в папку приложения (attachments/<UUID>.<ext>), из него извлекается то,
 * что увидит нейросеть: текст документа, кадры видео, описание аудио. Вся работа — на Dispatchers.IO.
 * Ошибки — [AttachmentException] или [DocumentReaderException]; `message` готов для показа.
 */
object AttachmentImporter {
    const val MAXIMUM_FILE_BYTES = 20L * 1024 * 1024
    const val MAXIMUM_TEXT_LENGTH = 160_000
    const val MAXIMUM_VIDEO_BYTES = 300L * 1024 * 1024
    const val MAXIMUM_VIDEO_SECONDS = 20.0 * 60
    const val MAXIMUM_AUDIO_BYTES = 150L * 1024 * 1024
    private const val MAXIMUM_IMAGE_SIDE = 2048
    private const val MAXIMUM_IMAGE_BYTES = 2_500_000
    private const val MAXIMUM_FRAME_SIDE = 1280

    val audioExtensions = setOf(
        "m4a", "mp3", "wav", "aac", "caf", "aif", "aiff", "flac", "mp4a", "amr", "3gp", "3ga", "ogg", "oga", "opus", "wma", "awb",
    )
    val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp", "3g2", "avi", "ts", "mpg", "mpeg", "wmv")
    val imageExtensions = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "avif", "dng")
    private val plainExtensions = setOf("txt", "md", "markdown", "text", "log")

    /** Копирует файл в папку приложения и извлекает текст/кадры для нейросети. */
    suspend fun import(context: Context, uri: Uri): MessageAttachment = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val info = describe(app, uri)
        when (classify(info)) {
            Kind.AUDIO -> importAudio(app, uri, info)
            Kind.VIDEO -> importVideo(app, uri, info)
            Kind.IMAGE -> {
                if (info.size > MAXIMUM_FILE_BYTES) throw AttachmentException(AttachmentError.TOO_LARGE)
                importImage(app, readLimited(app, uri, MAXIMUM_FILE_BYTES, AttachmentError.TOO_LARGE), info.name)
            }
            Kind.DOCUMENT -> importDocument(app, uri, info)
            Kind.UNSUPPORTED -> throw AttachmentException(AttachmentError.UNSUPPORTED)
        }
    }

    /** Фото из байтов (камера, буфер обмена): уменьшение до 2048 px, JPEG до 2,5 МБ. */
    suspend fun importImage(context: Context, data: ByteArray, name: String = "Фото.jpg"): MessageAttachment = withContext(Dispatchers.IO) {
        if (data.size > MAXIMUM_FILE_BYTES) throw AttachmentException(AttachmentError.TOO_LARGE)
        val bitmap = decodeScaled(data, MAXIMUM_IMAGE_SIDE) ?: throw AttachmentException(AttachmentError.INVALID_IMAGE)
        coroutineContext.ensureActive()
        val jpeg = try {
            compressJpeg(bitmap, MAXIMUM_IMAGE_BYTES)
        } finally {
            bitmap.recycle()
        }
        val id = newId()
        val file = destination(context, id, "jpg")
        try {
            coroutineContext.ensureActive()
            file.writeBytes(jpeg)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        val displayName = name.substringBeforeLast('.', name).ifBlank { "Фото" } + ".jpg"
        MessageAttachment(id = id, name = displayName, kind = AttachmentKind.IMAGE, extractedText = "", localPath = file.absolutePath)
    }

    /** Папка вложений приложения. */
    fun attachmentsDir(context: Context): File = File(context.filesDir, "attachments").apply { mkdirs() }

    // MARK: Что за файл

    internal enum class Kind { IMAGE, VIDEO, AUDIO, DOCUMENT, UNSUPPORTED }

    internal data class FileInfo(val name: String, val extension: String, val mime: String, val size: Long)

    private fun describe(context: Context, uri: Uri): FileInfo {
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it) }?.let { file ->
                name = file.name
                size = file.length()
            }
        }
        val mime = (runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "").lowercase(Locale.ROOT)
        var fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Файл"
        var ext = extensionOf(fileName)
        if (ext.isEmpty()) {
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase(Locale.ROOT) ?: ""
            if (ext.isNotEmpty()) fileName = "$fileName.$ext"
        }
        return FileInfo(fileName, ext, mime, size)
    }

    internal fun extensionOf(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.').lowercase(Locale.ROOT).trim() else ""

    internal fun classify(info: FileInfo): Kind {
        val ext = info.extension
        val mime = info.mime
        // .3gp бывает и видео с камеры, и голосовой записью — решает MIME-тип.
        if (ext == "3gp" || ext == "3g2") return if (mime.startsWith("video/")) Kind.VIDEO else Kind.AUDIO
        if (ext in audioExtensions || (mime.startsWith("audio/") && ext != "mp4")) return Kind.AUDIO
        if (ext in videoExtensions || mime.startsWith("video/")) return Kind.VIDEO
        if (ext in imageExtensions || mime.startsWith("image/")) return Kind.IMAGE
        if (ext == "pdf" || mime == "application/pdf") return Kind.DOCUMENT
        if (DocumentReader.isSupported(ext)) return Kind.DOCUMENT
        if (ext.isEmpty() && (mime.startsWith("text/") || mime == "application/json")) return Kind.DOCUMENT
        return Kind.UNSUPPORTED
    }

    // MARK: Документы

    private suspend fun importDocument(context: Context, uri: Uri, info: FileInfo): MessageAttachment {
        if (info.size > MAXIMUM_FILE_BYTES) throw AttachmentException(AttachmentError.TOO_LARGE)
        val data = readLimited(context, uri, MAXIMUM_FILE_BYTES, AttachmentError.TOO_LARGE)
        coroutineContext.ensureActive()
        var ext = info.extension
        if (ext.isEmpty()) ext = if (info.mime == "application/pdf") "pdf" else if (info.mime == "application/json") "json" else "txt"
        val text: String
        val kind: AttachmentKind
        val summary: String
        if (ext == "pdf") {
            val pdf = extractPDF(context, data)
            text = pdf.first
            summary = "PDF: " + DocumentReader.plural(pdf.second, "страница", "страницы", "страниц")
            kind = AttachmentKind.DOCUMENT
        } else {
            val document = try {
                DocumentReader.extractText(data, ext)
            } catch (e: DocumentReaderException) {
                if (e.error == DocumentReaderError.EMPTY_DOCUMENT) throw AttachmentException(AttachmentError.EMPTY_DOCUMENT)
                if (e.error == DocumentReaderError.UNSUPPORTED_FORMAT) throw AttachmentException(AttachmentError.UNSUPPORTED)
                throw e
            }
            text = document.text
            summary = document.summary
            kind = if (ext in plainExtensions || DocumentReader.kind(ext).startsWith("Код")) AttachmentKind.TEXT else AttachmentKind.DOCUMENT
        }
        if (text.isBlank()) throw AttachmentException(AttachmentError.EMPTY_DOCUMENT)
        if (text.length > MAXIMUM_TEXT_LENGTH) throw AttachmentException(AttachmentError.TOO_MUCH_TEXT)
        val id = newId()
        val file = destination(context, id, ext)
        try {
            coroutineContext.ensureActive()
            file.writeBytes(data)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return MessageAttachment(id = id, name = info.name, kind = kind, extractedText = text, localPath = file.absolutePath, summary = summary)
    }

    @Volatile private var pdfBoxReady = false

    /** Текст PDF по страницам «[Page N]»; сканы без текстового слоя дают пустой текст (распознавания текста на картинках нет). */
    private suspend fun extractPDF(context: Context, data: ByteArray): Pair<String, Int> {
        if (!pdfBoxReady) {
            synchronized(this) {
                if (!pdfBoxReady) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxReady = true
                }
            }
        }
        val document = try {
            PDDocument.load(data)
        } catch (_: InvalidPasswordException) {
            throw AttachmentException(AttachmentError.LOCKED_PDF)
        } catch (_: Exception) {
            throw AttachmentException(AttachmentError.LOCKED_PDF)
        } catch (_: OutOfMemoryError) {
            throw AttachmentException(AttachmentError.TOO_LARGE)
        }
        document.use { pdf ->
            val pageCount = pdf.numberOfPages
            if (pageCount > 80) throw AttachmentException(AttachmentError.TOO_MANY_PAGES)
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val pages = ArrayList<String>()
            var total = 0
            for (index in 1..pageCount) {
                coroutineContext.ensureActive()
                stripper.startPage = index
                stripper.endPage = index
                val text = try {
                    stripper.getText(pdf).trim()
                } catch (_: Exception) {
                    ""
                }
                if (text.isEmpty()) continue
                val content = "[Page $index]\n$text"
                total += content.length
                if (total > MAXIMUM_TEXT_LENGTH) throw AttachmentException(AttachmentError.TOO_MUCH_TEXT)
                pages.add(content)
            }
            if (pages.isEmpty()) throw AttachmentException(AttachmentError.EMPTY_DOCUMENT)
            return pages.joinToString("\n\n") to pageCount
        }
    }

    // MARK: Видео

    private suspend fun importVideo(context: Context, uri: Uri, info: FileInfo): MessageAttachment {
        if (info.size > MAXIMUM_VIDEO_BYTES) throw AttachmentException(AttachmentError.VIDEO_TOO_LARGE)
        val id = newId()
        val ext = info.extension.ifEmpty { "mp4" }
        val saved = destination(context, id, ext)
        val created = arrayListOf(saved)
        var completed = false
        try {
            copyLimited(context, uri, saved, MAXIMUM_VIDEO_BYTES, AttachmentError.VIDEO_TOO_LARGE)
            val retriever = MediaMetadataRetriever()
            try {
                try {
                    retriever.setDataSource(saved.absolutePath)
                } catch (_: Exception) {
                    throw AttachmentException(AttachmentError.INVALID_VIDEO)
                }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val duration = durationMs / 1000.0
                if (duration <= 0 || !hasVideo) throw AttachmentException(AttachmentError.INVALID_VIDEO)
                if (duration > MAXIMUM_VIDEO_SECONDS) throw AttachmentException(AttachmentError.VIDEO_TOO_LONG)

                val count = min(10, max(1, ceil(duration * 2).toInt()))
                val end = max(0.0, duration - min(0.05, duration / 4))
                val frames = ArrayList<String>()
                val labels = ArrayList<String>()
                for (index in 0 until count) {
                    coroutineContext.ensureActive()
                    val second = if (count == 1) duration / 2 else end * index / (count - 1)
                    val bitmap = frameAt(retriever, (second * 1_000_000).toLong()) ?: continue
                    val frame = destination(context, newId(), "jpg")
                    created.add(frame)
                    try {
                        FileOutputStream(frame).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    } finally {
                        bitmap.recycle()
                    }
                    frames.add(frame.absolutePath)
                    labels.add(String.format(Locale.US, "%.2f", second))
                }
                if (frames.isEmpty()) throw AttachmentException(AttachmentError.INVALID_VIDEO)
                var description = "Video duration: ${String.format(Locale.US, "%.1f", duration)} seconds. " +
                    "Sampled frame timestamps in seconds: ${labels.joinToString(", ")}. " +
                    "The frames below are a sample; do not claim to have seen unsampled moments."
                description += if (hasAudio) {
                    "\nThe video has an audio track, but it was not transcribed: on-device transcription of media files " +
                        "is not available in the Android version of the app. Do not guess what is said or heard; " +
                        "if asked, explain that you can only see the sampled frames."
                } else {
                    "\nThe video has no audio track."
                }
                val attachment = MessageAttachment(
                    id = id, name = info.name, kind = AttachmentKind.VIDEO, extractedText = description,
                    localPath = saved.absolutePath, videoFramePaths = frames, summary = "Видео " + clock(duration),
                )
                completed = true
                return attachment
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } finally {
            if (!completed) created.forEach { it.delete() }
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, micros: Long): Bitmap? {
        val raw = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, MAXIMUM_FRAME_SIDE, MAXIMUM_FRAME_SIDE)
                    ?: retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAXIMUM_FRAME_SIDE, MAXIMUM_FRAME_SIDE)
            } else {
                retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        return scaleDown(raw, MAXIMUM_FRAME_SIDE)
    }

    // MARK: Аудио

    private suspend fun importAudio(context: Context, uri: Uri, info: FileInfo): MessageAttachment {
        if (info.size > MAXIMUM_AUDIO_BYTES) throw AttachmentException(AttachmentError.AUDIO_TOO_LARGE)
        val id = newId()
        val ext = info.extension.ifEmpty { "m4a" }
        val saved = destination(context, id, ext)
        var completed = false
        try {
            copyLimited(context, uri, saved, MAXIMUM_AUDIO_BYTES, AttachmentError.AUDIO_TOO_LARGE)
            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(saved.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            val duration = durationMs / 1000.0
            if (duration <= 0) throw AttachmentException(AttachmentError.UNSUPPORTED)
            coroutineContext.ensureActive()
            val text = "Audio recording, duration ${clock(duration)}. " +
                "The recording was not transcribed: on-device transcription of audio files is not available in the Android " +
                "version of the app (Android has no public API for recognizing speech in a file). Do not guess its content; " +
                "if asked, say honestly that you cannot hear the recording and suggest the user dictate or paste the text."
            completed = true
            return MessageAttachment(
                id = id, name = info.name, kind = AttachmentKind.AUDIO, extractedText = text,
                localPath = saved.absolutePath, summary = "Аудио " + clock(duration),
            )
        } finally {
            if (!completed) saved.delete()
        }
    }

    /** 84.2 → "1:24". */
    internal fun clock(seconds: Double): String {
        val total = seconds.toInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    // MARK: Картинки

    /** Декодирование с уменьшением (inSampleSize) до [maxSide] и поворотом по EXIF. */
    internal fun decodeScaled(data: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (_: OutOfMemoryError) {
            options.inSampleSize *= 2
            runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }.getOrNull()
        } ?: return null
        val orientation = runCatching {
            ExifInterface(data.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotated = applyOrientation(decoded, orientation)
        return scaleDown(rotated, maxSide)
    }

    /** Наибольшая степень двойки, при которой картинка ещё не меньше [maxSide] по большей стороне. */
    internal fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return try {
            val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (result !== bitmap) bitmap.recycle()
            result
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        return try {
            val result = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (result !== bitmap) bitmap.recycle()
            result
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    /** JPEG: качество 0,86 и ниже с шагом 0,12, пока файл больше [limit] (как на iPhone). */
    private fun compressJpeg(bitmap: Bitmap, limit: Int): ByteArray {
        // У PNG с прозрачностью фон белый, а не чёрный.
        val source = if (bitmap.hasAlpha()) {
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            flat
        } else bitmap
        try {
            var quality = 86
            var jpeg = encode(source, quality)
            while (jpeg.size > limit && quality > 25) {
                quality -= 12
                jpeg = encode(source, quality)
            }
            return jpeg
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw AttachmentException(AttachmentError.INVALID_IMAGE)
        return stream.toByteArray()
    }

    // MARK: Файлы

    private fun destination(context: Context, id: String, ext: String): File {
        val safe = ext.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(10).ifEmpty { "bin" }
        return File(attachmentsDir(context), "$id.$safe")
    }

    private fun open(context: Context, uri: Uri): InputStream =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            null
        } catch (_: java.io.FileNotFoundException) {
            null
        } ?: throw AttachmentException(AttachmentError.UNREADABLE_FILE)

    private suspend fun readLimited(context: Context, uri: Uri, limit: Long, error: AttachmentError): ByteArray {
        val output = ByteArrayOutputStream()
        open(context, uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw AttachmentException(error)
                output.write(buffer, 0, read)
                if (total % (1024 * 1024) < read) coroutineContext.ensureActive()
            }
        }
        return output.toByteArray()
    }

    private suspend fun copyLimited(context: Context, uri: Uri, target: File, limit: Long, error: AttachmentError) {
        open(context, uri).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw AttachmentException(error)
                    output.write(buffer, 0, read)
                    coroutineContext.ensureActive()
                }
            }
        }
    }
}
