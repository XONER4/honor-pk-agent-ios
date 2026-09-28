package com.honerai.app.media

/**
 * Цветовые матрицы фильтров и настроек в формате android.graphics.ColorMatrix (4×5, сдвиги 0…255).
 * Общие для фото (ColorMatrixColorFilter) и видео (RgbMatrix в Media3). Чистый Kotlin.
 * Значения подобраны под фильтры Core Image на iPhone (CIColorControls, CIPhotoEffect*).
 */
object ColorMatrices {
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Сначала [first], затем [second]. */
    fun then(first: FloatArray, second: FloatArray): FloatArray {
        val result = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var value = 0f
                for (k in 0 until 4) value += second[row * 5 + k] * first[k * 5 + col]
                if (col == 4) value += second[row * 5 + 4]
                result[row * 5 + col] = value
            }
        }
        return result
    }

    fun chain(vararg matrices: FloatArray): FloatArray = matrices.fold(identity()) { acc, m -> then(acc, m) }

    fun saturation(s: Float): FloatArray {
        val inv = 1 - s
        return floatArrayOf(
            LR * inv + s, LG * inv, LB * inv, 0f, 0f,
            LR * inv, LG * inv + s, LB * inv, 0f, 0f,
            LR * inv, LG * inv, LB * inv + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Сдвиг яркости: b в долях (0.1 = +10 %). */
    fun brightness(b: Float): FloatArray = scaleOffset(1f, 1f, 1f, b * 255f)

    /** Контраст вокруг середины: c = 1 — без изменений. */
    fun contrast(c: Float): FloatArray = scaleOffset(c, c, c, 127.5f * (1 - c))

    /** amount > 0 — теплее, < 0 — холоднее (-1…1). */
    fun temperature(amount: Float): FloatArray {
        val a = amount.coerceIn(-1f, 1f)
        return scaleOffset(1 + 0.14f * a, 1 + 0.02f * a, 1 - 0.18f * a, 0f)
    }

    fun grayscale(): FloatArray = saturation(0f)

    fun sepia(amount: Float): FloatArray {
        val sepia = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        return lerp(identity(), sepia, amount)
    }

    /** Как CIColorControls: насыщенность, затем яркость, затем контраст. */
    fun controls(brightness: Float, contrast: Float, saturation: Float): FloatArray =
        chain(saturation(saturation), brightness(brightness), contrast(contrast))

    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(20) { a[it] + (b[it] - a[it]) * t }

    private fun scaleOffset(r: Float, g: Float, b: Float, offset: Float) = floatArrayOf(
        r, 0f, 0f, 0f, offset,
        0f, g, 0f, 0f, offset,
        0f, 0f, b, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Цветовая часть фильтра; null — фильтр не меняет цвета (оригинал, резкость, размытие, виньетка). */
    fun forFilter(filter: EditFilter): FloatArray? = when (filter) {
        EditFilter.ORIGINAL, EditFilter.SHARPEN, EditFilter.BLUR, EditFilter.VIGNETTE -> null
        EditFilter.VIVID -> controls(0.02f, 1.08f, 1.45f)
        EditFilter.WARM -> then(temperature(0.6f), controls(0.01f, 1.03f, 1.08f))
        EditFilter.COOL -> then(temperature(-0.6f), controls(0f, 1.03f, 1f))
        EditFilter.MONO -> chain(grayscale(), contrast(1.05f))
        EditFilter.NOIR -> chain(grayscale(), contrast(1.38f), brightness(-0.04f))
        EditFilter.SEPIA -> sepia(0.9f)
        EditFilter.FADE -> chain(saturation(0.72f), contrast(0.82f), brightness(0.05f), temperature(0.08f))
        EditFilter.CHROME -> chain(saturation(1.28f), contrast(1.12f), brightness(0.02f), temperature(-0.05f))
        EditFilter.INSTANT -> chain(saturation(0.82f), temperature(0.35f), contrast(0.9f), brightness(0.04f))
        EditFilter.DRAMATIC -> controls(-0.03f, 1.32f, 1.12f)
    }

    /** Сила затемнения краёв (виньетка) для фильтра. */
    fun vignetteStrength(filter: EditFilter): Float = when (filter) {
        EditFilter.VIGNETTE -> 1f
        EditFilter.DRAMATIC -> 0.7f
        else -> 0f
    }

    /** Настройки: все параметры -1…1, 0 — без изменений (как adjustedCIImage на iOS). */
    fun adjustments(brightness: Double, contrast: Double, saturation: Double, warmth: Double): FloatArray? {
        if (listOf(brightness, contrast, saturation, warmth).all { kotlin.math.abs(it) < 0.001 }) return null
        var result = identity()
        if (kotlin.math.abs(warmth) > 0.001) result = temperature(warmth.toFloat())
        val b = brightness.coerceIn(-1.0, 1.0).toFloat() * 0.3f
        val c = 1 + contrast.coerceIn(-1.0, 1.0).toFloat() * 0.4f
        val s = 1 + saturation.coerceIn(-1.0, 1.0).toFloat()
        return then(result, controls(b, c, s))
    }

    /** Матрица 4×4 по столбцам для Media3 RgbMatrix: цвет' = M · (r, g, b, 1), цвета 0…1. */
    fun toRgbMatrix(m: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) out[col * 4 + row] = m[row * 5 + col]
            out[3 * 4 + row] = m[row * 5 + 4] / 255f
        }
        out[15] = 1f
        return out
    }

    /** Применить матрицу к цвету ARGB (для проверок и мелких расчётов). */
    fun applyTo(m: FloatArray, argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val a = (argb ushr 24) and 0xFF
        fun ch(row: Int): Int = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * a + m[row * 5 + 4])
            .toInt().coerceIn(0, 255)
        return (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }
}
