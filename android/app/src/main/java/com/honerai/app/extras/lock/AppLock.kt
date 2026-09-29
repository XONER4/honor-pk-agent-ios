package com.honerai.app.extras.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Итог проверки PIN. */
sealed class PinCheck {
    data object Ok : PinCheck()
    data class Wrong(val attemptsLeft: Int) : PinCheck()
    data class LockedOut(val remainingMs: Long) : PinCheck()
}

/**
 * Блокировка приложения: настройки (режим, задержка, «скрывать в недавних»), хеш PIN,
 * счётчик ошибок и сам признак «заблокировано». Следит за уходом приложения в фон
 * через ProcessLifecycleOwner (поворот экрана и диалоги не считаются уходом).
 */
object AppLock {
    private const val PREFS = "honer.extras.lock"
    private const val KEY_MODE = "mode"
    private const val KEY_PIN = "pinRecord"
    private const val KEY_PIN_LENGTH = "pinLength"
    private const val KEY_DELAY = "delaySeconds"
    private const val KEY_HIDE = "hideInRecents"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCKED_UNTIL = "lockedUntil"

    private var prefs: SharedPreferences? = null
    @Volatile private var initialized = false

    private val _mode = MutableStateFlow(LockMode.OFF)
    private val _delay = MutableStateFlow(LockDelay.IMMEDIATE)
    private val _hideInRecents = MutableStateFlow(false)
    private val _locked = MutableStateFlow(false)
    private val _attempts = MutableStateFlow(PinAttempts())
    private val _pinLength = MutableStateFlow(0)

    val mode: StateFlow<LockMode> = _mode.asStateFlow()
    val delay: StateFlow<LockDelay> = _delay.asStateFlow()
    val hideInRecents: StateFlow<Boolean> = _hideInRecents.asStateFlow()
    /** Сейчас показан экран блокировки. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    val attempts: StateFlow<PinAttempts> = _attempts.asStateFlow()
    /** Длина PIN: по ней рисуются точки и ввод проверяется без кнопки «ОК». */
    val pinLength: StateFlow<Int> = _pinLength.asStateFlow()

    private var backgroundedAt: Long? = null
    private var tripAllowedUntil = 0L

    private val observer = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            val now = SystemClock.elapsedRealtime()
            backgroundedAt = now
            // «Сразу»: экран блокировки встанет ещё до возвращения — чужой не увидит чат ни на миг.
            if (LockPolicy.shouldLockOnLeave(_mode.value, _delay.value, tripAllowedUntil > now)) _locked.value = true
        }

        override fun onStart(owner: LifecycleOwner) {
            val now = SystemClock.elapsedRealtime()
            if (LockPolicy.shouldLockOnReturn(_mode.value, backgroundedAt, now, _delay.value, tripAllowedUntil)) _locked.value = true
            backgroundedAt = null
            tripAllowedUntil = 0L
        }
    }

    /** Вызывается с главного потока при старте интерфейса. Холодный запуск с включённой блокировкой — сразу замок. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val hasPin = !p.getString(KEY_PIN, null).isNullOrEmpty()
        _mode.value = if (hasPin) LockMode.from(p.getString(KEY_MODE, null)) else LockMode.OFF
        _delay.value = LockDelay.from(p.getInt(KEY_DELAY, 0))
        _hideInRecents.value = p.getBoolean(KEY_HIDE, false)
        _pinLength.value = p.getInt(KEY_PIN_LENGTH, 0)
        _attempts.value = PinAttempts(p.getInt(KEY_FAILURES, 0), p.getLong(KEY_LOCKED_UNTIL, 0L))
        _locked.value = _mode.value != LockMode.OFF
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
    }

    val isEnabled: Boolean get() = _mode.value != LockMode.OFF

    /**
     * Приложение само открывает другое окно (часы, системные настройки, запись экрана):
     * возвращение в течение [millis] не блокирует.
     */
    fun allowTrip(millis: Long) {
        tripAllowedUntil = SystemClock.elapsedRealtime() + millis.coerceIn(0L, 30 * 60_000L)
    }

    fun lockNow() { if (isEnabled) _locked.value = true }

    fun unlock() {
        _locked.value = false
        saveAttempts(PinAttemptPolicy.onSuccess())
    }

    fun hasPin(): Boolean = !prefs?.getString(KEY_PIN, null).isNullOrEmpty()

    /** Новый PIN (хеширование — не на главном потоке). */
    suspend fun setPin(pin: String) {
        val record = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
        prefs?.edit()?.putString(KEY_PIN, record)?.putInt(KEY_PIN_LENGTH, pin.length)?.apply()
        _pinLength.value = pin.length
        saveAttempts(PinAttempts())
    }

    /** Проверка PIN с учётом паузы после 5 ошибок. */
    suspend fun checkPin(pin: String): PinCheck {
        val now = System.currentTimeMillis()
        val state = PinAttemptPolicy.normalize(_attempts.value, now)
        if (PinAttemptPolicy.isLocked(state, now)) return PinCheck.LockedOut(PinAttemptPolicy.remainingMs(state, now))
        val record = prefs?.getString(KEY_PIN, null).orEmpty()
        val ok = record.isNotEmpty() && withContext(Dispatchers.Default) { PinHasher.verify(pin, record) }
        if (ok) {
            saveAttempts(PinAttemptPolicy.onSuccess())
            return PinCheck.Ok
        }
        val after = PinAttemptPolicy.onFailure(state, System.currentTimeMillis())
        saveAttempts(after)
        val later = System.currentTimeMillis()
        return if (PinAttemptPolicy.isLocked(after, later)) PinCheck.LockedOut(PinAttemptPolicy.remainingMs(after, later))
        else PinCheck.Wrong(PinAttemptPolicy.attemptsLeft(after, later))
    }

    /** Включить режим. PIN или биометрия требуют заданного PIN. */
    fun setMode(mode: LockMode) {
        val value = if (mode != LockMode.OFF && !hasPin()) LockMode.OFF else mode
        prefs?.edit()?.putString(KEY_MODE, value.raw)?.apply()
        _mode.value = value
        if (value == LockMode.OFF) _locked.value = false
    }

    /** Выключить блокировку и забыть PIN. */
    fun disable() {
        prefs?.edit()?.remove(KEY_PIN)?.remove(KEY_PIN_LENGTH)?.putString(KEY_MODE, LockMode.OFF.raw)?.apply()
        _pinLength.value = 0
        _mode.value = LockMode.OFF
        _locked.value = false
        saveAttempts(PinAttempts())
    }

    fun setDelay(delay: LockDelay) {
        prefs?.edit()?.putInt(KEY_DELAY, delay.seconds)?.apply()
        _delay.value = delay
    }

    fun setHideInRecents(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_HIDE, value)?.apply()
        _hideInRecents.value = value
    }

    private fun saveAttempts(state: PinAttempts) {
        _attempts.value = state
        prefs?.edit()?.putInt(KEY_FAILURES, state.failures)?.putLong(KEY_LOCKED_UNTIL, state.lockedUntil)?.apply()
    }
}
