package com.honerai.app.ui.markdown

import java.net.URI

/**
 * Ссылки на видео (порт MediaLinks.youTubeID / isVideo / videoThumbnail с iOS).
 * Чистый Kotlin на java.net.URI — работает и в модульных тестах.
 */
object VideoLinks {
    private val videoExtensions = setOf("mp4", "mov", "m4v", "m3u8", "webm")
    private val youTubeIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    private fun uri(url: String): URI? = try {
        URI(url.trim().replace(" ", "%20"))
    } catch (_: Exception) {
        null
    }

    /** Идентификатор ролика YouTube из любой его ссылки. */
    fun youTubeId(url: String): String? {
        val parsed = uri(url) ?: return null
        val host = parsed.host?.lowercase() ?: return null
        val segments = (parsed.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val id: String? = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            host.contains("youtube.com") || host.contains("youtube-nocookie.com") -> {
                if (parsed.rawPath == "/watch") {
                    (parsed.rawQuery ?: "").split('&').firstOrNull { it.startsWith("v=") }?.substring(2)
                } else {
                    val marker = segments.indexOfFirst { it in setOf("shorts", "embed", "live", "v") }
                    if (marker >= 0 && marker + 1 < segments.size) segments[marker + 1] else null
                }
            }
            else -> null
        }
        return id?.takeIf { youTubeIdPattern.matches(it) }
    }

    /** Расширение файла из пути ссылки. */
    fun pathExtension(url: String): String {
        val path = uri(url)?.rawPath ?: return ""
        val name = path.substringAfterLast('/')
        return if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    }

    /** Ссылка ведёт на файл видео (его можно играть встроенным плеером). */
    fun isVideoFile(url: String): Boolean = pathExtension(url) in videoExtensions

    /** Ссылка ведёт на видео: YouTube, RuTube, VK Видео или файл видео. */
    fun isVideo(url: String): Boolean {
        if (youTubeId(url) != null) return true
        val parsed = uri(url) ?: return false
        val host = parsed.host?.lowercase() ?: ""
        val path = parsed.rawPath ?: ""
        if (host.contains("rutube.ru") && path.contains("/video/")) return true
        if ((host.contains("vk.com") || host.contains("vkvideo.ru")) && path.contains("video")) return true
        return isVideoFile(url)
    }

    /** Превью ролика: для YouTube — обложка ролика. */
    fun thumbnail(url: String): String? = youTubeId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    /** Имя сайта из ссылки без «www.». */
    fun host(url: String): String = (uri(url)?.host ?: "").removePrefix("www.")
}
