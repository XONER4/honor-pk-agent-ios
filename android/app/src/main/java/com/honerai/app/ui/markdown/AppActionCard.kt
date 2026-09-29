package com.honerai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.AppLaunch
import com.honerai.app.core.AppLauncher
import com.honerai.app.core.AppRequest
import com.honerai.app.core.LaunchKind
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// media: блок ```app в ответе — кнопка «Открыть <приложение>». Приложение открывается
// только по нажатию пользователя; платёжные приложения лишь запускаются, без оплаты.

@Composable
internal fun AppActionCard(body: String, fontSize: Float, english: Boolean) {
    val request = remember(body) { AppRequest.parse(body) }
    val launch = remember(request) { request?.let { AppLauncher.launchFor(it) } }
    if (request == null || launch == null) {
        // Не распознали — показываем как обычный текст, ничего не открываем.
        Text(body, fontSize = (fontSize * 0.85f).sp, color = HonerTheme.colors.secondary)
        return
    }
    val context = LocalContext.current
    val colors = HonerTheme.colors
    var installed by remember(launch) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(launch) {
        installed = withContext(Dispatchers.IO) { runCatching { AppLauncher.canOpenApp(context, launch) }.getOrDefault(false) }
    }
    val title = if (installed == false && launch.webFallback != null) {
        if (launch.kind == LaunchKind.PACKAGE && launch.webFallback.contains("play.google.com")) tr(english, "Установить ", "Install ") + launch.label
        else tr(english, "Открыть на сайте: ", "Open website: ") + launch.label
    } else tr(english, "Открыть ", "Open ") + launch.label
    val detail = listOf(request.query, request.to, request.title).firstOrNull { it.isNotBlank() }.orEmpty()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(colors.surface)
            .border(0.7.dp, colors.divider, shape)
            .clickable { AppLauncher.open(context, launch) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("message.app." + launch.appId)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint(launch)), contentAlignment = Alignment.Center) {
            Icon(icon(launch), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = (fontSize * 0.9f).sp, fontWeight = FontWeight.SemiBold, color = colors.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (launch.payment) tr(english, "Только открыть — платежи вы делаете сами", "Open only — you make payments yourself")
            else detail
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = (fontSize * 0.75f).sp, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
    }
}

private fun icon(launch: AppLaunch): ImageVector = when {
    launch.payment -> Icons.Rounded.AccountBalanceWallet
    launch.appId.endsWith("maps") -> Icons.Rounded.Map
    launch.appId == "yandex_navigator" -> Icons.Rounded.Navigation
    launch.appId == "yandex_taxi" -> Icons.Rounded.LocalTaxi
    launch.appId == "yandex_music" -> Icons.Rounded.MusicNote
    launch.appId == "yandex_weather" -> Icons.Rounded.Cloud
    launch.appId == "gmail" -> Icons.Rounded.Mail
    launch.appId == "google_calendar" -> Icons.Rounded.CalendarMonth
    launch.appId == "youtube" -> Icons.Rounded.SmartDisplay
    else -> Icons.Rounded.Apps
}

private fun tint(launch: AppLaunch): Color = when {
    launch.payment -> Color(0xFF34A853)
    launch.appId.startsWith("yandex") -> Color(0xFFFC3F1D)
    launch.appId == "youtube" -> Color(0xFFFF0000)
    launch.appId.startsWith("google") || launch.appId == "gmail" -> Color(0xFF4285F4)
    else -> Color(0xFF8E8E93)
}
