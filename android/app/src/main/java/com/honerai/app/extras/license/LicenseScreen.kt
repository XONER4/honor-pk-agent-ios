package com.honerai.app.extras.license

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.R
import com.honerai.app.core.AppSettings
import com.honerai.app.ui.settings.SettingsGroup
import com.honerai.app.ui.settings.SettingsPageScaffold
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.theme.HonerTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Экран соглашения при первом запуске (до знакомства) и страница в настройках для перечитывания.

/** Текст соглашения: разделы с заголовками, абзацы и пункты. */
@Composable
private fun LicenseSectionView(section: LicenseSection) {
    val colors = HonerTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(section.title, color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        section.paragraphs.forEach { Text(it, color = colors.foreground.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 21.sp) }
        section.bullets.forEach { bullet ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", color = colors.accent, fontSize = 15.sp, lineHeight = 21.sp)
                Text(bullet, color = colors.foreground.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }
}

/**
 * Первый запуск: соглашение на весь экран. «Принять и продолжить» доступна после галочки,
 * «Выйти» закрывает приложение.
 */
@Composable
fun LicenseAcceptanceScreen(settings: AppSettings, onExit: () -> Unit) {
    val colors = HonerTheme.colors
    val language by settings.language.collectAsState()
    val english = language == "en"
    var agreed by rememberSaveable { mutableStateOf(false) }
    fun t(ru: String, en: String) = if (english) en else ru
    val sections = LicenseAgreement.sections(english)

    BackHandler(onBack = onExit)
    Column(Modifier.fillMaxSize().background(colors.background).testTag("license.screen")) {
        // Шапка: логотип, заголовок, переключатель языка.
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.honer_logo), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                Text(LicenseAgreement.title(english), color = colors.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Honer AI", color = colors.secondary, fontSize = 13.sp)
            }
            TextButton(onClick = { settings.setLanguage(if (english) "ru" else "en") }, modifier = Modifier.testTag("license.language")) {
                Text(if (english) "Русский" else "English", color = colors.accent, fontSize = 14.sp)
            }
        }
        HorizontalDivider(thickness = 0.6.dp, color = colors.divider)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("license.text"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                item(key = "intro") {
                    Text(
                        t("Пожалуйста, прочитайте соглашение до конца. Оно объясняет, какие данные передаются и на каких условиях работает приложение.",
                            "Please read the whole agreement. It explains what data is sent and the terms the app works under."),
                        color = colors.secondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                itemsIndexed(sections, key = { index, _ -> "s$index" }) { _, section -> LicenseSectionView(section) }
                item(key = "end") { Spacer(Modifier.height(8.dp)) }
            }
        }
        HorizontalDivider(thickness = 0.6.dp, color = colors.divider)
        // Низ: галочка и кнопки.
        Column(
            Modifier.fillMaxWidth().background(colors.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Checkbox) { agreed = !agreed }.heightIn(min = 48.dp).testTag("license.agree"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = agreed, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.secondary, checkmarkColor = Color.White),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Text(t("Я прочитал(а) и принимаю условия соглашения", "I have read and accept the agreement"), color = colors.foreground, fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { settings.setLicenseAccepted(LicenseAgreement.CURRENT_VERSION) },
                enabled = agreed,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White,
                    disabledContainerColor = colors.raised, disabledContentColor = colors.secondary),
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 50.dp).testTag("license.accept"),
            ) { Text(t("Принять и продолжить", "Accept and continue"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onExit, modifier = Modifier.testTag("license.exit")) {
                Text(t("Выйти", "Exit"), color = colors.secondary, fontSize = 15.sp)
            }
        }
    }
}

/** Дата принятия словами: «29.09.2026, 14:05». */
internal fun acceptedDateText(millis: Long): String =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** Настройки → О программе → «Лицензионное соглашение»: перечитать текст. */
@Composable
fun LicenseSettingsPage(onBack: () -> Unit) {
    val settings = appSettings()
    val colors = HonerTheme.colors
    val english = settings.isEnglish
    val acceptedAt by settings.licenseAcceptedAt.collectAsState()
    val version by settings.licenseVersion.collectAsState()
    SettingsPageScaffold(LicenseAgreement.title(english), "settings.page.license", onBack) {
        item(key = "status") {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(settings.text("Редакция № ${LicenseAgreement.CURRENT_VERSION}", "Version ${LicenseAgreement.CURRENT_VERSION}"),
                        color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (acceptedAt > 0) settings.text("Принято ${acceptedDateText(acceptedAt)} (редакция № $version)", "Accepted on ${acceptedDateText(acceptedAt)} (version $version)")
                        else settings.text("Ещё не принято", "Not accepted yet"),
                        color = colors.secondary, fontSize = 14.sp,
                    )
                }
            }
        }
        LicenseAgreement.sections(english).forEachIndexed { index, section ->
            item(key = "section$index") {
                SettingsGroup {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { LicenseSectionView(section) }
                }
            }
        }
    }
}
