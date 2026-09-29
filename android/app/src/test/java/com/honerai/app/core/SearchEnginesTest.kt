package com.honerai.app.core

import com.honerai.app.data.WebSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// media: разборщики выдачи поисковиков на сохранённых страницах, слияние и ранний выход.
class SearchEnginesTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/search/$name")) { "нет файла $name" }.readText()

    @Test fun yandexSkipsAdsAndOwnServices() {
        val parsed = SearchEngineParsers.yandex(fixture("yandex.html"))
        assertFalse(parsed.blocked)
        assertEquals(2, parsed.results.size)
        assertTrue(parsed.results[0].url.startsWith("https://ru.wikipedia.org/wiki/"))
        assertEquals("Эйфелева башня — Википедия", parsed.results[0].title)
        assertTrue(parsed.results[0].snippet.contains("330 метров"))
        assertTrue(parsed.results[1].url.contains("toureiffel.paris"))
        assertTrue(parsed.results.none { it.url.contains("yabs.yandex") || it.url.contains("yandex.ru/images") })
    }

    @Test fun yandexCaptchaIsSkippedGracefully() {
        val parsed = SearchEngineParsers.yandex(fixture("yandex_captcha.html"))
        assertTrue(parsed.blocked)
        assertTrue(parsed.results.isEmpty())
        assertTrue(SearchEngineParsers.yandex("<html></html>", "https://yandex.ru/showcaptcha?retpath=x").blocked)
    }

    @Test fun googleDecodesRedirectsAndSkipsServiceLinks() {
        val parsed = SearchEngineParsers.google(fixture("google.html"))
        assertFalse(parsed.blocked)
        val urls = parsed.results.map { it.url }
        assertEquals(3, urls.size)
        assertEquals("https://ru.wikipedia.org/wiki/%D0%AD%D0%B9%D1%84%D0%B5%D0%BB%D0%B5%D0%B2%D0%B0_%D0%B1%D0%B0%D1%88%D0%BD%D1%8F", urls[0])
        assertEquals("https://www.toureiffel.paris/ru/the-monument/key-figures", urls[1])
        assertEquals("https://travel.example.com/paris/eiffel", urls[2])
        assertTrue(parsed.results[0].snippet.contains("Высота 330"))
        assertEquals("Узнайте высоту, вес и другие факты о башне.", parsed.results[1].snippet)
        assertTrue(urls.none { it.contains("google.com") })
    }

    @Test fun googleConsentAndCaptchaAreBlocked() {
        assertTrue(SearchEngineParsers.google(fixture("google_consent.html")).blocked)
        assertTrue(SearchEngineParsers.google("<html></html>", "https://www.google.com/sorry/index?continue=x").blocked)
        assertTrue(SearchEngineParsers.google("<html>Our systems have detected unusual traffic</html>").blocked)
    }

    @Test fun bingDecodesTrackingLinksAndDropsQuiz() {
        val parsed = SearchEngineParsers.bing(fixture("bing.html"))
        assertEquals(listOf("https://ru.wikipedia.org/wiki/Eiffel_Tower", "https://m.toureiffel.paris/ru/the-monument/key-figures/"),
            parsed.results.map { it.url })
        assertTrue(parsed.results[0].snippet.contains("330"))
        assertNull(SearchEngineParsers.bingTarget("https://www.bing.com/ck/a?u=zzz"))
        assertEquals("https://example.com/a", SearchEngineParsers.bingTarget("https://example.com/a")?.toString())
    }

    @Test fun duckDuckGoResultsAreParsed() {
        val parsed = SearchEngineParsers.duckDuckGo(fixture("ddg.html"))
        assertEquals(listOf("https://ru.wikipedia.org/wiki/Eiffel_Tower", "https://history.example.org/eiffel-tower"), parsed.results.map { it.url })
        assertTrue(parsed.results[0].snippet.contains("330"))
        assertTrue(SearchEngineParsers.duckDuckGo("<div class=\"anomaly-modal\"></div>").blocked)
    }

    @Test fun normalizedKeyIgnoresSchemeMobileHostsTrackingAndSlash() {
        val key = SearchMerge.normalizedKey("https://www.toureiffel.paris/ru/the-monument/key-figures?utm_source=yandex#top")
        assertEquals(key, SearchMerge.normalizedKey("http://m.toureiffel.paris/ru/the-monument/key-figures/"))
        assertEquals(key, SearchMerge.normalizedKey("https://toureiffel.paris/RU/the-monument/key-figures"))
        assertEquals(SearchMerge.normalizedKey("https://ru.wikipedia.org/wiki/A"), SearchMerge.normalizedKey("https://ru.m.wikipedia.org/wiki/A"))
        assertTrue(SearchMerge.normalizedKey("https://example.com/?b=2&a=1") == SearchMerge.normalizedKey("https://example.com?a=1&b=2&gclid=x"))
        assertFalse(SearchMerge.normalizedKey("https://example.com/?id=1") == SearchMerge.normalizedKey("https://example.com/?id=2"))
        assertEquals("m.ru/a", SearchMerge.normalizedKey("https://m.ru/a"))
        assertNull(SearchMerge.normalizedKey("not a url"))
    }

    @Test fun mergeRanksByEngineCountThenPositionAndKeepsLabels() {
        val results = listOf(
            EngineResult(SearchEngine.YANDEX, SearchEngineParsers.yandex(fixture("yandex.html")).results),
            EngineResult(SearchEngine.GOOGLE, SearchEngineParsers.google(fixture("google.html")).results),
            EngineResult(SearchEngine.BING, SearchEngineParsers.bing(fixture("bing.html")).results),
            EngineResult(SearchEngine.DUCKDUCKGO, SearchEngineParsers.duckDuckGo(fixture("ddg.html")).results),
        )
        val merged = SearchMerge.merge(results, "эйфелева башня высота")
        val first = merged.first()
        assertTrue(first.url.contains("toureiffel.paris"))
        assertEquals(listOf("yandex", "google", "bing"), first.engines)
        // Затем адреса, найденные двумя поисковиками, выше найденных одним.
        assertEquals(2, merged[1].engines!!.size)
        assertEquals(2, merged[2].engines!!.size)
        assertTrue(merged.drop(3).all { it.engines!!.size == 1 })
        assertEquals(merged.size, merged.map { SearchMerge.normalizedKey(it.url) }.toSet().size)
        // Лучшая позиция решает при равном числе поисковиков.
        val tie = SearchMerge.merge(listOf(
            EngineResult(SearchEngine.BING, listOf(WebSource(title = "Второй результат", url = "https://b.example/x", snippet = ""),
                WebSource(title = "Первый результат", url = "https://a.example/x", snippet = ""))),
            EngineResult(SearchEngine.DUCKDUCKGO, listOf(WebSource(title = "Третий результат", url = "https://c.example/x", snippet = ""))),
        ), null)
        assertEquals(listOf("https://b.example/x", "https://c.example/x", "https://a.example/x"), tie.map { it.url })
    }

    @Test fun mergeDropsIrrelevantNoise() {
        val merged = SearchMerge.merge(listOf(EngineResult(SearchEngine.BING, listOf(
            WebSource(title = "Bing homepage quiz", url = "https://quiz.example/", snippet = "Play the quiz"),
            WebSource(title = "Высота Эйфелевой башни", url = "https://tower.example/", snippet = "330 метров"),
        ))), "эйфелева башня высота")
        assertEquals(listOf("https://tower.example/"), merged.map { it.url })
    }

    @Test fun earlyExitRules() {
        assertTrue(SearchMerge.enough(3, 10, 300))
        assertFalse(SearchMerge.enough(2, 10, 300))
        assertFalse(SearchMerge.enough(2, 5, 2_000))
        assertTrue(SearchMerge.enough(2, 5, 2_600))
        assertFalse(SearchMerge.enough(1, 5, 3_000))
        assertTrue(SearchMerge.enough(1, 3, 4_100))
        assertFalse(SearchMerge.enough(1, 2, 5_900))
    }

    @Test fun engineTitles() {
        assertEquals("Яндекс, Google, DDG", SearchEngine.titles(listOf("ddg", "google", "yandex")))
        assertEquals("Yandex, Wikipedia", SearchEngine.titles(listOf("wikipedia", "yandex", "unknown"), english = true))
    }

    @Test fun multiEngineSearchDoesNotWaitForSlowEngine() = runBlocking {
        val pages = mapOf(
            "yandex.ru" to fixture("yandex.html"),
            "www.bing.com" to fixture("bing.html"),
            "html.duckduckgo.com" to fixture("ddg.html"),
        )
        val fetcher: PageFetcher = { url, _, _ ->
            val host = url.toHttpUrl().host
            if (host == "www.google.com") delay(10_000)
            val body = pages[host]
            Fetched((body ?: "").toByteArray(), url, if (body == null) 404 else 200, "text/html; charset=utf-8")
        }
        val client = WebSearchClient(fetcher = fetcher)
        val started = System.currentTimeMillis()
        val outcome = MultiEngineSearch(client, perEngineMillis = 400, overallMillis = 1_500).search("эйфелева башня высота")
        val elapsed = System.currentTimeMillis() - started
        assertTrue("поиск ждал медленный поисковик: $elapsed мс", elapsed < 3_000)
        assertTrue(outcome.sources.isNotEmpty())
        assertTrue(SearchEngine.GOOGLE !in outcome.responded)
        assertTrue(SearchEngine.YANDEX in outcome.responded)
        val tower = outcome.sources.first { it.url.contains("toureiffel") }
        assertNotNull(tower.engines)
        assertTrue(tower.engines!!.containsAll(listOf("yandex", "bing")))
    }
}
