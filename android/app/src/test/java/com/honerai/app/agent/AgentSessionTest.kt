package com.honerai.app.agent

import com.honerai.app.core.agent.ActionOutcome
import com.honerai.app.core.agent.AgentBrain
import com.honerai.app.core.agent.AgentSession
import com.honerai.app.core.agent.ScreenController
import com.honerai.app.core.agent.ScreenNode
import com.honerai.app.core.agent.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Экран-заглушка: отдаёт заданный снимок и записывает выполненные действия. */
class FakeScreen(var snapshot: ScreenSnapshot?) : ScreenController {
    val taps = mutableListOf<Int>()
    val typed = mutableListOf<Pair<Int, String>>()
    var scrolls = 0
    var opened: String? = null

    override suspend fun snapshot(): ScreenSnapshot? = snapshot
    override suspend fun tap(index: Int): ActionOutcome { taps.add(index); return ActionOutcome.Success }
    override suspend fun tapByText(text: String): ActionOutcome { return ActionOutcome.Success }
    override suspend fun setText(index: Int, text: String): ActionOutcome {
        val node = snapshot?.node(index)
        if (node?.isPassword == true) return ActionOutcome.PasswordRefused
        typed.add(index to text); return ActionOutcome.Success
    }
    override suspend fun scroll(direction: String): ActionOutcome { scrolls++; return ActionOutcome.Success }
    override suspend fun back(): ActionOutcome = ActionOutcome.Success
    override suspend fun home(): ActionOutcome = ActionOutcome.Success
    override suspend fun openApp(packageOrName: String): ActionOutcome { opened = packageOrName; return ActionOutcome.Success }
}

/** «Мозг» по сценарию: выдаёт заранее заданные JSON-ответы по одному. */
class ScriptedBrain(private val script: List<String>) : AgentBrain {
    var calls = 0
    override suspend fun nextAction(task: String, snapshot: ScreenSnapshot?, history: List<String>, recipeHint: String?): String {
        val response = script.getOrElse(calls) { """{"action":"done","summary":"нет сценария"}""" }
        calls++
        return response
    }
}

class AgentSessionTest {
    private fun node(index: Int, text: String, editable: Boolean = false, password: Boolean = false, clickable: Boolean = true) =
        ScreenNode(index, text, "", "View", clickable, editable, false, false, password, "0,0,100,40")

    private fun screenWith(vararg nodes: ScreenNode) =
        FakeScreen(ScreenSnapshot("com.wildberries.ru", "Wildberries", nodes.toList()))

    @Test fun happyPathPerformsActionsUntilDone() = runBlocking {
        val screen = screenWith(node(0, "Поиск", clickable = true), node(1, "поле", editable = true))
        val brain = ScriptedBrain(listOf(
            """{"action":"tap","index":0}""",
            """{"action":"type","index":1,"text":"наушники"}""",
            """{"action":"done","summary":"Найдено и добавлено"}""",
        ))
        val outcome = AgentSession(screen, brain).run("найди наушники")
        assertTrue(outcome is AgentSession.Outcome.Success)
        assertEquals(listOf(0), screen.taps)
        assertEquals(listOf(1 to "наушники"), screen.typed)
    }

    @Test fun opensAppFirstWhenProvided() = runBlocking {
        val screen = screenWith(node(0, "экран"))
        val brain = ScriptedBrain(listOf("""{"action":"done","summary":"ок"}"""))
        AgentSession(screen, brain).run("задача", app = "wildberries")
        assertEquals("wildberries", screen.opened)
    }

    @Test fun neverTypesIntoPasswordField() = runBlocking {
        val screen = screenWith(node(0, "Пароль", editable = true, password = true))
        val brain = ScriptedBrain(listOf("""{"action":"type","index":0,"text":"12345"}"""))
        val outcome = AgentSession(screen, brain).run("войти")
        assertTrue(outcome is AgentSession.Outcome.NeedsUser)
        assertTrue(screen.typed.isEmpty()) // ввод в поле пароля не выполнялся
    }

