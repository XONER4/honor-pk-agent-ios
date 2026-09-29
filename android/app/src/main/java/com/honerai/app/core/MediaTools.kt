package com.honerai.app.core

import com.honerai.app.data.GenerationStep
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// media: инструменты open_app и send_media, короткая инструкция для модели
// и страховка «карточки не потерялись».

/** Схемы, шаги ленты и строка состояния для open_app и send_media. */
object MediaToolSchemas {
    private val s = ToolSchema

    fun schema(tool: HonerTool): JsonObject = when (tool) {
        HonerTool.OPEN_APP -> s.function(tool.rawValue,
            "Готовит кнопку «Открыть <приложение>» в ответе — приложение открывается сразу на нужном экране с подставленным запросом/адресом, а пользователь завершает (вход, оплату, заказ) сам. Умеет: Google Карты (маршрут/место), Gmail и Яндекс Почта (черновик письма), Google Календарь (событие), YouTube и Google (поиск), Яндекс Карты, Навигатор, Яндекс Go (такси до адреса), Яндекс Музыка, Яндекс Погода, Яндекс Электрички, Wildberries/Ozon/Яндекс Маркет (поиск товара), Play Маркет (поиск), Delimobil, KFC, Burger King, «Вкусно и точка», ВКонтакте и Telegram (открыть профиль/чат по имени), операторы МТС/Билайн/МегаФон/Tele2, кошельки Google Pay/Кошелёк/Mir Pay/СБПэй — либо любое установленное приложение по названию. Кошельки, банки и операторы только открываются: НИКОГДА не начинай оплату или перевод денег.",
            mapOf(
                "app" to s.string("Название или ключ сервиса: google_maps, gmail, yandex_mail, google_calendar, youtube, google, yandex_maps, yandex_navigator, yandex_taxi, yandex_music, yandex_weather, yandex_rasp, wildberries, ozon, yandex_market, play_market, delimobil, kfc, burger_king, vkusno, vk, telegram, mts, beeline, megafon, tele2, google_wallet, koshelek, mir_pay, sbp — либо название установленного приложения"),
                "action" to s.string("route (маршрут), place (место), search, compose (письмо), event (событие), open; необязательно"),
                "query" to s.string("Адрес назначения, место, что искать, имя пользователя (для vk/telegram) или тема; необязательно"),
                "to" to s.string("Адрес получателя письма (для gmail)"),
                "subject" to s.string("Тема письма"),
                "body" to s.string("Текст письма или описание события"),
                "title" to s.string("Название события календаря"),
                "start" to s.string("Начало события, ISO: 2026-10-01T15:00"),
                "end" to s.string("Конец события, ISO"),
                "location" to s.string("Место события"),
            ), listOf("app"))
        HonerTool.SEND_MEDIA -> s.function(tool.rawValue,
            "Находит и присылает в чат медиа карточками с кнопками «Скачать» и «Поделиться»: image — фото (Openverse, Wikimedia Commons), video — ролики YouTube и VK Видео, audio — свободная музыка и звуки (Internet Archive, Wikimedia Commons; платные сервисы не используются). Можно передать готовую ссылку url. Возвращает строки для вставки в ответ.",
            mapOf(
                "kind" to s.string("image, video или audio"),
                "query" to s.string("Что искать; для фото и музыки лучше на английском"),
                "url" to s.string("Прямая ссылка на файл или ролик вместо поиска; необязательно"),
                "caption" to s.string("caption — подпись на русском (для ссылки url или общая тема подборки)"),
                "count" to s.integer("Сколько, 1–6, по умолчанию 3"),
            ), listOf("kind"))
        else -> s.function(tool.rawValue, "", emptyMap(), emptyList())
    }

    fun step(call: ToolCallRequest): GenerationStep? {
        val arguments = call.parsedArguments
        fun argument(key: String) = ToolArgument.string(arguments[key]).orEmpty().take(120)
        return when (HonerTool.from(call.name)) {
            HonerTool.OPEN_APP -> GenerationStep(kind = "settings", title = "Готовлю кнопку приложения", detail = argument("app"))
            HonerTool.SEND_MEDIA -> {
                val kind = MediaSender.kind(argument("kind"), argument("url"))
                val title = when (kind) { "audio" -> "Ищу музыку и звуки"; "video" -> "Ищу видео"; else -> "Ищу фотографии" }
                GenerationStep(kind = if (kind == "video") "videos" else "images", title = title,
                    detail = argument("query").ifEmpty { argument("url") }.let { if (it.isEmpty()) "" else "«$it»" })
            }
            else -> null
        }
    }

