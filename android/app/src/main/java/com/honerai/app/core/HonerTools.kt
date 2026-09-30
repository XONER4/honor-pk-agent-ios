package com.honerai.app.core

import com.honerai.app.data.ChatTable
import com.honerai.app.data.MessageAttachment
import com.honerai.app.data.WebSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Инструменты, которые модель вызывает сама (function calling): приложение отправляет
 * список функций, модель возвращает tool_calls, приложение выполняет и отдаёт результат.
 */
enum class HonerTool(val rawValue: String) {
    COPY_TO_CLIPBOARD("copy_to_clipboard"),
    GET_CURRENT_DATETIME("get_current_datetime"),
    GET_DEVICE_INFO("get_device_info"),
    SUMMARIZE_CHAT("summarize_chat_stats"),
    LIST_CHATS("list_chats"),
    READ_CHAT("read_chat"),
    RENAME_CHAT("rename_chat"),
    PIN_CHAT("pin_chat"),
    SEND_TO_CHAT("send_message_to_chat"),
    SAVE_MEMORY("save_memory"),
    SET_APP_SETTING("set_app_setting"),
    GET_APP_SETTINGS("get_app_settings"),
    DRAW_IMAGE("draw_image"),
    FIND_CONTACT("find_contact"),
    START_GAME("start_game"),
    WEB_SEARCH("web_search"),
    OPEN_PAGE("open_page"),
    FIND_IMAGES("find_images"),
    FIND_VIDEOS("find_videos"),
    SCREENSHOT_PAGE("screenshot_page"),
    GET_WEATHER("get_weather"),
    CREATE_TABLE("create_table"),
    UPDATE_TABLE("update_table"),
    READ_TABLE("read_table"),
    LIST_MEMORY("list_memory"),
    UPDATE_MEMORY("update_memory"),
    DELETE_MEMORY("delete_memory"),
    READ_MANY_PAGES("read_many_pages"),
    YOUTUBE_SEARCH("youtube_search"),
    YOUTUBE_VIDEO("youtube_video"),
    GITHUB("github"),
    MARKETPLACE_SEARCH("marketplace_search"),
    VK_PAGE("vk_page"),
    TELEGRAM_CHANNEL("telegram_channel"),
    VIEW_IMAGE("view_image"),
    TRANSCRIBE_MEDIA("transcribe_media"),
    EDIT_IMAGE("edit_image"),
    // media: приложения на телефоне и медиа в чат.
    OPEN_APP("open_app"),
    SEND_MEDIA("send_media"),
    // extras: инструменты устройства (extras/device/DeviceToolSchemas.kt).
    SET_ALARM("set_alarm"),
    SET_TIMER("set_timer"),
    TAKE_SCREENSHOT("take_screenshot"),
    SCREEN_RECORDING("start_screen_recording"),
    SYSTEM_HEALTH("system_health"),
    PHONE_DATA("phone_data"),
    // agent: действия в приложениях через службу специальных возможностей.
    RUN_DEVICE_TASK("run_device_task"),
    CONFIRM_PENDING_ACTION("confirm_pending_action"),
    CLOUD_TASK("cloud_task"),
    // integ: GitHub через официальный API по токену пользователя.
    GITHUB_REPOS("github_repos"),
    GITHUB_READ_FILE("github_read_file"),
    GITHUB_LIST("github_list"),
    GITHUB_WRITE_FILE("github_write_file"),
    GITHUB_SEARCH_CODE("github_search_code"),
    GITHUB_CREATE_REPO("github_create_repo");

    /** Инструмент ходит в интернет (доступен только с кнопкой «Поиск»). */
    val isWeb: Boolean
        get() = this in setOf(WEB_SEARCH, OPEN_PAGE, FIND_IMAGES, FIND_VIDEOS, SCREENSHOT_PAGE, GET_WEATHER,
            READ_MANY_PAGES, YOUTUBE_SEARCH, YOUTUBE_VIDEO, GITHUB, MARKETPLACE_SEARCH, VK_PAGE, TELEGRAM_CHANNEL,
            SEND_MEDIA) // media: send_media ищет в интернете

