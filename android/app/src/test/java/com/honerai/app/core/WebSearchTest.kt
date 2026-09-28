package com.honerai.app.core

import com.honerai.app.data.WebSource
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Поиск, разбор страниц, погода и ссылки на медиа — без настоящей сети. */
class WebSearchTest {
    @Test fun searchIntentFetchesOnlyWhenReallyNeeded() {
        assertTrue(SearchIntent.needsSearch("Найди последние новости", false))
        assertTrue(SearchIntent.needsSearch("Какая погода в Клину сегодня?", false))
        assertTrue(SearchIntent.needsSearch("Прочитай https://example.com/ice и объясни вывод.", false))
        assertTrue(SearchIntent.needsSearch("Курс доллара сегодня", false))
        assertFalse(SearchIntent.needsSearch("Что такое лёд?", false))
        assertFalse(SearchIntent.needsSearch("Сколько будет 17 умножить на 23?", false))
        assertTrue(SearchIntent.needsSearch("Сколько будет 17 умножить на 23?", true))
        assertFalse(SearchIntent.needsSearch("Объясни, почему сегодня важно спать", false))
        assertFalse(SearchIntent.needsSearch("   ", true))
    }

    @Test fun weatherIntentAndRussianWeatherDataUseCorrectCityDatesAndUnits() {
        assertEquals("Клин", WeatherIntent.location("Какая сегодня погода в Клину?"))
        assertEquals("Клин", WeatherIntent.location("Weather in Klin tomorrow"))
        assertEquals("Новосибирске", WeatherIntent.location("Какая погода в Новосибирске сегодня"))
        assertNull(WeatherIntent.location("Почему лёд тает при нагревании?"))
        val json = """{"timezone":"Europe/Moscow","current":{"time":"2026-09-23T10:00","temperature_2m":14.2,"apparent_temperature":13.1,"relative_humidity_2m":80,"weather_code":3,"wind_speed_10m":2.5},"daily":{"time":["2026-09-23","2026-09-24"],"temperature_2m_max":[16.2,17.4],"temperature_2m_min":[9.3,10.1],"precipitation_probability_max":[20,40],"weather_code":[3,61]}}"""
        val russian = WeatherForecast.parse(json)!!.russianDescription("Клин, Московская область")
        assertTrue(russian.contains("2026-09-23T10:00"))
        assertTrue(russian.contains("14.2 °C"))
        assertTrue(russian.contains("2.5 м/с"))
        assertTrue(russian, russian.contains("2026-09-24: 10.1…17.4 °C; дождь"))
        assertTrue(russian.contains("Europe/Moscow"))
    }

    @Test fun pageExtractionAndSearchRelevanceRejectBingQuizNoise() {
        val html = "<html><head><title>Экран телефона</title><script>SECRET_SCRIPT</script></head><body><nav>MENU</nav><article><h1>iPhone 13</h1><p>Display: 6.1 inches &amp; OLED.</p><p>Русский текст &#1087;ро экран.</p></article></body></html>"
        val text = WebPageText.extract(html)
        assertTrue(text.contains("6.1 inches & OLED"))
        assertTrue(text.contains("про экран"))
        assertFalse(text.contains("SECRET_SCRIPT"))
        assertFalse(text.contains("MENU"))
        assertTrue(text.lines().contains("iPhone 13"))
        assertEquals("Экран телефона", WebPageText.title(html))
        assertEquals("Экран телефона", WebPageText.parse(html).title)
        assertFalse(WebPageText.isPublicWebURL("http://127.0.0.1/private"))
        assertFalse(WebPageText.isPublicWebURL("http://192.168.1.1/"))
        assertFalse(WebPageText.isPublicWebURL("http://172.20.0.1/"))
        assertTrue(WebPageText.isPublicWebURL("http://172.40.0.1/"))
        val noise = WebSource(title = "Bing homepage quiz answers", url = "https://reddit.com/bing", snippet = "Bing quiz latest answers and rewards")
        assertEquals(0, SearchRelevance.score(noise, "погода Клин"))
        val good = WebSource(title = "Клин: прогноз погоды", url = "https://example.com/klin", snippet = "Погода в Клину сегодня")
        assertTrue(SearchRelevance.score(good, "погода Клин") > 0)
        assertEquals("Apple's iPhone 13 technical specifications",
            SearchRelevance.compactQuery("Find Apple's iPhone 13 technical specifications. Give the display size in one sentence."))
        assertEquals("старых телефонов", SearchRelevance.compactQuery("Пох создай таблицу старых телефонов"))
        val source = WebSource(title = "Прочитано", url = good.url, snippet = "Выдержка", content = "Полный извлечённый текст", fetchedAt = Instant.EPOCH)
        val context = WebSearchClient.context(listOf(good, source))
        assertTrue(context.contains("[1]"))
        assertTrue(context.contains("[2]"))
        assertTrue(context.contains("страница не прочитана"))
        assertTrue(context.contains("Полный извлечённый текст"))
        assertTrue(context.contains("1970-01-01T00:00:00Z"))
    }

