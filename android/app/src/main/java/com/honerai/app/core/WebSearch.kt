package com.honerai.app.core

import com.honerai.app.data.DeepSeekConfiguration
import com.honerai.app.data.WebSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Нужен ли выход в интернет до ответа. Поиск включается только по явной нужде:
 * прямая просьба, ссылка в тексте, погода или вопрос о свежих данных.
 */
object SearchIntent {
    private val explicit = Regex("(?iu)(?:\\bнайд|\\bпоищ|\\bпогугл|\\bзагугл|\\bпоры(?:й|л)|посмотри в интернете|поиск в интернете|найти в интернете|\\bsearch\\b|\\blook\\s*up\\b|google it)")
    private val freshness = Regex("(?iu)(?:\\bсегодня\\b|\\bвчера\\b|\\bсейчас\\b|\\bактуальн|\\bпоследн(?:ие|их|яя|юю)|\\bновост|\\bкурс\\b|\\bкотировк|\\bстоимость\\b|\\bцен[аыу]\\b|\\bпогод|\\bпробк|\\bсвежие данные|\\bна этой неделе\\b|\\bв этом месяце\\b|20(?:2[4-9]|[3-9]\\d))")
    private val selfSufficient = Regex("(?iu)^\\s*(?:что такое|кто такой|кто такие|объясни|расскажи|как работает|как сделать|как настроить|напиши|сделай|создай|переведи|посчитай|реши|определение|в чём разница|сравни|придумай|составь)")
    private val weatherRequest = Regex("(?iu)(?:какая\\s+погода|погода\\s+(?:в|на|сегодня|завтра)|прогноз\\s+погоды|сколько\\s+градусов|температура\\s+(?:воздуха|на улице)|будет\\s+ли\\s+дождь|weather\\s+(?:in|today|tomorrow))")

    fun needsSearch(query: String, searchToggleOn: Boolean, hasAttachments: Boolean = false): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        if (explicit.containsMatchIn(trimmed)) return true
        if (WebPageText.urls(trimmed).isNotEmpty()) return true
        if (weatherRequest.containsMatchIn(trimmed) && WeatherIntent.location(trimmed) != null) return true
        if (selfSufficient.containsMatchIn(trimmed)) return false
        if (freshness.containsMatchIn(trimmed)) return true
        return searchToggleOn
    }
}

interface WebSearching {
    suspend fun search(query: String): List<WebSource>
}

interface SearchURLDiscovering {
    suspend fun candidates(query: String): List<String>
}

/** Чтение страницы с JavaScript (на Android — невидимый WebView). */
interface PageRenderer {
    class Rendered(val url: String, val title: String, val text: String, val html: String)
    suspend fun render(url: String, timeoutMillis: Long = 12_000): Rendered?
}

/** Ход поиска: сколько нашлось и какие сайты сейчас читаются. */
sealed class SearchProgress {
    // media: engines — какие поисковики ответили.
    data class Found(val count: Int, val engines: List<String> = emptyList()) : SearchProgress()
    data class Reading(val sites: List<String>) : SearchProgress()
    data class Read(val count: Int, val sites: List<String>) : SearchProgress()
}

typealias PageFetcher = suspend (url: String, maximumBytes: Int, timeoutSeconds: Long) -> Fetched?

private fun WebSource.withRaw(html: String?): WebSource = also { it.rawHTML = html }

/** Копия источника вместе с разметкой (rawHTML не входит в copy()). */
internal fun WebSource.copyKeepingRaw(
    title: String = this.title, url: String = this.url, snippet: String = this.snippet,
    content: String? = this.content, fetchedAt: Instant? = this.fetchedAt,
): WebSource = copy(title = title, url = url, snippet = snippet, content = content, fetchedAt = fetchedAt).withRaw(rawHTML)

val WebSource.host: String? get() = url.toHttpUrlOrNull()?.host

/**
 * Поиск по нескольким поисковикам (media: Яндекс, Google, Bing, DuckDuckGo, Brave, Википедия —
 * см. MultiEngineSearch) и чтение найденных страниц.
 */
