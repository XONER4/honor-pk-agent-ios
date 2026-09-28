package com.honerai.app.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

// Модель правок фото (порт ImageEditing.swift без работы с пикселями): разбор операций от нейросети,
// фильтры, пропорции, стили надписей, цвета. Чистый Kotlin — проверяется JVM-тестами.

/** Понятная пользователю ошибка редактора фото и видео (русский + английский). */
class MediaEditingException(val russian: String, val english: String) : Exception("$russian / $english") {
    fun text(isEnglish: Boolean): String = if (isEnglish) english else russian

    companion object {
        val noSubject get() = MediaEditingException("Не удалось найти объект на фото", "No subject found")
        val invalidImage get() = MediaEditingException("Не удалось прочитать изображение", "Could not read the image")
        val saveFailed get() = MediaEditingException("Не удалось сохранить изображение", "Could not save the image")
        val noOperations get() = MediaEditingException("Не переданы операции (operations).", "No operations were given.")
    }
}

/** Готовые фильтры редактора. */
enum class EditFilter(val raw: String, val title: String, val englishTitle: String) {
    ORIGINAL("original", "Оригинал", "Original"),
    VIVID("vivid", "Яркий", "Vivid"),
    WARM("warm", "Тёплый", "Warm"),
    COOL("cool", "Холодный", "Cool"),
    MONO("mono", "Моно", "Mono"),
    NOIR("noir", "Нуар", "Noir"),
    SEPIA("sepia", "Сепия", "Sepia"),
    FADE("fade", "Выцветший", "Fade"),
    CHROME("chrome", "Хром", "Chrome"),
    INSTANT("instant", "Полароид", "Instant"),
    DRAMATIC("dramatic", "Драма", "Dramatic"),
    VIGNETTE("vignette", "Виньетка", "Vignette"),
    SHARPEN("sharpen", "Резкость", "Sharpen"),
    BLUR("blur", "Размытие", "Blur");

    companion object {
        private val aliases: List<Pair<String, EditFilter>> = listOf(
            "none" to ORIGINAL, "normal" to ORIGINAL, "без" to ORIGINAL, "ориг" to ORIGINAL,
            "bw" to MONO, "b&w" to MONO, "black" to MONO, "gray" to MONO, "grey" to MONO,
            "чб" to MONO, "черно" to MONO, "моно" to MONO, "сер" to MONO,
            "нуар" to NOIR, "vivid" to VIVID, "ярк" to VIVID, "насыщ" to VIVID, "сочн" to VIVID,
            "тепл" to WARM, "warm" to WARM, "холод" to COOL, "cold" to COOL, "cool" to COOL,
            "сепи" to SEPIA, "vintage" to INSTANT, "винтаж" to INSTANT, "полароид" to INSTANT,
            "моментал" to INSTANT, "ретро" to INSTANT, "fade" to FADE, "выцв" to FADE, "блекл" to FADE,
            "хром" to CHROME, "драм" to DRAMATIC, "drama" to DRAMATIC, "виньет" to VIGNETTE,
            "sharp" to SHARPEN, "резк" to SHARPEN, "чётк" to SHARPEN, "четк" to SHARPEN,
            "blur" to BLUR, "размыт" to BLUR, "размыв" to BLUR,
        )

        /** Мягкий разбор названия фильтра (английский, русский, синонимы). */
        fun named(raw: String?): EditFilter? {
            if (raw == null) return null
            val value = EditParsing.normalizedWord(raw)
            if (value.isEmpty()) return null
            entries.firstOrNull { it.raw == value }?.let { return it }
            entries.firstOrNull { EditParsing.normalizedWord(it.title) == value || it.englishTitle.lowercase() == value }
                ?.let { return it }
            for ((prefix, filter) in aliases) if (value.startsWith(prefix)) return filter
            return null
        }
    }
}

