package com.honerai.admin.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.LocalAttachment
import com.honerai.admin.data.AttachmentKinds
import com.honerai.admin.data.AttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Адрес вложения: локальный черновик (content:// / file://) или файл на сервере. */
fun AttachmentRef.isLocal(): Boolean = url.startsWith("content:") || url.startsWith("file:")

fun AttachmentRef.resolvedUrl(container: AdminContainer): String = if (isLocal()) url else container.api.absolute(url)

/** Плеер Media3 с токеном администратора в заголовке; понимает и http(s), и content:// / file://. */
@OptIn(UnstableApi::class)
fun buildAuthorizedPlayer(context: Context, container: AdminContainer): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(container.api.authHeader()?.let { mapOf("Authorization" to it) } ?: emptyMap())
    val data = DefaultDataSource.Factory(context, http)
    return ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build()
}

/**
 * Один проигрыватель голосовых и аудио на всё приложение: включили новое — предыдущее остановилось.
 * Позиция обновляется 10 раз в секунду только пока что-то играет.
 */
class AudioPlayback(private val context: Context, private val container: AdminContainer) {
    data class State(val id: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    private fun ensurePlayer(): ExoPlayer = player ?: buildAuthorizedPlayer(context, container).also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
                if (isPlaying) startTicker() else { ticker?.cancel(); publish() }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    p.pause(); p.seekTo(0)
                    _state.value = _state.value.copy(playing = false, positionMs = 0)
                } else publish()
            }
        })
        player = p
    }

    fun toggle(id: String, url: String, knownDurationMs: Long?) {
        val p = ensurePlayer()
        if (_state.value.id == id) {
            if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            return
        }
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        _state.value = State(id = id, playing = true, positionMs = 0, durationMs = knownDurationMs ?: 0)
    }

    fun seek(id: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.id != id) return
        val duration = p.duration.takeIf { it > 0 } ?: _state.value.durationMs
        if (duration <= 0) return
        p.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    fun pause() { player?.pause() }

    fun stop() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = State()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = container.scope.launch {
            while (isActive) {
                publish()
                delay(100)
            }
        }
    }

    private fun publish() {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: _state.value.durationMs
        _state.value = _state.value.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = duration)
    }
}

/** Запись голосового: AAC в контейнере m4a (MediaRecorder). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        cancel()
        val target = File(context.cacheDir, "voice").apply { mkdirs() }.let { File(it, "voice-${UUID.randomUUID()}.m4a") }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w("VoiceRecorder", "start failed", e)
            runCatching { r.release() }
            target.delete()
            false
        }
    }

    fun elapsedMs(): Long = if (recorder != null) System.currentTimeMillis() - startedAt else 0

    /** Остановить и вернуть файл и длительность; слишком короткая запись (< 0,7 с) отбрасывается. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val duration = elapsedMs()
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || duration < 700) { f?.delete(); return null }
        return f to duration
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }
}

/** Сведения о выбранном файле: имя, размер, MIME, вид, размеры картинки, длительность. */
suspend fun resolveLocal(context: Context, uri: Uri, forcedKind: String? = null): LocalAttachment? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
            }
        }
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val kind = forcedKind ?: when {
            mime.startsWith("image/") -> AttachmentKinds.IMAGE
            mime.startsWith("video/") -> AttachmentKinds.VIDEO
            mime.startsWith("audio/") -> AttachmentKinds.AUDIO
            else -> AttachmentKinds.FILE
        }
        var width: Int? = null
        var height: Int? = null
        var duration: Long? = null
        if (kind == AttachmentKinds.IMAGE) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0) { width = opts.outWidth; height = opts.outHeight }
        } else if (kind == AttachmentKinds.VIDEO || kind == AttachmentKinds.AUDIO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (kind == AttachmentKinds.VIDEO) {
                    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w != null && h != null) {
                        if (rotation == 90 || rotation == 270) { width = h; height = w } else { width = w; height = h }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { mmr.release() }
            }
        }
        LocalAttachment(uri.toString(), name, mime, size, kind, duration, width, height)
    } catch (e: Exception) {
        Log.w("resolveLocal", "cannot read $uri", e)
        null
    }
}

/** Открыть файл вложения во внешнем приложении (скачивается в кэш при первом открытии). */
suspend fun openAttachment(context: Context, container: AdminContainer, ref: AttachmentRef, onProgress: (Float) -> Unit): Boolean {
    val uri: Uri = if (ref.isLocal()) {
        Uri.parse(ref.url)
    } else {
        val dir = File(context.cacheDir, "media/${ref.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}").apply { mkdirs() }
        val safeName = ref.name.ifBlank { "file" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(dir, safeName)
        if (!target.exists() || target.length() == 0L) container.api.download(ref.url, target, onProgress)
        FileProvider.getUriForFile(context, context.packageName + ".files", target)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, ref.mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, ref.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
