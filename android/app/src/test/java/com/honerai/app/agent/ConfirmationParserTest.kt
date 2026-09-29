package com.honerai.app.agent

import com.honerai.app.core.agent.ConfirmationParser
import com.honerai.app.core.agent.ConfirmationParser.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationParserTest {
    private fun v(text: String) = ConfirmationParser.parse(text)

    @Test fun recognizesYes() {
        listOf("да", "Да", "ДА", "ага", "конечно", "хорошо", "ок", "окей",
            "давай", "подтверждаю", "подтверди", "оплачивай", "оплати", "отправляй", "отправь",
            "продолжай", "го", "поехали", "yes", "ok", "confirm").forEach {
            assertEquals("«$it» должно быть YES", Verdict.YES, v(it))
        }
    }

    @Test fun recognizesNo() {
        listOf("нет", "неа", "отмена", "отмени", "стоп", "стой", "хватит",
            "не надо", "не нужно", "прекрати", "no", "cancel", "stop").forEach {
            assertEquals("«$it» должно быть NO", Verdict.NO, v(it))
        }
    }

    @Test fun yesWithPunctuationAndWords() {
        assertEquals(Verdict.YES, v("да, оплачивай!"))
        assertEquals(Verdict.YES, v("Конечно, давай."))
        assertEquals(Verdict.YES, v("ок, отправляй"))
    }

    @Test fun explicitNoWinsOverYes() {
        // Явный отказ важнее случайного «да».
        assertEquals(Verdict.NO, v("нет, не надо"))
        assertEquals(Verdict.NO, v("да нет, отмена"))
    }

    @Test fun unknownForUnrelatedText() {
        assertEquals(Verdict.UNKNOWN, v("а какая цена?"))
        assertEquals(Verdict.UNKNOWN, v("покажи ещё варианты"))
        assertEquals(Verdict.UNKNOWN, v(""))
        // «недавно» не должно читаться как «нет».
        assertEquals(Verdict.UNKNOWN, v("недавно смотрел"))
    }

    @Test fun abortDetection() {
        assertTrue(ConfirmationParser.isAbort("стоп"))
        assertTrue(ConfirmationParser.isAbort("Хватит".lowercase()))
        assertTrue(ConfirmationParser.isAbort("отмена"))
        assertFalse(ConfirmationParser.isAbort("да, давай"))
        assertFalse(ConfirmationParser.isAbort("продолжай"))
    }
}
