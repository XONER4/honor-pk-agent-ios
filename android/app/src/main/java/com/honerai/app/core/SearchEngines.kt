package com.honerai.app.core

import com.honerai.app.data.WebSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// media: параллельный поиск сразу в нескольких поисковиках (Яндекс, Google, Bing,
// DuckDuckGo, Brave, Википедия), слияние выдач и ранжирование.

/** Поисковик: идентификатор хранится в WebSource.engines, подписи — для значков в «Источниках». */
enum class SearchEngine(val id: String, val titleRU: String, val titleEN: String) {
    YANDEX("yandex", "Яндекс", "Yandex"),
    GOOGLE("google", "Google", "Google"),
    BING("bing", "Bing", "Bing"),
    DUCKDUCKGO("ddg", "DDG", "DDG"),
    BRAVE("brave", "Brave", "Brave"),
    WIKIPEDIA("wikipedia", "Википедия", "Wikipedia");

    fun title(english: Boolean): String = if (english) titleEN else titleRU

    companion object {
        private val byId = entries.associateBy { it.id }
        fun from(id: String): SearchEngine? = byId[id]

        /** Подписи для строки хода поиска: «Яндекс, Google, Bing». */
        fun titles(ids: Collection<String>, english: Boolean = false): String =
            ids.mapNotNull { from(it) }.sortedBy { it.ordinal }.joinToString(", ") { it.title(english) }
    }
}

/** Разбор выдачи: результаты по порядку и признак «поисковик закрылся проверкой/согласием». */
class EngineParse(val results: List<WebSource>, val blocked: Boolean = false)

/** Разборщики HTML-выдачи. Без сети — проверяются модульными тестами на сохранённых страницах. */
object SearchEngineParsers {
    private const val MAX_RESULTS = 12

    private fun clean(text: String): String = text.replace(Regex("[\\s\\u00a0]+"), " ").trim()

    private fun http(raw: String, base: String): HttpUrl? {
        val value = WebPageText.decodeEntities(raw.trim())
        if (value.isEmpty() || value.startsWith("javascript:") || value.startsWith("#")) return null
        val absolute = if (value.startsWith("//")) "https:$value" else value
        val url = absolute.toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(absolute) ?: return null
        return url.takeIf { WebPageText.isPublicWebURL(it) }
    }

    private fun addUnique(into: MutableList<WebSource>, seen: HashSet<String>, url: HttpUrl, title: String, snippet: String) {
        val key = SearchMerge.normalizedKey(url.toString()) ?: return
        if (title.length < 3 || !seen.add(key)) return
        into.add(WebSource(title = title.take(180), url = url.toString(), snippet = snippet.take(600)))
    }

    // ---- Яндекс ----

    private val yandexCaptcha = listOf("showcaptcha", "smartcaptcha", "checkboxcaptcha", "вы не робот", "подтвердите, что запросы отправляли вы")

    fun yandex(html: String, finalUrl: String = "https://yandex.ru/search/"): EngineParse {
        val lower = html.take(60_000).lowercase()
        if (finalUrl.contains("showcaptcha") || yandexCaptcha.any { lower.contains(it) }) return EngineParse(emptyList(), blocked = true)
        val document = Jsoup.parse(html, finalUrl)
        val results = mutableListOf<WebSource>()
        val seen = HashSet<String>()
        for (item in document.select("li.serp-item, div.serp-item, li[data-cid]")) {
            // Реклама: метка «Реклама» или ссылки рекламной сети.
            if (item.select(".label, .OrganicTextContentSpan ~ span, [class*=Label]").any { it.text().trim().equals("Реклама", true) }) continue
            val link = item.select("a.OrganicTitle-Link, a.organic__url, h2 a[href], a.Link[href]").firstOrNull { anchor ->
                val url = http(anchor.attr("href"), finalUrl) ?: return@firstOrNull false
                !isYandexService(url)
            } ?: continue
            val url = http(link.attr("href"), finalUrl) ?: continue
            val title = clean(item.selectFirst(".OrganicTitleContentSpan, h2")?.text() ?: link.text())
            val snippet = clean(item.selectFirst(".OrganicTextContentSpan, .organic__content-wrapper, .text-container, .Organic-ContentWrapper, .TextContainer")?.text().orEmpty())
            addUnique(results, seen, url, title, snippet)
            if (results.size >= MAX_RESULTS) break
        }
        return EngineParse(results)
    }

