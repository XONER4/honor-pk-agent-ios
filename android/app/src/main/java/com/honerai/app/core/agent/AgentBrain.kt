package com.honerai.app.core.agent

import com.honerai.app.core.DeepSeekStreaming
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageRole

/**
 * Мозг агента: по задаче и текущему снимку экрана возвращает СЫРОЙ ответ модели (ожидается JSON
 * одного действия). Абстрактно, чтобы цикл проверялся тестами со сценарным «мозгом».
 */
interface AgentBrain {
    suspend fun nextAction(task: String, snapshot: ScreenSnapshot?, history: List<String>, recipeHint: String?): String
}

/** Системная инструкция и пользовательский запрос агента для нейросети (одно действие за шаг). */
object AgentPrompt {
    val SYSTEM: String = buildString {
        append("Ты — управляющий агент внутри приложения Honer AI. Ты действуешь на телефоне пользователя через службу специальных возможностей: ")
        append("выполняешь задачу в тех приложениях, куда пользователь УЖЕ вошёл сам. ")
        append("На каждом шаге тебе дают снимок экрана (JSON: приложение и список видимых узлов с индексами i, текстом, флагами clickable/editable/scrollable/password). ")
        append("Верни РОВНО ОДИН JSON-объект следующего действия и ничего больше — без пояснений и markdown.\n")
        append("Доступные действия:\n")
        append("• {\"action\":\"tap\",\"index\":<i>} или {\"action\":\"tap\",\"text\":\"...\"} — нажать узел.\n")
        append("• {\"action\":\"type\",\"index\":<i>,\"text\":\"...\"} — ввести текст в поле.\n")
        append("• {\"action\":\"scroll\",\"direction\":\"down|up|left|right\"} — прокрутить.\n")
        append("• {\"action\":\"back\"} / {\"action\":\"home\"} — назад / на главный экран.\n")
        append("• {\"action\":\"open\",\"app\":\"название или пакет\"} — открыть приложение.\n")
        append("• {\"action\":\"ask_user\",\"message\":\"...\"} — остановиться и спросить пользователя.\n")
        append("• {\"action\":\"confirm\",\"description\":\"что будет сделано\",\"amount\":\"сумма, если оплата\"} — перед важным/необратимым шагом.\n")
        append("• {\"action\":\"done\",\"summary\":\"итог\"} — задача выполнена.\n")
        append("Жёсткие правила:\n")
        append("1. НИКОГДА не вводи пароли, ПИН, номера карт, CVV и коды из СМС; поле password не заполняй — вместо этого верни ask_user и попроси пользователя сделать это самому.\n")
        append("2. Перед нажатием «Оплатить/Купить/Заказать/Оформить/Отправить/Опубликовать/Удалить» и перед отправкой любой формы сначала верни confirm (для оплаты укажи сумму).\n")
        append("3. Если увидел капчу или проверку «я не робот» — верни ask_user, не пытайся её пройти.\n")
        append("4. Действуй только по текущей задаче пользователя. Двигайся маленькими шагами: одно действие — один ответ.\n")
        append("Приоритет — российские приложения и русский язык.")
    }

    fun userPrompt(task: String, snapshot: ScreenSnapshot?, history: List<String>, recipeHint: String?): String = buildString {
        append("Задача пользователя: ").append(task).append('\n')
        if (!recipeHint.isNullOrBlank()) append("Подсказка по приложению: ").append(recipeHint).append('\n')
        if (history.isNotEmpty()) {
            append("Уже сделанные шаги:\n")
            history.takeLast(12).forEach { append("• ").append(it).append('\n') }
        }
        append("Текущий экран:\n")
        append(if (snapshot != null) ScreenSerialization.toCompactString(snapshot) else "(экран недоступен)")
        append("\nВерни ОДИН JSON-объект следующего действия.")
    }
}

/** Рабочий «мозг»: спрашивает нейросеть через существующий клиент DeepSeek. */
class ModelAgentBrain(
    private val client: DeepSeekStreaming,
) : AgentBrain {
    override suspend fun nextAction(task: String, snapshot: ScreenSnapshot?, history: List<String>, recipeHint: String?): String {
        val message = ChatMessage(role = MessageRole.USER, content = AgentPrompt.userPrompt(task, snapshot, history, recipeHint))
        return client.complete(listOf(message), thinking = false, systemInstruction = AgentPrompt.SYSTEM, searchContext = "")
    }
}
