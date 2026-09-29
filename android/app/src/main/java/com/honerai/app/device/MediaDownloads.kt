package com.honerai.app.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.honerai.app.R
import com.honerai.app.core.HonerHttp
import com.honerai.app.core.MediaKinds
import com.honerai.app.core.WebPageText
import com.honerai.app.core.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import okhttp3.Request
import java.io.File
import java.io.OutputStream

// media: «Скачать» и «Поделиться» у карточек медиа в ответе.

/** Вид медиа — от него зависят папка (Pictures/Movies/Music) и тип файла. */
enum class MediaKind(val folder: String, val fallbackMime: String) {
    // Значения Environment.DIRECTORY_PICTURES/MOVIES/MUSIC — строками, чтобы имена проверялись без Android.
    IMAGE("Pictures", "image/jpeg"),
    VIDEO("Movies", "video/mp4"),
    AUDIO("Music", "audio/mpeg"),
}

/** Состояние загрузки одной ссылки для индикатора на карточке. */
sealed class DownloadState {
    /** [progress] от 0 до 1; null — размер неизвестен. */
    data class Running(val progress: Float?) : DownloadState()
    data class Done(val uri: Uri) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/** Имена и типы файлов: без Android, проверяется модульными тестами. */
object MediaFileNames {
    private val unsafe = Regex("[^\\p{L}\\p{N}._ -]+")

    /** Имя файла из ссылки или подписи, с расширением по типу ответа. */
    fun fileName(url: String, caption: String, mime: String?, kind: MediaKind): String {
        val fromUrl = runCatching { Uri.decode(url.substringBefore('?').substringAfterLast('/')) }.getOrNull()
            ?: url.substringBefore('?').substringAfterLast('/')
        val urlExtension = MediaKinds.extension(url)
        val base = caption.trim().ifEmpty { fromUrl.substringBeforeLast('.') }.ifEmpty { "honer" }
        val clean = unsafe.replace(base, " ").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "honer" }
        val extension = extensionFor(mime) ?: urlExtension.takeIf { it.isNotEmpty() && it.length <= 5 } ?: defaultExtension(kind)
        return "$clean.$extension"
    }

    fun extensionFor(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/opus" -> "opus"
        else -> null
    }

    fun mimeFor(extension: String, kind: MediaKind): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> if (kind == MediaKind.AUDIO) "audio/webm" else "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        else -> kind.fallbackMime
    }

    private fun defaultExtension(kind: MediaKind) = when (kind) { MediaKind.IMAGE -> "jpg"; MediaKind.VIDEO -> "mp4"; MediaKind.AUDIO -> "mp3" }

    /** Тип ответа годится для этого вида медиа (а не страница с ошибкой). */
    fun acceptable(mime: String?, kind: MediaKind): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return true
        if (type.isEmpty() || type == "application/octet-stream" || type == "binary/octet-stream") return true
        return when (kind) {
            MediaKind.IMAGE -> type.startsWith("image/")
            MediaKind.VIDEO -> type.startsWith("video/") || type == "application/vnd.apple.mpegurl"
            MediaKind.AUDIO -> type.startsWith("audio/") || type == "application/ogg" || type == "video/webm"
        }
    }
}

/**
 * Загрузки медиа: OkHttp → MediaStore (Android 10+, папка «Honer AI» в Pictures/Movies/Music)
 * или общая папка с разрешением на запись (Android 7–9). По завершении — системное уведомление.
 */
object MediaDownloads {
    private const val CHANNEL = "honer.downloads.v1"
    private const val FOLDER = "Honer AI"
    private const val MAX_BYTES = 1_500L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private fun set(url: String, state: DownloadState?) {
        _states.value = if (state == null) _states.value - url else _states.value + (url to state)
    }

