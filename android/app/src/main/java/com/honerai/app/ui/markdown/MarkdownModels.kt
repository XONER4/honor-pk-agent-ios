package com.honerai.app.ui.markdown

import androidx.compose.runtime.Immutable
import com.honerai.app.ui.questions.QuestionMedia

/**
 * Модель блоков ответа (порт MarkdownBlockKind / MarkdownBlockModel с iOS).
 * Все классы неизменяемые и сравниваются по значению: Compose пропускает
 * перерисовку блока, который не изменился между кадрами печати.
 */
@Immutable
sealed interface BlockKind {
    data class Heading(val level: Int) : BlockKind
    data object Paragraph : BlockKind
    data class Bullets(val items: List<String>, val ordered: Boolean) : BlockKind
    data class Checklist(val items: List<ChecklistItem>) : BlockKind
    data object Quote : BlockKind
    data class Code(val language: String) : BlockKind
    data object CopyBlock : BlockKind
    data class Card(val style: String, val title: String) : BlockKind
    /** Вопросы ```ask; [open] — закрывающая рамка ещё не пришла (ответ печатается). */
    data class Ask(val questions: List<QuickQuestion>, val open: Boolean) : BlockKind
    /** Формула отдельным блоком: $$ … $$ */
    data class MathBlock(val expression: String) : BlockKind
    /** Диаграмма Mermaid: ```mermaid */
    data class Diagram(val source: String) : BlockKind
    data class Table(
        val headers: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>,
    ) : BlockKind
    data object Divider : BlockKind
}

/** Вопрос с вариантами ответов, который Honer AI задаёт пользователю. */
@Immutable
data class QuickQuestion(
    val text: String,
    val options: List<String> = emptyList(),
    /** Разрешить свой вариант ответа. */
    val allowsCustom: Boolean = false,
    /** Номера правильных вариантов — для тестов. */
    val correct: List<Int> = emptyList(),
    /** Картинки, звук, видео и файлы к вопросу. */
    val media: List<QuestionMedia> = emptyList(),
)

@Immutable
data class ChecklistItem(val done: Boolean, val text: String)

enum class TableAlignment { LEADING, CENTER, TRAILING }

/** Блок ответа. [id] — порядковый номер: одинаков у пошагового и полного разбора. */
@Immutable
data class MarkdownBlock(val id: Int, val kind: BlockKind, val text: String)
