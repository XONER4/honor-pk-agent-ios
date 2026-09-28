package com.honerai.app.device

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.honerai.app.AppContainer
import com.honerai.app.BuildConfig
import com.honerai.app.MainActivity
import com.honerai.app.R
import kotlinx.coroutines.delay

// Службы и приёмники из манифеста: фоновая генерация и автообновление.

/**
 * Дописывает ответ, пока приложение свёрнуто (служба переднего плана, тип dataSync).
 * Тихое уведомление «Honer AI дописывает ответ…»; сама останавливается через 30 минут.
 */
class GenerationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoStop = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, title), type)
        } catch (e: Exception) {
            // Android 12+: запуск из фона запрещён; Android 14+: исчерпан лимит типа — работаем без службы.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        acquireWakeLock()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_DURATION_MS)
        return START_NOT_STICKY
    }

    /** Android 14+: система просит завершить службу (лимит времени типа). */
    override fun onTimeout(startId: Int) = finish()

    /** Android 15+: для dataSync — не больше 6 часов в сутки. */
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    private fun finish() {
        handler.removeCallbacks(autoStop)
        releaseWakeLock()
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Экран может погаснуть — процессор не засыпает, пока ответ приходит по сети. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HonerAI:generation").apply {
                setReferenceCounted(false)
                acquire(MAX_DURATION_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "com.honerai.app.action.STOP_GENERATION"
        private const val NOTIFICATION_ID = 7_100
        private const val MAX_DURATION_MS = 30L * 60 * 1000
        internal const val KEEP_ALIVE_WORK = "honer.generation.keepalive"

        @Volatile
        var running = false
            private set

        @Volatile
        internal var keepAliveRequested = false

        @Volatile
        private var startRequested = false

        /**
         * Ответ идёт, приложение свёрнуто — держим процесс живым с тихим уведомлением.
         * Лучше вызывать в начале генерации, пока приложение на экране: с Android 12 службу
         * переднего плана нельзя запустить из фона. Если система отказала — срочная задача
         * WorkManager ненадолго (до ~10 минут) удерживает процесс.
         */
        fun start(context: Context, title: String) {
            val app = context.applicationContext
            val intent = Intent(app, GenerationService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                ContextCompat.startForegroundService(app, intent)
                startRequested = true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (Android 12+) или IllegalStateException (фон).
                startKeepAlive(app)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            keepAliveRequested = false
            runCatching { WorkManager.getInstance(app).cancelUniqueWork(KEEP_ALIVE_WORK) }
            if (running || startRequested) {
                startRequested = false
                // Через onStartCommand: служба сначала успевает вызвать startForeground, потом останавливается.
                runCatching { app.startService(Intent(app, GenerationService::class.java).setAction(ACTION_STOP)) }
                    .onFailure { runCatching { app.stopService(Intent(app, GenerationService::class.java)) } }
            } else {
                runCatching { app.stopService(Intent(app, GenerationService::class.java)) }
            }
        }

        private fun startKeepAlive(app: Context) {
            keepAliveRequested = true
            val request = OneTimeWorkRequestBuilder<GenerationKeepAliveWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            runCatching { WorkManager.getInstance(app).enqueueUniqueWork(KEEP_ALIVE_WORK, ExistingWorkPolicy.REPLACE, request) }
        }

        internal fun notification(context: Context, title: String): Notification {
            val heading = UpdateManager.text(context, "Honer AI дописывает ответ…", "Honer AI is finishing the answer…")
            return NotificationCompat.Builder(context, HonerNotifications.CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(HonerNotifications.ACCENT_COLOR)
                .setContentTitle(heading)
                .setContentText(title.trim().take(60).ifEmpty { "Honer AI" })
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(HonerNotifications.openAppIntent(context))
                .build()
        }
    }
}

/** Запасной путь, если службу запустить не дали: держит процесс, пока ответ не допишется (не дольше 9 минут). */
class GenerationKeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + 9 * 60 * 1000L
        while (GenerationService.keepAliveRequested && System.currentTimeMillis() < deadline && !isStopped) delay(1_000)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = GenerationService.notification(applicationContext, "")
        // Нужна только до Android 12 (там срочная работа идёт через службу WorkManager без объявленного типа).
        return ForegroundInfo(7_101, notification)
    }
}

/** Итог установки обновления от PackageInstaller. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.ACTION_INSTALL_RESULT) return
        UpdateManager.onInstallResult(context, intent)
    }
}

/**
 * Приложение обновилось — показать уведомление и вернуть пользователя в чат.
 * До Android 10 приложение открывается само; с Android 10 система запрещает открывать окна
 * из фона, поэтому путь обратно — уведомление «Honer AI обновлён до …» (нажатие открывает приложение).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        UpdateManager.onPackageReplaced(app)
        val version = BuildConfig.VERSION_NAME
        val builder = NotificationCompat.Builder(app, HonerNotifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(HonerNotifications.ACCENT_COLOR)
            .setContentTitle(UpdateManager.text(app, "Honer AI обновлён до $version", "Honer AI updated to $version"))
            .setContentText(UpdateManager.text(app, "Нажмите, чтобы продолжить.", "Tap to continue."))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(HonerNotifications.openAppIntent(app))
        HonerNotifications.post(app, UpdateManager.NOTIFICATION_INSTALLED, builder)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val autoUpdate = runCatching { AppContainer.get(app).settings.autoUpdate.value }.getOrDefault(true)
        UpdateScheduler.schedule(app, autoUpdate)
    }
}

/** После перезагрузки телефона — снова запланировать проверку обновлений. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val autoUpdate = runCatching { AppContainer.get(app).settings.autoUpdate.value }.getOrDefault(true)
        UpdateScheduler.schedule(app, autoUpdate)
    }
}
