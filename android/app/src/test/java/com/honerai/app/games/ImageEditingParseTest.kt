package com.honerai.app.games

import com.honerai.app.media.ColorMatrices
import com.honerai.app.media.CropAspect
import com.honerai.app.media.EditBackground
import com.honerai.app.media.EditColors
import com.honerai.app.media.EditFilter
import com.honerai.app.media.EditParsing
import com.honerai.app.media.EditStep
import com.honerai.app.media.MediaEditingException
import com.honerai.app.media.PixelOps
import com.honerai.app.media.SubjectMask
import com.honerai.app.media.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class ImageEditingParseTest {
    private fun steps(json: String) = EditParsing.resolveAll(json)

    @Test
    fun parsesJsonArray() {
        val result = steps(
            """[{"type":"remove_background"},{"type":"background_color","color":"#FF0000"},
               {"type":"filter","value":"mono","amount":50},{"type":"text","text":"Привет","color":"жёлтый","style":"neon","y":"80%"},
               {"type":"crop","value":"9:16"},{"type":"rotate","value":"left"},{"type":"resize","size":"800px"}]""",
        )
        assertEquals(EditStep.RemoveBackground, result[0])
        assertEquals(EditStep.Background(EditBackground.Color(EditColors.argb(255, 255, 0, 0))), result[1])
        assertEquals(EditStep.Filter(EditFilter.MONO, 0.5), result[2])
        val text = result[3] as EditStep.Text
        assertEquals("Привет", text.text)
        assertEquals(EditColors.rgb(1.0, 0.84, 0.1), text.color)
        assertEquals(TextStyle.NEON, text.style)
        assertEquals(0.8, text.y, 1e-9)
        assertEquals(0.5, text.x, 1e-9)
        assertEquals(EditStep.CropToAspect(CropAspect.STORY_9X16), result[4])
        assertEquals(EditStep.Rotate(3), result[5])
        assertEquals(EditStep.Resize(800), result[6])
    }

    @Test
    fun parsesSingleObjectNestedAndShorthand() {
        assertEquals(listOf(EditStep.Filter(EditFilter.SEPIA, 1.0)), steps("""{"type":"filter","value":"сепия"}"""))
        assertEquals(2, steps("""{"operations":[{"op":"flip"},{"action":"flip","direction":"vertical"}]}""").size)
        assertEquals(listOf(EditStep.Flip(false), EditStep.Flip(true)),
            steps("""{"Ops":[{"op":"flip"},{"action":"mirror","direction":"вертикально"}]}"""))
        assertEquals(listOf(EditStep.Filter(EditFilter.MONO, 1.0)), steps("filter mono"))
        assertEquals(listOf(EditStep.Filter(EditFilter.NOIR, 1.0)), steps("noir"))
        val text = steps("text: Hello world").single() as EditStep.Text
        assertEquals("Hello world", text.text)
        assertEquals(TextStyle.OUTLINE, text.style)
    }

    @Test
    fun typeAliasesIncludingRussian() {
        assertEquals(EditStep.RemoveBackground, steps("""[{"type":"убрать фон"}]""").single())
        assertEquals(EditStep.RemoveBackground, steps("""[{"type":"remove-bg"}]""").single())
        assertEquals(EditStep.Background(EditBackground.Blur(40.0)), steps("""[{"type":"размыть фон","radius":40}]""").single())
        assertEquals(EditStep.Background(null), steps("""[{"type":"фон","color":"прозрачный"}]""").single())
        val gradient = steps("""[{"type":"gradient","colors":["red","#00f"]}]""").single() as EditStep.Background
        assertEquals(EditBackground.Gradient(listOf(EditColors.rgb(0.93, 0.22, 0.21), EditColors.argb(255, 0, 0, 255))), gradient.background)
        assertEquals(EditStep.Sticker("🔥", 0.25, 0.5, 2.0), steps("""[{"type":"эмодзи","emoji":"🔥","x":25,"scale":"2"}]""").single())
        assertEquals(EditStep.Filter(EditFilter.WARM, 1.0), steps("""[{"type":"тёплый"}]""").single())
    }

    @Test
    fun numbersAsStringsAndPercent() {
        val adjust = steps("""[{"type":"adjust","brightness":"20%","contrast":"-0,3","saturation":150}]""").single() as EditStep.Adjust
        assertEquals(0.2, adjust.brightness, 1e-9)
        assertEquals(-0.3, adjust.contrast, 1e-9)
        assertEquals(1.0, adjust.saturation, 1e-9)
        val brightness = steps("""[{"type":"яркость","value":"30"}]""").single() as EditStep.Adjust
        assertEquals(0.3, brightness.brightness, 1e-9)
        val crop = steps("""[{"type":"crop","x":"10%","y":0.1,"width":"80","height":0.5}]""").single() as EditStep.CropRect
        assertEquals(EditStep.CropRect(0.1, 0.1, 0.8, 0.5), crop)
    }

    @Test
    fun rotationVariants() {
        assertEquals(EditStep.Rotate(1), steps("""[{"type":"rotate"}]""").single())
        assertEquals(EditStep.Rotate(2), steps("""[{"type":"rotate","degrees":180}]""").single())
        assertEquals(EditStep.Rotate(3), steps("""[{"type":"поворот","clockwise":false}]""").single())
        assertEquals(EditStep.Rotate(3), steps("""[{"type":"rotate","angle":-90}]""").single())
        assertEquals(EditStep.Rotate(1), steps("""[{"type":"rotate","value":"вправо"}]""").single())
    }

    @Test
    fun colorNames() {
        assertEquals(EditColors.BLACK, EditColors.parse("Чёрный"))
        assertEquals(EditColors.WHITE, EditColors.parse("белый"))
        assertEquals(EditColors.rgb(0.93, 0.22, 0.21), EditColors.parse("красная"))
        assertEquals(EditColors.rgb(0.45, 0.75, 1.0), EditColors.parse("light blue"))
        assertEquals(EditColors.argb(255, 0x12, 0x34, 0x56), EditColors.parse("#123456"))
        assertEquals(EditColors.argb(0x80, 255, 0, 0), EditColors.parse("#FF000080"))
        assertEquals(EditColors.argb(255, 0xAA, 0xBB, 0xCC), EditColors.parse("0xabc"))
        assertEquals(EditColors.TRANSPARENT, EditColors.parse("transparent"))
        assertNull(EditColors.parse("буро-малиновый"))
    }

    @Test
    fun unknownTypeThrowsDescriptiveError() {
        try {
            steps("""[{"type":"filter","value":"mono"},{"type":"explode"}]""")
            fail("expected error")
        } catch (error: MediaEditingException) {
            assertTrue(error.russian, error.russian.contains("Неизвестная операция «explode»"))
            assertTrue(error.english.contains("Unknown operation 'explode'"))
            assertTrue(error.russian.contains("remove_background"))
        }
    }

    @Test
    fun parameterErrors() {
        assertError("Неизвестный фильтр") { steps("""[{"type":"filter","value":"psychedelic"}]""") }
        assertError("Не понял цвет") { steps("""[{"type":"background_color","color":"буро-малиновый"}]""") }
        assertError("Для надписи нужен текст") { steps("""[{"type":"text"}]""") }
        assertError("Для обрезки") { steps("""[{"type":"crop"}]""") }
        assertError("resize") { steps("""[{"type":"resize"}]""") }
        assertError("Не переданы операции") { steps("[]") }
        assertError("Не переданы операции") { steps("   ") }
    }

    private fun assertError(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected error with $fragment")
        } catch (error: MediaEditingException) {
            assertTrue(error.russian, error.russian.contains(fragment))
        }
    }

    @Test
    fun namedFiltersAndAspects() {
        assertEquals(EditFilter.MONO, EditFilter.named("ЧБ"))
        assertEquals(EditFilter.VIVID, EditFilter.named("Яркий"))
        assertEquals(EditFilter.INSTANT, EditFilter.named("vintage"))
        assertNull(EditFilter.named("xyz"))
        assertEquals(CropAspect.SQUARE, CropAspect.named("1x1"))
        assertEquals(CropAspect.LANDSCAPE_16X9, CropAspect.named("широкий"))
        assertEquals(CropAspect.PORTRAIT_4X5, CropAspect.named("portrait4x5"))
        val rect = CropAspect.centerCropRect(200.0, 100.0, CropAspect.SQUARE)
        assertEquals(0.25, rect[0], 1e-9)
        assertEquals(0.5, rect[2], 1e-9)
    }

    // Пиксели и матрицы

    @Test
    fun colorMatricesBehave() {
        val gray = ColorMatrices.forFilter(EditFilter.MONO)!!
        val out = ColorMatrices.applyTo(gray, EditColors.argb(255, 200, 40, 40))
        assertEquals(EditColors.red(out), EditColors.green(out))
        assertEquals(EditColors.green(out), EditColors.blue(out))
        assertNull(ColorMatrices.adjustments(0.0, 0.0, 0.0, 0.0))
        val brighter = ColorMatrices.applyTo(ColorMatrices.adjustments(0.5, 0.0, 0.0, 0.0)!!, EditColors.argb(255, 100, 100, 100))
        assertTrue(EditColors.red(brighter) > 100)
        val rgb = ColorMatrices.toRgbMatrix(ColorMatrices.brightness(0.1f))
        assertEquals(16, rgb.size)
        assertEquals(1f, rgb[0], 1e-6f)
        assertEquals(0.1f, rgb[12], 1e-4f)
        assertEquals(1f, rgb[15], 1e-6f)
    }

    @Test
    fun stackBlurKeepsFlatImageAndSmoothsEdges() {
        val w = 40
        val h = 30
        val flat = IntArray(w * h) { EditColors.argb(255, 10, 120, 200) }
        PixelOps.stackBlur(flat, w, h, 5)
        assertTrue(flat.all { it == EditColors.argb(255, 10, 120, 200) })
        val edge = IntArray(w * h) { if (it % w < w / 2) EditColors.BLACK else EditColors.WHITE }
        PixelOps.stackBlur(edge, w, h, 4)
        val middle = EditColors.red(edge[10 * w + w / 2])
        assertTrue(middle in 60..200)
        assertEquals(0, EditColors.red(edge[10 * w]))
    }

    @Test
    fun subjectMaskFindsDiskOnPlainBackground() {
        val w = 120
        val h = 90
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val dx = x - 60
            val dy = y - 50
            if (dx * dx + dy * dy < 25 * 25) EditColors.argb(255, 220, 40, 40)
            else EditColors.argb(255, 70 + (x / 12), 110, 210 - (y / 10)) // лёгкий градиент фона
        }
        val mask = SubjectMask.compute(pixels, w, h)
        assertNotNull(mask)
        mask!!
        assertTrue(mask[50 * w + 60] > 0.95f)
        assertTrue(mask[5 * w + 5] < 0.05f)
        assertTrue(mask[85 * w + 115] < 0.05f)
        val coverage = mask.count { it > 0.5f }.toDouble() / (w * h)
        assertTrue("coverage $coverage", abs(coverage - Math.PI * 625 / (w * h)) < 0.03)
    }

    @Test
    fun subjectMaskRejectsFlatImage() {
        val pixels = IntArray(64 * 64) { EditColors.argb(255, 128, 128, 128) }
        assertNull(SubjectMask.compute(pixels, 64, 64))
    }
}
