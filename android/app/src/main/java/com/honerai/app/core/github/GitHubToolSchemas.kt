package com.honerai.app.core.github

import com.honerai.app.core.HonerTool
import com.honerai.app.core.ToolArgument
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.core.ToolSchema
import com.honerai.app.data.GenerationStep
import kotlinx.serialization.json.JsonObject

// github: описания инструментов GitHub для модели, шаги ленты и строка состояния.

object GitHubToolSchemas {
    private val s = ToolSchema

    private val tools = setOf(
        HonerTool.GITHUB_REPOS, HonerTool.GITHUB_READ_FILE, HonerTool.GITHUB_LIST,
        HonerTool.GITHUB_WRITE_FILE, HonerTool.GITHUB_SEARCH_CODE, HonerTool.GITHUB_CREATE_REPO,
    )

    fun isGitHubTool(tool: HonerTool): Boolean = tool in tools

    fun schema(tool: HonerTool): JsonObject = when (tool) {
        HonerTool.GITHUB_REPOS -> s.function(tool.rawValue,
            "Показывает репозитории пользователя на GitHub через официальный API (нужен подключённый токен в Настройки → Интеграции → GitHub): название, приватный или публичный, язык, число звёзд.",
            emptyMap(), emptyList())
        HonerTool.GITHUB_READ_FILE -> s.function(tool.rawValue,
            "Читает содержимое файла из репозитория пользователя на GitHub (в т.ч. приватного). Возвращает текст файла.",
            mapOf(
                "repo" to s.string("Репозиторий в виде owner/name"),
                "path" to s.string("Путь к файлу в репозитории, например src/App.kt"),
                "ref" to s.string("Ветка, тег или коммит; необязательно (по умолчанию основная ветка)"),
            ), listOf("repo", "path"))
        HonerTool.GITHUB_LIST -> s.function(tool.rawValue,
            "Показывает содержимое папки (или корня) репозитория пользователя на GitHub: файлы и подпапки.",
            mapOf(
                "repo" to s.string("Репозиторий в виде owner/name"),
                "path" to s.string("Путь к папке; пусто — корень репозитория"),
            ), listOf("repo"))
        HonerTool.GITHUB_WRITE_FILE -> s.function(tool.rawValue,
            "Создаёт или обновляет файл в репозитории пользователя на GitHub (делает коммит). Это изменяющее действие: приложение сначала покажет карточку подтверждения, и коммит уйдёт только после согласия пользователя. Для существующего файла sha берётся автоматически.",
            mapOf(
                "repo" to s.string("Репозиторий в виде owner/name"),
                "path" to s.string("Путь к файлу, например docs/notes.md"),
                "content" to s.string("Новое полное содержимое файла (текст)"),
                "message" to s.string("Сообщение коммита"),
                "branch" to s.string("Ветка для коммита; необязательно"),
            ), listOf("repo", "path", "content", "message"))
        HonerTool.GITHUB_SEARCH_CODE -> s.function(tool.rawValue,
            "Ищет код на GitHub через официальный API. Можно ограничить одним репозиторием пользователя.",
            mapOf(
                "query" to s.string("Что искать в коде"),
                "repo" to s.string("Ограничить репозиторием owner/name; необязательно"),
            ), listOf("query"))
        HonerTool.GITHUB_CREATE_REPO -> s.function(tool.rawValue,
            "Создаёт новый репозиторий у пользователя на GitHub. Изменяющее действие: приложение сначала покажет карточку подтверждения.",
            mapOf(
                "name" to s.string("Название репозитория"),
                "private" to s.boolean("true — приватный, false — публичный (по умолчанию приватный)"),
            ), listOf("name"))
        else -> s.function(tool.rawValue, "", emptyMap(), emptyList())
    }

    fun step(call: ToolCallRequest): GenerationStep? {
        val arguments = call.parsedArguments
        fun arg(key: String) = ToolArgument.string(arguments[key]).orEmpty().take(120)
        val tool = HonerTool.from(call.name) ?: return null
        if (!isGitHubTool(tool)) return null
        val sites = listOf("github.com")
        return when (tool) {
            HonerTool.GITHUB_REPOS -> GenerationStep(kind = "read", title = "Смотрю репозитории GitHub", sites = sites)
            HonerTool.GITHUB_READ_FILE -> GenerationStep(kind = "read", title = "Читаю файл на GitHub", detail = arg("path"), sites = sites)
            HonerTool.GITHUB_LIST -> GenerationStep(kind = "read", title = "Смотрю файлы на GitHub", detail = arg("repo"), sites = sites)
            HonerTool.GITHUB_WRITE_FILE -> GenerationStep(kind = "settings", title = "Готовлю запись файла на GitHub", detail = arg("path"), sites = sites)
            HonerTool.GITHUB_SEARCH_CODE -> GenerationStep(kind = "search", title = "Ищу код на GitHub", detail = "«${arg("query")}»", sites = sites)
            HonerTool.GITHUB_CREATE_REPO -> GenerationStep(kind = "settings", title = "Готовлю создание репозитория", detail = arg("name"), sites = sites)
            else -> null
        }
    }

    fun status(names: Set<String>): String? = when {
        HonerTool.GITHUB_WRITE_FILE.rawValue in names -> "Готовлю запись на GitHub…"
        HonerTool.GITHUB_CREATE_REPO.rawValue in names -> "Создаю репозиторий…"
        tools.any { it.rawValue in names } -> "Смотрю GitHub…"
        else -> null
    }

    /** Блок системной инструкции: инструменты GitHub и что записи требуют подтверждения. */
    const val PROMPT = "\n\n## GitHub (официальный API по токену пользователя)\n" +
        "• Если пользователь подключил токен GitHub (Настройки → Интеграции → GitHub), доступны: github_repos (список репозиториев), github_read_file (прочитать файл), github_list (файлы папки), github_search_code (поиск кода), github_write_file (создать/обновить файл коммитом) и github_create_repo (новый репозиторий).\n" +
        "• Если токена нет, инструменты вернут просьбу подключить GitHub в настройках — передай её пользователю, не выдумывай данные.\n" +
        "• github_write_file и github_create_repo — изменяющие действия. Приложение само покажет карточку подтверждения и выполнит коммит только после согласия пользователя; ты просто вызови инструмент с нужными данными. Никогда не проси и не вставляй сам токен."
}
