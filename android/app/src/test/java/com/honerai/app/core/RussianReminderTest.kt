package com.honerai.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** При русском языке приложения английский вопрос получает скрытое «ответь по-русски». */
class RussianReminderTest {
    @Test
    fun englishQuestionNeedsReminder() {
        assertTrue(RussianTextPolicy.needsRussianReminder("Latest SpaceX news this week"))
    }

    @Test
    fun russianOrMixedQuestionDoesNot() {
        assertFalse(RussianTextPolicy.needsRussianReminder("Новости SpaceX"))
        assertFalse(RussianTextPolicy.needsRussianReminder("Что такое iPhone?"))
        assertFalse(RussianTextPolicy.needsRussianReminder("2+2"))
        assertFalse(RussianTextPolicy.needsRussianReminder("👍"))
    }
}