    /**
     * Интернет по прямой просьбе пользователя (прислать фото, видео, музыку, погоду, открыть ссылку,
     * YouTube, маркетплейсы) — работает и без кнопки «Поиск»; её выключает только родительский контроль.
     * Кнопка «Поиск» управляет самим поиском по сайтам (web_search, read_many_pages).
     */
    val isOnDemand: Boolean
        get() = this in setOf(FIND_IMAGES, FIND_VIDEOS, SEND_MEDIA, GET_WEATHER, SCREENSHOT_PAGE, OPEN_PAGE,
            YOUTUBE_SEARCH, YOUTUBE_VIDEO, GITHUB, MARKETPLACE_SEARCH, VK_PAGE, TELEGRAM_CHANNEL)

    /** Новые инструменты (ExtraTools). */
    val isExtra: Boolean
        get() = this in setOf(CREATE_TABLE, UPDATE_TABLE, READ_TABLE, LIST_MEMORY, UPDATE_MEMORY, DELETE_MEMORY,
            READ_MANY_PAGES, YOUTUBE_SEARCH, YOUTUBE_VIDEO, GITHUB, MARKETPLACE_SEARCH, VK_PAGE, TELEGRAM_CHANNEL,
            VIEW_IMAGE, TRANSCRIBE_MEDIA, EDIT_IMAGE) ||
            com.honerai.app.extras.device.DeviceToolSchemas.isDeviceTool(this) // extras

    /** integ: инструменты GitHub через официальный API по токену пользователя. */
    val isGitHub: Boolean get() = com.honerai.app.core.github.GitHubToolSchemas.isGitHubTool(this)

    /** Выполняется асинхронно (сеть, контакты, медиа). */
    val isAsync: Boolean get() = isWeb || this == FIND_CONTACT || this == OPEN_APP /* media */ || this == VIEW_IMAGE || this == TRANSCRIBE_MEDIA || this == EDIT_IMAGE ||
        this == RUN_DEVICE_TASK /* agent */ || isGitHub /* integ */ ||
        com.honerai.app.extras.device.DeviceToolSchemas.isDeviceTool(this) // extras