/** Пропорции обрезки (обрезка по центру). */
enum class CropAspect(val raw: String, val ratio: Double?, val label: String, val title: String, val englishTitle: String) {
    ORIGINAL("original", null, "—", "Исходный", "Original"),
    SQUARE("square", 1.0, "1:1", "Квадрат", "Square"),
    PORTRAIT_4X5("portrait4x5", 4.0 / 5.0, "4:5", "Портрет", "Portrait"),
    STORY_9X16("story9x16", 9.0 / 16.0, "9:16", "Сторис", "Story"),
    LANDSCAPE_16X9("landscape16x9", 16.0 / 9.0, "16:9", "Широкий", "Wide");

    companion object {
        fun named(raw: String?): CropAspect? {
            if (raw == null) return null
            entries.firstOrNull { it.raw == raw.trim() }?.let { return it }
            val value = EditParsing.normalizedWord(raw).replace("x", ":").replace("/", ":").replace("×", ":")
            return when (value) {
                "1:1", "square", "квадрат", "квадратный" -> SQUARE
                "4:5", "portrait", "портрет", "портретный", "portrait4:5" -> PORTRAIT_4X5
                "9:16", "story", "stories", "сторис", "история", "vertical", "вертикальный", "story9:16" -> STORY_9X16
                "16:9", "landscape", "wide", "широкий", "пейзаж", "горизонтальный", "landscape16:9" -> LANDSCAPE_16X9
                "original", "исходный", "оригинал", "none" -> ORIGINAL
                else -> null
            }
        }

        /** Нормализованный прямоугольник (x, y, ширина, высота) обрезки по центру для нужных пропорций. */
        fun centerCropRect(width: Double, height: Double, aspect: CropAspect): DoubleArray {
            val ratio = aspect.ratio
            if (ratio == null || width <= 0 || height <= 0) return doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            val imageRatio = width / height
            if (imageRatio > ratio) {
                val w = ratio / imageRatio
                return doubleArrayOf((1 - w) / 2, 0.0, w, 1.0)
            }
            val h = imageRatio / ratio
            return doubleArrayOf(0.0, (1 - h) / 2, 1.0, h)
        }
    }
}

/** Оформление надписи. */
enum class TextStyle(val raw: String, val title: String, val englishTitle: String) {
    PLAIN("plain", "Обычный", "Plain"),
    OUTLINE("outline", "Контур", "Outline"),
    BANNER("banner", "Плашка", "Banner"),
    NEON("neon", "Неон", "Neon");

    companion object {
        fun named(raw: String?): TextStyle? {
            if (raw == null) return null
            val value = EditParsing.normalizedWord(raw)
            entries.firstOrNull { it.raw == value }?.let { return it }
            if (value.startsWith("обыч") || value.startsWith("прост")) return PLAIN
            if (value.startsWith("контур") || value.startsWith("обвод") || value == "stroke") return OUTLINE
            if (value.startsWith("плаш") || value.startsWith("фон") || value == "label" || value == "box") return BANNER
            if (value.startsWith("неон") || value == "glow" || value.startsWith("свеч")) return NEON
            return null
        }
    }
}

