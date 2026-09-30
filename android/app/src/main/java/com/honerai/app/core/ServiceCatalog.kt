package com.honerai.app.core

import java.net.URLEncoder

// integ: расширение open_app — прямые ссылки/интенты «открыть приложение сразу на нужном экране»
// с запасным сайтом. Только открывают экран с подставленным запросом/адресом; вход, оплату и заказ
// пользователь завершает сам. AppCatalog проверяется первым; сюда попадают остальные сервисы.

/**
 * Каталог сервисов для open_app сверх [AppCatalog]: маркетплейсы, доставка еды, каршеринг, почта,
 * поиск, магазин приложений, электрички, мессенджеры, операторы связи. Чистый построитель ссылок —
 * проверяется модульными тестами (deeplink + web-fallback).
 */
object ServiceCatalog {
    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private val aliases: Map<String, String> = buildMap {
        fun add(id: String, vararg names: String) { put(AppCatalog.normalize(id), id); names.forEach { put(AppCatalog.normalize(it), id) } }
        add("wildberries", "вайлдберриз", "вб", "wb", "валдберис", "вилдберис")
        add("ozon", "озон")
        add("yandex_market", "яндекс маркет", "yandex market", "маркет", "ярмаркет", "беру")
        add("delimobil", "делимобиль", "делимобил", "каршеринг")
        add("kfc", "кфс", "кэфси")
        add("burger_king", "burger king", "бургер кинг", "бургеркинг")
        add("vkusno", "вкусно и точка", "вкусно", "vkusno i tochka", "макдоналдс", "mcdonalds")
        add("ozon_job", "ozon job", "озон работа", "озон job", "озон подработка", "ozon работа", "ozon hire")
        add("wb_team", "wildberries работа", "вб работа", "wb работа", "wildberries team", "вб подработка")
        add("yandex_mail", "яндекс почта", "yandex mail", "почта яндекс", "яндекспочта")
        add("google_search", "гугл", "google", "поиск google", "гугл поиск", "google search")
        add("play_market", "play market", "play маркет", "плей маркет", "гугл плей", "google play", "play store", "плеймаркет", "плей стор")
        add("yandex_rasp", "яндекс электрички", "электрички", "яндекс расписание", "электричка", "rasp")
        add("vk", "вконтакте", "вк", "vkontakte")
        add("telegram", "телеграм", "телега", "tg", "telegram")
        add("mts", "мтс", "мой мтс", "mymts")
        add("beeline", "билайн", "beeline")
        add("megafon", "мегафон", "megafon")
        add("tele2", "теле2", "tele 2", "мой теле2")
    }

    fun id(app: String): String? = aliases[AppCatalog.normalize(app)]

