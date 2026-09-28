package com.honerai.app.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.honerai.app.data.WebSource
import com.honerai.app.ui.markdown.InlineMarkup
import com.honerai.app.ui.markdown.InlinePalette
import com.honerai.app.ui.markdown.SyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkupTest {
    private val palette = InlinePalette(Color.White, Color.Gray, Color.Blue, Color.DarkGray)

    @Test
    fun stylesAreAppliedAndMarkupRemoved() {
        val value = InlineMarkup.build("Это **жирный**, *курсив*, `код`, ~~нет~~, ==маркер== и ||тайна||.", palette)
        assertEquals("Это жирный, курсив, код, нет, маркер и тайна.", value.text)
        val bold = value.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("жирный", value.text.substring(bold.start, bold.end))
        val italic = value.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("курсив", value.text.substring(italic.start, italic.end))
        val strike = value.spanStyles.first { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals("нет", value.text.substring(strike.start, strike.end))
        val spoiler = value.getStringAnnotations(InlineMarkup.TAG_SPOILER, 0, value.length).single()
        assertEquals("тайна", value.text.substring(spoiler.start, spoiler.end))
    }

    @Test
    fun customTagsLinksCitationsAndMath() {
        val sources = listOf(WebSource(title = "Википедия", url = "https://ru.wikipedia.org/wiki/X", snippet = ""))
        val value = InlineMarkup.build(
            "{color:red}Красный{/color} {upper}громко{/upper} [сайт](https://example.com) по данным [1], формула \$x^2\$ стоит \$5 и \$10.",
            palette, sources,
        )
        assertEquals("Красный ГРОМКО сайт по данным [1], формула x² стоит \$5 и \$10.", value.text)
        val red = value.spanStyles.first { it.item.color == InlineMarkup.color("red") }
        assertEquals("Красный", value.text.substring(red.start, red.end))
        val link = value.getLinkAnnotations(0, value.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
        assertEquals("сайт", value.text.substring(link.start, link.end))
        val cite = value.getStringAnnotations(InlineMarkup.TAG_CITATION, 0, value.length).single()
        assertEquals("1\u001Fhttps://ru.wikipedia.org/wiki/X", cite.item)
    }

    @Test
    fun underscoresInsideWordsAndLiteralAsterisksStay() {
        assertEquals("snake_case_name и 2 * 3 * 4", InlineMarkup.build("snake_case_name и 2 * 3 * 4", palette).text)
        assertEquals("ссылка https://a.b/c_(d). Дальше", InlineMarkup.build("ссылка https://a.b/c_(d). Дальше", palette).text)
        assertEquals("*звёздочка", InlineMarkup.build("\\*звёздочка", palette).text)
    }

    @Test
    fun findQueryIsHighlighted() {
        val value = InlineMarkup.build("Кот и кот", palette, findQuery = "кот")
        assertEquals(2, value.spanStyles.count { it.item.background == InlineMarkup.findColor })
    }

    @Test
    fun highlighterKeepsTextExactly() {
        val code = "fun main() {\n    val x = \"hi\" // привет\n}"
        assertEquals(code, SyntaxHighlighter.highlight(code, "kotlin", SyntaxHighlighter.dark).text)
        assertEquals("print(1)", SyntaxHighlighter.highlight("print(1)", "unknown", SyntaxHighlighter.light).text)
    }
}
