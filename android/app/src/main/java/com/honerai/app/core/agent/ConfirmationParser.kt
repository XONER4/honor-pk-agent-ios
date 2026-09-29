package com.honerai.app.core.agent

/**
 * Разбор ответа пользователя «да/нет» для подтверждения важного действия агента.
 * Пользователь может подтвердить кнопкой или написать «да / подтверждаю / оплачивай / отправляй»,
 * а отменить — «нет / отмена / стоп». Чистая логика, проверяется тестами.
 */
object ConfirmationParser {
    enum class Verdict { YES, NO, UNKNOWN }

    private val yes = setOf(
        "да", "ага", "угу", "конечно", "хорошо", "ладно", "окей", "ок", "океюшки", "давай", "давай же",
        "подтверждаю", "подтверди", "подтвердить", "подтверждай", "верно", "точно", "именно", "согласен", "согласна",
        "оплачивай", "оплати", "оплатить", "плати", "заплати", "покупай", "купи", "заказывай", "закажи", "оформляй", "оформи",
        "отправляй", "отправь", "отправить", "шли", "пошли", "публикуй", "опубликуй", "выкладывай",
        "продолжай", "продолжи", "продолжить", "жми", "нажимай", "поехали", "го", "вперёд", "вперед", "плюс",
        "yes", "yep", "yeah", "ok", "okay", "sure", "confirm", "go", "proceed",
    )

    private val no = setOf(
        "нет", "неа", "не", "неее", "нельзя", "отмена", "отмени", "отменить", "отменяй", "отбой", "стоп", "стой",
        "прекрати", "прекратить", "останови", "остановись", "хватит", "не надо", "ненадо", "не нужно", "погоди", "подожди",
        "no", "nope", "nah", "cancel", "stop", "abort", "wait",
    )

    /** Разбирает короткий ответ. Смотрит на слова, а не на подстроки: «недавно» не считается «нет». */
    fun parse(raw: String): Verdict {
        val text = normalize(raw)
        if (text.isEmpty()) return Verdict.UNKNOWN
        // Целая фраза «не надо», «не нужно» — отказ, хотя состоит из отдельных слов.
        if (text == "не надо" || text == "не нужно" || text == "не хочу" || text.startsWith("не надо") || text.startsWith("не нужно")) return Verdict.NO
        val words = text.split(' ', ',', '.', '!', '?', ';', ':', '—', '-').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return Verdict.UNKNOWN
        val hasNo = words.any { it in no }
        val hasYes = words.any { it in yes }
        // Явный отказ важнее: «нет, стоп» и «нет, давай потом» — это NO.
        if (hasNo && !hasYes) return Verdict.NO
        if (hasYes && !hasNo) return Verdict.YES
        if (hasNo && hasYes) return Verdict.NO
        return Verdict.UNKNOWN
    }

    /** Пользователь просит немедленно остановиться. */
    fun isAbort(raw: String): Boolean {
        val words = normalize(raw).split(' ', ',', '.', '!', '?').map { it.trim() }
        return words.any { it == "стоп" || it == "стой" || it == "отмена" || it == "хватит" || it == "прекрати" || it == "stop" || it == "abort" }
    }

    private fun normalize(raw: String): String =
        raw.lowercase().replace('ё', 'е').trim()
}
