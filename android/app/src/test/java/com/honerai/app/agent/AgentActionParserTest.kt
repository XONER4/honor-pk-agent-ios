package com.honerai.app.agent

import com.honerai.app.core.agent.AgentAction
import com.honerai.app.core.agent.AgentActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionParserTest {
    @Test fun parsesTapByIndex() {
        val a = AgentActionParser.parse("""{"action":"tap","index":3}""") as AgentAction.Tap
        assertEquals(3, a.index)
    }

    @Test fun parsesTapByText() {
        val a = AgentActionParser.parse("""{"action":"tap","text":"В корзину"}""") as AgentAction.Tap
        assertEquals("В корзину", a.text)
    }

    @Test fun parsesType() {
        val a = AgentActionParser.parse("""{"action":"type","index":2,"text":"наушники"}""") as AgentAction.TypeText
        assertEquals(2, a.index)
        assertEquals("наушники", a.text)
    }

    @Test fun parsesScrollBackHomeOpen() {
        assertEquals("down", (AgentActionParser.parse("""{"action":"scroll","direction":"down"}""") as AgentAction.Scroll).direction)
        assertTrue(AgentActionParser.parse("""{"action":"back"}""") is AgentAction.Back)
        assertTrue(AgentActionParser.parse("""{"action":"home"}""") is AgentAction.Home)
        assertEquals("wildberries", (AgentActionParser.parse("""{"action":"open","app":"wildberries"}""") as AgentAction.Open).app)
    }

    @Test fun parsesConfirmWithAmount() {
        val a = AgentActionParser.parse("""{"action":"confirm","description":"Оплатить заказ","amount":"1 990 ₽"}""") as AgentAction.Confirm
        assertEquals("Оплатить заказ", a.description)
        assertEquals("1 990 ₽", a.amount)
    }

    @Test fun parsesAskUserAndDone() {
        assertTrue(AgentActionParser.parse("""{"action":"ask_user","message":"Введите пароль сами"}""") is AgentAction.AskUser)
        val done = AgentActionParser.parse("""{"action":"done","summary":"Готово"}""") as AgentAction.Done
        assertEquals("Готово", done.summary)
    }

    @Test fun toleratesTextWrappingAndCodeFences() {
        val wrapped = "Хорошо, следующий шаг:\n```json\n{\"action\":\"tap\",\"index\":5}\n```\nвот так"
        val a = AgentActionParser.parse(wrapped) as AgentAction.Tap
        assertEquals(5, a.index)
    }

    @Test fun toleratesLeadingProseBeforeJson() {
        val a = AgentActionParser.parse("Нажимаю кнопку. {\"action\":\"tap\",\"text\":\"Купить\"}") as AgentAction.Tap
        assertEquals("Купить", a.text)
    }

    @Test fun unparseableForGarbage() {
        assertTrue(AgentActionParser.parse("не понял задачу") is AgentAction.Unparseable)
        assertTrue(AgentActionParser.parse("{}") is AgentAction.Unparseable)
    }

    @Test fun acceptsAlternateFieldNames() {
        // Модель иногда пишет type/name вместо action и value вместо text.
        val a = AgentActionParser.parse("""{"type":"input","i":1,"value":"текст"}""") as AgentAction.TypeText
        assertEquals(1, a.index)
        assertEquals("текст", a.text)
    }
}
