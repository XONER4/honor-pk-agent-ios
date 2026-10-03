package com.honerai.admin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.StaffMessage
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.HonerField
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

private val developerPurple = Color(0xFF8B5CF6)

/** Общий чат команды (админы + разработчик). Закреплён вверху «Поддержки». План админка п.9. */
@Composable
fun StaffChatScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf<List<StaffMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // Загрузка + опрос новых сообщений и отметка прочтения.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { container.api.staffMessages(limit = 100) }.onSuccess { fresh ->
                if (fresh.isNotEmpty()) messages = fresh
                runCatching { container.api.staffRead() }
            }
            delay(3000)
        }
    }
    // Автопрокрутка вниз при новом сообщении.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        draft = ""
        scope.launch {
            try {
                val sent = container.api.sendStaff(text)
                if (messages.none { it.id == sent.id }) messages = messages + sent
            } catch (e: Exception) {
                draft = text
            } finally {
                sending = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        TopBar(tr("Чат команды", "Team chat"), subtitle = tr("Админы и разработчик", "Admins and developer"),
            onBack = { navigator.pop() })
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(messages, key = { it.id }) { m -> StaffBubble(m, now, zone, english) }
        }
        Row(
            Modifier.fillMaxWidth().background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) {
                HonerField(draft, { draft = it }, tr("Сообщение команде…", "Message to the team…"),
                    singleLine = false, minLines = 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(if (draft.isBlank()) colors.raised else colors.accent)
                    .clickable(enabled = draft.isNotBlank() && !sending) { send() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, tr("Отправить", "Send"),
                    tint = if (draft.isBlank()) colors.secondary else colors.background, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StaffBubble(m: StaffMessage, now: java.time.Instant, zone: ZoneId, english: Boolean) {
    val colors = HonerTheme.colors
    val mine = m.mine
    val nameColor = if (m.adminRole == "developer") developerPurple else colors.danger
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (mine) colors.outgoing else colors.raised)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.adminName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nameColor)
                    if (m.adminRole == "developer") {
                        Spacer(Modifier.size(6.dp))
                        Text(tr("разработчик", "developer"), fontSize = 10.sp, color = developerPurple)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(m.text, fontSize = 16.sp, color = colors.foreground)
            Text(Times.parse(m.createdAt)?.let { PresenceText.clockOf(it, zone) }.orEmpty(),
                fontSize = 11.sp, color = colors.secondary, modifier = Modifier.padding(top = 2.dp).align(Alignment.End))
        }
    }
}
