package com.honerai.app.render

import com.honerai.app.ui.markdown.BlockKind
import com.honerai.app.ui.markdown.ChecklistItem
import com.honerai.app.ui.markdown.LiveMarkdown
import com.honerai.app.ui.markdown.MarkdownBlockParser
import com.honerai.app.ui.markdown.MarkdownParseMemo
import com.honerai.app.ui.markdown.ParagraphParts
import com.honerai.app.ui.markdown.TableAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun kinds(text: String, streaming: Boolean = false) = MarkdownBlockParser.parse(text, streaming).map { it.kind }

    @Test
    fun parsesAllBlockKinds() {
        val text = """
            # Заголовок
            Обычный **абзац** текста.
            вторая строка абзаца

            Setext
            ======

            - пункт один
            - пункт два

            1. первый
            2) второй

            - [ ] сделать
            - [x] готово

            > цитата
            > вторая строка

            ```kotlin
            val a = 1

            println(a)
            ```

            ```copy
            скопируй меня
            ```

            ```card:warn
            Осторожно
            ```

            ```mermaid
            graph TD
            A --> B
            ```

            ```ask
            ? Как дела?
            - хорошо
            - плохо
            ```

            $$
            \frac{a}{b}
            $$

            $${'$'}x^2$${'$'}

            | A | B |
            |:-:|--:|
            | 1 | 2 |

            ---

            Конец
        """.trimIndent()
        val blocks = MarkdownBlockParser.parse(text)
        val k = blocks.map { it.kind }
        assertEquals(BlockKind.Heading(1), k[0])
        assertEquals("Заголовок", blocks[0].text)
        assertEquals(BlockKind.Paragraph, k[1])
        assertEquals("Обычный **абзац** текста.\nвторая строка абзаца", blocks[1].text)
        assertEquals(BlockKind.Heading(1), k[2])
        assertEquals("Setext", blocks[2].text)
        assertEquals(BlockKind.Bullets(listOf("пункт один", "пункт два"), ordered = false), k[3])
        assertEquals(BlockKind.Bullets(listOf("первый", "второй"), ordered = true), k[4])
        assertEquals(BlockKind.Checklist(listOf(ChecklistItem(false, "сделать"), ChecklistItem(true, "готово"))), k[5])
        assertEquals(BlockKind.Quote, k[6])
        assertEquals("цитата\nвторая строка", blocks[6].text)
        assertEquals(BlockKind.Code("kotlin"), k[7])
        assertEquals("val a = 1\n\nprintln(a)", blocks[7].text)
        assertEquals(BlockKind.CopyBlock, k[8])
        assertEquals(BlockKind.Card("warn", ""), k[9])
        assertEquals(BlockKind.Diagram("graph TD\nA --> B"), k[10])
        val ask = k[11] as BlockKind.Ask
        assertFalse(ask.open)
        assertEquals(listOf("хорошо", "плохо"), ask.questions.single().options)
        assertEquals(BlockKind.MathBlock("\\frac{a}{b}"), k[12])
        assertEquals(BlockKind.MathBlock("x^2"), k[13])
        val table = k[14] as BlockKind.Table
        assertEquals(listOf("A", "B"), table.headers)
        assertEquals(listOf(TableAlignment.CENTER, TableAlignment.TRAILING), table.alignments)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        assertEquals(BlockKind.Divider, k[15])
        assertEquals(BlockKind.Paragraph, k[16])
        assertEquals(blocks.indices.toList(), blocks.map { it.id })
    }

    @Test
    fun unclosedAskBlockIsShownOpen() {
        val blocks = MarkdownBlockParser.parse("Вопрос:\n```ask\n? Да или нет?\n- да\n- не", streaming = true)
        val ask = blocks.last().kind as BlockKind.Ask
        assertTrue(ask.open)
        assertEquals(listOf("да", "не"), ask.questions.single().options)
    }

    @Test
    fun setextH2AndDividerUnderList() {
        assertEquals(BlockKind.Heading(2), kinds("Раздел\n---")[0])
        // «---» под списком — разделитель, а не сырой текст.
        assertEquals(listOf(BlockKind.Bullets(listOf("a"), false), BlockKind.Divider), kinds("- a\n---"))
    }

    @Test
    fun tablesLikeIos() {
        val padded = MarkdownBlockParser.parse("| Город | Температура |\n|:---|---:|\n| Клин | 14 |\n| Москва | 12 |")
        val t1 = padded.first().kind as BlockKind.Table
        assertEquals(listOf("Город", "Температура"), t1.headers)
        assertEquals(listOf(TableAlignment.LEADING, TableAlignment.TRAILING), t1.alignments)
        assertEquals(listOf(listOf("Клин", "14"), listOf("Москва", "12")), t1.rows)

        val bare = MarkdownBlockParser.parse("Город | Температура\n---|---\nКлин | 14").first().kind as BlockKind.Table
        assertEquals(listOf("Город", "Температура"), bare.headers)
        assertEquals(listOf(listOf("Клин", "14")), bare.rows)

        val plus = MarkdownBlockParser.parse("Город + Температура\n---+---\nКлин + 14").first().kind as BlockKind.Table
        assertEquals(listOf("Город", "Температура"), plus.headers)
        assertEquals(listOf(listOf("Клин", "14")), plus.rows)

        assertFalse(kinds("| |\n|---|---|").any { it is BlockKind.Table })
        val headerOnly = MarkdownBlockParser.parse("| Модель | Год |\n|---|---|")
        assertFalse(headerOnly.any { it.kind is BlockKind.Table })
        assertFalse(headerOnly.joinToString(" ") { it.text }.contains("|---"))

        val single = MarkdownBlockParser.parse("| Модель |\n|---|\n| Motorola DynaTAC 8000X |\n| IBM Simon |").first().kind as BlockKind.Table
        assertEquals(listOf("Модель"), single.headers)
        assertEquals(2, single.rows.size)

        assertFalse(kinds("Текст\n\n---\n\nЕщё текст").any { it is BlockKind.Table })

        // Незаконченная таблица (не в режиме печати) показывается текстом, но не пропадает.
        val growing = MarkdownBlockParser.parse("| Модель | Год |\n|---|---|\n| Motorola | 1983 |\n| IBM Simon |")
        assertFalse(growing.any { it.kind is BlockKind.Table })
        val growingText = growing.joinToString("\n") { it.text }
        assertTrue(growingText.contains("Motorola") && growingText.contains("IBM Simon"))
        val cut = MarkdownBlockParser.parse("| Модель | Год |\n|---|---|\n| Motorola | 1983 |\n| IBM Simo")
        assertFalse(cut.any { it.kind is BlockKind.Table })
        assertTrue(cut.joinToString("\n") { it.text }.contains("IBM Simo"))

        val finished = MarkdownBlockParser.parse("| Модель | Год |\n|---|---|\n| Motorola | 1983 |\n| IBM Simon | 1992 |")
        assertTrue(finished.any { it.kind is BlockKind.Table })

        // Экранированная палочка остаётся внутри ячейки.
        assertEquals(listOf("a | b", "5"), MarkdownBlockParser.splitRow("| a \\| b | 5 |"))
    }

    @Test
    fun streamingTableGrowsRowByRow() {
        val growing = MarkdownBlockParser.parse(
            "Вот таблица:\n| Модель | Год |\n|---|---|\n| Motorola | 1983 |\n| IBM Si", streaming = true,
        )
        val table = growing.last().kind as BlockKind.Table
        assertEquals(listOf("Модель", "Год"), table.headers)
        assertEquals(listOf(listOf("Motorola", "1983")), table.rows)
        assertFalse(growing.any { it.text.contains("|") })
        val complete = MarkdownBlockParser.parse("| Модель | Год |\n|---|---|\n| Motorola | 1983 |\n| IBM Simon | 1992 |", streaming = true)
        assertEquals(2, (complete.last().kind as BlockKind.Table).rows.size)
    }

    @Test
    fun liveMarkdownHidesHalfTypedMarkup() {
        assertEquals("Это важ", LiveMarkdown.displayable("Это **важ"))
        assertEquals("Это **важно**", LiveMarkdown.displayable("Это **важно**"))
        assertEquals("Смотри ", LiveMarkdown.displayable("Смотри [сайт](https://exa"))
        assertEquals("Смотри [сайт](https://example.com)", LiveMarkdown.displayable("Смотри [сайт](https://example.com)"))
        assertEquals("Текст", LiveMarkdown.displayable("Текст\n| Модель | Го"))
        assertEquals("Текст", LiveMarkdown.displayable("Текст\n##"))
        assertEquals("крас", LiveMarkdown.displayable("{color:red}крас"))
        assertEquals("```swift\nlet a = **", LiveMarkdown.displayable("```swift\nlet a = **"))
        assertEquals("Готово.\n", LiveMarkdown.displayable("Готово.\n"))
        assertEquals("Список", LiveMarkdown.displayable("Список\n-"))
        assertEquals("Код val", LiveMarkdown.displayable("Код `val"))
        assertEquals("Смотри ", LiveMarkdown.displayable("Смотри ![фото](https://a.b/c"))
        assertEquals("| A | B |\n|---|---|\n| 1 |", LiveMarkdown.displayable("| A | B |\n|---|---|\n| 1 |"))
        assertEquals(2, LiveMarkdown.fenceLineCount("```a\nb\n  ```\nc ``` d"))
    }

    @Test
    fun paragraphImagesAreSplit() {
        assertEquals("https://a.b/c.jpg", ParagraphParts.trimmedUrl("https://a.b/c.jpg)"))
        assertEquals("https://a.b/Photo_(1).jpg", ParagraphParts.trimmedUrl("https://a.b/Photo_(1).jpg)"))
        assertEquals("https://a.b/Photo_(1).jpg", ParagraphParts.trimmedUrl("https://a.b/Photo_(1).jpg"))
        assertEquals("https://a.b/c.jpg?v=2&w=800", ParagraphParts.trimmedUrl("https://a.b/c.jpg?v=2&w=800)"))
        val parts = ParagraphParts.split("Смотри: ![Сочи](https://a.b/Photo_(1).jpg) Готово.")
        assertEquals(3, parts.size)
        assertEquals("Смотри:", parts[0].text)
        assertEquals("https://a.b/Photo_(1).jpg", parts[1].imageUrl)
        assertEquals("Сочи", parts[1].caption)
        assertEquals("Готово.", parts[2].text)
        val blocks = MarkdownBlockParser.parse("Смотри:\n\n![Сочи](https://a.b/Photo_(1).jpg)\n\nГотово.")
        val text = blocks.joinToString(" ") { it.text }
        assertTrue(text.contains("Смотри") && text.contains("Готово"))
    }

    /** Большой ответ из всех видов блоков. */
    private fun bigAnswer(blocks: Int): String {
        val pieces = ArrayList<String>()
        for (index in 0 until blocks) {
            pieces.add(
                when (index % 10) {
                    0 -> "## Раздел $index"
                    1 -> "Абзац **$index** с [ссылкой](https://example.com/$index), `кодом` и ==маркером==.\nВторая строка абзаца."
                    2 -> "- пункт $index\n- ещё пункт\n- третий"
                    3 -> "```swift\nlet value$index = $index\n\nprint(value$index)\n```"
                    4 -> "| A | B |\n|---|---|\n| $index | ${index * 2} |\n| x | y |"
                    5 -> "> цитата $index\n> продолжение"
                    6 -> "$$\n\\frac{$index}{2}\n\n+ 1\n$$"
                    7 -> "```ask\n? Вопрос $index?\n- да\n-* нет\n```"
                    8 -> "1. один\n2. два\n\n- [x] готово\n- [ ] нет"
                    else -> "Текст $index\n---\n\n***"
                }
            )
        }
        return pieces.joinToString("\n\n")
    }

    @Test
    fun incrementalParseEqualsFullParseAndIsFast() {
        val full = bigAnswer(400)
        val memo = MarkdownParseMemo()
        var cursor = 0
        var frames = 0
        var checked = 0
        val started = System.nanoTime()
        var checkNanos = 0L
        while (cursor < full.length) {
            cursor = minOf(full.length, cursor + 97)
            val shown = full.substring(0, cursor)
            val incremental = memo.blocks(shown, streaming = true)
            frames++
            if (frames % 10 == 0) {
                val checkStart = System.nanoTime()
                val reference = MarkdownBlockParser.parse(shown, streaming = true)
                assertEquals("кадр $frames", reference, incremental)
                checked++
                checkNanos += System.nanoTime() - checkStart
            }
        }
        val seconds = (System.nanoTime() - started - checkNanos) / 1e9
        println("HONER_PERF incremental parse chars=${full.length} frames=$frames seconds=$seconds")
        val incremental = memo.blocks(full, streaming = true)
        val reference = MarkdownBlockParser.parse(full, streaming = true)
        assertEquals(reference.map { it.kind }, incremental.map { it.kind })
        assertEquals(reference.map { it.text }, incremental.map { it.text })
        assertEquals(reference.map { it.id }, incremental.map { it.id })
        assertTrue(checked > 10)
        assertTrue("Печать длинного ответа тормозит: $seconds с", seconds < 3.0)
        // Среднее время кадра должно быть намного меньше кадра 120 Гц (8 мс).
        assertTrue("Средний кадр ${seconds * 1000 / frames} мс", seconds * 1000 / frames < 4.0)
    }

    @Test
    fun streamingHundredThousandCharactersStaysWithinFrameBudget() {
        val full = bigAnswer(2800)
        assertTrue(full.length > 100_000)
        val memo = MarkdownParseMemo()
        var cursor = 0
        var frames = 0
        var worst = 0L
        val started = System.nanoTime()
        while (cursor < full.length) {
            cursor = minOf(full.length, cursor + 100)
            val frameStart = System.nanoTime()
            // Как в MarkdownContent: скрыть недописанную разметку и разобрать только хвост.
            memo.blocks(LiveMarkdown.displayable(full.substring(0, cursor)), streaming = true)
            worst = maxOf(worst, System.nanoTime() - frameStart)
            frames++
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("HONER_PERF stream chars=${full.length} frames=$frames seconds=$seconds avgMs=${seconds * 1000 / frames} worstMs=${worst / 1e6}")
        assertEquals(MarkdownBlockParser.parse(full, streaming = true), memo.blocks(full, streaming = true))
        assertTrue("Средний кадр ${seconds * 1000 / frames} мс", seconds * 1000 / frames < 2.0)
    }

    @Test
    fun hugeMarkdownParsesFast() {
        val text = StringBuilder()
        for (index in 0 until 400) {
            text.append("## Раздел $index\n**Жирный** текст и [ссылка](https://example.com/$index). Обычное предложение для объёма.\n- пункт\n- пункт\n\n")
            if (index % 20 == 0) text.append("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\n```swift\nlet x = $index\n```\n\n")
        }
        MarkdownBlockParser.parse(text.toString()) // прогрев JIT
        val started = System.nanoTime()
        val blocks = MarkdownBlockParser.parse(text.toString())
        val seconds = (System.nanoTime() - started) / 1e9
        println("HONER_PERF markdown chars=${text.length} blocks=${blocks.size} seconds=$seconds")
        assertTrue(blocks.size > 1000)
        assertTrue(seconds < 1.0)
    }

    @Test
    fun finishedParseIsCachedAndStable() {
        val memo = MarkdownParseMemo()
        val text = bigAnswer(30)
        val first = memo.blocks(text, streaming = false)
        val again = MarkdownParseMemo().blocks(text, streaming = false)
        // Тот же объект из кэша: Compose не перерисовывает старое сообщение.
        assertTrue(first === again)
        assertNotNull(first.firstOrNull())
    }

    @Test
    fun safeBoundaryIgnoresBlankLinesInsideCodeAndMath() {
        val text = "a\n\n```\nx\n\ny\n```\n\n$$\n1\n\n2\n$$\n\nend"
        val boundary = MarkdownParseMemo.safeBoundary(text, 0)
        assertEquals(text.indexOf("end"), boundary)
        assertEquals(null, MarkdownParseMemo.safeBoundary("```\na\n\nb", 0))
    }
}
