package com.honerai.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.honerai.app.R

/**
 * Каналы уведомлений. У ответов — фирменный звук Honer AI (res/raw/honer_notify).
 * Канал создаётся один раз; звук канала потом меняет только пользователь в настройках системы,
 * поэтому идентификатор канала включает версию звука.
 */
object HonerNotifications {
    const val CHANNEL_ANSWERS = "honer.answers.v1"
    const val CHANNEL_WORK = "honer.work.v1"
    const val CHANNEL_UPDATES = "honer.updates.v1"

    /**
     * Уведомление «ответ готов»: заголовок — название чата, текст ответа (развёрнутый),
     * картинка из ответа, если есть; нажатие открывает этот чат. Звук — фирменный.
     * (Реализацию дописывает модуль «Устройство».)
     */
    fun notifyAnswer(context: Context, chatId: String, chatTitle: String, text: String, imageUrl: String? = null) {}

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
