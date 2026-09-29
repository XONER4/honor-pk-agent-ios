package com.honerai.app.extras

import com.honerai.app.core.HonerTool
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.extras.device.AlarmRequest
import com.honerai.app.extras.device.ArgResult
import com.honerai.app.extras.device.DeviceToolArgs
import com.honerai.app.extras.device.DeviceToolSchemas
import com.honerai.app.extras.device.PhoneDataFormat
import com.honerai.app.extras.device.PhoneDataKind
import com.honerai.app.extras.device.RecordingSize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Инструменты устройства: схемы, разбор и проверка аргументов, размер записи. */
class DeviceToolsTest {
    private fun json(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is String -> put(key, value)
            is List<*> -> put(key, buildJsonArray { value.forEach { add(it.toString()) } })
        }
    }

    private fun <T> ok(result: ArgResult<T>): T {
        assertTrue("ожидался успех: $result", result is ArgResult.Ok)
        return (result as ArgResult.Ok).value
    }

    private fun invalid(result: ArgResult<*>): String {
        assertTrue("ожидалась ошибка: $result", result is ArgResult.Invalid)
        return (result as ArgResult.Invalid).message
    }

    // Calendar: SUNDAY=1 … SATURDAY=7.
    private val mon = 2; private val tue = 3; private val wed = 4; private val thu = 5; private val fri = 6; private val sat = 7; private val sun = 1

    @Test
    fun registryAndSchemas() {
        assertEquals(6, DeviceToolSchemas.tools.size)
        for (tool in DeviceToolSchemas.tools) {
            assertTrue(tool.isExtra && tool.isAsync && !tool.isWeb)
            val schema = tool.schema
            val function = schema["function"]!!.jsonObject
            assertEquals(tool.rawValue, function["name"]!!.jsonPrimitive.content)
            assertTrue(function["description"]!!.jsonPrimitive.content.length > 30)
            assertNotNull(DeviceToolSchemas.step(ToolCallRequest("1", tool.rawValue, "{}")))
        }
        val required = HonerTool.SCREEN_RECORDING.schema["function"]!!.jsonObject["parameters"]!!.jsonObject["required"]!!.jsonArray
        assertEquals(listOf("durationSeconds"), required.map { it.jsonPrimitive.content })
        // Будильник и таймер не требуют переключателя доступа, остальное — требует.
        assertFalse(HonerTool.SET_ALARM in DeviceToolSchemas.gated)
        assertFalse(HonerTool.SET_TIMER in DeviceToolSchemas.gated)
        assertEquals(setOf(HonerTool.TAKE_SCREENSHOT, HonerTool.SCREEN_RECORDING, HonerTool.SYSTEM_HEALTH, HonerTool.PHONE_DATA), DeviceToolSchemas.gated)
        // В запросе к модели инструменты есть и без кнопки «Поиск».
        val names = HonerTool.schemas(searchEnabled = false).map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("set_alarm", "set_timer", "take_screenshot", "start_screen_recording", "system_health", "phone_data")))
        assertEquals("Записываю экран…", DeviceToolSchemas.status(setOf("start_screen_recording")))
    }

    @Test
    fun promptBlockMentionsToggle() {
        val off = DeviceToolSchemas.promptBlock(accessEnabled = false)
        assertTrue(off.contains("set_alarm") && off.contains("system_health") && off.contains("phone_data"))
        assertTrue(off.contains("«Доступ ИИ к данным и состоянию телефона»"))
        assertTrue(off.contains("ВЫКЛЮЧЕН"))
        val on = DeviceToolSchemas.promptBlock(accessEnabled = true)
        assertFalse(on.contains("ВЫКЛЮЧЕН"))
        assertTrue(on.contains("включил"))
    }

    @Test
    fun alarmHourAndMinuteRanges() {
        assertEquals(AlarmRequest(7, 30, "", emptyList()), ok(DeviceToolArgs.alarm(json("hour" to 7, "minute" to 30))))
        assertEquals(0, ok(DeviceToolArgs.alarm(json("hour" to 0, "minute" to 0))).hour)
        assertEquals(59, ok(DeviceToolArgs.alarm(json("hour" to 23, "minute" to 59))).minute)
        assertTrue(invalid(DeviceToolArgs.alarm(json("hour" to 24, "minute" to 0))).contains("0 до 23"))
        assertTrue(invalid(DeviceToolArgs.alarm(json("hour" to -1, "minute" to 0))).contains("0 до 23"))
        assertTrue(invalid(DeviceToolArgs.alarm(json("hour" to 7, "minute" to 60))).contains("0 до 59"))
        assertTrue(invalid(DeviceToolArgs.alarm(json("minute" to 5))).contains("hour"))
        // Строки и время одной строкой.
        assertEquals(6, ok(DeviceToolArgs.alarm(json("hour" to "6", "minute" to "05"))).hour)
        val fromTime = ok(DeviceToolArgs.alarm(json("time" to "07:45")))
        assertEquals(7 to 45, fromTime.hour to fromTime.minute)
        // Минуты не переданы — ровно в час.
        assertEquals(0, ok(DeviceToolArgs.alarm(json("hour" to 9))).minute)
    }

    @Test
    fun alarmLabelAndDays() {
        val request = ok(DeviceToolArgs.alarm(json("hour" to 6, "minute" to 45, "label" to "  Работа \n  ", "days" to listOf("mon", "wed", "fri"))))
        assertEquals("Работа", request.label)
        assertEquals(listOf(mon, wed, fri), request.days)
        assertEquals(DeviceToolArgs.MAX_LABEL, ok(DeviceToolArgs.alarm(json("hour" to 6, "label" to "a".repeat(200)))).label.length)
        assertEquals(listOf(mon, tue, wed, thu, fri), ok(DeviceToolArgs.days(JsonPrimitive("будни"))))
        assertEquals(listOf(mon, tue, wed, thu, fri), ok(DeviceToolArgs.days(JsonPrimitive("по будням"))))
        assertEquals(listOf(sat, sun), ok(DeviceToolArgs.days(buildJsonArray { add("weekends") })))
        assertEquals(7, ok(DeviceToolArgs.days(JsonPrimitive("каждый день"))).size)
        assertEquals(listOf(mon, sun), ok(DeviceToolArgs.days(JsonPrimitive("вс, пн"))))
        assertEquals(listOf(mon, sun), ok(DeviceToolArgs.days(buildJsonArray { add("7"); add("1") })))
        assertEquals(listOf(tue, thu), ok(DeviceToolArgs.days(buildJsonArray { add("Tuesday"); add("четверг") })))
        assertEquals(emptyList<Int>(), ok(DeviceToolArgs.days(null)))
        assertTrue(invalid(DeviceToolArgs.days(JsonPrimitive("someday"))).contains("someday"))
        assertTrue(invalid(DeviceToolArgs.days(buildJsonArray { add("8") })).contains("8"))
        assertEquals("по будням", DeviceToolArgs.daysText(listOf(mon, tue, wed, thu, fri)))
        assertEquals("пн, ср", DeviceToolArgs.daysText(listOf(mon, wed)))
        assertEquals("один раз", DeviceToolArgs.daysText(emptyList()))
    }

    @Test
    fun timerLimits() {
        assertEquals(600, ok(DeviceToolArgs.timer(json("seconds" to 600))).seconds)
        assertEquals(1, ok(DeviceToolArgs.timer(json("seconds" to 1))).seconds)
        assertEquals(86_400, ok(DeviceToolArgs.timer(json("seconds" to 86_400))).seconds)
        assertEquals(300, ok(DeviceToolArgs.timer(json("minutes" to 5))).seconds)
        invalid(DeviceToolArgs.timer(json("seconds" to 0)))
        invalid(DeviceToolArgs.timer(json("seconds" to 86_401)))
        invalid(DeviceToolArgs.timer(json("label" to "чай")))
        assertEquals("чай", ok(DeviceToolArgs.timer(json("seconds" to 180, "label" to "чай"))).label)
        assertEquals("1 ч 5 мин", DeviceToolArgs.duration(3900))
        assertEquals("45 с", DeviceToolArgs.duration(45))
        assertEquals("10 мин", DeviceToolArgs.duration(600))
        assertEquals("07:05", DeviceToolArgs.clock(7, 5))
    }

    @Test
    fun recordingDurationIsOneToSixHundredSeconds() {
        assertEquals(1, ok(DeviceToolArgs.recording(json("durationSeconds" to 1))).durationSeconds)
        val max = ok(DeviceToolArgs.recording(json("durationSeconds" to 600, "withAudio" to true)))
        assertEquals(600, max.durationSeconds)
        assertTrue(max.withAudio)
        assertFalse(ok(DeviceToolArgs.recording(json("durationSeconds" to 30))).withAudio)
        assertTrue(ok(DeviceToolArgs.recording(json("durationSeconds" to "30", "withAudio" to "да"))).withAudio)
        assertTrue(invalid(DeviceToolArgs.recording(json("durationSeconds" to 0))).contains("600"))
        assertTrue(invalid(DeviceToolArgs.recording(json("durationSeconds" to 601))).contains("600"))
        invalid(DeviceToolArgs.recording(json()))
    }

    @Test
    fun screenshotDelay() {
        assertEquals(0, ok(DeviceToolArgs.screenshotDelay(json())))
        assertEquals(5, ok(DeviceToolArgs.screenshotDelay(json("delaySeconds" to 5))))
        assertEquals(60, ok(DeviceToolArgs.screenshotDelay(json("delaySeconds" to 60))))
        invalid(DeviceToolArgs.screenshotDelay(json("delaySeconds" to 61)))
        invalid(DeviceToolArgs.screenshotDelay(json("delaySeconds" to -1)))
        invalid(DeviceToolArgs.screenshotDelay(json("delaySeconds" to "скоро")))
    }

    @Test
    fun phoneDataKinds() {
        assertEquals(PhoneDataKind.CONTACTS_COUNT, ok(DeviceToolArgs.phoneDataKind(json("kind" to "contacts_count"))))
        assertEquals(PhoneDataKind.INSTALLED_APPS, ok(DeviceToolArgs.phoneDataKind(json("kind" to "Installed_Apps"))))
        assertEquals(PhoneDataKind.USAGE_TODAY, ok(DeviceToolArgs.phoneDataKind(json("kind" to "usage_today"))))
        assertEquals(PhoneDataKind.DEVICE_INFO, ok(DeviceToolArgs.phoneDataKind(json("kind" to "device"))))
        // Звонки и SMS запрещены правилами — понятный отказ.
        assertTrue(invalid(DeviceToolArgs.phoneDataKind(json("kind" to "recent_calls"))).contains("SMS"))
        assertTrue(invalid(DeviceToolArgs.phoneDataKind(json("kind" to "sms"))).contains("SMS"))
        invalid(DeviceToolArgs.phoneDataKind(json("kind" to "weather")))
    }

    @Test
    fun phoneDataFormatting() {
        val usage = PhoneDataFormat.usage(listOf("YouTube" to 5_400_000L, "Telegram" to 1_200_000L, "Калькулятор" to 30_000L))
        assertTrue(usage.contains("всего 1 ч 50 мин"))
        assertTrue(usage.indexOf("YouTube") < usage.indexOf("Telegram"))
        assertFalse("меньше минуты не показываем", usage.contains("Калькулятор"))
        assertTrue(PhoneDataFormat.usage(emptyList()).contains("почти не использовались"))
        val apps = PhoneDataFormat.apps(List(410) { "App$it" })
        assertTrue(apps.startsWith("Приложений с иконкой на рабочем столе: 410."))
        assertTrue(apps.contains("…и ещё 10."))
    }

    @Test
    fun recordingSizeIsCappedAt1080p() {
        assertEquals(1080 to 2400, RecordingSize.choose(1080, 2400))
        assertEquals(1080 to 2400, RecordingSize.choose(1440, 3200))
        assertEquals(1920 to 1080, RecordingSize.choose(2560, 1440))
        assertEquals(720 to 1600, RecordingSize.choose(720, 1600))
        // Нечётные размеры выравниваются до чётных.
        val odd = RecordingSize.choose(721, 1601)
        assertTrue(odd.first % 2 == 0 && odd.second % 2 == 0)
        // Кодер не умеет полный размер — пробуем кратный 16, потом меньше.
        assertEquals(1072 to 2400, RecordingSize.choose(1080, 2400) { w, _ -> w % 16 == 0 })
        val small = RecordingSize.choose(1080, 2400) { w, h -> w <= 720 && h <= 1600 }
        assertTrue(small.first <= 720 && small.second <= 1600)
        assertTrue(small.second.toDouble() / small.first in 2.1..2.35)
    }

    @Test
    fun bitrateFitsLongRecordings() {
        assertEquals(7_776_000, RecordingSize.bitrate(1080, 2400, 30))
        assertEquals(1_500_000, RecordingSize.bitrate(320, 480, 30))
        assertEquals(12_000_000, RecordingSize.bitrate(3840, 2160, 60))
        val tenMinutes = RecordingSize.bitrate(1080, 2400, 30, 600)
        assertTrue(tenMinutes.toLong() * 600 / 8 <= RecordingSize.MAX_FILE_BYTES)
        assertEquals(7_776_000, RecordingSize.bitrate(1080, 2400, 30, 30))
    }
}
