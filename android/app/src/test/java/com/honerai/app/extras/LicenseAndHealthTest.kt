package com.honerai.app.extras

import com.honerai.app.extras.device.HealthSnapshot
import com.honerai.app.extras.device.NetworkKind
import com.honerai.app.extras.device.SystemHealthFormat
import com.honerai.app.extras.license.LicenseAgreement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Лицензионное соглашение (когда показывать, полнота текста) и отчёт о состоянии телефона. */
class LicenseAndHealthTest {
    @Test
    fun licenseShownUntilCurrentVersionAccepted() {
        val current = LicenseAgreement.CURRENT_VERSION
        assertTrue("первый запуск", LicenseAgreement.needsAcceptance(0, 0L))
        assertFalse(LicenseAgreement.needsAcceptance(current, 1_700_000_000_000L))
        assertTrue("старая редакция", LicenseAgreement.needsAcceptance(current - 1, 1_700_000_000_000L))
        assertTrue("новая редакция выпущена", LicenseAgreement.needsAcceptance(1, 1_700_000_000_000L, currentVersion = 2))
        assertFalse("принята более новая", LicenseAgreement.needsAcceptance(3, 1_700_000_000_000L, currentVersion = 2))
        assertTrue("версия без времени — не принято", LicenseAgreement.needsAcceptance(current, 0L))
    }

    @Test
    fun licenseTextCoversRequiredPointsInBothLanguages() {
        val ru = LicenseAgreement.plainText(english = false)
        for (phrase in listOf("модель и название", "версию установленного", "дату установки", "дату рождения",
            "время, проведённое", "количество отправленных", "в сети", "в фоне", "печатаете", "чат с администратором",
            "поставщику модели", "без объяснения причин", "«как есть»", "чат с администратором внутри Приложения")) {
            assertTrue("RU: $phrase", ru.contains(phrase))
        }
        val en = LicenseAgreement.plainText(english = true)
        for (phrase in listOf("device model and device name", "install date", "birthday", "time spent", "typing",
            "AI model provider", "without explanation", "\"as is\"", "in-app chat with the administrator")) {
            assertTrue("EN: $phrase", en.contains(phrase))
        }
        assertEquals(LicenseAgreement.sections(false).size, LicenseAgreement.sections(true).size)
        for ((a, b) in LicenseAgreement.sections(false).zip(LicenseAgreement.sections(true))) {
            assertEquals(a.paragraphs.size, b.paragraphs.size)
            assertEquals(a.bullets.size, b.bullets.size)
        }
    }

    private fun snapshot(
        battery: Int? = 82, charging: Boolean? = false, temperature: Double? = 31.5, health: Int? = 2,
        ramTotal: Long = 8L * 1_073_741_824, ramFree: Long = 3L * 1_073_741_824,
        storageTotal: Long = 128L * 1_073_741_824, storageFree: Long = 40L * 1_073_741_824,
        network: NetworkKind = NetworkKind.WIFI, thermal: Int? = 0,
    ) = HealthSnapshot(
        model = "Pixel 8", androidRelease = "15", sdk = 35, securityPatch = "2026-09-05",
        batteryPercent = battery, batteryHealth = health, batteryTemperatureC = temperature, charging = charging,
        plugged = if (charging == true) 1 else 0, batteryVoltageMv = 3950,
        ramTotalBytes = ramTotal, ramAvailableBytes = ramFree, lowMemory = false,
        storageTotalBytes = storageTotal, storageFreeBytes = storageFree,
        cpuCores = 9, abis = listOf("arm64-v8a"), soc = "Google Tensor G3",
        uptimeMs = (3L * 24 * 60 + 4 * 60 + 12) * 60_000, network = network,
        refreshRateHz = 120f, thermalStatus = thermal, batterySaver = false,
    )

    @Test
    fun healthUnitsFormatting() {
        assertEquals("3,0 ГБ", SystemHealthFormat.bytes(3L * 1_073_741_824))
        assertEquals("512 МБ", SystemHealthFormat.bytes(512L * 1_048_576))
        assertEquals("0 МБ", SystemHealthFormat.bytes(0))
        assertEquals("3 дн. 4 ч 12 мин", SystemHealthFormat.uptime((3L * 24 * 60 + 4 * 60 + 12) * 60_000))
        assertEquals("5 мин", SystemHealthFormat.uptime(5 * 60_000L))
        assertEquals("хорошее", SystemHealthFormat.batteryHealth(2))
        assertEquals(null, SystemHealthFormat.batteryHealth(1))
        assertEquals("в норме", SystemHealthFormat.thermal(0))
        assertEquals(null, SystemHealthFormat.thermal(null))
        assertEquals("мобильный интернет", SystemHealthFormat.network(NetworkKind.CELLULAR))
    }

    @Test
    fun healthReportIsStructured() {
        val report = SystemHealthFormat.report(snapshot())
        assertTrue(report.startsWith("Состояние телефона:"))
        for (line in listOf("• Устройство: Pixel 8", "• Android 15 (API 35), патч безопасности от 2026-09-05",
            "• Батарея: 82%, не заряжается, состояние: хорошее, температура 31,5 °C, напряжение 3,95 В",
            "• Энергосбережение: выключено", "• Оперативная память: свободно 3,0 ГБ из 8,0 ГБ (38%)",
            "• Память телефона: свободно 40,0 ГБ из 128,0 ГБ (31%)", "• Процессор: 9 ядер, Google Tensor G3, архитектура arm64-v8a",
            "• Работает без перезагрузки: 3 дн. 4 ч 12 мин", "• Сеть: Wi-Fi", "• Частота экрана: 120 Гц", "• Температурный режим: в норме")) {
            assertTrue(line, report.contains(line))
        }
        assertTrue(report.endsWith("Отклонений не найдено."))
        assertEquals("🔋 82% · ОЗУ 3,0 ГБ своб. · память 40,0 ГБ своб. · Wi-Fi", SystemHealthFormat.summary(snapshot()))
        assertTrue(SystemHealthFormat.summary(snapshot(charging = true)).startsWith("🔋 82% ⚡"))
    }

    @Test
    fun healthWarnings() {
        val bad = snapshot(battery = 9, temperature = 44.0, storageFree = 5L * 1_073_741_824, network = NetworkKind.OFFLINE, thermal = 3, health = 3)
        val warnings = SystemHealthFormat.warnings(bad)
        assertEquals(6, warnings.size)
        assertTrue(warnings.any { it.contains("Заряд низкий: 9%") })
        assertTrue(warnings.any { it.contains("44,0 °C") })
        assertTrue(warnings.any { it.contains("перегрев") })
        assertTrue(warnings.any { it.contains("меньше 10%") })
        assertTrue(warnings.any { it.contains("снижает производительность") })
        assertTrue(warnings.any { it.contains("Нет подключения") })
        assertTrue(SystemHealthFormat.report(bad).contains("Обрати внимание:"))
        // На зарядке низкий заряд — не проблема.
        assertTrue(SystemHealthFormat.warnings(snapshot(battery = 9, charging = true)).isEmpty())
        // Неизвестные значения пропускаются без ошибок.
        val sparse = SystemHealthFormat.report(snapshot(battery = null, charging = null, temperature = null, health = null, thermal = null))
        assertFalse(sparse.contains("Температурный режим"))
        assertFalse(sparse.contains("• Батарея: ,"))
    }
}
