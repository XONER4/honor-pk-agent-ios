package com.honerai.app.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.R
import com.honerai.app.device.HonerNotifications

/**
 * Системные уведомления облака: сообщения администратора и рассылки. Звук — фирменный
 * (res/raw/honer_notify), свои каналы, чтобы их можно было настроить отдельно от ответов.
 */
object CloudNotifier {
    const val CHANNEL_ADMIN = "honer.cloud.admin.v1"
    const val CHANNEL_BROADCAST = "honer.cloud.broadcast.v1"

    /** Дополнение Intent: что открыть по нажатию — [OPEN_ADMIN] или [OPEN_NOTIFICATIONS]. */
    const val EXTRA_OPEN = "honer.cloud.open"
    const val OPEN_ADMIN = "admin"
    const val OPEN_NOTIFICATIONS = "notifications"

    private const val ADMIN_ID = 7_300_001
    private const val FIRST_CONTACT_ID = 7_300_002
    private const val ADMIN_RED = 0xFFE53935.toInt()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val english = isEnglish(context)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val admin = NotificationChannel(CHANNEL_ADMIN, if (english) "Administrator messages" else "Сообщения администратора",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(HonerNotifications.soundUri(context), attributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 60, 80, 60)
            enableLights(true)
            lightColor = ADMIN_RED
            setShowBadge(true)
        }
        val broadcast = NotificationChannel(CHANNEL_BROADCAST, if (english) "Honer AI announcements" else "Уведомления Honer AI",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(HonerNotifications.soundUri(context), attributes)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannels(listOf(admin, broadcast))
    }

    /** Новое сообщение администратора (или Honer AI в группе). Несколько сообщений собираются в одно уведомление. */
    fun adminMessage(context: Context, sender: String, text: String, unread: Int) {
        val english = isEnglish(context)
        val name = if (sender == CloudMessage.SENDER_AI) "Honer AI" else if (english) "Honer AI Administrator" else "Администратор Honer AI"
        val person = Person.Builder().setName(name).setImportant(true).build()
        val me = Person.Builder().setName(if (english) "You" else "Вы").build()
        val style = NotificationCompat.MessagingStyle(me).addMessage(text, System.currentTimeMillis(), person)
        val builder = NotificationCompat.Builder(context, CHANNEL_ADMIN)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ADMIN_RED)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(style)
            .setNumber(unread)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(context, OPEN_ADMIN))
        legacySound(context, builder)
        HonerNotifications.post(context, ADMIN_ID, builder)
    }

    /** Разовое системное уведомление при первом сообщении администратора. */
    fun firstContact(context: Context) {
        val english = isEnglish(context)
        val text = if (english) "The Honer AI administrator wrote to you (verified account)"
        else "Вам написал администратор Honer AI (подтверждённый аккаунт)"
        val builder = NotificationCompat.Builder(context, CHANNEL_ADMIN)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ADMIN_RED)
            .setContentTitle("Honer AI")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(context, OPEN_ADMIN))
        legacySound(context, builder)
        HonerNotifications.post(context, FIRST_CONTACT_ID, builder)
    }

    /** Рассылка администратора (вкладка «Уведомления»). */
    fun broadcast(context: Context, id: String, title: String, body: String) {
        val heading = title.ifBlank { "Honer AI" }
        val builder = NotificationCompat.Builder(context, CHANNEL_BROADCAST)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(HonerNotifications.ACCENT_COLOR)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body).setBigContentTitle(heading))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(context, OPEN_NOTIFICATIONS))
        legacySound(context, builder)
        HonerNotifications.post(context, 7_400_000 + (id.hashCode() and 0xFFFF), builder)
    }

    /** Открыли чат — его уведомления больше не нужны. */
    fun cancelAdmin(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(ADMIN_ID)
            NotificationManagerCompat.from(context).cancel(FIRST_CONTACT_ID)
        }
    }

    fun openIntent(context: Context, target: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("honer://cloud/$target")
            putExtra(EXTRA_OPEN, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, target.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun legacySound(context: Context, builder: NotificationCompat.Builder) {
        // До Android 8 звук задаёт само уведомление.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(HonerNotifications.soundUri(context)).setVibrate(longArrayOf(0, 60, 80, 60))
        }
    }

    private fun isEnglish(context: Context): Boolean =
        runCatching { AppContainer.get(context).settings.isEnglish }.getOrDefault(false)
}
