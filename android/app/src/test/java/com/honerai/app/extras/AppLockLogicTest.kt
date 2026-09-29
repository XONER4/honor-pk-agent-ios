package com.honerai.app.extras

import androidx.biometric.BiometricManager
import com.honerai.app.extras.lock.BiometricCapability
import com.honerai.app.extras.lock.BiometricChoice
import com.honerai.app.extras.lock.BiometricKind
import com.honerai.app.extras.lock.LockDelay
import com.honerai.app.extras.lock.LockMode
import com.honerai.app.extras.lock.LockPolicy
import com.honerai.app.extras.lock.PinAttemptPolicy
import com.honerai.app.extras.lock.PinAttempts
import com.honerai.app.extras.lock.PinHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Блокировка приложения: хеш PIN, пауза после ошибок, когда блокировать, выбор биометрии. */
class AppLockLogicTest {
    private val fast = 1_000

    @Test
    fun pinValidation() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("123456"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("1234567"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin(""))
        assertFalse(PinHasher.isValidPin("١٢٣٤")) // не ASCII-цифры
    }

    @Test
    fun hashVerifiesOnlyTheRightPin() {
        val record = PinHasher.hash("2580", salt = ByteArray(16) { (it * 7).toByte() }, iterations = fast)
        assertTrue(record.startsWith("pbkdf2:PBKDF2WithHmacSHA256:1000:"))
        assertFalse("PIN не хранится открыто", record.contains("2580"))
        assertTrue(PinHasher.verify("2580", record))
        assertFalse(PinHasher.verify("2581", record))
        assertFalse(PinHasher.verify("02580", record))
        assertFalse(PinHasher.verify("", record))
    }

