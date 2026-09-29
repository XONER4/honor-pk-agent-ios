package com.honerai.app.core.agent

import com.honerai.app.core.get
import com.honerai.app.core.int
import com.honerai.app.core.obj
import com.honerai.app.core.parseJson
import com.honerai.app.core.str
import kotlinx.serialization.json.JsonObject

/**
 * Одно следующее действие, которое возвращает нейросеть. Модель отдаёт маленький JSON вида
 * {"action":"tap","index":3} или {"action":"confirm","description":"Оплатить 1990 ₽","amount":"1990 ₽"}.
 */
sealed class AgentAction {
    /** Нажать по индексу или по тексту кнопки. */
    data class Tap(val index: Int?, val text: String?) : AgentAction()
    /** Ввести текст в поле по индексу (никогда — в поле пароля). */
    data class TypeText(val index: Int?, val text: String) : AgentAction()
    data class Scroll(val direction: String) : AgentAction()
    object Back : AgentAction()
    object Home : AgentAction()
    /** Открыть приложение по названию или пакету. */
    data class Open(val app: String) : AgentAction()
    /** Остановиться и спросить пользователя (нужны данные, пароль, капча, тупик). */
    data class AskUser(val message: String) : AgentAction()
    /** Важное/необратимое действие: требуется подтверждение пользователя. */
    data class Confirm(val description: String, val amount: String?) : AgentAction()
    /** Задача выполнена. */
    data class Done(val summary: String) : AgentAction()
    /** Не удалось понять ответ модели. */
    data class Unparseable(val raw: String) : AgentAction()
}

/** Разбор действия из ответа модели — терпимо к обёртке из текста и разным именам полей. */
object AgentActionParser {
    fun parse(raw: String): AgentAction {
        val json = extractObject(raw) ?: return AgentAction.Unparseable(raw.take(200))
        val action = (json["action"].str ?: json["type"].str ?: json["name"].str).orEmpty().trim().lowercase()
        fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { json[it].str?.takeIf { s -> s.isNotBlank() } }
        fun index(vararg keys: String): Int? = keys.firstNotNullOfOrNull { json[it].int }
        return when {
            action == "tap" || action == "click" || action == "press" ->
                AgentAction.Tap(index("index", "i", "node"), text("text", "label", "target"))
            action == "type" || action == "settext" || action == "set_text" || action == "input" || action == "fill" ->
                AgentAction.TypeText(index("index", "i", "node"), text("text", "value", "content").orEmpty())
            action == "scroll" || action == "swipe" ->
                AgentAction.Scroll((text("direction", "dir") ?: "down").lowercase())
            action == "back" -> AgentAction.Back
            action == "home" -> AgentAction.Home
            action == "open" || action == "open_app" || action == "openapp" || action == "launch" ->
                AgentAction.Open(text("app", "package", "target", "name").orEmpty())
            action == "ask_user" || action == "ask" || action == "stop" || action == "clarify" ->
                AgentAction.AskUser(text("message", "text", "reason", "question").orEmpty().ifEmpty { "Нужна ваша помощь, чтобы продолжить." })
            action == "confirm" || action == "confirmation" || action == "verify" ->
                AgentAction.Confirm(text("description", "text", "message", "action").orEmpty().ifEmpty { "Подтвердите действие" }, text("amount", "price", "sum"))
            action == "done" || action == "finish" || action == "complete" || action == "finished" ->
                AgentAction.Done(text("summary", "text", "result", "message").orEmpty().ifEmpty { "Задача выполнена." })
            else -> AgentAction.Unparseable(raw.take(200))
        }
    }

    /** Достаёт первый JSON-объект из текста: модель иногда добавляет пояснения вокруг JSON. */
    private fun extractObject(raw: String): JsonObject? {
        val trimmed = stripFences(raw.trim())
        (parseJson(trimmed) as? JsonObject)?.let { return it }
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return parseJson(trimmed.substring(start, i + 1)) as? JsonObject
                }
            }
        }
        return null
    }

    /** Убирает обрамление ```json … ```. */
    private fun stripFences(text: String): String {
        if (!text.startsWith("```")) return text
        return text.removePrefix("```").removePrefix("json").removePrefix("JSON").trim().removeSuffix("```").trim()
    }

    /** Для истории: короткое человекочитаемое описание разобранного действия. */
    fun describe(action: AgentAction): String = when (action) {
        is AgentAction.Tap -> "tap ${action.index ?: "«${action.text}»"}"
        is AgentAction.TypeText -> "type[${action.index}] «${action.text.take(40)}»"
        is AgentAction.Scroll -> "scroll ${action.direction}"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
        is AgentAction.Open -> "open ${action.app}"
        is AgentAction.AskUser -> "ask_user"
        is AgentAction.Confirm -> "confirm «${action.description}»"
        is AgentAction.Done -> "done"
        is AgentAction.Unparseable -> "unparseable"
    }
}
