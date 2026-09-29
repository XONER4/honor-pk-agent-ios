package com.honerai.app.cloud

import android.content.Context
import android.util.Log
import com.honerai.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Падения приложения → отчёт администратору. Необработанное исключение сохраняется в файл
 * (быстро и синхронно — процесс вот-вот завершится), затем управление уходит прежнему обработчику
 * (система покажет «Приложение остановлено»). При следующем запуске [uploadPending] отправляет файлы
 * на сервер (POST /v1/devices/me/report) и удаляет отправленные.
 */
object CloudCrashReporter {
    private const val TAG = "HonerCloud"
    private const val MAX_FILES = 10
    @Volatile private var installed = false
    @Volatile private var dir: File? = null

    /** Вызывается из HonerApp.onCreate (хук `admin2:`). Без облака ничего не делает. */
    fun install(context: Context) {
        if (!CloudConfig.isConfigured || installed) return
        synchronized(this) {
            if (installed) return
            dir = File(context.applicationContext.filesDir, "cloud/crashes")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { save(error, thread.name) }
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
            installed = true
        }
    }

    /** Сохраняет отчёт о падении в файл (не более [MAX_FILES] штук — старые удаляются). */
    fun save(error: Throwable, threadName: String = Thread.currentThread().name, now: Instant = Instant.now()) {
        val folder = dir ?: return
        folder.mkdirs()
        val report = crashReport(error, threadName, BuildConfig.VERSION_NAME, now)
        File(folder, "crash-${now.toEpochMilli()}.json").writeText(CloudJson.encodeToString(ReportRequest.serializer(), report))
        folder.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Отправка сохранённых падений. [send] бросает исключение, если отправить не удалось (файл останется). */
    fun uploadPending(send: (ReportRequest) -> Unit) {
        val folder = dir ?: return
        val files = folder.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        for (file in files) {
            val report = runCatching { CloudJson.decodeFromString(ReportRequest.serializer(), file.readText()) }.getOrNull()
            if (report == null) { file.delete(); continue }
            try {
                send(report)
                file.delete()
            } catch (e: CloudHttpException) {
                // 4xx (кроме лимита) — сервер такой отчёт не примет никогда: не копим.
                if (e.status in 400..499 && e.status != 429 && e.status != 401) file.delete()
                Log.w(TAG, "crash upload failed: HTTP ${e.status}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "crash upload failed", e)
                return
            }
        }
    }

    /** Отчёт из исключения: сообщение «Класс: текст», стек (с причинами), версия и время. Чистая функция. */
    fun crashReport(error: Throwable, threadName: String, appVersion: String, now: Instant): ReportRequest {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val message = buildString {
            append(error.javaClass.name)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            append(" [").append(threadName).append(']')
        }
        return ReportRequest(kind = "crash", message = message.take(2000), stack = stack.take(16_000), appVersion = appVersion, at = now.toString())
    }
}

