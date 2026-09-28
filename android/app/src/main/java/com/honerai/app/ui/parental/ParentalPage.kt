package com.honerai.app.ui.parental

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.device.ContentGuard
import com.honerai.app.device.ParentalControl
import com.honerai.app.device.ParentalMath
import com.honerai.app.device.ParentalRules
import com.honerai.app.ui.help.HelpTint
import com.honerai.app.ui.help.color
import com.honerai.app.ui.settings.DestructiveRed
import com.honerai.app.ui.settings.SegmentedPicker
import com.honerai.app.ui.settings.SettingsButtonRow
import com.honerai.app.ui.settings.SettingsDivider
import com.honerai.app.ui.settings.SettingsFootnote
import com.honerai.app.ui.settings.SettingsGroup
import com.honerai.app.ui.settings.SettingsMenuRow
import com.honerai.app.ui.settings.SettingsPageScaffold
import com.honerai.app.ui.settings.SettingsRow
import com.honerai.app.ui.settings.SettingsToggleRow
import com.honerai.app.ui.settings.SettingsTopBar
import com.honerai.app.ui.settings.SuccessGreen
import com.honerai.app.ui.settings.WarningOrange
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.cardBackground
import com.honerai.app.ui.settings.pageBackground
import com.honerai.app.ui.settings.rememberReduceMotion
import com.honerai.app.ui.theme.HonerTheme

// Страница «Родительский контроль» (порт ParentalControlViews.swift): вступление и создание PIN,
// ввод PIN с защитой от перебора, полная форма настроек. Сессия закрывается при уходе со страницы.

private enum class ParentalDialog { CHANGE_PIN, DISABLE, RESET }

/** Страница родительского контроля в настройках. */
@Composable
fun ParentalControlPage(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { ParentalControl.init(context) }
    val settings = appSettings()
    val snap by ParentalControl.state.collectAsState()
    val reduce = rememberReduceMotion()
    var dialog by remember { mutableStateOf<ParentalDialog?>(null) }
    // Уход со страницы закрывает сессию настроек (сворачивание приложения — тоже, см. ParentalControl).
    DisposableEffect(Unit) { onDispose { ParentalControl.lock() } }
    LaunchedEffect(Unit) { ParentalControl.refresh() }

    val phase = when {
        !snap.hasPIN -> 0
        snap.unlocked -> 2
        else -> 1
    }
    Column(Modifier.fillMaxSize().background(pageBackground)) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(if (reduce) 0 else 250)) togetherWith fadeOut(tween(if (reduce) 0 else 200)) },
            label = "parental.phase",
        ) { current ->
            when (current) {
                0 -> ParentalIntro(onBack)
                1 -> ParentalUnlock(onBack, snap.rules.enabled)
                else -> ParentalSettingsForm(onBack) { dialog = it }
            }
        }
    }
    when (dialog) {
        ParentalDialog.CHANGE_PIN -> ParentalChangePinDialog(Icons.Filled.Key, Icons.Filled.GppGood) { dialog = null }
        ParentalDialog.DISABLE -> ParentalPinPromptDialog(
            settings.text("Выключить контроль", "Turn off control"),
            settings.text("Введите PIN родителя, чтобы выключить защиту.", "Enter the parent PIN to turn protection off."),
            Icons.Outlined.PowerSettingsNew, { dialog = null },
        ) { ParentalControl.disable(it) }
        ParentalDialog.RESET -> ParentalPinPromptDialog(
            settings.text("Сбросить всё", "Reset everything"),
            settings.text("PIN и все настройки будут удалены.", "The PIN and all settings will be removed."),
            Icons.Outlined.Delete, { dialog = null },
        ) { ParentalControl.resetEverything(it) }
        null -> Unit
    }
}

// MARK: - Вступление и создание PIN

