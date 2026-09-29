package com.honerai.app.extras.device

import com.honerai.app.core.HonerTool
import com.honerai.app.core.ToolArgument
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.core.ToolSchema
import com.honerai.app.data.GenerationStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Инструменты устройства для нейросети: описания для API, разбор аргументов, шаги ленты
// и блок системной инструкции. Без Android — всё проверяется модульными тестами.

object DeviceToolSchemas {
    val tools: Set<HonerTool> = setOf(
        HonerTool.SET_ALARM, HonerTool.SET_TIMER, HonerTool.TAKE_SCREENSHOT,
        HonerTool.SCREEN_RECORDING, HonerTool.SYSTEM_HEALTH, HonerTool.PHONE_DATA,
    )

    /** Требуют включённого «Доступа ИИ к данным и состоянию телефона». */
    val gated: Set<HonerTool> = setOf(HonerTool.TAKE_SCREENSHOT, HonerTool.SCREEN_RECORDING, HonerTool.SYSTEM_HEALTH, HonerTool.PHONE_DATA)

    fun isDeviceTool(tool: HonerTool): Boolean = tool in tools

    fun schema(tool: HonerTool): JsonObject? {
        val s = ToolSchema
        return when (tool) {
            HonerTool.SET_ALARM -> s.function(tool.rawValue,
                "Ставит будильник в приложении «Часы» телефона. Пользователь подтверждает его в «Часах». Для «разбуди в 7:30», «будильник на завтра», «по будням в 6:45».",
                mapOf(
                    "hour" to s.integer("Час, 0–23"),
                    "minute" to s.integer("Минуты, 0–59"),
                    "label" to s.string("Подпись будильника, необязательно"),
                    "days" to s.array(s.string(), "Повтор по дням: mon, tue, wed, thu, fri, sat, sun, или weekdays / weekends / daily. Пусто — один раз"),
                ), listOf("hour", "minute"))
            HonerTool.SET_TIMER -> s.function(tool.rawValue,
                "Запускает таймер в приложении «Часы» (пользователь подтверждает). Для «поставь таймер на 10 минут».",
                mapOf(
                    "seconds" to s.integer("Длительность в секундах, 1–86400"),
                    "label" to s.string("Подпись таймера, необязательно"),
                ), listOf("seconds"))
            HonerTool.TAKE_SCREENSHOT -> s.function(tool.rawValue,
                "Делает скриншот экрана телефона (система спросит разрешение) и показывает его в чате; ты получишь описание снимка. С задержкой delaySeconds приложение свернётся, чтобы пользователь открыл нужный экран.",
                mapOf("delaySeconds" to s.integer("Задержка перед снимком, 0–60 секунд; по умолчанию 0")), emptyList())
            HonerTool.SCREEN_RECORDING -> s.function(tool.rawValue,
                "Записывает видео экрана телефона (система спросит разрешение; в уведомлении есть кнопка «Стоп»), сохраняет в галерею и прикрепляет к чату; ты получишь описание кадров.",
                mapOf(
                    "durationSeconds" to s.integer("Длительность записи, 1–600 секунд"),
                    "withAudio" to s.boolean("Записывать звук с микрофона; по умолчанию false"),
                ), listOf("durationSeconds"))
            HonerTool.SYSTEM_HEALTH -> s.function(tool.rawValue,
                "Возвращает состояние телефона: заряд, здоровье и температура батареи, зарядка, ОЗУ, память, процессор, время работы, версия Android и патч безопасности, сеть, частота экрана, перегрев, энергосбережение.",
                emptyMap(), emptyList())
            HonerTool.PHONE_DATA -> s.function(tool.rawValue,
                "Данные телефона по виду kind: contacts_count (сколько контактов), installed_apps (установленные приложения), device_info (модель, имя устройства, экран, язык), usage_today (время в приложениях сегодня — нужен доступ к статистике использования).",
                mapOf("kind" to s.string("contacts_count, installed_apps, device_info или usage_today")), listOf("kind"))
            else -> null
        }
    }

