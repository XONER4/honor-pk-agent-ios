package com.honerai.admin.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.honerai.admin.MainActivity
import com.honerai.admin.R

/**
 * Уведомление «новое сообщение от пользователя», когда админка свёрнута, а соединение ещё живо.
 * Звук — фирменный звук Honer AI (res/raw/honer_notify).
 */
object AdminNotifications {
    const val CHANNEL_MESSAGES = "admin.messages.v1"
    const val EXTRA_CHAT_ID = "chatId"
    const val EXTRA_DEVICE_ID = "deviceId"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val sound = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.honer_notify}")
        val channel = NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun notifyMessage(context: Context, chatId: String, deviceId: String?, title: String, text: String) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            deviceId?.let { putExtra(EXTRA_DEVICE_ID, it) }
        }
        val pending = PendingIntent.getActivity(context, chatId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sound = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.honer_notify}")
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE5283C.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSound(sound)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(chatId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Разрешение отозвали между проверкой и показом.
        }
    }

    fun cancel(context: Context, chatId: String) {
        NotificationManagerCompat.from(context).cancel(chatId.hashCode())
    }
}
