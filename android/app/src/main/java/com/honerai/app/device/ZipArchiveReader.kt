package com.honerai.app.device

import java.util.zip.DataFormatException
import java.util.zip.Inflater

// Порт ZipArchiveReader из DocumentReader.swift: свой разбор ZIP (stored и deflate,
// без ZIP64 и шифрования). Свой, а не java.util.zip.ZipInputStream, чтобы проверять
// заявленные размеры ДО распаковки (защита от «ZIP-бомб») и читать части по имени.

/** Ошибки чтения документов — тексты как на iPhone. */
enum class DocumentReaderError(val text: String) {
    INVALID_ARCHIVE("Не удалось открыть документ: файл повреждён / Could not open the document: the file is damaged."),
    LEGACY_OR_ENCRYPTED("Документ защищён паролем или сохранён в старом формате (.doc, .xls, .ppt). Сохраните его как .docx, .xlsx или .pptx / The document is password protected or uses a legacy format."),
    ARCHIVE_TOO_LARGE("Документ слишком большой после распаковки / The document is too large when unpacked."),
    UNSUPPORTED_COMPRESSION("Документ сжат неподдерживаемым способом / The document uses unsupported compression."),
    MISSING_CONTENT("Не удалось открыть документ: содержимое не найдено / Could not open the document: content not found."),
    UNREADABLE_TEXT("Не удалось прочитать текст файла / Could not decode this text file."),
    EMPTY_DOCUMENT("В документе не удалось найти текст / No readable text found in this document."),
    UNSUPPORTED_FORMAT("Этот тип файла не поддерживается / Unsupported file type."),
}

class DocumentReaderException(val error: DocumentReaderError) : Exception(error.text)

object ZipArchiveReader {
    const val MAXIMUM_ENTRIES = 2_000
    const val MAXIMUM_ENTRY_BYTES = 60L * 1024 * 1024
    const val MAXIMUM_TOTAL_BYTES = 200L * 1024 * 1024

