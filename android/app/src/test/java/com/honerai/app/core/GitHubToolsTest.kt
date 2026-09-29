package com.honerai.app.core

import com.honerai.app.core.github.Base64Content
import com.honerai.app.core.github.GitHubModels
import com.honerai.app.core.github.GitHubToolExecutor
import com.honerai.app.core.github.GitHubTokenStore
import com.honerai.app.core.github.GitHubWriteGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// integ: хранилище токена GitHub, разбор JSON API, base64 и гейтинг подтверждения записи.
class GitHubToolsTest {

    @Test fun tokenStoreRoundTrips() {
        val store = GitHubTokenStore(MemoryKeyValueStore())
        assertFalse(store.isConnected)
        assertNull(store.token)
        store.token = "  ghp_secret123  "
        assertEquals("ghp_secret123", store.token) // обрезаются пробелы
        assertTrue(store.isConnected)
        store.login = "octocat"
        assertEquals("octocat", store.login)
        // Пустая строка равносильна очистке.
        store.token = ""
        assertNull(store.token)
        assertFalse(store.isConnected)
        store.token = "ghp_x"
        store.disconnect()
        assertNull(store.token)
        assertNull(store.login)
    }

    @Test fun parsesReposAndUserAndListing() {
        assertEquals("octocat", GitHubModels.userLogin("""{"login":"octocat","id":1}"""))
        val repos = GitHubModels.repos("""[
            {"full_name":"octocat/Hello-World","name":"Hello-World","private":false,"language":"Kotlin","stargazers_count":42,"description":"пример","html_url":"https://github.com/octocat/Hello-World"},
            {"full_name":"octocat/secret","name":"secret","private":true,"stargazers_count":0}
        ]""")
        assertEquals(2, repos.size)
        assertEquals("octocat/Hello-World", repos[0].fullName)
        assertFalse(repos[0].private)
        assertEquals("Kotlin", repos[0].language)
        assertEquals(42, repos[0].stars)
        assertTrue(repos[0].line.contains("★42"))
        assertTrue(repos[1].private)
        assertTrue(repos[1].line.contains("приватный"))

        val entries = GitHubModels.listing("""[
            {"path":"src","name":"src","type":"dir","size":0},
            {"path":"README.md","name":"README.md","type":"file","size":120}
        ]""")
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDir)
        assertFalse(entries[1].isDir)
        assertTrue(entries[0].line.startsWith("📁"))
        // Файл, а не каталог, приходит объектом — listing тогда пуст.
        assertTrue(GitHubModels.listing("""{"type":"file","path":"a"}""").isEmpty())

