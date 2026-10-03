package com.honerai.admin.ui.home

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honerai.admin.AdminContainer
import com.honerai.admin.core.PresenceText
import com.honerai.admin.core.Times
import com.honerai.admin.core.UserFilter
import com.honerai.admin.core.UserList
import com.honerai.admin.core.UserSort
import com.honerai.admin.core.Numbers
import com.honerai.admin.data.idLabel
import com.honerai.admin.ui.common.ToastHost
import com.honerai.admin.ui.common.rememberToastState
import com.honerai.admin.data.DeviceSummary
import com.honerai.admin.data.Overview
import com.honerai.admin.data.Presence
import com.honerai.admin.data.title
import com.honerai.admin.net.ConnectionState
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.Avatar
import com.honerai.admin.ui.common.ConnectionBanner
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.presenceColor
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Главный экран: счётчики (живые) и список пользователей с поиском и фильтрами. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(container: AdminContainer, navigator: Navigator) {
    val repo = container.repo
    val users by repo.users.collectAsStateWithLifecycle()
    val overview by repo.overview.collectAsStateWithLifecycle()
    val metrics by repo.metrics.collectAsStateWithLifecycle()
    val ai by repo.ai.collectAsStateWithLifecycle()
    val reports by repo.reports.collectAsStateWithLifecycle()
    val toast = rememberToastState()
    val connection by container.realtime.state.collectAsStateWithLifecycle()
    val session by container.session.session.collectAsStateWithLifecycle()
    val english = LocalEnglish.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(UserFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(UserSort.ACTIVITY) }
    val listState = rememberLazyListState()
    val now = rememberNow()

    var searched by rememberSaveable { mutableStateOf(query) }
    var pulled by remember { mutableStateOf(false) }
    // Поиск на сервере — с паузой после набора.
    LaunchedEffect(query) {
        if (query == searched) return@LaunchedEffect
        delay(350)
        searched = query
        repo.refreshUsers(query)
    }
    LaunchedEffect(Unit) { if (!users.loaded && !users.loading) repo.refreshAll(query) }
    LaunchedEffect(users.loading) { if (!users.loading) pulled = false }
    // Метрики приходят кадром раз в 5 с; без WebSocket — опрашиваем сами, пока экран открыт.
    LaunchedEffect(connection) {
        while (connection != ConnectionState.CONNECTED) {
            repo.refreshMetrics()
            delay(5_000)
        }
    }

    val pinned by container.settings.pinned.collectAsStateWithLifecycle()
    // Фильтр и сортировка — вне композиции.
    val visible by produceState(emptyList<DeviceSummary>(), users.devices, filter, query, sort, pinned) {
        value = withContext(Dispatchers.Default) { UserList.visible(users.devices.values, filter, query, sort, pinned) }
    }
    val counts by produceState(IntArray(5), users.devices) {
        value = withContext(Dispatchers.Default) {
            val all = users.devices.values
            intArrayOf(
                all.count { !it.deleted },
                all.count { it.presence == Presence.FOREGROUND && !it.blocked && !it.deleted },
                all.count { it.presence == Presence.BACKGROUND && !it.blocked && !it.deleted },
                all.count { it.blocked },
                all.count { it.deleted },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(HonerTheme.colors.background)) {
            TopBar(
                title = "Honer Admin",
                subtitle = session?.email?.takeIf { it.isNotBlank() } ?: session?.name,
                actions = {
                    IconCircle(Icons.Rounded.BugReport, tr("Ошибки и падения", "Errors and crashes"), { navigator.push(Route.Reports) })
                    IconCircle(Icons.Rounded.Campaign, tr("Рассылка", "Broadcast"), { navigator.push(Route.Broadcast(null)) })
                    IconCircle(Icons.Rounded.Settings, tr("Настройки", "Settings"), { navigator.push(Route.Settings) })
                },
                leading = { AdminBadge() },
            )
            PullToRefreshBox(
                isRefreshing = pulled && users.loading,
                onRefresh = { pulled = true; repo.refreshAll(query) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    item(key = "connection", contentType = "banner") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ConnectionBanner(connection != ConnectionState.CONNECTED, Modifier.padding(top = 8.dp))
                        }
                    }
                    item(key = "ai", contentType = "ai") {
                        AiSwitchCard(ai, onToggle = { repo.updateAi(enabled = it) }, onOpen = { navigator.push(Route.Ai) },
                            onError = { toast.show(it) })
                    }
                    item(key = "metrics", contentType = "metrics") {
                        MetricsGrid(metrics, onRetry = { repo.refreshMetrics() })
                    }
                    item(key = "overview", contentType = "overview") {
                        OverviewGrid(overview.overview, overview.error, onRetry = { repo.refreshOverview() }) { f -> filter = f }
                    }
                    item(key = "support", contentType = "support") {
                        SupportStatusRow(container)
                    }
                    item(key = "reports", contentType = "reports") {
                        RecentReports(reports, now, onOpenAll = { navigator.push(Route.Reports) }, onOpenUser = { navigator.push(Route.User(it)) })
                    }
                    item(key = "search", contentType = "search") {
                        SearchField(query, { query = it })
                    }
                    item(key = "filters", contentType = "filters") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HonerPill(tr("Все", "All"), filter == UserFilter.ALL, { filter = UserFilter.ALL }, count = counts[0])
                            HonerPill(tr("В сети", "Online"), filter == UserFilter.ONLINE, { filter = UserFilter.ONLINE }, count = counts[1])
                            HonerPill(tr("В фоне", "Background"), filter == UserFilter.BACKGROUND, { filter = UserFilter.BACKGROUND }, count = counts[2])
                            HonerPill(tr("Заблокированы", "Blocked"), filter == UserFilter.BLOCKED, { filter = UserFilter.BLOCKED }, count = counts[3])
                            HonerPill(tr("Удалившие", "Uninstalled"), filter == UserFilter.DELETED, { filter = UserFilter.DELETED }, count = counts[4])
                            HonerPill(tr("По токенам", "By tokens"), sort == UserSort.TOKENS,
                                { sort = if (sort == UserSort.TOKENS) UserSort.ACTIVITY else UserSort.TOKENS })
                        }
                    }
                    when {
                        !users.loaded && users.error != null -> item(key = "error") {
                            ErrorPanel(users.error.orEmpty(), { repo.refreshAll(query) })
                        }
                        !users.loaded -> item(key = "loading") { LoadingBox(Modifier.height(200.dp)) }
                        visible.isEmpty() -> item(key = "empty") {
                            EmptyState(Icons.Rounded.Group, if (query.isNotBlank()) tr("Никого не нашли", "Nobody found") else tr("Пока пусто", "Nothing here yet"))
                        }
                        else -> items(visible, key = { it.deviceId }, contentType = { "user" }) { device ->
                            UserRow(
                                device, now, english, showTokens = sort == UserSort.TOKENS,
                                onOpen = { navigator.push(Route.User(device.deviceId)) },
                                onChat = { navigator.push(Route.Chat(device.adminChatId, device.deviceId)) },
                            )
                        }
                    }
                    if (users.loaded && users.error != null) {
                        item(key = "refresh-error") {
                            Text(users.error.orEmpty(), color = HonerTheme.colors.danger, fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { repo.refreshAll(query) })
                        }
                    }
                }
            }
        }
        ToastHost(toast, bottom = 40.dp)
    }
}

