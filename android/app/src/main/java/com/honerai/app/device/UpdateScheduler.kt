package com.honerai.app.device

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.honerai.app.AppContainer
import com.honerai.app.BuildConfig
import com.honerai.app.R
import java.util.concurrent.TimeUnit

/**
 * Периодическая проверка обновлений (WorkManager): раз в 6 часов при интернете и не разряженной батарее,
 * плюс быстрая проверка при запуске приложения (не чаще раза в 30 минут).
 */
object UpdateScheduler {
    private const val PERIODIC = "honer.update.periodic"
    private const val STARTUP = "honer.update.startup"
    private const val STARTUP_INTERVAL_MS = 30L * 60 * 1000

    fun schedule(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val manager = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        if (enabled) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(PERIODIC)
        }
        // Проверка при запуске: экран сразу узнаёт о новой версии (UpdateManager.available).
        if (System.currentTimeMillis() - UpdateManager.lastCheck(app) > STARTUP_INTERVAL_MS) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniqueWork(STARTUP, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Проверка обновления в фоне. Если приложение не на экране и включено автообновление:
 * когда система позволяет поставить обновление без вопроса — скачивает и ставит сама,
 * иначе показывает уведомление «Доступно обновление». На экране — только флаг available.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val info = UpdateManager.checkNow(app) ?: return Result.success()
        val autoUpdate = runCatching { AppContainer.get(app).settings.autoUpdate.value }.getOrDefault(true)
        // Отладочная сборка (пакет .debug) не обновляется выпусками — только показывает флаг на экране.
        if (BuildConfig.DEBUG || !autoUpdate || HonerNotifications.isAppInForeground()) return Result.success()
        if (UpdateManager.canInstallSilently(app)) {
            if (UpdateManager.installInternal(app)) return Result.success()
        }
        UpdateManager.notifyAvailable(app, info)
        return Result.success()
    }

    /** Для срочной работы на Android 11 и ниже WorkManager запускает службу переднего плана. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, HonerNotifications.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(HonerNotifications.ACCENT_COLOR)
            .setContentTitle(UpdateManager.text(applicationContext, "Проверка обновлений Honer AI…", "Checking for Honer AI updates…"))
            .setOngoing(true)
            .setSilent(true)
            .build()
        // Нужна только до Android 12 (там срочная работа идёт через службу WorkManager без объявленного типа).
        return ForegroundInfo(7_010, notification)
    }
}
