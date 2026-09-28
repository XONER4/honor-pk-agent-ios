package com.honerai.app.ui.tables

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.honerai.app.AppContainer
import com.honerai.app.data.ChatTable
import com.honerai.app.ui.markdown.RenderActions
import com.honerai.app.ui.markdown.rememberEnglish
import com.honerai.app.ui.markdown.tr
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Карточки таблиц под ответом; нажатие открывает редактор на весь экран.
 * Данные берутся из хранилища чатов: правки пользователя сразу видны и здесь,
 * и в следующих ответах Honer AI.
 */
@Composable
fun ChatTableCards(ids: List<String>, fontScale: Float = 1f) {
    val context = LocalContext.current
    val store = remember(context) { AppContainer.get(context).store }
    // Подписка на чаты: сохранённая правка таблицы меняет список — карточки перерисовываются.
    val conversations by store.conversations.collectAsState()
    val scale = fontScale.coerceIn(0.9f, 1.25f)
    var opened by remember { mutableStateOf<String?>(null) }
    val tables = remember(ids, conversations) { ids.mapNotNull { store.table(it) } }
    if (tables.isEmpty() && opened == null) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tables.forEach { table ->
            TableCard(table, scale, Modifier.clickable { opened = table.id }.testTag("message.table.${table.id}"))
        }
    }
    opened?.let { id ->
        Dialog(
            onDismissRequest = { opened = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            TableEditorScreen(id) { opened = null }
        }
    }
}

/** Карточка таблицы: название, размер и первые строки. */
@Composable
private fun TableCard(table: ChatTable, scale: Float, modifier: Modifier) {
    val colors = HonerTheme.colors
    val english = rememberEnglish()
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(0.8.dp, colors.divider, shape)
            .then(modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF33B373), Color(0xFF1A8C66)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.TableChart, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(table.title, fontSize = (16 * scale).sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = mutableListOf(
                    tr(english, "${table.rows.size} строк · ${table.columns.size} столбцов", "${table.rows.size} rows · ${table.columns.size} columns"),
                    if (table.editable) tr(english, "можно править", "editable") else tr(english, "только просмотр", "read-only"),
                )
                if (table.editedByUser) parts.add(tr(english, "есть ваши правки", "edited by you"))
                Text(parts.joinToString(" · "), fontSize = (12 * scale).sp, color = colors.secondary)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.raised.copy(alpha = 0.6f))) {
            PreviewRow(table.columns.take(3), bold = true, scale = scale)
            table.rows.take(3).forEach { row ->
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                PreviewRow(TableCsv.normalized(row, table.columns.size).take(3), bold = false, scale = scale)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (table.editable) Icons.Outlined.EditNote else Icons.Outlined.OpenInFull, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(
                if (table.editable) tr(english, "Открыть и редактировать", "Open and edit") else tr(english, "Открыть таблицу", "Open table"),
                fontSize = (13 * scale).sp, fontWeight = FontWeight.SemiBold, color = colors.accent,
            )
        }
    }
}