class WebSearchClient(
    val urlDiscovery: SearchURLDiscovering? = null,
    val renderer: PageRenderer? = null,
    private val fetcher: PageFetcher = { url, max, timeout -> fetchBytes(url, max, timeout) },
) : WebSearching {

    override suspend fun search(query: String): List<WebSource> = search(query, null)

    suspend fun search(query: String, progress: ((SearchProgress) -> Unit)?): List<WebSource> {
        WeatherIntent.location(query)?.let { location ->
            return listOf(WeatherClient(fetcher).forecast(location))
        }
        val direct = WebPageText.urls(query)
        if (direct.isNotEmpty()) {
            val sources = direct.take(5).map { WebSource(title = it.host, url = it.toString(), snippet = "Ссылка пользователя") }
            val fetched = readPages(sources, 5)
            currentCoroutineContext().ensureActive()
            if (fetched.none { it.content != null }) throw HonorError.SearchUnavailable()
            return fetched
        }
        val searchQuery = SearchRelevance.compactQuery(query)
        // media: Яндекс, Google, Bing, DuckDuckGo, Brave и Википедия параллельно (≤ 4 с на каждый, ≤ 6 с всего),
        // выдачи склеиваются по адресу; фильтр релевантности внутри слияния отсекает мусор вида «Bing quiz».
        val outcome = MultiEngineSearch(this).search(searchQuery)
        currentCoroutineContext().ensureActive()
        val ranked = outcome.sources
        if (ranked.isEmpty()) {
            val discovery = urlDiscovery ?: throw HonorError.SearchUnavailable()
            val suggested = discovery.candidates(query)
            currentCoroutineContext().ensureActive()
            val fallback = suggested.take(3).map {
                WebSource(title = it.toHttpUrlOrNull()?.host ?: "Страница", url = it,
                    snippet = "Проверенная по прямой ссылке страница; не поисковая выдержка")
            }
            val fetched = readPages(fallback, 3, 8).filter { it.content != null }
            currentCoroutineContext().ensureActive()
            if (fetched.isEmpty()) throw HonorError.SearchUnavailable()
            return fetched
        }
        progress?.invoke(SearchProgress.Found(ranked.size, outcome.responded.map { it.id }))
        progress?.invoke(SearchProgress.Reading(ranked.take(5).mapNotNull { it.host }))
        val fetched = readPages(ranked.take(12), 5)
        currentCoroutineContext().ensureActive()
        val read = fetched.filter { it.content != null }
        progress?.invoke(SearchProgress.Read(read.size, read.mapNotNull { it.host }))
        return fetched
    }

    suspend fun bingResults(query: String): List<WebSource> {
        val url = "https://www.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(400)).addQueryParameter("format", "rss")
            .addQueryParameter("setlang", "ru-RU").build()
        val document = fetch(url.toString(), 1_500_000) ?: return emptyList()
        return withContext(Dispatchers.Default) { RSSResultsParser.parse(document.text) }
    }

    suspend fun duckDuckGoResults(query: String): List<WebSource> {
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(400)).addQueryParameter("kl", "ru-ru").build()
        val document = fetch(url.toString(), 1_500_000) ?: return emptyList()
        return withContext(Dispatchers.Default) { WebPageText.searchResults(document.text) }
    }

    /** Выдача Brave Search: ссылки результатов из HTML. */
    suspend fun braveResults(query: String): List<WebSource> {
        val url = "https://search.brave.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(400)).addQueryParameter("source", "web").build()
        val document = fetch(url.toString(), 1_500_000, 8) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            WebPageText.resultLinks(document.text, listOf("brave.com", "brave.app")).take(10)
        }
    }

    /** Википедия (русская, затем английская): надёжный источник для справочных вопросов. */
    suspend fun wikipediaResults(query: String): List<WebSource> {
        for (language in listOf("ru", "en")) {
            val url = "https://$language.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("list", "search")
                .addQueryParameter("srsearch", query.take(300)).addQueryParameter("format", "json")
                .addQueryParameter("srlimit", "2").addQueryParameter("utf8", "1").build()
            val document = fetch(url.toString(), 400_000, 8) ?: continue
            val items = parseJson(document.text)["query"]["search"].arr ?: continue
            if (items.isEmpty()) continue
            return items.mapNotNull { item ->
                val title = item["title"].str ?: return@mapNotNull null
                val page = HttpUrl.Builder().scheme("https").host("$language.wikipedia.org")
                    .addPathSegment("wiki").addPathSegment(title.replace(' ', '_')).build()
                WebSource(title = "$title — Википедия", url = page.toString(), snippet = WebPageText.extract(item["snippet"].str.orEmpty()))
            }
        }
        return emptyList()
    }

    /** Параллельное чтение страниц: текст, заголовок и разметка для картинок. */
    suspend fun readPages(sources: List<WebSource>, limit: Int, timeoutSeconds: Long = 10): List<WebSource> = coroutineScope {
        val head = sources.take(limit)
        val read = head.map { source ->
            async {
                val document = fetch(source.url, 1_500_000, timeoutSeconds) ?: return@async source
                withContext(Dispatchers.Default) {
                    val html = document.text
                    val parsed = WebPageText.parse(html)
                    if (parsed.text.length >= 100 && !WebPageText.looksLikeChallenge(parsed.text)) {
                        source.copy(content = parsed.text.take(9000), fetchedAt = Instant.now(), url = document.url,
                            title = parsed.title ?: source.title).withRaw(html.take(400_000))
                    } else source
                }
            }
        }.awaitAll()
        val result = read + sources.drop(limit)
        val seen = HashSet<String>()
        result.filter { seen.add(it.url) }
    }

    /** Загрузка страницы: только публичные адреса и успешный ответ. */
    suspend fun fetch(url: String, maximumBytes: Int, timeoutSeconds: Long = 10): Fetched? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!WebPageText.isPublicWebURL(parsed)) return null
        val document = fetcher(url, maximumBytes, timeoutSeconds) ?: return null
        if (document.status !in 200..299) return null
        val final = document.url.toHttpUrlOrNull()
        if (final != null && !WebPageText.isPublicWebURL(final)) return null
        return document
    }

    companion object {
        /** Контекст для модели: пронумерованные источники и картинки с прочитанных страниц. */
        fun context(sources: List<WebSource>): String {
            val images = mutableListOf<String>()
            val seen = HashSet<String>()
            for (source in sources) {
                val html = source.rawHTML ?: continue
                for (url in WebPageText.imageURLs(html, source.url).take(4)) if (seen.add(url)) images.add(url)
            }
            val body = sources.mapIndexed { index, source ->
                val provenance = if (source.content == null) "Только поисковая выдержка; страница не прочитана."
                else "Страница/структурированные данные получены: ${source.fetchedAt?.let { DateTimeFormatter.ISO_INSTANT.format(it.truncatedTo(ChronoUnit.SECONDS)) } ?: "в этом запросе"}."
                "[${index + 1}] ${source.title}\nURL: ${source.url}\n$provenance\n${source.content ?: source.snippet}"
            }.joinToString("\n\n")
            if (images.isEmpty()) return body
            val list = images.take(12).joinToString("\n") { "- $it" }
            return body + "\n\n\nИзображения на прочитанных страницах. Если по теме ответа уместна картинка, вставь её в ответ строкой ![короткая подпись](ссылка) — приложение покажет изображение прямо в чате. Бери только ссылки из списка ниже, не выдумывай адреса. Если ничего не подходит, изображения не добавляй.\n$list"
        }
    }
}

