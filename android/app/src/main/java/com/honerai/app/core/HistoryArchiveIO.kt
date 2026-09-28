package com.honerai.app.core

import com.honerai.app.data.AttachmentKind
import com.honerai.app.data.HistoryArchive
import com.honerai.app.data.HonerJson
import com.honerai.app.data.MessageRole
import com.honerai.app.data.newId
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors

/** Результат чтения истории с диска. */
class HistoryReadResult(val archive: HistoryArchive? = null, val error: String? = null)

/**
 * Файл истории, резервная копия и импорт — формат тот же, что у iPhone
 * (даты ISO 8601, вложения в base64 в attachmentFiles).
 */
object HistoryArchiveIO {
    const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
    const val MAX_FILES_BYTES = 64L * 1024 * 1024

    fun encode(archive: HistoryArchive): String = HonerJson.encodeToString(HistoryArchive.serializer(), archive)

    fun decode(text: String): HistoryArchive = HonerJson.decodeFromString(HistoryArchive.serializer(), text)

    fun readHistory(file: File): HistoryReadResult {
        if (!file.exists()) return HistoryReadResult()
        return try {
            val loaded = decode(file.readText())
            if (loaded.version != 1) throw HonorError.InvalidArchive()
            HistoryReadResult(loaded)
        } catch (e: Throwable) {
            val backup = File(file.parentFile, file.nameWithoutExtension + ".unreadable-${System.currentTimeMillis() / 1000}.json")
            runCatching { file.copyTo(backup, overwrite = true) }
            HistoryReadResult(error = "Не удалось открыть историю. Исходный файл сохранён отдельно: ${backup.name}.")
        }
    }

    /** Запись через временный файл: история не бывает наполовину записанной. */
    fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    /** Резервная копия с файлами вложений внутри. */
    fun export(snapshot: HistoryArchive, directory: File): File {
        directory.mkdirs()
        val target = File(directory, "Honor-История-${newId().take(8)}.json")
        val files = LinkedHashMap<String, String>()
        val frameFiles = LinkedHashMap<String, List<String>>()
        val references = LinkedHashMap<String, String>()
        val canonical = HashMap<String, String>()
        var totalBytes = 0L
        val all = snapshot.conversations.flatMap { it.messages }.flatMap { it.attachments } + snapshot.attachments
        for (attachment in all) {
            val id = attachment.id
            if (id in files || id in references) continue
            val file = AttachmentFiles.resolve(attachment.localPath) ?: continue
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val known = canonical[path]
            if (known != null) { references[id] = known; continue }
            // Пропавший файл не мешает выгрузить остальную историю.
            val data = runCatching { file.readBytes() }.getOrNull() ?: continue
            totalBytes += data.size
            if (totalBytes > MAX_FILES_BYTES) throw HonorError.ArchiveTooLarge()
            files[id] = data.toByteString().base64()
            if (attachment.kind == AttachmentKind.VIDEO) {
                val frames = mutableListOf<String>()
                for (framePath in attachment.videoFramePaths.orEmpty().take(8)) {
                    val frame = AttachmentFiles.resolve(framePath)?.let { runCatching { it.readBytes() }.getOrNull() } ?: continue
                    totalBytes += frame.size
                    if (totalBytes > MAX_FILES_BYTES) throw HonorError.ArchiveTooLarge()
                    frames.add(frame.toByteString().base64())
                }
                frameFiles[id] = frames
            }
            canonical[path] = id
        }
        val exported = snapshot.copy(attachmentFiles = files, attachmentFileReferences = references, attachmentFrameFiles = frameFiles)
        val text = encode(exported)
        if (text.length > MAX_ARCHIVE_BYTES) throw HonorError.ArchiveTooLarge()
        writeAtomically(target, text)
        return target
    }