    fun status(names: Set<String>): String? = when {
        HonerTool.SEND_MEDIA.rawValue in names -> "Подбираю медиа…"
        HonerTool.OPEN_APP.rawValue in names -> "Готовлю кнопку приложения…"
        else -> null
    }

    /** Короткая инструкция модели про новые инструменты и запрет платежей. */
    const val PROMPT = "\n\n## Приложения и медиа\n" +
        "• open_app готовит кнопку «Открыть …» и открывает приложение сразу на нужном экране: Google Карты, Gmail и Яндекс Почта, Google Календарь, YouTube и Google (поиск), Яндекс Карты/Навигатор/Go (такси до адреса)/Музыка/Погода/Электрички, маркетплейсы Wildberries·Ozon·Яндекс Маркет (поиск товара), Play Маркет, доставку и каршеринг (Delimobil, KFC, Burger King, «Вкусно и точка» — просто запуск), ВКонтакте и Telegram (профиль/чат по имени), операторов МТС·Билайн·МегаФон·Tele2, кошельки (Google Pay, Кошелёк, Mir Pay, СБПэй) и установленные приложения по названию. Вставляй возвращённый блок ```app без изменений. Для такси/карт клади адрес в query; для vk/telegram — имя пользователя в query.\n" +
        "• Платежи: никогда не начинай, не готовь и не подтверждай оплату или перевод денег, не спрашивай номера карт, коды и пароли. Кошельки и банки можно только открыть — дальше пользователь действует сам.\n" +
        "• send_media присылает в чат фото, видео или музыку карточками с кнопками «Скачать» и «Поделиться». Вставляй возвращённые строки ![подпись](ссылка) без изменений адресов; подписи — на русском.\n" +
        "• «Пришли/скинь/включи музыку, песню, трек, мелодию» — это send_media с kind=audio: музыка должна прийти прямо в чат и играть здесь. open_app для Яндекс Музыки — только если просят именно открыть приложение или сервис; можно предложить его вторым вариантом после карточек.\n" +
        "• Подписи к фото и видео из интернета пиши на языке пользователя, не копируй английские названия файлов."
}

/**
 * Блоки, которые инструмент попросил вставить в ответ. Если модель не вставила ни одного
 * из блоков вызова, приложение само добавляет их в конец ответа — карточка не теряется.
 */
object MediaAnswerBlocks {
    /** [key] — что должно встретиться в ответе (адрес или JSON), [text] — что дописать. */
    class Item(val key: String, val text: String)

    private val byCall = ConcurrentHashMap<String, List<Item>>()

    fun register(callId: String, items: List<Item>) {
        if (items.isNotEmpty()) byCall[callId] = items
        // Старые записи (ответ прервали) не копятся бесконечно.
        if (byCall.size > 200) byCall.keys.take(100).forEach { byCall.remove(it) }
    }

    /** Ответ с дописанными блоками или null, если дописывать нечего. Записи вызовов удаляются. */
    fun completed(content: String, callIds: List<String>): String? {
        val missing = mutableListOf<String>()
        for (id in callIds) {
            val items = byCall.remove(id) ?: continue
            if (items.none { content.contains(it.key) }) missing += items.map { it.text }
        }
        if (missing.isEmpty()) return null
        return content.trimEnd() + "\n\n" + missing.joinToString("\n\n")
    }
}

/** Найденное медиа: ссылка, подпись и страница-источник. */
class MediaItem(val url: String, val title: String, val page: String? = null)

