package com.honerai.app.device

import android.content.Context
import android.net.Uri
import com.honerai.app.data.MessageAttachment
import java.io.File

// ЗАГОТОВКА (модуль «Устройство»): вложения — фото, видео, аудио, документы.

object AttachmentImporter {
    /** Копирует файл в папку приложения и извлекает текст/кадры для нейросети. */
    suspend fun import(context: Context, uri: Uri): MessageAttachment =
        throw UnsupportedOperationException("not implemented")

    /** Папка вложений приложения. */
    fun attachmentsDir(context: Context): File = File(context.filesDir, "attachments").apply { mkdirs() }
}