/** Одна правка фото, которую присылает модель. */
data class ImageEditOperation(
    var type: String,
    var value: String? = null,
    var text: String? = null,
    var color: String? = null,
    var style: String? = null,
    var x: Double? = null,
    var y: Double? = null,
    var width: Double? = null,
    var height: Double? = null,
    var size: Double? = null,
    var amount: Double? = null,
    var brightness: Double? = null,
    var contrast: Double? = null,
    var saturation: Double? = null,
    var warmth: Double? = null,
) {
    /** Тип операции после разбора синонимов. */
    val normalizedType: String
        get() {
            val raw = EditParsing.normalizedWord(type).replace("-", "_").replace(" ", "_")
            return when (raw) {
                "remove_background", "remove_bg", "removebackground", "removebg", "cutout", "cut_out",
                "убрать_фон", "удалить_фон", "вырезать", "вырезать_фон" -> "remove_background"
                "background_color", "background_colour", "background", "bg", "bg_color", "set_background",
                "replace_background", "фон", "цвет_фона", "заменить_фон" -> "background_color"
                "background_blur", "blur_background", "bg_blur", "размыть_фон", "размытый_фон" -> "background_blur"
                "background_gradient", "gradient", "gradient_background", "градиент", "градиентный_фон" -> "background_gradient"
                "filter", "effect", "фильтр", "эффект" -> "filter"
                "adjust", "adjustment", "adjustments", "brightness", "contrast", "saturation", "warmth", "temperature",
                "настройки", "яркость", "контраст", "насыщенность", "теплота" -> "adjust"
                "rotate", "rotation", "turn", "повернуть", "поворот" -> "rotate"
                "flip", "mirror", "отразить", "зеркало", "отражение" -> "flip"
                "crop", "обрезать", "обрезка" -> "crop"
                "text", "caption", "add_text", "title", "label", "надпись", "текст", "подпись" -> "text"
                "sticker", "emoji", "add_sticker", "стикер", "эмодзи", "смайлик" -> "sticker"
                "resize", "scale", "downscale", "размер", "уменьшить" -> "resize"
                else -> if (EditFilter.named(raw) != null) "filter" else raw
            }
        }

    companion object {
        val supportedTypes = listOf(
            "remove_background", "background_color", "background_blur", "background_gradient",
            "filter", "adjust", "rotate", "flip", "crop", "text", "sticker", "resize",
        )
    }
}

/** Новый фон под вырезанным объектом. */
sealed class EditBackground {
    data class Color(val argb: Int) : EditBackground()
    /** Размытие исходного фото; радиус задан для снимка ~1000 px и масштабируется. */
    data class Blur(val radius: Double) : EditBackground()
    data class Gradient(val colors: List<Int>) : EditBackground()
    class Picture(val bitmap: android.graphics.Bitmap) : EditBackground()
}

/** Правка, готовая к выполнению (все параметры разобраны и проверены). */
sealed class EditStep {
    data object RemoveBackground : EditStep()
    /** background == null — прозрачный фон. */
    data class Background(val background: EditBackground?) : EditStep()
    data class Filter(val filter: EditFilter, val intensity: Double) : EditStep()
    data class Adjust(val brightness: Double, val contrast: Double, val saturation: Double, val warmth: Double) : EditStep()
    /** Число поворотов на 90° по часовой стрелке (0…3). */
    data class Rotate(val quarterTurns: Int) : EditStep()
    data class Flip(val vertical: Boolean) : EditStep()
    data class CropRect(val x: Double, val y: Double, val width: Double, val height: Double) : EditStep()
    data class CropToAspect(val aspect: CropAspect) : EditStep()
    data class Text(val text: String, val x: Double, val y: Double, val color: Int, val size: Double, val style: TextStyle) : EditStep()
    data class Sticker(val emoji: String, val x: Double, val y: Double, val scale: Double) : EditStep()
    data class Resize(val maxSide: Int) : EditStep()
}

/** Разбор операций и параметров (как parseOperations на iOS). */
object EditParsing {
    private val json = Json { isLenient = true }

    fun normalizedWord(raw: String): String = raw.trim().lowercase().replace("ё", "е")

