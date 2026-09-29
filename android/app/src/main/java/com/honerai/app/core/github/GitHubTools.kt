package com.honerai.app.core.github

import com.honerai.app.core.HonerTool
import com.honerai.app.core.ToolArgument
import com.honerai.app.core.ToolCallRequest
import com.honerai.app.core.ToolCallResult

// github: выполнение инструментов GitHub. Read-действия идут сразу; write/create проходят
// через подтверждение пользователя (тот же механизм, что у агента: карточка + «да»).

/**
 * @param token токен пользователя или null (тогда все инструменты просят подключить GitHub).
 * @param confirm ставит действие на подтверждение и ждёт решения пользователя (кнопка или «да/нет»).
 *        Возвращает true, если пользователь согласился.
 */
class GitHubToolExecutor(
    private val token: String?,
    private val confirm: suspend (description: String, amount: String?) -> Boolean,
    private val clientFactory: (String) -> GitHubClient = { GitHubClient(it) },
) {
    private fun reply(call: ToolCallRequest, text: String) = ToolCallResult(call.id, call.name, text)

    private val notConnected =
        "GitHub не подключён. Скажи пользователю: чтобы работать с его репозиториями, нужно вставить токен в Настройки → Интеграции → GitHub (github.com → Settings → Developer settings → Fine-grained token)."

    suspend fun execute(call: ToolCallRequest): ToolCallResult {
        val tool = HonerTool.from(call.name) ?: return reply(call, "Неизвестный инструмент.")
        val value = token ?: return reply(call, notConnected)
        val client = clientFactory(value)
        val arguments = call.parsedArguments
        fun arg(key: String) = ToolArgument.string(arguments[key]).orEmpty().trim()
        return when (tool) {
            HonerTool.GITHUB_REPOS -> {
                val response = client.repos()
                if (!response.ok) return reply(call, apiError(response.status, "получить список репозиториев"))
                val repos = GitHubModels.repos(response.body)
                if (repos.isEmpty()) return reply(call, "У пользователя нет репозиториев на GitHub.")
                reply(call, "Репозитории пользователя на GitHub (${repos.size}):\n" + repos.joinToString("\n") { it.line })
            }
            HonerTool.GITHUB_READ_FILE -> {
                val repo = GitHubModels.normalizeRepo(arg("repo")) ?: return reply(call, "Укажи репозиторий в виде owner/name.")
                val path = arg("path")
                if (path.isEmpty()) return reply(call, "Не передан путь к файлу.")
                val response = client.contents(repo, path, arg("ref").ifEmpty { null })
                if (!response.ok) return reply(call, apiError(response.status, "прочитать файл $path в $repo"))
                val file = GitHubModels.file(response.body)
                    ?: return reply(call, "По пути $path в $repo лежит папка, а не файл. Посмотри содержимое инструментом github_list.")
                val lang = path.substringAfterLast('.', "")
                reply(call, "Файл $repo/${file.path}:\n```$lang\n${file.text.take(40_000)}\n```")
            }
            HonerTool.GITHUB_LIST -> {
                val repo = GitHubModels.normalizeRepo(arg("repo")) ?: return reply(call, "Укажи репозиторий в виде owner/name.")
                val path = arg("path")
                val response = client.contents(repo, path)
                if (!response.ok) return reply(call, apiError(response.status, "открыть папку в $repo"))
                val entries = GitHubModels.listing(response.body)
                if (entries.isEmpty()) return reply(call, "Папка пуста или это файл. Для чтения файла используй github_read_file.")
                val where = if (path.isBlank()) repo else "$repo/${path.trim('/')}"
                reply(call, "Содержимое $where:\n" + entries.joinToString("\n") { it.line })
            }
            HonerTool.GITHUB_SEARCH_CODE -> {
                val query = arg("query")
                if (query.isEmpty()) return reply(call, "Не передан поисковый запрос.")
                val repo = GitHubModels.normalizeRepo(arg("repo"))
                val response = client.searchCode(repo, query)
                if (!response.ok) return reply(call, apiError(response.status, "искать код"))
                val hits = GitHubModels.codeHits(response.body)
                if (hits.isEmpty()) return reply(call, "По запросу «$query» код не найден.")
                reply(call, "Найдено на GitHub (${hits.size}):\n" + hits.joinToString("\n") { it.line })
            }
            HonerTool.GITHUB_WRITE_FILE -> {
                val repo = GitHubModels.normalizeRepo(arg("repo")) ?: return reply(call, "Укажи репозиторий в виде owner/name.")
                val path = arg("path")
                val content = ToolArgument.string(arguments["content"]) ?: ""
                val message = arg("message")
                val branch = arg("branch").ifEmpty { null }
                if (path.isEmpty()) return reply(call, "Не передан путь к файлу.")
                val creating = !client.fileExists(repo, path, branch)
                // Подтверждение перед коммитом — коммит не уходит, пока пользователь не согласился.
                val approved = confirm(GitHubWriteGate.writeFileCard(repo, path, creating), null)
                if (!approved) return reply(call, "Запись файла отменена пользователем. Не выполняй её и не повторяй без новой просьбы.")
                val response = client.writeFile(repo, path, content, message, branch)
                if (!response.ok) return reply(call, apiError(response.status, "записать файл $path в $repo"))
                reply(call, "Готово: файл $path ${if (creating) "создан" else "обновлён"} в $repo (коммит сделан).")
            }
            HonerTool.GITHUB_CREATE_REPO -> {
                val name = arg("name")
                if (name.isEmpty()) return reply(call, "Не передано название репозитория.")
                val private = ToolArgument.bool(arguments["private"]) ?: true
                val approved = confirm(GitHubWriteGate.createRepoCard(name, private), null)
                if (!approved) return reply(call, "Создание репозитория отменено пользователем.")
                val response = client.createRepo(name, private)
                if (!response.ok) return reply(call, apiError(response.status, "создать репозиторий «$name»"))
                val full = GitHubModels.fullName(response.body) ?: name
                reply(call, "Репозиторий «$full» создан на GitHub (${if (private) "приватный" else "публичный"}).")
            }
            else -> reply(call, "Неизвестный инструмент GitHub.")
        }
    }

    private fun apiError(status: Int, action: String): String = when (status) {
        0 -> "GitHub не ответил (нет сети). Не удалось $action. Предложи повторить позже."
        401 -> "GitHub отклонил токен (401). Скажи пользователю проверить токен в Настройки → Интеграции → GitHub."
        403 -> "GitHub отказал (403): не хватает прав токена или превышен лимит запросов. Не удалось $action."
        404 -> "GitHub не нашёл нужное (404) при попытке $action. Проверь имя репозитория/путь и права токена."
        else -> "GitHub вернул ошибку $status при попытке $action."
    }
}
