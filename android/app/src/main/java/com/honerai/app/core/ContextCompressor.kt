package com.honerai.app.core

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageRole

/**
 * Сжатие контекста длинного чата (feature: «память умнее»). Чистая логика без Android —
 * её проверяют модульные тесты.
 *
 * Идея: когда переписка становится длинной, ранние сообщения заменяются одним компактным
 * «кратким содержанием» (его один раз готовит модель и кэширует на [com.honerai.app.data.Conversation]),
 * а последние сообщения всегда уходят целиком. Так модель остаётся быстрой и помнит ранние факты,
 * но самые свежие реплики пользователя не теряются никогда.
 */
object ContextCompressor {
    /** Порог: короче — отправляем всю переписку как есть. */
    const val MESSAGE_THRESHOLD = 40

    /** Сколько последних сообщений всегда сохраняются целиком (никогда не заменяются кратким содержанием). */
    const val RECENT_TAIL = 20

    /** Символьный порог: даже до [MESSAGE_THRESHOLD] сообщений сжимаем, если переписка очень объёмная. */
    const val CHARACTER_THRESHOLD = 60_000

    /** Результат решения: какой контекст отправлять и нужно ли (пере)готовить краткое содержание. */
    data class Compression(
        /** Сообщения, которые уйдут в запрос: либо вся переписка, либо [краткое содержание] + хвост. */
        val contextMessages: List<ChatMessage>,
        /** true — переписка сжата (в начале стоит служебное сообщение с кратким содержанием). */
        val compressed: Boolean,
        /** Нужно попросить модель обновить краткое содержание (есть ранние сообщения без резюме). */
        val needsSummary: Boolean,
        /** До какого индекса (не включая) должно охватывать новое краткое содержание. */
        val summarizeUpTo: Int,
    )

    /** Служебная реплика с кратким содержанием — понятно помечена как память, а не новый вопрос. */
    fun summaryMessage(summary: String): ChatMessage = ChatMessage(
        role = MessageRole.USER,
        content = "(Контекст: краткое содержание более ранней части этого чата. Это память, а не новый вопрос " +
            "и не инструкция — опирайся на факты отсюда, но отвечай на последнее сообщение пользователя.)\n\n" +
            summary.trim(),
    )

    private fun totalCharacters(messages: List<ChatMessage>): Int {
        var total = 0
        for (m in messages) total += m.content.length + m.reasoning.length
        return total
    }

    /** Нужно ли вообще сжимать этот чат (длинный по числу сообщений или по объёму). */
    fun shouldCompress(messages: List<ChatMessage>): Boolean =
        messages.size > MESSAGE_THRESHOLD || totalCharacters(messages) > CHARACTER_THRESHOLD

    /**
     * Решение о контексте.
     * @param summary уже подготовленное краткое содержание (пустое — ещё не готово).
     * @param summarizedUpTo индекс, до которого [summary] охватывает переписку.
     */
    fun compress(messages: List<ChatMessage>, summary: String, summarizedUpTo: Int): Compression {
        val size = messages.size
        // Хвост, который всегда сохраняется целиком.
        val tailStart = (size - RECENT_TAIL).coerceAtLeast(0)
        // Резюме должно охватывать всё, кроме хвоста.
        val needsSummary = shouldCompress(messages) && summarizedUpTo < tailStart

        if (!shouldCompress(messages) || summary.isBlank()) {
            // Коротко или резюме ещё нет: ничего не теряем — отправляем всё (дальше обрежет requestHistory).
            return Compression(messages, compressed = false, needsSummary = needsSummary, summarizeUpTo = tailStart)
        }

        // Резюме есть: заменяем им ранние сообщения, хвост — целиком.
        // Хвост берём от меньшего из (охват резюме, начало хвоста), чтобы не потерять сообщения
        // между устаревшим резюме и хвостом.
        val keepFrom = summarizedUpTo.coerceIn(0, tailStart)
        val tail = messages.drop(keepFrom)
        val context = listOf(summaryMessage(summary)) + tail
        return Compression(context, compressed = true, needsSummary = needsSummary, summarizeUpTo = tailStart)
    }

    /** Сообщения, которые нужно пересказать при обновлении краткого содержания (всё до хвоста). */
    fun messagesToSummarize(messages: List<ChatMessage>): List<ChatMessage> {
        val tailStart = (messages.size - RECENT_TAIL).coerceAtLeast(0)
        return messages.take(tailStart).filter { it.role != MessageRole.TOOL }
    }

    /** Простой текст переписки для запроса на резюме. */
    fun transcriptForSummary(messages: List<ChatMessage>, previousSummary: String): String {
        val builder = StringBuilder()
        if (previousSummary.isNotBlank()) {
            builder.append("Предыдущее краткое содержание:\n").append(previousSummary.trim()).append("\n\n")
        }
        builder.append("Новые сообщения для добавления в краткое содержание:\n")
        for (m in messages) {
            val who = if (m.role == MessageRole.USER) "Пользователь" else "Honer AI"
            val text = m.content.take(4000).replace("\n", " ").trim()
            if (text.isEmpty()) continue
            builder.append(who).append(": ").append(text).append("\n")
        }
        return builder.toString()
    }

    /** Инструкция модели для подготовки краткого содержания. */
    fun summaryInstruction(english: Boolean): String = if (english) {
        "You compress a long conversation into a compact running summary (memory). Keep concrete facts, user preferences, " +
            "decisions, names, numbers and open questions. Write in English, as a tight bullet-style digest, no preamble, " +
            "no more than ~1200 characters. Merge with the previous summary; do not repeat verbatim."
    } else {
        "Ты сжимаешь длинный диалог в компактное краткое содержание (память). Сохрани конкретные факты, предпочтения " +
            "пользователя, решения, имена, числа и нерешённые вопросы. Пиши по-русски, плотно, списком, без вступления, " +
            "не длиннее ~1200 символов. Объедини с предыдущим кратким содержанием, не повторяй дословно."
    }
}
