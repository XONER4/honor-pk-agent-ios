package com.honerai.app.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

/** FCM доступен, только если сборка с google-services.json (FirebaseApp создан автоматически). */
object PushSupport {
    fun available(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    fun fetchToken(onToken: (String) -> Unit) {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> if (!token.isNullOrBlank()) onToken(token) }
        }
    }
}

/**
 * Без FCM: раз в 15 минут (минимум WorkManager) проверяем новые сообщения администратора
 * и рассылки и показываем уведомления с фирменным звуком.
 */
class CloudPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CloudManager.start(applicationContext)
        if (!CloudManager.enabled) return Result.success()
        runCatching { CloudManager.backgroundSync() }
        return Result.success()
    }

    companion object {
        private const val NAME = "honer.cloud.poll"

        fun schedule(context: Context) {
            val manager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = PeriodicWorkRequestBuilder<CloudPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}

/**
 * Push FCM (data-сообщения сервера `{ type, chatId, title, body }`): новый токен — на сервер,
 * сообщение — синхронизация и уведомление со звуком Honer AI.
 */
class HonerMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        CloudManager.start(applicationContext)
        CloudManager.updatePushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        CloudManager.start(applicationContext)
        val data = message.data
        val type = data["type"] ?: "message"
        val title = data["title"] ?: message.notification?.title.orEmpty()
        val body = data["body"] ?: message.notification?.body.orEmpty()
        CloudManager.onPush(type, title, body)
    }
}
