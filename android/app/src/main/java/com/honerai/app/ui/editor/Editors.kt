package com.honerai.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import java.io.File

// ЗАГОТОВКА (модуль «Игры и редактор»).

/** Редактор фото на весь экран. [onSave] получает сохранённый файл. */
@Composable
fun PhotoEditorScreen(imageFile: File, onSave: (File) -> Unit, onCancel: () -> Unit) {}

/** Редактор видео на весь экран. */
@Composable
fun VideoEditorScreen(videoFile: File, onSave: (File) -> Unit, onCancel: () -> Unit) {}

/** Правка фото для инструмента нейросети edit_image. */
object ImageEditing {
    /** operations — JSON-массив операций (как на iOS: remove_background, filter, text, crop…). */
    suspend fun apply(operationsJson: String, source: Bitmap): Bitmap = source

    /** Сохранить результат в [dir]; PNG, если есть прозрачность. */
    fun save(bitmap: Bitmap, dir: File, preferPng: Boolean): File = File(dir, "edited.png")
}
