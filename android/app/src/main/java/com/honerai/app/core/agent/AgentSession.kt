package com.honerai.app.core.agent

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Цикл агента: снимок экрана → следующий шаг от нейросети → проверка безопасности → выполнение → повтор.
 * Экран, «мозг» и подтверждение вынесены в интерфейсы, поэтому весь цикл и предохранители проверяются
 * тестами на поддельном экране и сценарном «мозге».
 */
class AgentSession(
    private val screen: ScreenController,
    private val brain: AgentBrain,
    private val maxSteps: Int = 25,
    private val hintFor: (String?) -> String? = { AppRecipes.hintFor(it) },
    /** Прогресс в чат: заголовок и деталь очередного шага. */
    private val onProgress: suspend (title: String, detail: String) -> Unit = { _, _ -> },
    /** Запрос подтверждения важного действия. true — продолжать, false — отменить. */
    private val onConfirm: suspend (description: String, amount: String?) -> Boolean = { _, _ -> false },
) {
    private val aborted = AtomicBoolean(false)
    private val history = mutableListOf<String>()

    /** Немедленно остановить (пользователь написал «стоп» или нажал «Стоп»). */
    fun abort() { aborted.set(true) }

    /** Итог работы агента. */
    sealed class Outcome(val summary: String) {
        class Success(summary: String) : Outcome(summary)
        /** Нужна помощь пользователя (пароль, капча, неоднозначность, тупик). */
        class NeedsUser(val reason: String) : Outcome(reason)
        class Cancelled(reason: String) : Outcome(reason)
        class Failed(reason: String) : Outcome(reason)
        /** Достигнут предел шагов. */
        class MaxSteps(steps: Int) : Outcome("Достигнут предел в $steps шагов — задача не завершена.")

        val steps: List<String> get() = _steps
        internal var _steps: List<String> = emptyList()
    }

    suspend fun run(task: String, app: String? = null): Outcome {
        val result = try {
            loop(task, app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed("Сбой при выполнении: ${e.message ?: "неизвестная ошибка"}")
        }
        result._steps = history.toList()
        return result
    }

    private suspend fun loop(task: String, app: String?): Outcome {
        if (!app.isNullOrBlank()) {
            report("Открываю приложение", app)
            val opened = screen.openApp(app)
            record("open ${app}: ${opened.message}")
            if (!opened.ok) return Outcome.Failed("Не удалось открыть «$app»: ${opened.message}")
        }
        var steps = 0
        while (steps < maxSteps) {
            if (aborted.get()) return Outcome.Cancelled("Остановлено пользователем.")
            steps++
            val snapshot = screen.snapshot()
                ?: return Outcome.NeedsUser("Не вижу экран. Откройте нужное приложение и повторите — либо включите службу в настройках.")
            // Капча — жёсткий стоп.
            if (AgentSafety.looksLikeCaptcha(snapshot)) {
                return Outcome.NeedsUser("На экране проверка «я не робот» (капча). Пройдите её сами — я не могу и не должен её обходить.")
            }
            val hint = hintFor(snapshot.packageName)
            val raw = brain.nextAction(task, snapshot, history, hint)
            val action = AgentActionParser.parse(raw)
            when (val gate = decide(action, snapshot)) {
                is Decision.Stop -> return gate.outcome
                is Decision.Skip -> { /* подтверждение получено — само действие придёт следующим шагом */ }
                is Decision.Perform -> {
                    val outcome = perform(gate.action, snapshot)
                    if (outcome != null) return outcome
                }
            }
        }
        return Outcome.MaxSteps(maxSteps)
    }

    /** Предохранители: пароли, важные действия, капча, неразобранный ответ. */
    private suspend fun decide(action: AgentAction, snapshot: ScreenSnapshot): Decision {
        when (action) {
            is AgentAction.Unparseable ->
                return Decision.Stop(Outcome.NeedsUser("Не удалось понять следующий шаг. Уточните задачу, пожалуйста."))
            is AgentAction.AskUser ->
                return Decision.Stop(Outcome.NeedsUser(action.message))
            is AgentAction.Done ->
                return Decision.Stop(Outcome.Success(action.summary))
            is AgentAction.Confirm -> {
                record("confirm «${action.description}»")
                val approved = onConfirm(action.description, action.amount)
                if (!approved) return Decision.Stop(Outcome.Cancelled("Отменено: пользователь не подтвердил «${action.description}»."))
                record("подтверждено пользователем")
                // После подтверждения продолжаем цикл — модель сделает само действие следующим шагом.
                return Decision.Skip
            }
            is AgentAction.TypeText -> {
                val node = action.index?.let { snapshot.node(it) }
                // Ввод в поле пароля/кода — запрещён всегда.
                if (node != null && AgentSafety.isCredentialField(node)) {
                    return Decision.Stop(Outcome.NeedsUser("Дальше нужно ввести пароль или код — введите его сами. Я никогда не набираю пароли и коды."))
                }
                if (AgentSafety.mentionsCredential(action.text)) {
                    return Decision.Stop(Outcome.NeedsUser("Похоже, тут нужен пароль или код — введите его сами."))
                }
                return Decision.Perform(action)
            }
            is AgentAction.Tap -> {
                val node = action.index?.let { snapshot.node(it) } ?: action.text?.let { snapshot.nodeByText(it) }
                val label = node?.label ?: action.text.orEmpty()
                // Нажатие на важную кнопку (оплатить/отправить/удалить…) — только после подтверждения.
                if (AgentSafety.isSensitiveLabel(label)) {
                    val amount = AgentSafety.extractAmount(label)
                    record("важное действие: «$label»")
                    val approved = onConfirm(label, amount)
                    if (!approved) return Decision.Stop(Outcome.Cancelled("Отменено: пользователь не подтвердил «$label»."))
                    record("подтверждено пользователем")
                }
                return Decision.Perform(action)
            }
            else -> return Decision.Perform(action)
        }
    }

    /** Выполняет разрешённое действие. Возвращает Outcome, если действие завершает работу, иначе null. */
    private suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Outcome? {
        when (action) {
            is AgentAction.Tap -> {
                val node = action.index?.let { snapshot.node(it) }
                val title = node?.label?.takeIf { it.isNotBlank() } ?: action.text ?: "элемент"
                report("Нажимаю", title.take(60))
                val outcome = if (action.index != null && node != null) screen.tap(action.index)
                else if (!action.text.isNullOrBlank()) screen.tapByText(action.text)
                else return null.also { record("tap: цель не указана") }
                record("tap «${title.take(40)}»: ${outcome.message}")
            }
            is AgentAction.TypeText -> {
                val index = action.index ?: return null.also { record("type: не указан индекс поля") }
                report("Ввожу текст", action.text.take(40))
                val outcome = screen.setText(index, action.text)
                if (outcome === ActionOutcome.PasswordRefused) {
                    return Outcome.NeedsUser("Это поле пароля — введите значение сами. Я не набираю пароли и коды.")
                }
                record("type[$index]: ${outcome.message}")
            }
            is AgentAction.Scroll -> {
                report("Прокручиваю", action.direction)
                record("scroll ${action.direction}: ${screen.scroll(action.direction).message}")
            }
            AgentAction.Back -> { report("Назад", ""); record("back: ${screen.back().message}") }
            AgentAction.Home -> { report("На главный экран", ""); record("home: ${screen.home().message}") }
            is AgentAction.Open -> {
                report("Открываю приложение", action.app)
                record("open ${action.app}: ${screen.openApp(action.app).message}")
            }
            else -> {}
        }
        return null
    }

    private suspend fun report(title: String, detail: String) {
        onProgress(title, detail)
    }

    private fun record(entry: String) {
        history.add(entry)
        if (history.size > 60) history.subList(0, history.size - 60).clear()
    }

    private sealed class Decision {
        class Perform(val action: AgentAction) : Decision()
        class Stop(val outcome: Outcome) : Decision()
        /** Подтверждение получено, само действие придёт следующим шагом — просто продолжаем цикл. */
        object Skip : Decision()
    }
}