    /** Описание для API: имя, назначение и параметры в формате JSON Schema. */
    val schema: JsonObject
        get() {
            if (com.honerai.app.core.agent.AgentToolSchemas.isAgentTool(this)) return com.honerai.app.core.agent.AgentToolSchemas.schema(this) // agent
            if (isGitHub) return com.honerai.app.core.github.GitHubToolSchemas.schema(this) // integ
            if (isExtra) return ExtraToolSchemas.schema(this)
            if (this == OPEN_APP || this == SEND_MEDIA) return MediaToolSchemas.schema(this) // media:
            return when (this) {
                COPY_TO_CLIPBOARD -> ToolSchema.function(rawValue, "Кладёт переданный текст в буфер обмена телефона. Используй, когда пользователь просит скопировать что-то, чтобы вставить в другом приложении.",
                    mapOf("text" to ToolSchema.string("Текст для копирования")), listOf("text"))
                GET_CURRENT_DATETIME -> ToolSchema.function(rawValue, "Возвращает точные текущие дату, время, день недели и часовой пояс устройства.", emptyMap(), emptyList())
                GET_DEVICE_INFO -> ToolSchema.function(rawValue, "Возвращает модель телефона, версию Android и версию приложения Honer AI.", emptyMap(), emptyList())
                SUMMARIZE_CHAT -> ToolSchema.function(rawValue, "Возвращает статистику текущего чата: сколько сообщений, сколько голосовых, когда начался и когда было последнее.", emptyMap(), emptyList())
                LIST_CHATS -> ToolSchema.function(rawValue, "Показывает список всех чатов пользователя: номер, название, число сообщений, дата последнего сообщения, закреплён ли чат. Вызывай, когда пользователь спрашивает про свои чаты, просит найти чат или поработать с другим чатом.", emptyMap(), emptyList())
                READ_CHAT -> ToolSchema.function(rawValue, "Читает содержимое другого чата пользователя по его номеру из list_chats. Возвращает последние сообщения с ролями.",
                    mapOf("number" to ToolSchema.integer("Номер чата из списка list_chats"),
                        "messages" to ToolSchema.integer("Сколько последних сообщений вернуть, по умолчанию 40")), listOf("number"))
                RENAME_CHAT -> ToolSchema.function(rawValue, "Переименовывает чат пользователя по номеру из list_chats.",
                    mapOf("number" to ToolSchema.integer("Номер чата из списка list_chats"),
                        "title" to ToolSchema.string("Новое название чата")), listOf("number", "title"))
                PIN_CHAT -> ToolSchema.function(rawValue, "Закрепляет или открепляет чат пользователя по номеру из list_chats.",
                    mapOf("number" to ToolSchema.integer("Номер чата из списка list_chats"),
                        "pinned" to ToolSchema.boolean("true — закрепить, false — открепить")), listOf("number", "pinned"))
                SEND_TO_CHAT -> ToolSchema.function(rawValue, "Отправляет сообщение в другой чат пользователя по номеру из list_chats. Пиши от себя, коротко и по делу. Пользователь увидит сообщение в том чате.",
                    mapOf("number" to ToolSchema.integer("Номер чата из списка list_chats"),
                        "text" to ToolSchema.string("Текст сообщения")), listOf("number", "text"))
                SAVE_MEMORY -> ToolSchema.function(rawValue, "Сам сохраняет важный факт о пользователе в память Honer AI. Используй, когда узнал устойчивый факт: имя, город, профессию, предпочтения, постоянные требования к ответам.",
                    mapOf("text" to ToolSchema.string("Факт одной короткой фразой, например «Живёт в Новосибирске»")), listOf("text"))
                GET_APP_SETTINGS -> ToolSchema.function(rawValue, "Показывает текущие настройки приложения у пользователя: включены ли рассуждение, поиск, озвучивание, уведомления, стикеры, тема, язык, размер шрифта, голос, имя в профиле, число записей памяти. Вызывай, когда пользователь спрашивает про свои настройки или когда от них зависит ответ.", emptyMap(), emptyList())
                DRAW_IMAGE -> ToolSchema.function(rawValue, "Рисует картинку по описанию (иллюстрация, арт, логотип, пейзаж, персонаж) и возвращает строку для вставки в ответ. Описание пиши на английском — так качество выше. Вставь возвращённую строку ![…](…) в ответ без изменений.",
                    mapOf("prompt" to ToolSchema.string("Подробное описание картинки на английском: объект, стиль, цвета, композиция"),
                        "orientation" to ToolSchema.string("square, portrait или landscape; по умолчанию square")), listOf("prompt"))
                START_GAME -> ToolSchema.function(rawValue, "Открывает на экране пользователя мини-игру против тебя: chess (шахматы), checkers (русские шашки), durak (дурак подкидной), slots (игровой автомат «Удача»). Вызывай, когда пользователь хочет поиграть.",
                    mapOf("game" to ToolSchema.string("chess, checkers, durak или slots")), listOf("game"))
                FIND_CONTACT -> ToolSchema.function(rawValue, "Находит контакт в телефонной книге пользователя по имени: телефоны, почту, организацию, день рождения. Работает, если пользователь разрешил доступ к контактам.",
                    mapOf("name" to ToolSchema.string("Имя или фамилия")), listOf("name"))
                WEB_SEARCH -> ToolSchema.function(rawValue, "Ищет в интернете сразу в нескольких поисковиках (Яндекс, Google, Bing, DuckDuckGo, Brave, Википедия) и читает найденные страницы. Вызывай, когда нужны свежие или точные данные, которых ты не знаешь наверняка: новости, цены, курсы, события, расписания, характеристики, факты о малоизвестном. Для общих знаний, расчётов, кода, советов и болтовни поиск не нужен.",
                    mapOf("query" to ToolSchema.string("Поисковый запрос: только тема, без слов-команд")), listOf("query"))
                OPEN_PAGE -> ToolSchema.function(rawValue, "Открывает и читает страницу по ссылке, как браузер: выполняет JavaScript, читает публичные каналы Telegram (t.me/…), страницы ВКонтакте, новости, документацию. Возвращает текст страницы и картинки на ней.",
                    mapOf("url" to ToolSchema.string("Полная ссылка https://…; для Telegram можно t.me/имя_канала")), listOf("url"))
                FIND_IMAGES -> ToolSchema.function(rawValue, "Находит в интернете настоящие фотографии и изображения по теме и возвращает строки для вставки в ответ. Вызывай, когда пользователь просит показать фото, картинку, как что-то выглядит. Подписи в строках ![подпись](ссылка) пиши на русском.",
                    mapOf("query" to ToolSchema.string("Что должно быть на изображении; лучше на английском"),
                        "count" to ToolSchema.integer("Сколько изображений, 1–6, по умолчанию 3")), listOf("query"))
                FIND_VIDEOS -> ToolSchema.function(rawValue, "Находит видео (YouTube и другие) по теме и возвращает строки для вставки в ответ: приложение покажет видео с кнопкой воспроизведения прямо в чате.",
                    mapOf("query" to ToolSchema.string("Тема видео"),
                        "count" to ToolSchema.integer("Сколько видео, 1–4, по умолчанию 2")), listOf("query"))
                SCREENSHOT_PAGE -> ToolSchema.function(rawValue, "Делает скриншот страницы сайта и возвращает строку для вставки в ответ — пользователь увидит, как выглядит страница.",
                    mapOf("url" to ToolSchema.string("Полная ссылка https://…")), listOf("url"))
                GET_WEATHER -> ToolSchema.function(rawValue, "Возвращает текущую погоду и прогноз на 7 дней для города (Open-Meteo).",
                    mapOf("city" to ToolSchema.string("Город, например «Клин» или «Москва»")), listOf("city"))
                SET_APP_SETTING -> ToolSchema.function(rawValue, "Меняет настройку приложения. Доступно: reasoning (рассуждение), search (поиск), autoRead (озвучивать ответы), fontScale (размер шрифта), notifications (уведомления).",
                    mapOf("name" to ToolSchema.string("Название настройки: reasoning, search, autoRead, notifications или fontScale"),
                        "value" to ToolSchema.string("Новое значение: true/false для переключателей, число для размера шрифта")), listOf("name", "value"))
                else -> ExtraToolSchemas.schema(this)
            }
        }