@Composable
private fun PreviewRow(values: List<String>, bold: Boolean, scale: Float) {
    val colors = HonerTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            Text(
                value.ifEmpty { "—" },
                fontSize = (12.5f * scale).sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = if (bold) colors.foreground else colors.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val ColumnWidth = 150.dp
private val NumberWidth = 44.dp

/**
 * Таблица на весь экран (порт TableEditorView): правка ячеек, строк, столбцов и названия,
 * сортировка, поиск, экспорт CSV, копирование текстом, «Спросить Honer AI».
 * Каждая правка сохраняется сразу — Honer AI видит её в следующем ответе.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableEditorScreen(tableId: String, onClose: () -> Unit) {
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val english = rememberEnglish()
    val store = remember(context) { AppContainer.get(context).store }
    val conversations by store.conversations.collectAsState()
    // Номер правки: после сохранения таблица перечитывается, даже если хранилище не сменило список чатов.
    var revision by remember { mutableIntStateOf(0) }
    val table = remember(tableId, conversations, revision) { store.table(tableId) }

    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var draft by remember { mutableStateOf("") }
    var renamingColumn by remember { mutableStateOf<Int?>(null) }
    var renamingTitle by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var headerMenu by remember { mutableStateOf<Int?>(null) }
    var rowMenu by remember { mutableStateOf<Int?>(null) }
    var toolbarMenu by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)
    LaunchedEffect(toast) {
        if (toast != null) { delay(1600); toast = null }
    }

    fun t(ru: String, en: String) = tr(english, ru, en)

    fun mutate(change: (ChatTable) -> ChatTable?) {
        val current = store.table(tableId) ?: return
        val updated = change(current) ?: return
        store.saveTable(updated.copy(updatedAt = Instant.now()))
        revision++
    }

    fun insertRow(after: Int) {
        mutate { value ->
            val position = (after + 1).coerceIn(0, value.rows.size)
            value.copy(rows = value.rows.toMutableList().apply { add(position, List(value.columns.size) { "" }) })
        }
        RenderActions.haptic(view, RenderActions.Haptic.LIGHT)
    }

    fun deleteRow(index: Int) = mutate { value ->
        if (index !in value.rows.indices) null else value.copy(rows = value.rows.toMutableList().apply { removeAt(index) })
    }

    fun addColumn() = mutate { value ->
        value.copy(
            columns = value.columns + (t("Столбец", "Column") + " ${value.columns.size + 1}"),
            rows = value.rows.map { it + "" },
        )
    }

    fun deleteColumn(index: Int) = mutate { value ->
        if (value.columns.size <= 1 || index !in value.columns.indices) null
        else value.copy(
            columns = value.columns.toMutableList().apply { removeAt(index) },
            rows = value.rows.map { row -> if (index < row.size) row.toMutableList().apply { removeAt(index) } else row },
        )
    }

    fun sort(column: Int, descending: Boolean) {
        val current = store.table(tableId) ?: return
        store.saveTable(TableCsv.sorted(current, column, descending), byUser = current.editable)
        revision++
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("table.editor"),
    ) {
        // Верхняя панель: «Готово», название, меню.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose, modifier = Modifier.testTag("table.close")) {
                Text(t("Готово", "Done"), color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                table?.title ?: t("Таблица", "Table"),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { toolbarMenu = true }.testTag("table.menu")
                        .semantics { contentDescription = t("Меню таблицы", "Table menu") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.MoreVert, null, tint = colors.accent)
                }
                DropdownMenu(expanded = toolbarMenu, onDismissRequest = { toolbarMenu = false }, containerColor = colors.raised) {
                    if (table?.editable == true) {
                        MenuItem(Icons.Outlined.Edit, t("Переименовать таблицу", "Rename table")) {
                            toolbarMenu = false; nameDraft = table.title; renamingTitle = true
                        }
                    }
                    MenuItem(Icons.Outlined.ContentCopy, t("Копировать как текст", "Copy as text")) {
                        toolbarMenu = false
                        table?.let {
                            RenderActions.copy(context, it.title + "\n" + TableCsv.markdown(it, limitRows = 5000))
                            toast = t("Таблица скопирована", "Table copied")
                        }
                    }
                    MenuItem(Icons.Outlined.IosShare, t("Экспорт CSV", "Export CSV"), tag = "table.export") {
                        toolbarMenu = false
                        table?.let {
                            // BOM нужен, чтобы Excel открыл кириллицу правильно.
                            val ok = TableCsv.shareFile(context, TableCsv.fileName(it.title, "csv"), TableCsv.csvWithBom(it), "text/csv", t("Экспорт CSV", "Export CSV"))
                            if (!ok) toast = t("Не удалось сохранить файл", "Couldn't save the file")
                        }
                    }
                    MenuItem(Icons.Outlined.AutoAwesome, t("Спросить Honer AI о таблице", "Ask Honer AI about the table"), tag = "table.ask") {
                        toolbarMenu = false
                        table?.let {
                            val index = store.selectedConversation()?.tables?.indexOfFirst { item -> item.id == it.id } ?: -1
                            val number = index.coerceAtLeast(0) + 1
                            store.draft.value = t("Посмотри таблицу T$number «${it.title}» и ", "Look at table T$number \"${it.title}\" and ")
                            onClose()
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                    MenuItem(Icons.Outlined.Delete, t("Удалить таблицу", "Delete table"), destructive = true, tag = "table.delete") {
                        toolbarMenu = false
                        store.deleteTable(tableId)
                        onClose()
                    }
                }
            }
        }

        // Поиск по таблице.
        Row(
            Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Search, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (search.isEmpty()) Text(t("Найти в таблице", "Search table"), color = colors.secondary, fontSize = 15.sp)
                BasicTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    textStyle = TextStyle(color = colors.foreground, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().testTag("table.search"),
                )
            }
            if (search.isNotEmpty()) {
                Icon(Icons.Filled.Cancel, t("Очистить", "Clear"), tint = colors.secondary, modifier = Modifier.size(18.dp).clickable { search = "" })
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

        if (table == null) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.TableRows, null, tint = colors.secondary, modifier = Modifier.size(40.dp))
                Text(t("Таблица удалена", "The table was deleted"), color = colors.secondary)
            }
        } else {
            val query = search.trim().lowercase()
            val visibleRows = remember(table, query) {
                if (query.isEmpty()) table.rows.indices.toList()
                else table.rows.indices.filter { index -> table.rows[index].any { it.lowercase().contains(query) } }
            }
            BoxWithConstraints(Modifier.fillMaxSize().imePadding().testTag("table.grid")) {
                val gridWidth = NumberWidth + ColumnWidth * table.columns.size + if (table.editable) 44.dp else 0.dp
                val screenWidth = maxWidth
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        // Небольшая таблица прижата к верху, а не висит посреди экрана.
                        Modifier.width(maxOf(gridWidth, screenWidth)).fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 40.dp),
                    ) {
                        stickyHeader(key = "header") {
                            Row(Modifier.background(colors.raised).height(44.dp)) {
                                Box(Modifier.width(NumberWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Text("№", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.foreground)
                                }
                                table.columns.forEachIndexed { index, name ->
                                    Box {
                                        Box(
                                            Modifier
                                                .width(ColumnWidth)
                                                .fillMaxHeight()
                                                .clickable { headerMenu = index }
                                                .padding(horizontal = 10.dp)
                                                .testTag("table.header.$index"),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        DropdownMenu(expanded = headerMenu == index, onDismissRequest = { headerMenu = null }, containerColor = colors.raised) {
                                            MenuItem(Icons.Outlined.ContentCopy, t("Копировать", "Copy")) {
                                                headerMenu = null; RenderActions.copy(context, name); toast = t("Скопировано", "Copied")
                                            }
                                            MenuItem(Icons.Outlined.ArrowUpward, t("Сортировать по возрастанию", "Sort ascending")) {
                                                headerMenu = null; sort(index, descending = false)
                                            }
                                            MenuItem(Icons.Outlined.ArrowDownward, t("Сортировать по убыванию", "Sort descending")) {
                                                headerMenu = null; sort(index, descending = true)
                                            }
                                            if (table.editable) {
                                                MenuItem(Icons.Outlined.Edit, t("Переименовать", "Rename")) {
                                                    headerMenu = null; nameDraft = name; renamingColumn = index
                                                }
                                                MenuItem(Icons.Outlined.Delete, t("Удалить столбец", "Delete column"), destructive = true) {
                                                    headerMenu = null; deleteColumn(index)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (table.editable) {
                                    Box(
                                        Modifier.size(44.dp).clickable { addColumn() }.testTag("table.addColumn")
                                            .semantics { contentDescription = t("Добавить столбец", "Add column") },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.Add, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            HorizontalDivider(color = colors.divider, thickness = 1.dp)
                        }
                        items(visibleRows, key = { it }) { rowIndex ->
                            val row = TableCsv.normalized(table.rows[rowIndex], table.columns.size)
                            Box {
                                Row(
                                    Modifier
                                        .height(46.dp)
                                        .background(if (rowIndex % 2 == 0) colors.background else colors.surface.copy(alpha = 0.6f))
                                ) {
                                    Box(
                                        Modifier.width(NumberWidth).fillMaxHeight().combinedClickable(onClick = {}, onLongClick = { rowMenu = rowIndex }),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${rowIndex + 1}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.secondary)
                                    }
                                    row.forEachIndexed { column, value ->
                                        Box(
                                            Modifier
                                                .width(ColumnWidth)
                                                .fillMaxHeight()
                                                .combinedClickable(
                                                    onClick = {
                                                        if (table.editable) {
                                                            draft = value
                                                            editing = rowIndex to column
                                                        } else {
                                                            RenderActions.copy(context, value)
                                                            toast = t("Скопировано: ", "Copied: ") + value.take(30)
                                                        }
                                                    },
                                                    onLongClick = { rowMenu = rowIndex },
                                                )
                                                .padding(horizontal = 10.dp)
                                                .testTag("table.cell.$rowIndex.$column"),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(value, fontSize = 14.sp, color = colors.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.divider.copy(alpha = 0.5f)))
                                    }
                                }
                                DropdownMenu(expanded = rowMenu == rowIndex, onDismissRequest = { rowMenu = null }, containerColor = colors.raised) {
                                    MenuItem(Icons.Outlined.ContentCopy, t("Копировать строку", "Copy row")) {
                                        rowMenu = null
                                        RenderActions.copy(context, row.joinToString("\t"))
                                        toast = t("Строка скопирована", "Row copied")
                                    }
                                    if (table.editable) {
                                        MenuItem(Icons.Outlined.VerticalAlignBottom, t("Вставить строку ниже", "Insert row below")) {
                                            rowMenu = null; insertRow(rowIndex)
                                        }
                                        MenuItem(Icons.Outlined.Delete, t("Удалить строку", "Delete row"), destructive = true) {
                                            rowMenu = null; deleteRow(rowIndex)
                                        }
                                    }
                                }
                            }
                        }
                        if (table.editable) {
                            item(key = "addRow") {
                                Row(
                                    Modifier
                                        .height(48.dp)
                                        .clickable { insertRow(table.rows.size - 1) }
                                        .padding(horizontal = 14.dp)
                                        .testTag("table.addRow"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.AddCircle, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                                    Text(t("Добавить строку", "Add row"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
                                }
                            }
                        }
                    }
                }
                // Короткое сообщение внизу: «Скопировано», «Строка скопирована».
                androidx.compose.animation.AnimatedVisibility(
                    visible = toast != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                ) {
                    Text(
                        toast.orEmpty(),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.foreground,
                        modifier = Modifier.clip(CircleShape).background(colors.raised).border(0.5.dp, colors.divider, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    // Правка ячейки.
    editing?.let { (row, column) ->
        EditDialog(
            title = t("Ячейка", "Cell"),
            label = t("Значение", "Value"),
            value = draft,
            onValueChange = { draft = it },
            english = english,
            fieldTag = "table.cell.field",
            saveTag = "table.cell.save",
            onDismiss = { editing = null },
        ) {
            mutate { value ->
                if (row !in value.rows.indices || column !in value.columns.indices) null
                else value.copy(rows = value.rows.toMutableList().also { rows ->
                    rows[row] = TableCsv.normalized(rows[row], value.columns.size).toMutableList().also { it[column] = draft }
                })
            }
            editing = null
        }
    }
    renamingColumn?.let { index ->
        EditDialog(t("Название столбца", "Column name"), t("Название", "Name"), nameDraft, { nameDraft = it }, english, onDismiss = { renamingColumn = null }) {
            val name = nameDraft.trim()
            if (name.isNotEmpty()) mutate { value ->
                if (index !in value.columns.indices) null
                else value.copy(columns = value.columns.toMutableList().also { it[index] = name })
            }
            renamingColumn = null
        }
    }
    if (renamingTitle) {
        EditDialog(t("Название таблицы", "Table title"), t("Название", "Title"), nameDraft, { nameDraft = it }, english, onDismiss = { renamingTitle = false }) {
            val title = nameDraft.trim()
            if (title.isNotEmpty()) mutate { it.copy(title = title.take(120)) }
            renamingTitle = false
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, destructive: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    val tint = if (destructive) Color(0xFFFF453A) else colors.foreground
    DropdownMenuItem(
        text = { Text(title, color = tint, fontSize = 15.sp) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}

@Composable
private fun EditDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    english: Boolean,
    fieldTag: String? = null,
    saveTag: String? = null,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = HonerTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(title, color = colors.foreground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.foreground, unfocusedTextColor = colors.foreground,
                    focusedBorderColor = colors.accent, unfocusedBorderColor = colors.divider,
                    focusedLabelColor = colors.accent, unfocusedLabelColor = colors.secondary, cursorColor = colors.accent,
                ),
                modifier = Modifier.fillMaxWidth().let { if (fieldTag != null) it.testTag(fieldTag) else it },
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, modifier = if (saveTag != null) Modifier.testTag(saveTag) else Modifier) {
                Text(tr(english, "Сохранить", "Save"), color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr(english, "Отмена", "Cancel"), color = colors.secondary) }
        },
    )
}
