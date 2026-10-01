package com.honerai.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// integ: построитель deep-link/веб-фолбэка для расширенного open_app (маркетплейсы, транспорт, мессенджеры и т.д.).
class ServiceCatalogTest {

    private fun resolve(app: String, query: String = "", location: String = "", to: String = "") =
        ServiceCatalog.resolve(AppRequest(app = app, query = query, location = location, to = to))

    @Test fun marketplacesOpenSearch() {
        val wb = resolve("Wildberries", "наушники")!!
        assertEquals(LaunchKind.VIEW, wb.kind)
        assertEquals("https://www.wildberries.ru/catalog/0/search.aspx?search=%D0%BD%D0%B0%D1%83%D1%88%D0%BD%D0%B8%D0%BA%D0%B8", wb.uri)
        assertEquals(wb.uri, wb.webFallback)
        assertEquals(listOf("com.wildberries.ru"), wb.packages)

        val ozon = resolve("озон", "кофе")!!
        assertEquals("https://www.ozon.ru/search/?text=%D0%BA%D0%BE%D1%84%D0%B5", ozon.uri)
        assertEquals(listOf("ru.ozon.app.android"), ozon.packages)

        val market = resolve("яндекс маркет", "телефон")!!
        assertEquals("https://market.yandex.ru/search?text=%D1%82%D0%B5%D0%BB%D0%B5%D1%84%D0%BE%D0%BD", market.uri)
        assertEquals(AppIntegrations.YANDEX, market.integration)
    }

    @Test fun taxiRoutesToAddressButLaunchesWithoutOne() {
        // integ: у AppCatalog такси теперь принимает адрес назначения.
        val taxi = AppCatalog.resolve(AppRequest(app = "Яндекс Такси", query = "Тверская 1"))!!
        assertEquals(LaunchKind.VIEW, taxi.kind)
        assertEquals("yandextaxi://route?end-text=%D0%A2%D0%B2%D0%B5%D1%80%D1%81%D0%BA%D0%B0%D1%8F%201", taxi.uri)
        assertTrue(taxi.webFallback!!.startsWith("https://3.redirect.appmetrica.yandex.com/route?end-text="))
        assertEquals(listOf("ru.yandex.taxi"), taxi.packages)
        // Без адреса — просто запуск приложения.
        assertEquals(LaunchKind.PACKAGE, AppCatalog.resolve(AppRequest(app = "Яндекс Go"))!!.kind)
    }

    @Test fun messengersOpenProfileOrChatByName() {
        val tg = resolve("Телеграм", "durov")!!
        assertEquals(LaunchKind.VIEW, tg.kind)
        assertEquals("tg://resolve?domain=durov", tg.uri)
        assertEquals("https://t.me/durov", tg.webFallback)
        assertEquals(listOf("org.telegram.messenger"), tg.packages)
        // Имя можно передать ссылкой или с @.
        assertEquals("tg://resolve?domain=durov", resolve("telegram", "https://t.me/durov")!!.uri)
        assertEquals("tg://resolve?domain=durov", resolve("telegram", "@durov")!!.uri)
        // Без имени — запуск приложения.
        assertEquals(LaunchKind.PACKAGE, resolve("телеграм")!!.kind)

        val vk = resolve("вконтакте", "durov")!!
        assertEquals("https://vk.com/durov", vk.uri)
        assertEquals(listOf("com.vkontakte.android"), vk.packages)
        assertEquals("https://vk.com/durov", resolve("вк", "vk.com/durov")!!.uri)
    }

    @Test fun launchOnlyServicesFallBackToSite() {
        for ((app, pkg, site) in listOf(
            Triple("Delimobil", "com.delimobil", "https://delimobil.ru/"),
            Triple("KFC", "com.yum.ru.kfc", "https://www.kfc.ru/"),
            Triple("Burger King", "com.burgerking", "https://burgerkingrus.ru/"),
            Triple("Вкусно и точка", "com.apegroup.mcdonaldsrussia", "https://vkusnoitochka.ru/"),
            Triple("Яндекс Электрички", "ru.yandex.rasp", "https://rasp.yandex.ru/"),
            Triple("МТС", "ru.mts.mymts", "https://mts.ru/"),
            Triple("Билайн", "ru.beeline.services", "https://beeline.ru/"),
            Triple("МегаФон", "ru.megafon.mlk", "https://megafon.ru/"),
            Triple("Теле2", "ru.tele2.mytele2", "https://tele2.ru/"),
        )) {
            val launch = resolve(app)!!
            assertEquals(app, LaunchKind.PACKAGE, launch.kind)
            assertNull(app, launch.uri)
            assertEquals(app, pkg, launch.packages.first())
            assertEquals(app, site, launch.webFallback)
            assertFalse(app, launch.payment) // это не платёжные приложения — просто открываем
        }
    }

    @Test fun mailAndSearchAndStore() {
        val mail = resolve("Яндекс Почта", to = "ivan@example.com")!!
        assertEquals(LaunchKind.SENDTO, mail.kind)
        assertTrue(mail.uri!!.startsWith("mailto:ivan@example.com"))
        assertEquals(listOf("ru.yandex.mail"), mail.packages)

        val google = resolve("Google", "погода завтра")!!
        assertTrue(google.uri!!.startsWith("https://www.google.com/search?q="))
        assertTrue(google.packages.isEmpty()) // откроется браузером по умолчанию

        val play = resolve("Play Маркет", "telegram")!!
        assertEquals("market://search?q=telegram", play.uri)
        assertEquals("https://play.google.com/store/search?q=telegram", play.webFallback)
        assertEquals(listOf("com.android.vending"), play.packages)
    }

    @Test fun unknownServiceReturnsNull() {
        assertNull(resolve("какое-то приложение"))
        assertNull(resolve(""))
    }

    @Test fun launchForConsultsServiceCatalogAfterAppCatalog() {
        // open_app-блок с сервисом из расширенного каталога должен корректно резолвиться при нажатии кнопки.
        val launch = AppLauncher.launchFor(AppRequest(app = "ozon", query = "чай"))!!
        assertEquals("https://www.ozon.ru/search/?text=%D1%87%D0%B0%D0%B9", launch.uri)
    }
}
