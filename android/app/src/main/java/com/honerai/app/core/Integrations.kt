package com.honerai.app.core

import android.content.Context

/**
 * Интеграции (как IntegrationsPage.swift на iOS): какие сервисы нейросеть может
 * использовать сама. Выключенная интеграция не передаётся модели вовсе.
 * Инструменты — по их именам в API (youtube_search, github, marketplace_search…).
 */
object Integrations {
    data class Service(
        val id: String,
        val name: String,
        val descriptionRU: String,
        val descriptionEN: String,
        val tools: List<String>,
    )

    private const val PREFS = "honer.integrations"
    private const val KEY = "disabled"

    val services: List<Service> = listOf(
        Service("youtube", "YouTube",
            "Поиск роликов, описание, длительность и субтитры — нейросеть понимает, о чём видео.",
            "Video search, descriptions, length and subtitles — the AI understands what a video is about.",
            listOf("youtube_search", "youtube_video")),
        Service("github", "GitHub",
            "Поиск репозиториев, README, файлы кода, задачи и релизы публичных проектов.",
            "Repository search, READMEs, code files, issues and releases of public projects.",
            listOf("github")),
        Service("marketplaces", "Wildberries · Ozon · Avito · Маркет",
            "Поиск товаров и объявлений: названия, цены, рейтинг и ссылки.",
            "Product and listing search: names, prices, ratings and links.",
            listOf("marketplace_search")),
        Service("vk", "ВКонтакте", "Публичные сообщества и страницы: описание и открытые записи.",
            "Public communities and pages: description and open posts.", listOf("vk_page")),
        Service("telegram", "Telegram", "Последние публикации публичных каналов.",
            "Latest posts of public channels.", listOf("telegram_channel")),
        Service("research", "Массовое чтение сайтов",
            "Параллельное чтение сотен и тысяч страниц для исследований и сравнений.",
            "Parallel reading of hundreds and thousands of pages for research.", listOf("read_many_pages")),
    )

    @Volatile private var disabledCache: Set<String>? = null

    fun disabled(context: Context): Set<String> = disabledCache ?: context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()
        .also { disabledCache = it }

    fun isEnabled(context: Context, id: String): Boolean = id !in disabled(context)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val set = disabled(context).toMutableSet()
        if (enabled) set.remove(id) else set.add(id)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
        disabledCache = set
    }

    /** Инструмент выключен вместе со своей интеграцией. */
    fun allowsTool(context: Context, toolName: String): Boolean {
        val off = disabled(context)
        if (off.isEmpty()) return true
        return services.none { it.id in off && toolName in it.tools }
    }
}