/** Инструмент send_media: поиск фото, видео и свободной музыки. */
class MediaSender(
    private val client: WebSearchClient,
    private val language: String = "ru",
    private val translate: suspend (List<String>) -> Map<String, String> = { CaptionTranslator.translate(it) },
) {
    suspend fun execute(call: ToolCallRequest, progress: ToolProgress? = null): ToolCallResult {
        val arguments = call.parsedArguments
        fun text(key: String) = ToolArgument.string(arguments[key])?.trim().orEmpty()
        fun reply(content: String) = ToolCallResult(call.id, call.name, content)
        val rawUrl = text("url")
        val kind = kind(text("kind"), rawUrl)
        val query = text("query")
        val caption = text("caption")
        val count = (ToolArgument.int(arguments["count"]) ?: 3).coerceIn(1, 6)
        val items: List<MediaItem> = if (rawUrl.isNotEmpty()) {
            val url = WebToolExecutor.url(rawUrl) ?: return reply("Ссылка «$rawUrl» не распознана. Нужна полная ссылка https://…")
            listOf(MediaItem(url.toString(), caption.ifEmpty { query }))
        } else {
            if (query.isEmpty()) return reply("Не передано, что искать.")
            when (kind) {
                "audio" -> { progress?.invoke("Internet Archive и Wikimedia Commons", listOf("archive.org", "commons.wikimedia.org")); audio(query, count) }
                "video" -> video(query, count)
                else -> client.imageResults(query, count).map { MediaItem(it.url, it.title, it.page) }
            }
        }
        if (items.isEmpty()) {
            return reply(when (kind) {
                "audio" -> "Свободных записей по запросу «$query» не нашлось. Скажи об этом; платные сервисы музыки не используются."
                "video" -> "Видео по запросу «$query» не нашлось. Скажи об этом пользователю."
                else -> "Изображения по запросу «$query» не найдены. Скажи об этом и предложи нарисовать картинку инструментом draw_image."
            })
        }
        val captions = captions(items, caption)
        val lines = items.mapIndexed { index, item -> "![${clean(captions[index])}](${item.url})" }
        MediaAnswerBlocks.register(call.id, items.mapIndexed { index, item -> MediaAnswerBlocks.Item(item.url, lines[index]) })
        val what = when (kind) { "audio" -> "Аудио"; "video" -> "Видео"; else -> "Изображения" }
        return reply("$what найдено: ${items.size}. Вставь в ответ строки ровно в таком виде, каждую с новой строки — приложение покажет карточки с кнопками «Скачать» и «Поделиться». Подписи уже на языке пользователя, адреса не меняй:\n" +
            lines.joinToString("\n"))
    }

    /** Подписи на языке интерфейса: русская подпись модели, перевод названий или название сайта. */
    suspend fun captions(items: List<MediaItem>, common: String = ""): List<String> {
        val titles = items.map { it.title.ifBlank { common } }
        val foreign = titles.filter { CaptionLanguage.needsTranslation(it, language) }
        val translated = if (foreign.isEmpty()) emptyMap() else runCatching { translate(foreign) }.getOrDefault(emptyMap())
        return items.mapIndexed { index, item ->
            val title = titles[index]
            val chosen = CaptionSelection.choose(title, item.page ?: item.url, language, translated[title.trim()])
            // Модель дала русскую общую подпись — лучше, чем название сайта.
            if (chosen == CaptionSelection.siteName(item.page ?: item.url) && common.isNotEmpty() && !CaptionLanguage.needsTranslation(common, language)) common else chosen
        }
    }

    private suspend fun video(query: String, count: Int): List<MediaItem> {
        val found = client.videoResults(query, count).map { MediaItem(it.first, it.second) }.toMutableList()
        if (found.size < count) {
            // VK Видео — из выдачи поисковиков, если роликов YouTube мало.
            val vk = runCatching { client.duckDuckGoResults("site:vkvideo.ru $query") + client.bingResults("site:vk.com/video $query") }.getOrDefault(emptyList())
            for (source in vk) {
                if (found.size >= count) break
                if (!MediaLinks.isVideo(source.url) || found.any { it.url == source.url }) continue
                found += MediaItem(source.url, source.title.substringBefore(" — ").substringBefore(" | ").trim())
            }
        }
        return found.take(count)
    }

    /** Свободное аудио: Internet Archive и Wikimedia Commons, по очереди. */
    suspend fun audio(query: String, count: Int): List<MediaItem> = coroutineScope {
        val archive = async { withTimeoutOrNull(9_000) { archiveAudio(query, count) } ?: emptyList() }
        val commons = async { withTimeoutOrNull(9_000) { commonsAudio(query, count) } ?: emptyList() }
        val first = archive.await()
        val second = commons.await()
        val merged = mutableListOf<MediaItem>()
        for (index in 0 until maxOf(first.size, second.size)) {
            if (index < first.size) merged += first[index]
            if (index < second.size) merged += second[index]
        }
        merged.distinctBy { it.url }.take(count)
    }

    private suspend fun archiveAudio(query: String, count: Int): List<MediaItem> {
        val search = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", "(${query.take(200)}) AND mediatype:(audio)")
            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title").addQueryParameter("fl[]", "creator")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", (count + 3).toString()).addQueryParameter("output", "json").build()
        val document = client.fetch(search.toString(), 600_000, 8) ?: return emptyList()
        val docs = ArchiveAudio.searchResults(document.text)
        val limit = Semaphore(4)
        return coroutineScope {
            docs.map { doc ->
                async {
                    limit.withPermit {
                        val metadata = client.fetch("https://archive.org/metadata/${URLEncoder.encode(doc.identifier, "UTF-8")}", 2_000_000, 8)
                            ?: return@withPermit null
                        val file = ArchiveAudio.pickFile(metadata.text) ?: return@withPermit null
                        MediaItem(ArchiveAudio.downloadUrl(doc.identifier, file), doc.caption, "https://archive.org/details/${doc.identifier}")
                    }
                }
            }.awaitAll().filterNotNull()
        }.take(count)
    }

    private suspend fun commonsAudio(query: String, count: Int): List<MediaItem> {
        val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "filetype:audio " + query.take(200))
            .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrlimit", (count + 3).toString())
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|mime")
            .addQueryParameter("format", "json").build()
        val document = client.fetch(url.toString(), 800_000, 8) ?: return emptyList()
        return CommonsAudio.parse(document.text).take(count)
    }

    companion object {
        /** Вид медиа из аргумента (терпимо к «фото», «music») или по расширению ссылки. */
        fun kind(raw: String, url: String = ""): String {
            val value = raw.lowercase()
            return when {
                listOf("audio", "music", "sound", "song", "аудио", "музык", "звук", "песн", "трек").any { value.contains(it) } -> "audio"
                listOf("video", "видео", "ролик", "clip", "клип").any { value.contains(it) } -> "video"
                listOf("image", "photo", "picture", "фото", "картин", "изображ").any { value.contains(it) } -> "image"
                url.isNotEmpty() && MediaKinds.isAudio(url) -> "audio"
                url.isNotEmpty() && MediaLinks.isVideo(url) -> "video"
                else -> "image"
            }
        }

        private fun clean(title: String): String = title.replace(Regex("[\\[\\]()\\n]"), " ").replace(Regex("\\s+"), " ").trim().take(100)
    }
}

