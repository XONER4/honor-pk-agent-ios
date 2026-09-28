package com.honerai.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.honerai.app.BuildConfig
import com.honerai.app.device.ParentalControl
import com.honerai.app.ui.help.HelpCenterScreen
import com.honerai.app.ui.parental.ParentalControlPage

// Настройки (порт SettingsView.swift): главный список и все страницы.
// Переходы — собственный стек страниц внутри экрана; системная «Назад» возвращает на шаг.

/** Страницы настроек. */
internal enum class SettingsPage {
    ROOT, PROFILE, DATA, ARCHIVE, LANGUAGE, FONT, PERMISSIONS, PARENTAL, INTEGRATIONS, MEMORY,
    VOICE, VOICE_CLONE, STATISTICS, GUIDE, ABOUT,
}

/** Настройки со всеми страницами. [onClose] — закрыть настройки. */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    remember { ParentalControl.init(context); BackupService.init(context); true }
    val settings = appSettings()
    val language by settings.language.collectAsState()
    val reduceMotion = rememberReduceMotion()
    var stackNames by rememberSaveable { mutableStateOf(listOf(SettingsPage.ROOT.name)) }
    val stack = stackNames.map { SettingsPage.valueOf(it) }
    var forward by remember { mutableStateOf(true) }
    val push: (SettingsPage) -> Unit = { page -> forward = true; stackNames = stackNames + page.name }
    val pop: () -> Unit = {
        forward = false
        if (stackNames.size > 1) stackNames = stackNames.dropLast(1) else onClose()
    }
    val rootListState = rememberLazyListState()

    // Руководство обрабатывает «Назад» само (статьи, просмотр скриншота).
    BackHandler(enabled = stack.last() != SettingsPage.GUIDE) { pop() }

    // Смена языка перерисовывает все строки сразу.
    key(language) {
        Box(Modifier.fillMaxSize().background(pageBackground)) {
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    else if (forward) (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(180)))
                    else (slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(180)))
                },
                label = "settings.nav",
            ) { page ->
                when (page) {
                    SettingsPage.ROOT -> SettingsRootPage(rootListState, push, onClose)
                    SettingsPage.PROFILE -> ProfileSettingsPage(pop)
                    SettingsPage.DATA -> DataSettingsPage(pop)
                    SettingsPage.ARCHIVE -> ArchivedChatsPage(pop)
                    SettingsPage.LANGUAGE -> LanguageSettingsPage(pop)
                    SettingsPage.FONT -> FontSettingsPage(pop)
                    SettingsPage.PERMISSIONS -> PermissionsPage(pop)
                    SettingsPage.PARENTAL -> ParentalControlPage(pop)
                    SettingsPage.INTEGRATIONS -> IntegrationsPage(pop)
                    SettingsPage.MEMORY -> MemorySettingsPage(pop)
                    SettingsPage.VOICE -> VoiceSettingsPage(pop, push)
                    SettingsPage.VOICE_CLONE -> VoiceClonePage(pop)
                    SettingsPage.STATISTICS -> StatisticsSettingsPage(pop)
                    SettingsPage.GUIDE -> HelpCenterScreen(onBack = pop)
                    SettingsPage.ABOUT -> AboutSettingsPage(pop)
                }
            }
        }
    }
}

