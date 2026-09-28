package com.honerai.app.chat

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.GenerationStep
import com.honerai.app.data.MessageRole
import com.honerai.app.data.WebSource
import com.honerai.app.ui.chat.ActivityInfo
import com.honerai.app.ui.chat.ChatInsight
import com.honerai.app.ui.chat.FindRules
import com.honerai.app.ui.chat.MessageMenuAction
import com.honerai.app.ui.chat.NavigationLayout
import com.honerai.app.ui.chat.SourceText
import com.honerai.app.ui.chat.SpeechText
import com.honerai.app.ui.chat.thinkingParagraphs
import com.honerai.app.ui.common.MediaLinks
import com.honerai.app.ui.common.StatusText
import com.honerai.app.ui.common.TimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SpeechTextTest {
    @Test
    fun waitsForCompleteSentence() {
        assertNull(SpeechText.speakableEnd("Привет, как дела", final = false))
        // Предложение закончено, но слишком короткое и без перевода строки — ждём продолжения.
        assertNull(SpeechText.speakableEnd("Да. Нет", final = false))
    }

    @Test
    fun cutsAfterLastFinishedSentence() {
        val text = "Сегодня хорошая погода. Завтра будет дождь! Послезавтра"
        val cut = SpeechText.speakableEnd(text, final = false)
        assertEquals("Сегодня хорошая погода. Завтра будет дождь!", text.substring(0, cut!!))
    }

    @Test
    fun newlineIsAlwaysABoundary() {
        val text = "Итог\nДальше"
        assertEquals("Итог\n", text.substring(0, SpeechText.speakableEnd(text, final = false)!!))
    }

    @Test
    fun terminatorWithoutSpaceIsNotABoundary() {
        // «3.14» и «example.com» — не конец предложения.
        assertNull(SpeechText.speakableEnd("Число пи примерно равно 3.14", final = false))
    }

    @Test
    fun skipsUnclosedCodeFence() {
        val text = "Вот пример кода для вас:\n```kotlin\nval x = 1.\nval y = 2.\n"
        val cut = SpeechText.speakableEnd(text, final = false)
        assertEquals("Вот пример кода для вас:\n", text.substring(0, cut!!))
        // В конце ответа незакрытый код тоже не читается.
        assertEquals(text.indexOf("```"), SpeechText.speakableEnd(text, final = true))
    }

    @Test
    fun finalTakesTheRestAndIgnoresBlank() {
        assertEquals(5, SpeechText.speakableEnd("Готов", final = true))
        assertNull(SpeechText.speakableEnd("   \n ", final = true))
        assertNull(SpeechText.speakableEnd("```kotlin\ncode", final = true))
    }

    @Test
    fun sanitizedRemovesMarkupLinksAndCode() {
        val text = "## Заголовок\n**Жирный** текст [ссылка](https://example.com) [1] и `код`.\n```\nprint(1)\n```\n👍 Готово"
        val clean = SpeechText.sanitized(text)
        assertFalse(clean.contains("**"))
        assertFalse(clean.contains("https"))
        assertFalse(clean.contains("print"))
        assertFalse(clean.contains("[1]"))
        assertFalse(clean.contains("👍"))
        assertTrue(clean.startsWith("Заголовок"))
        assertTrue(clean.contains("Жирный текст ссылка"))
        assertTrue(clean.contains("код."))
    }
}

class StatusTextTest {
    @Test
    fun russianStaysAsIs() {
        assertEquals("Ищу в интернете…", StatusText.localized("Ищу в интернете…", english = false))
    }

    @Test
    fun exactPhrasesAreTranslated() {
        assertEquals("Searching the web…", StatusText.localized("Ищу в интернете…", english = true))
        assertEquals("Thinking…", StatusText.localized("Размышляю…", english = true))
    }

    @Test
    fun patternsAndCompoundLinesAreTranslated() {
        assertEquals("Read 12 of 60", StatusText.localized("Прочитано 12 из 60", english = true))
        assertEquals("Searched the web · sites: 5 · photos",
            StatusText.localized("Искал в интернете · сайтов: 5 · фото", english = true))
        assertEquals("Steps: 3", StatusText.localized("Шаги: 3", english = true))
        assertEquals("Unknown line", StatusText.localized("Unknown line", english = true))
    }
}

class TimeTextTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z")
    private fun ago(duration: Duration, english: Boolean = false) = TimeText.relative(now.minus(duration), now, english, ZoneOffset.UTC)

    @Test
    fun russianRelativeTimestamps() {
        assertEquals("только что", ago(Duration.ofSeconds(10)))
        assertEquals("50 секунд назад", ago(Duration.ofSeconds(50)))
        assertEquals("1 минуту назад", ago(Duration.ofSeconds(70)))
        assertEquals("2 минуты назад", ago(Duration.ofMinutes(2)))
        assertEquals("5 минут назад", ago(Duration.ofMinutes(5)))
        assertEquals("21 минуту назад", ago(Duration.ofMinutes(21)))
        assertEquals("1 час назад", ago(Duration.ofMinutes(94)))
        assertEquals("3 часа назад", ago(Duration.ofHours(3)))
        assertEquals("12 часов назад", ago(Duration.ofHours(12)))
        assertEquals("2 дня назад", ago(Duration.ofDays(2)))
        assertEquals("5 дней назад", ago(Duration.ofDays(5)))
        assertEquals("2 недели назад", ago(Duration.ofDays(15)))
        assertEquals("2 месяца назад", ago(Duration.ofDays(65)))
        assertEquals("1 год назад", ago(Duration.ofDays(400)))
    }

    @Test
    fun englishRelativeTimestamps() {
        assertEquals("just now", ago(Duration.ofSeconds(5), english = true))
        assertEquals("1 minute ago", ago(Duration.ofSeconds(61), english = true))
        assertEquals("2 minutes ago", ago(Duration.ofMinutes(2), english = true))
        assertEquals("3 hours ago", ago(Duration.ofHours(3), english = true))
    }

    @Test
    fun secondsWordsAndReasoningTitles() {
        assertEquals("секунду", TimeText.secondsWord(1))
        assertEquals("секунды", TimeText.secondsWord(3))
        assertEquals("секунд", TimeText.secondsWord(11))
        assertEquals("секунду", TimeText.secondsWord(21))
        assertEquals("секунд", TimeText.secondsWord(112))
        assertEquals("Размышлял 5 секунд", TimeText.finishedReasoningTitle(5, translated = false, english = false))
        assertEquals("Размышлял 1 секунду", TimeText.finishedReasoningTitle(0, translated = false, english = false))
        assertEquals("Thought for 7s", TimeText.finishedReasoningTitle(7, translated = false, english = true))
        assertEquals("Описание рассуждения · перевод", TimeText.finishedReasoningTitle(7, translated = true, english = false))
    }
}

class ChatLogicTest {
    @Test
    fun activityProgressParsesReadCounters() {
        assertEquals(0.264f, ActivityInfo.progress("Прочитано 132 из 500")!!, 0.001f)
        assertEquals(1f, ActivityInfo.progress("Read 9 of 3")!!, 0.001f)
        assertNull(ActivityInfo.progress("Собрано ссылок: 40"))
        assertNull(ActivityInfo.progress("Прочитано 5 из 0"))
    }

    @Test
    fun activitySummary() {
        val steps = listOf(
            GenerationStep(kind = "search", title = "Ищу в интернете", sites = listOf("a.ru", "b.ru")),
            GenerationStep(kind = "read", title = "Читаю страницу", sites = listOf("a.ru", "c.ru")),
            GenerationStep(kind = "images", title = "Ищу фотографии"),
        )
        assertEquals("Искал в интернете · сайтов: 3 · фото", ActivityInfo.summary(steps))
        assertEquals("Шаги: 1", ActivityInfo.summary(listOf(GenerationStep(kind = "weather", title = "Смотрю погоду"))))
        assertEquals(listOf("a.ru", "b.ru"), ActivityInfo.uniqueSites(listOf("www.a.ru", "a.ru", "b.ru")))
    }

    @Test
    fun navigationLayoutMapsTouchToMessage() {
        val layout = NavigationLayout(count = 5, available = 1000f, spacing = 12f)
        assertEquals(48f, layout.height, 0.001f)
        assertEquals(476f, layout.top, 0.001f)
        assertEquals(0, layout.index(0f))
        assertEquals(4, layout.index(2000f))
        assertEquals(2, layout.index(layout.y(2) + 3f))
        // Длинный чат не выше 60 % экрана.
        assertEquals(600f, NavigationLayout(count = 500, available = 1000f, spacing = 12f).height, 0.001f)
        assertEquals(0, NavigationLayout(count = 1, available = 1000f).index(700f))
    }

