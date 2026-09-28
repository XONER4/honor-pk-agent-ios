package com.honerai.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Автообновление (разбор выпусков), названия моделей, текст уведомлений, вложения. */
class DeviceServicesTest {

    // MARK: Обновления

    private val releasesJson = """
    [
      {"tag_name": "v10.45.0", "draft": false, "body": "iOS", "assets": [{"name": "Honer.ipa", "size": 10, "browser_download_url": "https://x/ios.ipa"}]},
      {"tag_name": "android-v10.46.0", "draft": true, "body": "versionCode: 1046", "assets": [{"name": "honer.apk", "size": 10, "browser_download_url": "https://x/draft.apk"}]},
      {"tag_name": "android-v10.45.1", "draft": false, "prerelease": false,
       "body": "Что нового:\n- быстрее\n\nversionCode: 1052\n",
       "assets": [
         {"name": "honer-debug.apk", "size": 11, "browser_download_url": "https://x/debug.apk"},
         {"name": "honer-release.apk", "size": 31457280, "browser_download_url": "https://x/1052.apk"}
       ]},
      {"tag_name": "android-v10.45.0-1050", "draft": false, "body": "Старое", "assets": [{"name": "honer.apk", "size": 5, "browser_download_url": "https://x/1050.apk"}]},
      {"tag_name": "android-v10.44.0", "draft": false, "body": "Без апк", "assets": []}
    ]
    """.trimIndent()

    @Test
    fun picksNewestNonDraftAndroidReleaseWithApk() {
        val info = UpdateReleases.pickLatest(releasesJson)!!
        assertEquals("10.45.1", info.versionName)
        assertEquals(1052, info.versionCode)
        assertEquals("https://x/1052.apk", info.apkUrl)
        assertEquals(31457280L, info.sizeBytes)
        assertEquals("Что нового:\n- быстрее", info.notes)
        assertTrue(UpdateReleases.isNewer(info, 1044, "10.44.0"))
        assertFalse(UpdateReleases.isNewer(info, 1052, "10.45.1"))
    }

    @Test
    fun versionCodeFromBodyOrTag() {
        assertEquals(1060, UpdateReleases.parseVersionCode("**versionCode:** 1060", "android-v10.50.0"))
        assertEquals(1061, UpdateReleases.parseVersionCode("notes\nversionCode = 1061", "android-v10.50.0"))
        assertEquals(1050, UpdateReleases.parseVersionCode("", "android-v10.45.0-1050"))
        assertNull(UpdateReleases.parseVersionCode("no code", "android-v10.45.0"))
        assertEquals("10.45.0", UpdateReleases.versionName("android-v10.45.0-1050"))
    }

    @Test
    fun versionNamesCompareNumerically() {
        assertTrue(UpdateReleases.compareVersionNames("10.44.1", "10.44.0") > 0)
        assertTrue(UpdateReleases.compareVersionNames("10.5", "10.44") < 0)
        assertEquals(0, UpdateReleases.compareVersionNames("10.44", "10.44.0"))
        assertEquals(0, UpdateReleases.compareVersionNames("10.44.0-debug", "10.44.0"))
        val withoutCode = UpdateInfo("10.44.2", 0, "", "https://x", 0)
        assertTrue(UpdateReleases.isNewer(withoutCode, 1044, "10.44.0"))
        assertFalse(UpdateReleases.isNewer(withoutCode, 1044, "10.45.0"))
    }

    @Test
    fun releaseParsingSurvivesGarbage() {
        assertNull(UpdateReleases.pickLatest("not json"))
        assertNull(UpdateReleases.pickLatest("{\"message\": \"API rate limit exceeded\"}"))
        assertNull(UpdateReleases.pickLatest("[]"))
    }

    // MARK: Модели

    @Test
    fun deviceModelNames() {
        assertEquals("Samsung Galaxy S24 Ultra", DeviceModels.marketingName("samsung", "SM-S928B"))
        assertEquals("Samsung Galaxy A54 5G", DeviceModels.marketingName("samsung", "SM-A546E/DS"))
        assertEquals("Samsung Galaxy Z Fold5", DeviceModels.marketingName("samsung", "SM-F946U1"))
        assertEquals("Samsung SM-Z999X", DeviceModels.marketingName("samsung", "SM-Z999X"))
        assertEquals("Xiaomi 14", DeviceModels.marketingName("Xiaomi", "Xiaomi 14"))
        assertEquals("Redmi Note 12", DeviceModels.marketingName("Xiaomi", "Redmi Note 12"))
        assertEquals("POCO X6 Pro", DeviceModels.marketingName("Xiaomi", "POCO X6 Pro"))
        assertEquals("Google Pixel 8", DeviceModels.marketingName("Google", "Pixel 8"))
        assertEquals("Honor 90", DeviceModels.marketingName("HONOR", "Honor 90"))
        assertEquals("Huawei ALN-L29", DeviceModels.marketingName("HUAWEI", "ALN-L29"))
        assertEquals("OnePlus CPH2581", DeviceModels.marketingName("OnePlus", "CPH2581"))
        // Новые Xiaomi: в Build.MODEL код, а название — в имени устройства из настроек.
        assertEquals("Xiaomi 14", DeviceModels.marketingName("Xiaomi", "23127PN0CG", "Xiaomi 14"))
        assertEquals("Redmi Note 13", DeviceModels.marketingName("Xiaomi", "23129RAA4G", "Redmi Note 13"))
        assertEquals("Xiaomi 23127PN0CG", DeviceModels.marketingName("Xiaomi", "23127PN0CG", "Мой телефон"))
    }

