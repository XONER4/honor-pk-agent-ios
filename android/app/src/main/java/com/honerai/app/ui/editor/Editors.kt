package com.honerai.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import com.honerai.app.media.EditParsing
import com.honerai.app.media.EditStep
import com.honerai.app.media.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

// Модуль «Игры и редактор»: точки входа редакторов фото и видео и правка фото для нейросети.

/** Редактор фото на весь экран. [onSave] получает сохранённый файл. */
@Composable
fun PhotoEditorScreen(imageFile: File, onSave: (File) -> Unit, onCancel: () -> Unit) {
    PhotoEditor(imageFile = imageFile, onSave = onSave, onCancel = onCancel)
}

/** Редактор видео на весь экран. */
@Composable
fun VideoEditorScreen(videoFile: File, onSave: (File) -> Unit, onCancel: () -> Unit) {
    VideoEditor(videoFile = videoFile, onSave = onSave, onCancel = onCancel)
}

/** Правка фото для инструмента нейросети edit_image (как ImageEditing.apply на iOS). */
object ImageEditing {
    /** Длинная сторона, до которой уменьшается очень большое фото перед правкой. */
    private const val MAX_SIDE = 4096

    /**
     * operations — JSON-массив операций (как на iOS: remove_background, filter, text, crop…),
     * также один объект, {"operations": [...]} или короткая строка «filter mono».
     * Бросает [com.honerai.app.media.MediaEditingException] с понятным текстом (рус. / англ.).
     */
    suspend fun apply(operationsJson: String, source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val steps = EditParsing.resolveAll(operationsJson)
        var current = ImageOps.ensureArgb(ImageOps.resize(source, MAX_SIDE))
        var cutoutSubject: Bitmap? = null
        var cutoutOriginal: Bitmap? = null
        for (step in steps) {
            ensureActive()
            when (step) {
                EditStep.RemoveBackground -> {
                    val subject = ImageOps.removeBackground(current)
                    cutoutOriginal = current
                    cutoutSubject = subject
                    current = subject
                }
                is EditStep.Background -> {
                    // Объект, вырезанный предыдущей операцией с фоном, используется повторно:
                    // вторая замена фона не вырезает объект из уже собранной картинки.
                    val knownSubject = cutoutSubject
                    val knownOriginal = cutoutOriginal
                    val original = if (knownSubject != null && knownOriginal != null) knownOriginal else current
                    val subject = if (knownSubject != null && knownOriginal != null) knownSubject else ImageOps.removeBackground(current)
                    cutoutSubject = subject
                    cutoutOriginal = original
                    current = step.background?.let { ImageOps.composite(subject, it, original) } ?: subject
                }
                else -> {
                    current = applySimple(step, current)
                    cutoutSubject = null
                    cutoutOriginal = null
                }
            }
        }
        current
    }

    private fun applySimple(step: EditStep, image: Bitmap): Bitmap = when (step) {
        is EditStep.Filter -> ImageOps.applyFilter(image, step.filter, step.intensity)
        is EditStep.Adjust -> ImageOps.adjust(image, step.brightness, step.contrast, step.saturation, step.warmth)
        is EditStep.Rotate -> ImageOps.rotateQuarterTurns(image, step.quarterTurns)
        is EditStep.Flip -> ImageOps.flip(image, step.vertical)
        is EditStep.CropRect -> ImageOps.crop(image, step.x, step.y, step.width, step.height)
        is EditStep.CropToAspect -> ImageOps.crop(image, step.aspect)
        is EditStep.Text -> ImageOps.addText(image, step.text, step.x, step.y, step.color, step.size, step.style)
        is EditStep.Sticker -> ImageOps.addSticker(image, step.emoji, step.x, step.y, step.scale)
        is EditStep.Resize -> ImageOps.resize(image, step.maxSide)
        EditStep.RemoveBackground, is EditStep.Background -> image
    }

    /** Сохранить результат в [dir]; PNG, если есть прозрачность (иначе JPEG 90 %). */
    fun save(bitmap: Bitmap, dir: File, preferPng: Boolean): File = ImageOps.save(bitmap, dir, preferPng)

    /** Есть ли в картинке прозрачные пиксели — подсказка для [save] (preferPng). */
    fun hasTransparency(bitmap: Bitmap): Boolean = ImageOps.hasTransparency(bitmap)
}