@Composable
private fun ParentalIntro(onBack: () -> Unit) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val haptics = LocalHapticFeedback.current
    var settingUp by rememberSaveable { mutableStateOf(false) }
    val reduce = rememberReduceMotion()
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(settings.text("Родительский контроль", "Parental control"), onBack, backLabel = settings.text("Назад", "Back"))
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("parental.page"), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                AnimatedContent(
                    targetState = settingUp,
                    transitionSpec = {
                        if (reduce) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                    },
                    label = "parental.setup",
                ) { setup ->
                    if (setup) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ParentalPinSetupFlow(Icons.Filled.Key, Icons.Filled.GppGood) { pin ->
                                if (ParentalControl.setPIN(pin)) {
                                    ParentalControl.update { it.copy(enabled = true) }
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            TextButton(onClick = { settingUp = false }, modifier = Modifier.testTag("parental.setup.cancel")) {
                                Text(settings.text("Отмена", "Cancel"), color = colors.accent, fontSize = 16.sp)
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            ParentalIntroCard()
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ParentalAccentGradient)
                                    .clickable(role = Role.Button) { settingUp = true }.padding(vertical = 16.dp).testTag("parental.enable"),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Security, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(settings.text("Включить родительский контроль", "Turn on parental control"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentalIntroCard() {
    val settings = appSettings()
    val colors = HonerTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(cardBackground)
            .border(0.7.dp, colors.divider, RoundedCornerShape(26.dp)).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ParentalHeroIcon(Icons.Filled.FamilyRestroom, Icons.Filled.Security)
        Text(settings.text("Родительский контроль", "Parental control"), color = colors.foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            settings.text(
                "Выключен по умолчанию и никогда не включается сам — ни по возрасту, ни по другим признакам. Включить его может только родитель, защитив настройки PIN-кодом.",
                "Off by default and never turns on by itself — not by age or anything else. Only a parent can turn it on and protect the settings with a PIN.",
            ),
            color = colors.secondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Bullet(Icons.Filled.PanTool, HelpTint.pink.color(), settings.text("Фильтр взрослых, опасных и пугающих тем", "Filters adult, dangerous and scary topics"))
            Bullet(Icons.Filled.Schedule, HelpTint.orange.color(), settings.text("Лимит времени и тихие часы", "Daily time limit and quiet hours"))
            Bullet(Icons.Filled.Public, HelpTint.blue.color(), settings.text("Ограничение сайтов, поиска и игр", "Limits sites, web search and games"))
            Bullet(Icons.Filled.Security, HelpTint.green.color(), settings.text("Ребёнок не сможет выключить контроль без PIN", "A child can't turn it off without the PIN"))
        }
    }
}

@Composable
private fun Bullet(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.honerai.app.ui.settings.IconBadge(icon, tint)
        Text(text, color = HonerTheme.colors.foreground, fontSize = 15.sp)
    }
}

// MARK: - Ввод PIN

@Composable
private fun ParentalUnlock(onBack: () -> Unit, enabled: Boolean) {
    val settings = appSettings()
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(settings.text("Родительский контроль", "Parental control"), onBack, backLabel = settings.text("Назад", "Back"))
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("parental.page"), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).padding(horizontal = 18.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StatusChip(enabled)
                ParentalPinEntryPanel(
                    settings.text("Введите PIN родителя", "Enter the parent PIN"),
                    settings.text("Настройки защищены — ребёнок не сможет их изменить.", "Settings are protected — a child can't change them."),
                    Icons.Outlined.Security,
                ) { ParentalControl.verify(it) }
            }
        }
    }
}

