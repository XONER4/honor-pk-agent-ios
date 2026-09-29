package com.honerai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.github.GitHubClient
import com.honerai.app.core.github.GitHubIntegration
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// integ: страница «GitHub» под «Интеграции» — пользователь сам вставляет персональный токен.
// Токен хранится в зашифрованном хранилище (GitHubIntegration) и никогда не показывается и не логируется.

@Composable
internal fun GitHubSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val store = remember { GitHubIntegration.tokenStore(context) }
    fun t(ru: String, en: String) = settings.text(ru, en)

    var connected by remember { mutableStateOf(store.isConnected) }
    var login by remember { mutableStateOf(store.login) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // При открытии страницы с уже сохранённым токеном подтягиваем логин аккаунта (GET /user).
    LaunchedEffect(Unit) {
        if (store.isConnected && store.login == null) {
            busy = true
            val fetched = withContext(Dispatchers.IO) { runCatching { GitHubClient(store.token!!).userLogin() }.getOrNull() }
            busy = false
            if (fetched != null) { store.login = fetched; login = fetched }
        }
    }

    SettingsPageScaffold(t("GitHub", "GitHub"), "settings.page.github", onBack) {
        item(key = "status") {
            SettingsGroup(
                footer = t(
                    "Токен хранится только на этом телефоне в зашифрованном виде и используется для работы с вашими репозиториями через официальный API GitHub. Пароль вводить не нужно — только персональный токен. Создать его: github.com → Settings → Developer settings → Fine-grained tokens (дайте доступ к нужным репозиториям, права Contents: Read and write).",
                    "The token is stored only on this phone, encrypted, and used to work with your repositories via the official GitHub API. No password is needed — only a personal token. Create it at github.com → Settings → Developer settings → Fine-grained tokens (grant access to the repos you need, Contents: Read and write).",
                ),
            ) {
                if (connected) {
                    SettingsRow(
                        Icons.Outlined.Code, t("Подключён аккаунт", "Connected account"),
                        value = login ?: (if (busy) t("проверяю…", "checking…") else t("токен сохранён", "token saved")),
                        chevron = false, tag = "github.connected",
                    )
                } else {
                    SettingsRow(Icons.Outlined.Code, t("GitHub не подключён", "GitHub not connected"), chevron = false, tag = "github.disconnected")
                }
            }
        }
        if (!connected) {
            item(key = "input") {
                SettingsGroup(t("Токен GitHub", "GitHub token")) {
                    Box(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).clip(RoundedCornerShape(12.dp))
                            .background(colors.foreground.copy(alpha = 0.05f)).padding(12.dp),
                    ) {
                        if (draft.isEmpty()) {
                            Text("github_pat_… / ghp_…", color = colors.secondary, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = draft, onValueChange = { draft = it.trim() }, singleLine = true,
                            textStyle = TextStyle(color = colors.foreground, fontSize = 15.sp),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.testTag("github.token.field"),
                        )
                    }
                    SettingsDivider()
                    SettingsButtonRow(
                        if (busy) t("Проверяю…", "Checking…") else t("Подключить", "Connect"),
                        enabled = draft.isNotBlank() && !busy, tag = "github.connect",
                    ) {
                        val value = draft.trim()
                        busy = true
                        message = null
                        scope.launch {
                            val fetched = withContext(Dispatchers.IO) { runCatching { GitHubClient(value).userLogin() }.getOrNull() }
                            busy = false
                            if (fetched != null) {
                                store.token = value
                                store.login = fetched
                                login = fetched
                                connected = true
                                draft = ""
                            } else {
                                message = t("Не удалось подключиться: проверьте токен и доступ к интернету.",
                                    "Could not connect: check the token and your internet connection.")
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "disconnect") {
                SettingsGroup {
                    SettingsButtonRow(t("Отключить", "Disconnect"), destructive = true, tag = "github.disconnect") {
                        store.disconnect()
                        connected = false
                        login = null
                        message = null
                    }
                }
            }
        }
        message?.let { text -> item(key = "message") { SettingsFootnote(text, color = DestructiveRed) } }
    }
}
