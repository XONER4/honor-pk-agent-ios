package com.honerai.app.core

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.HonorMemory
import com.honerai.app.data.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Логика чата без Android: язык, память, объявления, контекст, профиль, родительский контроль. */
class ChatLogicTest {
    @Test fun russianPolicyAllowsCodeAndNamesButDetectsEnglishAndChineseProse() {
        assertTrue(RussianTextPolicy.needsNormalization("The temperature is rising and the ice absorbs heat."))
        assertTrue(RussianTextPolicy.needsNormalization("冰吸收热量后分子运动加剧，最终从固态转变为液态，这是熔化。"))
        assertFalse(RussianTextPolicy.needsNormalization("Вот пример на Swift:\n```swift\nlet text = \"Hello world\"\nprint(text)\n```"))
        assertFalse(RussianTextPolicy.needsNormalization("Honer AI отвечает на русском языке и использует DeepSeek."))
        assertTrue(RussianTextPolicy.needsNormalization("你好，我可以帮助你。"))
        assertTrue(RussianTextPolicy.needsNormalization("Hello there!"))
        assertTrue(RussianTextPolicy.needsNormalization("Hello!"))
        assertTrue(RussianTextPolicy.needsNormalization("Let me help."))
        assertFalse(RussianTextPolicy.needsNormalization("Honer AI"))
        assertFalse(RussianTextPolicy.needsNormalization("DeepSeek"))
        assertFalse(RussianTextPolicy.needsNormalization("HONOR_TEST_OK"))
        assertFalse(RussianTextPolicy.needsNormalization("```swift\nlet greeting = \"Hello there!\"\n```"))
        assertTrue(RussianTextPolicy.needsReasoningNormalization("We need to explain how ice changes from solid to liquid."))
        assertFalse(RussianTextPolicy.needsReasoningNormalization("Нужно объяснить переход льда в жидкое состояние."))
    }

    @Test fun truncatedOrForeignTranslationIsRejected() {
        val source = "Ice melts when it receives enough heat energy. ".repeat(40)
        assertTrue(RussianTextPolicy.isAcceptableTranslation("Лёд тает, когда получает достаточно тепла. ".repeat(12), source))
        assertTrue(RussianTextPolicy.isAcceptableTranslation("Сначала разберу условие задачи и проверю логику. ".repeat(12), source))
        assertFalse(RussianTextPolicy.isAcceptableTranslation("В", source))
        assertFalse(RussianTextPolicy.isAcceptableTranslation("Лёд тает.", source))
        assertFalse(RussianTextPolicy.isAcceptableTranslation("", source))
        assertFalse(RussianTextPolicy.isAcceptableTranslation(source, source))
        assertFalse(RussianTextPolicy.isAcceptableTranslation("Сначала разберу условие и проверю".repeat(4), source))
        assertTrue(RussianTextPolicy.isAcceptableTranslation("Ок.", "Hi"))
    }

    @Test fun shortFragmentIsNotAnAnswer() {
        assertTrue(ChatLogic.isTooShortToBeAnAnswer("В"))
        assertTrue(ChatLogic.isTooShortToBeAnAnswer("  Х "))
        assertTrue(ChatLogic.isTooShortToBeAnAnswer(""))
        assertFalse(ChatLogic.isTooShortToBeAnAnswer("Не знаю."))
        assertFalse(ChatLogic.isTooShortToBeAnAnswer("Лёд тает при 0 °C."))
    }

    @Test fun keywordsSkipStopWordsAndShortParts() {
        assertEquals(listOf("мой", "любимый", "цвет", "синий"), ChatLogic.keywords("Мой любимый цвет — синий, и я"))
        assertEquals(listOf("стоимость", "билета", "поезд", "казань"), ChatLogic.keywords("стоимость билета поезд Казань"))
        assertEquals(24, ChatLogic.keywords((1..40).joinToString(" ") { "слово$it" }).size)
        assertTrue(ChatLogic.keywords("и в на the of").isEmpty())
    }

    @Test fun relevantMemoriesPickMatchesFirstAndStayFastForFiveThousandFacts() {
        val memories = (0 until 5000).map { index ->
            val text = if (index == 1234) "Живу в Казани и люблю кофе" else "Факт номер $index про разное $index"
            HonorMemory(text = text, keywords = ChatLogic.keywords(text))
        }
        val started = System.nanoTime()
        var picked = emptyList<HonorMemory>()
        repeat(5) { picked = ChatLogic.relevantMemories(memories, "Где я живу? В Казани?") }
        val seconds = (System.nanoTime() - started) / 1e9 / 5
        assertTrue("Отбор памяти слишком медленный: $seconds с", seconds < 0.5)
        assertEquals("Живу в Казани и люблю кофе", picked.first().text)
        assertEquals(40, picked.size)
        // Без слов в запросе — самые свежие факты.
        assertEquals(memories.takeLast(40), ChatLogic.relevantMemories(memories, "и в"))
    }