@Composable
private fun AdminBadge() {
    val colors = HonerTheme.colors
    Text("ADMIN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
        modifier = Modifier.clip(CircleShape).background(colors.adminRed).padding(horizontal = 7.dp, vertical = 2.dp))
}

/** Компактная строка статуса поддержки: в сети/не в сети и среднее время ответа (план п.20).
 *  Нажатие — выбор режима: авто (по присутствию), всегда в сети, не в сети. */
@Composable
private fun SupportStatusRow(container: com.honerai.admin.AdminContainer) {
    val colors = HonerTheme.colors
    val english = com.honerai.admin.ui.theme.LocalEnglish.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var stats by remember { mutableStateOf<com.honerai.admin.data.SupportStats?>(null) }
    var menu by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { runCatching { container.api.supportStats() }.onSuccess { stats = it } }
    val s = stats ?: return
    val modeLabel = when (s.mode) {
        "online" -> tr("вручную: в сети", "manual: online")
        "offline" -> tr("вручную: не в сети", "manual: offline")
        else -> tr("авто", "auto")
    }
    fun setMode(mode: String) {
        menu = false
        scope.launch { runCatching { container.api.setSupportStatus(mode) }; reload++ }
    }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { menu = true }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.online) colors.online else colors.secondary))
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.online) tr("Поддержка в сети", "Support online") else tr("Поддержка не в сети", "Support offline"),
                fontSize = 14.sp, color = colors.foreground, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text("· $modeLabel", fontSize = 12.sp, color = colors.secondary)
            s.avgResponseSeconds?.let { avg ->
                Spacer(Modifier.weight(1f))
                Text(tr("ср. ответ ", "avg reply ") + PresenceText.duration(avg.toLong(), english),
                    fontSize = 13.sp, color = colors.secondary)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
            containerColor = colors.sidebar) {
            val opts = listOf(
                "auto" to tr("Авто (по присутствию)", "Auto (by presence)"),
                "online" to tr("Всегда в сети", "Always online"),
                "offline" to tr("Не в сети", "Offline"),
            )
            for ((mode, label) in opts) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, color = if (s.mode == mode) colors.accent else colors.foreground) },
                    onClick = { setMode(mode) },
                )
            }
        }
    }
}