    /** Шаг ленты «что делает Honer AI». Виды — из существующих значков. */
    fun step(call: ToolCallRequest): GenerationStep? {
        val arguments = call.parsedArguments
        return when (HonerTool.from(call.name)) {
            HonerTool.SET_ALARM -> {
                val detail = (DeviceToolArgs.alarm(arguments) as? ArgResult.Ok)?.value?.let { DeviceToolArgs.clock(it.hour, it.minute) }.orEmpty()
                GenerationStep(kind = "settings", title = "Ставлю будильник", detail = detail)
            }
            HonerTool.SET_TIMER -> {
                val detail = (DeviceToolArgs.timer(arguments) as? ArgResult.Ok)?.value?.let { DeviceToolArgs.duration(it.seconds) }.orEmpty()
                GenerationStep(kind = "settings", title = "Ставлю таймер", detail = detail)
            }
            HonerTool.TAKE_SCREENSHOT -> GenerationStep(kind = "screenshot", title = "Делаю скриншот экрана")
            HonerTool.SCREEN_RECORDING -> {
                val detail = (DeviceToolArgs.recording(arguments) as? ArgResult.Ok)?.value?.let { DeviceToolArgs.duration(it.durationSeconds) }.orEmpty()
                GenerationStep(kind = "screenshot", title = "Записываю экран", detail = detail)
            }
            HonerTool.SYSTEM_HEALTH -> GenerationStep(kind = "settings", title = "Проверяю состояние телефона")
            HonerTool.PHONE_DATA -> GenerationStep(kind = "settings", title = "Смотрю данные телефона",
                detail = ToolArgument.string(arguments["kind"]).orEmpty().take(40))
            else -> null
        }
    }

    fun status(names: Set<String>): String? = listOf(
        HonerTool.SET_ALARM to "Ставлю будильник…", HonerTool.SET_TIMER to "Ставлю таймер…",
        HonerTool.TAKE_SCREENSHOT to "Делаю скриншот…", HonerTool.SCREEN_RECORDING to "Записываю экран…",
        HonerTool.SYSTEM_HEALTH to "Проверяю телефон…", HonerTool.PHONE_DATA to "Смотрю данные телефона…",
    ).firstOrNull { it.first.rawValue in names }?.second

    /** Блок системной инструкции: инструменты есть всегда, но часть работает только с переключателем. */
    fun promptBlock(accessEnabled: Boolean): String {
        val access = if (accessEnabled) {
            "Доступ к данным телефона пользователь включил."
        } else {
            "Сейчас доступ ВЫКЛЮЧЕН: take_screenshot, start_screen_recording, system_health и phone_data вернут отказ. Если пользователь просит такое — коротко скажи, что нужно включить «Доступ ИИ к данным и состоянию телефона» в Настройки → Разрешения, и не вызывай их повторно."
        }
        return "\n\n## Телефон пользователя\n" +
            "• Будильник и таймер: set_alarm (hour, minute, label, days) и set_timer (seconds, label) открывают приложение «Часы» — пользователь подтверждает сам. Время бери из просьбы; «через 20 минут» — это таймер или будильник на текущее время + 20 минут.\n" +
            "• Экран: take_screenshot (delaySeconds — если нужно показать другой экран) и start_screen_recording (durationSeconds 1–600, withAudio). Каждый раз система спрашивает разрешение; результат появится в чате, а тебе придёт описание.\n" +
            "• Состояние телефона: system_health — батарея, память, процессор, сеть, перегрев. Разбери итог по-человечески: что в норме, что стоит поправить.\n" +
            "• phone_data: contacts_count, installed_apps, device_info, usage_today. Звонки и SMS недоступны — не обещай их.\n" +
            "• Скриншоты, запись экрана, system_health и phone_data работают только при включённом переключателе «Доступ ИИ к данным и состоянию телефона» (Настройки → Разрешения). " + access
    }
}

// ---- Разбор аргументов ----

/** Итог разбора: значение или понятное модели объяснение, что не так. */
sealed class ArgResult<out T> {
    data class Ok<T>(val value: T) : ArgResult<T>()
    data class Invalid(val message: String) : ArgResult<Nothing>()
}

/** [days] — дни недели в нумерации java.util.Calendar (1 — воскресенье … 7 — суббота), по порядку пн→вс. */
data class AlarmRequest(val hour: Int, val minute: Int, val label: String, val days: List<Int>)
data class TimerRequest(val seconds: Int, val label: String)
data class RecordingRequest(val durationSeconds: Int, val withAudio: Boolean)