@Composable
private fun StatusChip(enabled: Boolean) {
    val settings = appSettings()
    val tint = if (enabled) SuccessGreen else HonerTheme.colors.secondary
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (enabled) Icons.Outlined.Verified else Icons.Outlined.GppBad, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(if (enabled) settings.text("Контроль включён", "Control is on") else settings.text("Контроль выключен", "Control is off"), color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// MARK: - Форма настроек

private data class ToggleSpec(
    val field: String, val icon: ImageVector, val tint: HelpTint,
    val ruTitle: String, val enTitle: String, val ruHint: String, val enHint: String,
    val get: (ParentalRules) -> Boolean, val set: (ParentalRules, Boolean) -> ParentalRules,
)

private val filterSpecs = listOf(
    ToggleSpec("blockAdult", Icons.Filled.VisibilityOff, HelpTint.pink, "Взрослый контент", "Adult content", "Секс, эротика, откровенные сцены", "Sex, erotica, explicit scenes", { it.blockAdult }, { r, v -> r.copy(blockAdult = v) }),
    ToggleSpec("blockViolence", Icons.Filled.GppMaybe, HelpTint.orange, "Насилие и оружие", "Violence and weapons", "Жестокость, оружие, взрывчатка", "Cruelty, weapons, explosives", { it.blockViolence }, { r, v -> r.copy(blockViolence = v) }),
    ToggleSpec("blockDrugs", Icons.Filled.Medication, HelpTint.teal, "Наркотики, алкоголь, табак", "Drugs, alcohol, tobacco", "Включая вейпы и электронные сигареты", "Including vapes and e-cigarettes", { it.blockDrugs }, { r, v -> r.copy(blockDrugs = v) }),
    ToggleSpec("blockGambling", Icons.Filled.Casino, HelpTint.green, "Азартные игры", "Gambling", "Казино, ставки, букмекеры, автоматы", "Casinos, betting, slot machines", { it.blockGambling }, { r, v -> r.copy(blockGambling = v) }),
    ToggleSpec("blockProfanity", Icons.Filled.Report, HelpTint.purple, "Мат и грубость", "Profanity", "Бранные слова скрываются точками", "Swear words are hidden with dots", { it.blockProfanity }, { r, v -> r.copy(blockProfanity = v) }),
    ToggleSpec("blockSelfHarm", Icons.Filled.Favorite, HelpTint.red, "Самоповреждение", "Self-harm", "Бережный ответ и телефон доверия вместо опасных советов", "Caring reply and a helpline instead of harmful advice", { it.blockSelfHarm }, { r, v -> r.copy(blockSelfHarm = v) }),
    ToggleSpec("blockHate", Icons.Filled.ThumbDown, HelpTint.brown, "Ненависть и травля", "Hate and bullying", "Оскорбления, дискриминация, буллинг", "Insults, discrimination, bullying", { it.blockHate }, { r, v -> r.copy(blockHate = v) }),
    ToggleSpec("blockScaryContent", Icons.Filled.NightsStay, HelpTint.indigo, "Страшилки", "Scary content", "Хорроры и жуткие истории", "Horror and creepy stories", { it.blockScaryContent }, { r, v -> r.copy(blockScaryContent = v) }),
    ToggleSpec("blockDating", Icons.Filled.HeartBroken, HelpTint.pink, "Знакомства и флирт", "Dating and flirting", "Романтические ролевые игры, сайты знакомств", "Romantic roleplay, dating apps", { it.blockDating }, { r, v -> r.copy(blockDating = v) }),
    ToggleSpec("blockPersonalDataSharing", Icons.Filled.PersonOff, HelpTint.blue, "Личные данные", "Personal data", "Не спрашивать адрес, телефон и школу; предупреждать ребёнка", "Never ask for address, phone or school; warn the child", { it.blockPersonalDataSharing }, { r, v -> r.copy(blockPersonalDataSharing = v) }),
)

private val featureSpecs = listOf(
    ToggleSpec("allowWebSearch", Icons.Filled.Search, HelpTint.blue, "Поиск в интернете", "Web search", "Honer AI может искать в сети", "Honer AI can search the web", { it.allowWebSearch }, { r, v -> r.copy(allowWebSearch = v) }),
    ToggleSpec("allowOpenLinks", Icons.Filled.Link, HelpTint.teal, "Открывать ссылки", "Open links", "Переходить на сайты из ответов", "Open websites from answers", { it.allowOpenLinks }, { r, v -> r.copy(allowOpenLinks = v) }),
    ToggleSpec("allowImageGeneration", Icons.Filled.Brush, HelpTint.orange, "Рисование", "Drawing", "Создание картинок", "Image generation", { it.allowImageGeneration }, { r, v -> r.copy(allowImageGeneration = v) }),
    ToggleSpec("allowGames", Icons.Filled.SportsEsports, HelpTint.green, "Игры", "Games", "Шахматы, шашки и другие мини-игры", "Chess, checkers and other mini games", { it.allowGames }, { r, v -> r.copy(allowGames = v) }),
    ToggleSpec("allowVoiceCloning", Icons.Filled.GraphicEq, HelpTint.purple, "Клонирование голоса", "Voice cloning", "Запись и отправка образца голоса", "Recording and uploading a voice sample", { it.allowVoiceCloning }, { r, v -> r.copy(allowVoiceCloning = v) }),
    ToggleSpec("allowContacts", Icons.Filled.AccountCircle, HelpTint.gray, "Контакты", "Contacts", "Доступ к телефонной книге", "Access to the address book", { it.allowContacts }, { r, v -> r.copy(allowContacts = v) }),
    ToggleSpec("allowLocation", Icons.Filled.LocationOn, HelpTint.blue, "Геолокация", "Location", "Погода и места рядом", "Weather and nearby places", { it.allowLocation }, { r, v -> r.copy(allowLocation = v) }),
)

private val limitOptions = listOf(0, 15, 30, 45, 60, 90, 120, 180)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentalSettingsForm(onBack: () -> Unit, openDialog: (ParentalDialog) -> Unit) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val snap by ParentalControl.state.collectAsState()
    val rules = snap.rules
    val english = settings.isEnglish
    fun t(ru: String, en: String) = settings.text(ru, en)
    fun change(block: (ParentalRules) -> ParentalRules) { ParentalControl.update(block) }
    var timePicker by remember { mutableStateOf<Boolean?>(null) } // true — начало, false — конец

    SettingsPageScaffold(t("Родительский контроль", "Parental control"), "parental.page", onBack) {
        item(key = "master") {
            SettingsGroup(footer = t("Контроль никогда не включается автоматически. Выключить его можно только с PIN-кодом.", "Control never turns on automatically. It can only be turned off with the PIN.")) {
                StatusHeader(rules.enabled)
                SettingsDivider(16)
                SettingsToggleRow(
                    t("Родительский контроль", "Parental control"), rules.enabled,
                    { on -> if (on) change { it.copy(enabled = true) } else openDialog(ParentalDialog.DISABLE) },
                    icon = Icons.Outlined.Security, tag = "parental.toggle.enabled",
                )
            }
        }
        item(key = "today") {
            SettingsGroup(title = t("Сегодня", "Today")) {
                val used = snap.minutesUsedToday
                val limit = rules.dailyLimitMinutes
                SettingsRow(
                    Icons.Filled.Schedule, t("Сегодня в Honer AI", "Today in Honer AI"),
                    value = if (limit > 0) t("$used из $limit мин", "$used of $limit min") else t("$used мин", "$used min"), chevron = false,
                )
                if (limit > 0) {
                    val progress = (used.toFloat() / limit).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp).testTag("parental.today.progress"),
                        color = if (progress >= 1f) DestructiveRed else colors.accent, trackColor = colors.divider,
                    )
                }
                ParentalControl.blockReasonText(english)?.let {
                    Text(it, color = WarningOrange, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
                }
            }
        }
        item(key = "filters") {
            SettingsGroup(title = t("Фильтры контента", "Content filters")) {
                filterSpecs.forEachIndexed { i, spec ->
                    if (i > 0) SettingsDivider(58)
                    SpecToggle(spec, rules, ::change)
                }
            }
        }
        item(key = "features") {
            SettingsGroup(title = t("Возможности", "Features")) {
                featureSpecs.forEachIndexed { i, spec ->
                    if (i > 0) SettingsDivider(58)
                    SpecToggle(spec, rules, ::change)
                }
            }
        }
        if (rules.allowGames) {
            item(key = "games") {
                SettingsGroup(
                    title = t("Разрешённые игры", "Allowed games"),
                    footer = t("«Удача» — игровой автомат; он всегда недоступен, пока включён фильтр азартных игр.", "\"Luck\" is a slot machine; it stays unavailable while the gambling filter is on."),
                ) {
                    listOf(
                        Triple("chess", Icons.Filled.EmojiEvents, t("Шахматы", "Chess")),
                        Triple("checkers", Icons.Filled.Apps, t("Шашки", "Checkers")),
                        Triple("durak", Icons.Filled.Style, t("Дурак", "Durak")),
                        Triple("slots", Icons.Filled.Casino, t("Удача (автомат)", "Luck (slots)")),
                    ).forEachIndexed { i, (raw, icon, title) ->
                        if (i > 0) SettingsDivider()
                        SettingsToggleRow(
                            title, ContentGuard.isGameAllowed(raw, rules),
                            { on -> change { r -> r.copy(allowedGames = if (on) (if (raw in r.allowedGames) r.allowedGames else r.allowedGames + raw) else r.allowedGames - raw) } },
                            icon = icon, enabled = !(raw == "slots" && rules.blockGambling), tag = "parental.toggle.allowedGames.$raw",
                        )
                    }
                }
            }
        }
        item(key = "sites") {
            SettingsGroup(
                title = t("Сайты", "Sites"),
                footer = t(
                    "Заблокированные сайты не откроются и не попадут в поиск. Поддомены тоже блокируются. Сайты для взрослых и казино блокируются всегда.",
                    "Blocked sites won't open or appear in search. Subdomains are blocked too. Adult and casino sites are always blocked.",
                ),
            ) {
                rules.blockedSites.forEach { site ->
                    ListEntry(Icons.Outlined.Block, site) { change { r -> r.copy(blockedSites = r.blockedSites - site) } }
                    SettingsDivider()
                }
                AddField("tiktok.com", "parental.site.add", KeyboardType.Uri) { text ->
                    val domain = ContentGuard.normalizeDomain(text)
                    if (domain.isNotEmpty()) change { r -> if (domain in r.blockedSites) r else r.copy(blockedSites = r.blockedSites + domain) }
                }
            }
        }
        item(key = "allowed") {
            SettingsGroup(footer = t("В этом режиме открываются только сайты из списка, например wikipedia.org.", "In this mode only the listed sites open, e.g. wikipedia.org.")) {
                SettingsToggleRow(
                    t("Только разрешённые сайты", "Allowed sites only"), rules.allowedSitesOnly,
                    { on -> change { it.copy(allowedSitesOnly = on) } }, icon = Icons.Outlined.Verified, tag = "parental.toggle.allowedSitesOnly",
                )
                if (rules.allowedSitesOnly) {
                    rules.allowedSites.forEach { site ->
                        SettingsDivider()
                        ListEntry(Icons.Outlined.CheckCircle, site) { change { r -> r.copy(allowedSites = r.allowedSites - site) } }
                    }
                    SettingsDivider()
                    AddField("wikipedia.org", "parental.allowedSite.add", KeyboardType.Uri) { text ->
                        val domain = ContentGuard.normalizeDomain(text)
                        if (domain.isNotEmpty()) change { r -> if (domain in r.allowedSites) r else r.copy(allowedSites = r.allowedSites + domain) }
                    }
                }
            }
        }
        item(key = "words") {
            SettingsGroup(
                title = t("Запрещённые слова", "Blocked words"),
                footer = t(
                    "Сообщения с этими словами не отправятся, а в ответах слова скрываются точками. Окончания учитываются.",
                    "Messages with these words won't be sent, and they are hidden with dots in answers. Word endings are included.",
                ),
            ) {
                if (rules.blockedWords.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rules.blockedWords.forEach { word ->
                            Row(
                                Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(word, color = colors.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Icon(
                                    Icons.Filled.Cancel, contentDescription = t("Удалить", "Remove"), tint = colors.secondary,
                                    modifier = Modifier.size(18.dp).clip(CircleShape)
                                        .clickable { change { r -> r.copy(blockedWords = r.blockedWords - word) } }
                                        .testTag("parental.blockedWord.remove.$word"),
                                )
                            }
                        }
                    }
                    SettingsDivider(16)
                }
                AddField(t("Новое слово", "New word"), "parental.blockedWord.add", KeyboardType.Text) { text ->
                    val word = text.trim()
                    if (word.isNotEmpty()) change { r -> if (r.blockedWords.any { it.equals(word, ignoreCase = true) }) r else r.copy(blockedWords = r.blockedWords + word) }
                }
            }
        }
        item(key = "time") {
            SettingsGroup(
                title = t("Время", "Time"),
                footer = t(
                    "Когда время закончилось или идут тихие часы, чат закрывается. Родитель может открыть его до конца дня по PIN-коду.",
                    "When time is up or during quiet hours the chat is locked. A parent can unlock it for the rest of the day with the PIN.",
                ),
            ) {
                SettingsMenuRow(
                    Icons.Outlined.HourglassEmpty, t("Лимит в день", "Daily limit"),
                    limitOptions.map { it to limitTitle(it, settings::text) }, rules.dailyLimitMinutes,
                    { minutes -> change { it.copy(dailyLimitMinutes = minutes) } }, tag = "parental.limit",
                )
                SettingsDivider()
                SettingsToggleRow(
                    t("Тихие часы", "Quiet hours"), rules.quietHoursEnabled, { on -> change { it.copy(quietHoursEnabled = on) } },
                    icon = Icons.Outlined.Bedtime, tag = "parental.toggle.quietHoursEnabled",
                )
                if (rules.quietHoursEnabled) {
                    SettingsDivider()
                    SettingsRow(null, t("Начало", "Start"), ParentalMath.clockString(rules.quietStart), tag = "parental.quietStart", chevron = false) { timePicker = true }
                    SettingsDivider(16)
                    SettingsRow(null, t("Конец", "End"), ParentalMath.clockString(rules.quietEnd), tag = "parental.quietEnd", chevron = false) { timePicker = false }
                }
            }
        }
        item(key = "answers") {
            SettingsGroup(
                title = t("Ответы для ребёнка", "Answers for the child"),
                footer = t("Возраст нужен только чтобы подобрать понятные слова. Он никогда не включает контроль сам.", "Age is only used to choose clear wording. It never turns control on by itself."),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp).testTag("parental.childAge"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.ChildCare, null, tint = colors.foreground, modifier = Modifier.size(23.dp))
                    Text(t("Возраст: ${rules.childAge}", "Age: ${rules.childAge}"), color = colors.foreground, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Stepper(rules.childAge > 6, rules.childAge < 17, { change { it.copy(childAge = it.childAge - 1) } }, { change { it.copy(childAge = it.childAge + 1) } })
                }
                SettingsDivider(16)
                Text(t("Стиль ответов", "Answer style"), color = colors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                SegmentedPicker(
                    listOf("simple" to t("Проще", "Simpler"), "normal" to t("Обычно", "Normal")), rules.answerStyle,
                    { style -> change { it.copy(answerStyle = style) } }, tag = "parental.answerStyle",
                )
            }
        }
        item(key = "pin") {
            SettingsGroup(
                title = "PIN",
                footer = t("Настройки закрываются сами через 5 минут без действий и при выходе со страницы.", "Settings lock automatically after 5 minutes of inactivity and when you leave this page."),
            ) {
                SettingsButtonRow(t("Сменить PIN", "Change PIN"), Icons.Outlined.Key, tag = "parental.pin.change") { openDialog(ParentalDialog.CHANGE_PIN) }
                SettingsDivider()
                SettingsButtonRow(t("Закрыть настройки", "Lock settings"), Icons.Outlined.Lock, tag = "parental.lock") { ParentalControl.lock() }
                if (rules.enabled) {
                    SettingsDivider()
                    SettingsButtonRow(t("Выключить родительский контроль", "Turn off parental control"), Icons.Outlined.PowerSettingsNew, destructive = true, tag = "parental.disable") {
                        openDialog(ParentalDialog.DISABLE)
                    }
                }
                SettingsDivider()
                SettingsButtonRow(t("Удалить PIN и сбросить настройки", "Delete PIN and reset settings"), Icons.Outlined.Delete, destructive = true, tag = "parental.reset") {
                    openDialog(ParentalDialog.RESET)
                }
            }
        }
    }
    timePicker?.let { isStart ->
        QuietTimeDialog(
            initial = if (isStart) rules.quietStart else rules.quietEnd,
            title = if (isStart) t("Начало", "Start") else t("Конец", "End"),
            confirm = t("Готово", "Done"), cancel = t("Отмена", "Cancel"),
            onDismiss = { timePicker = null },
        ) { minutes -> change { if (isStart) it.copy(quietStart = minutes) else it.copy(quietEnd = minutes) } }
    }
}