    @Test fun duckDuckGoResultsDecodeLinksAndSnippets() {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Farticle&amp;rut=abc">Полезная <b>страница</b></a>
            <a class="result__snippet" href="#">Текст &amp; данные.</a>
        """.trimIndent()
        val results = WebPageText.searchResults(html)
        assertEquals(1, results.size)
        assertEquals("https://example.com/article", results.first().url)
        assertEquals("Полезная страница", results.first().title)
        assertEquals("Текст & данные.", results.first().snippet)
    }

    @Test fun rssParserFiltersUnsafeUrlsAndDeduplicates() {
        val xml = """<rss><channel><item><title>A &amp; B</title><link>https://example.com/a</link><description><![CDATA[<b>Text</b>]]></description></item>
            <item><title>Duplicate</title><link>https://example.com/a</link></item>
            <item><title>Bad</title><link>javascript:alert(1)</link></item></channel></rss>"""
        val results = RSSResultsParser.parse(xml)
        assertEquals(1, results.size)
        assertEquals("A & B", results[0].title)
        assertEquals("Text", results[0].snippet)
    }

    @Test fun searchResultLinksAreParsedFromEngineHtml() {
        val html = """
            <a href="https://search.brave.com/settings">Настройки поиска Brave</a>
            <a href="https://ru.investing.com/currencies/usd-rub" class="l1"><div class="title">Доллар США — рубль: курс</div></a>
            <a href="https://cbr.ru/currency_base/daily/">Официальные курсы валют ЦБ РФ</a>
            <a href="https://cbr.ru/currency_base/daily/">Дубль ссылки на курсы ЦБ</a>
            <a href="https://x.io">ok</a>
        """.trimIndent()
        val links = WebPageText.resultLinks(html, listOf("brave.com"))
        assertEquals(listOf("ru.investing.com", "cbr.ru"), links.map { it.host })
        assertEquals("Доллар США — рубль: курс", links.first().title)
    }

    @Test fun imageUrlsSkipIconsAndResolveRelativePaths() {
        val html = """<meta property="og:image" content="https://cdn.example.com/cover.jpg"><img src="/img/photo.png"><img src="/logo.png"><img src="data:image/png;base64,AAA"><script src="/app.js"></script>"""
        val images = WebPageText.imageURLs(html, "https://example.com/page")
        assertEquals(listOf("https://cdn.example.com/cover.jpg", "https://example.com/img/photo.png"), images)
        assertEquals("<a & b>", WebPageText.decodeEntities("&lt;a &amp; b&gt;"))
        assertEquals("ф", WebPageText.decodeEntities("&#x444;"))
    }

    @Test fun mediaLinksRecognizeVideosDrawingsAndScreenshots() {
        val id = "dQw4w9WgXcQ"
        for (raw in listOf("https://www.youtube.com/watch?v=$id", "https://youtu.be/$id", "https://www.youtube.com/shorts/$id",
            "https://m.youtube.com/watch?v=$id&t=42", "https://www.youtube.com/embed/$id")) {
            assertEquals(raw, id, MediaLinks.youTubeID(raw))
            assertTrue(raw, MediaLinks.isVideo(raw))
        }
        assertTrue(MediaLinks.isVideo("https://example.com/clip.mp4"))
        assertFalse(MediaLinks.isVideo("https://example.com/photo.jpg"))
        assertNull(MediaLinks.youTubeID("https://www.youtube.com/@channel"))
        assertEquals("https://i.ytimg.com/vi/$id/hqdefault.jpg", MediaLinks.videoThumbnail("https://youtu.be/$id"))
        val drawing = MediaLinks.drawing("cat astronaut (watercolor), stars", "landscape", 5)!!
        assertEquals("image.pollinations.ai", drawing.toHttpUrl().host)
        assertTrue(drawing.contains("width=1152&height=768"))
        assertFalse(drawing.contains("("))
        assertFalse(drawing.contains(" "))
        assertTrue(MediaLinks.drawing("кот", "square", 1)!!.contains("%D0%BA%D0%BE%D1%82"))
        val shot = MediaLinks.screenshot("https://example.com/page")!!
        assertTrue(shot.startsWith("https://image.thum.io/"))
        assertTrue(shot.endsWith("https://example.com/page"))
        assertNull(MediaLinks.screenshot("http://localhost/x"))
    }

    @Test fun telegramLinksOpenThroughTheWebPreview() {
        assertEquals("https://t.me/s/durov", WebSearchClient.readableURL("https://t.me/durov".toHttpUrl()).toString())
        assertEquals("https://t.me/s/durov/300", WebSearchClient.readableURL("https://t.me/durov/300".toHttpUrl()).toString())
        assertEquals("https://t.me/s/durov", WebSearchClient.readableURL("https://t.me/s/durov".toHttpUrl()).toString())
        assertEquals("https://example.com/a", WebSearchClient.readableURL("https://example.com/a".toHttpUrl()).toString())
        assertEquals("https://t.me/durov", WebToolExecutor.url("t.me/durov")?.toString())
        assertNull(WebToolExecutor.url(""))
        assertNull(WebToolExecutor.url("http://10.0.0.1/admin"))
    }

    @Test fun unavailableSearchUsesOnlyActuallyReadDiscoveredPages() = runBlocking {
        val pages = mapOf(
            "www.bing.com" to (200 to "<rss><channel><item><title>Bing homepage quiz answers</title><link>https://reddit.com/bing</link><description>Bing rewards quiz.</description></item></channel></rss>"),
            "html.duckduckgo.com" to (200 to "<html><p>Verify you are human</p></html>"),
            "research.example/specifications" to (200 to "<html><title>Telescope specifications</title><article><h1>Technical specifications</h1><p>Aperture is 120 mm. Focal length is 900 mm. This technical document describes the optical equipment and the supplied mounting assembly in detail.</p></article></html>"),
        )
        val fetcher: PageFetcher = { url, _, _ ->
            val parsed = url.toHttpUrl()
            val key = if (parsed.host == "research.example") parsed.host + parsed.encodedPath else parsed.host
            val (status, body) = pages[key] ?: (404 to "Page not found")
            Fetched(body.toByteArray(), url, status, "text/html; charset=utf-8")
        }
        val discovery = object : SearchURLDiscovering {
            override suspend fun candidates(query: String) = listOf("https://research.example/specifications", "https://research.example/missing")
        }
        val client = WebSearchClient(urlDiscovery = discovery, fetcher = fetcher)
        val sources = client.search("Find telescope specifications")
        assertEquals(listOf("https://research.example/specifications"), sources.map { it.url })
        assertEquals("Telescope specifications", sources.first().title)
        assertTrue(sources.first().content!!.contains("Aperture is 120 mm"))
        assertNotNull(sources.first().fetchedAt)
        assertTrue(sources.first().snippet.contains("не поисковая выдержка"))
        assertFalse(WebSearchClient.context(sources).contains("missing"))
    }

    @Test fun webSearchToolDescribesReadPagesWithSources() = runBlocking {
        val fetcher: PageFetcher = { url, _, _ ->
            val parsed = url.toHttpUrl()
            val body = when (parsed.host) {
                "www.bing.com" -> "<rss><channel><item><title>Эйфелева башня — высота</title><link>https://tower.example/height</link><description>Высота башни 330 метров</description></item></channel></rss>"
                "tower.example" -> "<html><title>Эйфелева башня</title><body><p>" + "Высота Эйфелевой башни составляет 330 метров вместе с антенной. ".repeat(4) + "</p><img src=\"/tower.jpg\"></body></html>"
                else -> ""
            }
            Fetched(body.toByteArray(), url, if (body.isEmpty()) 404 else 200, "text/html")
        }
        val executor = WebToolExecutor(WebSearchClient(fetcher = fetcher))
        val steps = mutableListOf<String>()
        val result = executor.execute(ToolCallRequest("w", "web_search", """{"query":"Эйфелева башня высота"}""")) { detail, _ -> steps.add(detail) }
        val effect = result.effect as ToolEffect.AddSources
        assertEquals("https://tower.example/height", effect.sources.first().url)
        assertTrue(result.content.contains("URL: https://tower.example/height (страница прочитана)"))
        assertTrue(result.content.contains("https://tower.example/tower.jpg"))
        assertTrue(steps.any { it.startsWith("Прочитано страниц") })
        val weatherless = executor.execute(ToolCallRequest("x", "get_weather", "{}"))
        assertTrue(weatherless.content.contains("Не назван город"))
        val shot = executor.execute(ToolCallRequest("s", "screenshot_page", """{"url":"example.com"}"""))
        assertTrue(shot.content.contains("![Скриншот example.com](https://image.thum.io/"))
    }
}
