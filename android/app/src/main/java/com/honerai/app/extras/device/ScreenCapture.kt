package com.honerai.app.extras.device

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.honerai.app.data.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Итог снимка или записи экрана. */
data class CaptureOutcome(
    val file: File? = null,
    val galleryUri: Uri? = null,
    val error: String? = null,
    val durationMs: Long = 0L,
    val stoppedEarly: Boolean = false,
    val withAudio: Boolean = false,
)

/** Задания для службы записи: инструмент ждёт итог по номеру задания. */
object ScreenCaptureJobs {
    private val jobs = ConcurrentHashMap<String, CompletableDeferred<CaptureOutcome>>()
    private val _recordingMs = MutableStateFlow<Long?>(null)
    /** Сколько уже записано (null — запись не идёт). */
    val recordingMs: StateFlow<Long?> = _recordingMs.asStateFlow()

    fun create(): Pair<String, CompletableDeferred<CaptureOutcome>> {
        val id = newId()
        val deferred = CompletableDeferred<CaptureOutcome>()
        jobs[id] = deferred
        return id to deferred
    }

    fun complete(id: String?, outcome: CaptureOutcome) {
        if (id == null) return
        jobs.remove(id)?.complete(outcome)
    }

    fun forget(id: String) { jobs.remove(id) }

    internal fun setRecording(ms: Long?) { _recordingMs.value = ms }
}

/** Размер видео и битрейт. Без Android. */
object RecordingSize {
    /**
     * Размер записи: экран, уменьшенный так, чтобы короткая сторона была не больше [maxShortSide] (1080p).
     * Если кодер не принимает размер — пробуем кратный 16, затем уменьшаем на четверть.
     */
    fun choose(width: Int, height: Int, maxShortSide: Int = 1080, supported: (Int, Int) -> Boolean = { _, _ -> true }): Pair<Int, Int> {
        val w0 = max(2, width)
        val h0 = max(2, height)
        var scale = min(1.0, maxShortSide.toDouble() / min(w0, h0))
        var last = align(w0 * scale, 2) to align(h0 * scale, 2)
        repeat(8) {
            val even = align(w0 * scale, 2) to align(h0 * scale, 2)
            if (supported(even.first, even.second)) return even
            val block = align(w0 * scale, 16) to align(h0 * scale, 16)
            if (supported(block.first, block.second)) return block
            last = block
            scale *= 0.75
        }
        return last
    }

    private fun align(value: Double, step: Int): Int = max(step, (value.roundToInt() / step) * step)

    /** Файл длинной записи должен поместиться в чат (вложение видео — до 300 МБ). */
    const val MAX_FILE_BYTES = 280L * 1024 * 1024

    /**
     * Около 0,1 бита на пиксель кадра: 1080×2400 при 30 к/с ≈ 7,8 Мбит/с;
     * для долгой записи ниже, чтобы файл уложился в [MAX_FILE_BYTES] (10 минут ≈ 3,9 Мбит/с).
     */
    fun bitrate(width: Int, height: Int, fps: Int = 30, durationSeconds: Int = 0): Int {
        val quality = (width.toLong() * height * fps / 10).coerceIn(1_500_000L, 12_000_000L)
        if (durationSeconds <= 0) return quality.toInt()
        val budget = (MAX_FILE_BYTES * 8 / durationSeconds - 160_000L).coerceAtLeast(1_000_000L)
        return minOf(quality, budget).toInt()
    }

    /** Кодер H.264 телефона умеет такой размер. */
    fun encoderSupports(width: Int, height: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .any { info: MediaCodecInfo -> info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.isSizeSupported(width, height) }
    }.getOrDefault(true)
}

/** Сохранение в галерею: Pictures/Honer AI и Movies/Honer AI. */
object GallerySaver {
    const val FOLDER = "Honer AI"

    /** На Android 9 и старше нужна запись в общую память. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun saveImage(context: Context, file: File, name: String): Uri? =
        save(context, file, name, "image/png", video = false)

    fun saveVideo(context: Context, file: File, name: String): Uri? =
        save(context, file, name, "video/mp4", video = true)

    private fun save(context: Context, file: File, name: String, mime: String, video: Boolean): Uri? = runCatching {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@runCatching null
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } else {
            if (needsLegacyPermission(context)) return@runCatching null
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
            val directory = File(root, FOLDER).apply { mkdirs() }
            val target = File(directory, name)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
            Uri.fromFile(target)
        }
    }.getOrNull()
}