    @Test fun toolAnnouncementPrefacesAreRemovedFromTheAnswer() {
        val cases = listOf(
            "Сначала посмотрю, какие чаты есть у вас, и найду тот, что про отпуск.## Ответ по вашему чату\nВ чате «Отпуск в Сочи» написано: 12 дней." to
                "## Ответ по вашему чату\nВ чате «Отпуск в Сочи» написано: 12 дней.",
            "Сначала посмотрю список чатов.В вашем чате «Отпуск в Сочи» написано: 12 дней." to "В вашем чате «Отпуск в Сочи» написано: 12 дней.",
            "Сейчас найду нужный чат. Затем прочитаю его и отвечу.В чате «Отпуск в Сочи» сказано: 12 дней." to "В чате «Отпуск в Сочи» сказано: 12 дней.",
            "Сейчас проверю список чатов. Затем открою нужный чат. Потом прочитаю переписку.В чате «Отпуск в Сочи»: 12 дней, с 3 по 14 июля." to
                "В чате «Отпуск в Сочи»: 12 дней, с 3 по 14 июля.",
            "Let me read your chat.Your trip lasts 12 days." to "Your trip lasts 12 days.",
            "Разберу таблицу по столбцам: в первом — города, во втором — дни." to "Разберу таблицу по столбцам: в первом — города, во втором — дни.",
            "Отпуск в Сочи длится 12 дней, с 3 по 14 июля." to "Отпуск в Сочи длится 12 дней, с 3 по 14 июля.",
            "В вашем чате про отпуск я нашёл даты: с 3 по 14 июля." to "В вашем чате про отпуск я нашёл даты: с 3 по 14 июля.",
            "| Город | Дни |\n| --- | --- |\n| Сочи | 12 |" to "| Город | Дни |\n| --- | --- |\n| Сочи | 12 |",
            "Сейчас прочитаю ваш чат." to "",
        )
        for ((input, expected) in cases) assertEquals("вход: $input", expected, ChatLogic.strippingToolAnnouncements(input))
        assertTrue(ChatLogic.isToolAnnouncement("Сначала найду чаты, затем открою нужный."))
        assertFalse(ChatLogic.isToolAnnouncement("Отпуск в Сочи длится 12 дней."))
        assertTrue(ChatLogic.needsAnswerRecovery("Сейчас найду нужный чат."))
    }

    @Test fun chatContextIsAttachedOnlyWhenTheQuestionIsAboutChats() {
        assertTrue(ChatLogic.queryMentionsChats("Посмотри мой чат про отпуск", ""))
        assertTrue(ChatLogic.queryMentionsChats("Что мы обсуждали вчера?", ""))
        assertTrue(ChatLogic.queryMentionsChats("найди это в переписке", ""))
        assertTrue(ChatLogic.queryMentionsChats("а во втором?", "Пользователь: посмотри мой чат про отпуск"))
        assertFalse(ChatLogic.queryMentionsChats("Какая сегодня погода в Новосибирске?", ""))
        assertFalse(ChatLogic.queryMentionsChats("Расскажи историю Рима", ""))
        assertFalse(ChatLogic.queryMentionsChats("Как писали письма в XIX веке?", ""))
        assertTrue(ChatLogic.queryMentionsChats("Что я писал в другом чате?", ""))
    }

    @Test fun requestHistoryBoundsWhatIsSentToTheService() {
        val messages = (0 until 80).flatMap { index ->
            listOf(ChatMessage(role = MessageRole.USER, content = "вопрос $index " + "а".repeat(2000)),
                ChatMessage(role = MessageRole.ASSISTANT, content = "ответ $index " + "б".repeat(2000)))
        }
        val bounded = ChatLogic.requestHistory(messages)
        assertTrue(bounded.size <= 61)
        assertTrue(bounded.sumOf { it.content.length + it.reasoning.length } <= 121_000)
        assertEquals(MessageRole.USER, bounded.first().role)
        assertTrue(bounded.last().content.startsWith("ответ 79"))
        val trimmed = ChatLogic.requestHistory(listOf(ChatMessage(role = MessageRole.USER, content = "в".repeat(60_000))))
        assertEquals(1, trimmed.size)
        assertTrue(trimmed[0].content.length < 21_000)
        assertTrue(trimmed[0].content.contains("сокращено"))
        val cleaned = ChatLogic.requestHistory(listOf(ChatMessage(role = MessageRole.USER, content = "Привет"), ChatMessage(role = MessageRole.ASSISTANT)))
        assertEquals(listOf(MessageRole.USER), cleaned.map { it.role })
    }

