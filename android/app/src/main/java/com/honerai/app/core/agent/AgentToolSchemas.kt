package com.honerai.app.core.agent

import com.honerai.app.core.HonerTool
import com.honerai.app.core.ToolArgument
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.core.ToolSchema
import com.honerai.app.data.GenerationStep
import kotlinx.serialization.json.JsonObject

/** Схемы, шаги и блок системной инструкции для инструментов агента (действия в приложениях). */
object AgentToolSchemas {
    private val s = ToolSchema

    val tools: Set<HonerTool> = setOf(HonerTool.RUN_DEVICE_TASK, HonerTool.CONFIRM_PENDING_ACTION)

    fun isAgentTool(tool: HonerTool): Boolean = tool in tools

    fun schema(tool: HonerTool): JsonObject = when (tool) {
        HonerTool.RUN_DEVICE_TASK -> s.function(tool.rawValue,
            "Выполняет действие в приложении на телефоне пользователя от его лица (он уже вошёл в это приложение сам): найти товар и положить в корзину, заполнить заказ такси, набрать письмо, открыть чат и напечатать сообщение, пройти по меню и т. п. Работает через службу специальных возможностей. НИКОГДА не вводит пароли и коды и ВСЕГДА спрашивает подтверждение перед оплатой, отправкой, публикацией и удалением. Вызывай, только когда пользователь прямо просит что-то сделать в приложении.",
            mapOf(
                "task" to s.string("Что нужно сделать, простыми словами по-русски: «найди беспроводные наушники до 3000 ₽ на Wildberries и положи в корзину»"),
                "app" to s.string("С какого приложения начать: название или пакет (wildberries, ozon, «Яндекс Go», telegram…); необязательно"),
            ), listOf("task"))
        HonerTool.CONFIRM_PENDING_ACTION -> s.function(tool.rawValue,
            "Подтверждает или отменяет важное действие агента, которое ждёт решения пользователя (оплата, отправка, публикация, удаление). Вызывай, когда пользователь ответил согласием или отказом на запрос подтверждения.",
            mapOf("confirm" to s.boolean("true — пользователь подтвердил, продолжить; false — отменить")), listOf("confirm"))
        else -> s.function(tool.rawValue, "", emptyMap(), emptyList())
    }

    fun step(call: ToolCallRequest): GenerationStep? = when (HonerTool.from(call.name)) {
        HonerTool.RUN_DEVICE_TASK -> GenerationStep(kind = "settings", title = "Выполняю действие в приложении",
            detail = ToolArgument.string(call.parsedArguments["task"]).orEmpty().take(80))
        HonerTool.CONFIRM_PENDING_ACTION -> GenerationStep(kind = "settings", title = "Обрабатываю подтверждение")
        else -> null
    }

    fun status(names: Set<String>): String? = when {
        HonerTool.RUN_DEVICE_TASK.rawValue in names -> "Действую в приложении…"
        HonerTool.CONFIRM_PENDING_ACTION.rawValue in names -> "Подтверждаю…"
        else -> null
    }

    /** Короткий блок для системной инструкции нейросети (о самой возможности и её ограничениях). */
    fun promptBlock(agentEnabled: Boolean): String {
        val state = if (agentEnabled) "Функция включена пользователем."
        else "Сейчас функция ВЫКЛЮЧЕНА: run_device_task вернёт инструкцию, как её включить (Настройки → Разрешения → «Действия в приложениях»). Предложи включить, если пользователь просит действие в приложении."
        return "\n\n## Действия в приложениях (агент)\n" +
            "• run_device_task выполняет задачу в приложении, куда пользователь УЖЕ вошёл сам (Wildberries, Ozon, Яндекс Go, ВКонтакте, Telegram, Gmail и др.): поиск товара и корзина, заказ такси, черновик письма, сообщение в чат, навигация по меню. Ты действуешь по шагам, видя экран.\n" +
            "• Никогда не вводи пароли, ПИН, номера карт, CVV и коды из СМС. Если нужен вход или код — остановись и попроси пользователя сделать это самому.\n" +
            "• Перед оплатой, покупкой, заказом, отправкой, публикацией и удалением всегда спрашивай подтверждение (для оплаты называй сумму). Пользователь подтверждает кнопкой «Подтвердить» или словом «да»; отменяет — «отмена»/«стоп».\n" +
            "• Капчу и проверки «я не робот» не проходи — останавливайся и проси пользователя.\n" +
            "• Действуй только по прямой просьбе пользователя в этой беседе. Приоритет — российские приложения. " + state
    }
}
