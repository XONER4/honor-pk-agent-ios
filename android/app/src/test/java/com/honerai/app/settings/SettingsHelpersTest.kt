package com.honerai.app.settings

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.honerai.app.device.ContentGuard
import com.honerai.app.ui.help.ease
import com.honerai.app.ui.help.helpRichText
import com.honerai.app.ui.help.ramp
import com.honerai.app.ui.help.typedPrefix
import com.honerai.app.ui.settings.formattedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Вспомогательные функции экранов: время, разметка статей, кадры мини-роликов, разбор адресов. */
class SettingsHelpersTest {
    @Test
    fun formattedTimeMatchesIos() {
        assertEquals("40 сек", formattedTime(40.0, english = false))
        assertEquals("12 мин", formattedTime(12 * 60 + 5.0, english = false))
        assertEquals("2 ч 5 мин", formattedTime(2 * 3600 + 5 * 60.0, english = false))
        assertEquals("2 h 5 min", formattedTime(2 * 3600 + 5 * 60.0, english = true))
    }

    @Test
    fun richTextParsesBoldAndItalic() {
        val text = helpRichText("Нажмите **«Поиск»** и *подождите*.")
        assertEquals("Нажмите «Поиск» и подождите.", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("«Поиск»", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("подождите", text.text.substring(italic.start, italic.end))
        // Одиночная звёздочка и «2 * 3» остаются как есть.
        assertEquals("2 * 3 = 6", helpRichText("2 * 3 = 6").text)
    }

    @Test
    fun demoTimingHelpers() {
        assertEquals(0.0, ramp(0.5, 1.0, 2.0), 0.0)
        assertEquals(0.5, ramp(1.5, 1.0, 2.0), 1e-9)
        assertEquals(1.0, ramp(3.0, 1.0, 2.0), 0.0)
        assertEquals(0.5, ease(0.5), 1e-9)
        assertEquals("", typedPrefix("abcd", 0.0))
        assertEquals("ab", typedPrefix("abcd", 0.5))
        assertEquals("abcd", typedPrefix("abcd", 2.0))
    }

    @Test
    fun urlParsing() {
        assertEquals("https" to "www.tiktok.com", ContentGuard.parseUrl("https://user:pw@www.tiktok.com:443/@x?y#z"))
        assertEquals("mailto" to null, ContentGuard.parseUrl("mailto:a@b.c"))
        assertEquals("https" to "wikipedia.org", ContentGuard.parseUrl("wikipedia.org/wiki/Кот"))
        assertNull(ContentGuard.parseUrl("https://").second)
        assertTrue(ContentGuard.isUrlAllowed("tel:112", com.honerai.app.device.ParentalRules(enabled = true)))
    }
}