private fun limitTitle(minutes: Int, text: (String, String) -> String): String = when {
    minutes == 0 -> text("Нет", "None")
    minutes < 60 || minutes % 60 != 0 -> text("$minutes мин", "$minutes min")
    else -> text("${minutes / 60} ч", "${minutes / 60} h")
}

@Composable
private fun StatusHeader(enabled: Boolean) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val reduce = rememberReduceMotion()
    val scale by animateFloatAsState(if (enabled) 1f else 0.94f, if (reduce) tween(0) else spring(0.75f, 400f), label = "status")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size((52 * scale).dp).clip(CircleShape).background(if (enabled) ParentalAccentGradient else ParentalMutedGradient),
            contentAlignment = Alignment.Center,
        ) { Icon(if (enabled) Icons.Filled.GppGood else Icons.Outlined.GppBad, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (enabled) settings.text("Защита включена", "Protection is on") else settings.text("Защита выключена", "Protection is off"), color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(settings.text("Настройки защищены PIN-кодом", "Settings are protected by a PIN"), color = colors.secondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SpecToggle(spec: ToggleSpec, rules: ParentalRules, change: ((ParentalRules) -> ParentalRules) -> Unit) {
    val settings = appSettings()
    SettingsToggleRow(
        settings.text(spec.ruTitle, spec.enTitle), spec.get(rules), { on -> change { spec.set(it, on) } },
        icon = spec.icon, iconTint = spec.tint.color(), subtitle = settings.text(spec.ruHint, spec.enHint),
        tag = "parental.toggle.${spec.field}",
    )
}

@Composable
private fun ListEntry(icon: ImageVector, text: String, onRemove: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = colors.foreground, modifier = Modifier.size(22.dp))
        Text(text, color = colors.foreground, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(
            Icons.Outlined.Delete, contentDescription = appSettings().text("Удалить", "Delete"), tint = DestructiveRed,
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onRemove).padding(9.dp),
        )
    }
}

/** Поле добавления с кнопкой «+» (сайт или слово). */
@Composable
private fun AddField(placeholder: String, tag: String, keyboard: KeyboardType, onAdd: (String) -> Unit) {
    val colors = HonerTheme.colors
    var text by remember { mutableStateOf("") }
    val empty = text.isBlank()
    fun submit() { if (!empty) { onAdd(text); text = "" } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(placeholder, color = colors.secondary, fontSize = 16.sp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = colors.foreground, fontSize = 16.sp), cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("$tag.field"),
            )
        }
        Icon(
            Icons.Filled.AddCircle, contentDescription = "+", tint = if (empty) colors.secondary else colors.accent,
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !empty) { submit() }.padding(7.dp).testTag(tag),
        )
    }
}

