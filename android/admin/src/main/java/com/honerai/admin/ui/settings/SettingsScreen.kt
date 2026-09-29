package com.honerai.admin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.BuildConfig
import com.honerai.admin.net.ConnectionState
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.SecondaryButton
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.tr

/** Настройки: аккаунт, язык, сервер, выход. */
@Composable
fun SettingsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val session by container.session.session.collectAsStateWithLifecycle()
    val english by container.settings.english.collectAsStateWithLifecycle()
    val serverUrl by container.session.serverUrl.collectAsStateWithLifecycle()
    val connection by container.realtime.state.collectAsStateWithLifecycle()
    var editServer by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(serverUrl) }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Настройки", "Settings"), onBack = { navigator.pop() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(session?.name?.ifBlank { null } ?: tr("Администратор", "Administrator"), fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, color = colors.foreground)
                        session?.email?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = colors.secondary) }
                    }
                }
                Label(tr("Язык интерфейса", "Language"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HonerPill("Русский", !english, { container.settings.setEnglish(false) })
                    HonerPill("English", english, { container.settings.setEnglish(true) })
                }
                Label(tr("Сервер", "Server"))
                SectionCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(serverUrl.ifEmpty { "—" }, color = colors.foreground, fontSize = 15.sp)
                        Text(
                            when (connection) {
                                ConnectionState.CONNECTED -> tr("Соединение установлено", "Connected")
                                ConnectionState.CONNECTING -> tr("Соединение…", "Connecting…")
                                ConnectionState.OFFLINE -> tr("Нет соединения", "Offline")
                            },
                            color = if (connection == ConnectionState.CONNECTED) colors.online else colors.secondary, fontSize = 13.sp,
                        )
                        if (!editServer) {
                            Text(tr("Изменить адрес", "Change address"), color = colors.accent, fontSize = 14.sp,
                                modifier = Modifier.heightIn(min = 36.dp).clickable { url = serverUrl; editServer = true }.padding(top = 8.dp))
                        } else {
                            Spacer(Modifier.height(4.dp))
                            HonerField(url, { url = it }, tr("Адрес сервера", "Server address"),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri))
                            Text(tr("После смены адреса нужно войти заново.", "You'll need to sign in again after changing it."),
                                fontSize = 12.sp, color = colors.secondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(tr("Сохранить", "Save"), {
                                    if (url.isNotBlank()) {
                                        container.session.setServerUrl(url)
                                        container.session.logout()
                                    }
                                })
                                SecondaryButton(tr("Отмена", "Cancel"), { editServer = false })
                            }
                        }
                    }
                }
                SecondaryButton(tr("Выйти", "Sign out"), { confirmLogout = true }, Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Rounded.Logout, tint = colors.danger)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Honer Admin ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = colors.secondary)
                }
            }
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = tr("Выйти из админки?", "Sign out?"),
            text = "",
            confirm = tr("Выйти", "Sign out"),
            destructive = true,
            onDismiss = { confirmLogout = false },
            onConfirm = { confirmLogout = false; container.session.logout() },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 14.sp, color = HonerTheme.colors.secondary, modifier = Modifier.padding(start = 4.dp, bottom = 0.dp))
}