/** Подсказка модели: первичные адреса, когда поисковики недоступны. Источник появляется только после чтения. */
class DeepSeekURLDiscovery(private val configuration: DeepSeekConfiguration) : SearchURLDiscovering {
    override suspend fun candidates(query: String): List<String> = withContext(Dispatchers.IO) {
        if (configuration.apiKey.isEmpty()) throw HonorError.SearchUnavailable()
        val instruction = "Нужно найти первичные источники для запроса пользователя, когда поисковые сайты недоступны. Верни JSON вида {\"urls\":[\"https://...\"]} — не больше трёх конкретных, уверенно известных тебе URL официальных страниц документации, технических характеристик или первичных справочных источников. Не выдумывай неизвестные адреса и не отвечай на вопрос. Если точных известных URL нет, верни пустой массив. Предложенные адреса будут проверены реальным HTTP-запросом, поэтому они не считаются уже найденными источниками. Не включай поисковые выдачи, ссылки с API-ключами, локальные адреса или личные данные."
        val payload: JsonObject = buildJsonObject {
            put("model", configuration.model)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("stream", false)
            put("max_tokens", 600)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", instruction) })
                add(buildJsonObject { put("role", "user"); put("content", query.take(1600)) })
            })
        }
        val request = Request.Builder().url(configuration.baseURL.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${configuration.apiKey}")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val raw = try {
            HonerHttp.web(6).newCall(request).await().use { response ->
                if (response.code != 200) throw HonorError.SearchUnavailable()
                response.body?.string().orEmpty()
            }
        } catch (e: java.io.IOException) {
            throw HonorError.SearchUnavailable()
        }
        if (raw.length >= 32_000) throw HonorError.SearchUnavailable()
        val content = parseJson(raw)["choices"].arr?.firstOrNull()["message"]["content"].str ?: throw HonorError.SearchUnavailable()
        val urls = parseJson(content)["urls"].arr ?: throw HonorError.SearchUnavailable()
        val seen = HashSet<String>()
        urls.mapNotNull { it.str?.toHttpUrlOrNull() }
            .filter { WebPageText.isPublicWebURL(it) && seen.add(it.toString()) }
            .take(3).map { it.toString() }
    }
}