@Composable
private fun OverviewGrid(overview: Overview?, error: String?, onRetry: () -> Unit, onFilter: (UserFilter) -> Unit) {
    val colors = HonerTheme.colors
    val o = overview
    val tiles = listOf(
        Triple(tr("Пользователи", "Users"), o?.users, UserFilter.ALL),
        Triple(tr("Установки", "Installs"), o?.installs, null),
        Triple(tr("В сети", "Online"), o?.online, UserFilter.ONLINE),
        Triple(tr("В фоне", "Background"), o?.inBackground, UserFilter.BACKGROUND),
        Triple(tr("Заблокированы", "Blocked"), o?.blocked, UserFilter.BLOCKED),
        Triple(tr("Сообщений сегодня", "Messages today"), o?.messagesToday, null),
        Triple(tr("Обновления", "Updates"), o?.updates, null),
        Triple(tr("Удалили (вероятно)", "Uninstalled (likely)"), o?.uninstalls, null),
        Triple(tr("Не заходили >", "Inactive >") + (o?.inactiveDays ?: 7) + tr(" дн.", " d"), o?.inactive, null),
    )
    val accents = listOf(colors.foreground, colors.foreground, colors.online, colors.away, colors.danger, colors.accent,
        colors.foreground, colors.secondary, colors.secondary)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val columns = if (maxWidth >= 560.dp) 9 else 3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.indices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { i ->
                        val (label, value, f) = tiles[i]
                        StatTile(label, value, accents[i], Modifier.weight(1f), onClick = f?.let { { onFilter(it) } })
                    }
                }
            }
            if (error != null && overview == null) {
                Text(error + tr(" · Повторить", " · Retry"), color = colors.danger, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(6.dp))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int?, accent: Color, modifier: Modifier, onClick: (() -> Unit)?) {
    val colors = HonerTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value?.toString() ?: "—", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        Text(label, fontSize = 12.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val colors = HonerTheme.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(44.dp).clip(CircleShape)
            .background(colors.surface).border(0.7.dp, colors.divider, CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(tr("Поиск по имени, ID или модели", "Search by name, ID or model"), color = colors.secondary, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = colors.foreground, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Rounded.Close, tr("Очистить", "Clear"), tint = colors.secondary,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") })
        }
    }
}

@Composable
private fun UserRow(device: DeviceSummary, now: Instant, english: Boolean, showTokens: Boolean, onOpen: () -> Unit, onChat: () -> Unit) {
    val colors = HonerTheme.colors
    val name = device.title(english)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, device.deviceId, if (device.blocked) null else device.presence, blocked = device.blocked)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                device.idLabel().takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, fontSize = 13.sp, color = colors.secondary, maxLines = 1)
                }
                if (device.blocked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Block, tr("Заблокирован", "Blocked"), tint = colors.danger, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            if (device.typingIn != null && !device.blocked) {
                TypingLabel(tr("печатает", "typing"))
            } else {
                val status = PresenceText.status(device.presence, Times.parse(device.lastSeen), now, ZoneId.systemDefault(), english)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(
                            if (showTokens) Numbers.compact(device.aiTokens, english) + if (english) " tok." else " ток." else null,
                            device.deviceModel, device.appVersion.takeIf { it.isNotBlank() }?.let { "v$it" },
                        )
                            .filter { !it.isNullOrBlank() }.joinToString(" · "),
                        fontSize = 13.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" · ", fontSize = 13.sp, color = colors.secondary)
                    Text(status, fontSize = 13.sp, color = if (device.presence == Presence.OFFLINE) colors.secondary else presenceColor(device.presence),
                        maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(contentAlignment = Alignment.TopEnd) {
            IconCircle(Icons.AutoMirrored.Rounded.Chat, tr("Написать", "Message"), onChat, tint = colors.accent)
            if (device.unreadForAdmin > 0) {
                Text(
                    if (device.unreadForAdmin > 99) "99+" else device.unreadForAdmin.toString(),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.widthIn(min = 20.dp).clip(CircleShape).background(colors.accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** «печатает…» с бегущими точками. */
@Composable
fun TypingLabel(text: String, color: Color = HonerTheme.colors.accent, fontSize: Int = 13) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "dots")
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = fontSize.sp, color = color, maxLines = 1)
        for (i in 0..2) {
            val alpha = if (phase.toInt() == i) 1f else 0.35f
            Text(".", fontSize = fontSize.sp, color = color, modifier = Modifier.alpha(alpha))
        }
    }
}
