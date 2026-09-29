package com.honerai.app.core.agent

import com.honerai.app.core.DeepSeekStreaming
import com.honerai.app.core.ToolArgument
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.core.ToolCallResult

/** Куда агент шлёт прогресс (карточки шагов в чате). Реализуется хранилищем чата. */
interface AgentProgressSink {
    /** Начать новую карточку шага, вернуть её id. */
    fun start(title: String, detail: String): String
    fun update(stepId: String, detail: String? = null, done: Boolean? = null)
}

/**
 * Инструмент run_device_task: запускает цикл агента для задачи на естественном языке.
 * Проверяет доступность (переключатель + служба), запускает [AgentSession], передаёт прогресс
 * карточками шагов и просит подтверждение перед важными действиями. Возвращает итог для нейросети.
 */
object DeviceTaskTool {

    /** Как включить функцию — текст для нейросети, когда функция недоступна. */
    fun howToEnable(masterEnabled: Boolean, serviceEnabled: Boolean): String = when {
        !masterEnabled ->
            "Функция «Действия в приложениях» выключена. Коротко и вежливо предложи включить её: Настройки → Разрешения → «Действия в приложениях (агент)», затем включить службу Honer AI в «Специальных возможностях» Android. Не вызывай этот инструмент снова, пока пользователь не включит функцию."
        !serviceEnabled ->
            "Служба специальных возможностей Honer AI не включена в Android. Попроси пользователя открыть Настройки → Разрешения → «Действия в приложениях (агент)» и нажать «Включить службу», затем разрешить Honer AI в системном окне (Android покажет своё предупреждение — это нормально). Не вызывай инструмент снова, пока служба не включена."
        else ->
            "Служба агента сейчас не подключена. Попроси пользователя открыть Honer AI ещё раз и повторить, либо переключить службу в «Специальных возможностях»."
    }

    data class Availability(
        val masterEnabled: Boolean,
        val serviceEnabled: Boolean,
        val connected: Boolean,
    ) {
        val ready: Boolean get() = masterEnabled && serviceEnabled && connected
    }

    suspend fun execute(
        call: ToolCallRequest,
        controller: ScreenController?,
        brain: AgentBrain?,
        availability: Availability,
        sink: AgentProgressSink,
        confirm: suspend (description: String, amount: String?) -> Boolean,
        maxSteps: Int = 25,
    ): ToolCallResult {
        fun reply(text: String) = ToolCallResult(call.id, call.name, text)
        val arguments = call.parsedArguments
        val task = ToolArgument.string(arguments["task"])?.trim().orEmpty()
        val app = ToolArgument.string(arguments["app"])?.trim()?.takeIf { it.isNotEmpty() }
        if (task.isEmpty()) return reply("Не передано, что нужно сделать. Уточни задачу у пользователя.")
        if (!availability.ready || controller == null || brain == null) {
            return reply(howToEnable(availability.masterEnabled, availability.serviceEnabled && availability.connected))
        }

        // Каждый шаг цикла — своя карточка в чате: заголовок и деталь.
        var lastStepId: String? = null
        val session = AgentSession(
            screen = controller,
            brain = brain,
            maxSteps = maxSteps,
            onProgress = { title, detail ->
                lastStepId?.let { sink.update(it, done = true) }
                lastStepId = sink.start(title, detail)
            },
            onConfirm = confirm,
        )
        val outcome = session.run(task, app)
        lastStepId?.let { sink.update(it, done = true) }

        val stepsText = if (outcome.steps.isNotEmpty()) "\nШаги: " + outcome.steps.joinToString("; ") else ""
        return reply(summaryFor(outcome) + stepsText)
    }

    private fun summaryFor(outcome: AgentSession.Outcome): String = when (outcome) {
        is AgentSession.Outcome.Success ->
            "Готово: ${outcome.summary} Коротко подтверди пользователю результат."
        is AgentSession.Outcome.NeedsUser ->
            "Остановился: ${outcome.reason} Передай это пользователю и подскажи, что сделать дальше."
        is AgentSession.Outcome.Cancelled ->
            "Действие отменено: ${outcome.summary} Скажи пользователю, что ничего не выполнено."
        is AgentSession.Outcome.Failed ->
            "Не получилось: ${outcome.summary} Извинись и предложи попробовать иначе или сделать это вручную."
        is AgentSession.Outcome.MaxSteps ->
            "${outcome.summary} Спроси у пользователя, продолжать ли, или уточнить задачу."
    }
}
