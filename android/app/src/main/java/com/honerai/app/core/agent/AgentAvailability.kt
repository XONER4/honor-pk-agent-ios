package com.honerai.app.core.agent

/**
 * Статическое зеркало переключателя «Действия в приложениях» (honor.agentEnabled в AppSettings).
 * Нужно, чтобы системная инструкция нейросети и проверка доступности читались без экземпляра настроек.
 * Владелец значения — AppSettings, он обновляет это зеркало.
 */
object AgentAvailability {
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) { enabled = value }
}