    companion object {
        private val byRaw = entries.associateBy { it.rawValue }
        fun from(raw: String): HonerTool? = byRaw[raw]

        val apiSchemas: List<JsonObject> get() = entries.map { it.schema }

        /**
         * Инструменты для запроса: поиск по сайтам — с кнопкой «Поиск», интернет по прямой просьбе — всегда,
         * если его не запретил родительский контроль ([onDemandAllowed]); выключенные интеграции не передаются.
         */
        fun schemas(searchEnabled: Boolean, onDemandAllowed: Boolean = true, allows: (String) -> Boolean = { true }): List<JsonObject> =
            entries.filter { (searchEnabled || !it.isWeb || (onDemandAllowed && it.isOnDemand)) && allows(it.rawValue) }.map { it.schema }
    }
}

/** Построение схем инструментов. */
object ToolSchema {
    fun function(name: String, description: String, properties: Map<String, JsonObject>, required: List<String>): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(properties))
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                })
            })
        }

    fun string(description: String? = null) = typed("string", description)
    fun integer(description: String? = null) = typed("integer", description)
    fun boolean(description: String? = null) = typed("boolean", description)

    fun array(items: JsonObject, description: String? = null): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
        if (description != null) put("description", description)
    }

    private fun typed(type: String, description: String?): JsonObject = buildJsonObject {
        put("type", type)
        if (description != null) put("description", description)
    }
}

/** Один вызов инструмента, собранный из потока (аргументы приходят кусками). */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: String,
    /** Номер вызова в потоке: по нему склеиваются куски аргументов. */
    val index: Int? = null,
) {
    /** Разобранные аргументы вызова. */
    val parsedArguments: JsonObject get() = parseJson(arguments) as? JsonObject ?: JsonObject(emptyMap())
}

/**
 * Терпимое чтение аргументов: номер чата приходит то числом, то строкой («3»),
 * флаг — то true, то «true» или «да».
 */