    /**
     * Проверка и распаковка архива: чаты с уже существующими id пропускаются,
     * пути к файлам из архива никогда не используются — файлы пишутся заново.
     */
    fun prepareImport(bytes: ByteArray, excluding: Set<String>, attachmentsDirectory: File): HistoryArchive {
        if (bytes.size > MAX_ARCHIVE_BYTES) throw HonorError.ArchiveTooLarge()
        val incoming = try { decode(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { throw HonorError.InvalidArchive() }
        val fileSizes = incoming.attachmentFiles.orEmpty().mapValues { base64Size(it.value) }
        val frameSizes = incoming.attachmentFrameFiles.orEmpty().mapValues { entry -> entry.value.map { base64Size(it) } }
        val memories = incoming.memories.orEmpty()
        val valid = incoming.version == 1 &&
            incoming.conversations.map { it.id }.toSet().size == incoming.conversations.size &&
            incoming.conversations.all { chat -> chat.messages.map { it.id }.toSet().size == chat.messages.size } &&
            memories.size <= ChatLogic.MAXIMUM_MEMORY_COUNT &&
            memories.all { it.text.isNotBlank() && it.text.length <= ChatLogic.MAXIMUM_MEMORY_LENGTH } &&
            fileSizes.values.all { it <= MAX_FILES_BYTES } &&
            frameSizes.values.all { list -> list.size <= 8 && list.all { it <= 8L * 1024 * 1024 } } &&
            fileSizes.values.sum() + frameSizes.values.flatten().sum() <= MAX_FILES_BYTES
        if (!valid) throw HonorError.InvalidArchive()
        attachmentsDirectory.mkdirs()
        val restored = HashMap<String, String>()
        val restoredFrames = HashMap<String, List<String>>()
        val created = mutableListOf<File>()
        val allowed = setOf("jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "md", "csv", "json", "mp4", "mov", "m4v")
        try {
            val conversations = incoming.conversations.filter { it.id !in excluding }.map { chat ->
                chat.copy(messages = chat.messages.map { message ->
                    var updated = message
                    if (message.id == incoming.inFlightMessageID && message.role == MessageRole.ASSISTANT) {
                        updated = updated.copy(isInterrupted = true)
                    }
                    updated.copy(attachments = message.attachments.map { original ->
                        val suffix = original.localPath?.let { File(it).extension.lowercase() }.orEmpty()
                        // Пути из архива никогда не дают права читать локальные файлы.
                        var attachment = original.copy(localPath = null, videoFramePaths = null)
                        val id = attachment.id
                        val canonicalId = incoming.attachmentFileReferences?.get(id) ?: id
                        val existing = restored[canonicalId]
                        if (existing != null) attachment = attachment.copy(localPath = existing)
                        else incoming.attachmentFiles?.get(canonicalId)?.let { encoded ->
                            val data = encoded.decodeBase64()?.toByteArray() ?: throw HonorError.InvalidArchive()
                            val ext = if (suffix in allowed) suffix else if (attachment.kind == AttachmentKind.IMAGE) "jpg" else "txt"
                            val target = File(attachmentsDirectory, "${newId()}.$ext")
                            target.writeBytes(data)
                            created.add(target)
                            restored[canonicalId] = target.path
                            attachment = attachment.copy(localPath = target.path)
                        }
                        if (attachment.kind == AttachmentKind.VIDEO) {
                            val paths = restoredFrames[canonicalId]
                            if (paths != null) attachment = attachment.copy(videoFramePaths = paths)
                            else incoming.attachmentFrameFiles?.get(canonicalId)?.let { frames ->
                                val list = frames.map { encoded ->
                                    val data = encoded.decodeBase64()?.toByteArray() ?: throw HonorError.InvalidArchive()
                                    val target = File(attachmentsDirectory, "${newId()}.jpg")
                                    target.writeBytes(data)
                                    created.add(target)
                                    target.path
                                }
                                restoredFrames[canonicalId] = list
                                attachment = attachment.copy(videoFramePaths = list)
                            }
                        }
                        attachment
                    })
                })
            }
            return incoming.copy(conversations = conversations, attachmentFiles = null, attachmentFileReferences = null, attachmentFrameFiles = null)
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        }
    }

    private fun base64Size(encoded: String): Long = encoded.length.toLong() * 3 / 4
}

/**
 * Один последовательный писатель: снимки истории склеиваются (пишется только
 * последний), кодирование и запись — не на главном потоке.
 */
class HistoryPersistence(private val file: File) {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "honer-history").apply { isDaemon = true } }
    private val pending = AtomicReference<Pair<HistoryArchive, (Throwable) -> Unit>?>(null)
    private val draining = AtomicBoolean(false)
    private val lock = Any()

    fun enqueue(archive: HistoryArchive, onError: (Throwable) -> Unit) {
        pending.set(archive to onError)
        if (draining.compareAndSet(false, true)) executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next = pending.getAndSet(null)
            if (next == null) {
                draining.set(false)
                // Снимок мог прийти между проверкой и сбросом флага.
                if (pending.get() != null && draining.compareAndSet(false, true)) continue
                return
            }
            try { write(next.first) } catch (e: Throwable) { next.second(e) }
        }
    }

    /** Запись сейчас: встаёт в ту же очередь, чтобы более старый снимок не записался поверх. */
    fun saveSynchronously(archive: HistoryArchive) {
        pending.set(null)
        try {
            executor.submit { write(archive) }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun write(archive: HistoryArchive) {
        val text = HistoryArchiveIO.encode(archive)
        synchronized(lock) { HistoryArchiveIO.writeAtomically(file, text) }
    }
}

/** Простое хранилище флагов (SharedPreferences на Android, словарь в тестах). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class MemoryKeyValueStore : KeyValueStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

class SharedPrefsStore(context: android.content.Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("honer.chat", android.content.Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
}