    /** На Android 7–9 для общей папки нужно разрешение на запись. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun download(context: Context, url: String, caption: String, kind: MediaKind) {
        val app = context.applicationContext
        if (_states.value[url] is DownloadState.Running) return
        if (!WebPageText.isPublicWebURL(url)) { set(url, DownloadState.Failed("bad url")); return }
        set(url, DownloadState.Running(null))
        scope.launch {
            val result = runCatching { save(app, url, caption, kind) }
            result.onSuccess { (uri, name) ->
                set(url, DownloadState.Done(uri))
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, text(app, "Сохранено: $name", "Saved: $name"), Toast.LENGTH_SHORT).show()
                }
                notifyDone(app, uri, name, kind)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                set(url, DownloadState.Failed(error.message ?: "error"))
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, text(app, "Не удалось скачать файл", "Couldn't download the file"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun text(context: Context, ru: String, en: String): String = runCatching {
        com.honerai.app.AppContainer.get(context).settings.text(ru, en)
    }.getOrDefault(ru)

    private suspend fun save(context: Context, url: String, caption: String, kind: MediaKind): Pair<Uri, String> {
        val request = Request.Builder().url(url).header("User-Agent", HonerHttp.MOBILE_AGENT).build()
        HonerHttp.base.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("empty")
            val mime = body.contentType()?.toString() ?: response.header("Content-Type")
            if (!MediaFileNames.acceptable(mime, kind)) throw IllegalStateException("not media: $mime")
            val length = body.contentLength().takeIf { it > 0 }
            if (length != null && length > MAX_BYTES) throw IllegalStateException("too large")
            val name = MediaFileNames.fileName(url, caption, mime, kind)
            val type = MediaFileNames.extensionFor(mime)?.let { mime?.substringBefore(';') }
                ?: MediaFileNames.mimeFor(name.substringAfterLast('.'), kind)
            val input = body.byteStream()
            fun copy(out: OutputStream) {
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    if (total > MAX_BYTES) throw IllegalStateException("too large")
                    if (total - lastReport >= 128 * 1024) {
                        lastReport = total
                        set(url, DownloadState.Running(length?.let { (total.toFloat() / it).coerceIn(0f, 1f) }))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = when (kind) {
                    MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${kind.folder}/$FOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
                try {
                    resolver.openOutputStream(uri)?.use { copy(it) } ?: throw IllegalStateException("no stream")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Throwable) {
                    // Недокачанная запись нашего же файла не должна остаться в галерее.
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                uri to name
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(kind.folder), FOLDER)
                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("no folder")
                val file = uniqueFile(directory, name)
                try {
                    file.outputStream().use { copy(it) }
                } catch (e: Throwable) {
                    file.delete()
                    throw e
                }
                // Адрес content:// выдаёт сканер медиа: по нему файл откроется из уведомления.
                val scanned = withTimeoutOrNull(5_000) {
                    suspendCancellableCoroutine<Uri?> { continuation ->
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(type)) { _, uri ->
                            if (continuation.isActive) continuation.resume(uri)
                        }
                    }
                }
                (scanned ?: Uri.EMPTY) to file.name
            }
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        var file = File(directory, name)
        var index = 2
        while (file.exists()) {
            file = File(directory, name.substringBeforeLast('.') + " ($index)." + name.substringAfterLast('.'))
            index++
        }
        return file
    }

    private fun notifyDone(context: Context, uri: Uri, name: String, kind: MediaKind) {
        if (!HonerNotifications.canNotify(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(NotificationChannel(CHANNEL, text(context, "Загрузки", "Downloads"), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: kind.fallbackMime
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Без адреса content:// (сканер не ответил) уведомление просто сообщает о файле.
        val pending = if (uri == Uri.EMPTY) null else PendingIntent.getActivity(context, name.hashCode(), view,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val folder = when (kind) { MediaKind.IMAGE -> "Pictures"; MediaKind.VIDEO -> "Movies"; MediaKind.AUDIO -> "Music" } + "/$FOLDER"
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(context, "Файл скачан", "Download complete"))
            .setContentText("$name · $folder")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        HonerNotifications.post(context, 40_000 + (name.hashCode() and 0xFFFF), builder)
    }

    /**
     * Поделиться: файл скачивается во временную папку и уходит через FileProvider;
     * страницы (YouTube, VK) и неудачные загрузки — ссылкой.
     */
    fun share(context: Context, url: String, caption: String, kind: MediaKind?) {
        val app = context.applicationContext
        if (kind == null) { shareLink(app, url, caption); return }
        set(url, DownloadState.Running(null))
        scope.launch {
            val file = runCatching { cacheCopy(app, url, caption, kind) }.getOrNull()
            set(url, null)
            withContext(Dispatchers.Main) {
                if (file == null) { shareLink(app, url, caption); return@withContext }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                val type = MediaFileNames.mimeFor(file.extension, kind)
                val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (caption.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, caption)
                runCatching {
                    app.startActivity(Intent.createChooser(send, text(app, "Поделиться", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun shareLink(context: Context, url: String, caption: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, if (caption.isBlank()) url else "$caption\n$url")
        runCatching { context.startActivity(Intent.createChooser(send, text(context, "Поделиться", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private suspend fun cacheCopy(context: Context, url: String, caption: String, kind: MediaKind): File? {
        if (!WebPageText.isPublicWebURL(url)) return null
        val directory = File(context.cacheDir, "shared-media").apply { mkdirs() }
        // Старые временные файлы убираем, чтобы кэш не рос.
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L }?.forEach { it.delete() }
        val request = Request.Builder().url(url).header("User-Agent", HonerHttp.MOBILE_AGENT).build()
        return HonerHttp.web(60).newCall(request).await().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val mime = body.contentType()?.toString()
            if (!MediaFileNames.acceptable(mime, kind)) return@use null
            if (body.contentLength() > 200L * 1024 * 1024) return@use null
            val file = File(directory, MediaFileNames.fileName(url, caption, mime, kind))
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            file
        }
    }
}