@Composable
private fun Stepper(canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val colors = HonerTheme.colors
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(colors.foreground.copy(alpha = 0.08f)), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Remove, "−", tint = if (canDecrease) colors.foreground else colors.secondary.copy(alpha = 0.5f),
            modifier = Modifier.clickable(enabled = canDecrease, onClick = onDecrease).padding(horizontal = 14.dp, vertical = 6.dp).size(20.dp),
        )
        Box(Modifier.size(width = 0.7.dp, height = 18.dp).background(colors.divider))
        Icon(
            Icons.Outlined.Add, "+", tint = if (canIncrease) colors.foreground else colors.secondary.copy(alpha = 0.5f),
            modifier = Modifier.clickable(enabled = canIncrease, onClick = onIncrease).padding(horizontal = 14.dp, vertical = 6.dp).size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(initial: Int, title: String, confirm: String, cancel: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val colors = HonerTheme.colors
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBackground,
        title = { Text(title, color = colors.foreground) },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = colors.surface, selectorColor = colors.accent, timeSelectorSelectedContainerColor = colors.accent.copy(alpha = 0.25f),
                    timeSelectorUnselectedContainerColor = colors.surface, timeSelectorSelectedContentColor = colors.foreground,
                    timeSelectorUnselectedContentColor = colors.foreground, clockDialUnselectedContentColor = colors.foreground,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute); onDismiss() }) { Text(confirm, color = colors.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel, color = colors.accent) } },
    )
}
