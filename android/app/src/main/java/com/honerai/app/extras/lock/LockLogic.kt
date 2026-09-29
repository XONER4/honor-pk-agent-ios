package com.honerai.app.extras.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// Логика блокировки приложения без Android: хеш PIN, счётчик ошибок, когда блокировать.
// Всё проверяется обычными модульными тестами.

/** Хеш PIN-кода: PBKDF2 с солью. Сам PIN нигде не хранится. */
object PinHasher {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6
    /** Сколько повторов PBKDF2: ~0,1–0,4 с на слабом телефоне, перебор 10⁶ PIN офлайн — дни. */
    const val DEFAULT_ITERATIONS = 40_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2"

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** PBKDF2WithHmacSHA256 есть с Android 8; на Android 7 — SHA1 (тоже надёжен для PBKDF2). */
    fun preferredAlgorithm(): String = listOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1")
        .firstOrNull { runCatching { SecretKeyFactory.getInstance(it) }.isSuccess } ?: "PBKDF2WithHmacSHA1"

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** Запись вида pbkdf2:алгоритм:повторы:соль:хеш (hex). */
    fun hash(
        pin: String,
        salt: ByteArray = randomSalt(),
        iterations: Int = DEFAULT_ITERATIONS,
        algorithm: String = preferredAlgorithm(),
    ): String {
        require(isValidPin(pin)) { "PIN: 4–6 цифр" }
        val derived = derive(pin, salt, iterations, algorithm)
        return listOf(PREFIX, algorithm, iterations.toString(), hex(salt), hex(derived)).joinToString(":")
    }

    /** PIN подходит к сохранённой записи. Сравнение за постоянное время. */
    fun verify(pin: String, record: String): Boolean {
        if (!isValidPin(pin)) return false
        val parts = record.split(":")
        if (parts.size != 5 || parts[0] != PREFIX) return false
        val iterations = parts[2].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = unhex(parts[3]) ?: return false
        val expected = unhex(parts[4]) ?: return false
        val actual = runCatching { derive(pin, salt, iterations, parts[1]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(actual, expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        return runCatching { ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}

/** Счётчик неверных попыток: после 5 ошибок — пауза 30 секунд. */
data class PinAttempts(val failures: Int = 0, val lockedUntil: Long = 0L)

object PinAttemptPolicy {
    const val MAX_FAILURES = 5
    const val LOCKOUT_MS = 30_000L

    /**
     * Часы могли перевести назад — пауза не длиннее [LOCKOUT_MS] от «сейчас»;
     * закончившаяся пауза обнуляет счётчик (следующие 5 попыток — заново).
     */
    fun normalize(state: PinAttempts, now: Long): PinAttempts = when {
        state.lockedUntil > now + LOCKOUT_MS -> state.copy(lockedUntil = now + LOCKOUT_MS)
        state.lockedUntil in 1..now -> PinAttempts()
        else -> state
    }

    fun isLocked(state: PinAttempts, now: Long): Boolean = normalize(state, now).lockedUntil > now

    /** Сколько миллисекунд ещё ждать (0 — можно вводить). */
    fun remainingMs(state: PinAttempts, now: Long): Long = (normalize(state, now).lockedUntil - now).coerceIn(0L, LOCKOUT_MS)

    fun attemptsLeft(state: PinAttempts, now: Long): Int = (MAX_FAILURES - normalize(state, now).failures).coerceIn(0, MAX_FAILURES)

    fun onFailure(state: PinAttempts, now: Long): PinAttempts {
        val current = normalize(state, now)
        if (current.lockedUntil > now) return current
        val failures = current.failures + 1
        return if (failures >= MAX_FAILURES) PinAttempts(failures, now + LOCKOUT_MS) else PinAttempts(failures, 0L)
    }

    fun onSuccess(): PinAttempts = PinAttempts()
}

/** Режим блокировки: выключена, PIN или биометрия (PIN остаётся запасным способом). */
enum class LockMode(val raw: String) {
    OFF("off"), PIN("pin"), BIOMETRIC("biometric");

    companion object {
        fun from(raw: String?): LockMode = entries.firstOrNull { it.raw == raw } ?: OFF
    }
}

/** Через сколько после ухода в фон блокировать. */
enum class LockDelay(val seconds: Int) {
    IMMEDIATE(0), ONE_MINUTE(60), FIVE_MINUTES(300), FIFTEEN_MINUTES(900);

    val millis: Long get() = seconds * 1000L

    companion object {
        fun from(seconds: Int): LockDelay = entries.firstOrNull { it.seconds == seconds } ?: IMMEDIATE
    }
}

object LockPolicy {
    /**
     * Блокировать ли при возвращении в приложение. Время — монотонное (elapsedRealtime).
     * [tripAllowedUntil] — поездка, которую начало само приложение (часы, запись экрана):
     * пока она не истекла, возвращение не блокирует.
     */
    fun shouldLockOnReturn(
        mode: LockMode,
        backgroundedAt: Long?,
        now: Long,
        delay: LockDelay,
        tripAllowedUntil: Long = 0L,
    ): Boolean {
        if (mode == LockMode.OFF || backgroundedAt == null) return false
        if (tripAllowedUntil > 0 && now <= tripAllowedUntil) return false
        val away = now - backgroundedAt
        // Часы «назад» (перезагрузка без пересоздания процесса невозможна, но на всякий случай) — блокируем.
        if (away < 0) return true
        return away >= delay.millis
    }

    /** Блокировать сразу при уходе в фон (чтобы вернуться уже на экран блокировки). */
    fun shouldLockOnLeave(mode: LockMode, delay: LockDelay, tripActive: Boolean): Boolean =
        mode != LockMode.OFF && delay == LockDelay.IMMEDIATE && !tripActive
}
