package com.honerai.app.core.github

import com.honerai.app.core.arr
import com.honerai.app.core.bool
import com.honerai.app.core.get
import com.honerai.app.core.int
import com.honerai.app.core.obj
import com.honerai.app.core.parseJson
import com.honerai.app.core.str
import kotlinx.serialization.json.JsonElement

// github: разбор ответов официального REST API GitHub и base64 файлов.
// Всё здесь чистое (без сети и Android) — проверяется модульными тестами.

/** Репозиторий пользователя из GET /user/repos или /users/{login}/repos. */
data class GitHubRepo(
    val fullName: String,
    val name: String,
    val private: Boolean,
    val language: String,
    val stars: Int,
    val description: String,
    val htmlUrl: String,
) {
    /** Строка для карточки результата. */
    val line: String
        get() {
            val kind = if (private) "приватный" else "публичный"
            val lang = if (language.isEmpty()) "" else " · $language"
            val about = if (description.isEmpty()) "" else " — $description"
            return "• $fullName ($kind, ★$stars$lang)$about"
        }
}

/** Содержимое файла из GET /repos/{repo}/contents/{path}. */
data class GitHubFile(
    val path: String,
    val sha: String,
    val size: Int,
    val encoding: String,
    /** Уже раскодированный из base64 текст (или пусто, если не текст/не удалось). */
    val text: String,
)

/** Один элемент дерева каталога. */
data class GitHubEntry(val path: String, val name: String, val type: String, val size: Int) {
    val isDir: Boolean get() = type == "dir"
    val line: String get() = "${if (isDir) "📁" else "📄"} $path"
}

/** Совпадение из GET /search/code. */
data class GitHubCodeHit(val repo: String, val path: String, val htmlUrl: String) {
    val line: String get() = "• $repo — $path"
}

/** Кодирование содержимого файлов GitHub (contents API отдаёт и принимает base64). */
object Base64Content {
    /** GitHub присылает base64 c переводами строк — их нужно убрать перед декодированием. */
    fun decode(base64: String): String? = runCatching {
        val cleaned = base64.replace("\n", "").replace("\r", "").trim()
        if (cleaned.isEmpty()) return ""
        String(java.util.Base64.getMimeDecoder().decode(cleaned), Charsets.UTF_8)
    }.getOrNull()

    /** Кодирует текст файла в одну строку base64 для PUT contents. */
    fun encode(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
}

/** Разбор JSON-ответов GitHub. */
object GitHubModels {
    fun parse(json: String): JsonElement? = parseJson(json)

    /** Логин аккаунта из GET /user. */
    fun userLogin(json: String): String? = parse(json)["login"].str

    /** full_name репозитория из ответа создания (POST /user/repos). */
    fun fullName(json: String): String? = parse(json)["full_name"].str

    /** Список репозиториев из массива JSON. */
    fun repos(json: String): List<GitHubRepo> {
        val items = parse(json).arr ?: return emptyList()
        return items.mapNotNull { item ->
            val full = item["full_name"].str ?: return@mapNotNull null
            GitHubRepo(
                fullName = full,
                name = item["name"].str ?: full.substringAfterLast('/'),
                private = item["private"].bool ?: false,
                language = item["language"].str.orEmpty(),
                stars = item["stargazers_count"].int ?: 0,
                description = item["description"].str.orEmpty(),
                htmlUrl = item["html_url"].str.orEmpty(),
            )
        }
    }

    /** Содержимое файла: раскодированный текст и sha (нужен для последующей записи). */
    fun file(json: String): GitHubFile? {
        val obj = parse(json).obj ?: return null
        // Каталог отдаётся массивом, а не объектом — это не файл.
        if (obj["type"].str != "file" && obj["content"] == null) return null
        val encoding = obj["encoding"].str.orEmpty()
        val rawContent = obj["content"].str.orEmpty()
        val text = if (encoding == "base64") Base64Content.decode(rawContent).orEmpty() else rawContent
        return GitHubFile(
            path = obj["path"].str.orEmpty(),
            sha = obj["sha"].str.orEmpty(),
            size = obj["size"].int ?: text.length,
            encoding = encoding,
            text = text,
        )
    }

    /** sha существующего файла (для обновления) — null, если файла ещё нет. */
    fun sha(json: String): String? = parse(json).obj?.get("sha").str

    /** Каталог: массив элементов. Возвращает пустой список, если пришёл не массив (например, файл). */
    fun listing(json: String): List<GitHubEntry> {
        val items = parse(json).arr ?: return emptyList()
        return items.mapNotNull { item ->
            val path = item["path"].str ?: return@mapNotNull null
            GitHubEntry(path, item["name"].str ?: path.substringAfterLast('/'), item["type"].str.orEmpty(), item["size"].int ?: 0)
        }
    }

    /** Результаты поиска кода из GET /search/code. */
    fun codeHits(json: String): List<GitHubCodeHit> {
        val items = parse(json)["items"].arr ?: return emptyList()
        return items.mapNotNull { item ->
            val path = item["path"].str ?: return@mapNotNull null
            GitHubCodeHit(item["repository"]["full_name"].str.orEmpty(), path, item["html_url"].str.orEmpty())
        }
    }

    /** Приводит «owner/name», ссылку github.com/owner/name или «owner / name» к «owner/name». */
    fun normalizeRepo(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        // Отбрасываем протокол и хост, если это ссылка.
        value = value.substringAfter("://", value)
        if (value.startsWith("github.com") || value.contains("github.com/")) {
            value = value.substringAfter("github.com").removePrefix("/")
        }
        value = value.substringBefore('?').substringBefore('#')
        val parts = value.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) "${parts[0]}/${parts[1].removeSuffix(".git")}" else null
    }
}

/**
 * Чистое решение о подтверждении для инструментов GitHub. Запись файла и создание репозитория —
 * изменяющие действия: их нельзя выполнять, пока пользователь не подтвердил (та же логика, что у агента).
 */
object GitHubWriteGate {
    /** Инструменты, меняющие GitHub пользователя. */
    val modifyingTools = setOf("github_write_file", "github_create_repo")

    fun isModifying(toolName: String): Boolean = toolName in modifyingTools

    /** Текст карточки подтверждения записи файла. */
    fun writeFileCard(repo: String, path: String, creating: Boolean): String =
        "${if (creating) "Создать" else "Записать"} файл «$path» в репозиторий $repo?"

    /** Текст карточки подтверждения создания репозитория. */
    fun createRepoCard(name: String, private: Boolean): String =
        "Создать ${if (private) "приватный" else "публичный"} репозиторий «$name» на GitHub?"

    /**
     * Итог решения: выполнять ли действие. Для изменяющего инструмента — только если получено
     * подтверждение; для остальных — всегда.
     */
    fun shouldProceed(toolName: String, confirmed: Boolean): Boolean =
        if (isModifying(toolName)) confirmed else true
}
