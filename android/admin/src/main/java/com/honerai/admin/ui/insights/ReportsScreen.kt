package com.honerai.admin.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.admin.AdminContainer
import com.honerai.admin.data.ClientReport
import com.honerai.admin.data.ReportKinds
import com.honerai.admin.net.friendlyError
import com.honerai.admin.ui.Navigator
import com.honerai.admin.ui.Route
import com.honerai.admin.ui.common.EmptyState
import com.honerai.admin.ui.common.ErrorPanel
import com.honerai.admin.ui.common.HonerPill
import com.honerai.admin.ui.common.IconCircle
import com.honerai.admin.ui.common.LoadingBox
import com.honerai.admin.ui.common.TopBar
import com.honerai.admin.ui.common.rememberNow
import com.honerai.admin.ui.home.ReportRow
import com.honerai.admin.ui.theme.HonerTheme
import com.honerai.admin.ui.theme.LocalEnglish
import com.honerai.admin.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Ошибки и падения из приложений пользователей: фильтр, подробности (стек), переход в карточку. */
@Composable
fun ReportsScreen(container: AdminContainer, navigator: Navigator) {
    val colors = HonerTheme.colors
    val english = LocalEnglish.current
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<ClientReport>() }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(kind, reload) {
        loading = true; error = null
        try {
            val page = container.api.reports(kind = kind, limit = PAGE)
            items.clear(); items.addAll(page)
            more = page.size == PAGE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e, english)
        } finally {
            loading = false
        }
    }

    fun loadMore() {
        val before = items.lastOrNull()?.at ?: return
        more = false
        scope.launch {
            try {
                val page = container.api.reports(kind = kind, limit = PAGE, before = before)
                items.addAll(page.filter { p -> items.none { it.id == p.id } })
                more = page.size == PAGE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e, english)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        TopBar(tr("Ошибки и падения", "Errors and crashes"), onBack = { navigator.pop() },
            actions = { IconCircle(Icons.Rounded.Refresh, tr("Обновить", "Refresh"), { reload++ }) })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HonerPill(tr("Все", "All"), kind == null, { kind = null })
            HonerPill(tr("Падения", "Crashes"), kind == ReportKinds.CRASH, { kind = ReportKinds.CRASH })
            HonerPill(tr("Ошибки", "Errors"), kind == ReportKinds.ERROR, { kind = ReportKinds.ERROR })
        }
        when {
            loading && items.isEmpty() -> LoadingBox()
            error != null && items.isEmpty() -> ErrorPanel(error.orEmpty(), { reload++ }, Modifier.padding(top = 40.dp))
            items.isEmpty() -> EmptyState(Icons.Rounded.CheckCircle, tr("Ошибок нет", "No errors"))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                items(items, key = { it.id }) { r ->
                    val open = expanded == r.id
                    Column {
                        ReportRow(r, now, compact = false, expanded = open, onClick = { expanded = if (open) null else r.id })
                        if (open) {
                            Text(tr("Открыть пользователя", "Open user"), color = colors.accent, fontSize = 14.sp,
                                modifier = Modifier.padding(start = 44.dp, bottom = 8.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { navigator.push(Route.User(r.deviceId)) }.padding(6.dp))
                        }
                        Box(Modifier.fillMaxWidth().padding(start = 44.dp).height(0.6.dp).background(colors.divider))
                    }
                }
                if (more) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(tr("Показать ещё", "Show more"), color = colors.accent, fontSize = 15.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { loadMore() }.padding(10.dp))
                    }
                }
            }
        }
    }
}

private const val PAGE = 50