@Composable
private fun SettingsRootPage(listState: androidx.compose.foundation.lazy.LazyListState, push: (SettingsPage) -> Unit, onClose: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    val store = container.store
    val conversations by store.conversations.collectAsState()
    val memories by store.memories.collectAsState()
    val appearance by settings.appearance.collectAsState()
    val speechLanguage by settings.speechLanguage.collectAsState()
    val parental by ParentalControl.state.collectAsState()
    val archivedCount = remember(conversations) { store.archivedConversations().size }
    fun t(ru: String, en: String) = settings.text(ru, en)

    SettingsPageScaffold(t("Настройки", "Settings"), "settings.page.root", onBack = null, onClose = onClose, state = listState) {
        item(key = "profile") {
            SettingsGroup(t("Профиль", "Profile")) {
                SettingsRow(Icons.Outlined.Person, t("Настройки аккаунта", "Account settings"), tag = "settings.profile") { push(SettingsPage.PROFILE) }
                SettingsDivider()
                SettingsRow(Icons.Outlined.Storage, t("Управление данными", "Data management"), tag = "settings.data") { push(SettingsPage.DATA) }
                SettingsDivider()
                SettingsRow(Icons.Outlined.Archive, t("Архив чатов", "Archived chats"), "$archivedCount", tag = "settings.archive") { push(SettingsPage.ARCHIVE) }
            }
        }
        item(key = "app") {
            SettingsGroup(t("Приложение", "Application")) {
                SettingsRow(Icons.Outlined.Language, t("Язык", "Language"), if (settings.isEnglish) "English" else "Русский", tag = "settings.language") { push(SettingsPage.LANGUAGE) }
                SettingsDivider()
                SettingsMenuRow(
                    Icons.Outlined.Bedtime, t("Внешний вид", "Appearance"),
                    listOf("system" to t("Система", "System"), "light" to t("Светлый", "Light"), "dark" to t("Тёмный", "Dark")),
                    appearance, { settings.setAppearance(it) }, tag = "settings.appearance",
                )
                SettingsDivider()
                SettingsRow(Icons.Outlined.FormatSize, t("Размер шрифта", "Font size"), tag = "settings.font") { push(SettingsPage.FONT) }
                SettingsDivider()
                SettingsRow(Icons.Outlined.Security, t("Разрешения", "Permissions"), tag = "settings.permissions") { push(SettingsPage.PERMISSIONS) }
                SettingsDivider()
                SettingsRow(
                    Icons.Outlined.FamilyRestroom, t("Родительский контроль", "Parental control"),
                    if (parental.rules.enabled) t("включён", "on") else t("выключен", "off"), tag = "settings.parental",
                ) { push(SettingsPage.PARENTAL) }
                SettingsDivider()
                SettingsRow(Icons.Outlined.GridView, t("Интеграции", "Integrations"), tag = "settings.integrations") { push(SettingsPage.INTEGRATIONS) }
                SettingsDivider()
                SettingsRow(Icons.Outlined.Psychology, t("Память Honer AI", "Honer AI memory"), "${memories.size}", tag = "settings.memory") { push(SettingsPage.MEMORY) }
            }
        }
        item(key = "audio") {
            SettingsGroup(
                t("Аудио", "Audio"),
                footer = t(
                    "Выберите язык, который вы используете для голосового ввода, чтобы улучшить распознавание.",
                    "Choose the language you use for voice input to improve speech recognition.",
                ),
            ) {
                SettingsRow(Icons.AutoMirrored.Outlined.VolumeUp, t("Голос", "Voice"), tag = "settings.voice") { push(SettingsPage.VOICE) }
                SettingsDivider()
                SettingsMenuRow(
                    Icons.Outlined.Mic, t("Основной язык", "Speech language"),
                    listOf("ru-RU" to "Русский", "en-US" to "English (US)", "en-GB" to "English (UK)"),
                    speechLanguage, { settings.setSpeechLanguage(it) }, tag = "settings.speechLanguage",
                )
            }
        }
        item(key = "about") {
            SettingsGroup(t("О программе", "About")) {
                SettingsRow(Icons.Outlined.Info, t("Версия", "Version"), appVersionText(), chevron = false, tag = "settings.version")
                SettingsDivider()
                SettingsRow(Icons.Outlined.BarChart, t("Статистика", "Statistics"), tag = "settings.statistics") { push(SettingsPage.STATISTICS) }
                SettingsDivider()
                SettingsRow(Icons.AutoMirrored.Outlined.MenuBook, t("Руководство и возможности", "Guide and features"), tag = "settings.guide") { push(SettingsPage.GUIDE) }
                SettingsDivider()
                SettingsRow(Icons.Outlined.Description, "Honer AI", tag = "settings.about") { push(SettingsPage.ABOUT) }
            }
        }
    }
}

/** «10.44.0 (1044)». */
internal fun appVersionText(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