    /** Разобрать операции: JSON-массив, один объект, {"operations": [...]}, или короткую строку вроде "filter mono". */
    fun parseOperations(raw: String?): List<ImageEditOperation> {
        if (raw == null) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            if (element != null) return parseOperations(element)
        }
        return listOfNotNull(shorthandOperation(trimmed))
    }

    fun parseOperations(element: JsonElement?): List<ImageEditOperation> = when (element) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> if (element.isString) parseOperations(element.content) else emptyList()
        is JsonArray -> element.flatMap { parseOperations(it) }
        is JsonObject -> {
            val lowered = element.entries.associate { it.key.lowercase() to it.value }
            val nestedKey = listOf("operations", "ops", "edits", "steps", "actions").firstOrNull { lowered.containsKey(it) }
            if (nestedKey != null) parseOperations(lowered[nestedKey]) else listOfNotNull(operation(lowered))
        }
    }

    private fun shorthandOperation(string: String): ImageEditOperation? {
        if (string.isEmpty()) return null
        val index = string.indexOfFirst { it == ' ' || it == ':' || it == '=' }
        if (index < 0) return ImageEditOperation(type = string)
        val operation = ImageEditOperation(type = string.substring(0, index))
        val rest = string.substring(index + 1).trim()
        if (operation.normalizedType == "text") operation.text = rest else operation.value = rest.ifEmpty { null }
        return operation
    }

    private fun firstValue(map: Map<String, JsonElement>, keys: List<String>): JsonElement? {
        for (key in keys) {
            val value = map[key]
            if (value != null && value !is JsonNull) return value
        }
        return null
    }

    private fun stringValue(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull?.let { if (it) "1" else "0" } ?: value.content
        is JsonArray -> value.mapNotNull { stringValue(it) }.takeIf { it.isNotEmpty() }?.joinToString(",")
        is JsonObject -> null
    }

    /** Число из числа или строки ("50%", "1,5", "90°", "800px"). */
    fun doubleValue(value: JsonElement?): Double? {
        if (value !is JsonPrimitive || value is JsonNull) return null
        if (!value.isString) return value.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: value.content.toDoubleOrNull()
        return parseNumber(value.content)
    }

    fun parseNumber(raw: String?): Double? {
        if (raw == null) return null
        val cleaned = raw.trim().replace(",", ".").replace("%", "").replace("px", "").replace("°", "").trim()
        return cleaned.toDoubleOrNull()
    }

    private fun operation(map: Map<String, JsonElement>): ImageEditOperation? {
        val type = stringValue(firstValue(map, listOf("type", "op", "operation", "action", "tool", "kind")))
        if (type == null || type.isBlank()) return null
        val op = ImageEditOperation(type = type)
        op.value = stringValue(firstValue(map, listOf("value", "filter", "emoji", "aspect", "direction", "mode", "name", "colors", "background", "degrees")))
        op.text = stringValue(firstValue(map, listOf("text", "caption", "title", "label")))
        op.color = stringValue(firstValue(map, listOf("color", "colour", "text_color", "textcolor", "цвет")))
        op.style = stringValue(firstValue(map, listOf("style", "font_style", "стиль")))
        op.x = doubleValue(firstValue(map, listOf("x", "left")))
        op.y = doubleValue(firstValue(map, listOf("y", "top")))
        op.width = doubleValue(firstValue(map, listOf("width", "w")))
        op.height = doubleValue(firstValue(map, listOf("height", "h")))
        op.size = doubleValue(firstValue(map, listOf("size", "font_size", "fontsize", "scale", "max_side", "maxside")))
        op.amount = doubleValue(firstValue(map, listOf("amount", "intensity", "radius", "strength", "angle")))
        op.brightness = doubleValue(firstValue(map, listOf("brightness", "яркость")))
        op.contrast = doubleValue(firstValue(map, listOf("contrast", "контраст")))
        op.saturation = doubleValue(firstValue(map, listOf("saturation", "насыщенность")))
        op.warmth = doubleValue(firstValue(map, listOf("warmth", "temperature", "теплота")))
        if (op.normalizedType == "rotate" && op.amount == null) {
            doubleValue(map["degrees"])?.let { op.amount = it }
        }
        val clockwise = (map["clockwise"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (clockwise != null && op.normalizedType == "rotate" && op.value == null) {
            op.value = if (clockwise) "right" else "left"
        }
        return op
    }

    /** Значение как доля: 50 → 0.5, 0.5 → 0.5. */
    fun fraction(value: Double?): Double? = value?.let { if (abs(it) > 1) it / 100 else it }

    // Проверка и перевод в шаги

    /** Перевести операцию в шаг; бросает понятную ошибку для неизвестных типов и параметров. */
    fun resolve(operation: ImageEditOperation): EditStep {
        return when (val kind = operation.normalizedType) {
            "remove_background" -> EditStep.RemoveBackground
            "background_color", "background_blur", "background_gradient" -> EditStep.Background(backgroundFor(operation, kind))
            "filter" -> {
                val name = when {
                    EditFilter.named(operation.value) != null -> operation.value
                    EditFilter.named(operation.style) != null -> operation.style
                    else -> operation.type
                }
                val filter = EditFilter.named(name) ?: run {
                    val names = EditFilter.entries.joinToString(", ") { it.raw }
                    throw MediaEditingException(
                        "Неизвестный фильтр «${operation.value ?: ""}». Доступны: $names",
                        "Unknown filter '${operation.value ?: ""}'. Available: $names",
                    )
                }
                val intensity = fraction(operation.amount) ?: 1.0
                EditStep.Filter(filter, intensity.coerceIn(0.0, 1.0))
            }
            "adjust" -> resolveAdjust(operation)
            "rotate" -> resolveRotate(operation)
            "flip" -> {
                val word = normalizedWord(operation.value ?: "")
                EditStep.Flip(word.startsWith("vert") || word.startsWith("верт") || word == "y" || word.startsWith("up"))
            }
            "crop" -> {
                val width = operation.width
                val height = operation.height
                if (width != null && height != null) {
                    EditStep.CropRect(fraction(operation.x) ?: 0.0, fraction(operation.y) ?: 0.0,
                        fraction(width) ?: 1.0, fraction(height) ?: 1.0)
                } else {
                    val aspect = CropAspect.named(operation.value ?: operation.style)
                        ?: throw MediaEditingException(
                            "Для обрезки укажите value (square, 4:5, 9:16, 16:9) или x, y, width, height",
                            "crop needs value (square, 4:5, 9:16, 16:9) or x, y, width, height",
                        )
                    EditStep.CropToAspect(aspect)
                }
            }
            "text" -> {
                val content = operation.text ?: operation.value ?: ""
                if (content.isBlank()) {
                    throw MediaEditingException("Для надписи нужен текст (поле text)", "The text operation needs a 'text' field")
                }
                val colorSource = operation.color ?: if (operation.text != null) operation.value else null
                val color = EditColors.parse(colorSource) ?: EditColors.WHITE
                var size = operation.size ?: 0.08
                if (size > 1) size /= 100
                EditStep.Text(
                    content.trim(), fraction(operation.x) ?: 0.5, fraction(operation.y) ?: 0.85, color, size,
                    TextStyle.named(operation.style) ?: TextStyle.OUTLINE,
                )
            }
            "sticker" -> EditStep.Sticker(
                operation.value ?: operation.text ?: "⭐️",
                fraction(operation.x) ?: 0.5, fraction(operation.y) ?: 0.5,
                operation.size ?: operation.amount ?: 1.0,
            )
            "resize" -> {
                val side = operation.size ?: operation.amount ?: operation.width ?: parseNumber(operation.value) ?: 0.0
                if (side < 16) {
                    throw MediaEditingException("Для resize укажите size — длинную сторону в пикселях",
                        "resize needs 'size' — the long side in pixels")
                }
                EditStep.Resize(side.roundToInt())
            }
            else -> {
                val list = ImageEditOperation.supportedTypes.joinToString(", ")
                throw MediaEditingException(
                    "Неизвестная операция «${operation.type}». Поддерживаются: $list",
                    "Unknown operation '${operation.type}'. Supported: $list",
                )
            }
        }
    }

    /** Разобрать и проверить все операции; пустой список — ошибка. */
    fun resolveAll(operationsJson: String?): List<EditStep> {
        val operations = parseOperations(operationsJson)
        if (operations.isEmpty()) throw MediaEditingException.noOperations
        return operations.map { resolve(it) }
    }

    /** null — прозрачный фон. */
    private fun backgroundFor(operation: ImageEditOperation, kind: String): EditBackground? {
        when (kind) {
            "background_blur" -> {
                val radius = operation.amount ?: operation.size ?: parseNumber(operation.value) ?: 25.0
                return EditBackground.Blur(radius.coerceIn(1.0, 120.0))
            }
            "background_gradient" -> {
                val source = operation.value ?: operation.color ?: "#6E8BFF,#C86DD7"
                val colors = source.split(',', ';', '/', '|').mapNotNull { EditColors.parse(it) }
                if (colors.isEmpty()) {
                    throw MediaEditingException("Не понял цвета градиента «$source»", "Could not understand gradient colors '$source'")
                }
                return EditBackground.Gradient(colors)
            }
            else -> {
                val source = operation.color ?: operation.value ?: "white"
                val valueWord = normalizedWord(operation.value ?: "")
                if (valueWord.startsWith("размыт") || valueWord == "blur") return EditBackground.Blur(operation.amount ?: 25.0)
                val color = EditColors.parse(source) ?: throw MediaEditingException(
                    "Не понял цвет «$source». Используйте #RRGGBB или название цвета",
                    "Unknown color '$source'. Use #RRGGBB or a color name",
                )
                if (EditColors.alpha(color) < 0.01) return null
                return EditBackground.Color(color)
            }
        }
    }

    private fun resolveAdjust(operation: ImageEditOperation): EditStep.Adjust {
        val raw = normalizedWord(operation.type)
        var brightness = fraction(operation.brightness) ?: 0.0
        var contrast = fraction(operation.contrast) ?: 0.0
        var saturation = fraction(operation.saturation) ?: 0.0
        var warmth = fraction(operation.warmth) ?: 0.0
        val generic = fraction(operation.amount) ?: fraction(parseNumber(operation.value))
        if (generic != null) {
            if (raw == "brightness" || raw == "яркость") brightness = generic
            if (raw == "contrast" || raw == "контраст") contrast = generic
            if (raw == "saturation" || raw == "насыщенность") saturation = generic
            if (raw == "warmth" || raw == "temperature" || raw == "теплота") warmth = generic
        }
        fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)
        return EditStep.Adjust(clamp(brightness), clamp(contrast), clamp(saturation), clamp(warmth))
    }

    private fun resolveRotate(operation: ImageEditOperation): EditStep.Rotate {
        val word = normalizedWord(operation.value ?: "")
        var degrees = operation.amount ?: word.toDoubleOrNull() ?: 90.0
        if (word.startsWith("left") || word.startsWith("влев") || word.startsWith("против") || word.startsWith("counter") || word == "ccw") {
            degrees = -abs(operation.amount ?: 90.0)
        } else if (word.startsWith("right") || word.startsWith("вправ") || word == "cw" || word.startsWith("clockwise") ||
            word.startsWith("по_час") || word.startsWith("по час")
        ) {
            degrees = abs(operation.amount ?: 90.0)
        }
        // Округление «от нуля», как rounded() в Swift.
        val quarter = degrees / 90
        val steps = (if (quarter < 0) -Math.floor(-quarter + 0.5) else Math.floor(quarter + 0.5)).toInt()
        return EditStep.Rotate(((steps % 4) + 4) % 4)
    }
}

