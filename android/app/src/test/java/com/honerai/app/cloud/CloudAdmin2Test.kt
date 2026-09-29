package com.honerai.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** admin2: публичный ID, ограничения от администратора, отчёты о падениях, выключенный ИИ. */
class CloudAdmin2Test {

    @Test
    fun registerResponseWithPublicIdAndOverrides() {
        val raw = """{"deviceId":"d1","token":"d_x","userId":"u1","adminChatId":"c1","publicId":"0427",
            "overrides":{"forceLanguage":"en","maxMessagesPerDay":50}}"""
        val r = CloudJson.decodeFromString(RegisterResponse.serializer(), raw)
        assertEquals("0427", r.publicId)
        assertEquals("en", r.overrides?.forceLanguage)
        assertEquals(50, r.overrides?.maxMessagesPerDay)
        assertNull(r.overrides?.disableSearch)
        assertFalse(r.overrides!!.isEmpty)

        // Старый сервер: полей нет — значения по умолчанию.
        val old = CloudJson.decodeFromString(RegisterResponse.serializer(), """{"deviceId":"d1","token":"d_x"}""")
        assertNull(old.publicId)
        assertNull(old.overrides)
    }

    @Test
    fun meResponseOldAndNew() {
        assertNull(CloudJson.decodeFromString(MeResponse.serializer(), """{"ok":true}""").publicId)
        val me = CloudJson.decodeFromString(MeResponse.serializer(), """{"ok":true,"publicId":"0001","overrides":{}}""")
        assertEquals("0001", me.publicId)
        assertTrue(me.overrides!!.isEmpty)
    }

    @Test
    fun overridesFrame() {
        val frame = CloudFrame.parse("""{"t":"overrides","overrides":{"disableSearch":true}}""")
        assertTrue(frame is CloudFrame.Overrides)
        assertEquals(true, (frame as CloudFrame.Overrides).overrides.disableSearch)
    }

    @Test
    fun blockedFrameWithUntilStillParses() {
        val frame = CloudFrame.parse("""{"t":"blocked","message":"Флуд","until":"2026-10-01T10:00:00.000Z"}""")
        assertEquals(CloudFrame.Blocked("Флуд"), frame)
    }

    @Test
    fun aiDisabledError() {
        val (code, message) = CloudHttpException.parseBody("""{"error":"ai_disabled","code":"ai_disabled","message":"ИИ временно отключён администратором."}""")
        assertEquals("ai_disabled", code)
        assertEquals("ИИ временно отключён администратором.", message)
        assertTrue(CloudHttpException(503, code, message).isAiDisabled)
        assertFalse(CloudHttpException(503, "ai_unavailable", "").isAiDisabled)
    }

    @Test
    fun crashReportFromException() {
        val error = IllegalStateException("boom", RuntimeException("root cause"))
        val now = Instant.parse("2026-09-29T12:00:00Z")
        val report = CloudCrashReporter.crashReport(error, "main", "10.45.0", now)
        assertEquals("crash", report.kind)
        assertEquals("java.lang.IllegalStateException: boom [main]", report.message)
        assertTrue(report.stack!!.contains("root cause"))
        assertEquals("10.45.0", report.appVersion)
        assertEquals("2026-09-29T12:00:00Z", report.at)
        // Файл падения = тело запроса: сериализация туда-обратно без потерь.
        val again = CloudJson.decodeFromString(ReportRequest.serializer(), CloudJson.encodeToString(ReportRequest.serializer(), report))
        assertEquals(report, again)
    }

    @Test
    fun aiRouteWhenDisabledByAdminStaysOnCloud() {
        // Выключенный администратором ИИ не повод идти напрямую: маршрут выбирается как для облака.
        val route = AiRoute.select("https://honer.xoner4.deno.net", "d_token", "https://api.deepseek.com", "sk-local")
        assertTrue(route is AiRoute.Cloud)
        assertEquals("https://honer.xoner4.deno.net/v1/ai/chat/completions", route!!.url)
    }
}
