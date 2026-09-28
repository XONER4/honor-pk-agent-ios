package com.honerai.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порт SpeechTests.swift + разбиение по языкам, произношение знаков и выбор голоса. */
class SpeechTextTest {

    @Test
    fun sanitizerOmitsEmojiFamiliesFlagsAndKeycapsButPreservesNumbers() {
        val result = SpeechText.sanitizedSpeechText("## Привет, **мир**! 👨‍👩‍👧‍👦 🚀 🇷🇺 1️⃣\nЦена 13 500 ₽, 25% и номер 13.")
        assertEquals("Привет, мир!\nЦена 13 500 ₽, 25% и номер 13.", result)
    }

    @Test
    fun sanitizerKeepsNamedLinkLabelsButOmitsNumericCitationsAndURLs() {
        val result = SpeechText.sanitizedSpeechText(
            "Читайте [документацию](https://example.com/docs). [1](https://example.com/ref) [2, 3]\nАдрес: https://example.com/very-long-path?x=7\nПродолжение.")
        assertTrue(result, result.contains("Читайте документацию."))
        assertTrue(result, result.contains("Продолжение."))
        for (hidden in listOf("https", "example", "1", "2, 3", "[")) assertFalse(result, result.contains(hidden))
    }

    @Test
    fun sanitizerDoesNotReadCodeFencesOrPrivateCitationMarkers() {
        val input = "Итог: **готово**.\n```swift\nlet noisy = 123; print(noisy)\n```\nИспользуйте `Honer`. citeturn0search0"
        val result = SpeechText.sanitizedSpeechText(input)
        assertTrue(result, result.contains("Итог: готово."))
        assertTrue(result, result.contains("Используйте Honer."))
        assertFalse(result.contains("noisy"))
        assertFalse(result.contains("turn0search0"))
        assertFalse(result.contains("`"))
    }

    @Test
    fun sanitizerPreservesPlainProseAndMathematicalValues() {
        val input = "Температура −5 °C, время 13:45, сумма 2 × 3 = 6.\nHoner AI 10.0."
        assertEquals(input, SpeechText.sanitizedSpeechText(input))
        assertEquals("", SpeechText.sanitizedSpeechText("😀 ❤️ ✨\n"))
        assertEquals("Таблица: a, b", SpeechText.sanitizedSpeechText("Таблица: a | b"))
        assertEquals("Список\nпункт", SpeechText.sanitizedSpeechText("Список\n- пункт"))
    }

    @Test
    fun spokenFormPronouncesSymbolsAndAddsPauses() {
        assertEquals("На улице 5 градусов Цельсия.", SpeechText.spokenForm("На улице 5 °C"))
        assertEquals("Скидка 25 процентов.", SpeechText.spokenForm("Скидка 25%"))
        assertEquals("Цена 300 рублей.", SpeechText.spokenForm("Цена 300 ₽"))
        assertEquals("Стоит 20 долларов.", SpeechText.spokenForm("Стоит $20"))
        assertEquals("Ветер 5 метров в секунду.", SpeechText.spokenForm("Ветер 5 м/с"))
        assertEquals("то есть 2 до 3.", SpeechText.spokenForm("Т.е. 2–3"))
        assertEquals("2 умножить на 3 равно 6.", SpeechText.spokenForm("2 × 3 = 6"))
        assertEquals("Заголовок.\nПункт один.", SpeechText.spokenForm("Заголовок\nПункт один"))
        assertEquals("Вопрос?", SpeechText.spokenForm("Вопрос?"))
        assertTrue(SpeechText.spokenForm("5 млн рублей").startsWith("5 миллионов"))
    }

    @Test
    fun languageSegmentsSplitLatinAndCyrillic() {
        val segments = SpeechText.languageSegments("Откройте Google Chrome и нажмите Settings. Готово 100%")
        assertEquals(listOf(false, true, false, true, false), segments.map { it.english })
        assertEquals("Откройте ", segments[0].text)
        assertEquals("Google Chrome ", segments[1].text)
        assertTrue(segments[4].text.contains("100%"))
        assertEquals(listOf(SpeechSegment("123 456", false)), SpeechText.languageSegments("123 456"))
        assertTrue(SpeechText.languageSegments("   ").isEmpty())
    }

