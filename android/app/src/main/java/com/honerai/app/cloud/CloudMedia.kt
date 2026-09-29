package com.honerai.app.cloud

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import java.io.File
import java.io.IOException
import java.util.UUID

/** Файлы чата с администратором: подготовка к отправке, кэш загрузок, сохранение в галерею/«Загрузки». */
object CloudMedia {
    const val MAX_BYTES = 100L * 1024 * 1024

    /** Вид вложения по MIME-типу (как в API: image | video | audio | file). */
    fun kindOf(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "file"
    }

    /** Имя файла без символов, которые не любят файловые системы. */
    fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(120).ifEmpty { "file" }

    /** Копия выбранного файла в очередь отправки с размерами и длительностью. */
    suspend fun importUri(context: Context, uri: Uri): LocalAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        if (uri.scheme == "file") uri.path?.let { name = File(it).name }
        if (size > MAX_BYTES) throw IOException("too_large")
        val mime = resolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val target = File(CloudManager.outboxDir, UUID.randomUUID().toString().take(8) + "_" + safeName(name))
        val input = resolver.openInputStream(uri) ?: throw IOException("unreadable")
        input.use { source ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { out.close(); target.delete(); throw IOException("too_large") }
                    out.write(buffer, 0, n)
                }
            }
        }
        describe(target, name, mime, kindOf(mime))
    }

    /** Сведения о файле: размеры картинки (с учётом поворота EXIF), размеры и длительность видео/аудио. */
    fun describe(file: File, name: String, mime: String, kind: String): LocalAttachment {
        var width: Int? = null
        var height: Int? = null
        var duration: Long? = null
        when (kind) {
            "image" -> runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                val rotation = ExifInterface(file.path).rotationDegrees
                if (options.outWidth > 0) {
                    if (rotation == 90 || rotation == 270) { width = options.outHeight; height = options.outWidth }
                    else { width = options.outWidth; height = options.outHeight }
                }
            }
            "video", "audio", "voice" -> runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (kind == "video") {
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rotation == 90 || rotation == 270) { width = h; height = w } else { width = w; height = h }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
        return LocalAttachment(file.path, kind, name, mime, file.length(), duration, width, height)
    }

    /** Файл вложения в кэше (скачивается один раз). Своё только что отправленное — локальная копия. */
    suspend fun localFile(attachment: CloudAttachment, localPath: String?): File = withContext(Dispatchers.IO) {
        localPath?.let { File(it) }?.takeIf { it.exists() }?.let { return@withContext it }
        val extension = attachment.name.substringAfterLast('.', "").takeIf { it.length in 1..6 }
            ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mime) ?: "bin"
        val key = safeName(attachment.id.ifEmpty { attachment.url.hashCode().toString() })
        val target = File(CloudManager.mediaCacheDir, "$key.$extension")
        if (target.exists() && target.length() > 0) return@withContext target
        downloadLock.withLock {
            if (!(target.exists() && target.length() > 0)) CloudManager.api.download(attachment.url, target)
        }
        target
    }

    private val downloadLock = Mutex()

    /**
     * Сохранить в память телефона: фото — «Изображения/Honer AI», видео — «Фильмы/Honer AI»,
     * остальное — «Загрузки/Honer AI». Возвращает папку, куда сохранено.
     * До Android 10 нужно разрешение WRITE_EXTERNAL_STORAGE (его спрашивает экран).
     */
    suspend fun saveToDevice(context: Context, attachment: CloudAttachment, localPath: String?): String = withContext(Dispatchers.IO) {
        val source = localFile(attachment, localPath)
        val name = safeName(attachment.name.ifBlank { source.name })
        val (collection, folder) = when (attachment.kind) {
            "image" -> (if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else null) to Environment.DIRECTORY_PICTURES
            "video" -> (if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else null) to Environment.DIRECTORY_MOVIES
            else -> (if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else null) to Environment.DIRECTORY_DOWNLOADS
        }
        if (Build.VERSION.SDK_INT >= 29 && collection != null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, attachment.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Honer AI")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val item = resolver.insert(collection, values) ?: throw IOException("insert failed")
            try {
                resolver.openOutputStream(item)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IOException("open failed")
                resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(item, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), "Honer AI").apply { mkdirs() }
            var target = File(dir, name)
            var index = 1
            while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " ($index)." + name.substringAfterLast('.', "bin")).also { index++ }
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(attachment.mime), null)
        }
        "$folder/Honer AI"
    }
}

