package com.honerai.admin.ui.insights

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.AdminLogin
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/** Журнал входов и попыток входа в админку: кто, когда, успех/неудача, способ. */
@Composable
fun LoginsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    var filter by rememberSaveable { mutableStateOf<Boolean?>(null) } // null=все, true=успешные, false=неудачные
    val items = remember { mutableStateListOf<AdminLogin>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(filter, reload) {
        loading = true; error = null
        try {
            val page = container.api.logins(limit = 100, success = filter)
            items.clear(); items.addAll(page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e, english)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Журнал входов", "Login history"), onBack = { navigator.pop() },
            actions = { IconCircle(Icons.Rounded.Refresh, tr("Обновить", "Refresh"), { reload++ }) })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HonerPill(tr("Все", "All"), filter == null, { filter = null })
            HonerPill(tr("Успешные", "Successful"), filter == true, { filter = true })
            HonerPill(tr("Неудачные", "Failed"), filter == false, { filter = false })
        }
        when {
            loading && items.isEmpty() -> LoadingBox()
            error != null && items.isEmpty() -> ErrorPanel(error.orEmpty(), { reload++ }, Modifier.padding(top = 40.dp))
            items.isEmpty() -> EmptyState(Icons.Rounded.Login, tr("Входов пока нет", "No logins yet"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                items(items, key = { it.id }) { l ->
                    LoginRow(l, now, zone, english)
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(colors.divider))
                }
            }
        }
    }
}

@Composable
private fun LoginRow(l: AdminLogin, now: java.time.Instant, zone: ZoneId, english: Boolean) {
    val colors = HonerTheme.colors
    val who = l.adminName?.takeIf { it.isNotBlank() } ?: l.loginTried?.takeIf { it.isNotBlank() } ?: tr("Неизвестно", "Unknown")
    val method = when (l.method) {
        "password" -> tr("пароль", "password")
        "code" -> tr("код", "code")
        "token" -> tr("токен", "token")
        else -> l.method.orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (l.success) Icons.Rounded.CheckCircle else Icons.Rounded.Login,
                null, tint = if (l.success) colors.online else colors.danger, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(who, fontSize = 15.sp, color = colors.foreground, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (l.success) tr("вход", "signed in") else tr("ошибка", "failed"),
                fontSize = 13.sp, color = if (l.success) colors.online else colors.danger,
            )
        }
        val meta = listOfNotNull(
            Times.parse(l.at)?.let { PresenceText.ago(it, now, zone, english) },
            method.takeIf { it.isNotBlank() },
            l.ip?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(meta, fontSize = 12.sp, color = colors.secondary, modifier = Modifier.padding(start = 26.dp))
        }
    }
}
