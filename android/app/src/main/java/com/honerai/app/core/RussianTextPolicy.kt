package com.honerai.app.core

/**
 * Ошибки движка чата с текстами для пользователя (как HonorError на iPhone).
 */
sealed class HonorError(message: String) : Exception(message) {
    class MissingApiKey : HonorError("Добавьте ключ DeepSeek в настройках аккаунта, чтобы начать разговор.")
    class InvalidResponse : HonorError("Сервис вернул ответ в неизвестном формате. Попробуйте ещё раз.")
    class UnfinishedResponse : HonorError("Соединение прервалось. Часть ответа сохранена — можно повторить запрос.")
    class EmptyResponse : HonorError("Сервис завершил запрос без ответа. Попробуйте ещё раз.")
    class SearchUnavailable : HonorError("Поиск сейчас недоступен: страницы не открылись. Нажмите «Поиск», чтобы выключить его, — я отвечу по своим знаниям. Заодно я умею читать любые ваши чаты, открывать прикреплённые фото, видео и документы, вести таблицы и запоминать важное.")
    class AttachmentUnavailable(name: String) : HonorError("Не удалось прочитать вложение «$name». Прикрепите файл ещё раз.")
    class RequestTooLarge : HonorError("Слишком много вложений в этом разговоре. Начните новый чат или отправьте меньше изображений.")
    class InvalidArchive : HonorError("Этот файл не является поддерживаемым архивом Honor.")
    class ArchiveTooLarge : HonorError("Архив слишком большой: поддерживается до 100 МБ, включая до 64 МБ файлов.")
    class MemoryLimit : HonorError("После импорта в памяти будет больше 50 записей. Удалите ненужные записи перед импортом.")
    class Http(val status: Int, val detail: String) : HonorError(describe(status, detail))

    companion object {
        fun describe(status: Int, detail: String): String = when (status) {
            401, 403 -> "DeepSeek не принял API-ключ. Проверьте его в настройках аккаунта."
            402 -> "На аккаунте DeepSeek недостаточно средств для запроса."
            429 -> "Лимит запросов DeepSeek достигнут. Попробуйте немного позже."
            in 500..599 -> "DeepSeek временно недоступен ($status). Попробуйте ещё раз."
            else -> if (detail.isEmpty()) "Не удалось выполнить запрос ($status)." else "DeepSeek ($status): $detail"
        }
    }
}

/**
 * Проверки языка ответа: не остался ли текст английским (китайским),
 * не обрывок ли это вместо ответа, годится ли перевод.
 */
object RussianTextPolicy {
    private val codeAndURLs = Regex("(?s)```.*?```|`[^`]*`|https?://\\S+")
    private val markdownLinks = Regex("\\[[^\\]]*\\]\\([^)]*\\)")
    private val brandNames = Regex("(?i)\\b(?:Honer\\s+AI|Honor\\s+AI|Hon[oe]r\\s+PK\\s+Agent|Honor\\s+PC\\s+Agent|DeepSeek|OpenAI)\\b")
    private val latinWords = Regex("[A-Za-z]+")
    private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val shortEnglishPhrases = setOf(
        "hello", "hi", "hey", "hello there", "good morning", "good evening", "good night", "thank you", "thanks",
        "yes", "no", "of course", "sure", "let me help", "let me explain", "i can help", "how can i help",
    )
    private val terminators = setOf('.', '!', '?', '…', ':', '\n')
    private val translationEnds = setOf('.', '!', '?', '…', ':', '»', '"', ')', '`', '*', '|', '-')

    /** Раньше при этом признаке текст стирался прямо во время потока. Теперь текст никогда не прячем. */
    @Suppress("UNUSED_PARAMETER")
    fun holdWhileStreaming(text: String): Boolean = false

    /** Одна-две буквы без знака конца — следствие сбоя («В», «Х», «Ок»), а не ответ. */
    fun isTooShortToBeAnAnswer(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.codePointCount(0, trimmed.length) <= 3 && trimmed.none { it in terminators }
    }

    internal fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /**
     * Код, ссылки и названия могут оставаться на языке оригинала; ищем чужую прозу.
     * Порог высокий: каждый перевод — второй запрос к сервису и риск обрыва.
     */
    fun needsNormalization(text: String): Boolean {
        var prose = codeAndURLs.replace(text, "")
        prose = markdownLinks.replace(prose, "")
        prose = brandNames.replace(prose, "")
        var letters = 0
        var russian = 0
        var cjk = 0
        var i = 0
        while (i < prose.length) {
            val cp = prose.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            if (cp in 0x0400..0x04ff) russian++
            if (cp in 0x3400..0x9fff || cp in 0xf900..0xfaff || cp in 0x20000..0x2fa1f) cjk++
        }
        if (letters == 0 || russian.toDouble() / letters >= 0.3) return false
        if (cjk >= 2) return true
        val trimmed = prose.trim { it.isWhitespace() || isPunctuation(it) }.lowercase()
        if (trimmed in shortEnglishPhrases) return true
        // Одиночный идентификатор кода остаётся как есть.
        if (prose.none { it.isWhitespace() } && prose.contains('_') && identifier.matches(prose)) return false
        if (letters >= 220) return true
        // Восемь латинских слов — это уже английская проза, даже если текст короткий.
        return latinWords.findAll(prose).count() >= 8
    }

    /** Перевод не пустой, не остался чужим языком и не оборван на полуслове. */
    fun isAcceptableTranslation(translated: String, source: String): Boolean {
        val cleaned = translated.trim()
        if (cleaned.isEmpty() || needsNormalization(cleaned)) return false
        val sourceLength = source.trim().length
        if (sourceLength < 200) return true
        if (cleaned.length < maxOf(60, sourceLength / 5)) return false
        val last = cleaned.last()
        if (last !in translationEnds && !last.isDigit()) return false
        return true
    }

    /** Рассуждение — короткий текст: для него главный признак — отсутствие кириллицы. */
    fun needsReasoningNormalization(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 24) return false
        if (needsNormalization(trimmed)) return true
        var letters = 0
        var cyrillic = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            if (cp in 0x0400..0x04ff) cyrillic++
        }
        if (letters < 20) return false
        return cyrillic.toDouble() / letters < 0.2
    }
}