/** Загрузчик картинок Coil с токеном устройства для адресов нашего сервера. */
object CloudImages {
    @Volatile private var instance: ImageLoader? = null

    fun loader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: run {
            val client = CloudHttp.media.newBuilder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val header = CloudManager.authHeader()
                if (header != null && CloudUrls.isOwnUrl(CloudConfig.baseUrl, request.url.toString())) {
                    chain.proceed(request.newBuilder().header("Authorization", header).build())
                } else chain.proceed(request)
            }).build()
            ImageLoader.Builder(context.applicationContext)
                .okHttpClient(client)
                .components {
                    add(VideoFrameDecoder.Factory())
                    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                }
                .crossfade(true)
                .respectCacheHeaders(false)
                .build()
        }.also { instance = it }
    }
}

/** Запись голосового сообщения: AAC в контейнере MP4 (.m4a), моно 44,1 кГц. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        cancel()
        val target = File(CloudManager.outboxDir, "voice_" + System.currentTimeMillis() + ".m4a")
        val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(64_000)
            created.setOutputFile(target.path)
            created.prepare()
            created.start()
            recorder = created
            file = target
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { created.release() }
            target.delete()
            false
        }
    }

    /** Громкость 0..1 для индикатора. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    fun elapsedMs(): Long = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    /** Остановить и вернуть вложение; слишком короткая запись (меньше 0,7 с) — null. */
    fun stop(): LocalAttachment? {
        val active = recorder ?: return null
        val target = file
        val duration = elapsedMs()
        recorder = null
        file = null
        val ok = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        if (!ok || target == null || duration < 700 || !target.exists()) { target?.delete(); return null }
        return LocalAttachment(target.path, "voice", target.name, "audio/mp4", target.length(), duration)
    }

    fun cancel() {
        val active = recorder ?: return
        recorder = null
        runCatching { active.stop() }
        runCatching { active.release() }
        file?.delete()
        file = null
    }
}

/** Проигрыватель голосовых и аудио в ленте: один на приложение, с ходом воспроизведения. */
object CloudAudio {
    data class State(val key: String? = null, val playing: Boolean = false, val loading: Boolean = false,
                     val positionMs: Long = 0, val durationMs: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Нажали «играть/пауза» у вложения [key]; [file] достаёт файл (скачивает при необходимости). */
    fun toggle(key: String, file: suspend () -> File) {
        val current = _state.value
        val active = player
        if (current.key == key && active != null) {
            if (active.isPlaying) { active.pause(); _state.value = current.copy(playing = false) }
            else { active.start(); _state.value = current.copy(playing = true); tick() }
            return
        }
        stop()
        _state.value = State(key = key, loading = true)
        scope.launch {
            val source = runCatching { file() }.getOrNull()
            if (_state.value.key != key) return@launch
            if (source == null) { _state.value = State(); return@launch }
            val created = MediaPlayer()
            try {
                created.setDataSource(source.path)
                created.prepare()
                created.setOnCompletionListener {
                    _state.value = _state.value.copy(playing = false, positionMs = 0)
                    ticker?.cancel()
                }
                created.start()
                player = created
                _state.value = State(key = key, playing = true, durationMs = created.duration.toLong().coerceAtLeast(0))
                tick()
            } catch (e: Exception) {
                runCatching { created.release() }
                _state.value = State()
            }
        }
    }

    fun seek(key: String, fraction: Float) {
        val active = player ?: return
        if (_state.value.key != key) return
        val position = (fraction.coerceIn(0f, 1f) * active.duration).toInt()
        runCatching { active.seekTo(position) }
        _state.value = _state.value.copy(positionMs = position.toLong())
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        _state.value = State()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val active = player ?: break
                val playing = runCatching { active.isPlaying }.getOrDefault(false)
                _state.value = _state.value.copy(positionMs = runCatching { active.currentPosition.toLong() }.getOrDefault(0), playing = playing)
                if (!playing) break
                delay(100)
            }
        }
    }
}
