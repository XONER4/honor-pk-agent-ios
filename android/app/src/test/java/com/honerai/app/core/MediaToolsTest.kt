package com.honerai.app.core

import com.honerai.app.data.ChatMessage
import com.honerai.app.data.DeepSeekConfiguration
import com.honerai.app.data.MessageRole
import com.honerai.app.device.MediaFileNames
import com.honerai.app.device.MediaKind
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

// media: язык подписей, выбор подписи, перевод, каталог приложений, аудио-источники, блоки ответа.
class MediaToolsTest {

    @Test fun captionLanguageDetection() {
        assertEquals(1.0, CaptionLanguage.cyrillicRatio("Эйфелева башня ночью"), 0.0001)
        assertEquals(0.0, CaptionLanguage.cyrillicRatio("Eiffel Tower at night"), 0.0001)
        assertEquals(1.0, CaptionLanguage.cyrillicRatio("2024 — 🎉"), 0.0001)
        assertTrue(CaptionLanguage.isRussian("Башня Eiffel вечером"))
        assertTrue(CaptionLanguage.needsTranslation("Eiffel Tower at night", "ru"))
        assertFalse(CaptionLanguage.needsTranslation("Eiffel Tower at night", "en"))
        assertFalse(CaptionLanguage.needsTranslation("Эйфелева башня ночью", "ru"))
        assertFalse(CaptionLanguage.needsTranslation("Рисунок: кот", "ru"))
        assertFalse(CaptionLanguage.needsTranslation("", "ru"))
        assertFalse(CaptionLanguage.needsTranslation("BMW", "ru"))
        assertTrue(CaptionLanguage.needsTranslation("Рисунок: a cute cat sitting in space with stars", "ru"))
        assertTrue(CaptionLanguage.looksLikeProperName("Eiffel Tower"))
        assertTrue(CaptionLanguage.looksLikeProperName("iPhone 15 Pro"))
        assertFalse(CaptionLanguage.looksLikeProperName("Eiffel Tower at night"))
    }

    @Test fun captionSelectionPrefersRussianThenTranslationThenSite() {
        val url = "https://upload.wikimedia.org/wikipedia/commons/a/b/Tower.jpg"
        assertEquals("Эйфелева башня", CaptionSelection.choose("Эйфелева башня", url, "ru", null))
        assertEquals("Eiffel Tower at night", CaptionSelection.choose("Eiffel Tower at night", url, "en", null))
        assertEquals("Эйфелева башня ночью", CaptionSelection.choose("Eiffel Tower at night", url, "ru", "Эйфелева башня ночью"))
        assertEquals("upload.wikimedia.org", CaptionSelection.choose("Eiffel Tower at night", url, "ru", null))
        assertEquals("upload.wikimedia.org", CaptionSelection.choose("Eiffel Tower at night", url, "ru", "Eiffel Tower at night translated"))
        assertEquals("Eiffel Tower", CaptionSelection.choose("Eiffel Tower", url, "ru", null))
        assertEquals("iPhone 15 Pro", CaptionSelection.choose("iPhone 15 Pro", "https://www.apple.com/x.jpg", "ru", "iPhone 15 Pro"))
        assertEquals("apple.com", CaptionSelection.siteName("https://www.apple.com/x.jpg"))
    }