/** Цвета: "#RRGGBB", "#RGB", "#RRGGBBAA", "0x…" или названия (рус./англ.). Результат — ARGB. */
object EditColors {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0

    fun rgb(red: Double, green: Double, blue: Double): Int =
        (0xFF shl 24) or ((red * 255).roundToInt() shl 16) or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt()

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    fun alpha(color: Int): Double = ((color ushr 24) and 0xFF) / 255.0
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    private val english: Map<String, Int> = mapOf(
        "red" to rgb(0.93, 0.22, 0.21), "blue" to rgb(0.18, 0.42, 0.95), "white" to WHITE, "black" to BLACK,
        "green" to rgb(0.2, 0.75, 0.35), "yellow" to rgb(1.0, 0.84, 0.1), "pink" to rgb(1.0, 0.45, 0.7),
        "purple" to rgb(0.58, 0.32, 0.9), "violet" to rgb(0.58, 0.32, 0.9), "orange" to rgb(1.0, 0.58, 0.1),
        "gray" to rgb(0.55, 0.55, 0.57), "grey" to rgb(0.55, 0.55, 0.57), "transparent" to TRANSPARENT,
        "clear" to TRANSPARENT, "none" to TRANSPARENT, "cyan" to rgb(0.3, 0.8, 0.95), "lightblue" to rgb(0.45, 0.75, 1.0),
        "brown" to rgb(0.55, 0.36, 0.22), "gold" to rgb(0.95, 0.75, 0.25), "beige" to rgb(0.96, 0.92, 0.82),
        "navy" to rgb(0.08, 0.14, 0.4),
    )

