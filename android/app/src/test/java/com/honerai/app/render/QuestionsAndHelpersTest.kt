package com.honerai.app.render

import com.honerai.app.data.ChatTable
import com.honerai.app.ui.markdown.InlineMath
import com.honerai.app.ui.markdown.MarkdownBlockParser
import com.honerai.app.ui.markdown.MathToken
import com.honerai.app.ui.markdown.MathTokenizer
import com.honerai.app.ui.markdown.MermaidParser
import com.honerai.app.ui.markdown.QuickQuestion
import com.honerai.app.ui.markdown.TableColumnLayout
import com.honerai.app.ui.markdown.VideoLinks
import com.honerai.app.ui.questions.QuestionMedia
import com.honerai.app.ui.questions.QuestionnaireHeader
import com.honerai.app.ui.questions.QuestionnaireReport
import com.honerai.app.ui.tables.TableCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionsAndHelpersTest {
    @Test
    fun parsesQuestionsWithQuizMarkersMediaAndCustomAnswer() {
        val body = """
            @title Проверка
            @mode quiz
            @timer 30
            ? Столица Франции?
            ![Карта](https://example.com/map.png)
            - Лондон
            -* Париж
            - Берлин
            ? Сколько будет 2+2?
            - 3
            - [x] 4
            + свой вариант
            ? Послушайте и ответьте
            @audio https://example.com/sound.mp3
            - Кошка
            - ✓ Собака
        """.trimIndent()
        val questions = MarkdownBlockParser.parseQuestions(body)
        assertEquals("Строка «+ свой вариант» не должна становиться отдельным вопросом", 3, questions.size)
        assertEquals(listOf("Лондон", "Париж", "Берлин"), questions[0].options)
        assertEquals(listOf(1), questions[0].correct)
        assertEquals(QuestionMedia.Kind.IMAGE, questions[0].media.first().kind)
        assertEquals(listOf(1), questions[1].correct)
        assertTrue(questions[1].allowsCustom)
        assertFalse(questions[0].allowsCustom)
        assertEquals(QuestionMedia.Kind.AUDIO, questions[2].media.first().kind)
        assertEquals(listOf(1), questions[2].correct)
        val header = QuestionnaireHeader.parse(body)
        assertTrue(header.quiz)
        assertEquals(30, header.timer)
        assertEquals("Проверка", header.title)
        assertEquals("По умолчанию 10 секунд на вопрос", 10, QuestionnaireHeader.parse("? Вопрос\n- да").timer)
        assertEquals(0, QuestionnaireHeader.parse("@timer 0\n? Вопрос").timer)
        assertEquals(0, QuestionnaireHeader.parse("@timer off").timer)
        assertEquals(3, QuestionnaireHeader.parse("@timer 1").timer)
        assertEquals(600, QuestionnaireHeader.parse("@таймер 9999").timer)
        assertTrue(QuestionnaireHeader.parse("@тест").quiz)
        assertFalse(QuestionnaireHeader.parse("@timer 5").quiz)
    }

    @Test
    fun questionTextContinuesAndPlusStartsCustomQuestion() {
        val questions = MarkdownBlockParser.parseQuestions("? Длинный\nвопрос в две строки\n- да\n+ Опишите своими словами")
        assertEquals(1, questions.size)
        assertEquals("Длинный вопрос в две строки", questions[0].text)
        assertTrue(questions[0].allowsCustom)
        val open = MarkdownBlockParser.parseQuestions("+ Что вы думаете?")
        assertEquals("Что вы думаете?", open.single().text)
        assertTrue(open.single().allowsCustom)
        assertTrue(open.single().options.isEmpty())
    }

    @Test
    fun boldOptionIsNotACorrectMarker() {
        val questions = MarkdownBlockParser.parseQuestions("? Что выбрать?\n- **жирный** вариант\n- обычный")
        assertEquals(emptyList<Int>(), questions.first().correct)
        assertEquals("**жирный** вариант", questions.first().options.first())
        assertEquals("вариант" to false, MarkdownBlockParser.parseOption(" [ ] вариант"))
        assertEquals("да" to true, MarkdownBlockParser.parseOption(" [х] да"))
        assertEquals("да" to true, MarkdownBlockParser.parseOption("✅ да"))
    }

    @Test
    fun thirtyQuestionsLimit() {
        val body = (1..40).joinToString("\n") { "? Вопрос $it\n- да\n- нет" }
        assertEquals(30, MarkdownBlockParser.parseQuestions(body).size)
    }

    @Test
    fun questionMediaParsing() {
        assertEquals(QuestionMedia(QuestionMedia.Kind.VIDEO, "https://youtu.be/dQw4w9WgXcQ"), QuestionMedia.parse("@video https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(QuestionMedia(QuestionMedia.Kind.FILE, "отчёт.pdf"), QuestionMedia.parse("@файл отчёт.pdf"))
        assertNull(QuestionMedia.parse("@timer 10"))
        assertNull(QuestionMedia.parse("@image"))
        assertEquals(QuestionMedia.Kind.VIDEO, QuestionMedia.markdownImage("![](https://www.youtube.com/watch?v=dQw4w9WgXcQ)")?.kind)
        assertEquals(QuestionMedia.Kind.AUDIO, QuestionMedia.markdownImage("![звук](https://a.b/c.mp3)")?.kind)
        assertNull(QuestionMedia(QuestionMedia.Kind.FILE, "отчёт.pdf").url)
    }

    @Test
    fun quizReportScoresAndExplainsTimeouts() {
        val questions = MarkdownBlockParser.parseQuestions("? A?\n-* да\n- нет\n? B?\n- да\n-* нет\n? C?\n-* 1\n- 2")
        val answers = listOf("да", "да", "")
        assertEquals(1, QuestionnaireReport.score(answers, questions))
        val message = QuestionnaireReport.message(answers, questions, quiz = true, title = "Тест", english = false)
        assertTrue(message, message.startsWith("Результаты теста «Тест»: 1 из 3."))
        assertTrue(message, message.contains("1. A? — мой ответ: да ✓"))
        assertTrue(message, message.contains("правильно: нет"))
        assertTrue(message, message.contains("3. C? — нет ответа (время вышло) ✗ (правильно: 1)"))

        val english = QuestionnaireReport.message(answers, questions, quiz = true, title = "Quiz", english = true)
        assertTrue(english, english.startsWith("Test results \"Quiz\": 1 of 3."))
        assertTrue(english, english.contains("no answer (time ran out)"))

        // Тест без отмеченных правильных ответов: оценку ставит нейросеть.
        val unmarked = MarkdownBlockParser.parseQuestions("? A?\n- да\n- нет")
        assertFalse(QuestionnaireReport.isGradable(unmarked))
        val review = QuestionnaireReport.message(listOf("да"), unmarked, quiz = true, title = "", english = false)
        assertTrue(review, review.startsWith("Проверь мои ответы на тест и оцени каждый:"))

        // Ни одного ответа: единственное и множественное число.
        val silent = QuestionnaireReport.message(listOf(""), listOf(questions[0]), quiz = false, title = "", english = false)
        assertEquals("Я не ответил на вопрос за отведённое время. Реши сам, как лучше поступить, и продолжай.", silent)
        val silentMany = QuestionnaireReport.message(listOf("", null), questions.take(2), quiz = false, title = "", english = false)
        assertEquals("Я не ответил на вопросы за отведённое время. Реши сам, как лучше поступить, и продолжай.", silentMany)
        assertEquals(
            "I didn't answer the question in time. Decide yourself how best to proceed and continue.",
            QuestionnaireReport.message(listOf(""), listOf(questions[0]), quiz = false, title = "", english = true),
        )

        // Одиночный уточняющий вопрос уходит как есть.
        val single = QuestionnaireReport.message(listOf("учёба"), listOf(QuickQuestion("Цель?", listOf("учёба"))), quiz = false, title = "", english = false)
        assertEquals("учёба", single)

        // Обычные вопросы: список и «реши сам», где ответа нет.
        val plain = MarkdownBlockParser.parseQuestions("? Цвет?\n- синий\n? Размер?\n- M")
        val answered = QuestionnaireReport.message(listOf("синий", ""), plain, quiz = false, title = "", english = false)
        assertEquals("Мои ответы:\n1. Цвет? — мой ответ: синий\n2. Размер? — нет ответа (время вышло)\nГде ответа нет — реши сам.", answered)
        assertTrue(QuestionnaireReport.isCorrect(" ДА ", questions[0]))
        assertEquals("да", QuestionnaireReport.correctText(questions[0]))
    }

    @Test
    fun mathTokenizerBasics() {
        assertEquals(listOf(MathToken.Frac("a", "b")), MathTokenizer.tokenize("\\frac{a}{b}"))
        assertEquals(listOf(MathToken.Text("x"), MathToken.Sup("2")), MathTokenizer.tokenize("x^2"))
        assertEquals(listOf(MathToken.Text("x"), MathToken.Sub("n+1")), MathTokenizer.tokenize("x_{n+1}"))
        assertEquals(listOf(MathToken.Sqrt("x")), MathTokenizer.tokenize("\\sqrt{x}"))
        assertEquals(
            listOf(MathToken.Text("∑"), MathToken.Sub("i=1"), MathToken.Sup("n"), MathToken.Text(" i")),
            MathTokenizer.tokenize("\\sum_{i=1}^{n} i"),
        )
        assertEquals(listOf(MathToken.Text("α"), MathToken.Text(" + "), MathToken.Text("β")), MathTokenizer.tokenize("\\alpha + \\beta"))
        assertEquals(listOf(MathToken.Text("∫"), MathToken.Sub("0"), MathToken.Sup("1")), MathTokenizer.tokenize("\\int_0^1"))
        assertEquals(listOf(MathToken.Frac("a²", "2")), MathTokenizer.tokenize("\\frac{a^2}{2}"))
        assertEquals(listOf(MathToken.Text("")), MathTokenizer.tokenize(""))
    }

    @Test
    fun inlineMathToUnicode() {
        assertEquals("x²", InlineMath.unicode("x^2"))
        assertEquals("H₂O", InlineMath.unicode("H_2O"))
        assertEquals("α + β", InlineMath.unicode("\\alpha + \\beta"))
        assertEquals("(a)/(b)", InlineMath.unicode("\\frac{a}{b}"))
        assertEquals("∫ f", InlineMath.unicode("\\int f"))
        assertEquals("x ∈ A", InlineMath.unicode("x \\in A"))
        assertEquals("E = mc²", InlineMath.unicode("E = mc^2"))
    }

    @Test
    fun csvExportAndTableHelpers() {
        val table = ChatTable(
            title = "Покупки",
            columns = listOf("Товар", "Цена"),
            rows = listOf(listOf("Хлеб, белый", "45"), listOf("Сок \"Яблоко\"", "120", "лишнее"), listOf("Вода")),
        )
        assertEquals("Товар,Цена\n\"Хлеб, белый\",45\n\"Сок \"\"Яблоко\"\"\",120\nВода,", TableCsv.csv(table))
        assertTrue(TableCsv.csvWithBom(table).startsWith("﻿Товар,Цена"))
        assertEquals(
            "| № | Товар | Цена |\n|---|---|---|\n| 1 | Хлеб, белый | 45 |\n| 2 | Сок \"Яблоко\" | 120 |\n| 3 | Вода |  |",
            TableCsv.markdown(table),
        )
        assertEquals(listOf("a", ""), TableCsv.normalized(listOf("a"), 2))
        assertEquals(listOf("a"), TableCsv.normalized(listOf("a", "b"), 1))

        val sorted = TableCsv.sorted(table, 1, descending = true)
        assertEquals(listOf("120", "45", ""), sorted.rows.map { it.getOrElse(1) { "" } })
        val byName = TableCsv.sorted(table.copy(rows = listOf(listOf("пункт 10"), listOf("пункт 2"), listOf("Пункт 1"))), 0, false)
        assertEquals(listOf("Пункт 1", "пункт 2", "пункт 10"), byName.rows.map { it[0] })

        assertEquals(1200.0, TableCsv.numericValue("1 200 ₽")!!, 0.0)
        assertEquals(12.5, TableCsv.numericValue("12,5%")!!, 0.0)
        assertNull(TableCsv.numericValue("abc"))
        assertEquals("Отчёт-2024.csv", TableCsv.fileName("Отчёт/2024", "csv"))
    }

    @Test
    fun markdownTableHelpers() {
        val shares = TableColumnLayout.shares(listOf("Год", "Очень длинное название модели телефона"), listOf(listOf("1983", "Motorola DynaTAC 8000X")), 2)
        assertEquals(1.0, shares.sum(), 1e-9)
        assertTrue(shares[1] > shares[0])
        assertTrue(shares.all { it >= TableColumnLayout.MINIMUM_SHARE - 1e-9 && it <= TableColumnLayout.MAXIMUM_SHARE + 1e-9 })
        val rows = listOf(listOf("Клин", "1 000"), listOf("Москва", "900"), listOf("Тула", "1 200"))
        assertEquals(listOf("900", "1 000", "1 200"), TableColumnLayout.visibleRows(rows, "", 1, true).map { it[1] })
        assertEquals(listOf("Москва"), TableColumnLayout.visibleRows(rows, "моск", null, true).map { it[0] })
        assertEquals(listOf(1), TableColumnLayout.numericColumns(rows, 2))
        assertEquals("| A | B |\n|:---|---:|\n| 1 | x\\|y |", TableColumnLayout.asMarkdown(listOf("A", "B"), listOf(com.honerai.app.ui.markdown.TableAlignment.LEADING, com.honerai.app.ui.markdown.TableAlignment.TRAILING), listOf(listOf("1", "x|y"))))
        assertEquals("A\tB\n1\t2", TableColumnLayout.asTsv(listOf("A", "B"), listOf(listOf("1", "2"))))
        assertEquals("3100", TableColumnLayout.formatted(3100.0))
        assertEquals("1.50", TableColumnLayout.formatted(1.5))
    }

    @Test
    fun videoLinks() {
        assertEquals("dQw4w9WgXcQ", VideoLinks.youTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", VideoLinks.youTubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoLinks.youTubeId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(VideoLinks.youTubeId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(VideoLinks.isVideo("https://rutube.ru/video/abc/"))
        assertTrue(VideoLinks.isVideo("https://cdn.site/clip.MP4"))
        assertFalse(VideoLinks.isVideo("https://a.b/photo.jpg"))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", VideoLinks.thumbnail("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun mermaidParsing() {
        val chain = MermaidParser.parse("graph LR\nA[Начало] --> B(Шаг) --> C{Конец}")
        assertFalse(chain.vertical)
        assertTrue(chain.isChain)
        assertEquals(listOf("Начало", "Шаг"), chain.edges.map { it.from })
        assertEquals("Конец", chain.edges.last().to)
        val graph = MermaidParser.parse("graph TD\nA[Старт] -->|да| B[Готово]\nA -- нет --> C[Стоп]\n")
        assertTrue(graph.vertical)
        assertFalse(graph.isChain)
        assertEquals(listOf("да", "нет"), graph.edges.map { it.label })
        assertEquals(listOf("Готово", "Стоп"), graph.edges.map { it.to })
        assertEquals(listOf("Старт", "Старт"), graph.edges.map { it.from })
        assertTrue(MermaidParser.parse("sequenceDiagram\nAlice: hi").edges.isEmpty())
    }
}
