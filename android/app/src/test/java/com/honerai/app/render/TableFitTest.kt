package com.honerai.app.render

import com.honerai.app.ui.markdown.TableColumnLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Компактная таблица: столбцы не уже самого длинного слова, сумма ширин — ровно доступная ширина. */
class TableFitTest {
    @Test
    fun longestWordsIgnoreMarkup() {
        assertEquals(listOf("Меркурий", "Венера"), TableColumnLayout.longestWords("**Меркурий** и Венера"))
        assertEquals(emptyList<String>(), TableColumnLayout.longestWords("  "))
    }

    @Test
    fun widthsRespectMinimumsAndFillAvailable() {
        val widths = TableColumnLayout.fitWidths(listOf(0.2, 0.4, 0.4), listOf(90f, 40f, 40f), 300f)
        assertNotNull(widths)
        widths!!
        assertTrue(widths[0] >= 90f)
        assertEquals(300f, widths.sum(), 0.5f)
        assertEquals(widths[1], widths[2], 0.5f)
    }

    @Test
    fun sharesUsedWhenMinimumsAreSmall() {
        val widths = TableColumnLayout.fitWidths(listOf(0.25, 0.75), listOf(10f, 10f), 400f)!!
        assertEquals(100f, widths[0], 0.5f)
        assertEquals(300f, widths[1], 0.5f)
    }

    @Test
    fun tooNarrowFallsBackToWideTable() {
        assertNull(TableColumnLayout.fitWidths(listOf(0.5, 0.5), listOf(200f, 200f), 300f))
    }
}