object ToolArgument {
    fun int(value: JsonElement?): Int? {
        val p = value as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) {
            val digits = p.content.trim().trim('#', '№', '.', ' ')
            return digits.toIntOrNull()
        }
        p.longOrNull?.let { return it.toInt() }
        val number = p.doubleOrNull ?: return null
        return if (number == Math.rint(number)) number.toInt() else null
    }

    fun bool(value: JsonElement?): Boolean? {
        val p = value as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) {
            p.booleanOrNull?.let { return it }
            p.longOrNull?.let { return it != 0L }
            return null
        }
        return when (p.content.trim().lowercase()) {
            "true", "1", "yes", "да", "вкл", "on" -> true
            "false", "0", "no", "нет", "выкл", "off" -> false
            else -> null
        }
    }

    fun string(value: JsonElement?): String? {
        val p = value as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        p.booleanOrNull?.let { return if (it) "true" else "false" }
        p.longOrNull?.let { return it.toString() }
        val number = p.doubleOrNull ?: return p.content
        return if (number == Math.rint(number) && Math.abs(number) < 1e15) number.toLong().toString() else number.toString()
    }
}

/** Обзор одного чата для списка, который видит модель. */
data class ChatOverview(
    val number: Int,
    /** Устойчивый идентификатор: номер в списке меняется, действия идут по id. */
    val id: String = com.honerai.app.data.newId(),
    val title: String,
    val messageCount: Int,
    val lastMessageAt: Instant?,
    val pinned: Boolean,
    val archived: Boolean,
    val preview: String,
)

/** Строка чата для read_chat. */
data class ChatTranscriptLine(val role: String, val text: String)

data class MemoryRef(val id: String, val text: String)

/** Данные приложения, нужные инструментам. */
data class ToolExecutionContext(
    val deviceModel: String = "Android",
    val systemVersion: String = "",
    val appVersion: String = "",
    val messageCount: Int = 0,
    val voiceMessageCount: Int = 0,
    val chatStartedAt: Instant? = null,
    val lastMessageAt: Instant? = null,
    val chats: List<ChatOverview> = emptyList(),
    val transcripts: Map<Int, List<ChatTranscriptLine>> = emptyMap(),
    val settingsSummary: String = "",
    /** Таблицы текущего чата (T1, T2…). */
    val tables: List<ChatTable> = emptyList(),
    /** Факты памяти по порядку (номера для list_memory). */
    val memoryItems: List<MemoryRef> = emptyList(),
    /** Вложения текущего чата: фото для редактора и просмотра, записи для расшифровки. */
    val chatAttachments: List<MessageAttachment> = emptyList(),
    /** Клиент нейросети для просмотра изображений. */
    val visionClient: DeepSeekStreaming? = null,
    /** Буфер обмена (на Android — ClipboardManager). */
    val clipboard: ((String) -> Unit)? = null,
    /** Папка для файлов, созданных инструментами (отредактированные фото). */
    val outputDirectory: java.io.File? = null,
    val cacheDirectory: java.io.File? = null,
)

/** Что приложение должно сделать по просьбе модели. */
sealed class ToolEffect {
    data class SendToChat(val number: Int, val text: String) : ToolEffect()
    data class SaveMemory(val text: String) : ToolEffect()
    data class SetSetting(val name: String, val value: String) : ToolEffect()
    data class RenameChat(val id: String, val title: String) : ToolEffect()
    data class PinChat(val id: String, val pinned: Boolean) : ToolEffect()
    data class SendToChatID(val id: String, val text: String) : ToolEffect()
    /** Прочитанные страницы: карточка «Источники ответа». */
    data class AddSources(val sources: List<WebSource>) : ToolEffect()
    data class OpenGame(val raw: String) : ToolEffect()
    data class CreateTable(val table: ChatTable) : ToolEffect()
    data class ReplaceTable(val table: ChatTable) : ToolEffect()
    data class UpdateMemory(val id: String, val text: String) : ToolEffect()
    data class DeleteMemory(val id: String) : ToolEffect()
    /** Файл (например, отредактированное фото) под ответом. */
    data class AttachFile(val attachment: MessageAttachment) : ToolEffect()
}

/** Результат инструмента, который уходит обратно в модель. */
data class ToolCallResult(
    val callID: String,
    val name: String,
    val content: String,
    val effect: ToolEffect? = null,
)

/** Мини-игры: разбор названия, которое прислала модель. */
object GameKinds {
    val all = listOf("chess", "checkers", "durak", "slots")

    fun from(raw: String): String? {
        val value = raw.lowercase()
        if (value.contains("chess") || value.contains("шахмат")) return "chess"
        if (value.contains("checker") || value.contains("draught") || value.contains("шашк")) return "checkers"
        if (value.contains("durak") || value.contains("дурак") || value.contains("карт")) return "durak"
        if (value.contains("slot") || value.contains("казино") || value.contains("удач") || value.contains("рулет")) return "slots"
        return null
    }