enum class PhoneDataKind(val raw: String) {
    CONTACTS_COUNT("contacts_count"), INSTALLED_APPS("installed_apps"), DEVICE_INFO("device_info"), USAGE_TODAY("usage_today")
}

object DeviceToolArgs {
    const val MAX_TIMER_SECONDS = 86_400
    const val MAX_RECORDING_SECONDS = 600
    const val MAX_SCREENSHOT_DELAY = 60
    const val MAX_LABEL = 80

    // Calendar: SUNDAY=1, MONDAY=2 … SATURDAY=7.
    private const val SUN = 1; private const val MON = 2; private const val TUE = 3; private const val WED = 4
    private const val THU = 5; private const val FRI = 6; private const val SAT = 7
    private val weekOrder = listOf(MON, TUE, WED, THU, FRI, SAT, SUN)

    fun alarm(arguments: JsonObject): ArgResult<AlarmRequest> {
        var hour = ToolArgument.int(arguments["hour"])
        var minute = ToolArgument.int(arguments["minute"] ?: arguments["minutes"])
        // Модель иногда присылает время одной строкой: «07:30».
        val time = ToolArgument.string(arguments["time"]) ?: ToolArgument.string(arguments["hour"])?.takeIf { it.contains(':') }
        if (time != null && time.contains(':')) {
            val parts = time.trim().split(':')
            hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
            minute = parts.getOrNull(1)?.trim()?.take(2)?.toIntOrNull()
        }
        if (hour == null) return ArgResult.Invalid("Не передан час будильника (hour, 0–23).")
        if (hour !in 0..23) return ArgResult.Invalid("Час должен быть от 0 до 23, получено $hour.")
        val m = minute ?: 0
        if (m !in 0..59) return ArgResult.Invalid("Минуты должны быть от 0 до 59, получено $m.")
        val days = when (val parsed = days(arguments["days"])) {
            is ArgResult.Ok -> parsed.value
            is ArgResult.Invalid -> return parsed
        }
        return ArgResult.Ok(AlarmRequest(hour, m, label(arguments), days))
    }

    fun timer(arguments: JsonObject): ArgResult<TimerRequest> {
        val seconds = ToolArgument.int(arguments["seconds"])
            ?: ToolArgument.int(arguments["minutes"])?.let { it * 60 }
            ?: return ArgResult.Invalid("Не передана длительность таймера (seconds).")
        if (seconds !in 1..MAX_TIMER_SECONDS) return ArgResult.Invalid("Таймер — от 1 секунды до 24 часов, получено $seconds с.")
        return ArgResult.Ok(TimerRequest(seconds, label(arguments)))
    }

    fun screenshotDelay(arguments: JsonObject): ArgResult<Int> {
        val raw = arguments["delaySeconds"] ?: arguments["delay"]
        if (raw == null || raw is JsonNull) return ArgResult.Ok(0)
        val delay = ToolArgument.int(raw) ?: return ArgResult.Invalid("delaySeconds должно быть целым числом секунд.")
        if (delay !in 0..MAX_SCREENSHOT_DELAY) return ArgResult.Invalid("Задержка скриншота — от 0 до $MAX_SCREENSHOT_DELAY секунд, получено $delay.")
        return ArgResult.Ok(delay)
    }

    fun recording(arguments: JsonObject): ArgResult<RecordingRequest> {
        val duration = ToolArgument.int(arguments["durationSeconds"] ?: arguments["duration"] ?: arguments["seconds"])
            ?: return ArgResult.Invalid("Не передана длительность записи (durationSeconds, 1–$MAX_RECORDING_SECONDS).")
        if (duration !in 1..MAX_RECORDING_SECONDS) {
            return ArgResult.Invalid("Запись экрана — от 1 до $MAX_RECORDING_SECONDS секунд (10 минут), получено $duration.")
        }
        val audio = ToolArgument.bool(arguments["withAudio"] ?: arguments["audio"]) ?: false
        return ArgResult.Ok(RecordingRequest(duration, audio))
    }

