package com.honerai.app.extras.device

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.honerai.app.AppContainer
import com.honerai.app.MainActivity
import com.honerai.app.R
import com.honerai.app.device.HonerNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Служба снимка и записи экрана (MediaProjection). По требованиям Android 14:
 * сначала startForeground с типом mediaProjection, затем getMediaProjection,
 * callback регистрируется до createVirtualDisplay; одно согласие — одна запись.
 */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val thread = HandlerThread("honer-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var jobId: String? = null
    private var recording = false
    private var finished = false
    private var withAudio = false
    private var startedAt = 0L
    private var output: File? = null

    private val callback = object : MediaProjection.Callback() {
        // Пользователь остановил показ экрана (значок в строке состояния, блокировка экрана).
        override fun onStop() {
            scope.launch { if (recording) finishRecording(stoppedEarly = true) else fail("Показ экрана остановлен до снимка.") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { if (recording) finishRecording(stoppedEarly = true) else if (jobId == null) stopSelf() }
            return START_NOT_STICKY
        }
        val job = intent?.getStringExtra(EXTRA_JOB)
        if (jobId != null) {
            ScreenCaptureJobs.complete(job, CaptureOutcome(error = "Уже идёт запись экрана. Дождитесь её окончания."))
            return START_NOT_STICKY
        }
        jobId = job
        val mode = intent?.getStringExtra(EXTRA_MODE)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val delaySeconds = intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0
        val duration = intent?.getIntExtra(EXTRA_DURATION, 10) ?: 10
        withAudio = intent?.getBooleanExtra(EXTRA_AUDIO, false) == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (data == null || mode == null) {
            fail("Нет разрешения на запись экрана.")
            return START_NOT_STICKY
        }
        val recordingMode = mode == MODE_RECORD
        try {
            var type = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (recordingMode && withAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            val text = when {
                recordingMode -> t("Идёт запись экрана", "Recording the screen")
                delaySeconds > 0 -> t("Скриншот через $delaySeconds с — откройте нужный экран", "Screenshot in $delaySeconds s — open the screen you need")
                else -> t("Делаю скриншот", "Taking a screenshot")
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text, recordingMode), type)
        } catch (e: Exception) {
            fail("Android не разрешил запустить запись экрана: ${e.message.orEmpty()}")
            return START_NOT_STICKY
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val granted = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (granted == null) {
            fail("Разрешение на запись экрана недействительно. Попробуйте ещё раз.")
            return START_NOT_STICKY
        }
        projection = granted
        granted.registerCallback(callback, handler)
        if (recordingMode) {
            scope.launch { startRecording(duration.coerceIn(1, DeviceToolArgs.MAX_RECORDING_SECONDS)) }
        } else {
            scope.launch {
                if (delaySeconds > 0) delay(delaySeconds * 1000L)
                captureFrame()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun metrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        runCatching { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics) }
        if (metrics.widthPixels <= 0) metrics.setTo(resources.displayMetrics)
        return metrics
    }

    // ---- Снимок ----

    private suspend fun captureFrame() {
        val projection = projection ?: return fail("Нет доступа к экрану.")
        val metrics = metrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val latest = arrayOfNulls<Bitmap>(1)
        val first = CompletableDeferred<Unit>()
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (padded.width != width) Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() } else padded
                synchronized(latest) {
                    latest[0]?.recycle()
                    latest[0] = cropped
                }
                first.complete(Unit)
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, handler)
        virtualDisplay = runCatching {
            projection.createVirtualDisplay("HonerScreenshot", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler)
        }.getOrNull() ?: return fail("Не удалось получить изображение экрана.")
        val got = withTimeoutOrNull(5_000) { first.await() } != null
        // Первый кадр бывает ещё не дорисован — берём самый свежий через мгновение.
        if (got) delay(300)
        val bitmap = synchronized(latest) { latest[0].also { latest[0] = null } }
        releaseDisplay()
        if (bitmap == null) return fail("Экран не прислал изображение. Возможно, приложение на экране запрещает снимки.")
        val name = "Скриншот ${stamp()}.png"
        val file = File(captureDir(), name)
        val saved = withContext(Dispatchers.IO) {
            runCatching { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
        }
        bitmap.recycle()
        if (!saved) return fail("Не удалось сохранить скриншот.")
        val uri = withContext(Dispatchers.IO) { GallerySaver.saveImage(this@ScreenCaptureService, file, name) }
        ScreenCaptureJobs.complete(jobId, CaptureOutcome(file = file, galleryUri = uri))
        jobId = null
        stopEverything()
    }

    // ---- Запись ----

    private suspend fun startRecording(durationSeconds: Int) {
        val projection = projection ?: return fail("Нет доступа к экрану.")
        val metrics = metrics()
        val (width, height) = RecordingSize.choose(metrics.widthPixels, metrics.heightPixels) { w, h -> RecordingSize.encoderSupports(w, h) }
        val file = File(captureDir(), "Запись экрана ${stamp()}.mp4")
        output = file
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = mediaRecorder
        try {
            if (withAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(file.path)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(FPS)
            mediaRecorder.setVideoEncodingBitRate(RecordingSize.bitrate(width, height, FPS, durationSeconds))
            if (withAudio) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder.setAudioEncodingBitRate(128_000)
                mediaRecorder.setAudioSamplingRate(44_100)
            }
            mediaRecorder.prepare()
            virtualDisplay = projection.createVirtualDisplay("HonerRecording", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.surface, null, handler)
            mediaRecorder.start()
        } catch (e: Exception) {
            return fail("Не удалось начать запись экрана: ${e.message.orEmpty()}")
        }
        recording = true
        startedAt = SystemClock.elapsedRealtime()
        ScreenCaptureJobs.setRecording(0L)
        val end = startedAt + durationSeconds * 1000L
        while (recording && SystemClock.elapsedRealtime() < end) {
            ScreenCaptureJobs.setRecording(SystemClock.elapsedRealtime() - startedAt)
            delay(250)
        }
        if (recording) finishRecording(stoppedEarly = false)
    }

    private suspend fun finishRecording(stoppedEarly: Boolean) {
        if (finished || !recording) return
        finished = true
        recording = false
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        ScreenCaptureJobs.setRecording(null)
        // Слишком короткая запись без кадров: stop() бросает исключение, файл пустой.
        val stopped = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        releaseDisplay()
        val file = output
        if (!stopped || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            return fail("Запись оказалась пустой — экран не успел записаться.")
        }
        val uri = withContext(Dispatchers.IO) { GallerySaver.saveVideo(this@ScreenCaptureService, file, file.name) }
        ScreenCaptureJobs.complete(jobId, CaptureOutcome(file = file, galleryUri = uri, durationMs = elapsed, stoppedEarly = stoppedEarly, withAudio = withAudio))
        jobId = null
        stopEverything()
    }

    // ---- Общее ----

    private fun fail(message: String) {
        ScreenCaptureJobs.complete(jobId, CaptureOutcome(error = message))
        jobId = null
        recording = false
        finished = true
        ScreenCaptureJobs.setRecording(null)
        runCatching { recorder?.release() }
        recorder = null
        output?.delete()
        stopEverything()
    }

    private fun releaseDisplay() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
    }

    private fun stopEverything() {
        releaseDisplay()
        projection?.let { p ->
            runCatching { p.unregisterCallback(callback) }
            runCatching { p.stop() }
        }
        projection = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (jobId != null) {
            ScreenCaptureJobs.complete(jobId, CaptureOutcome(error = "Запись экрана прервана системой."))
            jobId = null
        }
        ScreenCaptureJobs.setRecording(null)
        runCatching { recorder?.release() }
        releaseDisplay()
        runCatching { projection?.stop() }
        scope.cancel()
        thread.quitSafely()
        super.onDestroy()
    }

    /** Строка на языке приложения. */
    private fun t(ru: String, en: String): String = runCatching { AppContainer.get(this).settings.text(ru, en) }.getOrDefault(ru)

    private fun captureDir(): File = File(cacheDir, "screen").apply { mkdirs() }

    private fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))

    private fun notification(text: String, recordingMode: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, HonerNotifications.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Honer AI")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
        if (recordingMode) {
            val stop = PendingIntent.getService(this, 1,
                Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setUsesChronometer(true).setWhen(System.currentTimeMillis())
                .addAction(0, t("Стоп", "Stop"), stop)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.honerai.app.extras.CAPTURE_START"
        const val ACTION_STOP = "com.honerai.app.extras.CAPTURE_STOP"
        const val EXTRA_JOB = "job"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_AUDIO = "audio"
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
        private const val NOTIFICATION_ID = 4711
        private const val FPS = 30

        fun start(context: Context, job: String, mode: String, resultCode: Int, data: Intent, delaySeconds: Int = 0, durationSeconds: Int = 0, audio: Boolean = false) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_JOB, job).putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_DELAY, delaySeconds).putExtra(EXTRA_DURATION, durationSeconds).putExtra(EXTRA_AUDIO, audio)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Остановить идущую запись (кнопка в чате или уведомлении). */
        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
