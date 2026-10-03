package com.honerai.admin.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.AiError
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.SectionCard
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import java.time.ZoneId

/** Ошибки ИИ (AI-400/5xx) с причиной от DeepSeek — диагностика без логов Railway. */
@Composable
fun AiErrorsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    var items by remember { mutableStateOf<List<AiError>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { container.api.aiErrors() }
            .onSuccess { items = it }
            .onFailure { error = friendlyError(it, english); items = emptyList() }
    }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Ошибки ИИ", "AI errors"), onBack = { navigator.pop() })
        val list = items
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> EmptyState(Icons.Rounded.CheckCircle, error ?: tr("Ошибок ИИ нет", "No AI errors"))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list) { e -> AiErrorCard(e, now, zone, english) }
            }
        }
    }
}

@Composable
private fun AiErrorCard(e: AiError, now: java.time.Instant, zone: ZoneId, english: Boolean) {
    val colors = HonerTheme.colors
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.status.toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.danger,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(colors.danger.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(e.model ?: "—", fontSize = 13.sp, color = colors.foreground, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                e.messages?.let { Text(tr("сообщений: ", "messages: ") + it, fontSize = 12.sp, color = colors.secondary) }
                Spacer(Modifier.weight(1f))
                Times.parse(e.at)?.let { Text(PresenceText.ago(it, now, zone, english), fontSize = 12.sp, color = colors.secondary) }
            }
            if (e.body.isNotBlank()) Text(e.body, fontSize = 12.5.sp, color = colors.secondary, lineHeight = 17.sp)
        }
    }
}