    @Test
    fun saltMakesHashesDifferent() {
        val a = PinHasher.hash("1111", iterations = fast)
        val b = PinHasher.hash("1111", iterations = fast)
        assertNotEquals(a, b)
        assertTrue(PinHasher.verify("1111", a) && PinHasher.verify("1111", b))
        // Та же соль — тот же хеш (детерминированность).
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHasher.hash("1111", salt, fast), PinHasher.hash("1111", salt, fast))
    }

    @Test
    fun sha1RecordsFromOldPhonesStillVerify() {
        val record = PinHasher.hash("654321", iterations = fast, algorithm = "PBKDF2WithHmacSHA1")
        assertTrue(record.contains("PBKDF2WithHmacSHA1"))
        assertTrue(PinHasher.verify("654321", record))
        assertFalse(PinHasher.verify("654320", record))
    }

    @Test
    fun malformedRecordsAreRejected() {
        assertFalse(PinHasher.verify("1234", ""))
        assertFalse(PinHasher.verify("1234", "plain:1234"))
        assertFalse(PinHasher.verify("1234", "pbkdf2:PBKDF2WithHmacSHA256:abc:00:00"))
        assertFalse(PinHasher.verify("1234", "pbkdf2:PBKDF2WithHmacSHA256:1000:zz:00"))
        assertFalse(PinHasher.verify("1234", "pbkdf2:NoSuchAlgorithm:1000:0011:0011"))
        assertEquals(null, PinHasher.unhex("abc"))
        assertEquals("00ff10", PinHasher.hex(PinHasher.unhex("00ff10")!!))
    }

    @Test
    fun fiveFailuresLockForThirtySeconds() {
        var state = PinAttempts()
        val t0 = 1_000_000L
        for (i in 1..4) {
            state = PinAttemptPolicy.onFailure(state, t0 + i)
            assertFalse(PinAttemptPolicy.isLocked(state, t0 + i))
            assertEquals(5 - i, PinAttemptPolicy.attemptsLeft(state, t0 + i))
        }
        state = PinAttemptPolicy.onFailure(state, t0 + 5)
        assertTrue(PinAttemptPolicy.isLocked(state, t0 + 5))
        assertEquals(30_000L, PinAttemptPolicy.remainingMs(state, t0 + 5))
        assertEquals(10_000L, PinAttemptPolicy.remainingMs(state, t0 + 20_005))
        // Во время паузы ошибки не копятся и пауза не продлевается.
        assertEquals(state, PinAttemptPolicy.onFailure(state, t0 + 10_000))
        // Пауза кончилась — снова 5 попыток.
        val after = t0 + 5 + 30_000
        assertFalse(PinAttemptPolicy.isLocked(state, after))
        assertEquals(5, PinAttemptPolicy.attemptsLeft(state, after))
        state = PinAttemptPolicy.onFailure(state, after)
        assertEquals(1, state.failures)
        assertFalse(PinAttemptPolicy.isLocked(state, after))
    }

    @Test
    fun successResetsAndClockSkewIsBounded() {
        assertEquals(PinAttempts(), PinAttemptPolicy.onSuccess())
        // Часы перевели назад на час: ждать всё равно не больше 30 секунд.
        val skewed = PinAttempts(failures = 5, lockedUntil = 10_000_000L)
        val now = 10_000_000L - 3_600_000L
        assertEquals(30_000L, PinAttemptPolicy.remainingMs(skewed, now))
        assertEquals(now + 30_000L, PinAttemptPolicy.normalize(skewed, now).lockedUntil)
    }

    @Test
    fun lockOnReturnRespectsModeDelayAndTrips() {
        val away = 1_000L
        assertFalse(LockPolicy.shouldLockOnReturn(LockMode.OFF, away, away + 3_600_000, LockDelay.IMMEDIATE))
        assertFalse("не уходили в фон", LockPolicy.shouldLockOnReturn(LockMode.PIN, null, 5_000, LockDelay.IMMEDIATE))
        assertTrue(LockPolicy.shouldLockOnReturn(LockMode.PIN, away, away + 1, LockDelay.IMMEDIATE))
        assertFalse(LockPolicy.shouldLockOnReturn(LockMode.PIN, away, away + 59_999, LockDelay.ONE_MINUTE))
        assertTrue(LockPolicy.shouldLockOnReturn(LockMode.BIOMETRIC, away, away + 60_000, LockDelay.ONE_MINUTE))
        assertFalse(LockPolicy.shouldLockOnReturn(LockMode.PIN, away, away + 299_000, LockDelay.FIVE_MINUTES))
        assertTrue(LockPolicy.shouldLockOnReturn(LockMode.PIN, away, away + 900_000, LockDelay.FIFTEEN_MINUTES))
        // Поездка в «Часы», открытые самим приложением, не блокирует — пока не истекла.
        assertFalse(LockPolicy.shouldLockOnReturn(LockMode.PIN, away, away + 20_000, LockDelay.IMMEDIATE, tripAllowedUntil = away + 60_000))
        assertTrue(LockPolicy.shouldLockOnReturn(LockMode.PIN, away, away + 120_000, LockDelay.IMMEDIATE, tripAllowedUntil = away + 60_000))
        // Сразу при уходе — только для «Сразу» и не во время своей поездки.
        assertTrue(LockPolicy.shouldLockOnLeave(LockMode.PIN, LockDelay.IMMEDIATE, tripActive = false))
        assertFalse(LockPolicy.shouldLockOnLeave(LockMode.PIN, LockDelay.IMMEDIATE, tripActive = true))
        assertFalse(LockPolicy.shouldLockOnLeave(LockMode.PIN, LockDelay.ONE_MINUTE, tripActive = false))
        assertFalse(LockPolicy.shouldLockOnLeave(LockMode.OFF, LockDelay.IMMEDIATE, tripActive = false))
    }

    @Test
    fun modeAndDelayParsing() {
        assertEquals(LockMode.PIN, LockMode.from("pin"))
        assertEquals(LockMode.BIOMETRIC, LockMode.from("biometric"))
        assertEquals(LockMode.OFF, LockMode.from("garbage"))
        assertEquals(LockMode.OFF, LockMode.from(null))
        assertEquals(LockDelay.FIVE_MINUTES, LockDelay.from(300))
        assertEquals(LockDelay.IMMEDIATE, LockDelay.from(42))
        assertEquals(900_000L, LockDelay.FIFTEEN_MINUTES.millis)
    }

    // ---- Биометрия ----

    private val ok = BiometricManager.BIOMETRIC_SUCCESS
    private val none = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    private val noHardware = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    @Test
    fun strongBiometricPreferred() {
        val option = BiometricChoice.select(BiometricCapability(ok, ok, hasFingerprint = true, hasFace = false))
        assertTrue(option.available)
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, option.authenticators)
        assertEquals(BiometricKind.FINGERPRINT, option.kind)
        assertEquals("Отпечаток пальца", BiometricChoice.label(option.kind, english = false))
    }

    @Test
    fun weakOnlyFaceUnlock() {
        val option = BiometricChoice.select(BiometricCapability(none, ok, hasFingerprint = false, hasFace = true))
        assertTrue(option.available)
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_WEAK, option.authenticators)
        assertEquals(BiometricKind.FACE, option.kind)
        assertEquals("Распознавание лица", BiometricChoice.label(option.kind, false))
        assertEquals("Face unlock", BiometricChoice.label(option.kind, true))
    }

    @Test
    fun severalSensorsAreJustBiometrics() {
        val option = BiometricChoice.select(BiometricCapability(ok, ok, hasFingerprint = true, hasFace = true))
        assertEquals(BiometricKind.GENERIC, option.kind)
        assertEquals("Биометрия", BiometricChoice.label(option.kind, false))
        assertEquals(BiometricKind.GENERIC, BiometricChoice.kind(BiometricCapability(ok, ok, false, false)))
        assertEquals(BiometricKind.GENERIC, BiometricChoice.kind(BiometricCapability(ok, ok, true, false, hasIris = true)))
    }

    @Test
    fun notEnrolledOrMissingHardwareIsNotOffered() {
        val notEnrolled = BiometricChoice.select(BiometricCapability(none, none, hasFingerprint = true, hasFace = false))
        assertFalse(notEnrolled.available)
        assertTrue(notEnrolled.needsEnrollment)
        val missing = BiometricChoice.select(BiometricCapability(noHardware, noHardware, hasFingerprint = false, hasFace = false))
        assertFalse(missing.available)
        assertFalse(missing.needsEnrollment)
        val unavailable = BiometricChoice.select(BiometricCapability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, hasFingerprint = true, hasFace = false))
        assertFalse(unavailable.available)
    }
}
