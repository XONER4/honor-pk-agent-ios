package com.honerai.app.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единая точка доступа к экрану устройства: служба доступности регистрирует здесь свою реализацию
 * [ScreenController] при подключении и снимает при отключении. Инструменты берут контроллер отсюда.
 * Чистый Kotlin — Android-часть живёт в службе.
 */
object AgentController {
    @Volatile
    private var current: ScreenController? = null

    private val _connected = MutableStateFlow(false)
    /** Служба доступности подключена и готова управлять экраном. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val controller: ScreenController? get() = current

    fun register(controller: ScreenController) {
        current = controller
        _connected.value = true
    }

    fun unregister(controller: ScreenController) {
        // Снимаем только «свою» регистрацию: перезапуск службы не должен обнулить новую.
        if (current === controller) {
            current = null
            _connected.value = false
        }
    }
}
