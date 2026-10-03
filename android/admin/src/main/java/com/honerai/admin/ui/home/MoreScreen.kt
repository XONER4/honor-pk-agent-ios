package com.honerai.admin.ui.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.tr

/** Вкладка «Ещё»: разделы, которые не вынесены в основные вкладки. */
@Composable
fun MoreScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val session by container.session.session.collectAsStateWithLifecycle()
    val isDeveloper = session?.isDeveloper == true

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Ещё", "More"))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard {
                Column {
                    MoreRow(Icons.Rounded.BugReport, tr("Ошибки и падения", "Errors and crashes")) { navigator.push(Route.Reports) }
                    MoreDivider()
                    MoreRow(Icons.Rounded.Campaign, tr("Рассылка уведомлений", "Broadcast")) { navigator.push(Route.Broadcast(null)) }
                    MoreDivider()
                    MoreRow(Icons.Rounded.AutoAwesome, tr("Настройки ИИ", "AI settings")) { navigator.push(Route.Ai) }
                }
            }
            SectionCard {
                Column {
                    MoreRow(Icons.Rounded.Login, tr("Журнал входов", "Login history")) { navigator.push(Route.Logins) }
                    if (isDeveloper) {
                        MoreDivider()
                        MoreRow(Icons.Rounded.AdminPanelSettings, tr("Администраторы", "Administrators")) { navigator.push(Route.Admins) }
                    }
                }
            }
            SectionCard {
                MoreRow(Icons.Rounded.Settings, tr("Настройки", "Settings")) { navigator.push(Route.Settings) }
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 15.sp, color = colors.foreground, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondary)
    }
}

@Composable
private fun MoreDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.6.dp).background(HonerTheme.colors.divider))
}