    private fun isYandexService(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        if (host.contains("yabs.yandex") || host.startsWith("an.yandex") || host.startsWith("passport.yandex")) return true
        if (!host.contains("yandex.")) return false
        val path = url.encodedPath
        return listOf("/clck", "/search", "/images", "/video", "/an/", "/count").any { path.startsWith(it) }
    }

    // ---- Google ----

    fun google(html: String, finalUrl: String = "https://www.google.com/search"): EngineParse {
        val final = finalUrl.toHttpUrlOrNull()
        if (final != null && (final.host.startsWith("consent.") || final.encodedPath.startsWith("/sorry"))) return EngineParse(emptyList(), blocked = true)
        val lower = html.take(80_000).lowercase()
        if (lower.contains("consent.google.") && (lower.contains("before you continue") || lower.contains("прежде чем перейти"))) return EngineParse(emptyList(), blocked = true)
        if (lower.contains("unusual traffic") || lower.contains("необычный трафик") || lower.contains("id=\"captcha-form\"")) return EngineParse(emptyList(), blocked = true)
        val document = Jsoup.parse(html, finalUrl)
        val results = mutableListOf<WebSource>()
        val seen = HashSet<String>()
        for (anchor in document.select("a[href]")) {
            val heading = anchor.selectFirst("h3, div[role=heading]")
            val href = anchor.attr("href")
            // Без заголовка берём только ссылки-переадресации с осмысленным текстом (старая разметка).
            if (heading == null && !(href.startsWith("/url?") && anchor.text().trim().length >= 15)) continue
            val url = googleTarget(href, finalUrl) ?: continue
            val title = clean(heading?.text() ?: anchor.text())
            val container = anchor.closest("div.g, div.MjjYud, div.Gx5Zad, div.xpd, div.ezO2md")
            val snippet = container?.let { box ->
                box.selectFirst("div.VwiC3b, div.BNeawe.s3v9rd, span.aCOpRe, div.IsZvec, div[data-sncf]")?.text()
                    ?: box.text().replace(title, "")
            }.orEmpty()
            addUnique(results, seen, url, title, clean(snippet))
            if (results.size >= MAX_RESULTS) break
        }
        return EngineParse(results)
    }

    private fun googleTarget(href: String, base: String): HttpUrl? {
        var url = http(href, base) ?: return null
        if (isGoogleHost(url.host) && url.encodedPath == "/url") {
            url = (url.queryParameter("q") ?: url.queryParameter("url"))?.toHttpUrlOrNull() ?: return null
            if (!WebPageText.isPublicWebURL(url)) return null
        }
        val host = url.host.lowercase()
        if (isGoogleHost(host)) return null
        if (host.endsWith("googleusercontent.com") || host.endsWith("gstatic.com")) return null
        return url
    }

    /** google.com, google.ru и их поддомены (справка, вход, карты) — это служебные ссылки, не результаты. */
    private val googleHost = Regex("^(?:[a-z0-9-]+\\.)*google\\.(?:[a-z]{2,3}|co\\.[a-z]{2}|com\\.[a-z]{2})$")
    private fun isGoogleHost(host: String) = googleHost.matches(host.lowercase())

    // ---- Bing ----

    fun bing(html: String, finalUrl: String = "https://www.bing.com/search"): EngineParse {
        val lower = html.take(40_000).lowercase()
        if (lower.contains("/challenge/") || lower.contains("verify you are human")) return EngineParse(emptyList(), blocked = true)
        val document = Jsoup.parse(html, finalUrl)
        val results = mutableListOf<WebSource>()
        val seen = HashSet<String>()
        for (item in document.select("li.b_algo")) {
            val anchor = item.selectFirst("h2 a[href]") ?: continue
            val url = bingTarget(anchor.attr("href"), finalUrl) ?: continue
            val snippet = item.selectFirst(".b_caption p, p.b_lineclamp2, p.b_lineclamp3, p.b_lineclamp4, .b_algoSlug, p")?.text().orEmpty()
            addUnique(results, seen, url, clean(anchor.text()), clean(snippet))
            if (results.size >= MAX_RESULTS) break
        }
        return EngineParse(results)
    }

    /** Ссылки Bing вида bing.com/ck/a?…&u=a1<base64> ведут через переадресацию — достаём настоящий адрес. */
    fun bingTarget(href: String, base: String = "https://www.bing.com/"): HttpUrl? {
        val url = http(href, base) ?: return null
        val host = url.host.lowercase()
        if (host.endsWith("bing.com")) {
            val encoded = url.queryParameter("u") ?: return null
            val payload = encoded.removePrefix("a1")
            val decoded = payload.replace('-', '+').replace('_', '/').decodeBase64()?.utf8() ?: return null
            return decoded.toHttpUrlOrNull()?.takeIf { WebPageText.isPublicWebURL(it) && !it.host.endsWith("bing.com") }
        }
        if (host.endsWith("microsoft.com") && url.encodedPath.startsWith("/rewards")) return null
        return url
    }