        val hits = GitHubModels.codeHits("""{"items":[{"path":"src/App.kt","html_url":"https://github.com/o/r/blob/main/src/App.kt","repository":{"full_name":"o/r"}}]}""")
        assertEquals(1, hits.size)
        assertEquals("o/r", hits[0].repo)
        assertEquals("src/App.kt", hits[0].path)
        assertEquals("octocat/new-repo", GitHubModels.fullName("""{"full_name":"octocat/new-repo"}"""))
    }

    @Test fun parsesFileContentAndSha() {
        val json = """{"type":"file","path":"docs/notes.md","sha":"abc123","size":5,"encoding":"base64","content":"aGVsbG8=\n"}"""
        val file = GitHubModels.file(json)!!
        assertEquals("docs/notes.md", file.path)
        assertEquals("abc123", file.sha)
        assertEquals("hello", file.text)
        assertEquals("abc123", GitHubModels.sha(json))
        // Каталог (массив) — это не файл.
        assertNull(GitHubModels.file("""[{"path":"a","type":"file"}]"""))
    }

    @Test fun base64EncodeDecodeRoundTrip() {
        assertEquals("aGVsbG8=", Base64Content.encode("hello"))
        assertEquals("hello", Base64Content.decode("aGVsbG8="))
        // GitHub отдаёт base64 с переводами строк — их надо игнорировать.
        assertEquals("hello", Base64Content.decode("aGVs\nbG8=\n"))
        val text = "Привет, мир!\nЛиния 2\tтаб"
        assertEquals(text, Base64Content.decode(Base64Content.encode(text)))
        assertEquals("", Base64Content.decode(""))
    }

    @Test fun normalizeRepoAcceptsSeveralForms() {
        assertEquals("octocat/Hello-World", GitHubModels.normalizeRepo("octocat/Hello-World"))
        assertEquals("octocat/Hello-World", GitHubModels.normalizeRepo("https://github.com/octocat/Hello-World"))
        assertEquals("octocat/Hello-World", GitHubModels.normalizeRepo("github.com/octocat/Hello-World/tree/main"))
        assertEquals("o/r", GitHubModels.normalizeRepo("o / r"))
        assertEquals("o/r", GitHubModels.normalizeRepo("https://github.com/o/r.git"))
        assertNull(GitHubModels.normalizeRepo("just-a-name"))
        assertNull(GitHubModels.normalizeRepo(""))
    }

    @Test fun writeGateRequiresConfirmationOnlyForModifyingTools() {
        assertTrue(GitHubWriteGate.isModifying("github_write_file"))
        assertTrue(GitHubWriteGate.isModifying("github_create_repo"))
        assertFalse(GitHubWriteGate.isModifying("github_repos"))
        assertFalse(GitHubWriteGate.isModifying("github_read_file"))
        // Изменяющее действие выполняется только при подтверждении.
        assertFalse(GitHubWriteGate.shouldProceed("github_write_file", confirmed = false))
        assertTrue(GitHubWriteGate.shouldProceed("github_write_file", confirmed = true))
        assertTrue(GitHubWriteGate.shouldProceed("github_create_repo", confirmed = true))
        // Чтение не требует подтверждения.
        assertTrue(GitHubWriteGate.shouldProceed("github_read_file", confirmed = false))
        assertTrue(GitHubWriteGate.shouldProceed("github_repos", confirmed = false))
        assertTrue(GitHubWriteGate.writeFileCard("o/r", "a.md", creating = true).contains("Создать файл «a.md» в репозиторий o/r"))
        assertTrue(GitHubWriteGate.writeFileCard("o/r", "a.md", creating = false).contains("Записать файл"))
        assertTrue(GitHubWriteGate.createRepoCard("proj", private = true).contains("приватный"))
    }

    @Test fun toolsWithoutTokenAskToConnectAndNeverConfirm() = runBlocking {
        var confirmCalled = false
        val executor = GitHubToolExecutor(token = null, confirm = { _, _ -> confirmCalled = true; true })
        val read = executor.execute(ToolCallRequest("c1", "github_repos", "{}"))
        assertTrue(read.content.contains("GitHub не подключён"))
        val write = executor.execute(ToolCallRequest("c2", "github_write_file",
            """{"repo":"o/r","path":"a.md","content":"x","message":"m"}"""))
        assertTrue(write.content.contains("GitHub не подключён"))
        // Без токена подтверждение даже не запрашивается.
        assertFalse(confirmCalled)
    }

    @Test fun toolsAreRegistered() {
        assertEquals(HonerTool.GITHUB_REPOS, HonerTool.from("github_repos"))
        assertEquals(HonerTool.GITHUB_WRITE_FILE, HonerTool.from("github_write_file"))
        assertTrue(HonerTool.GITHUB_WRITE_FILE.isGitHub)
        assertTrue(HonerTool.GITHUB_WRITE_FILE.isAsync)
        assertFalse(HonerTool.GITHUB_WRITE_FILE.isWeb) // доступен и без кнопки «Поиск»
        val names = HonerTool.schemas(searchEnabled = false).map { it["function"]["name"].str }
        assertTrue("github_repos" in names)
        assertTrue("github_write_file" in names)
        val writeSchema = HonerTool.GITHUB_WRITE_FILE.schema["function"]["description"].str!!
        assertTrue(writeSchema.contains("подтвержд"))
    }
}