    fun resolve(request: AppRequest): AppLaunch? {
        val id = id(request.app) ?: return null
        val q = request.query.ifBlank { request.location }.ifBlank { request.subject }
        val address = request.location.ifBlank { request.query }
        return when (id) {
            // ---- Маркетплейсы: открыть поиск по запросу ----
            "wildberries" -> {
                val uri = if (q.isBlank()) "https://www.wildberries.ru/" else "https://www.wildberries.ru/catalog/0/search.aspx?search=${enc(q)}"
                view(id, "Wildberries", AppIntegrations.APPS, uri, listOf("com.wildberries.ru"), uri)
            }
            "ozon" -> {
                val uri = if (q.isBlank()) "https://www.ozon.ru/" else "https://www.ozon.ru/search/?text=${enc(q)}"
                view(id, "Ozon", AppIntegrations.APPS, uri, listOf("ru.ozon.app.android"), uri)
            }
            "yandex_market" -> {
                val uri = if (q.isBlank()) "https://market.yandex.ru/" else "https://market.yandex.ru/search?text=${enc(q)}"
                view(id, "Яндекс Маркет", AppIntegrations.YANDEX, uri, listOf("ru.beru.android", "com.yandex.market"), uri)
            }
            // ---- Такси и транспорт ----
            "yandex_rasp" -> // Публичного deeplink нет — запускаем приложение, иначе сайт.
                pkg(id, "Яндекс Электрички", AppIntegrations.YANDEX, listOf("ru.yandex.rasp"), "https://rasp.yandex.ru/",
                    "Публичной прямой ссылки на экран нет: приложение открывается, станции и дату пользователь вводит сам.")
            // ---- Доставка еды и каршеринг: публичных deeplink нет — запуск приложения ----
            "delimobil" -> pkg(id, "Делимобиль", AppIntegrations.APPS, listOf("com.delimobil"), "https://delimobil.ru/",
                "Публичной прямой ссылки нет: открывается приложение. Бронирование и аренду (списывают деньги) пользователь подтверждает сам.")
            "kfc" -> pkg(id, "KFC", AppIntegrations.APPS, listOf("com.yum.ru.kfc", "ru.kfc.kfc"), "https://www.kfc.ru/",
                "Публичной прямой ссылки нет: открывается приложение. Оформление и оплату завершает пользователь.")
            "burger_king" -> pkg(id, "Burger King", AppIntegrations.APPS, listOf("com.burgerking", "ru.burgerking"), "https://burgerkingrus.ru/",
                "Публичной прямой ссылки нет: открывается приложение. Оформление и оплату завершает пользователь.")
            "vkusno" -> pkg(id, "Вкусно и точка", AppIntegrations.APPS, listOf("com.apegroup.mcdonaldsrussia", "ru.vkusnoitochka.app", "com.mcdonalds.vkusnoitochka"), "https://vkusnoitochka.ru/",
                "Публичной прямой ссылки нет: открывается приложение. Оформление и оплату завершает пользователь.")
            "ozon_job" -> pkg(id, "Ozon Работа", AppIntegrations.APPS, listOf("ru.ozon.hire"), "https://job.ozon.ru/",
                "Открывается приложение подработки Ozon. Отклик на заказ/смену подтверждает пользователь.")
            "wb_team" -> pkg(id, "Wildberries Работа", AppIntegrations.APPS, listOf("ru.wildberries.team"), "https://team.wildberries.ru/",
                "Открывается приложение подработки Wildberries. Отклик на смену/заказ подтверждает пользователь.")
            // ---- Почта ----
            "yandex_mail" -> {
                val to = request.to.ifBlank { request.query.takeIf { it.contains('@') }.orEmpty() }
                val params = listOfNotNull(
                    request.subject.takeIf { it.isNotBlank() }?.let { "subject=${enc(it)}" },
                    request.body.takeIf { it.isNotBlank() }?.let { "body=${enc(it)}" },
                ).joinToString("&")
                val mailto = "mailto:${enc(to).replace("%40", "@")}" + if (params.isEmpty()) "" else "?$params"
                val web = "https://mail.yandex.ru/compose?mailto=mailto:${enc(to)}&subject=${enc(request.subject)}&body=${enc(request.body)}"
                AppLaunch(id, "Яндекс Почта", AppIntegrations.YANDEX, LaunchKind.SENDTO, mailto, listOf("ru.yandex.mail"), web,
                    note = "Письмо откроется черновиком: отправляет его сам пользователь.")
            }
            // ---- Поиск и магазин приложений ----
            "google_search" -> {
                val uri = if (q.isBlank()) "https://www.google.com/" else "https://www.google.com/search?q=${enc(q)}"
                // Без указания пакета откроется браузер по умолчанию (web-fallback).
                view(id, "Google", AppIntegrations.GOOGLE, uri, emptyList(), uri)
            }
            "play_market" -> {
                val deeplink = if (q.isBlank()) "market://search?q=apps" else "market://search?q=${enc(q)}"
                val web = if (q.isBlank()) "https://play.google.com/store" else "https://play.google.com/store/search?q=${enc(q)}"
                view(id, "Play Маркет", AppIntegrations.GOOGLE, deeplink, listOf("com.android.vending"), web)
            }
            // ---- Мессенджеры: открыть конкретный чат/профиль, если задан id ----
            "vk" -> {
                val screen = vkScreenName(q.ifBlank { request.to })
                val uri = if (screen.isEmpty()) "https://vk.com/" else "https://vk.com/$screen"
                view(id, "ВКонтакте", AppIntegrations.APPS, uri, listOf("com.vkontakte.android"), uri,
                    if (screen.isEmpty()) "Открывается лента ВКонтакте." else "Откроется страница $screen.")
            }
            "telegram" -> {
                val domain = tgUsername(q.ifBlank { request.to })
                if (domain.isEmpty()) {
                    pkg(id, "Telegram", AppIntegrations.APPS, listOf("org.telegram.messenger"), "https://t.me/",
                        "Открывается Telegram. Нужный чат откройте сами.")
                } else {
                    val deeplink = "tg://resolve?domain=$domain"
                    view(id, "Telegram", AppIntegrations.APPS, deeplink, listOf("org.telegram.messenger"), "https://t.me/$domain",
                        "Откроется чат/канал @$domain. Отправку сообщений делает пользователь.")
                }
            }
            // ---- Операторы связи: только запуск приложения (кабинет — задача агента) ----
            "mts" -> pkg(id, "МТС", AppIntegrations.APPS, listOf("ru.mts.mymts"), "https://mts.ru/",
                "Только открывает приложение. Платежи и подключение услуг — не через ссылку; это делает пользователь или агент.")
            "beeline" -> pkg(id, "Билайн", AppIntegrations.APPS, listOf("ru.beeline.services"), "https://beeline.ru/",
                "Только открывает приложение. Платежи и услуги — не через ссылку.")
            "megafon" -> pkg(id, "МегаФон", AppIntegrations.APPS, listOf("ru.megafon.mlk"), "https://megafon.ru/",
                "Только открывает приложение. Платежи и услуги — не через ссылку.")
            "tele2" -> pkg(id, "Tele2", AppIntegrations.APPS, listOf("ru.tele2.mytele2"), "https://tele2.ru/",
                "Только открывает приложение. Платежи и услуги — не через ссылку.")
            else -> null
        }
    }