    // ---- DuckDuckGo ----

    fun duckDuckGo(html: String): EngineParse {
        val lower = html.take(20_000).lowercase()
        if (lower.contains("anomaly-modal") || lower.contains("bots use duckduckgo too")) return EngineParse(emptyList(), blocked = true)
        val seen = HashSet<String>()
        val results = WebPageText.searchResults(html).filter { source ->
            val key = SearchMerge.normalizedKey(source.url)
            key != null && seen.add(key)
        }.take(MAX_RESULTS)
        return EngineParse(results)
    }
}

/** Выдача одного поисковика: позиция результата — его номер в списке. */
class EngineResult(val engine: SearchEngine, val sources: List<WebSource>, val blocked: Boolean = false)

/** Слияние выдач: одинаковые адреса склеиваются, выше — найденное большим числом поисковиков. */
object SearchMerge {
    private val trackingParameters = Regex("^(?:utm_.*|fbclid|gclid|yclid|ysclid|_openstat|from|ref|ref_src|spm|mc_cid|mc_eid)$", RegexOption.IGNORE_CASE)
    private val skippedHostLabels = setOf("www", "m", "mobile", "amp")

    /** Ключ сравнения адресов: без схемы, www./m., якоря, меток трекинга и хвостового «/». */
    fun normalizedKey(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val labels = url.host.lowercase().split('.')
        // Метку «m»/«www» убираем, но не последние две части домена (m.ru остаётся m.ru).
        val host = labels.filterIndexed { index, label -> index >= labels.size - 2 || label !in skippedHostLabels }.joinToString(".")
        var path = url.encodedPath.trimEnd('/').lowercase()
        if (path.endsWith("/index.html") || path.endsWith("/index.php")) path = path.substringBeforeLast('/')
        val query = (0 until url.querySize)
            .map { url.queryParameterName(it) to url.queryParameterValue(it) }
            .filter { !trackingParameters.matches(it.first) }
            .sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second.orEmpty()}" }
        return host + path + if (query.isEmpty()) "" else "?$query"
    }

    private class Entry(var source: WebSource, var bestPosition: Int) {
        val engines = LinkedHashSet<SearchEngine>()
    }

    /**
     * Общая выдача: сначала адреса, которые вернули несколько поисковиков, затем по лучшей позиции,
     * при равенстве — по релевантности запросу. Нерелевантное (реклама, «Bing quiz») отбрасывается.
     */
    fun merge(results: List<EngineResult>, query: String?): List<WebSource> {
        val entries = LinkedHashMap<String, Entry>()
        for (result in results) {
            for ((position, source) in result.sources.withIndex()) {
                val key = normalizedKey(source.url) ?: continue
                val entry = entries.getOrPut(key) { Entry(source, position) }
                entry.engines.add(result.engine)
                if (position < entry.bestPosition) entry.bestPosition = position
                // Берём более полную выдержку и https-адрес.
                val current = entry.source
                val better = current.copy(
                    title = if (current.title.length < 8 && source.title.length > current.title.length) source.title else current.title,
                    snippet = if (source.snippet.length > current.snippet.length) source.snippet else current.snippet,
                    url = if (!current.url.startsWith("https://") && source.url.startsWith("https://")) source.url else current.url,
                )
                entry.source = better
            }
        }
        val scored = entries.values.map { entry ->
            val engines = entry.engines.sortedBy { it.ordinal }.map { it.id }
            val source = entry.source.copy(engines = engines)
            Triple(source, entry, if (query == null) 1 else SearchRelevance.score(source, query))
        }.filter { it.third > 0 }
        return scored.sortedWith(
            compareByDescending<Triple<WebSource, Entry, Int>> { it.second.engines.size }
                .thenBy { it.second.bestPosition }
                .thenByDescending { it.third }
        ).map { it.first }
    }

    /**
     * Хватит ли уже собранного, чтобы не ждать медленные поисковики: простой запрос
     * отвечает так же быстро, как раньше, а трудный получает всё время.
     */
    fun enough(enginesWithResults: Int, relevant: Int, elapsedMillis: Long): Boolean =
        (enginesWithResults >= 3 && relevant >= 10) ||
            (elapsedMillis >= 2_500 && enginesWithResults >= 2 && relevant >= 5) ||
            (elapsedMillis >= 4_000 && relevant >= 3)
}