    @Test fun sensitiveButtonRequiresConfirmationAndStopsWhenDeclined() = runBlocking {
        val screen = screenWith(node(0, "Оплатить 1 990 ₽"))
        val brain = ScriptedBrain(listOf("""{"action":"tap","index":0}"""))
        var askedDescription: String? = null
        var askedAmount: String? = null
        val outcome = AgentSession(screen, brain, onConfirm = { d, a -> askedDescription = d; askedAmount = a; false }).run("оплати заказ")
        assertTrue(outcome is AgentSession.Outcome.Cancelled)
        assertTrue(askedDescription!!.contains("Оплатить"))
        assertEquals("1 990 ₽", askedAmount)
        assertTrue(screen.taps.isEmpty()) // без подтверждения не нажимаем
    }

    @Test fun sensitiveButtonProceedsWhenConfirmed() = runBlocking {
        val screen = screenWith(node(0, "Оформить заказ"))
        val brain = ScriptedBrain(listOf(
            """{"action":"tap","index":0}""",
            """{"action":"done","summary":"Заказ оформлен"}""",
        ))
        val outcome = AgentSession(screen, brain, onConfirm = { _, _ -> true }).run("оформи заказ")
        assertTrue(outcome is AgentSession.Outcome.Success)
        assertEquals(listOf(0), screen.taps)
    }

    @Test fun confirmActionRoutedThroughCallback() = runBlocking {
        val screen = screenWith(node(0, "Кнопка"))
        val brain = ScriptedBrain(listOf(
            """{"action":"confirm","description":"Отправить письмо","amount":null}""",
            """{"action":"done","summary":"Отправлено"}""",
        ))
        var confirmed = false
        val outcome = AgentSession(screen, brain, onConfirm = { _, _ -> confirmed = true; true }).run("отправь письмо")
        assertTrue(confirmed)
        assertTrue(outcome is AgentSession.Outcome.Success)
    }

    @Test fun captchaStopsAndAsksUser() = runBlocking {
        val screen = screenWith(node(0, "Я не робот"), node(1, "reCAPTCHA"))
        val brain = ScriptedBrain(listOf("""{"action":"tap","index":0}"""))
        val outcome = AgentSession(screen, brain).run("продолжи")
        assertTrue(outcome is AgentSession.Outcome.NeedsUser)
        assertTrue((outcome as AgentSession.Outcome.NeedsUser).reason.contains("капча"))
        assertTrue(screen.taps.isEmpty())
    }

    @Test fun maxStepsCapReached() = runBlocking {
        val screen = screenWith(node(0, "Дальше"))
        // Модель бесконечно прокручивает — цикл обязан остановиться по лимиту.
        val brain = ScriptedBrain(List(100) { """{"action":"scroll","direction":"down"}""" })
        val outcome = AgentSession(screen, brain, maxSteps = 5).run("бесконечно")
        assertTrue(outcome is AgentSession.Outcome.MaxSteps)
        assertEquals(5, screen.scrolls)
    }

    @Test fun abortStopsTheLoop() = runBlocking {
        val screen = screenWith(node(0, "Дальше"))
        val brain = ScriptedBrain(List(100) { """{"action":"scroll","direction":"down"}""" })
        val session = AgentSession(screen, brain, maxSteps = 25)
        session.abort()
        val outcome = session.run("что-то")
        assertTrue(outcome is AgentSession.Outcome.Cancelled)
        assertEquals(0, screen.scrolls)
    }

    @Test fun askUserWhenScreenUnavailable() = runBlocking {
        val screen = FakeScreen(null)
        val brain = ScriptedBrain(listOf("""{"action":"tap","index":0}"""))
        val outcome = AgentSession(screen, brain).run("сделай")
        assertTrue(outcome is AgentSession.Outcome.NeedsUser)
    }

    @Test fun unparseableModelReplyAsksUser() = runBlocking {
        val screen = screenWith(node(0, "Кнопка"))
        val brain = ScriptedBrain(listOf("совсем не json"))
        val outcome = AgentSession(screen, brain).run("сделай")
        assertTrue(outcome is AgentSession.Outcome.NeedsUser)
    }

    @Test fun progressReportsSteps() = runBlocking {
        val screen = screenWith(node(0, "Поиск"))
        val brain = ScriptedBrain(listOf("""{"action":"tap","index":0}""", """{"action":"done","summary":"ок"}"""))
        val titles = mutableListOf<String>()
        AgentSession(screen, brain, onProgress = { title, _ -> titles.add(title) }).run("сделай")
        assertFalse(titles.isEmpty())
        assertTrue(titles.any { it.contains("Нажимаю") })
    }
}
