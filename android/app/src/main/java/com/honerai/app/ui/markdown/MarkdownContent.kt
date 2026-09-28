package com.honerai.app.ui.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.honerai.app.data.WebSource

// ЗАГОТОВКА (модуль «Отображение ответов» заменит файл целиком, сохранив подпись).

/**
 * Ответ нейросети с разметкой: заголовки, списки, таблицы, код, цитаты, карточки,
 * блоки вопросов ```ask, формулы, картинки и видео. [streaming] — текст ещё печатается.
 * [messageId] и [isLatest] нужны карточкам вопросов (таймер идёт только у последнего ответа).
 * [onAnswer] — ответ на вопрос уходит в чат; false — отправить сейчас нельзя.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    streaming: Boolean = false,
    sources: List<WebSource> = emptyList(),
    messageId: String? = null,
    isLatest: Boolean = false,
    findQuery: String = "",
    onAnswer: (String) -> Boolean = { false },
) {
    Text(text, modifier = modifier)
}