    class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    ) {
        val isEncrypted: Boolean get() = flags and 0x1 != 0
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    /** Оглавление архива; [read] считает распакованные байты против общего лимита. */
    class Archive(val data: ByteArray) {
        val entries: List<Entry> = centralDirectory(data)
        private val byName = HashMap<String, Int>()
        private val byLowercasedName = HashMap<String, Int>()
        var totalBytesRead: Long = 0
            private set

        init {
            entries.forEachIndexed { index, entry ->
                byName.putIfAbsent(entry.name, index)
                byLowercasedName.putIfAbsent(entry.name.lowercase(), index)
            }
        }

        val names: List<String> get() = entries.filter { !it.isDirectory }.map { it.name }

        fun contains(path: String): Boolean = indexOf(path) != null

        private fun indexOf(path: String): Int? {
            val key = normalizedPath(path)
            return byName[key] ?: byLowercasedName[key.lowercase()]
        }

        /** null — файла нет; исключение — повреждение или превышение лимитов. */
        fun read(path: String): ByteArray? {
            val position = indexOf(path) ?: return null
            val entry = entries[position]
            if (entry.isDirectory) return null
            if (entry.uncompressedSize > MAXIMUM_ENTRY_BYTES || totalBytesRead + entry.uncompressedSize > MAXIMUM_TOTAL_BYTES) {
                throw DocumentReaderException(DocumentReaderError.ARCHIVE_TOO_LARGE)
            }
            val content = extract(entry, data)
            totalBytesRead += content.size
            return content
        }
    }

    fun names(data: ByteArray): List<String> = Archive(data).names

    fun read(path: String, data: ByteArray): ByteArray? = Archive(data).read(path)

    fun entries(data: ByteArray): Map<String, ByteArray> {
        val archive = Archive(data)
        val result = LinkedHashMap<String, ByteArray>()
        for (name in archive.names) archive.read(name)?.let { result[name] = it }
        return result
    }

    private fun invalid(): Nothing = throw DocumentReaderException(DocumentReaderError.INVALID_ARCHIVE)

    /** Разбор End Of Central Directory и записей центрального каталога. */
    fun centralDirectory(data: ByteArray): List<Entry> {
        val count = data.size
        if (count < 22) invalid()
        var endRecord = -1
        var position = count - 22
        val lowest = maxOf(0, count - 22 - 65_535)
        while (position >= lowest) {
            if (le32(data, position) == 0x0605_4b50L) {
                val commentLength = le16(data, position + 20)
                if (commentLength != null && position + 22 + commentLength <= count) {
                    endRecord = position
                    break
                }
            }
            position -= 1
        }
        if (endRecord < 0) invalid()
        val totalEntries = le16(data, endRecord + 10) ?: invalid()
        val directorySize = le32(data, endRecord + 12) ?: invalid()
        val directoryOffset = le32(data, endRecord + 16) ?: invalid()
        // ZIP64 не поддерживаем
        if (totalEntries == 0xFFFF || directorySize == 0xFFFF_FFFFL || directoryOffset == 0xFFFF_FFFFL) invalid()
        if (totalEntries > MAXIMUM_ENTRIES) throw DocumentReaderException(DocumentReaderError.ARCHIVE_TOO_LARGE)
        val directoryEnd = directoryOffset + directorySize
        if (directoryEnd > endRecord) invalid()

        val result = ArrayList<Entry>(totalEntries)
        var offset = directoryOffset.toInt()
        repeat(totalEntries) {
            if (offset + 46 > directoryEnd || le32(data, offset) != 0x0201_4b50L) invalid()
            val flags = le16(data, offset + 8) ?: invalid()
            val method = le16(data, offset + 10) ?: invalid()
            val compressedSize = le32(data, offset + 20) ?: invalid()
            val uncompressedSize = le32(data, offset + 24) ?: invalid()
            val nameLength = le16(data, offset + 28) ?: invalid()
            val extraLength = le16(data, offset + 30) ?: invalid()
            val commentLength = le16(data, offset + 32) ?: invalid()
            val localOffset = le32(data, offset + 42) ?: invalid()
            val nameStart = offset + 46
            if (nameStart + nameLength > directoryEnd) invalid()
            val rawName = decodeName(data, nameStart, nameLength)
            result.add(Entry(normalizedPath(rawName), method, flags, compressedSize, uncompressedSize, localOffset))
            offset = nameStart + nameLength + extraLength + commentLength
        }
        return result
    }

    private fun decodeName(data: ByteArray, start: Int, length: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(data, start, length)).toString()
        } catch (_: Exception) {
            String(data, start, length, Charsets.ISO_8859_1)
        }
    }

    /** Данные записи: смещение берём из локального заголовка (его name/extra могут отличаться от центральных). */
    fun extract(entry: Entry, data: ByteArray): ByteArray {
        if (entry.isEncrypted) throw DocumentReaderException(DocumentReaderError.LEGACY_OR_ENCRYPTED)
        if (entry.uncompressedSize > MAXIMUM_ENTRY_BYTES) throw DocumentReaderException(DocumentReaderError.ARCHIVE_TOO_LARGE)
        if (entry.localHeaderOffset > Int.MAX_VALUE) invalid()
        val local = entry.localHeaderOffset.toInt()
        if (le32(data, local) != 0x0403_4b50L) invalid()
        val nameLength = le16(data, local + 26) ?: invalid()
        val extraLength = le16(data, local + 28) ?: invalid()
        val start = local.toLong() + 30 + nameLength + extraLength
        val end = start + entry.compressedSize
        if (start < 0 || start > end || end > data.size) invalid()
        return when (entry.method) {
            0 -> {
                if (entry.compressedSize != entry.uncompressedSize) invalid()
                data.copyOfRange(start.toInt(), end.toInt())
            }
            8 -> inflate(data, start.toInt(), (end - start).toInt(), entry.uncompressedSize.toInt())
            else -> throw DocumentReaderException(DocumentReaderError.UNSUPPORTED_COMPRESSION)
        }
    }

    /** «Сырой» DEFLATE без заголовка zlib, как в ZIP. */
    private fun inflate(source: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
        if (expectedSize == 0) return ByteArray(0)
        if (length <= 0) invalid()
        // С nowrap zlib просит лишний «пустой» байт в конце входа.
        val input = ByteArray(length + 1)
        System.arraycopy(source, offset, input, 0, length)
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val output = ByteArray(expectedSize)
            var written = 0
            while (written < expectedSize) {
                val n = inflater.inflate(output, written, expectedSize - written)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                }
                written += n
            }
            if (written != expectedSize) invalid()
            return output
        } catch (_: DataFormatException) {
            invalid()
        } finally {
            inflater.end()
        }
    }

    /** Единый вид путей: прямые слэши, без "./" и "..". */
    fun normalizedPath(path: String): String {
        val unified = path.replace('\\', '/')
        val parts = ArrayList<String>()
        for (component in unified.split('/')) {
            if (component.isEmpty() || component == ".") continue
            if (component == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                continue
            }
            parts.add(component)
        }
        val joined = parts.joinToString("/")
        return if (unified.endsWith("/") && joined.isNotEmpty()) "$joined/" else joined
    }

    fun le16(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > data.size) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun le32(data: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > data.size) return null
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}
