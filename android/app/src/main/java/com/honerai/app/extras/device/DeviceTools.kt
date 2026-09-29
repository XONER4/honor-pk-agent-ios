package com.honerai.app.extras.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.honerai.app.core.DeepSeekStreaming
import com.honerai.app.core.HonerTool
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.core.ToolCallResult
import com.honerai.app.core.ToolEffect
import com.honerai.app.core.ToolExecutionContext
import com.honerai.app.core.ToolProgress
import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageRole
import com.honerai.app.device.AttachmentImporter
import com.honerai.app.extras.lock.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Выполнение инструментов устройства. Будильник и таймер открывают «Часы» (подтверждает
 * пользователь); скриншот и запись — через системное согласие MediaProjection;
 * состояние и данные телефона — только при включённом доступе.
 */
object DeviceTools {
    private fun reply(call: ToolCallRequest, text: String, effect: ToolEffect? = null) = ToolCallResult(call.id, call.name, text, effect)

    const val REFUSAL = "Доступ к данным и состоянию телефона выключен пользователем. Коротко и вежливо скажи: чтобы я мог делать скриншоты и запись экрана, проверять состояние телефона и смотреть его данные, включите «Доступ ИИ к данным и состоянию телефона» в Настройки → Разрешения. Не вызывай этот инструмент снова, пока пользователь не включит доступ."

    private const val NOT_VISIBLE = "Honer AI сейчас не на экране, поэтому система не даёт открыть нужное окно. Попроси пользователя открыть Honer AI и повторить просьбу."