    @Test
    fun speakableEndCutsAfterSentencesAndOutsideCode() {
        val text = "Это первое предложение. А это ещё не"
        val end = SpeechText.speakableEnd(text, final = false)
        assertEquals("Это первое предложение.", text.substring(0, end!!))
        assertNull(SpeechText.speakableEnd("Коротко. Ещё", final = false))
        assertEquals(14, SpeechText.speakableEnd("Смотрите код:\n```kotlin\nval a = 1. b", final = false))
        assertEquals(6, SpeechText.speakableEnd("Готово", final = true))
        assertNull(SpeechText.speakableEnd("  ", final = true))
    }

    @Test
    fun chunksRespectMaximumLength() {
        val text = "Первое предложение. ".repeat(50)
        val chunks = SpeechText.chunks(text, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun voiceGenderHeuristics() {
        assertEquals(VoiceCatalog.Gender.MALE, VoiceCatalog.gender("en-us-x-iom-local"))
        assertEquals(VoiceCatalog.Gender.FEMALE, VoiceCatalog.gender("en-us-x-tpf-network"))
        assertEquals(VoiceCatalog.Gender.FEMALE, VoiceCatalog.gender("ru-RU-SMTf00"))
        assertEquals(VoiceCatalog.Gender.MALE, VoiceCatalog.gender("ru-RU-SMTm00"))
        assertEquals(VoiceCatalog.Gender.MALE, VoiceCatalog.gender("RHVoice Aleksandr"))
        assertEquals(VoiceCatalog.Gender.FEMALE, VoiceCatalog.gender("Milena (Enhanced)"))
        assertEquals(VoiceCatalog.Gender.FEMALE, VoiceCatalog.gender("voice-7", setOf("female")))
        assertEquals(VoiceCatalog.Gender.MALE, VoiceCatalog.gender("voice-8", setOf("gender=male")))
        assertEquals(VoiceCatalog.Gender.UNKNOWN, VoiceCatalog.gender("ru-ru-x-zzz-local"))
    }

    @Test
    fun voiceChoicePrefersGenderThenOfflineThenQuality() {
        val candidates = listOf(
            VoiceCatalog.Candidate("ru-net-female", "ru", "RU", 500, needsNetwork = true, gender = VoiceCatalog.Gender.FEMALE),
            VoiceCatalog.Candidate("ru-local-female", "ru", "RU", 300, needsNetwork = false, gender = VoiceCatalog.Gender.FEMALE),
            VoiceCatalog.Candidate("ru-local-male", "ru", "RU", 300, needsNetwork = false, gender = VoiceCatalog.Gender.MALE),
            VoiceCatalog.Candidate("en-local-male", "en", "US", 400, needsNetwork = false, gender = VoiceCatalog.Gender.MALE),
        )
        assertEquals("ru-local-female", VoiceCatalog.choose(candidates, "ru-RU", VoiceCatalog.Gender.FEMALE, online = true)?.id)
        assertEquals("ru-local-male", VoiceCatalog.choose(candidates, "ru-RU", VoiceCatalog.Gender.MALE, online = true)?.id)
        assertEquals("en-local-male", VoiceCatalog.choose(candidates, "en", VoiceCatalog.Gender.FEMALE, online = true)?.id)
        // Единственный английский голос другого пола — поправка тона.
        assertEquals(1.1f, VoiceCatalog.choose(candidates, "en", VoiceCatalog.Gender.FEMALE, online = true)!!.pitch)
        assertNull(VoiceCatalog.choose(candidates, "de", VoiceCatalog.Gender.MALE, online = true))

        val unknown = listOf(
            VoiceCatalog.Candidate("b", "ru", "RU", 300, false, VoiceCatalog.Gender.UNKNOWN),
            VoiceCatalog.Candidate("a", "ru", "RU", 300, false, VoiceCatalog.Gender.UNKNOWN),
        )
        assertEquals("a", VoiceCatalog.choose(unknown, "ru", VoiceCatalog.Gender.FEMALE, online = false)?.id)
        assertEquals("b", VoiceCatalog.choose(unknown, "ru", VoiceCatalog.Gender.MALE, online = false)?.id)
    }

    @Test
    fun emojiDetection() {
        assertTrue(SpeechText.isEmojiScalar(0x1F600))
        assertTrue(SpeechText.isEmojiScalar(0x2728))
        assertFalse(SpeechText.isEmojiScalar('°'.code))
        assertFalse(SpeechText.isEmojiScalar(0x2192)) // →
        assertEquals("a   b", SpeechText.replaceEmoji("a 👍🏽 b"))
    }
}