/** Параллельный опрос поисковиков с лимитом на каждый и на весь поиск. */
class MultiEngineSearch(
    private val client: WebSearchClient,
    private val engines: List<SearchEngine> = SearchEngine.entries,
    private val perEngineMillis: Long = 4_000,
    private val overallMillis: Long = 6_000,
) {
    class Outcome(val sources: List<WebSource>, val responded: List<SearchEngine>, val blocked: List<SearchEngine>)

    suspend fun search(query: String): Outcome {
        val channel = Channel<EngineResult>(Channel.UNLIMITED)
        // Отдельная область: медленный поисковик отменяется и не задерживает ответ,
        // даже если его сокет ещё дочитывает страницу.
        val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            for (engine in engines) {
                engineScope.launch {
                    val result = withTimeoutOrNull(perEngineMillis) {
                        runCatching { run(engine, query) }.getOrElse { if (it is CancellationException) throw it; EngineResult(engine, emptyList()) }
                    } ?: EngineResult(engine, emptyList())
                    channel.trySend(result)
                }
            }
            val collected = mutableListOf<EngineResult>()
            var merged = emptyList<WebSource>()
            val start = System.currentTimeMillis()
            while (collected.size < engines.size) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= overallMillis) break
                // Проверяем каждые 250 мс: условие раннего выхода зависит и от времени.
                val next = withTimeoutOrNull(minOf(250L, overallMillis - elapsed)) { channel.receive() }
                if (next != null) {
                    collected.add(next)
                    merged = withContext(Dispatchers.Default) { SearchMerge.merge(collected, query) }
                }
                val withResults = collected.count { it.sources.isNotEmpty() }
                if (SearchMerge.enough(withResults, merged.size, System.currentTimeMillis() - start)) break
            }
            return Outcome(merged, collected.filter { it.sources.isNotEmpty() }.map { it.engine }, collected.filter { it.blocked }.map { it.engine })
        } finally {
            // Медленные поисковики больше не нужны.
            engineScope.cancel()
            channel.close()
        }
    }

    private suspend fun run(engine: SearchEngine, query: String): EngineResult = when (engine) {
        SearchEngine.YANDEX -> html(engine, "https://yandex.ru/search/".toHttpUrl().newBuilder()
            .addQueryParameter("text", query.take(400)).addQueryParameter("lr", "213").build()) { html, url -> SearchEngineParsers.yandex(html, url) }
        SearchEngine.GOOGLE -> html(engine, "https://www.google.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(400)).addQueryParameter("hl", "ru").addQueryParameter("num", "10").build()) { html, url -> SearchEngineParsers.google(html, url) }
        SearchEngine.DUCKDUCKGO -> html(engine, "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(400)).addQueryParameter("kl", "ru-ru").build()) { html, _ -> SearchEngineParsers.duckDuckGo(html) }
        SearchEngine.BING -> coroutineScope {
            // HTML-выдача и RSS параллельно: RSS надёжнее, HTML полнее.
            val page = async {
                html(engine, "https://www.bing.com/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query.take(400)).addQueryParameter("setlang", "ru").build()) { html, url -> SearchEngineParsers.bing(html, url) }
            }
            val rss = async { runCatching { client.bingResults(query) }.getOrDefault(emptyList()) }
            val first = page.await()
            val combined = LinkedHashMap<String, WebSource>()
            for (source in first.sources + rss.await()) {
                val key = SearchMerge.normalizedKey(source.url) ?: continue
                combined.putIfAbsent(key, source)
            }
            EngineResult(engine, combined.values.toList(), first.blocked && combined.isEmpty())
        }
        SearchEngine.BRAVE -> EngineResult(engine, client.braveResults(query))
        SearchEngine.WIKIPEDIA -> EngineResult(engine, client.wikipediaResults(query))
    }

    private suspend fun html(engine: SearchEngine, url: HttpUrl, parse: (String, String) -> EngineParse): EngineResult {
        val timeout = ((perEngineMillis + 999) / 1000).coerceAtLeast(2)
        val document = client.fetch(url.toString(), 1_500_000, timeout) ?: return EngineResult(engine, emptyList())
        val parsed = withContext(Dispatchers.Default) { parse(document.text, document.url) }
        return EngineResult(engine, parsed.results, parsed.blocked)
    }
}