/** Вид ссылки по расширению: аудио, видео-файл, картинка. */
object MediaKinds {
    val audioExtensions = setOf("mp3", "ogg", "oga", "opus", "m4a", "aac", "wav", "flac", "weba")
    val videoExtensions = setOf("mp4", "mov", "m4v", "m3u8", "webm", "3gp")
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "svg")

    fun extension(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')
        return path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    }

    fun isAudio(url: String): Boolean = extension(url) in audioExtensions
    fun isVideoFile(url: String): Boolean = extension(url) in videoExtensions
}

/** Ответы Internet Archive: поиск и выбор mp3-файла записи. */
object ArchiveAudio {
    class Doc(val identifier: String, val title: String, val creator: String) {
        val caption: String get() = if (creator.isNotBlank() && !title.contains(creator)) "$title — $creator" else title
    }

    fun searchResults(json: String): List<Doc> = parseJson(json)["response"]["docs"].arr.orEmpty().mapNotNull { doc ->
        val id = doc["identifier"].str ?: return@mapNotNull null
        val creator = doc["creator"].str ?: doc["creator"].arr?.firstOrNull().str ?: ""
        Doc(id, doc["title"].str ?: id, creator)
    }

    /** Файл для прослушивания: mp3 (сначала производный VBR MP3 — он легче), иначе ogg. */
    fun pickFile(json: String): String? {
        val files = parseJson(json)["files"].arr.orEmpty().mapNotNull { file ->
            val name = file["name"].str ?: return@mapNotNull null
            Triple(name, file["format"].str.orEmpty(), file["size"].str?.toLongOrNull() ?: Long.MAX_VALUE)
        }
        val mp3 = files.filter { it.first.lowercase().endsWith(".mp3") }
        val preferred = mp3.firstOrNull { it.second.contains("VBR", true) } ?: mp3.minByOrNull { it.third }
        return (preferred ?: files.firstOrNull { it.first.lowercase().endsWith(".ogg") })?.first
    }

    fun downloadUrl(identifier: String, file: String): String {
        val path = file.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "https://archive.org/download/${URLEncoder.encode(identifier, "UTF-8")}/$path"
    }
}

/** Аудио Wikimedia Commons из ответа API (generator=search, prop=imageinfo). */
object CommonsAudio {
    fun parse(json: String): List<MediaItem> {
        val pages = parseJson(json)["query"]["pages"].obj ?: return emptyList()
        return pages.values.sortedBy { it["index"].int ?: Int.MAX_VALUE }.mapNotNull { page ->
            val info = page["imageinfo"].arr?.firstOrNull() ?: return@mapNotNull null
            val url = info["url"].str ?: return@mapNotNull null
            val mime = info["mime"].str.orEmpty()
            if (!mime.startsWith("audio/") && !MediaKinds.isAudio(url)) return@mapNotNull null
            val title = (page["title"].str ?: "").removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
            MediaItem(url, title, info["descriptionurl"].str)
        }
    }
}