    @Test fun translatorBatchesAndCaches() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val saved = CaptionTranslator.requester
        try {
            CaptionTranslator.requester = { texts -> batches.add(texts); texts.map { "RU:$it" } }
            val first = CaptionTranslator.translate(listOf("Red apple on a table", "Green field under sky", "Red apple on a table"))
            assertEquals("RU:Red apple on a table", first["Red apple on a table"])
            assertEquals(1, batches.size)
            assertEquals(2, batches[0].size)
            val second = CaptionTranslator.translate(listOf("Red apple on a table"))
            assertEquals("RU:Red apple on a table", second["Red apple on a table"])
            assertEquals("один запрос на все подписи, повтор — из кэша", 1, batches.size)
            assertEquals("RU:Green field under sky", CaptionTranslator.cached("Green field under sky"))
            CaptionTranslator.requester = { null }
            assertTrue(CaptionTranslator.translate(listOf("Blue whale in the ocean")).isEmpty())
        } finally {
            CaptionTranslator.requester = saved
        }
        assertEquals(listOf("Кот", "Пёс"), CaptionTranslator.parseResponse("""{"captions":["Кот","Пёс"]}""", 2))
        assertNull(CaptionTranslator.parseResponse("""{"captions":["Кот"]}""", 2))
        assertNull(CaptionTranslator.parseResponse("не json", 1))
    }

    @Test fun appCatalogResolvesAllowListedIntents() {
        val maps = AppCatalog.resolve(AppRequest(app = "Яндекс Карты", action = "route", query = "Красная площадь"))!!
        assertEquals("yandex_maps", maps.appId)
        assertEquals(LaunchKind.VIEW, maps.kind)
        assertEquals("https://yandex.ru/maps/?rtext=~%D0%9A%D1%80%D0%B0%D1%81%D0%BD%D0%B0%D1%8F%20%D0%BF%D0%BB%D0%BE%D1%89%D0%B0%D0%B4%D1%8C&rtt=auto", maps.uri)
        assertEquals(listOf("ru.yandex.yandexmaps"), maps.packages)
        assertEquals(AppIntegrations.YANDEX, maps.integration)

        val google = AppCatalog.resolve(AppRequest(app = "google_maps", query = "Эрмитаж"))!!
        assertTrue(google.uri!!.startsWith("https://www.google.com/maps/search/?api=1&query="))

        val mail = AppCatalog.resolve(AppRequest(app = "gmail", to = "ivan@example.com", subject = "Привет", body = "Как дела?"))!!
        assertEquals(LaunchKind.SENDTO, mail.kind)
        assertEquals("mailto:ivan@example.com?subject=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82&body=%D0%9A%D0%B0%D0%BA%20%D0%B4%D0%B5%D0%BB%D0%B0%3F", mail.uri)

        val zone = ZoneId.of("Europe/Moscow")
        val event = AppCatalog.resolve(AppRequest(app = "календарь", title = "Встреча", start = "2026-10-01T15:00"), zone)!!
        assertEquals(LaunchKind.CALENDAR_INSERT, event.kind)
        assertEquals("Встреча", event.extras[CalendarExtras.TITLE])
        val begin = event.extras[CalendarExtras.BEGIN]!!.toLong()
        assertEquals(1_790_856_000_000L, begin)
        assertEquals(begin + 3_600_000L, event.extras[CalendarExtras.END]!!.toLong())
        assertTrue(event.webFallback!!.contains("dates=20261001T120000Z/20261001T130000Z"))

        val youtube = AppCatalog.resolve(AppRequest(app = "ютуб", query = "котики"))!!
        assertTrue(youtube.uri!!.startsWith("https://www.youtube.com/results?search_query="))
        assertEquals("yandexnavi://map_search?text=%D0%9A%D0%BB%D0%B8%D0%BD",
            AppCatalog.resolve(AppRequest(app = "навигатор", query = "Клин"))!!.uri)
        assertEquals(LaunchKind.PACKAGE, AppCatalog.resolve(AppRequest(app = "Яндекс Go"))!!.kind)
    }

    @Test fun paymentAppsOnlyLaunchAndNeverCarryPaymentData() {
        for (name in listOf("Google Pay", "Кошелёк", "Mir Pay", "СБП")) {
            val launch = AppCatalog.resolve(AppRequest(app = name, action = "pay", query = "перевести 500 рублей Ивану"))
            assertNotNull(name, launch)
            launch!!
            assertTrue(name, launch.payment)
            assertEquals(name, LaunchKind.PACKAGE, launch.kind)
            assertNull(name, launch.uri)
            assertTrue(name, launch.extras.isEmpty())
            assertEquals(AppIntegrations.PAYMENTS, launch.integration)
        }
        assertNull(AppCatalog.resolve(AppRequest(app = "Неизвестное приложение")))
        assertTrue(AppCatalog.launcherApp("ru.nspk.sbpay", "СБПэй").payment)
        assertTrue(AppCatalog.knownPackages.containsAll(AppCatalog.paymentPackages))
    }

    @Test fun appBlockRoundTripsAndLauncherAppsAreFoundByName() {
        val request = AppRequest(app = "yandex_maps", action = "route", query = "Клин")
        val block = AppCatalog.block(request)
        assertTrue(block.startsWith("```app\n{"))
        assertEquals(request, AppRequest.parse(block.removePrefix("```app\n").removeSuffix("\n```")))
        assertNull(AppRequest.parse("{\"app\":\"\"}"))
        val apps = listOf("Сбербанк Онлайн" to "ru.sberbankmobile", "Телеграм" to "org.telegram.messenger", "Telegram X" to "org.thunderdog.challegram")
        assertEquals("org.telegram.messenger", AppLauncher.findLauncherApp(apps, "телеграм")?.second)
        assertEquals("ru.sberbankmobile", AppLauncher.findLauncherApp(apps, "сбербанк")?.second)
        assertEquals("org.thunderdog.challegram", AppLauncher.findLauncherApp(apps, "telegram")?.second)
        assertNull(AppLauncher.findLauncherApp(apps, "x"))
        val launch = AppLauncher.launchFor(AppRequest(app = "Телеграм", packageName = "org.telegram.messenger", label = "Телеграм"))!!
        assertEquals(LaunchKind.PACKAGE, launch.kind)
        assertEquals(listOf("org.telegram.messenger"), launch.packages)
    }

    @Test fun archiveAndCommonsAudioAreParsed() {
        val search = """{"response":{"docs":[{"identifier":"calm-piano_2019","title":"Calm Piano","creator":["Jane Doe"]},{"identifier":"no-title"}]}}"""
        val docs = ArchiveAudio.searchResults(search)
        assertEquals(2, docs.size)
        assertEquals("Calm Piano — Jane Doe", docs[0].caption)
        assertEquals("no-title", docs[1].caption)
        val metadata = """{"files":[{"name":"cover.jpg","format":"JPEG"},{"name":"Track 01.flac","format":"Flac"},
            {"name":"Track 01.mp3","format":"VBR MP3","size":"4000000"},{"name":"Track 01_64kb.mp3","format":"64Kbps MP3","size":"1000000"}]}"""
        assertEquals("Track 01.mp3", ArchiveAudio.pickFile(metadata))
        assertNull(ArchiveAudio.pickFile("""{"files":[{"name":"a.jpg"}]}"""))
        assertEquals("https://archive.org/download/calm-piano_2019/Track%2001.mp3", ArchiveAudio.downloadUrl("calm-piano_2019", "Track 01.mp3"))
        val commons = """{"query":{"pages":{"12":{"index":2,"title":"File:Bird song.ogg","imageinfo":[{"url":"https://upload.wikimedia.org/a/Bird_song.ogg","mime":"application/ogg","descriptionurl":"https://commons.wikimedia.org/wiki/File:Bird_song.ogg"}]},
            "11":{"index":1,"title":"File:Piano_etude.mp3","imageinfo":[{"url":"https://upload.wikimedia.org/a/Piano_etude.mp3","mime":"audio/mpeg"}]},
            "13":{"index":3,"title":"File:Photo.jpg","imageinfo":[{"url":"https://upload.wikimedia.org/a/Photo.jpg","mime":"image/jpeg"}]}}}}"""
        val items = CommonsAudio.parse(commons)
        assertEquals(listOf("Piano etude", "Bird song"), items.map { it.title })
        assertTrue(MediaKinds.isAudio(items[1].url))
    }

    @Test fun mediaKindsAndToolKinds() {
        assertTrue(MediaKinds.isAudio("https://archive.org/download/x/Track%2001.mp3"))
        assertTrue(MediaKinds.isAudio("https://example.com/a.OGG?x=1"))
        assertFalse(MediaKinds.isAudio("https://example.com/a.jpg"))
        assertEquals("audio", MediaSender.kind("music"))
        assertEquals("audio", MediaSender.kind("Музыка"))
        assertEquals("video", MediaSender.kind("видео"))
        assertEquals("image", MediaSender.kind("фото"))
        assertEquals("audio", MediaSender.kind("", "https://example.com/song.mp3"))
        assertEquals("video", MediaSender.kind("", "https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("image", MediaSender.kind("", "https://example.com/pic"))
    }

    @Test fun missingMediaBlocksAreAppendedOnlyWhenModelForgotAll() {
        MediaAnswerBlocks.register("c1", listOf(MediaAnswerBlocks.Item("https://a/1.jpg", "![Кот](https://a/1.jpg)"),
            MediaAnswerBlocks.Item("https://a/2.jpg", "![Пёс](https://a/2.jpg)")))
        val appended = MediaAnswerBlocks.completed("Вот фото.", listOf("c1"))
        assertEquals("Вот фото.\n\n![Кот](https://a/1.jpg)\n\n![Пёс](https://a/2.jpg)", appended)
        MediaAnswerBlocks.register("c2", listOf(MediaAnswerBlocks.Item("https://a/1.jpg", "![Кот](https://a/1.jpg)"),
            MediaAnswerBlocks.Item("https://a/2.jpg", "![Пёс](https://a/2.jpg)")))
        assertNull(MediaAnswerBlocks.completed("Вот: ![Котик](https://a/1.jpg)", listOf("c2")))
        assertNull(MediaAnswerBlocks.completed("Вот фото.", listOf("c2")))
        assertNull(MediaAnswerBlocks.completed("текст", listOf("unknown")))
    }

    @Test fun sendMediaFindsFreeAudioWithRussianCaptions() = runBlocking {
        val fetcher: PageFetcher = { url, _, _ ->
            val parsed = url.toHttpUrl()
            val body = when {
                parsed.encodedPath == "/advancedsearch.php" -> """{"response":{"docs":[{"identifier":"calm-piano","title":"Calm piano for sleeping","creator":"Jane"}]}}"""
                parsed.encodedPath.startsWith("/metadata/") -> """{"files":[{"name":"calm.mp3","format":"VBR MP3"}]}"""
                parsed.host == "commons.wikimedia.org" -> """{"query":{"pages":{"1":{"index":1,"title":"File:Отдых у моря.ogg","imageinfo":[{"url":"https://upload.wikimedia.org/x/Sea.ogg","mime":"audio/ogg"}]}}}}"""
                else -> ""
            }
            Fetched(body.toByteArray(), url, if (body.isEmpty()) 404 else 200, "application/json")
        }
        val sender = MediaSender(WebSearchClient(fetcher = fetcher), "ru") { texts -> texts.associateWith { "Спокойное пианино для сна — Jane" } }
        val result = sender.execute(ToolCallRequest("m1", "send_media", """{"kind":"music","query":"calm piano","count":2}"""))
        assertTrue(result.content, result.content.contains("![Спокойное пианино для сна — Jane](https://archive.org/download/calm-piano/calm.mp3)"))
        assertTrue(result.content.contains("![Отдых у моря](https://upload.wikimedia.org/x/Sea.ogg)"))
        // Модель забыла вставить карточки — они допишутся в ответ.
        assertNotNull(MediaAnswerBlocks.completed("Держи музыку.", listOf("m1")))
        val direct = sender.execute(ToolCallRequest("m2", "send_media", """{"kind":"audio","url":"https://example.com/song.mp3","caption":"Песня"}"""))
        assertTrue(direct.content.contains("![Песня](https://example.com/song.mp3)"))
        val bad = sender.execute(ToolCallRequest("m3", "send_media", """{"kind":"image","url":"http://10.0.0.1/a.jpg"}"""))
        assertTrue(bad.content.contains("не распознана"))
    }

    @Test fun downloadFileNamesAndTypes() {
        assertEquals("Эйфелева башня.jpg", MediaFileNames.fileName("https://a.example/x/photo.png?w=10", "Эйфелева башня", "image/jpeg", MediaKind.IMAGE))
        assertEquals("song.mp3", MediaFileNames.fileName("https://a.example/song.mp3", "", null, MediaKind.AUDIO))
        assertEquals("honer.jpg", MediaFileNames.fileName("https://a.example/", "", null, MediaKind.IMAGE))
        assertEquals("a b.webp", MediaFileNames.fileName("https://a.example/p", "a/b", "image/webp", MediaKind.IMAGE))
        assertEquals("audio/ogg", MediaFileNames.mimeFor("oga", MediaKind.AUDIO))
        assertEquals("video/mp4", MediaFileNames.mimeFor("xyz", MediaKind.VIDEO))
        assertTrue(MediaFileNames.acceptable("image/png", MediaKind.IMAGE))
        assertTrue(MediaFileNames.acceptable("application/octet-stream", MediaKind.AUDIO))
        assertFalse(MediaFileNames.acceptable("text/html; charset=utf-8", MediaKind.IMAGE))
        assertTrue(MediaFileNames.acceptable("application/ogg", MediaKind.AUDIO))
    }

    @Test fun toolsAreRegisteredAndPromptForbidsPayments() {
        assertEquals(HonerTool.OPEN_APP, HonerTool.from("open_app"))
        assertEquals(HonerTool.SEND_MEDIA, HonerTool.from("send_media"))
        assertTrue(HonerTool.SEND_MEDIA.isWeb)
        assertFalse(HonerTool.OPEN_APP.isWeb)
        val offline = HonerTool.schemas(false).map { it["function"]["name"].str }
        assertTrue("open_app" in offline)
        assertFalse("send_media" in offline)
        val sendMedia = HonerTool.SEND_MEDIA.schema["function"]["parameters"]["properties"]["caption"]["description"].str!!
        assertTrue(sendMedia.contains("подпись на русском"))
        assertTrue(HonerTool.WEB_SEARCH.schema["function"]["description"].str!!.contains("Яндекс, Google"))
        val client = DeepSeekClient(DeepSeekConfiguration(apiKey = "k"))
        val body = client.buildBody(listOf(ChatMessage(role = MessageRole.USER, content = "Привет")), false, "", "")
        val system = body["messages"].arr!!.first()["content"].str!!
        assertTrue(system.contains("никогда не начинай, не готовь и не подтверждай оплату или перевод денег"))
        assertTrue(system.contains("send_media"))
        assertEquals("Готовлю кнопку приложения", ChatLogic.step(ToolCallRequest("a", "open_app", """{"app":"gmail"}""")).title)
        assertEquals("Ищу музыку и звуки", ChatLogic.step(ToolCallRequest("a", "send_media", """{"kind":"audio","query":"x"}""")).title)
    }
}
