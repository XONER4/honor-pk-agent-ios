package com.honerai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.Integrations
import com.honerai.app.device.UpdateInfo
import com.honerai.app.device.UpdateManager
import com.honerai.app.device.UpdateScheduler
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

// Язык, размер шрифта, интеграции, статистика и «О программе».

@Composable
internal fun LanguageSettingsPage(onBack: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    val language by settings.language.collectAsState()
    SettingsPageScaffold(settings.text("Язык", "Language"), "settings.page.language", onBack) {
        item(key = "languages") {
            SettingsGroup {
                listOf("ru" to "Русский", "en" to "English").forEachIndexed { index, (code, name) ->
                    if (index > 0) SettingsDivider(16)
                    SettingsCheckRow(name, language == code, tag = "language.$code") {
                        settings.setLanguage(code)
                        // Нейросеть отвечает на языке приложения.
                        container.store.setResponseLanguage(code)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FontSettingsPage(onBack: () -> Unit) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val scale by settings.fontScale.collectAsState()
    // Ползунок двигается плавно, а значение сохраняется шагами по 5 %.
    var sliderValue by remember { mutableFloatStateOf(scale.toFloat()) }
    fun t(ru: String, en: String) = settings.text(ru, en)
    SettingsPageScaffold(t("Размер шрифта", "Font size"), "settings.page.font", onBack) {
        item(key = "slider") {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A", color = colors.foreground, fontSize = 14.sp)
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it; settings.setFontScale((it * 20).roundToInt() / 20.0) },
                        valueRange = 0.85f..1.4f, steps = 10,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = colors.accent, inactiveTrackColor = colors.divider, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("font.slider"),
                    )
                    Text("A", color = colors.foreground, fontSize = 27.sp)
                }
                SettingsDivider(16)
                SettingsValueRow(t("Масштаб текста", "Text scale"), "${(scale * 100).roundToInt()}%", tag = "font.value")
                SettingsDivider(16)
                SettingsButtonRow(t("Восстановить стандартный", "Restore default"), tag = "font.reset") {
                    sliderValue = 1f
                    settings.setFontScale(1.0)
                }
            }
        }
        item(key = "preview") {
            SettingsGroup(t("Предпросмотр", "Preview")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Honer AI", color = colors.foreground, fontSize = (21 * scale).sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Привет! О чём хотите поговорить сегодня?", "Hi! What would you like to talk about today?"),
                        color = colors.foreground, fontSize = (17 * scale).sp, lineHeight = (23 * scale).sp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun IntegrationsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    var disabled by remember { mutableStateOf(Integrations.disabled(context)) }
    fun t(ru: String, en: String) = settings.text(ru, en)
    SettingsPageScaffold(t("Интеграции", "Integrations"), "settings.page.integrations", onBack) {
        item(key = "services") {
            SettingsGroup(
                footer = t(
                    "Интеграции работают через публичные страницы и открытые данные, без входа в ваши аккаунты. Нужна включённая кнопка «Поиск» в чате. Закрытые страницы и личные сообщения недоступны.",
                    "Integrations use public pages and open data, without signing in to your accounts. The Search button in the chat must be on. Private pages and messages are not available.",
                ),
            ) {
                Integrations.services.forEachIndexed { index, service ->
                    if (index > 0) SettingsDivider(62)
                    val (icon, tint) = when (service.id) {
                        "youtube" -> Icons.Outlined.SmartDisplay to Color(0xFFFF3B30)
                        "github" -> Icons.Outlined.Code to Color(0xFF333340)
                        "marketplaces" -> Icons.Outlined.ShoppingCart to Color(0xFF9933CC)
                        "vk" -> Icons.Outlined.Groups to Color(0xFF2673F2)
                        "telegram" -> Icons.AutoMirrored.Outlined.Send to Color(0xFF26A6E6)
                        else -> Icons.Outlined.Layers to Color(0xFFFF9500)
                    }
                    SettingsToggleRow(
                        service.name, service.id !in disabled,
                        { on ->
                            Integrations.setEnabled(context, service.id, on)
                            disabled = Integrations.disabled(context)
                        },
                        icon = icon, iconTint = tint,
                        subtitle = if (settings.isEnglish) service.descriptionEN else service.descriptionRU,
                        tag = "integration.${service.id}",
                    )
                }
            }
        }
        // media: приложения на телефоне (Google, Яндекс, кошельки, установленные программы).
        item(key = "apps") { AppIntegrationsGroup() }
    }
}

/** «2 ч 5 мин», «12 мин», «40 сек». */
internal fun formattedTime(seconds: Double, english: Boolean): String {
    val total = seconds.toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0 -> if (english) "$hours h $minutes min" else "$hours ч $minutes мин"
        minutes > 0 -> if (english) "$minutes min" else "$minutes мин"
        else -> if (english) "$total s" else "$total сек"
    }
}

@Composable
internal fun StatisticsSettingsPage(onBack: () -> Unit) {
    val container = appContainer()
    val settings = container.settings
    val store = container.store
    val stats by store.statistics.collectAsState()
    val conversations by store.conversations.collectAsState()
    val memories by store.memories.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    fun t(ru: String, en: String) = settings.text(ru, en)
    SettingsPageScaffold(t("Статистика", "Statistics"), "settings.page.statistics", onBack) {
        item(key = "messages") {
            SettingsGroup(t("Сообщения", "Messages")) {
                SettingsValueRow(t("Отправлено", "Sent"), "${stats.sentMessages}", "stats.sent")
                SettingsDivider(16)
                SettingsValueRow(t("Получено", "Received"), "${stats.receivedMessages}", "stats.received")
                SettingsDivider(16)
                SettingsValueRow(t("Из них голосом", "Of them by voice"), "${stats.voiceMessages}", "stats.voice")
                SettingsDivider(16)
                SettingsValueRow(t("Всего сообщений", "Total messages"), "${stats.sentMessages + stats.receivedMessages}", "stats.total")
            }
        }
        item(key = "usage") {
            SettingsGroup(t("Использование", "Usage")) {
                SettingsValueRow(t("Время в приложении", "Time in app"), formattedTime(stats.totalSessionSeconds, settings.isEnglish), "stats.time")
                SettingsDivider(16)
                SettingsValueRow(t("Чатов", "Conversations"), "${conversations.size}", "stats.chats")
                SettingsDivider(16)
                SettingsValueRow(t("Записей в памяти", "Memory entries"), "${memories.size}", "stats.memory")
                SettingsDivider(16)
                SettingsValueRow(t("Первое вхождение", "First launch"), dateTimeText(settings.isEnglish, stats.firstLaunch.toEpochMilli()))
            }
        }
        item(key = "reset") {
            SettingsGroup {
                SettingsButtonRow(t("Сбросить статистику", "Reset statistics"), Icons.Outlined.RestartAlt, destructive = true, tag = "stats.reset") { confirmReset = true }
            }
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            t("Сбросить статистику?", "Reset statistics?"), null, t("Сбросить", "Reset"), t("Отмена", "Cancel"),
            onConfirm = { store.resetStatistics() }, onDismiss = { confirmReset = false },
        )
    }
}

@Composable
internal fun AboutSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = appSettings()
    val colors = HonerTheme.colors
    val scope = rememberCoroutineScope()
    val autoUpdate by settings.autoUpdate.collectAsState()
    val available by UpdateManager.available.collectAsState()
    val progress by UpdateManager.progress.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    fun t(ru: String, en: String) = settings.text(ru, en)
    val update: UpdateInfo? = available

    SettingsPageScaffold(t("О программе", "About"), "settings.page.about", onBack) {
        item(key = "title") {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Honer AI", color = colors.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(t("Ваш личный ИИ-помощник", "Your personal AI assistant"), color = colors.secondary, fontSize = 16.sp)
                    Text(appVersionText(), color = colors.secondary, fontSize = 13.sp)
                }
            }
        }
        item(key = "text") {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        t("Honer AI — ИИ-помощник. Общение, поиск с источниками, фото и видео, документы, голосовой ввод и личная память — в одном месте.",
                            "Honer AI is an AI assistant. Conversations, search with sources, photos and videos, documents, voice input and personal memory — in one place."),
                        color = colors.foreground, fontSize = 16.sp,
                    )
                    Text(
                        t("Чаты сохраняются на вашем телефоне. Оценки ответов сохраняются локально.", "Conversations are saved on your phone. Response feedback is stored locally."),
                        color = colors.secondary, fontSize = 15.sp,
                    )
                }
            }
        }
        item(key = "updates") {
            SettingsGroup(
                t("Обновления", "Updates"),
                footer = t(
                    "Новая версия скачивается в фоне и устанавливается после подтверждения. Данные и настройки сохраняются.",
                    "A new version downloads in the background and installs after confirmation. Your data and settings are kept.",
                ),
            ) {
                SettingsToggleRow(
                    t("Обновлять автоматически", "Update automatically"), autoUpdate,
                    { on -> settings.setAutoUpdate(on); UpdateScheduler.schedule(context, on) },
                    icon = Icons.Outlined.Update, tag = "about.autoUpdate",
                )
                SettingsDivider()
                SettingsButtonRow(
                    t("Проверить обновления", "Check for updates"), Icons.Outlined.SystemUpdate, enabled = !checking && !installing, tag = "about.checkUpdates",
                    trailing = if (checking) ({ CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp) }) else null,
                ) {
                    checking = true
                    updateStatus = null
                    scope.launch {
                        val found = runCatching { UpdateManager.checkNow(context) }
                        checking = false
                        updateStatus = found.fold(
                            { info -> if (info == null) t("У вас последняя версия.", "You have the latest version.") else null },
                            { t("Не удалось проверить: ", "Could not check: ") + (it.localizedMessage ?: it.toString()) },
                        )
                    }
                }
                if (update != null) {
                    SettingsDivider(16)
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("about.update"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("Доступна версия ${update.versionName}", "Version ${update.versionName} is available"), color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (update.sizeBytes > 0) Text("%.1f MB".format(update.sizeBytes / 1_048_576.0), color = colors.secondary, fontSize = 13.sp)
                        if (update.notes.isNotBlank()) Text(update.notes.take(1200), color = colors.secondary, fontSize = 14.sp)
                        progress?.let { p ->
                            LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.divider)
                            Text("${(p * 100).roundToInt()}%", color = colors.secondary, fontSize = 12.sp)
                        }
                    }
                    SettingsDivider(16)
                    SettingsButtonRow(t("Установить", "Install"), Icons.Outlined.SystemUpdate, enabled = !installing, tag = "about.install") {
                        installing = true
                        scope.launch {
                            runCatching { UpdateManager.installNow(context) }
                                .onFailure { updateStatus = t("Обновление не установилось: ", "Update failed: ") + (it.localizedMessage ?: it.toString()) }
                            installing = false
                        }
                    }
                }
                updateStatus?.let {
                    Text(it, color = colors.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("about.updateStatus"))
                }
            }
        }
        item(key = "private") {
            SettingsGroup("Honer AI") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        t("Версия для личного использования. Установочный файл предоставляет разработчик.", "A private release. The installation file is supplied by the developer."),
                        color = colors.foreground, fontSize = 16.sp,
                    )
                    Text(
                        t("Нейросеть отвечает на языке приложения; попросите в чате — и она ответит на другом языке.",
                            "The assistant answers in the app language; ask in the chat and it will answer in another language."),
                        color = colors.secondary, fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
