package com.honerai.app.core

import com.honerai.app.data.ChatInstruction
import com.honerai.app.data.ChatMessage
import com.honerai.app.data.GenerationStep
import com.honerai.app.data.HonorMemory
import com.honerai.app.data.MessageRole
import com.honerai.app.data.newId
import com.honerai.app.device.GuardVerdict
import com.honerai.app.device.ParentalControl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Родительский контроль для движка чата. Обращения обёрнуты: если модуль настроек
 * ещё не готов (или это модульный тест), чат работает как без ограничений.
 */
internal object ParentalGuard {
    private inline fun <T> safe(default: T, block: () -> T): T = try { block() } catch (e: Throwable) { default }

    val enabled: Boolean get() = safe(false) { ParentalControl.enabled }
    val canSearchWeb: Boolean get() = safe(true) { ParentalControl.canSearchWeb }
    val canOpenLinks: Boolean get() = safe(true) { ParentalControl.canOpenLinks }
    val canGenerateImages: Boolean get() = safe(true) { ParentalControl.canGenerateImages }
    val canPlayGames: Boolean get() = safe(true) { ParentalControl.canPlayGames }
    val canUseContacts: Boolean get() = safe(true) { ParentalControl.canUseContacts }
    val blockReason: String? get() = safe(null) { ParentalControl.blockReason }
    fun systemPromptBlock(): String = safe("") { ParentalControl.systemPromptBlock() }
    fun check(text: String): GuardVerdict = safe(GuardVerdict.Allowed) { ParentalControl.check(text) }
    fun isUrlAllowed(url: String): Boolean = safe(true) { ParentalControl.isUrlAllowed(url) }
    fun isGameAllowed(raw: String): Boolean = safe(true) { ParentalControl.isGameAllowed(raw) }
}

/** Логика чата без Android: всё, что ChatStore на iPhone держал в статических методах. */
object ChatLogic {
    /** Сколько раз подряд модель может вызвать инструменты в одном ответе. */
    const val MAXIMUM_TOOL_ROUNDS = 5
    const val TOOLS_ENABLED = true
    const val MAXIMUM_MEMORY_COUNT = 5000
    const val MAXIMUM_MEMORY_LENGTH = 1200
    const val MAXIMUM_INSTRUCTION_LENGTH = 4000
    const val MAXIMUM_INSTRUCTIONS_PER_CHAT = 12

    private val stopWords = setOf(
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все",
        "она", "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по",
        "только", "ее", "мне", "было", "вот", "от", "меня", "еще", "нет", "о", "из", "ему",
        "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "for", "on", "with",
    )

    /** Ключевые слова факта или запроса — для отбора релевантной памяти без embeddings. */
    fun keywords(text: String): List<String> {
        val result = ArrayList<String>(8)
        val lower = text.lowercase()
        val word = StringBuilder()
        fun take(): Boolean {
            if (word.length >= 3) {
                val part = word.toString()
                if (part !in stopWords && part !in result) result.add(part)
            }
            word.setLength(0)
            return result.size >= 24
        }
        for (c in lower) {
            if (c.isLetterOrDigit()) word.append(c) else if (word.isNotEmpty() && take()) return result
        }
        if (word.isNotEmpty()) take()
        return result
    }

    /**
     * Факты памяти, относящиеся к вопросу: один проход по памяти (5000 фактов — миллисекунды),
     * больше совпадений — выше, при равенстве новее — выше; остаток — самыми свежими.
     */
    fun relevantMemories(memories: List<HonorMemory>, query: String, limit: Int = 40): List<HonorMemory> {
        // appui: убираем дубликаты по тексту — одинаковые факты не занимают места в контексте.
        val deduped = dedupeMemories(memories)
        val words = keywords(query).toHashSet()
        if (words.isEmpty()) return deduped.takeLast(limit)
        val matched = ArrayList<Pair<Int, Int>>()
        for ((index, memory) in deduped.withIndex()) {
            val own = memory.keywords.ifEmpty { keywords(memory.text) }
            var score = 0
            for (word in own.toHashSet()) if (word in words) score++
            if (score > 0) matched.add(index to score)
        }
        matched.sortWith { a, b -> if (a.second != b.second) b.second - a.second else b.first - a.first }
        val result = matched.take(limit).map { deduped[it.first] }.toMutableList()
        if (result.size >= limit) return result
        val taken = result.mapTo(HashSet()) { it.id }
        for (memory in deduped.asReversed()) {
            if (memory.id in taken) continue
            result.add(memory)
            if (result.size >= limit) break
        }
        return result
    }