    @Test
    fun utcOffsetFormatting() {
        assertEquals("UTC+3", DeviceInfo.utcOffset(3 * 3_600_000))
        assertEquals("UTC-5", DeviceInfo.utcOffset(-5 * 3_600_000))
        assertEquals("UTC+5:30", DeviceInfo.utcOffset(19_800_000))
        assertEquals("UTC+0", DeviceInfo.utcOffset(0))
    }

    // MARK: Уведомления

    @Test
    fun notificationPreviewIsCleanAndTruncated() {
        val preview = HonerNotifications.preview("## Готово!\n\n\n**Ответ** со [ссылкой](https://example.com) 🚀\n\nВторая строка")
        assertEquals("Готово!\nОтвет со ссылкой\nВторая строка", preview)
        val long = HonerNotifications.preview("Слово ".repeat(1000))
        assertEquals(1500, long.length)
        assertEquals("😀", HonerNotifications.preview("😀"))
        assertEquals("Honer AI", HonerNotifications.notificationTitle("   "))
        assertEquals(60, HonerNotifications.notificationTitle("Ч".repeat(100)).length)
    }

    @Test
    fun firstImageSkipsVideos() {
        val text = "Видео ![ролик](https://youtube.com/watch?v=1) и фото ![кот](https://img.example/cat.jpg) ![b](https://x/c.png)"
        assertEquals("https://img.example/cat.jpg", HonerNotifications.firstImageURL(text))
        assertNull(HonerNotifications.firstImageURL("без картинок"))
        assertNull(HonerNotifications.firstImageURL("![clip](https://cdn.example/clip.mp4?x=1)"))
    }

    // MARK: Вложения

    @Test
    fun attachmentClassificationAndHelpers() {
        fun info(name: String, mime: String = "") = AttachmentImporter.FileInfo(name, AttachmentImporter.extensionOf(name), mime, 100)
        assertEquals(AttachmentImporter.Kind.AUDIO, AttachmentImporter.classify(info("voice.ogg")))
        assertEquals(AttachmentImporter.Kind.AUDIO, AttachmentImporter.classify(info("rec.3gp", "audio/3gpp")))
        assertEquals(AttachmentImporter.Kind.VIDEO, AttachmentImporter.classify(info("clip.3gp", "video/3gpp")))
        assertEquals(AttachmentImporter.Kind.VIDEO, AttachmentImporter.classify(info("clip.MP4")))
        assertEquals(AttachmentImporter.Kind.IMAGE, AttachmentImporter.classify(info("photo.HEIC")))
        assertEquals(AttachmentImporter.Kind.IMAGE, AttachmentImporter.classify(info("noext", "image/jpeg")))
        assertEquals(AttachmentImporter.Kind.DOCUMENT, AttachmentImporter.classify(info("report.pdf")))
        assertEquals(AttachmentImporter.Kind.DOCUMENT, AttachmentImporter.classify(info("table.xlsx")))
        assertEquals(AttachmentImporter.Kind.UNSUPPORTED, AttachmentImporter.classify(info("setup.exe")))
        assertEquals("1:24", AttachmentImporter.clock(84.9))
        assertEquals("20:00", AttachmentImporter.clock(1200.0))
        assertEquals(1, AttachmentImporter.sampleSize(2048, 1536, 2048))
        assertEquals(2, AttachmentImporter.sampleSize(4096, 3072, 2048))
        assertEquals(4, AttachmentImporter.sampleSize(12000, 9000, 2048))
    }

    @Test
    fun contactBirthdayFormatting() {
        assertEquals("17.5.1990", ContactLookup.formatBirthday("1990-05-17"))
        assertEquals("17.5", ContactLookup.formatBirthday("--05-17"))
        assertEquals("завтра", ContactLookup.formatBirthday("завтра"))
    }
}
