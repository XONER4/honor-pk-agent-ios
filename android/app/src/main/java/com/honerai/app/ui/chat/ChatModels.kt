package com.honerai.app.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.MessageFeedback
import com.honerai.app.data.WebSource

/** Источники, открытые в окне «Источники» (readOnly — только прочитанные страницы). */
@Immutable
data class SourceSelection(val sources: List<WebSource>, val readOnly: Boolean)

/** Запрос «прокрутить к сообщению» от линий навигации (nonce — чтобы повторный запрос сработал). */
@Immutable
data class ScrollRequest(val nonce: Long, val messageId: String)

/**
 * Действия строк сообщений. Создаётся один раз на экран: строки не пересоздают
 * лямбды на каждой перерисовке, и список не перерисовывается целиком.
 */
@Stable
class MessageActions(
    val onCopy: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onShare: (String) -> Unit,
    val onSpeak: (String) -> Unit,
    val onAttachment: (MessageAttachment) -> Unit,
    val onSources: (SourceSelection) -> Unit,
    val onRetry: (String) -> Unit,
    val onFeedback: (String, MessageFeedback?) -> Unit,
    val onReaction: (String, String?) -> Unit,
    /** Ответ на вопрос агента; false — отправить сейчас нельзя (карточка не фиксирует выбор). */
    val onAnswer: (String) -> Boolean,
    val onMenuOpened: () -> Unit,
    val onMenuAction: (ChatMessage, MessageMenuAction) -> Unit,
)

/** Набор эмодзи для реакции на ответ. */
val ReactionEmojis = listOf("👍", "🔥", "❤️", "😂", "🤔", "👀", "✅", "❌", "💡", "🎯", "🙏", "😮")