    fun title(raw: String): String = when (raw) {
        "chess" -> "Шахматы"
        "checkers" -> "Шашки"
        "durak" -> "Дурак"
        "slots" -> "Удача"
        else -> raw
    }
}

private val russian = Locale("ru", "RU")

internal fun formatRussian(instant: Instant, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, russian).format(ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()))

/** Инструменты без сети: время, устройство, чаты, память, настройки, рисунок, игра. */
object ToolExecutor {
    private fun reply(call: ToolCallRequest, text: String, effect: ToolEffect? = null) =
        ToolCallResult(call.id, call.name, text, effect)

    fun execute(call: ToolCallRequest, context: ToolExecutionContext): ToolCallResult {
        val arguments = call.parsedArguments
        return when (HonerTool.from(call.name)) {
            HonerTool.COPY_TO_CLIPBOARD -> {
                val text = arguments["text"].str.orEmpty()
                if (text.isEmpty()) return reply(call, "Не удалось скопировать: текст не передан.")
                val clipboard = context.clipboard ?: return reply(call, "Буфер обмена сейчас недоступен.")
                runCatching { clipboard(text) }.onFailure { return reply(call, "Не удалось скопировать в буфер обмена.") }
                reply(call, "Скопировано в буфер обмена: ${text.take(200)}")
            }
            HonerTool.GET_CURRENT_DATETIME -> {
                val now = ZonedDateTime.now()
                val text = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss", russian).format(now)
                reply(call, "Сейчас $text (${now.zone.id}).")
            }
            HonerTool.GET_DEVICE_INFO ->
                reply(call, "Устройство: ${context.deviceModel}. Система: ${context.systemVersion}. Приложение: Honer AI ${context.appVersion}.")
            HonerTool.SUMMARIZE_CHAT -> {
                val parts = mutableListOf("Сообщений в чате: ${context.messageCount}", "Из них голосовых: ${context.voiceMessageCount}")
                context.chatStartedAt?.let { parts.add("Начат: ${formatRussian(it, "d MMMM, HH:mm")}") }
                context.lastMessageAt?.let { parts.add("Последнее сообщение: ${formatRussian(it, "d MMMM, HH:mm")}") }
                reply(call, parts.joinToString(". ") + ".")
            }
            HonerTool.START_GAME -> {
                val kind = GameKinds.from(ToolArgument.string(arguments["game"]).orEmpty())
                    ?: return reply(call, "Доступны игры: шахматы, шашки, дурак, «Удача». Спроси, во что сыграть.")
                reply(call, "Игра «${GameKinds.title(kind)}» открыта на экране пользователя. Коротко и весело пожелай удачи.", ToolEffect.OpenGame(kind))
            }
            HonerTool.GET_APP_SETTINGS -> {
                val summary = context.settingsSummary.ifEmpty { "Настройки сейчас недоступны." }
                reply(call, "Настройки пользователя в приложении Honer AI:\n$summary")
            }
            HonerTool.DRAW_IMAGE -> {
                val prompt = ToolArgument.string(arguments["prompt"])?.trim().orEmpty()
                if (prompt.isEmpty()) return reply(call, "Не передано описание картинки.")
                val orientation = ToolArgument.string(arguments["orientation"]) ?: "square"
                val url = MediaLinks.drawing(prompt, orientation) ?: return reply(call, "Не удалось составить ссылку на рисунок.")
                reply(call, "Картинка готова. Вставь в ответ ровно эту строку, без изменений:\n![${MediaLinks.caption(prompt)}]($url)")
            }
            else -> reply(call, "Инструмент «${call.name}» доступен только в расширенном режиме.")
        }
    }

