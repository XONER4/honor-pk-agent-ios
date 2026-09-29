package com.honerai.app.core.github

import com.honerai.app.core.HonerHttp
import com.honerai.app.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

// github: тонкий клиент официального REST API GitHub (api.github.com) с токеном пользователя.
// Все методы асинхронные, ходят через общий пул OkHttp (HonerHttp). Токен передаётся заголовком
// Authorization: Bearer <token> и никогда не пишется в лог.

/** Ответ API: код и тело. */
class GitHubResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** Клиент GitHub. [token] обязателен — вызывающий код проверяет его наличие заранее. */
class GitHubClient(
    private val token: String,
    private val http: OkHttpClient = HonerHttp.base,
) {
    private val api = "https://api.github.com"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Путь файла: сегменты кодируются, но «/» между ними сохраняются. */
    private fun encPath(path: String): String = path.trim('/').split('/').joinToString("/") { enc(it) }

    private suspend fun request(method: String, path: String, body: JsonObject? = null): GitHubResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(if (path.startsWith("http")) path else "$api$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "HonerAI-Android")
            when (method) {
                "GET" -> builder.get()
                else -> builder.method(method, (body?.toString() ?: "{}").toRequestBody(jsonType))
            }
            try {
                http.newCall(builder.build()).await().use { response ->
                    val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    GitHubResponse(response.code, text)
                }
            } catch (e: IOException) {
                GitHubResponse(0, "")
            }
        }

    /** GET /user — логин подключённого аккаунта, либо null при неверном токене. */
    suspend fun userLogin(): String? {
        val response = request("GET", "/user")
        if (!response.ok) return null
        return GitHubModels.userLogin(response.body)
    }

    /** GET /user/repos — репозитории пользователя (в т.ч. приватные). */
    suspend fun repos(): GitHubResponse = request("GET", "/user/repos?per_page=30&sort=updated&affiliation=owner,collaborator,organization_member")

    /** GET /repos/{repo}/contents/{path}[?ref=] — содержимое файла или каталога. */
    suspend fun contents(repo: String, path: String, ref: String? = null): GitHubResponse {
        val query = if (ref.isNullOrBlank()) "" else "?ref=${enc(ref)}"
        val folder = if (path.trim('/').isEmpty()) "" else "/${encPath(path)}"
        return request("GET", "/repos/$repo/contents$folder$query")
    }

    /**
     * PUT /repos/{repo}/contents/{path} — создать или обновить файл. Если файл уже есть,
     * сначала берётся его sha (иначе GitHub отклонит обновление).
     */
    suspend fun writeFile(repo: String, path: String, content: String, message: String, branch: String?): GitHubResponse {
        val existing = contents(repo, path, branch)
        val sha = if (existing.ok) GitHubModels.sha(existing.body) else null
        val body = buildJsonObject {
            put("message", message.ifBlank { "Update ${path.substringAfterLast('/')}" })
            put("content", Base64Content.encode(content))
            if (!branch.isNullOrBlank()) put("branch", branch)
            if (sha != null) put("sha", sha)
        }
        return request("PUT", "/repos/$repo/contents/${encPath(path)}", body)
    }

    /** Был ли файл до записи (для формулировки «создать»/«обновить» и карточки подтверждения). */
    suspend fun fileExists(repo: String, path: String, branch: String?): Boolean = contents(repo, path, branch).ok

    /** GET /search/code — поиск кода (можно ограничить репозиторием). */
    suspend fun searchCode(repo: String?, query: String): GitHubResponse {
        val q = if (repo.isNullOrBlank()) query else "$query repo:$repo"
        return request("GET", "/search/code?q=${enc(q)}&per_page=15")
    }

    /** POST /user/repos — создать репозиторий. */
    suspend fun createRepo(name: String, private: Boolean): GitHubResponse {
        val body = buildJsonObject {
            put("name", name)
            put("private", private)
            put("auto_init", true)
        }
        return request("POST", "/user/repos", body)
    }
}