object SearchRelevance {
    /** Служебные слова запроса: поисковику нужна тема, а не просьба. */
    private val commandWords = setOf(
        "создай", "создать", "сделай", "сделать", "составь", "составить", "собери", "собрать",
        "найди", "найти", "поищи", "пох", "покажи", "показать", "дай", "дайте",
        "таблицу", "таблица", "таблицей", "список", "списком", "подборку", "подборка",
        "лучших", "лучшие", "лучший", "топ", "мне", "нам", "пожалуйста", "нужно", "надо",
        "можешь", "можете", "хочу", "хотел", "информацию", "информация", "данные", "данных",
    )
    private val commandPrefix = Regex("(?iu)^(?:please\\s+)?(?:find|search for|look up|найди|найдите|поищи|покажи|пожалуйста)[,: ]+")
    private val stops = setOf("какая", "какой", "какие", "найди", "найти", "пожалуйста", "расскажи", "сейчас", "сегодня", "нужно",
        "можешь", "сделай", "покажи", "информацию", "интернет", "поиск", "узнай", "what", "which", "tell", "please", "search",
        "about", "latest", "the", "for", "and")
    private val noise = listOf("bing quiz", "bingquiz", "bing rewards", "bing homepage quiz", "microsoft rewards")

    fun compactQuery(query: String): String {
        val firstSentence = query.split(". ").firstOrNull() ?: query
        var stripped = commandPrefix.replace(firstSentence, "")
        val words = stripped.split(' ', '\t', ' ').filter { it.isNotEmpty() }
        val kept = words.filter { word ->
            val clean = word.lowercase().trim { RussianTextPolicy.isPunctuation(it) }
            clean !in commandWords
        }
        if (kept.isNotEmpty()) stripped = kept.joinToString(" ")
        return stripped.trim().take(300)
    }

    fun score(source: WebSource, query: String): Int {
        val q = query.lowercase()
        val parsed = source.url.toHttpUrlOrNull()
        val text = (source.title + " " + source.snippet + " " + (parsed?.host ?: "") + " " + (parsed?.encodedPath ?: "")).lowercase()
        if (!q.contains("bing") && noise.any { text.contains(it) }) return 0
        val words = q.split(Regex("[^\\p{L}]+")).filter { it.length >= 3 && it !in stops }
        if (words.isEmpty()) return 1
        val hits = words.count { text.contains(it.take(5)) }
        if (hits == 0 || hits.toDouble() / words.size < 0.2) return 0
        return hits * 10 + if (parsed?.host?.endsWith(".gov") == true) 2 else 0
    }
}

/** Текст страниц, ссылки, заголовки и картинки. */
object WebPageText {
    private val blockTags = setOf("p", "div", "article", "section", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "tr")
    private val spaces = Regex("[\\t \\u00a0]+")

    fun isPublicWebURL(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        if (host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (host in setOf("localhost", "::1", "0.0.0.0") || host.endsWith(".local")) return false
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: 0
            if (second in 16..31) return false
        }
        return true
    }

    fun isPublicWebURL(url: String): Boolean = url.toHttpUrlOrNull()?.let { isPublicWebURL(it) } ?: false

    private val urlPattern = Regex("(?i)https?://[^\\s<>\"\\[\\]]+")