    /** Факты без повторов по нормализованному тексту; при совпадении оставляем более новый (последний). */
    fun dedupeMemories(memories: List<HonorMemory>): List<HonorMemory> {
        if (memories.size < 2) return memories
        val seen = HashSet<String>()
        val result = ArrayList<HonorMemory>(memories.size)
        for (memory in memories.asReversed()) {
            val key = memory.text.trim().lowercase()
            if (key.isNotEmpty() && !seen.add(key)) continue
            result.add(memory)
        }
        return result.asReversed()
    }

    /** Раздел «Закреплённые инструкции» для модели. */
    fun instructionsBlock(instructions: List<ChatInstruction>, legacyPrompt: String = ""): String {
        val items = instructions.map { item ->
            val origin = if (item.author == MessageRole.ASSISTANT) "твой прежний ответ, который пользователь закрепил как образец или правило"
            else "написал пользователь"
            "[закрепил пользователь; $origin]\n${item.text}"
        }.toMutableList()
        val legacy = legacyPrompt.trim()
        if (legacy.isNotEmpty()) items.add(0, "[закрепил пользователь; написал пользователь]\n$legacy")
        if (items.isEmpty()) return ""
        val numbered = items.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n\n")
        return "## Закреплённые инструкции этого чата\nПользователь сам закрепил эти сообщения как постоянные инструкции для этого чата. Ты видишь их в каждом ответе. Соблюдай их всегда, пока они закреплены; последнее сообщение пользователя может уточнить их для конкретного ответа. Это правила, а не память и не факты.\n$numbered"
    }

    private val ruLocale = Locale("ru", "RU")

    /** Сведения профиля для модели: возраст и когда создан аккаунт. */
    fun profileBlock(birthday: String, accountCreatedAt: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val lines = mutableListOf<String>()
        val birth = runCatching { LocalDate.parse(birthday.trim()) }.getOrNull()
        if (birth != null) {
            val today = now.atZone(zone).toLocalDate()
            val age = ChronoUnit.YEARS.between(birth, today)
            val text = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(ruLocale).format(birth)
            lines.add("Дата рождения пользователя: $text (полных лет: $age). Если день рождения сегодня — поздравь.")
        }
        if (accountCreatedAt != null) {
            val created = accountCreatedAt.atZone(zone)
            val text = DateTimeFormatter.ofPattern("d MMMM yyyy 'г.', HH:mm", ruLocale).format(created)
            val days = maxOf(0L, ChronoUnit.DAYS.between(created, now.atZone(zone)))
            lines.add("Аккаунт Honer AI создан: $text (дней назад: $days).")
        }
        if (lines.isEmpty()) return ""
        return "\nПрофиль пользователя (данные, используй к месту):\n" + lines.joinToString("\n") { "• $it" }
    }

    /**
     * Строка «РЕАКЦИЯ: 🔥» в начале ответа — служебная. Пока строка не дописана,
     * начало ответа скрыто, иначе она мелькала бы в чате.
     * @return эмодзи (или null) и видимый текст.
     */
    fun reactionSplit(text: String): Pair<String?, String> {
        val leading = text.takeWhile { it.isWhitespace() }
        val rest = text.substring(leading.length)
        val markers = listOf("РЕАКЦИЯ", "REACTION")
        val marker = markers.firstOrNull { rest.startsWith(it) || it.startsWith(rest) }
        if (marker == null || rest.isEmpty()) return null to text
        if (!rest.startsWith(marker)) return null to ""
        val lineEnd = rest.indexOf('\n')
        if (lineEnd < 0) return null to ""
        val line = rest.substring(0, lineEnd)
        val value = line.substring(marker.length).dropWhile { it == ':' || it == '-' || it == ' ' }
        val emoji = value.trim(' ', '\t')
        val body = rest.substring(lineEnd + 1).trim('\n', '\r')
        if (emoji.isEmpty() || graphemeCount(emoji) > 3) return null to text
        return emoji to body
    }

    private val reactionLine = Regex("(?m)^\\s*(?:РЕАКЦИЯ|РЕАКЦИЯ НА СООБЩЕНИЕ|REACTION)\\s*[:\\-]\\s*(\\S{1,8})\\s*$")

    /** Реакция в готовом ответе: эмодзи и текст без служебной строки (null — строки нет). */
    fun extractReaction(content: String): Pair<String, String>? {
        val match = reactionLine.find(content) ?: return null
        val emoji = match.groupValues[1]
        if (graphemeCount(emoji) > 4) return null
        val remainder = content.removeRange(match.range).trim()
        // Если вся реплика — строка реакции, стирать её нельзя: иначе в чате тишина.
        if (remainder.isEmpty()) return null
        return emoji to remainder
    }

