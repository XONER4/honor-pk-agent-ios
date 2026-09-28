package com.honerai.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Правки фото на Bitmap (порт ImageEditing.swift): фильтры, настройки, геометрия, надписи,
 * стикеры, удаление и замена фона, сохранение. Все функции тяжёлые — вызывать вне главного потока.
 * Исходные картинки не меняются: каждая правка возвращает новый Bitmap.
 */
object ImageOps {
    private val filterPaint get() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    // Загрузка

    /** Загрузить фото с учётом EXIF-поворота, ограничив длинную сторону (экономно по памяти). */
    fun decode(file: File, maxPixel: Int): Bitmap? =
        decodeStream({ file.inputStream() }, maxPixel) { runCatching { ExifInterface(file).rotationMatrix() }.getOrNull() }

    fun decode(context: Context, uri: Uri, maxPixel: Int): Bitmap? = decodeStream(
        { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream") }, maxPixel,
    ) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationMatrix() } }.getOrNull()
    }

    private fun decodeStream(open: () -> InputStream, maxPixel: Int, orientation: () -> Matrix?): Bitmap? {
        var limit = maxPixel
        repeat(3) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open().use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                val longest = max(bounds.outWidth, bounds.outHeight)
                while (longest / (sample * 2) >= limit) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
                var result = resize(decoded, limit)
                val matrix = orientation()
                if (matrix != null && !matrix.isIdentity) {
                    result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
                }
                return ensureArgb(result)
            } catch (_: OutOfMemoryError) {
                // Слабый телефон: пробуем меньшее разрешение.
                limit = max(512, limit / 2)
            }
        }
        return null
    }

    private fun ExifInterface.rotationMatrix(): Matrix? {
        val matrix = Matrix()
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return null
        }
        return matrix
    }

    /** Картинка в ARGB_8888 (аппаратные и 565-картинки переводятся). */
    fun ensureArgb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)

    private fun blank(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)

    /** Уменьшить так, чтобы длинная сторона не превышала maxSide. */
    fun resize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (maxSide <= 0 || longest <= maxSide) return bitmap
        val factor = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap, max(1, (bitmap.width * factor).roundToInt()), max(1, (bitmap.height * factor).roundToInt()), true,
        )
    }

    // Фильтры и настройки

    private fun withColorMatrix(bitmap: Bitmap, matrix: FloatArray): Bitmap {
        val out = blank(bitmap.width, bitmap.height)
        val paint = filterPaint.apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /** Виньетка: затемнение краёв радиальным градиентом. */
    private fun vignette(bitmap: Bitmap, intensity: Float): Bitmap {
        val out = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val radius = hypot(w, h) / 2
        val edge = (intensity * 0.85f * 255).roundToInt().coerceIn(0, 255)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2, h / 2, radius,
                intArrayOf(0, 0, EditColors.argb(edge / 2, 0, 0, 0), EditColors.argb(edge, 0, 0, 0)),
                floatArrayOf(0f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP,
            )
            // Прозрачные пиксели (вырезанный объект) остаются прозрачными.
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        Canvas(out).drawRect(0f, 0f, w, h, paint)
        return out
    }

    /** Фильтр целиком; intensity 0…1 смешивает с исходником. */
    fun applyFilter(bitmap: Bitmap, filter: EditFilter, intensity: Double = 1.0): Bitmap {
        if (filter == EditFilter.ORIGINAL || intensity <= 0.001) return bitmap
        var output: Bitmap = when (filter) {
            EditFilter.SHARPEN -> {
                val pixels = pixels(bitmap)
                fromPixels(PixelOps.sharpen(pixels, bitmap.width, bitmap.height), bitmap.width, bitmap.height)
            }
            EditFilter.BLUR -> blurPixels(bitmap, max(2.0, max(bitmap.width, bitmap.height) * 0.012))
            else -> ColorMatrices.forFilter(filter)?.let { withColorMatrix(bitmap, it) } ?: bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val strength = ColorMatrices.vignetteStrength(filter)
        if (strength > 0) output = vignette(output, strength)
        val amount = intensity.coerceIn(0.0, 1.0)
        if (amount >= 0.999) return output
        val mixed = blank(bitmap.width, bitmap.height)
        val canvas = Canvas(mixed)
        canvas.drawBitmap(bitmap, 0f, 0f, filterPaint)
        canvas.drawBitmap(output, 0f, 0f, filterPaint.apply { alpha = (amount * 255).roundToInt() })
        return mixed
    }

    /** Все параметры -1…1, 0 — без изменений. */
    fun adjust(bitmap: Bitmap, brightness: Double, contrast: Double, saturation: Double, warmth: Double): Bitmap {
        val matrix = ColorMatrices.adjustments(brightness, contrast, saturation, warmth) ?: return bitmap
        return withColorMatrix(bitmap, matrix)
    }

    /** Размытие всей картинки; радиус задан для снимка ~1000 px. */
    fun blurred(bitmap: Bitmap, radius: Double): Bitmap {
        val longest = max(bitmap.width, bitmap.height).toDouble()
        return blurPixels(bitmap, max(1.0, radius * longest / 1000))
    }

    /** Размытие с радиусом в пикселях: большие радиусы считаются на уменьшенной копии — быстро и без лишней памяти. */
    private fun blurPixels(bitmap: Bitmap, radiusPx: Double): Bitmap {
        val factor = min(1.0, 8.0 / radiusPx)
        val small = if (factor < 1) {
            Bitmap.createScaledBitmap(bitmap, max(2, (bitmap.width * factor).roundToInt()), max(2, (bitmap.height * factor).roundToInt()), true)
        } else {
            bitmap
        }
        val pixels = pixels(small)
        PixelOps.stackBlur(pixels, small.width, small.height, max(1, (radiusPx * factor).roundToInt()))
        val blurredSmall = fromPixels(pixels, small.width, small.height)
        if (factor >= 1) return blurredSmall
        val out = blank(bitmap.width, bitmap.height)
        Canvas(out).drawBitmap(blurredSmall, null, Rect(0, 0, out.width, out.height), filterPaint)
        return out
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val result = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(result, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }

    private fun fromPixels(pixels: IntArray, width: Int, height: Int): Bitmap {
        val out = blank(width, height)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    // Геометрия

    fun rotate(bitmap: Bitmap, clockwise: Boolean): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(if (clockwise) 90f else -90f) }, true)

    /** Поворот на quarterTurns × 90° по часовой стрелке. */
    fun rotateQuarterTurns(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f * turns) }, true)
    }

    fun flip(bitmap: Bitmap, vertical: Boolean): Bitmap {
        val matrix = Matrix().apply { if (vertical) postScale(1f, -1f) else postScale(-1f, 1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun crop(bitmap: Bitmap, aspect: CropAspect): Bitmap {
        if (aspect == CropAspect.ORIGINAL) return bitmap
        val r = CropAspect.centerCropRect(bitmap.width.toDouble(), bitmap.height.toDouble(), aspect)
        return crop(bitmap, r[0], r[1], r[2], r[3])
    }

    /** Обрезка по нормализованному прямоугольнику (0…1, начало — левый верхний угол). */
    fun crop(bitmap: Bitmap, x: Double, y: Double, width: Double, height: Double): Bitmap {
        val left = min(x, x + width).coerceIn(0.0, 1.0)
        val top = min(y, y + height).coerceIn(0.0, 1.0)
        val right = max(x, x + width).coerceIn(0.0, 1.0)
        val bottom = max(y, y + height).coerceIn(0.0, 1.0)
        if (right - left <= 0.001 || bottom - top <= 0.001) return bitmap
        val px = (left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val py = (top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val pw = max(1, ((right - left) * bitmap.width).roundToInt()).coerceAtMost(bitmap.width - px)
        val ph = max(1, ((bottom - top) * bitmap.height).roundToInt()).coerceAtMost(bitmap.height - py)
        return Bitmap.createBitmap(bitmap, px, py, pw, ph)
    }

    // Надписи, стикеры, слои

    fun heavyTypeface(): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 900, false) else Typeface.DEFAULT_BOLD

    /** Надпись. x, y — центр текста (0…1), fontSize — доля ширины фото (например 0.08). */
    fun addText(bitmap: Bitmap, text: String, x: Double, y: Double, color: Int, fontSize: Double, style: TextStyle): Bitmap {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return bitmap
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val pointSize = max(6f, (fontSize.coerceIn(0.01, 0.5) * out.width).toFloat())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = pointSize
            typeface = heavyTypeface()
            this.color = color
        }
        val maxWidth = max(1, (out.width * 0.92f).toInt())
        fun layout(p: TextPaint, width: Int): StaticLayout = StaticLayout.Builder.obtain(trimmed, 0, trimmed.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val measured = layout(paint, maxWidth)
        val textWidth = min(maxWidth, ceil((0 until measured.lineCount).maxOf { measured.getLineWidth(it) }).toInt() + 2)
        val textHeight = measured.height
        val cx = (x.coerceIn(0.0, 1.0) * out.width).toFloat()
        val cy = (y.coerceIn(0.0, 1.0) * out.height).toFloat()
        val left = cx - textWidth / 2f
        val top = cy - textHeight / 2f
        fun draw(p: TextPaint) {
            canvas.save()
            canvas.translate(left, top)
            layout(p, textWidth).draw(canvas)
            canvas.restore()
        }
        when (style) {
            TextStyle.PLAIN -> {
                paint.setShadowLayer(pointSize * 0.12f, 0f, pointSize * 0.04f, EditColors.argb(102, 0, 0, 0))
                draw(paint)
            }
            TextStyle.OUTLINE -> {
                val stroke = TextPaint(paint).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = pointSize * 0.08f
                    strokeJoin = Paint.Join.ROUND
                    this.color = EditColors.contrasting(color)
                }
                draw(stroke)
                draw(paint)
            }
            TextStyle.BANNER -> {
                val plate = RectF(left - pointSize * 0.45f, top - pointSize * 0.2f,
                    left + textWidth + pointSize * 0.45f, top + textHeight + pointSize * 0.2f)
                canvas.drawRoundRect(plate, pointSize * 0.3f, pointSize * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                paint.color = EditColors.contrasting(color)
                draw(paint)
            }
            TextStyle.NEON -> {
                paint.color = EditColors.neonCore(color)
                paint.setShadowLayer(pointSize * 0.5f, 0f, 0f, color)
                draw(paint)
                draw(paint)
            }
        }
        return out
    }

    /** Эмодзи-стикер. scale 1 — примерно 18 % ширины фото. */
    fun addSticker(bitmap: Bitmap, emoji: String, x: Double, y: Double, scale: Double): Bitmap {
        val trimmed = emoji.trim()
        if (trimmed.isEmpty()) return bitmap
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val size = max(8f, (out.width * 0.18 * scale.coerceIn(0.1, 8.0)).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; textAlign = Paint.Align.CENTER }
        val metrics = paint.fontMetrics
        val cx = (x.coerceIn(0.0, 1.0) * out.width).toFloat()
        val cy = (y.coerceIn(0.0, 1.0) * out.height).toFloat()
        Canvas(out).drawText(trimmed, cx, cy - (metrics.ascent + metrics.descent) / 2, paint)
        return out
    }

    /** Наложить слой (рисунок) на всю площадь фото. */
    fun overlay(bitmap: Bitmap, layer: Bitmap): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(layer, null, Rect(0, 0, out.width, out.height), filterPaint)
        return out
    }

    // Фон

    /**
     * Вырезать объект (фон становится прозрачным). Маска считается на уменьшенной копии
     * (модель цвета фона по краям + заливка), затем растягивается с мягким краем.
     */
    fun removeBackground(bitmap: Bitmap, workSide: Int = 480): Bitmap {
        val work = resize(bitmap, workSide)
        val pixels = pixels(work)
        val mask = SubjectMask.compute(pixels, work.width, work.height) ?: throw MediaEditingException.noSubject
        // Уже прозрачные пиксели исходника остаются прозрачными.
        for (i in pixels.indices) {
            val alpha = ((mask[i] * ((pixels[i] ushr 24) and 255))).roundToInt().coerceIn(0, 255)
            pixels[i] = alpha shl 24
        }
        val maskBitmap = fromPixels(pixels, work.width, work.height)
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        out.setHasAlpha(true)
        val paint = filterPaint.apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        Canvas(out).drawBitmap(maskBitmap, null, Rect(0, 0, out.width, out.height), paint)
        return out
    }

    /** Положить вырезанный объект на фон. original — исходник для размытого фона. */
    fun composite(subject: Bitmap, background: EditBackground, original: Bitmap? = null): Bitmap {
        val out = blank(subject.width, subject.height)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        when (background) {
            is EditBackground.Color -> canvas.drawColor(background.argb)
            is EditBackground.Blur -> canvas.drawBitmap(blurred(original ?: subject, background.radius), null, rect, filterPaint)
            is EditBackground.Picture -> drawAspectFill(canvas, background.bitmap, rect)
            is EditBackground.Gradient -> {
                val colors = when (background.colors.size) {
                    0 -> intArrayOf(EditColors.WHITE, EditColors.rgb(0.8, 0.8, 0.8))
                    1 -> intArrayOf(background.colors[0], background.colors[0])
                    else -> background.colors.toIntArray()
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                    shader = LinearGradient(0f, 0f, rect.right, rect.bottom, colors, null, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(rect, paint)
            }
        }
        canvas.drawBitmap(subject, 0f, 0f, filterPaint)
        return out
    }

    private fun drawAspectFill(canvas: Canvas, picture: Bitmap, rect: RectF) {
        if (picture.width <= 0 || picture.height <= 0) return
        val scale = max(rect.width() / picture.width, rect.height() / picture.height)
        val w = picture.width * scale
        val h = picture.height * scale
        val target = RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2)
        canvas.drawBitmap(picture, null, target, filterPaint)
    }

    /** Есть ли в картинке действительно прозрачные пиксели (проверка по уменьшенной копии). */
    fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val pixels = pixels(small)
        return pixels.any { (it ushr 24) < 250 }
    }

    fun flattened(bitmap: Bitmap, background: Int): Bitmap {
        val out = blank(bitmap.width, bitmap.height)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(bitmap, 0f, 0f, filterPaint)
        return out
    }

    /** Сохранить в [dir]: PNG — для прозрачности, иначе JPEG 90 % (прозрачность заливается белым). */
    fun save(bitmap: Bitmap, dir: File, preferPng: Boolean): File {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw MediaEditingException.saveFailed
        val file = File(dir, UUID.randomUUID().toString() + if (preferPng) ".png" else ".jpg")
        try {
            val source = if (!preferPng && bitmap.hasAlpha()) flattened(bitmap, EditColors.WHITE) else bitmap
            val ok = FileOutputStream(file).use { stream ->
                source.compress(if (preferPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (preferPng) 100 else 90, stream)
            }
            if (!ok) throw MediaEditingException.saveFailed
        } catch (error: MediaEditingException) {
            file.delete()
            throw error
        } catch (error: Exception) {
            file.delete()
            throw MediaEditingException.saveFailed
        }
        return file
    }
}
