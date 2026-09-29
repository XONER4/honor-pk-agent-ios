package com.honerai.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// Модели повторяют iOS-версию (HonorPKAgent/Core/Models.swift) поле в поле и
// кодируются так же (даты — ISO 8601, идентификаторы — строки UUID), поэтому
// резервная копия с iPhone открывается на Android и наоборот.

/** Новый идентификатор в том же виде, что на iPhone (заглавные буквы). */
fun newId(): String = UUID.randomUUID().toString().uppercase()

/** Даты в формате ISO 8601 без долей секунды, как у JSONEncoder(.iso8601) на iOS. */
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IsoInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }
    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return runCatching { Instant.parse(text) }.getOrElse {
            // На всякий случай — числовая дата (секунды с 2001 года, как Date по умолчанию в Swift).
            text.toDoubleOrNull()?.let { Instant.ofEpochMilli(((it + 978_307_200.0) * 1000).toLong()) } ?: Instant.now()
        }
    }
}

typealias IsoDate = @Serializable(with = IsoInstantSerializer::class) Instant

@Serializable
enum class MessageRole { @SerialName("user") USER, @SerialName("assistant") ASSISTANT, @SerialName("tool") TOOL }

@Serializable
enum class MessageFeedback { @SerialName("like") LIKE, @SerialName("dislike") DISLIKE }

@Serializable
enum class AttachmentKind {
    @SerialName("image") IMAGE, @SerialName("document") DOCUMENT, @SerialName("text") TEXT,
    @SerialName("video") VIDEO, @SerialName("sticker") STICKER, @SerialName("audio") AUDIO
}

@Serializable
enum class MessageInputKind { @SerialName("text") TEXT, @SerialName("voice") VOICE, @SerialName("suggestion") SUGGESTION }

@Serializable
data class MessageAttachment(
    val id: String = newId(),
    val name: String,
    val kind: AttachmentKind,
    val extractedText: String = "",
    /** Абсолютный путь к файлу в папке приложения. */
    val localPath: String? = null,
    val videoFramePaths: List<String>? = null,
    /** Короткое описание под именем файла: «Таблица Excel: 2 листа», «Аудио 1:24». */
    val summary: String? = null,
)

@Serializable
data class WebSource(
    val id: String = newId(),
    val title: String,
    val url: String,
    val snippet: String,
    val content: String? = null,
    val fetchedAt: IsoDate? = null,
    // media: какие поисковики нашли страницу (yandex, google, bing, ddg, brave, wikipedia).
    val engines: List<String>? = null,
) {
    /** Разметка прочитанной страницы: не сохраняется, нужна для поиска картинок. */
    @kotlinx.serialization.Transient
    var rawHTML: String? = null
}

@Serializable
data class GenerationStep(
    val id: String = newId(),
    /** search, read, images, videos, screenshot, weather, draw, chats, memory, settings, contact, table. */
    val kind: String,
    val title: String,
    val detail: String = "",
    val sites: List<String> = emptyList(),
    val done: Boolean = false,
    val startedAt: IsoDate? = null,
)

@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: MessageRole,
    val content: String = "",
    val reasoning: String = "",
    val reasoningSeconds: Int = 0,
    val createdAt: IsoDate = Instant.now(),
    val feedback: MessageFeedback? = null,
    val attachments: List<MessageAttachment> = emptyList(),
    val sources: List<WebSource> = emptyList(),
    val error: String? = null,
    val isInterrupted: Boolean = false,
    val reasoningWasTranslated: Boolean? = null,
    val reasoningStayedForeign: Boolean = false,
    val inputKind: MessageInputKind = MessageInputKind.TEXT,
    /** Реакция пользователя на это сообщение (эмодзи). */
    val reaction: String? = null,
    /** Реакция нейросети на сообщение пользователя (эмодзи). */
    val assistantReaction: String? = null,
    val toolCallID: String? = null,
    /** Вызовы инструментов ответа в JSON — возвращаются в API как есть. */
    val toolCallsRaw: String = "",
    val searchFailed: Boolean = false,
    val continuesInBackground: Boolean? = null,
    val activity: List<GenerationStep>? = null,
    val tableIDs: List<String>? = null,
    /** Фрагмент, который пользователь выделил и процитировал в вопросе. */
    val quote: String? = null,
)

@Serializable
data class ChatInstruction(
    val id: String = newId(),
    val text: String,
    val author: MessageRole = MessageRole.USER,
    val sourceMessageID: String? = null,
    val createdAt: IsoDate = Instant.now(),
)

@Serializable
data class SavedInstruction(
    val id: String = newId(),
    val text: String,
    val savedAt: IsoDate = Instant.now(),
)

@Serializable
data class ChatTable(
    val id: String = newId(),
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val editable: Boolean = true,
    val createdAt: IsoDate = Instant.now(),
    val updatedAt: IsoDate = Instant.now(),
    val editedByUser: Boolean = false,
)

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "Новый чат",
    val messages: List<ChatMessage> = emptyList(),
    val pinned: Boolean = false,
    val updatedAt: IsoDate = Instant.now(),
    val createdAt: IsoDate = Instant.now(),
    val parentConversationID: String? = null,
    val forkedAtMessageID: String? = null,
    val archivedAt: IsoDate? = null,
    val systemPrompt: String = "",
    val pinOrder: Int = 0,
    val instructions: List<ChatInstruction>? = null,
    val tables: List<ChatTable>? = null,
) {
    /** Время последнего сообщения — по нему чаты сортируются в списке. */
    val lastMessageAt: Instant get() = messages.lastOrNull()?.createdAt ?: updatedAt
}

@Serializable
data class HonorMemory(
    val id: String = newId(),
    val text: String,
    val createdAt: IsoDate = Instant.now(),
    val sourceChatID: String? = null,
    val keywords: List<String> = emptyList(),
)

@Serializable
data class UsageStatistics(
    val sentMessages: Int = 0,
    val receivedMessages: Int = 0,
    val totalSessionSeconds: Double = 0.0,
    val voiceMessages: Int = 0,
    val firstLaunch: IsoDate = Instant.now(),
)

/** Архив истории — тот же формат, что у iOS: резервные копии переносятся между телефонами. */
@Serializable
data class HistoryArchive(
    val version: Int = 1,
    val conversations: List<Conversation>,
    val selectedConversationID: String? = null,
    val draft: String = "",
    val attachments: List<MessageAttachment> = emptyList(),
    /** Файлы вложений в base64 по идентификатору вложения. */
    val attachmentFiles: Map<String, String>? = null,
    val attachmentFileReferences: Map<String, String>? = null,
    val attachmentFrameFiles: Map<String, List<String>>? = null,
    val inFlightMessageID: String? = null,
    val memories: List<HonorMemory>? = null,
    val memoryEnabled: Boolean? = null,
    val instructionLibrary: List<SavedInstruction>? = null,
    val appSettings: Map<String, String>? = null,
)

/** Один JSON-кодек на всё приложение: неизвестные поля пропускаются (совместимость версий). */
val HonerJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/** Настройки подключения к нейросети. */
data class DeepSeekConfiguration(
    val apiKey: String,
    val baseURL: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    /** Язык ответов: "ru" (по умолчанию) или "en" — по языку приложения. */
    val language: String = "ru",
)
