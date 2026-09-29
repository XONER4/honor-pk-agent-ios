package com.honerai.app.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Каналы уведомлений. У ответов — фирменный звук Honer AI (res/raw/honer_notify).
 * Канал создаётся один раз; звук канала потом меняет только пользователь в настройках системы,
 * поэтому идентификатор канала включает версию звука.
 */
object HonerNotifications {
    const val CHANNEL_ANSWERS = "honer.answers.v1"
    const val CHANNEL_WORK = "honer.work.v1"
    const val CHANNEL_UPDATES = "honer.updates.v1"

    /** Дополнение Intent для MainActivity: какой чат открыть по нажатию на уведомление. */
    const val EXTRA_CHAT_ID = "chatId"
    const val ACTION_OPEN_CHAT = "com.honerai.app.action.OPEN_CHAT"

    internal const val ACCENT_COLOR = 0xFF6E99FA.toInt()
    private const val PREVIEW_LIMIT = 1500
    private const val TITLE_LIMIT = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Уведомление «ответ готов»: заголовок — название чата, текст ответа (развёрнутый),
     * картинка из ответа, если есть; нажатие открывает этот чат. Звук — фирменный.
     * Как на iPhone, показывается только когда приложение не на экране и уведомления включены.
     */
    fun notifyAnswer(context: Context, chatId: String, chatTitle: String, text: String, imageUrl: String? = null) {
        val app = context.applicationContext
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        runCatching { com.honerai.app.cloud.CloudManager.recordAnswer(app, chatId, chatTitle, trimmed) } // cloud: вкладка «Уведомления»
        if (!canNotify(app)) return
        if (!AppContainer.get(app).settings.notificationsEnabled.value) return
        if (isAppInForeground()) return
        val image = imageUrl?.takeIf { it.startsWith("http") } ?: firstImageURL(trimmed)
        scope.launch {
            val bitmap = image?.let { url -> withTimeoutOrNull(5_000) { downloadImage(url) } }
            postAnswer(app, chatId, chatTitle, trimmed, bitmap)
        }
    }

    private fun postAnswer(context: Context, chatId: String, chatTitle: String, text: String, image: Bitmap?) {
        val body = preview(text)
        val title = notificationTitle(chatTitle)
        val builder = NotificationCompat.Builder(context, CHANNEL_ANSWERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("honer.answers")
            .setContentIntent(openChatIntent(context, chatId))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // До Android 8 звук и вибрация задаются самим уведомлением, а не каналом.
            builder.setSound(soundUri(context)).setVibrate(longArrayOf(0, 60, 80, 60)).setLights(ACCENT_COLOR, 600, 1800)
        }
        if (image != null) {
            builder.setLargeIcon(image)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?).setSummaryText(body.take(240)))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body).setBigContentTitle(title))
        }
        post(context, notificationId(chatId), builder)
    }

    /** Открыть чат: MainActivity (singleTask) получает Intent с [EXTRA_CHAT_ID] в onNewIntent/onCreate. */
    fun openChatIntent(context: Context, chatId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = if (chatId != null) ACTION_OPEN_CHAT else Intent.ACTION_MAIN
            if (chatId != null) {
                putExtra(EXTRA_CHAT_ID, chatId)
                // Своё data у каждого чата — чтобы PendingIntent разных чатов не сливались.
                data = Uri.parse("honer://chat/$chatId")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, chatId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Открыть приложение (без конкретного чата). */
    fun openAppIntent(context: Context): PendingIntent = openChatIntent(context, null)

    internal fun notificationId(chatId: String): Int = 10_000 + (chatId.hashCode() and 0x3FFF_FFFF) % 1_000_000

    /** Заголовок: название чата (до 60 символов) или «Honer AI». */
    fun notificationTitle(chatTitle: String): String {
        val title = chatTitle.trim()
        return if (title.isEmpty()) "Honer AI" else title.take(TITLE_LIMIT)
    }

    /** Читаемый текст уведомления: без разметки, до 1500 символов (как на iPhone). */
    fun preview(text: String): String {
        var value = SpeechText.sanitizedSpeechText(text)
        if (value.isEmpty()) value = text.trim()
        value = value.replace(Regex("\n{2,}"), "\n")
        if (value.length > PREVIEW_LIMIT) {
            var cut = PREVIEW_LIMIT
            if (Character.isHighSurrogate(value[cut - 1])) cut -= 1
            value = value.substring(0, cut)
        }
        return value
    }

    private val videoHosts = listOf("youtube.com", "youtu.be", "rutube.ru", "vk.com/video", "vimeo.com")
    private val videoExtensions = listOf(".mp4", ".mov", ".webm", ".m3u8", ".mkv")

    /** Первая картинка из ответа (![подпись](ссылка)), но не видео. */
    fun firstImageURL(text: String): String? {
        val regex = Regex("!\\[[^\\]]*\\]\\(\\s*(https?://[^\\s)]+)")
        for (match in regex.findAll(text)) {
            val url = match.groupValues[1]
            val lower = url.lowercase()
            if (videoHosts.any { lower.contains(it) }) continue
            if (videoExtensions.any { lower.substringBefore('?').endsWith(it) }) continue
            return url
        }
        return null
    }

    private fun downloadImage(url: String): Bitmap? = try {
        val request = Request.Builder().url(url).header("User-Agent", "HonerAI-Android").build()
        imageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > 8L * 1024 * 1024) return null
            val bytes = body.bytes()
            if (bytes.size > 8 * 1024 * 1024) return null
            AttachmentImporter.decodeScaled(bytes, 1024)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** Разрешение на уведомления (Android 13+) и общий переключатель в системе. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    internal fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** Приложение на экране (хотя бы одна активность видна). */
    fun isAppInForeground(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    fun soundUri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.honer_notify}")

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val answers = NotificationChannel(CHANNEL_ANSWERS, context.getString(R.string.notification_channel_answers),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(soundUri(context), attributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 60, 80, 60)
            enableLights(true)
            lightColor = 0xFF6E99FA.toInt()
            setShowBadge(true)
        }
        val work = NotificationChannel(CHANNEL_WORK, context.getString(R.string.notification_channel_work),
            NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            setShowBadge(false)
        }
        val updates = NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.notification_channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(soundUri(context), attributes)
        }
        manager.createNotificationChannels(listOf(answers, work, updates))
    }

}