    fun urls(text: String): List<HttpUrl> = urlPattern.findAll(text).mapNotNull { match ->
        val value = match.value.trim('.', ',', ';', '!', '?', ')')
        value.toHttpUrlOrNull()?.takeIf { isPublicWebURL(it) }
    }.toList()

    private val metaImage = listOf(
        Regex("(?i)<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        Regex("(?i)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"']"),
    )
    private val imgTag = Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']")

    /** Ссылки на изображения со страницы: og:image, twitter:image и обычные <img>. */
    fun imageURLs(html: String, base: String): List<String> {
        val baseUrl = base.toHttpUrlOrNull()
        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        fun append(raw: String) {
            val value = decodeEntities(raw.trim())
            if (value.isEmpty() || value.startsWith("data:")) return
            val url = (baseUrl?.resolve(value) ?: value.toHttpUrlOrNull()) ?: return
            if (!isPublicWebURL(url)) return
            val text = url.toString()
            if (!seen.add(text)) return
            val lowered = text.lowercase()
            // Иконки, логотипы и трекеры в ответе не нужны.
            if (listOf("logo", "icon", "sprite", "pixel", "avatar").any { lowered.contains(it) }) return
            result.add(text)
        }
        for (pattern in metaImage) pattern.findAll(html).forEach { append(it.groupValues[1]) }
        imgTag.findAll(html).forEach { append(it.groupValues[1]) }
        return result.filter { url ->
            val path = url.toHttpUrlOrNull()?.encodedPath?.lowercase().orEmpty()
            listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic").any { path.endsWith(it) }
        }
    }

    class Parsed(val text: String, val title: String?)

    /** Разбор страницы один раз: текст и заголовок. */
    fun parse(html: String): Parsed {
        val document = Jsoup.parse(html)
        val title = document.title().trim().takeIf { it.isNotEmpty() }?.let { clean(it) }?.take(180)
        return Parsed(extract(document), title)
    }

    fun extract(html: String): String = extract(Jsoup.parse(html))

