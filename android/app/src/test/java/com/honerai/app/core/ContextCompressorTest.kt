package com.honerai.app.core

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка решения о сжатии контекста: короткие чаты — целиком, длинные — резюме + свежий хвост. */
class ContextCompressorTest {

    private fun messages(count: Int): List<ChatMessage> = (0 until count).map { i ->
        ChatMessage(role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, content = "Сообщение $i")
    }

    @Test fun `short chat is not compressed`() {
        val list = messages(10)
        val result = ContextCompressor.compress(list, summary = "", summarizedUpTo = 0)
        assertFalse(result.compressed)
        assertFalse(result.needsSummary)
        assertEquals(list, result.contextMessages)
    }

    @Test fun `long chat without summary keeps everything but requests a summary`() {
        val list = messages(60)
        val result = ContextCompressor.compress(list, summary = "", summarizedUpTo = 0)
        // Резюме ещё нет — ничего не теряем.
        assertFalse(result.compressed)
        assertTrue(result.needsSummary)
        assertEquals(list.size, result.contextMessages.size)
        // Резюме должно покрыть всё, кроме хвоста.
        assertEquals(60 - ContextCompressor.RECENT_TAIL, result.summarizeUpTo)
    }

    @Test fun `long chat with summary is replaced by summary plus recent tail`() {
        val list = messages(60)
        val upTo = 60 - ContextCompressor.RECENT_TAIL
        val result = ContextCompressor.compress(list, summary = "Ранее обсудили погоду", summarizedUpTo = upTo)
        assertTrue(result.compressed)
        assertFalse(result.needsSummary)
        // Первое сообщение — служебное с кратким содержанием.
        assertTrue(result.contextMessages.first().content.contains("краткое содержание"))
        // Хвост из RECENT_TAIL сообщений + одно служебное.
        assertEquals(ContextCompressor.RECENT_TAIL + 1, result.contextMessages.size)
    }

    @Test fun `latest messages are always present even when compressed`() {
        val list = messages(100)
        val upTo = 100 - ContextCompressor.RECENT_TAIL
        val result = ContextCompressor.compress(list, summary = "резюме", summarizedUpTo = upTo)
        val ids = result.contextMessages.map { it.content }.toSet()
        // Последние RECENT_TAIL сообщений обязаны быть в контексте.
        for (i in (100 - ContextCompressor.RECENT_TAIL) until 100) {
            assertTrue("Свежее сообщение $i потеряно", ids.contains("Сообщение $i"))
        }
    }

    @Test fun `stale summary still keeps messages between coverage and tail`() {
        val list = messages(100)
        // Резюме охватывает только первые 30, но хвост начинается с 80.
        val result = ContextCompressor.compress(list, summary = "резюме", summarizedUpTo = 30)
        // Ожидаем: служебное сообщение + сообщения с 30 по 99 (70 штук).
        assertEquals(1 + (100 - 30), result.contextMessages.size)
        assertTrue(result.needsSummary)
    }

    @Test fun `messages to summarize excludes the recent tail`() {
        val list = messages(60)
        val toSummarize = ContextCompressor.messagesToSummarize(list)
        assertEquals(60 - ContextCompressor.RECENT_TAIL, toSummarize.size)
    }
}
