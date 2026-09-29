package com.honerai.app.core.agent

/**
 * Управление экраном для агента — то, что делает служба доступности. Абстрактно, чтобы цикл
 * [AgentSession] проверялся тестами на поддельном экране (без Android).
 */
interface ScreenController {
    /** Приложение впереди и видимые узлы; пароли скрыты. null — экран сейчас недоступен. */
    suspend fun snapshot(): ScreenSnapshot?

    /** Нажать на узел по индексу из последнего снимка. */
    suspend fun tap(index: Int): ActionOutcome

    /** Нажать на узел, чей текст совпадает или содержит [text]. */
    suspend fun tapByText(text: String): ActionOutcome

    /**
     * Ввести текст в узел по индексу. Служба ОБЯЗАНА отказать, если узел — поле пароля
     * (isPassword), и вернуть [ActionOutcome.PasswordRefused].
     */
    suspend fun setText(index: Int, text: String): ActionOutcome

    /** Прокрутить экран: up, down, left, right. */
    suspend fun scroll(direction: String): ActionOutcome

    /** Системная кнопка «Назад». */
    suspend fun back(): ActionOutcome

    /** На главный экран. */
    suspend fun home(): ActionOutcome

    /** Открыть приложение по названию или пакету (через существующий лаунчер). */
    suspend fun openApp(packageOrName: String): ActionOutcome
}

/** Итог одного действия на экране. */
sealed class ActionOutcome(val ok: Boolean, val message: String) {
    object Success : ActionOutcome(true, "готово")
    class Done(val detail: String) : ActionOutcome(true, detail)
    class Failed(val reason: String) : ActionOutcome(false, reason)
    /** Попытка ввести текст в поле пароля — жёсткий отказ. */
    object PasswordRefused : ActionOutcome(false, "поле пароля: ввод запрещён")
    /** Экран или служба недоступны. */
    object NotConnected : ActionOutcome(false, "служба доступности не подключена")
    /** Нужного узла нет в снимке. */
    object NoTarget : ActionOutcome(false, "цель действия не найдена на экране")
}