    private val russian: List<Pair<String, Int>> = listOf(
        "прозрачн" to TRANSPARENT, "красн" to rgb(0.93, 0.22, 0.21), "син" to rgb(0.18, 0.42, 0.95),
        "голуб" to rgb(0.45, 0.75, 1.0), "бел" to WHITE, "черн" to BLACK, "зелен" to rgb(0.2, 0.75, 0.35),
        "желт" to rgb(1.0, 0.84, 0.1), "розов" to rgb(1.0, 0.45, 0.7), "фиолет" to rgb(0.58, 0.32, 0.9),
        "сиренев" to rgb(0.72, 0.55, 0.95), "оранж" to rgb(1.0, 0.58, 0.1), "сер" to rgb(0.55, 0.55, 0.57),
        "коричн" to rgb(0.55, 0.36, 0.22), "золот" to rgb(0.95, 0.75, 0.25), "бежев" to rgb(0.96, 0.92, 0.82),
    )

    fun parse(raw: String?): Int? {
        if (raw == null) return null
        val value = EditParsing.normalizedWord(raw)
        if (value.isEmpty()) return null
        hex(value)?.let { return it }
        english[value.replace(" ", "")]?.let { return it }
        for ((prefix, color) in russian) if (value.startsWith(prefix)) return color
        return null
    }

    private fun hex(value: String): Int? {
        var hex = when {
            value.startsWith("#") -> value.substring(1)
            value.startsWith("0x") -> value.substring(2)
            else -> return null
        }
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6 && hex.length != 8) return null
        val number = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 8) {
            argb((number and 255).toInt(), ((number shr 24) and 255).toInt(), ((number shr 16) and 255).toInt(), ((number shr 8) and 255).toInt())
        } else {
            argb(255, ((number shr 16) and 255).toInt(), ((number shr 8) and 255).toInt(), (number and 255).toInt())
        }
    }

    /** Контрастный цвет текста (чёрный на светлом, белый на тёмном). */
    fun contrasting(color: Int): Int {
        val luminance = 0.299 * red(color) / 255 + 0.587 * green(color) / 255 + 0.114 * blue(color) / 255
        return if (luminance > 0.6) BLACK else WHITE
    }

    /** Светлая сердцевина неоновой надписи. */
    fun neonCore(color: Int): Int {
        val mix = 0.6
        fun channel(c: Int) = (c + (255 - c) * mix).roundToInt().coerceIn(0, 255)
        return argb(255, channel(red(color)), channel(green(color)), channel(blue(color)))
    }
}
