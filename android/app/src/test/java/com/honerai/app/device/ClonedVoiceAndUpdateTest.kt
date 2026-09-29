package com.honerai.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Мой голос» (правила клона) и сопоставление результата проверки обновлений с состоянием. */
class ClonedVoiceAndUpdateTest {

    // MARK: Мой голос — включение

    @Test
    fun clonedIsUsedOnlyWithConsentVoiceAndKey() {
        assertTrue(ClonedVoicePlan.shouldUseCloned(true, "voice-123", "fk-abc"))
        // Тумблер выключен.
        assertFalse(ClonedVoicePlan.shouldUseCloned(false, "voice-123", "fk-abc"))
        // Голос ещё не создан.
        assertFalse(ClonedVoicePlan.shouldUseCloned(true, "", "fk-abc"))
        // Нет ключа Fish Audio.
        assertFalse(ClonedVoicePlan.shouldUseCloned(true, "voice-123", ""))
        assertFalse(ClonedVoicePlan.shouldUseCloned(true, "voice-123", "   "))
    }

    // MARK: Мой голос — деление текста на куски

    @Test
    fun shortTextStaysWhole() {
        assertEquals(listOf("Привет! Как дела?"), ClonedVoicePlan.synthChunks("Привет! Как дела?"))
    }

    @Test
    fun blankTextGivesNoChunks() {
        assertTrue(ClonedVoicePlan.synthChunks("   \n ").isEmpty())
        assertTrue(ClonedVoicePlan.synthChunks("").isEmpty())
    }

    @Test
    fun longTextIsSplitIntoSpeakableChunks() {
        val sentence = "Это довольно длинное предложение для проверки деления на куски. "
        val text = sentence.repeat(12) // заметно длиннее лимита
        val parts = ClonedVoicePlan.synthChunks(text, max = 120)
        assertTrue("ожидали несколько кусков, получили ${parts.size}", parts.size > 1)
        assertTrue("каждый кусок не длиннее лимита", parts.all { it.length <= 120 })
        assertTrue("куски непустые", parts.all { it.isNotBlank() })
        // Слова не теряются: склейка кусков содержит те же слова, что и исходник.
        val wordsIn = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val wordsOut = parts.joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }.size
        assertEquals(wordsIn, wordsOut)
    }

    // MARK: Проверка обновлений — состояние по результату

    private val newer = UpdateInfo("10.46.0", 1046, "notes", "https://x/1046.apk", 1000)
    private val older = UpdateInfo("10.40.0", 1040, "notes", "https://x/1040.apk", 1000)

    @Test
    fun statusUpToDateWhenNoReleaseOrNotNewer() {
        assertEquals(UpdateCheckStatus.UP_TO_DATE, UpdateCheck.status(null, 1044, "10.44.0"))
        assertEquals(UpdateCheckStatus.UP_TO_DATE, UpdateCheck.status(older, 1044, "10.44.0"))
    }

    @Test
    fun statusAvailableWhenReleaseIsNewer() {
        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, UpdateCheck.status(newer, 1044, "10.44.0"))
    }

    @Test
    fun statusFailedOnNetworkError() {
        val failed: Result<UpdateInfo?> = Result.failure(java.io.IOException("no network"))
        assertEquals(UpdateCheckStatus.CHECK_FAILED, UpdateCheck.status(failed, 1044, "10.44.0"))
        // Успех без нового выпуска — «последняя версия».
        assertEquals(UpdateCheckStatus.UP_TO_DATE, UpdateCheck.status(Result.success(null), 1044, "10.44.0"))
        // Успех с новым выпуском — «доступно обновление».
        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, UpdateCheck.status(Result.success(newer), 1044, "10.44.0"))
    }
}
