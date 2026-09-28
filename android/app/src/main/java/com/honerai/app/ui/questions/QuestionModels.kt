package com.honerai.app.ui.questions

import androidx.compose.runtime.Immutable
import com.honerai.app.ui.markdown.QuickQuestion
import com.honerai.app.ui.markdown.VideoLinks
import com.honerai.app.ui.markdown.trimWs

/** Картинка, звук, видео или файл, прикреплённые к вопросу (порт QuestionMedia). */
@Immutable
data class QuestionMedia(val kind: Kind, val value: String) {
    enum class Kind { IMAGE, AUDIO, VIDEO, FILE }

    /** Адрес http/https, иначе null. */
    val url: String?
        get() {
            val trimmed = value.trimWs()
            val lower = trimmed.lowercase()
            return if (lower.startsWith("https://") || lower.startsWith("http://")) trimmed else null
        }

    companion object {
        /** Строка вида `@audio https://…`, `@image …`, `@video …`, `@file имя`. */
        fun parse(line: String): QuestionMedia? {
            val trimmed = line.trimWs()
            if (!trimmed.startsWith("@")) return null
            val body = trimmed.substring(1)
            val space = body.indexOf(' ')
            if (space < 0) return null
            val name = body.substring(0, space).lowercase()
            val value = body.substring(space).trimWs()
            if (value.isEmpty()) return null
            return when (name) {
                "image", "photo", "picture", "картинка", "фото" -> QuestionMedia(Kind.IMAGE, value)
                "audio", "voice", "sound", "аудио", "голос", "звук" -> QuestionMedia(Kind.AUDIO, value)
                "video", "видео" -> QuestionMedia(Kind.VIDEO, value)
                "file", "файл" -> QuestionMedia(Kind.FILE, value)
                else -> null
            }
        }

        /** Картинка Markdown `![подпись](адрес)` внутри вопроса. */
        fun markdownImage(line: String): QuestionMedia? {
            val trimmed = line.trimWs()
            if (!trimmed.startsWith("![")) return null
            val open = trimmed.indexOf("](")
            if (open < 0 || !trimmed.endsWith(")")) return null
            val start = open + 2
            val end = trimmed.length - 1
            if (start >= end) return null
            val address = trimmed.substring(start, end).trimWs()
            val lower = address.lowercase()
            val kind = when {
                VideoLinks.isVideo(address) -> Kind.VIDEO
                lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".ogg") -> Kind.AUDIO
                else -> Kind.IMAGE
            }
            return QuestionMedia(kind, address)
        }
    }
}

/** Строки-настройки в начале блока ```ask: `@timer 10`, `@mode quiz`, `@title …`. */
@Immutable
data class QuestionnaireHeader(
    /** Секунд на один вопрос; 0 — без таймера. */
    val timer: Int = DEFAULT_TIMER,
    val quiz: Boolean = false,
    val title: String = "",
) {
    companion object {
        const val DEFAULT_TIMER = 10

        fun parse(body: String): QuestionnaireHeader {
            var timer = DEFAULT_TIMER
            var quiz = false
            var title = ""
            for (rawLine in body.split('\n')) {
                val line = rawLine.trimWs()
                if (!line.startsWith("@")) continue
                val lower = line.lowercase()
                if (lower.startsWith("@timer") || lower.startsWith("@таймер")) {
                    val value = lower.dropWhile { it != ' ' }.trimWs()
                    if (value == "off" || value == "нет" || value == "0") {
                        timer = 0
                    } else {
                        value.filter { it.isDigit() }.toIntOrNull()?.let { timer = it.coerceIn(3, 600) }
                    }
                } else if (lower.startsWith("@mode quiz") || lower == "@quiz" || lower.startsWith("@тест") ||
                    lower.startsWith("@mode test")
                ) {
                    quiz = true
                } else if (lower.startsWith("@title") || lower.startsWith("@заголовок")) {
                    title = line.dropWhile { it != ' ' }.trimWs()
                }
            }
            return QuestionnaireHeader(timer, quiz, title)
        }
    }
}

/** Итог карточки вопросов для нейросети (порт QuestionnaireReport с iOS; тексты те же). */
object QuestionnaireReport {
    /** Правильный ли ответ в тесте: совпадает с одним из отмеченных вариантов. */
    fun isCorrect(answer: String?, question: QuickQuestion): Boolean {
        if (answer.isNullOrEmpty() || question.correct.isEmpty()) return false
        val normalized = answer.trimWs().lowercase()
        return question.correct.any { index ->
            index < question.options.size && question.options[index].trimWs().lowercase() == normalized
        }
    }

    fun correctText(question: QuickQuestion): String =
        question.correct.mapNotNull { question.options.getOrNull(it) }.joinToString(" / ")

    /** Счёт можно показать, только если у каждого вопроса отмечен правильный ответ. */
    fun isGradable(questions: List<QuickQuestion>): Boolean =
        questions.isNotEmpty() && questions.all { it.correct.isNotEmpty() }

    fun score(answers: List<String?>, questions: List<QuickQuestion>): Int =
        answers.zip(questions).count { (answer, question) -> isCorrect(answer, question) }

    /** Сообщение, которое уходит в чат после последнего вопроса. */
    fun message(answers: List<String?>, questions: List<QuickQuestion>, quiz: Boolean, title: String, english: Boolean): String {
        val noAnswer = if (english) "no answer (time ran out)" else "нет ответа (время вышло)"
        val answered = answers.any { !it.isNullOrEmpty() }
        if (!answered) {
            val single = questions.size == 1
            return if (english) {
                "I didn't answer the ${if (single) "question" else "questions"} in time. Decide yourself how best to proceed and continue."
            } else {
                "Я не ответил на ${if (single) "вопрос" else "вопросы"} за отведённое время. Реши сам, как лучше поступить, и продолжай."
            }
        }
        if (questions.size == 1 && !quiz) return answers.firstOrNull() ?: ""
        val lines = ArrayList<String>()
        val name = if (title.isEmpty()) "" else if (english) " \"$title\"" else " «$title»"
        if (quiz && isGradable(questions)) {
            val points = score(answers, questions)
            lines.add(
                if (english) "Test results$name: $points of ${questions.size}."
                else "Результаты теста$name: $points из ${questions.size}."
            )
        } else if (quiz) {
            // Правильные ответы не отмечены — оценку ставит сама нейросеть.
            lines.add(
                if (english) "Check my answers to the test$name and grade each one:"
                else "Проверь мои ответы на тест$name и оцени каждый:"
            )
        } else {
            lines.add(if (english) "My answers:" else "Мои ответы:")
        }
        questions.forEachIndexed { index, question ->
            val answer = answers.getOrNull(index) ?: ""
            val line = StringBuilder("${index + 1}. ${question.text} — ")
            if (answer.isEmpty()) line.append(noAnswer)
            else line.append(if (english) "my answer: " else "мой ответ: ").append(answer)
            if (quiz && question.correct.isNotEmpty()) {
                if (isCorrect(answer, question)) {
                    line.append(" ✓")
                } else {
                    line.append(
                        if (english) " ✗ (correct: ${correctText(question)})"
                        else " ✗ (правильно: ${correctText(question)})"
                    )
                }
            }
            lines.add(line.toString())
        }
        if (!quiz && answers.any { it.isNullOrEmpty() }) {
            lines.add(if (english) "Where there is no answer, decide yourself." else "Где ответа нет — реши сам.")
        }
        return lines.joinToString("\n")
    }
}
