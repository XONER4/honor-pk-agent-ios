package com.honerai.admin.ui.insights

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.data.AdminAccount
import com.honerai.admin.data.Roles
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.common.ConfirmDialog
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.common.rememberToastState
import com.honerai.admin.ui.common.ToastHost
import com.honerai.admin.ui.settings.RoleBadge
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Список администраторов: роль, присутствие, последний вход. Разработчик может менять роль (кроме себя). */
@Composable
fun AdminsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val now = rememberNow()
    val zone = remember { ZoneId.systemDefault() }
    val session by container.session.session.collectAsStateWithLifecycle()
    val isDeveloper = session?.isDeveloper == true
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val items = remember { mutableStateListOf<AdminAccount>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var roleDialog by remember { mutableStateOf<AdminAccount?>(null) }

    LaunchedEffect(reload) {
        loading = true; error = null
        try {
            val list = container.api.admins()
            items.clear(); items.addAll(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e, english)
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(tr("Администраторы", "Administrators"), onBack = { navigator.pop() },
                actions = { IconCircle(Icons.Rounded.Refresh, tr("Обновить", "Refresh"), { reload++ }) })
            when {
                loading && items.isEmpty() -> LoadingBox()
                error != null && items.isEmpty() -> ErrorPanel(error.orEmpty(), { reload++ }, Modifier.padding(top = 40.dp))
                items.isEmpty() -> EmptyState(Icons.Rounded.Group, tr("Нет администраторов", "No administrators"))
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    items(items, key = { it.adminId }) { a ->
                        val isSelf = a.email.isNotBlank() && a.email == session?.email
                        AdminRow(a, now, zone, english, canEdit = isDeveloper && !isSelf) {
                            roleDialog = a
                        }
                        Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(colors.divider))
                    }
                }
            }
        }
        ToastHost(toast, bottom = 40.dp)
    }

    roleDialog?.let { a ->
        val makeDeveloper = a.role != Roles.DEVELOPER
        ConfirmDialog(
            title = if (makeDeveloper) tr("Сделать разработчиком?", "Make developer?") else tr("Сделать администратором?", "Make administrator?"),
            text = (a.name.ifBlank { a.login ?: a.email }) + "\n" +
                (if (makeDeveloper) tr("Разработчик видит и может всё.", "A developer can see and do everything.")
                else tr("Обычные права администратора.", "Regular administrator rights.")),
            confirm = tr("Применить", "Apply"),
            onDismiss = { roleDialog = null },
            onConfirm = {
                roleDialog = null
                scope.launch {
                    try {
                        container.api.setAdminRole(a.adminId, if (makeDeveloper) Roles.DEVELOPER else Roles.ADMIN)
                        toast.show(tr("Роль изменена", "Role changed"))
                        reload++
                    } catch (e: Exception) {
                        toast.show(friendlyError(e, english))
                    }
                }
            },
        )
    }
}

@Composable
private fun AdminRow(a: AdminAccount, now: java.time.Instant, zone: ZoneId, english: Boolean, canEdit: Boolean, onEditRole: () -> Unit) {
    val colors = HonerTheme.colors
    val online = a.presence != null && a.presence != "offline"
    Row(
        Modifier.fillMaxWidth().let { if (canEdit) it.clickable(onClick = onEditRole) else it }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (online) colors.online else colors.secondary))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.name.ifBlank { a.login ?: a.email }.ifBlank { tr("Администратор", "Admin") },
                    fontSize = 15.sp, color = colors.foreground, fontWeight = FontWeight.Medium)
                RoleBadge(a.role)
            }
            val meta = listOfNotNull(
                a.login?.takeIf { it.isNotBlank() },
                Times.parse(a.lastLoginAt)?.let { tr("вход ", "login ") + PresenceText.ago(it, now, zone, english) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = colors.secondary)
        }
        if (canEdit) Text(tr("роль", "role"), fontSize = 13.sp, color = colors.accent)
    }
}