    /** null — это не инструмент устройства. */
    suspend fun execute(call: ToolCallRequest, context: ToolExecutionContext, progress: ToolProgress?): ToolCallResult? {
        val tool = HonerTool.from(call.name) ?: return null
        if (!DeviceToolSchemas.isDeviceTool(tool)) return null
        if (tool in DeviceToolSchemas.gated && !DeviceAccess.enabled.value) return reply(call, REFUSAL)
        val app = DeviceHost.appContext ?: return reply(call, "Инструменты телефона сейчас недоступны: откройте Honer AI и повторите.")
        return try {
            when (tool) {
                HonerTool.SET_ALARM -> alarm(call, app)
                HonerTool.SET_TIMER -> timer(call, app)
                HonerTool.TAKE_SCREENSHOT -> screenshot(call, app, context, progress)
                HonerTool.SCREEN_RECORDING -> record(call, app, context, progress)
                HonerTool.SYSTEM_HEALTH -> health(call, app)
                HonerTool.PHONE_DATA -> phoneData(call, app)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reply(call, "Не получилось выполнить действие на телефоне: ${e.message.orEmpty()}")
        }
    }

    /** Карточка-итог под ответом (файл-карточка чата; по нажатию открывается полный текст). */
    private fun card(name: String, summary: String, text: String): MessageAttachment =
        MessageAttachment(name = name, kind = AttachmentKind.TEXT, extractedText = text, summary = summary)

    // ---- Часы ----

    private suspend fun alarm(call: ToolCallRequest, app: Context): ToolCallResult {
        val request = when (val parsed = DeviceToolArgs.alarm(call.parsedArguments)) {
            is ArgResult.Ok -> parsed.value
            is ArgResult.Invalid -> return reply(call, parsed.message)
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, request.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (request.label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, request.label)
        if (request.days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(request.days))
        if (intent.resolveActivity(app.packageManager) == null) {
            return reply(call, "На телефоне нет приложения «Часы», которое принимает будильники от других приложений. Предложи поставить будильник вручную.")
        }
        if (!DeviceHost.launch(intent)) return reply(call, NOT_VISIBLE)
        val time = DeviceToolArgs.clock(request.hour, request.minute)
        val days = DeviceToolArgs.daysText(request.days)
        val label = request.label.takeIf { it.isNotEmpty() }?.let { "«$it»" }
        val details = listOfNotNull(days, label).joinToString(", ")
        return reply(call,
            "Открыто приложение «Часы» с будильником на $time ($details). Пользователь сам подтверждает его в «Часах» — коротко скажи об этом.",
            ToolEffect.AttachFile(card("Будильник на $time", listOfNotNull(days, label, "подтвердите в «Часах»").joinToString(" · "),
                "Будильник на $time: $details. Поставлен через приложение «Часы».")))
    }

    private suspend fun timer(call: ToolCallRequest, app: Context): ToolCallResult {
        val request = when (val parsed = DeviceToolArgs.timer(call.parsedArguments)) {
            is ArgResult.Ok -> parsed.value
            is ArgResult.Invalid -> return reply(call, parsed.message)
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, request.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (request.label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, request.label)
        if (intent.resolveActivity(app.packageManager) == null) {
            return reply(call, "На телефоне нет приложения «Часы», которое принимает таймеры от других приложений. Предложи поставить таймер вручную.")
        }
        if (!DeviceHost.launch(intent)) return reply(call, NOT_VISIBLE)
        val length = DeviceToolArgs.duration(request.seconds)
        val label = request.label.takeIf { it.isNotEmpty() }?.let { "«$it»" }
        return reply(call,
            "Открыто приложение «Часы» с таймером на $length${label?.let { " ($it)" }.orEmpty()}. Пользователь подтверждает его в «Часах» — коротко скажи об этом.",
            ToolEffect.AttachFile(card("Таймер на $length", listOfNotNull(label, "подтвердите в «Часах»").joinToString(" · "),
                "Таймер на $length${label?.let { ", $it" }.orEmpty()}. Поставлен через приложение «Часы».")))
    }

    // ---- Экран ----

    /** На Android 9 и старше для галереи нужно разрешение на запись в общую память. */
    private suspend fun ensureLegacyStorage(app: Context) {
        if (GallerySaver.needsLegacyPermission(app)) DeviceHost.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    private fun galleryNote(uri: Uri?): String =
        if (uri != null) "Файл сохранён в галерею (папка «Honer AI»)." else "В галерею сохранить не удалось, но файл есть в чате."

    private suspend fun screenshot(call: ToolCallRequest, app: Context, context: ToolExecutionContext, progress: ToolProgress?): ToolCallResult {
        val delay = when (val parsed = DeviceToolArgs.screenshotDelay(call.parsedArguments)) {
            is ArgResult.Ok -> parsed.value
            is ArgResult.Invalid -> return reply(call, parsed.message)
        }
        if (!DeviceHost.awaitForeground()) return reply(call, NOT_VISIBLE)
        ensureLegacyStorage(app)
        progress?.invoke("Подтвердите снимок экрана в окне Android", emptyList())
        val consent = DeviceHost.requestProjection()
            ?: return reply(call, "Пользователь не разрешил снимок экрана в системном окне. Скажи, что без разрешения скриншот сделать нельзя.")
        val (job, deferred) = ScreenCaptureJobs.create()
        AppLock.allowTrip((delay + 180) * 1000L)
        ScreenCaptureService.start(app, job, ScreenCaptureService.MODE_SCREENSHOT, consent.resultCode, consent.data!!, delaySeconds = delay)
        if (delay > 0) {
            progress?.invoke("Снимок через $delay с — откройте нужный экран", emptyList())
            DeviceHost.moveToBack()
        }
        val outcome = withTimeoutOrNull((delay + 40) * 1000L) { deferred.await() }
            ?: run { ScreenCaptureJobs.forget(job); return reply(call, "Скриншот не получился: экран не ответил вовремя.") }
        outcome.error?.let { return reply(call, "Скриншот не получился: $it") }
        val file = outcome.file ?: return reply(call, "Скриншот не получился.")
        val attachment = try {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            AttachmentImporter.importImage(app, bytes, file.name)
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
        progress?.invoke("Рассматриваю снимок", emptyList())
        val description = describe(context.visionClient, listOfNotNull(attachment.localPath),
            "Это скриншот экрана телефона пользователя. Опиши, что на нём: какое приложение или экран открыт, ключевой текст, цифры, уведомления, ошибки.")
        return reply(call,
            "Скриншот сделан и показан пользователю в чате под ответом. ${galleryNote(outcome.galleryUri)}\n" +
                (description?.let { "Что на снимке:\n$it" } ?: "Описать снимок не удалось — если нужно, попроси пользователя рассказать, что на нём."),
            ToolEffect.AttachFile(attachment))
    }

    private suspend fun record(call: ToolCallRequest, app: Context, context: ToolExecutionContext, progress: ToolProgress?): ToolCallResult {
        val request = when (val parsed = DeviceToolArgs.recording(call.parsedArguments)) {
            is ArgResult.Ok -> parsed.value
            is ArgResult.Invalid -> return reply(call, parsed.message)
        }
        if (!DeviceHost.awaitForeground()) return reply(call, NOT_VISIBLE)
        var audio = request.withAudio
        var audioNote = ""
        if (audio && ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val granted = DeviceHost.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))[Manifest.permission.RECORD_AUDIO] == true
            if (!granted) { audio = false; audioNote = " Микрофон не разрешён — видео записано без звука." }
        }
        // Уведомление с кнопкой «Стоп» (Android 13+ спрашивает разрешение на уведомления).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            DeviceHost.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        ensureLegacyStorage(app)
        progress?.invoke("Подтвердите запись экрана в окне Android", emptyList())
        val consent = DeviceHost.requestProjection()
            ?: return reply(call, "Пользователь не разрешил запись экрана в системном окне. Скажи, что без разрешения запись невозможна.")
        val (job, deferred) = ScreenCaptureJobs.create()
        val total = request.durationSeconds
        AppLock.allowTrip((total + 300) * 1000L)
        ScreenCaptureService.start(app, job, ScreenCaptureService.MODE_RECORD, consent.resultCode, consent.data!!,
            durationSeconds = total, audio = audio)
        val outcome = coroutineScope {
            val ticker = launch {
                ScreenCaptureJobs.recordingMs.filterNotNull().map { it / 1000 }.distinctUntilChanged().collect { seconds ->
                    progress?.invoke("Идёт запись: ${clock(seconds.toInt())} из ${clock(total)} · «Стоп» в уведомлении", emptyList())
                }
            }
            val result = withTimeoutOrNull((total + 60) * 1000L) { deferred.await() }
            ticker.cancel()
            result
        } ?: run {
            ScreenCaptureService.stop(app)
            ScreenCaptureJobs.forget(job)
            return reply(call, "Запись экрана не завершилась вовремя.")
        }
        outcome.error?.let { return reply(call, "Запись экрана не получилась: $it") }
        val file = outcome.file ?: return reply(call, "Запись экрана не получилась.")
        progress?.invoke("Готовлю видео для чата", emptyList())
        val attachment = try {
            AttachmentImporter.import(app, Uri.fromFile(file))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
        val seconds = (outcome.durationMs / 1000).toInt().coerceAtLeast(1)
        val how = if (outcome.stoppedEarly) "остановлена пользователем через ${clock(seconds)}" else "длительностью ${clock(seconds)}"
        if (attachment == null) {
            return reply(call, "Запись экрана $how готова, но прикрепить её к чату не удалось. ${galleryNote(outcome.galleryUri)}$audioNote")
        }
        val frames = attachment.videoFramePaths.orEmpty()
        val picked = if (frames.size <= 4) frames else listOf(0, frames.size / 3, 2 * frames.size / 3, frames.size - 1).map { frames[it] }
        progress?.invoke("Смотрю кадры записи", emptyList())
        val description = describe(context.visionClient, picked,
            "Это кадры записи экрана телефона пользователя по порядку. Опиши, что происходило на экране: какие приложения и действия, ключевой текст.")
        val sound = if (outcome.withAudio) "со звуком с микрофона" else "без звука"
        return reply(call,
            "Запись экрана $how ($sound) показана пользователю в чате под ответом. ${galleryNote(outcome.galleryUri)}$audioNote\n" +
                (description?.let { "Что на записи (по выборочным кадрам):\n$it" } ?: "Описать запись не удалось."),
            ToolEffect.AttachFile(attachment))
    }

    /** «0:07», «10:00». */
    private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** Нейросеть рассматривает снимки и описывает их для ответа. */
    private suspend fun describe(vision: DeepSeekStreaming?, paths: List<String>, question: String): String? {
        if (vision == null || paths.isEmpty()) return null
        val attachments = paths.filter { File(it).isFile }.mapIndexed { index, path ->
            MessageAttachment(name = "frame${index + 1}.jpg", kind = AttachmentKind.IMAGE, localPath = path)
        }
        if (attachments.isEmpty()) return null
        return try {
            val message = ChatMessage(role = MessageRole.USER, content = "$question Отвечай по-русски, по существу, опираясь только на видимое.", attachments = attachments)
            vision.complete(listOf(message), false, "", "").trim().takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ---- Состояние и данные ----

    private suspend fun health(call: ToolCallRequest, app: Context): ToolCallResult {
        val snapshot = withContext(Dispatchers.IO) { SystemHealthProbe.snapshot(app) }
        val report = SystemHealthFormat.report(snapshot)
        return reply(call,
            report + "\n\nКарточка «Состояние телефона» уже показана пользователю. Дай короткий понятный разбор: что в норме, что стоит поправить, и конкретные советы.",
            ToolEffect.AttachFile(card("Состояние телефона", SystemHealthFormat.summary(snapshot), report)))
    }

    private suspend fun phoneData(call: ToolCallRequest, app: Context): ToolCallResult {
        val kind = when (val parsed = DeviceToolArgs.phoneDataKind(call.parsedArguments)) {
            is ArgResult.Ok -> parsed.value
            is ArgResult.Invalid -> return reply(call, parsed.message)
        }
        return when (kind) {
            PhoneDataKind.CONTACTS_COUNT -> {
                val count = withContext(Dispatchers.IO) { PhoneDataProbe.contactsCount(app) }
                    ?: return reply(call, "Доступ к контактам не разрешён. Предложи открыть Настройки → Разрешения в приложении и разрешить «Контакты».")
                reply(call, "В телефонной книге $count контактов.")
            }
            PhoneDataKind.INSTALLED_APPS -> {
                val apps = withContext(Dispatchers.IO) { PhoneDataProbe.launcherApps(app) }
                val text = PhoneDataFormat.apps(apps)
                reply(call, text, ToolEffect.AttachFile(card("Приложения на телефоне", "${apps.size} приложений", text)))
            }
            PhoneDataKind.DEVICE_INFO -> reply(call, withContext(Dispatchers.IO) { PhoneDataProbe.deviceInfo(app) })
            PhoneDataKind.USAGE_TODAY -> {
                val usage = withContext(Dispatchers.IO) { PhoneDataProbe.usageToday(app) }
                    ?: return reply(call, "Нет доступа к статистике использования. Предложи пользователю открыть Настройки → Разрешения → «Статистика использования» и разрешить доступ Honer AI.")
                val text = PhoneDataFormat.usage(usage)
                val total = usage.filter { it.second >= 60_000 }.sumOf { it.second }
                reply(call, text, ToolEffect.AttachFile(card("Экранное время сегодня", PhoneDataFormat.minutes(total), text)))
            }
        }
    }
}