    internal fun graphemeCount(text: String): Int {
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != java.text.BreakIterator.DONE) count++
        return count
    }

    /**
     * Переписка для сервиса: не больше 60 сообщений и 120 000 знаков, сообщение режется
     * до 20 000 знаков, первым идёт вопрос пользователя, пустой ответ не уходит.
     */
    fun requestHistory(messages: List<ChatMessage>): List<ChatMessage> {
        val characterLimit = 120_000
        val messageLimit = 60
        val picked = mutableListOf<ChatMessage>()
        var total = 0
        for (message in messages.asReversed()) {
            if (picked.size >= messageLimit) break
            var copy = message
            if (copy.content.length > 20_000) copy = copy.copy(content = copy.content.take(20_000) + "\n… (сообщение сокращено)")
            if (message.role == MessageRole.ASSISTANT && copy.reasoning.length > 4_000) copy = copy.copy(reasoning = copy.reasoning.take(4_000))
            val size = copy.content.length + copy.reasoning.length
            if (total + size > characterLimit && picked.isNotEmpty()) break
            total += size
            picked.add(copy)
        }
        var ordered = picked.asReversed().filterNot { it.role == MessageRole.ASSISTANT && it.content.isEmpty() && it.reasoning.isEmpty() }
        val firstUser = ordered.indexOfFirst { it.role == MessageRole.USER }
        if (firstUser >= 0) ordered = ordered.drop(firstUser)
        return ordered
    }

    /** Спрашивает ли пользователь про свои чаты (тогда к запросу прикладывается их переписка). */
    fun queryMentionsChats(queryText: String, recentContext: String): Boolean {
        val haystack = (queryText + "\n" + recentContext).lowercase()
        val markers = listOf("чат", "переписк", "диалог", "бесед", "ветк",
            "мы говорили", "мы обсуждали", "обсуждали", "мы писали", "я писал",
            "прошлом разговоре", "другом разговоре", "chat", "conversation", "thread")
        return markers.any { haystack.contains(it) }
    }

    /** Вызовы инструментов в формате API: id, тип и имя с аргументами. */
    fun toolCallsJSON(calls: List<ToolCallRequest>): String {
        val list = calls.filter { it.name.isNotEmpty() }.map { call ->
            buildJsonObject {
                put("id", call.id.ifEmpty { "call_" + newId().take(8) })
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", call.name)
                    put("arguments", call.arguments.ifEmpty { "{}" })
                })
            }
        }
        if (list.isEmpty()) return ""
        return JsonArray(list).toString()
    }

    /** Склеивает куски одного вызова: по index, затем по id, затем по имени. */
    fun mergeToolCall(call: ToolCallRequest, calls: MutableList<ToolCallRequest>) {
        if (call.index != null) {
            val existing = calls.indexOfFirst { it.index == call.index }
            if (existing >= 0) {
                val current = calls[existing]
                calls[existing] = current.copy(
                    arguments = current.arguments + call.arguments,
                    id = current.id.ifEmpty { call.id },
                    name = current.name.ifEmpty { call.name },
                )
                return
            }
        }
        if (call.id.isNotEmpty()) {
            val existing = calls.indexOfFirst { it.id == call.id }
            if (existing >= 0) {
                val current = calls[existing]
                calls[existing] = current.copy(
                    arguments = current.arguments + call.arguments,
                    name = current.name.ifEmpty { call.name },
                    index = current.index ?: call.index,
                )
                return
            }
        }
        if (call.name.isNotEmpty()) {
            val existing = calls.indexOfFirst { it.name == call.name && it.index == call.index }
            if (existing >= 0) {
                val current = calls[existing]
                calls[existing] = current.copy(arguments = current.arguments + call.arguments, id = current.id.ifEmpty { call.id })
                return
            }
        }
        calls.add(call)
    }

    fun isTooShortToBeAnAnswer(text: String): Boolean = RussianTextPolicy.isTooShortToBeAnAnswer(text)

    /** Модель вместо ответа объявляет о намерении («Сначала найду чаты…»). */
    fun isToolAnnouncement(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length >= 400) return false
        val lowered = trimmed.lowercase()
        val markers = listOf("сначала найду", "сначала проверю", "сейчас найду", "сейчас прочитаю",
            "затем открою", "затем прочитаю", "сейчас вызову", "вызову инструмент",
            "без вызова инструмента", "нашёл ваш чат", "нашел ваш чат",
            "прочитал его", "прочитал чат", "нашёл чат", "нашел чат",
            "let me check", "let me look", "i will call", "let me read",
            "i'll check", "i found your chat")
        return markers.any { lowered.contains(it) }
    }

    private val splitter = Regex("(?s)^.*?(?:[.!?…]+|\\n|$)")
    private val announceVerbs = listOf("найду", "найти", "прочитаю", "прочитать", "открою", "открыть",
        "вызову", "вызвать", "проверю", "проверить", "посмотрю", "посмотреть",
        "отвечу", "ответить", "сформулирую", "уточню", "достану", "извлеку",
        "разберу", "составлю", "подготовлю", "дам ответ", "let me", "i will", "i'll", "i am going to")
    private val announceNouns = listOf("чат", "переписк", "список", "инструмент", "сообщени", "chat", "tool")
    private val announceMarkers = listOf("сначала", "затем", "потом", "теперь", "для этого", "после этого", "first,", "then i")

    /** Убирает из ответа объявления о действиях; пустая строка — ответа нет, нужен повтор. */
    fun strippingToolAnnouncements(text: String): String {
        var value = text.trim()
        if (value.isEmpty()) return value
        fun isAnnouncement(sentence: String): Boolean {
            val lowered = sentence.lowercase()
            if (lowered.isEmpty() || lowered.length >= 220) return false
            // В настоящем ответе почти всегда есть числа — обещание их не содержит.
            if (lowered.any { it.isDigit() }) return false
            if (announceVerbs.none { lowered.contains(it) }) return false
            if (announceNouns.any { lowered.contains(it) }) return true
            return announceMarkers.any { lowered.contains(it) }
        }
        repeat(5) {
            val match = splitter.find(value) ?: return value
            val first = match.value.trim()
            if (!isAnnouncement(first)) return value
            val rest = value.substring(match.range.last + 1).trim()
            if (rest.length < 12) return ""
            value = rest
        }
        return value
    }

    /** Нужен ли повтор: ответ пуст, оборван или это объявление о действии без ответа. */
    fun needsAnswerRecovery(text: String): Boolean = isTooShortToBeAnAnswer(text) || isToolAnnouncement(text)

    private fun host(raw: String): String = WebToolExecutor.url(raw)?.host ?: raw

    /** Шаг ленты для вызова инструмента. */
    fun step(call: ToolCallRequest): GenerationStep {
        val arguments = call.parsedArguments
        fun argument(key: String) = ToolArgument.string(arguments[key]).orEmpty().take(120)
        return when (HonerTool.from(call.name)) {
            HonerTool.WEB_SEARCH -> GenerationStep(kind = "search", title = "Ищу в интернете", detail = "«${argument("query")}»")
            HonerTool.OPEN_PAGE -> host(argument("url")).let { GenerationStep(kind = "read", title = "Читаю страницу", detail = it, sites = listOf(it)) }
            HonerTool.FIND_IMAGES -> GenerationStep(kind = "images", title = "Ищу фотографии", detail = "«${argument("query")}»")
            HonerTool.FIND_VIDEOS -> GenerationStep(kind = "videos", title = "Ищу видео", detail = "«${argument("query")}»")
            HonerTool.SCREENSHOT_PAGE -> host(argument("url")).let { GenerationStep(kind = "screenshot", title = "Делаю скриншот страницы", detail = it, sites = listOf(it)) }
            HonerTool.GET_WEATHER -> GenerationStep(kind = "weather", title = "Смотрю погоду", detail = argument("city"))
            HonerTool.DRAW_IMAGE -> GenerationStep(kind = "draw", title = "Рисую картинку", detail = argument("prompt"))
            HonerTool.LIST_CHATS -> GenerationStep(kind = "chats", title = "Смотрю список чатов")
            HonerTool.READ_CHAT -> GenerationStep(kind = "chats", title = "Читаю чат", detail = "№${argument("number")}")
            HonerTool.RENAME_CHAT, HonerTool.PIN_CHAT -> GenerationStep(kind = "chats", title = "Обновляю чаты")
            HonerTool.SEND_TO_CHAT -> GenerationStep(kind = "chats", title = "Пишу в другой чат")
            HonerTool.SAVE_MEMORY -> GenerationStep(kind = "memory", title = "Запоминаю", detail = argument("text"))
            HonerTool.GET_APP_SETTINGS -> GenerationStep(kind = "settings", title = "Смотрю настройки")
            HonerTool.SET_APP_SETTING -> GenerationStep(kind = "settings", title = "Меняю настройку", detail = argument("name"))
            HonerTool.FIND_CONTACT -> GenerationStep(kind = "contact", title = "Ищу контакт", detail = argument("name"))
            HonerTool.COPY_TO_CLIPBOARD -> GenerationStep(kind = "settings", title = "Копирую в буфер обмена")
            HonerTool.START_GAME -> GenerationStep(kind = "settings", title = "Открываю игру", detail = argument("game"))
            // agent: действия в приложениях.
            HonerTool.RUN_DEVICE_TASK, HonerTool.CONFIRM_PENDING_ACTION ->
                com.honerai.app.core.agent.AgentToolSchemas.step(call) ?: GenerationStep(kind = "settings", title = "Выполняю действие в приложении")
            else -> com.honerai.app.core.github.GitHubToolSchemas.step(call) /* integ */ ?: ExtraToolSchemas.step(call) ?: GenerationStep(kind = "settings", title = "Выполняю действие")
        }
    }

    /** Строка состояния, пока выполняются инструменты. */
    fun toolStatus(calls: List<ToolCallRequest>): String {
        val names = calls.map { it.name }.toSet()
        fun has(tool: HonerTool) = tool.rawValue in names
        return when {
            has(HonerTool.WEB_SEARCH) -> "Ищу в интернете…"
            has(HonerTool.OPEN_PAGE) -> "Читаю страницу…"
            has(HonerTool.FIND_IMAGES) -> "Ищу изображения…"
            has(HonerTool.FIND_VIDEOS) -> "Ищу видео…"
            has(HonerTool.SCREENSHOT_PAGE) -> "Делаю скриншот страницы…"
            has(HonerTool.GET_WEATHER) -> "Смотрю погоду…"
            has(HonerTool.DRAW_IMAGE) -> "Рисую…"
            has(HonerTool.GET_APP_SETTINGS) -> "Смотрю настройки…"
            has(HonerTool.START_GAME) -> "Открываю игру…"
            has(HonerTool.READ_CHAT) -> "Читаю чат…"
            has(HonerTool.LIST_CHATS) -> "Смотрю список чатов…"
            has(HonerTool.SEND_TO_CHAT) -> "Отправляю сообщение в чат…"
            has(HonerTool.SAVE_MEMORY) -> "Запоминаю…"
            has(HonerTool.RENAME_CHAT) || has(HonerTool.PIN_CHAT) -> "Обновляю чаты…"
            has(HonerTool.SET_APP_SETTING) -> "Меняю настройку…"
            else -> com.honerai.app.core.agent.AgentToolSchemas.status(names) ?: com.honerai.app.core.github.GitHubToolSchemas.status(names) /* integ */ ?: ExtraToolSchemas.status(names) ?: "Выполняю действие…" // agent
        }
    }

    /** Инструмент запрещён родительским контролем — объяснение для модели, иначе null. */
    fun parentalRefusal(call: ToolCallRequest): String? {
        if (!ParentalGuard.enabled) return null
        val tool = HonerTool.from(call.name) ?: return null
        val refusal = "Родительский контроль запрещает это действие. Коротко и доброжелательно скажи ребёнку, что это недоступно, и предложи безопасную альтернативу."
        if (tool.isWeb && !ParentalGuard.canSearchWeb) return refusal
        if ((tool == HonerTool.DRAW_IMAGE || tool == HonerTool.EDIT_IMAGE) && !ParentalGuard.canGenerateImages) return refusal
        if (tool == HonerTool.FIND_CONTACT && !ParentalGuard.canUseContacts) return refusal
        val arguments = call.parsedArguments
        if (tool == HonerTool.START_GAME) {
            val game = ToolArgument.string(arguments["game"]).orEmpty().lowercase()
            if (!ParentalGuard.canPlayGames) return refusal
            val kind = GameKinds.from(game)
            if (kind != null && !ParentalGuard.isGameAllowed(kind)) return refusal
        }
        // Адреса страниц: запрещённые сайты не открываются.
        val addresses = mutableListOf<String>()
        for (key in listOf("url", "page", "channel", "video", "source")) ToolArgument.string(arguments[key])?.let { addresses.add(it) }
        addresses += TableEditing.strings(arguments["urls"])
        for (address in addresses) {
            val url = WebToolExecutor.url(address) ?: continue
            if (!ParentalGuard.canOpenLinks && (tool == HonerTool.OPEN_PAGE || tool == HonerTool.SCREENSHOT_PAGE)) return refusal
            if (!ParentalGuard.isUrlAllowed(url.toString())) return "Этот сайт запрещён родительским контролем. Не открывай его и предложи безопасную альтернативу."
        }
        return null
    }

    /** Первая картинка ответа (для уведомления). */
    fun firstImageUrl(markdown: String): String? =
        Regex("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)").find(markdown)?.groupValues?.get(1)
}