    fun phoneDataKind(arguments: JsonObject): ArgResult<PhoneDataKind> {
        val raw = ToolArgument.string(arguments["kind"])?.trim()?.lowercase().orEmpty()
        if (raw.contains("call") || raw.contains("sms") || raw.contains("звон") || raw.contains("смс")) {
            return ArgResult.Invalid("Журнал звонков и SMS недоступны: Honer AI их не читает. Доступно: contacts_count, installed_apps, device_info, usage_today.")
        }
        val kind = PhoneDataKind.entries.firstOrNull { it.raw == raw }
            ?: when {
                raw.contains("contact") || raw.contains("контакт") -> PhoneDataKind.CONTACTS_COUNT
                raw.contains("app") || raw.contains("прилож") -> PhoneDataKind.INSTALLED_APPS
                raw.contains("usage") || raw.contains("screen") || raw.contains("экран") -> PhoneDataKind.USAGE_TODAY
                raw.contains("device") || raw.contains("устрой") -> PhoneDataKind.DEVICE_INFO
                else -> null
            }
            ?: return ArgResult.Invalid("Неизвестный kind «$raw». Доступно: contacts_count, installed_apps, device_info, usage_today.")
        return ArgResult.Ok(kind)
    }

    private fun label(arguments: JsonObject): String =
        (ToolArgument.string(arguments["label"]) ?: ToolArgument.string(arguments["message"])).orEmpty()
            .replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    /** Дни повтора: массив или строка; номера 1–7 — ISO (1 — понедельник). Пусто — без повтора. */
    fun days(value: JsonElement?): ArgResult<List<Int>> {
        val raw: List<String> = when (value) {
            null, is JsonNull -> return ArgResult.Ok(emptyList())
            is JsonArray -> value.mapNotNull { ToolArgument.string(it) }
            is JsonPrimitive -> listOfNotNull(ToolArgument.string(value))
            else -> return ArgResult.Invalid("days — список дней недели.")
        }
        val tokens = raw.flatMap { phrases(it).split(',', ';', ' ', '/', '|') }
            .map { it.trim().trim('.') }.filter { it.isNotEmpty() }
        val result = LinkedHashSet<Int>()
        for (token in tokens) {
            val group = group(token)
            if (group != null) { result.addAll(group); continue }
            val day = day(token) ?: return ArgResult.Invalid("Не понял день недели «$token». Используй mon, tue, wed, thu, fri, sat, sun, weekdays, weekends или daily.")
            result.add(day)
        }
        return ArgResult.Ok(weekOrder.filter { it in result })
    }

    /** Фразы из нескольких слов — в одно слово до разбиения. */
    private fun phrases(text: String): String = text.lowercase()
        .replace("каждый день", "daily").replace("every day", "daily")
        .replace("по будням", "weekdays").replace("по выходным", "weekends")

    private fun group(token: String): List<Int>? = when (token) {
        "weekdays", "workdays", "будни", "будние", "будням", "рабочие", "пн-пт" -> listOf(MON, TUE, WED, THU, FRI)
        "weekend", "weekends", "выходные", "сб-вс" -> listOf(SAT, SUN)
        "daily", "everyday", "every_day", "ежедневно", "каждый_день", "all", "все" -> weekOrder
        else -> null
    }

    private fun day(token: String): Int? {
        token.toIntOrNull()?.let { iso -> return if (iso in 1..7) weekOrder[iso - 1] else null }
        val prefixes = listOf(
            MON to listOf("mo", "пн", "пон"), TUE to listOf("tu", "вт"), WED to listOf("we", "ср"),
            THU to listOf("th", "чт", "чет"), FRI to listOf("fr", "пт", "пят"), SAT to listOf("sa", "сб", "суб"),
            SUN to listOf("su", "вс", "вос"),
        )
        return prefixes.firstOrNull { (_, list) -> list.any { token.startsWith(it) } }?.first
    }

    /** «07:05». */
    fun clock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    /** «1 ч 5 мин», «10 мин», «45 с». */
    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h ч" else null,
            if (m > 0) "$m мин" else null,
            if (s > 0 || (h == 0 && m == 0)) "$s с" else null,
        ).joinToString(" ")
    }

    /** Дни словами: «пн, ср, пт», «по будням», «каждый день». */
    fun daysText(days: List<Int>): String = when {
        days.isEmpty() -> "один раз"
        days.size == 7 -> "каждый день"
        days == listOf(MON, TUE, WED, THU, FRI) -> "по будням"
        days == listOf(SAT, SUN) -> "по выходным"
        else -> days.joinToString(", ") { mapOf(MON to "пн", TUE to "вт", WED to "ср", THU to "чт", FRI to "пт", SAT to "сб", SUN to "вс")[it].orEmpty() }
    }
}
