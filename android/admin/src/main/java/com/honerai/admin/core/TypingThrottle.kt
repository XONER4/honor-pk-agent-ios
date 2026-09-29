package com.honerai.admin.core

/**
 * Кадры «печатает…» от администратора: typing=true не чаще раза в [intervalMs] пока идёт набор,
 * typing=false — когда набор прекратился или сообщение отправлено. Время передаётся снаружи (тесты).
 */
class TypingThrottle(private val intervalMs: Long = 3_000, private val idleMs: Long = 5_000) {
    private var lastSentTrueAt = Long.MIN_VALUE / 2
    private var lastInputAt = Long.MIN_VALUE / 2
    private var active = false

    /** Текст изменился. Возвращает true, если нужно отправить typing=true. */
    fun onInput(nowMs: Long, textEmpty: Boolean): Boolean? {
        lastInputAt = nowMs
        if (textEmpty) return if (active) { active = false; false } else null
        if (!active || nowMs - lastSentTrueAt >= intervalMs) {
            active = true
            lastSentTrueAt = nowMs
            return true
        }
        return null
    }

    /** Периодическая проверка: пора ли сказать typing=false из-за паузы в наборе. */
    fun onTick(nowMs: Long): Boolean? {
        if (active && nowMs - lastInputAt >= idleMs) {
            active = false
            return false
        }
        return null
    }

    /** Отправили сообщение или ушли с экрана. */
    fun stop(): Boolean? = if (active) { active = false; false } else null
}