    /** Текст страницы без скриптов, меню и подвалов; блоки — с новой строки. */
    fun extract(document: Document): String {
        document.select("script, style, nav, footer, header, noscript, svg, form").remove()
        val builder = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> builder.append(node.wholeText)
                    is Element -> if (node.normalName() in blockTags) builder.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blockTags) builder.append('\n')
            }
        }, document)
        return clean(builder.toString())
    }

    private fun clean(text: String): String = text.lines()
        .map { spaces.replace(it, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private val titlePattern = Regex("(?is)<title[^>]*>(.*?)</title>")

    fun title(html: String): String? {
        val match = titlePattern.find(html) ?: return null
        return extract(match.groupValues[1]).take(180)
    }

    fun looksLikeChallenge(text: String): Boolean {
        val sample = text.take(1500).lowercase()
        return listOf("verify you are human", "enable javascript and cookies to continue", "checking your browser",
            "подтвердите, что вы не робот", "access denied", "just a moment").any { sample.contains(it) }
    }

    private val ddgLinks = Regex("(?is)<a\\b[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
    private val ddgSnippets = Regex("(?is)<(?:a|div|span)\\b[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div|span)>")

    /** Выдача DuckDuckGo (HTML-версия). */
    fun searchResults(html: String): List<WebSource> {
        val links = ddgLinks.findAll(html).toList()
        val snippets = ddgSnippets.findAll(html).toList()
        return links.mapIndexedNotNull { index, match ->
            var link = decodeEntities(match.groupValues[1])
            if (link.startsWith("//")) link = "https:$link"
            val original = link.toHttpUrlOrNull() ?: return@mapIndexedNotNull null
            val redirected = original.queryParameter("uddg")?.toHttpUrlOrNull()
            val url = redirected ?: original
            if (!isPublicWebURL(url) || url.host.contains("duckduckgo.com")) return@mapIndexedNotNull null
            val snippet = if (index < snippets.size) extract(snippets[index].groupValues[1]) else ""
            WebSource(title = extract(match.groupValues[2]), url = url.toString(), snippet = snippet.take(1600))
        }
    }

    private val resultLink = Regex("(?is)<a\\b[^>]*href=[\"'](https?://[^\"'#]+)[\"'][^>]*>(.*?)</a>")

    /** Ссылки результатов из выдачи поисковика: внешние ссылки с осмысленным текстом. */
    fun resultLinks(html: String, excludingHostsContaining: List<String>): List<WebSource> {
        val seen = HashSet<String>()
        val result = mutableListOf<WebSource>()
        for (match in resultLink.findAll(html)) {
            val raw = decodeEntities(match.groupValues[1])
            val url = raw.toHttpUrlOrNull() ?: continue
            if (!isPublicWebURL(url)) continue
            val host = url.host.lowercase()
            if (excludingHostsContaining.any { host.contains(it) }) continue
            val title = extract(match.groupValues[2]).replace("\n", " ").trim()
            if (title.length < 8 || !seen.add(url.toString())) continue
            result.add(WebSource(title = title.take(180), url = url.toString(), snippet = title.take(300)))
        }
        return result
    }

    private val numericEntity = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val named = listOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'",
        "&apos;" to "'", "&nbsp;" to " ", "&ndash;" to "–", "&mdash;" to "—")

    fun decodeEntities(text: String): String {
        var result = text
        for ((entity, character) in named) result = result.replace(entity, character)
        return numericEntity.replace(result) { match ->
            val value = match.groupValues[1]
            val number = if (value.startsWith("x") || value.startsWith("X")) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
            if (number != null && Character.isValidCodePoint(number)) String(Character.toChars(number)) else match.value
        }
    }
}

object WeatherIntent {
    private val klin = Regex("(?iu)\\bклин(?:е|а|у|ом)?\\b|\\bklin\\b")
    private val place = Regex("(?iu)(?:\\bв|\\bво|\\bin|\\bfor)\\s+([\\p{L}][\\p{L} -]{1,60})")

    fun location(query: String): String? {
        val lower = query.lowercase()
        if (listOf("погод", "температур", "прогноз", "weather", "forecast").none { lower.contains(it) }) return null
        if (klin.containsMatchIn(lower)) return "Клин"
        if (lower.contains("москв") || lower.contains("moscow")) return "Москва"
        if (lower.contains("петербург") || lower.contains("saint petersburg")) return "Санкт-Петербург"
        val match = place.find(query) ?: return null
        var location = match.groupValues[1]
        for (suffix in listOf(" сегодня", " завтра", " сейчас", " на ", " в ", " today", " tomorrow", " this ")) {
            val index = location.indexOf(suffix, ignoreCase = true)
            if (index >= 0) location = location.substring(0, index)
        }
        return location.trim()
    }
}

/** Погода Open-Meteo: геокодирование и прогноз на 7 дней. */
class WeatherClient(private val fetcher: PageFetcher = { url, max, timeout -> fetchBytes(url, max, timeout) }) {
    private class Place(val name: String, val latitude: Double, val longitude: Double)

    suspend fun forecast(location: String): WebSource {
        val place = if (location == "Клин") Place("Клин, Московская область, Россия", 56.3333, 36.7333) else {
            val geo = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", location).addQueryParameter("count", "5")
                .addQueryParameter("language", "ru").addQueryParameter("format", "json").build()
            val data = fetcher(geo.toString(), 500_000, 10)
            if (data == null || data.status != 200) throw HonorError.SearchUnavailable()
            val result = parseJson(data.text)["results"].arr?.firstOrNull() ?: throw HonorError.SearchUnavailable()
            val latitude = result["latitude"].dbl ?: throw HonorError.SearchUnavailable()
            val longitude = result["longitude"].dbl ?: throw HonorError.SearchUnavailable()
            val name = listOfNotNull(result["name"].str, result["admin1"].str, result["country"].str).joinToString(", ")
            Place(name, latitude, longitude)
        }
        val api = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.latitude.toString()).addQueryParameter("longitude", place.longitude.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("forecast_days", "7").addQueryParameter("timezone", "auto")
            .addQueryParameter("wind_speed_unit", "ms").build()
        val data = fetcher(api.toString(), 500_000, 12)
        currentCoroutineContext().ensureActive()
        if (data == null || data.status != 200) throw HonorError.SearchUnavailable()
        val forecast = WeatherForecast.parse(data.text) ?: throw HonorError.SearchUnavailable()
        val text = forecast.russianDescription(place.name)
        return WebSource(title = "Погода: ${place.name} · Open-Meteo", url = api.toString(), snippet = text.take(500),
            content = text, fetchedAt = Instant.now())
    }
}

/** Ответ Open-Meteo и его описание по-русски. */
class WeatherForecast(
    val timezone: String,
    val currentTime: String,
    val temperature: Double,
    val apparent: Double?,
    val humidity: Double?,
    val precipitation: Double?,
    val weatherCode: Int,
    val wind: Double?,
    val dailyTime: List<String>,
    val dailyMax: List<Double?>,
    val dailyMin: List<Double?>,
    val dailyChance: List<Double?>?,
    val dailyCode: List<Int?>,
) {
    fun russianDescription(location: String): String {
        val lines = mutableListOf(
            "Место: $location. Часовой пояс: $timezone.",
            "Текущие модельные условия на $currentTime: ${condition(weatherCode)}; температура $temperature °C.",
        )
        apparent?.let { lines.add("Ощущается как $it °C.") }
        wind?.let { lines.add("Ветер $it м/с.") }
        humidity?.let { lines.add("Влажность $it%.") }
        precipitation?.let { lines.add("Осадки $it мм.") }
        for (index in dailyTime.indices.take(7)) {
            val low = dailyMin.getOrNull(index) ?: continue
            val high = dailyMax.getOrNull(index) ?: continue
            val code = dailyCode.getOrNull(index)
            var row = "${dailyTime[index]}: $low…$high °C; ${code?.let { condition(it) } ?: "нет данных об облачности"}"
            dailyChance?.getOrNull(index)?.let { row += "; вероятность осадков $it%" }
            lines.add("$row.")
        }
        lines.add("Источник Open-Meteo: расчёт прогностических моделей, не непосредственное измерение на метеостанции. Отвечай по указанным датам; не выдавай прогноз другого дня за сегодняшний.")
        return lines.joinToString("\n")
    }

    companion object {
        fun parse(json: String): WeatherForecast? {
            val root = parseJson(json) ?: return null
            val current = root["current"].obj ?: return null
            val daily = root["daily"].obj
            return WeatherForecast(
                timezone = root["timezone"].str ?: return null,
                currentTime = current["time"].str.orEmpty(),
                temperature = current["temperature_2m"].dbl ?: return null,
                apparent = current["apparent_temperature"].dbl,
                humidity = current["relative_humidity_2m"].dbl,
                precipitation = current["precipitation"].dbl,
                weatherCode = current["weather_code"].int ?: return null,
                wind = current["wind_speed_10m"].dbl,
                dailyTime = daily?.get("time").arr?.map { it.str.orEmpty() }.orEmpty(),
                dailyMax = daily?.get("temperature_2m_max").arr?.map { it.dbl }.orEmpty(),
                dailyMin = daily?.get("temperature_2m_min").arr?.map { it.dbl }.orEmpty(),
                dailyChance = daily?.get("precipitation_probability_max").arr?.map { it.dbl },
                dailyCode = daily?.get("weather_code").arr?.map { it.int }.orEmpty(),
            )
        }

        fun condition(code: Int): String = when (code) {
            0 -> "ясно"
            1 -> "преимущественно ясно"
            2 -> "переменная облачность"
            3 -> "пасмурно"
            45, 48 -> "туман"
            51, 53, 55, 56, 57 -> "морось"
            61, 63, 65, 66, 67 -> "дождь"
            71, 73, 75, 77 -> "снег"
            80, 81, 82 -> "ливень"
            85, 86 -> "снежные заряды"
            95, 96, 99 -> "гроза"
            else -> "код погоды $code"
        }
    }
}

/** Выдача Bing в формате RSS. */
object RSSResultsParser {
    private val tags = Regex("<[^>]+>")

    fun parse(xml: String): List<WebSource> {
        val document = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<WebSource>()
        for (item in document.select("item")) {
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val url = link.toHttpUrlOrNull() ?: continue
            if (results.any { it.url == url.toString() }) continue
            val title = clean(item.selectFirst("title")?.text().orEmpty())
            val snippet = clean(item.selectFirst("description")?.text().orEmpty()).take(1600)
            results.add(WebSource(title = title, url = url.toString(), snippet = snippet))
        }
        return results
    }

    private fun clean(text: String): String = tags.replace(text, "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").trim()
}