    private fun view(id: String, label: String, integration: String, uri: String, packages: List<String>, web: String, note: String = "") =
        AppLaunch(id, label, integration, LaunchKind.VIEW, uri, packages, web, note = note)

    private fun pkg(id: String, label: String, integration: String, packages: List<String>, web: String, note: String) =
        AppLaunch(id, label, integration, LaunchKind.PACKAGE, null, packages, web, note = note)

    /** Короткое имя страницы ВК: из ссылки vk.com/<name>, «@name» или как есть. */
    fun vkScreenName(raw: String): String {
        var name = raw.trim().removePrefix("@")
        val marker = "vk.com/"
        if (name.contains(marker)) name = name.substringAfter(marker)
        if (name.contains("vk.ru/")) name = name.substringAfter("vk.ru/")
        return name.substringBefore('?').substringBefore('/').trim()
    }

    /** Username Telegram: из t.me/<name>, «@name» или как есть (без пробелов). */
    fun tgUsername(raw: String): String {
        var name = raw.trim().removePrefix("@")
        val marker = "t.me/"
        if (name.contains(marker)) name = name.substringAfter(marker).removePrefix("s/")
        if (name.contains("://")) name = name.substringAfterLast('/')
        name = name.substringBefore('?').substringBefore('/').trim()
        return if (name.contains(' ')) "" else name
    }

    /** Пакеты для манифеста <queries> и информации. */
    val knownPackages = listOf(
        "com.wildberries.ru", "ru.ozon.app.android", "ru.beru.android", "com.yandex.market",
        "com.delimobil", "com.yum.ru.kfc", "ru.kfc.kfc", "com.burgerking", "ru.burgerking",
        "com.apegroup.mcdonaldsrussia", "ru.vkusnoitochka.app", "com.mcdonalds.vkusnoitochka",
        "ru.ozon.hire", "ru.wildberries.team", "ru.yandex.mail",
        "com.google.android.googlequicksearchbox", "com.android.vending", "ru.yandex.rasp",
        "com.vkontakte.android", "org.telegram.messenger",
        "ru.mts.mymts", "ru.beeline.services", "ru.megafon.mlk", "ru.tele2.mytele2",
    )
}
