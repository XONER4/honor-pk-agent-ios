package com.honerai.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.honerai.app.device.AttachmentImporter
import com.honerai.app.media.CropAspect
import com.honerai.app.media.EditBackground
import com.honerai.app.media.EditFilter
import com.honerai.app.media.ImageOps
import com.honerai.app.media.MediaEditingException
import com.honerai.app.media.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/** Фильтр и настройки, которые ещё не «запечены» в фото. */
internal data class PhotoLook(
    val filter: EditFilter = EditFilter.ORIGINAL,
    val intensity: Double = 1.0,
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val saturation: Double = 0.0,
    val warmth: Double = 0.0,
) {
    val hasAdjustments: Boolean
        get() = abs(brightness) > 0.001 || abs(contrast) > 0.001 || abs(saturation) > 0.001 || abs(warmth) > 0.001
    val isIdentity: Boolean get() = filter == EditFilter.ORIGINAL && !hasAdjustments

    fun apply(image: Bitmap): Bitmap {
        var result = image
        if (filter != EditFilter.ORIGINAL) result = ImageOps.applyFilter(result, filter, intensity)
        if (hasAdjustments) result = ImageOps.adjust(result, brightness, contrast, saturation, warmth)
        return result
    }
}

/** Стикер на холсте: положение — доли размера фото, масштаб 1 ≈ 18 % ширины. */
@Stable
internal class EditorSticker(val id: Long, val emoji: String, position: Offset, scale: Float) {
    var position by mutableStateOf(position)
    var scale by mutableStateOf(scale)
}

/** Штрих рисунка: точки в долях размера фото, толщина — доля ширины. */
@Stable
internal class DrawStroke(val color: Int, val widthFraction: Float) {
    val points = mutableStateListOf<Offset>()
}

/**
 * Фото для истории отмены. Картинки, не нужные сейчас, выгружаются в файлы кэша,
 * когда история занимает больше памяти, чем можно на этом телефоне.
 */
internal class StoredImage(bitmap: Bitmap) {
    @Volatile private var bitmap: Bitmap? = bitmap
    @Volatile private var file: File? = null
    private val width = bitmap.width
    private val height = bitmap.height
    val bytes: Long = bitmap.allocationByteCount.toLong()
    val inMemory: Boolean get() = bitmap != null

    fun peek(): Bitmap? = bitmap

    /** Картинка (из памяти или из файла) — вызывать вне главного потока. */
    fun get(): Bitmap {
        bitmap?.let { return it }
        val source = file ?: throw MediaEditingException.invalidImage
        val buffer = ByteBuffer.allocateDirect((width * height * 4))
        FileInputStream(source).channel.use { channel -> while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit }
        buffer.rewind()
        val restored = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        restored.copyPixelsFromBuffer(buffer)
        bitmap = restored
        return restored
    }

    fun spill(dir: File) {
        val current = bitmap ?: return
        if (file == null) {
            dir.mkdirs()
            val target = File(dir, UUID.randomUUID().toString() + ".raw")
            val buffer = ByteBuffer.allocateDirect(current.byteCount)
            current.copyPixelsToBuffer(buffer)
            buffer.rewind()
            FileOutputStream(target).channel.use { channel -> while (buffer.hasRemaining()) channel.write(buffer) }
            file = target
        }
        bitmap = null
    }
}

private class PhotoSnapshot(val image: StoredImage, val look: PhotoLook, val cutout: StoredImage?, val cutoutSource: StoredImage?)

internal enum class PhotoTool(val raw: String, val russian: String, val english: String) {
    FILTERS("filters", "Фильтры", "Filters"),
    ADJUST("adjust", "Настройки", "Adjust"),
    CROP("crop", "Обрезка", "Crop"),
    BACKGROUND("background", "Фон", "Background"),
    TEXT("text", "Текст", "Text"),
    STICKERS("stickers", "Стикеры", "Stickers"),
    DRAW("draw", "Рисование", "Draw"),
}