    @Test
    fun findMatchesAndCounter() {
        val messages = listOf(
            ChatMessage(id = "1", role = MessageRole.USER, content = "Где Париж?"),
            ChatMessage(id = "2", role = MessageRole.ASSISTANT, content = "Во Франции", reasoning = "Париж — столица"),
            ChatMessage(id = "3", role = MessageRole.ASSISTANT, content = "Лондон"),
        )
        assertEquals(listOf("1", "2"), FindRules.matchingIds(messages, "париж"))
        assertTrue(FindRules.matchingIds(messages, "  ").isEmpty())
        assertEquals("0 / 0", FindRules.counter(0, 0))
        assertEquals("2 / 5", FindRules.counter(1, 5))
        assertEquals("5 / 5", FindRules.counter(9, 5))
    }

    @Test
    fun menuActionsMatchIos() {
        assertEquals(listOf("copy", "pinInstruction", "select", "quote", "edit", "fork", "remember", "share"),
            MessageMenuAction.available(MessageRole.USER).map { it.raw })
        assertEquals(listOf("copy", "select", "quote", "pinInstruction", "retry", "fork", "remember", "like", "dislike", "speak", "share"),
            MessageMenuAction.available(MessageRole.ASSISTANT).map { it.raw })
        assertTrue(MessageMenuAction.COPY.requiresContent)
        assertFalse(MessageMenuAction.RETRY.requiresContent)
    }

    @Test
    fun sourceSummaryWording() {
        val read = WebSource(title = "A", url = "https://a.ru/x", snippet = "", content = "text")
        val found = WebSource(title = "", url = "https://www.b.ru/y", snippet = "")
        assertEquals("2 результат(ов) поиска", SourceText.summary(ChatMessage(role = MessageRole.ASSISTANT, sources = listOf(found, found)), false))
        assertEquals("прочитано 1 из 2 страниц", SourceText.summary(ChatMessage(role = MessageRole.ASSISTANT, sources = listOf(read, found)), false))
        assertEquals("1 pages read", SourceText.summary(ChatMessage(role = MessageRole.ASSISTANT, sources = listOf(read)), true))
        assertEquals("A · www.b.ru", SourceText.titles(ChatMessage(role = MessageRole.ASSISTANT, sources = listOf(read, found))))
    }

    @Test
    fun insightLinksAreUniqueAndLabelled() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Смотри [сайт](https://a.ru/page)"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Да, [сайт](https://a.ru/page) и ![фото](https://img.ru/p.jpg) и [ролик](https://youtu.be/dQw4w9WgXcQ)",
                sources = listOf(WebSource(title = "Источник", url = "https://src.ru", snippet = "s"))),
        )
        val links = ChatInsight.links(messages)
        assertEquals(listOf("https://a.ru/page", "https://src.ru", "https://youtu.be/dQw4w9WgXcQ"), links.map { it.url })
        assertEquals("user", links.first().origin)
        val media = ChatInsight.media(messages)
        assertEquals(listOf(ChatInsight.MediaKind.PHOTO, ChatInsight.MediaKind.VIDEO), media.map { it.kind })
        assertTrue(ChatInsight.passes(ChatInsight.Author.USER, "", MessageRole.USER, emptyList()))
        assertFalse(ChatInsight.passes(ChatInsight.Author.ASSISTANT, "", MessageRole.USER, emptyList()))
        assertFalse(ChatInsight.passes(ChatInsight.Author.ALL, "zzz", MessageRole.USER, listOf("abc")))
    }

    @Test
    fun mediaLinks() {
        assertEquals("dQw4w9WgXcQ", MediaLinks.youTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", MediaLinks.youTubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", MediaLinks.youTubeId("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(MediaLinks.youTubeId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(MediaLinks.isVideo("https://rutube.ru/video/abc/"))
        assertTrue(MediaLinks.isVideo("https://cdn.ru/clip.MP4"))
        assertFalse(MediaLinks.isVideo("https://example.com/article"))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", MediaLinks.videoThumbnail("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun thinkingPreviewKeepsLastParagraphs() {
        val text = (1..40).joinToString("\n") { "Абзац номер $it с **выделением** и `кодом`, довольно длинный текст." }
        val paragraphs = thinkingParagraphs(text)
        assertTrue(paragraphs.isNotEmpty())
        assertTrue(paragraphs.last().second.startsWith("Абзац номер 40"))
        assertFalse(paragraphs.any { it.second.contains("**") || it.second.contains("`") })
        assertTrue(paragraphs.sumOf { it.second.length } < 1100)
        // Номер абзаца — его место в тексте: устойчив, пока текст растёт.
        val grown = thinkingParagraphs(text + "\nНовый абзац")
        assertTrue(grown.map { it.first }.containsAll(paragraphs.takeLast(3).map { it.first }))
    }
}