    /** Расширенные инструменты: другие чаты, память, настройки. Возвращают и эффект для приложения. */
    fun executeExtended(call: ToolCallRequest, context: ToolExecutionContext): ToolCallResult {
        val arguments = call.parsedArguments
        return when (HonerTool.from(call.name)) {
            HonerTool.LIST_CHATS -> {
                if (context.chats.isEmpty()) return reply(call, "У пользователя пока только этот чат.")
                val lines = context.chats.map { chat ->
                    val parts = mutableListOf("${chat.number}. «${chat.title}»", "сообщений: ${chat.messageCount}")
                    chat.lastMessageAt?.let { parts.add("последнее: ${formatRussian(it, "d MMMM, HH:mm")}") }
                    if (chat.pinned) parts.add("закреплён")
                    if (chat.archived) parts.add("в архиве")
                    if (chat.preview.isNotEmpty()) parts.add("о чём: ${chat.preview}")
                    parts.joinToString(", ")
                }
                reply(call, "Чаты пользователя (всего ${context.chats.size}):\n" + lines.joinToString("\n") +
                    "\n\nЧтобы прочитать переписку чата, вызови read_chat с его номером.")
            }
            HonerTool.READ_CHAT -> {
                val number = ToolArgument.int(arguments["number"]) ?: return reply(call, "Не передан номер чата.")
                val chat = context.chats.firstOrNull { it.number == number }
                val transcript = context.transcripts[number]
                if (chat == null || transcript == null) return reply(call, "Чат с номером $number не найден. Сначала вызови list_chats.")
                val limit = (ToolArgument.int(arguments["messages"]) ?: 40).coerceIn(1, 120)
                val slice = transcript.takeLast(limit)
                if (slice.isEmpty()) return reply(call, "Чат «${chat.title}» пуст.")
                val body = slice.joinToString("\n") { line ->
                    val who = if (line.role == "user") "Пользователь" else "Honer AI"
                    "$who: ${line.text}"
                }
                reply(call, "Чат «${chat.title}», последние ${slice.size} сообщений:\n$body")
            }
            HonerTool.RENAME_CHAT -> {
                val number = ToolArgument.int(arguments["number"])
                val title = ToolArgument.string(arguments["title"])
                if (number == null || title.isNullOrBlank()) return reply(call, "Нужны номер чата и новое название.")
                val chat = context.chats.firstOrNull { it.number == number }
                    ?: return reply(call, "Чат с номером $number не найден. Сначала вызови list_chats.")
                reply(call, "Чат «${chat.title}» переименован в «$title».", ToolEffect.RenameChat(chat.id, title))
            }
            HonerTool.PIN_CHAT -> {
                val number = ToolArgument.int(arguments["number"])
                val pinned = ToolArgument.bool(arguments["pinned"])
                if (number == null || pinned == null) return reply(call, "Нужны номер чата и признак закрепления.")
                val chat = context.chats.firstOrNull { it.number == number }
                    ?: return reply(call, "Чат с номером $number не найден. Сначала вызови list_chats.")
                reply(call, "Чат «${chat.title}» ${if (pinned) "закреплён" else "откреплён"}.", ToolEffect.PinChat(chat.id, pinned))
            }
            HonerTool.SEND_TO_CHAT -> {
                val number = ToolArgument.int(arguments["number"])
                val text = ToolArgument.string(arguments["text"])
                if (number == null || text.isNullOrBlank()) return reply(call, "Нужны номер чата и текст сообщения.")
                val chat = context.chats.firstOrNull { it.number == number }
                    ?: return reply(call, "Чат с номером $number не найден. Сначала вызови list_chats.")
                reply(call, "Сообщение отправлено в чат «${chat.title}»: ${text.take(200)}", ToolEffect.SendToChatID(chat.id, text))
            }
            HonerTool.SAVE_MEMORY -> {
                val text = arguments["text"].str
                if (text.isNullOrBlank()) return reply(call, "Не передан текст для памяти.")
                reply(call, "Записано в память: $text", ToolEffect.SaveMemory(text))
            }
            HonerTool.SET_APP_SETTING -> {
                val name = ToolArgument.string(arguments["name"])
                val value = ToolArgument.string(arguments["value"])
                if (name == null || value == null) return reply(call, "Нужны название настройки и значение.")
                val allowed = listOf("reasoning", "search", "autoread", "notifications", "fontscale")
                if (name.lowercase() !in allowed) {
                    return reply(call, "Настройка «$name» недоступна. Доступны: reasoning, search, autoRead, notifications, fontScale.")
                }
                reply(call, "Настройка $name переключена в $value.", ToolEffect.SetSetting(name, value))
            }
            else -> execute(call, context)
        }
    }
}

internal fun jsonStrings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