/** Состояние фоторедактора (порт PhotoEditorModel): тяжёлая работа — на Dispatchers.Default. */
@Stable
internal class PhotoEditorModel(private val context: Context, lowEnd: Boolean) {
    var english = false
    private val previewSide = if (lowEnd) 1200 else 1600
    private val loadSide = if (lowEnd) 1600 else 2048
    private val undoBudget: Long = Runtime.getRuntime().maxMemory() / if (lowEnd) 8 else 5
    private val undoDir = File(context.cacheDir, "editor-undo/" + UUID.randomUUID().toString())

    var base by mutableStateOf<Bitmap?>(null); private set
    var originalPreview by mutableStateOf<ImageBitmap?>(null); private set
    var preview by mutableStateOf<ImageBitmap?>(null); private set
    var previewHasTransparency by mutableStateOf(false); private set
    var thumbnails by mutableStateOf<Map<EditFilter, ImageBitmap>>(emptyMap()); private set
    var busyMessage by mutableStateOf<String?>(null); private set
    var isWorking by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var hasCutout by mutableStateOf(false); private set
    var loadFailed by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null)
    var look by mutableStateOf(PhotoLook())

    var textDraft by mutableStateOf("")
    var textColorIndex by mutableStateOf(0)
    var textStyle by mutableStateOf(TextStyle.OUTLINE)
    var textSize by mutableStateOf(0.08f)
    var textPosition by mutableStateOf(Offset(0.5f, 0.5f))

    val stickers = mutableStateListOf<EditorSticker>()
    private var nextStickerId = 1L

    val strokes = mutableStateListOf<DrawStroke>()
    var drawColorIndex by mutableStateOf(2)
    var drawWidth by mutableStateOf(8f)
    var erasing by mutableStateOf(false)

    /** Свободная обрезка: прямоугольник в долях размера фото (null — режим выключен). */
    var cropRect by mutableStateOf<Rect?>(null)

    private var displayBase by mutableStateOf<Bitmap?>(null)
    private var baseHandle: StoredImage? = null
    private var cutout: StoredImage? = null
    private var cutoutSource: StoredImage? = null
    private val undoStack = ArrayList<PhotoSnapshot>()
    private val undoLock = Mutex()

    private fun t(russian: String, englishText: String) = if (english) englishText else russian

    val textColor: Int get() = EditorPalette.color(textColorIndex)
    val drawColor: Int get() = EditorPalette.color(drawColorIndex)
    val pendingText: String get() = textDraft.trim()

    // Загрузка и предпросмотр

    suspend fun load(file: File) {
        if (base != null || loadFailed) return
        val loaded = withContext(Dispatchers.IO) { runCatching { ImageOps.decode(file, loadSide) }.getOrNull() }
        if (loaded == null) {
            loadFailed = true
            return
        }
        originalPreview = withContext(Dispatchers.Default) { ImageOps.resize(loaded, previewSide).asImageBitmap() }
        setBase(loaded)
    }

    private suspend fun setBase(image: Bitmap) {
        base = image
        baseHandle = StoredImage(image)
        displayBase = withContext(Dispatchers.Default) { ImageOps.resize(image, previewSide) }
    }

    /** Предпросмотр: пересчёт при смене фото или настроек; промежуточные значения ползунков пропускаются. */
    suspend fun runPreviewLoop() {
        snapshotFlow { displayBase to look }.conflate().collect { (source, currentLook) ->
            if (source == null) return@collect
            val result = withContext(Dispatchers.Default) {
                try {
                    val image = currentLook.apply(source)
                    image to ImageOps.hasTransparency(image)
                } catch (_: OutOfMemoryError) {
                    source to ImageOps.hasTransparency(source)
                }
            }
            preview = result.first.asImageBitmap()
            previewHasTransparency = result.second
        }
    }

    /** Миниатюры фильтров по текущему фото. */
    suspend fun runThumbnailLoop() {
        snapshotFlow { displayBase }.collectLatest { source ->
            if (source == null) return@collectLatest
            thumbnails = withContext(Dispatchers.Default) {
                val small = ImageOps.resize(ImageOps.crop(ImageOps.resize(source, 240), CropAspect.SQUARE), 128)
                EditFilter.entries.associateWith { ImageOps.applyFilter(small, it).asImageBitmap() }
            }
        }
    }

    // Отмена

    fun pushUndo() {
        val handle = baseHandle ?: return
        undoStack.add(PhotoSnapshot(handle, look, cutout, cutoutSource))
        while (undoStack.size > 15) undoStack.removeAt(0)
        canUndo = true
    }

    /** Лишние картинки истории — в файлы кэша, чтобы не упереться в память на слабых телефонах. */
    private suspend fun enforceUndoBudget() = withContext(Dispatchers.IO) {
        undoLock.withLock {
            val live = setOf(baseHandle, cutout, cutoutSource)
            val handles = LinkedHashSet<StoredImage>()
            for (snapshot in undoStack) listOfNotNull(snapshot.image, snapshot.cutout, snapshot.cutoutSource).forEach { handles.add(it) }
            var total = handles.filter { it.inMemory && it !in live }.sumOf { it.bytes }
            for (handle in handles) {
                if (total <= undoBudget) break
                if (!handle.inMemory || handle in live) continue
                runCatching { handle.spill(undoDir) }
                total -= handle.bytes
            }
        }
    }

    suspend fun undo() {
        if (isWorking || undoStack.isEmpty()) return
        val last = undoStack.removeAt(undoStack.size - 1)
        canUndo = undoStack.isNotEmpty()
        isWorking = true
        try {
            val image = withContext(Dispatchers.IO) { undoLock.withLock { last.image.get() } }
            cutout = last.cutout
            cutoutSource = last.cutoutSource
            hasCutout = last.cutout != null
            look = last.look
            base = image
            baseHandle = last.image
            displayBase = withContext(Dispatchers.Default) { ImageOps.resize(image, previewSide) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            errorMessage = t("Не удалось отменить правку", "Could not undo")
        } finally {
            isWorking = false
        }
    }

    // Правки

    /** Выполнить тяжёлую правку вне главного потока. resetLook — «запечь» фильтр в фото. */
    private suspend fun perform(busy: String?, resetLook: Boolean = false, work: (Bitmap) -> Bitmap): Boolean {
        val current = base ?: return false
        if (isWorking) return false
        isWorking = true
        busyMessage = busy
        try {
            val result = withContext(Dispatchers.Default) { work(current) }
            pushUndo()
            if (resetLook) look = PhotoLook()
            setBase(result)
            enforceUndoBudget()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = describe(error)
            return false
        } finally {
            isWorking = false
            busyMessage = null
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is MediaEditingException -> error.text(english)
        is OutOfMemoryError -> t("Не хватает памяти для этой правки", "Not enough memory for this edit")
        else -> error.localizedMessage ?: t("Не удалось выполнить правку", "Could not apply the edit")
    }

    private fun clearCutout() {
        cutout = null
        cutoutSource = null
        hasCutout = false
    }

    fun selectFilter(filter: EditFilter) {
        if (look.filter == filter) return
        pushUndo()
        look = look.copy(filter = filter, intensity = 1.0)
    }

    fun resetAdjustments() {
        if (!look.hasAdjustments) return
        pushUndo()
        look = look.copy(brightness = 0.0, contrast = 0.0, saturation = 0.0, warmth = 0.0)
    }

    suspend fun rotate(clockwise: Boolean) {
        if (perform(null) { ImageOps.rotate(it, clockwise) }) clearCutout()
    }

    suspend fun flip(vertical: Boolean) {
        if (perform(null) { ImageOps.flip(it, vertical) }) clearCutout()
    }

    suspend fun crop(aspect: CropAspect) {
        cropRect = null
        if (perform(null) { ImageOps.crop(it, aspect) }) clearCutout()
    }

    fun startFreeCrop() {
        cropRect = Rect(0.08f, 0.08f, 0.92f, 0.92f)
    }

    suspend fun applyFreeCrop() {
        val rect = cropRect ?: return
        cropRect = null
        val done = perform(null) {
            ImageOps.crop(it, rect.left.toDouble(), rect.top.toDouble(), rect.width.toDouble(), rect.height.toDouble())
        }
        if (done) clearCutout()
    }

    suspend fun removeBackground() {
        val current = base ?: return
        if (isWorking) return
        if (cutout?.peek() === current) return
        isWorking = true
        busyMessage = t("Убираю фон…", "Removing background…")
        try {
            val subject = withContext(Dispatchers.Default) { ImageOps.removeBackground(current) }
            pushUndo()
            cutoutSource = baseHandle
            setBase(subject)
            cutout = baseHandle
            hasCutout = true
            enforceUndoBudget()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = describe(error)
        } finally {
            isWorking = false
            busyMessage = null
        }
    }

    /** null — прозрачный фон. */
    suspend fun applyBackground(background: EditBackground?) {
        if (cutout == null) removeBackground()
        val subjectHandle = cutout ?: return
        val sourceHandle = cutoutSource ?: return
        if (isWorking) return
        if (background == null) {
            if (base !== subjectHandle.peek()) {
                isWorking = true
                try {
                    val subject = withContext(Dispatchers.IO) { undoLock.withLock { subjectHandle.get() } }
                    pushUndo()
                    base = subject
                    baseHandle = subjectHandle
                    displayBase = withContext(Dispatchers.Default) { ImageOps.resize(subject, previewSide) }
                } finally {
                    isWorking = false
                }
            }
            return
        }
        isWorking = true
        busyMessage = t("Меняю фон…", "Changing background…")
        try {
            val result = withContext(Dispatchers.Default) {
                val subject = undoLock.withLock { subjectHandle.get() }
                val source = undoLock.withLock { sourceHandle.get() }
                val chosen = if (background is EditBackground.Picture) {
                    EditBackground.Picture(ImageOps.resize(background.bitmap, 2048))
                } else {
                    background
                }
                ImageOps.composite(subject, chosen, source)
            }
            pushUndo()
            setBase(result)
            enforceUndoBudget()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = describe(error)
        } finally {
            isWorking = false
            busyMessage = null
        }
    }

    /** Своё фото как фон. */
    suspend fun applyPictureBackground(uri: Uri) {
        val picture = withContext(Dispatchers.IO) { runCatching { ImageOps.decode(context, uri, 2048) }.getOrNull() }
        if (picture == null) {
            errorMessage = MediaEditingException.invalidImage.text(english)
            return
        }
        applyBackground(EditBackground.Picture(picture))
    }

    // Текст, стикеры, рисунок

    suspend fun commitText() {
        val text = pendingText
        if (text.isEmpty()) return
        val currentLook = look
        val color = textColor
        val position = textPosition
        val size = textSize.toDouble()
        val style = textStyle
        val done = perform(null, resetLook = !currentLook.isIdentity) {
            ImageOps.addText(currentLook.apply(it), text, position.x.toDouble(), position.y.toDouble(), color, size, style)
        }
        if (done) {
            textDraft = ""
            textPosition = Offset(0.5f, 0.5f)
            clearCutout()
        }
    }

    fun addSticker(emoji: String) {
        val offset = (stickers.size % 5) * 0.05f
        stickers.add(EditorSticker(nextStickerId++, emoji, Offset(0.5f + offset, 0.5f + offset), 1f))
    }

    fun moveSticker(sticker: EditorSticker, position: Offset) {
        sticker.position = Offset(position.x.coerceIn(0f, 1f), position.y.coerceIn(0f, 1f))
    }

    fun scaleSticker(sticker: EditorSticker, scale: Float) {
        sticker.scale = scale.coerceIn(0.3f, 5f)
    }

    fun removeSticker(sticker: EditorSticker) {
        stickers.remove(sticker)
    }

    fun clearStickers() = stickers.clear()

    suspend fun commitStickers() {
        if (stickers.isEmpty()) return
        val items = stickers.map { Triple(it.emoji, it.position, it.scale) }
        val currentLook = look
        val done = perform(null, resetLook = !currentLook.isIdentity) { source ->
            var result = currentLook.apply(source)
            for ((emoji, position, scale) in items) {
                result = ImageOps.addSticker(result, emoji, position.x.toDouble(), position.y.toDouble(), scale.toDouble())
            }
            result
        }
        if (done) {
            stickers.clear()
            clearCutout()
        }
    }

    val hasDrawing: Boolean get() = strokes.isNotEmpty()

    fun clearDrawing() = strokes.clear()

    /** Ластик: убирает штрихи, которых касается палец (как векторный ластик на iPhone). */
    /** point — доли размера фото; radius — доля ширины; aspect — высота / ширина холста. */
    fun erase(point: Offset, radius: Float, aspect: Float) {
        strokes.removeAll { stroke ->
            stroke.points.any { hypot((it.x - point.x).toDouble(), ((it.y - point.y) * aspect).toDouble()) < radius + stroke.widthFraction / 2 }
        }
    }

    suspend fun commitDrawing() {
        if (strokes.isEmpty()) return
        val items = strokes.map { Triple(it.color, it.widthFraction, it.points.toList()) }
        val currentLook = look
        val done = perform(null, resetLook = !currentLook.isIdentity) { source ->
            val out = currentLook.apply(source).copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val w = out.width.toFloat()
            val h = out.height.toFloat()
            for ((color, width, points) in items) {
                if (points.isEmpty()) continue
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = width * w
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val path = Path()
                path.moveTo(points[0].x * w, points[0].y * h)
                if (points.size == 1) path.lineTo(points[0].x * w + 0.01f, points[0].y * h)
                for (i in 1 until points.size) {
                    val previous = points[i - 1]
                    val point = points[i]
                    // Сглаживание: квадратичные кривые через середины отрезков.
                    path.quadTo(previous.x * w, previous.y * h, (previous.x + point.x) / 2 * w, (previous.y + point.y) / 2 * h)
                }
                path.lineTo(points.last().x * w, points.last().y * h)
                canvas.drawPath(path, paint)
            }
            out
        }
        if (done) {
            strokes.clear()
            clearCutout()
        }
    }

    fun hasPending(tool: PhotoTool): Boolean = when (tool) {
        PhotoTool.TEXT -> pendingText.isNotEmpty()
        PhotoTool.STICKERS -> stickers.isNotEmpty()
        PhotoTool.DRAW -> hasDrawing
        else -> false
    }

    /** Закрепить незавершённые слои инструмента, который покидаем. */
    suspend fun flush(tool: PhotoTool) {
        when (tool) {
            PhotoTool.TEXT -> commitText()
            PhotoTool.STICKERS -> commitStickers()
            PhotoTool.DRAW -> commitDrawing()
            PhotoTool.CROP -> cropRect = null
            else -> Unit
        }
    }

    // Сохранение

    suspend fun exportFinal(): File? {
        commitDrawing()
        commitText()
        commitStickers()
        val image = base ?: return null
        if (isWorking) return null
        isWorking = true
        busyMessage = t("Сохраняю…", "Saving…")
        val currentLook = look
        return try {
            withContext(Dispatchers.Default) {
                val finished = currentLook.apply(image)
                val dir = AttachmentImporter.attachmentsDir(context)
                withContext(Dispatchers.IO) { ImageOps.save(finished, dir, ImageOps.hasTransparency(finished)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = describe(error)
            null
        } finally {
            isWorking = false
            busyMessage = null
        }
    }

    fun dispose() {
        runCatching { undoDir.deleteRecursively() }
    }
}
