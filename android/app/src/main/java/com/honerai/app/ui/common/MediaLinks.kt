package com.honerai.app.ui.common

import java.net.URI

/** Ссылки на видео: YouTube, RuTube, VK Видео, файлы (порт MediaLinks с iOS). */
object MediaLinks {
    private val youTubeIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    private fun parse(url: String): URI? = runCatching { URI(url.trim()) }.getOrNull()

    fun host(url: String): String? = parse(url)?.host?.lowercase()

    /** Хост без «www.» — для подписей и значков сайтов. */
    fun displayHost(url: String): String = (host(url) ?: url).removePrefix("www.")

    /** Идентификатор ролика YouTube из любой его ссылки. */
    fun youTubeId(url: String): String? {
        val uri = parse(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val id: String? = when {
            host.endsWith("youtu.be") -> parts.firstOrNull()
            host.contains("youtube.com") || host.contains("youtube-nocookie.com") -> {
                if (uri.path == "/watch") {
                    (uri.rawQuery ?: "").split('&').map { it.split('=', limit = 2) }
                        .firstOrNull { it.size == 2 && it[0] == "v" }?.get(1)
                } else {
                    val marker = parts.indexOfFirst { it in setOf("shorts", "embed", "live", "v") }
                    if (marker >= 0 && marker + 1 < parts.size) parts[marker + 1] else null
                }
            }
            else -> null
        }
        return id?.takeIf { youTubeIdPattern.matches(it) }
    }

    fun fileExtension(url: String): String =
        (parse(url)?.path ?: url).substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Ссылка ведёт на видео: YouTube, RuTube, VK Видео или файл видео. */
    fun isVideo(url: String): Boolean {
        if (youTubeId(url) != null) return true
        val uri = parse(url) ?: return false
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path ?: ""
        if (host.contains("rutube.ru") && path.contains("/video/")) return true
        if ((host.contains("vk.com") || host.contains("vkvideo.ru")) && path.contains("video")) return true
        return fileExtension(url) in setOf("mp4", "mov", "m4v", "m3u8", "webm")
    }

    /** Файл видео (играет встроенный плеер), а не страница. */
    fun isVideoFile(url: String): Boolean = fileExtension(url) in setOf("mp4", "mov", "m4v", "m3u8", "webm")

    /** Превью ролика: для YouTube — обложка ролика. */
    fun videoThumbnail(url: String): String? = youTubeId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    /** Значок сайта (сервис Google — работает и для сайтов без favicon.ico). */
    fun favicon(hostOrUrl: String, size: Int = 64): String {
        val host = if (hostOrUrl.contains("://")) host(hostOrUrl) ?: hostOrUrl else hostOrUrl
        return "https://www.google.com/s2/favicons?sz=$size&domain=$host"
    }
}