    @Test fun reactionLineIsSplitWhileStreamingAndExtractedAtTheEnd() {
        assertEquals("🔥" to "Отличная новость!", ChatLogic.reactionSplit("РЕАКЦИЯ: 🔥\nОтличная новость!"))
        assertEquals(null to "", ChatLogic.reactionSplit("РЕАК"))
        assertEquals(null to "", ChatLogic.reactionSplit("РЕАКЦИЯ: 🔥"))
        assertEquals(null to "Обычный ответ", ChatLogic.reactionSplit("Обычный ответ"))
        assertEquals("👍" to "Спасибо!", ChatLogic.extractReaction("РЕАКЦИЯ: 👍\nСпасибо!"))
        assertNull("Реплика только из реакции остаётся как есть", ChatLogic.extractReaction("РЕАКЦИЯ: 👍"))
    }

    @Test fun profileBlockHasAgeAndAccountDate() {
        val now = LocalDate.parse("2026-05-11").atStartOfDay().toInstant(ZoneOffset.UTC)
        val block = ChatLogic.profileBlock("2000-05-10", Instant.ofEpochSecond(1_700_000_000), now, ZoneOffset.UTC)
        assertTrue(block, block.contains("полных лет: 26"))
        assertTrue(block, block.contains("Аккаунт Honer AI создан"))
        assertTrue(ChatLogic.profileBlock("", null).isEmpty())
    }

    @Test fun toolArgumentsAreReadTolerantly() {
        assertEquals(3, ToolArgument.int(JsonPrimitive(3)))
        assertEquals(3, ToolArgument.int(JsonPrimitive("3")))
        assertEquals(2, ToolArgument.int(JsonPrimitive("№2")))
        assertEquals(2, ToolArgument.int(JsonPrimitive(2.0)))
        assertNull(ToolArgument.int(JsonPrimitive("второй")))
        assertEquals(true, ToolArgument.bool(JsonPrimitive("да")))
        assertEquals(false, ToolArgument.bool(JsonPrimitive("false")))
        assertEquals(true, ToolArgument.bool(JsonPrimitive(true)))
        assertEquals("true", ToolArgument.string(JsonPrimitive(true)))
    }

    @Test fun parentalRefusalPassesToolsThroughWhenControlIsOff() {
        if (!ParentalGuard.enabled) {
            assertNull(ChatLogic.parentalRefusal(ToolCallRequest("1", "web_search", "{\"query\":\"погода\"}")))
            assertNull(ChatLogic.parentalRefusal(ToolCallRequest("2", "open_page", "{\"url\":\"https://example.com\"}")))
        }
    }

    @Test fun stepsAndStatusDescribeTheWork() {
        val step = ChatLogic.step(ToolCallRequest("1", "open_page", "{\"url\":\"https://example.com/a\"}"))
        assertEquals("read", step.kind)
        assertEquals("example.com", step.detail)
        assertEquals("Ищу в интернете…", ChatLogic.toolStatus(listOf(ToolCallRequest("1", "web_search", "{}"), ToolCallRequest("2", "list_chats", "{}"))))
        assertEquals("Читаю сайты…", ChatLogic.toolStatus(listOf(ToolCallRequest("1", "read_many_pages", "{}"))))
        assertEquals("Выполняю действие…", ChatLogic.toolStatus(listOf(ToolCallRequest("1", "unknown", "{}"))))
        assertEquals("Создаю таблицу", ChatLogic.step(ToolCallRequest("1", "create_table", "{\"title\":\"Бюджет\"}")).title)
    }

    @Test fun instructionsBlockSeparatesRulesFromMemory() {
        val block = ChatLogic.instructionsBlock(listOf(com.honerai.app.data.ChatInstruction(text = "Пиши кратко"),
            com.honerai.app.data.ChatInstruction(text = "Образец", author = MessageRole.ASSISTANT)), "Старый промт")
        assertTrue(block.startsWith("## Закреплённые инструкции этого чата"))
        assertTrue(block.contains("1. [закрепил пользователь; написал пользователь]\nСтарый промт"))
        assertTrue(block.contains("3. [закрепил пользователь; твой прежний ответ"))
        assertEquals("", ChatLogic.instructionsBlock(emptyList()))
    }
}
