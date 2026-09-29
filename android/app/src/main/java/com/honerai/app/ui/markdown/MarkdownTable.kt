package com.honerai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ViewAgenda
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.tables.TableCsv
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * Распределение ширины столбцов компактной таблицы по содержимому (порт TableColumnLayout):
 * короткие столбцы получают меньше места, длинные — больше, в пределах 12–55 %.
 */
object TableColumnLayout {
    const val MINIMUM_SHARE = 0.12
    const val MAXIMUM_SHARE = 0.55

    fun shares(headers: List<String>, rows: List<List<String>>, columnCount: Int): List<Double> {
        if (columnCount <= 0) return emptyList()
        val weights = DoubleArray(columnCount) { column ->
            var longest = headers.getOrNull(column)?.length ?: 0
            for (row in rows) if (column < row.size) longest = maxOf(longest, row[column].length)
            // Корень сглаживает разницу: столбец в 25 знаков не в 25 раз шире столбца в один знак.
            sqrt(longest.toDouble().coerceIn(3.0, 28.0))
        }
        val total = weights.sum()
        if (total <= 0) return List(columnCount) { 1.0 / columnCount }
        val shares = DoubleArray(columnCount) { weights[it] / total }
        repeat(4) {
            val overflowing = shares.indices.filter { shares[it] > MAXIMUM_SHARE }
            if (overflowing.isEmpty()) return@repeat
            var freed = 0.0
            for (index in overflowing) { freed += shares[index] - MAXIMUM_SHARE; shares[index] = MAXIMUM_SHARE }
            val flexible = shares.indices.filter { shares[it] < MAXIMUM_SHARE }
            val flexibleTotal = flexible.sumOf { shares[it] }
            if (flexible.isEmpty() || flexibleTotal <= 0) return@repeat
            for (index in flexible) shares[index] += freed * shares[index] / flexibleTotal
        }
        repeat(4) {
            val narrow = shares.indices.filter { shares[it] < MINIMUM_SHARE }
            if (narrow.isEmpty()) return@repeat
            var needed = 0.0
            for (index in narrow) { needed += MINIMUM_SHARE - shares[index]; shares[index] = MINIMUM_SHARE }
            val donors = shares.indices.filter { shares[it] > MINIMUM_SHARE }
            val donorTotal = donors.sumOf { shares[it] }
            if (donors.isEmpty() || donorTotal <= 0) return@repeat
            for (index in donors) shares[index] -= needed * shares[index] / donorTotal
        }
        return shares.toList()
    }

    /**
     * Ширины столбцов в ширину [available]: по долям [shares], но не уже [minimums]
     * (самое длинное слово столбца не должно рваться посередине).
     * null — столбцы не помещаются даже по минимуму, нужна широкая таблица с прокруткой.
     */
    fun fitWidths(shares: List<Double>, minimums: List<Float>, available: Float): List<Float>? {
        val n = shares.size
        if (n == 0 || minimums.size != n) return null
        if (minimums.sum() > available) return null
        val fixed = BooleanArray(n)
        val widths = FloatArray(n)
        repeat(n + 1) {
            val freeSpace = available - (0 until n).filter { fixed[it] }.sumOf { minimums[it].toDouble() }.toFloat()
            val freeShare = (0 until n).filter { !fixed[it] }.sumOf { shares[it] }
            var changed = false
            for (i in 0 until n) {
                if (fixed[i]) { widths[i] = minimums[i]; continue }
                widths[i] = if (freeShare > 0) (shares[i] / freeShare * freeSpace).toFloat() else freeSpace / n
                if (widths[i] < minimums[i]) { fixed[i] = true; changed = true }
            }
            if (!changed) return widths.toList()
        }
        return (0 until n).map { if (fixed[it]) minimums[it] else widths[it] }
    }

    /** Самые длинные слова ячейки (кандидаты на минимальную ширину столбца). */
    fun longestWords(text: String, limit: Int = 2): List<String> =
        text.replace(Regex("[*_`~]"), "").split(Regex("[\\s\\u00A0]+"))
            .filter { it.isNotEmpty() }.sortedByDescending { it.length }.take(limit)

    /** Строки после фильтра и сортировки (числа сравниваются как числа). */
    fun visibleRows(rows: List<List<String>>, filter: String, sortColumn: Int?, ascending: Boolean): List<List<String>> {
        var result = rows
        val query = filter.trim()
        if (query.isNotEmpty()) result = result.filter { row -> row.any { it.contains(query, ignoreCase = true) } }
        if (sortColumn != null) {
            val comparator = Comparator<List<String>> { lhs, rhs ->
                TableCsv.compareCells(lhs.getOrElse(sortColumn) { "" }, rhs.getOrElse(sortColumn) { "" })
            }
            result = result.sortedWith(if (ascending) comparator else comparator.reversed())
        }
        return result
    }

    /** Столбцы, где каждое значение — число (их можно сложить в строке итогов). */
    fun numericColumns(rows: List<List<String>>, columnCount: Int): List<Int> = (0 until columnCount).filter { column ->
        val values = rows.count { row -> column < row.size && TableCsv.numericValue(row[column]) != null }
        values >= 2 && values == rows.size
    }

    fun formatted(value: Double): String =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1_000_000) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", value)

    fun asMarkdown(headers: List<String>, alignments: List<TableAlignment>, rows: List<List<String>>): String {
        // Палочку внутри ячейки экранируем: иначе вставленная таблица распадается на лишние столбцы.
        fun cell(value: String) = value.replace("|", "\\|")
        val lines = ArrayList<String>()
        if (headers.isNotEmpty()) {
            lines.add("| " + headers.joinToString(" | ") { cell(it) } + " |")
            lines.add("|" + (0 until maxOf(headers.size, 1)).joinToString("|") { index ->
                when (alignments.getOrElse(index) { TableAlignment.LEADING }) {
                    TableAlignment.LEADING -> ":---"
                    TableAlignment.CENTER -> ":---:"
                    TableAlignment.TRAILING -> "---:"
                }
            } + "|")
        }
        for (cells in rows) lines.add("| " + cells.joinToString(" | ") { cell(it) } + " |")
        return lines.joinToString("\n")
    }

    fun asTsv(headers: List<String>, rows: List<List<String>>): String =
        (listOf(headers) + rows).joinToString("\n") { row -> row.joinToString("\t") { it.replace("\t", " ") } }
}

/** До скольких столбцов таблица по умолчанию умещается в ширину экрана телефона. */
private const val FIT_COLUMN_LIMIT = 4

/** Сколько строк показывать без раскрытия. */
private const val ROW_LIMIT = 40

/**
 * Таблица GFM в ответе (порт MarkdownTableView): выравнивание, сортировка по нажатию
 * на заголовок, фильтр строк, итоги, компактный и широкий режимы, копирование
 * в Markdown / TSV / CSV и экспорт файлом.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkdownTableView(
    headers: List<String>,
    alignments: List<TableAlignment>,
    rows: List<List<String>>,
    fontSize: Float,
    context: MarkdownRenderContext,
) {
    val colors = HonerTheme.colors
    val english = context.english
    val androidContext = LocalContext.current
    var sortColumn by remember { mutableStateOf<Int?>(null) }
    var sortAscending by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    var showTotals by remember { mutableStateOf(false) }
    var modeOverride by remember { mutableStateOf<Boolean?>(null) }
    var showAllRows by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copied) {
        if (copied != null) { delay(1600); copied = null }
    }

    val columnCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    val compact = modeOverride ?: (columnCount <= FIT_COLUMN_LIMIT)
    val hasHeaderText = headers.any { it.isNotBlank() }
    val visibleRows = remember(rows, filter, sortColumn, sortAscending) {
        TableColumnLayout.visibleRows(rows, filter, sortColumn, sortAscending)
    }
    val shownRows = if (showAllRows || visibleRows.size <= ROW_LIMIT) visibleRows else visibleRows.subList(0, ROW_LIMIT)
    val numericColumns = remember(shownRows, columnCount) { TableColumnLayout.numericColumns(shownRows, columnCount) }
    val alignment = { index: Int -> alignments.getOrElse(index) { TableAlignment.LEADING } }
    val onSort = { index: Int ->
        if (sortColumn == index) sortAscending = !sortAscending else { sortColumn = index; sortAscending = true }
    }
    val totals = if (showTotals && numericColumns.isNotEmpty()) {
        (0 until columnCount).map { column ->
            if (column !in numericColumns) null
            else shownRows.mapNotNull { row -> row.getOrNull(column)?.let { TableCsv.numericValue(it) } }
        }
    } else null

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (headers.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(0.6.dp, colors.divider, CircleShape)
                    .defaultMinSize(minHeight = 34.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Search, null, tint = colors.secondary, modifier = Modifier.size(15.dp))
                Box(Modifier.weight(1f)) {
                    if (filter.isEmpty()) {
                        Text(tr(english, "Фильтр по строкам", "Filter rows"), fontSize = 12.sp, color = colors.secondary)
                    }
                    BasicTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = colors.foreground),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().testTag("table.filter"),
                    )
                }
                if (filter.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Cancel, tr(english, "Очистить", "Clear"), tint = colors.secondary,
                        modifier = Modifier.size(18.dp).clip(CircleShape).clickable { filter = "" },
                    )
                }
                if (numericColumns.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Functions, tr(english, "Итоги по столбцам", "Column totals"),
                        tint = if (showTotals) colors.accent else colors.secondary,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { showTotals = !showTotals },
                    )
                }
                Icon(
                    if (compact) Icons.Outlined.SwapHoriz else Icons.Outlined.ViewAgenda,
                    if (compact) tr(english, "Показать широкой таблицей с прокруткой", "Show as a wide scrolling table")
                    else tr(english, "Уместить таблицу в экран", "Fit the table to the screen"),
                    tint = colors.secondary,
                    modifier = Modifier.size(22.dp).clip(CircleShape).clickable { modeOverride = !compact }.testTag("table.mode"),
                )
            }
        }

        if (compact) {
            FitTable(headers, shownRows, columnCount, hasHeaderText, alignment, sortColumn, sortAscending, onSort, totals, fontSize, context)
        } else {
            WideTable(headers, shownRows, columnCount, hasHeaderText, alignment, sortColumn, sortAscending, onSort, totals, fontSize, context)
        }

        // Фильтр может не найти ни одной строки — объясняем и даём сбросить.
        if (visibleRows.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(colors.raised.copy(alpha = 0.3f)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (filter.isEmpty()) tr(english, "Нет строк для показа", "No rows to show") else tr(english, "Ничего не найдено", "Nothing found"),
                    fontSize = 12.sp, color = colors.secondary,
                )
                if (filter.isNotEmpty()) {
                    Text(
                        tr(english, "Сбросить фильтр", "Reset filter"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.accent,
                        modifier = Modifier.clickable { filter = ""; sortColumn = null }.testTag("table.filter.reset"),
                    )
                }
            }
        }

        if (!showAllRows && visibleRows.size > ROW_LIMIT) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { showAllRows = true }.testTag("table.rows.more"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Text(
                    tr(english, "Показать все строки (${visibleRows.size})", "Show all rows (${visibleRows.size})"),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.accent,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val copyTo = { title: String, value: String ->
                RenderActions.copy(androidContext, value)
                copied = title
            }
            TableChip("Markdown") { copyTo("Markdown", TableColumnLayout.asMarkdown(headers, alignments, visibleRows)) }
            TableChip("TSV") { copyTo("TSV", TableColumnLayout.asTsv(headers, visibleRows)) }
            TableChip("CSV") { copyTo("CSV", TableCsv.csv(headers, visibleRows)) }
            // Экспорт файлом: CSV с BOM открывается в Excel, Google Таблицах и WPS.
            TableChip(tr(english, "Открыть в таблицах", "Open in Sheets"), tag = "table.export.numbers") {
                TableCsv.shareFile(
                    androidContext, "Honer-таблица-${System.currentTimeMillis() / 1000}.csv",
                    "﻿" + TableCsv.csv(headers, visibleRows), "text/csv", tr(english, "Экспорт таблицы", "Export table"),
                )
            }
            if (filter.isNotEmpty()) {
                Text(
                    tr(english, "найдено ${visibleRows.size} из ${rows.size}", "found ${visibleRows.size} of ${rows.size}"),
                    fontSize = 11.sp, color = colors.secondary, modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            copied?.let {
                Text(
                    tr(english, "Скопировано: $it", "Copied: $it"), fontSize = 11.sp, color = colors.secondary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun TableChip(title: String, tag: String? = null, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Box(
        Modifier
            .clip(CircleShape)
            .border(0.7.dp, colors.divider, CircleShape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 28.dp)
            .padding(horizontal = 10.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.secondary)
    }
}

/** Компактный режим: настоящая таблица в ширину экрана, текст переносится внутри ячейки. */
@Composable
private fun FitTable(
    headers: List<String>,
    rows: List<List<String>>,
    columnCount: Int,
    hasHeaderText: Boolean,
    alignment: (Int) -> TableAlignment,
    sortColumn: Int?,
    sortAscending: Boolean,
    onSort: (Int) -> Unit,
    totals: List<List<Double>?>?,
    fontSize: Float,
    context: MarkdownRenderContext,
) {
    val colors = HonerTheme.colors
    val shares = remember(headers, rows, columnCount) { TableColumnLayout.shares(headers, rows, columnCount) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = androidx.compose.material3.LocalTextStyle.current
    // Минимум столбца — самое длинное слово целиком (+ поля ячейки), чтобы «Меркурий» не рвался на «Меркури-й».
    val minimums = remember(headers, rows, columnCount, fontSize, density, baseStyle) {
        (0 until columnCount).map { column ->
            val headerStyle = baseStyle.merge(TextStyle(fontSize = (fontSize * 0.9f).sp, fontWeight = FontWeight.SemiBold))
            val cellStyle = baseStyle.merge(TextStyle(fontSize = (fontSize * 0.92f).sp))
            var widest = 0
            for (word in TableColumnLayout.longestWords(headers.getOrElse(column) { "" })) {
                widest = maxOf(widest, measurer.measure(word, headerStyle, maxLines = 1, softWrap = false).size.width)
            }
            val cellWords = rows.flatMap { TableColumnLayout.longestWords(it.getOrElse(column) { "" }) }
                .sortedByDescending { it.length }.take(3)
            for (word in cellWords) {
                widest = maxOf(widest, measurer.measure(word, cellStyle, maxLines = 1, softWrap = false).size.width)
            }
            with(density) { widest.toDp().value } + 14f
        }
    }
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth - 0.5.dp * maxOf(0, columnCount - 1) - 1.2.dp
        val fitted = remember(shares, minimums, available) { TableColumnLayout.fitWidths(shares, minimums, available.value) }
        if (fitted == null) {
            // Даже по минимуму не помещается — показываем широкой таблицей с прокруткой, без разрыва слов.
            WideTable(headers, rows, columnCount, hasHeaderText, alignment, sortColumn, sortAscending, onSort, totals, fontSize, context)
            return@BoxWithConstraints
        }
        val widthOf = { index: Int -> fitted.getOrElse(index) { 0f }.dp }
        Column(Modifier.clip(shape).background(colors.surface).border(0.6.dp, colors.divider, shape)) {
            if (hasHeaderText) {
                Row(Modifier.background(colors.raised).height(IntrinsicSize.Min)) {
                    for (index in 0 until columnCount) {
                        HeaderCell(headers.getOrElse(index) { "" }, index, widthOf(index), alignment(index), sortColumn, sortAscending, onSort, fontSize, context)
                        if (index < columnCount - 1) VerticalRule()
                    }
                }
                HorizontalRule(1.dp, 1f)
            }
            rows.forEachIndexed { rowIndex, cells ->
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .background(if (rowIndex % 2 == 1) colors.raised.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent)
                ) {
                    for (column in 0 until columnCount) {
                        DataCell(cells.getOrElse(column) { "" }, Modifier.width(widthOf(column)).padding(horizontal = 5.dp, vertical = 7.dp), alignment(column), fontSize, context)
                        if (column < columnCount - 1) VerticalRule()
                    }
                }
                if (rowIndex < rows.size - 1) HorizontalRule(0.5.dp, 0.5f)
            }
            if (totals != null) {
                HorizontalRule(1.dp, 1f)
                Row(Modifier.background(colors.raised.copy(alpha = 0.6f))) {
                    for (column in 0 until columnCount) {
                        TotalsCell(column, totals[column], Modifier.width(widthOf(column)).padding(horizontal = 5.dp, vertical = 7.dp), alignment(column), fontSize, context.english)
                        if (column < columnCount - 1) Box(Modifier.width(0.5.dp))
                    }
                }
            }
        }
    }
}

/** Широкая таблица с горизонтальной прокруткой; ширина столбца — по самому длинному тексту. */
@Composable
private fun WideTable(
    headers: List<String>,
    rows: List<List<String>>,
    columnCount: Int,
    hasHeaderText: Boolean,
    alignment: (Int) -> TableAlignment,
    sortColumn: Int?,
    sortAscending: Boolean,
    onSort: (Int) -> Unit,
    totals: List<List<Double>?>?,
    fontSize: Float,
    context: MarkdownRenderContext,
) {
    val colors = HonerTheme.colors
    val widths = remember(headers, rows, columnCount, fontSize) {
        (0 until columnCount).map { column ->
            var longest = headers.getOrNull(column)?.length ?: 1
            for (row in rows) if (column < row.size) longest = maxOf(longest, row[column].length)
            (longest * fontSize * 0.56f + 22f).coerceIn(96f, 260f).dp
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.clip(shape).background(colors.surface).border(0.6.dp, colors.divider, shape)) {
                if (hasHeaderText) {
                    Row(Modifier.background(colors.raised).height(IntrinsicSize.Min)) {
                        for (index in 0 until columnCount) {
                            HeaderCell(headers.getOrElse(index) { "" }, index, widths[index], alignment(index), sortColumn, sortAscending, onSort, fontSize, context)
                            if (index < columnCount - 1) VerticalRule()
                        }
                    }
                    HorizontalRule(1.dp, 1f, widths.fold(0.dp) { a, b -> a + b } + 0.5.dp * maxOf(0, columnCount - 1))
                }
                rows.forEachIndexed { rowIndex, cells ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        for (column in 0 until columnCount) {
                            DataCell(cells.getOrElse(column) { "" }, Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 8.dp), alignment(column), fontSize, context)
                            if (column < columnCount - 1) VerticalRule()
                        }
                    }
                    if (rowIndex < rows.size - 1) HorizontalRule(0.5.dp, 0.5f, widths.fold(0.dp) { a, b -> a + b } + 0.5.dp * maxOf(0, columnCount - 1))
                }
                if (totals != null) {
                    Row(Modifier.background(colors.raised.copy(alpha = 0.6f))) {
                        for (column in 0 until columnCount) {
                            TotalsCell(column, totals[column], Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 8.dp), alignment(column), fontSize, context.english)
                            if (column < columnCount - 1) Box(Modifier.width(0.5.dp))
                        }
                    }
                }
            }
        }
        if (rows.size > 2) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(colors.raised)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(Icons.Outlined.SwapHoriz, null, tint = colors.secondary, modifier = Modifier.size(11.dp))
                Text(tr(context.english, "прокрутите", "scroll"), fontSize = 9.sp, fontWeight = FontWeight.Medium, color = colors.secondary)
            }
        }
    }
}

@Composable
private fun HeaderCell(
    title: String,
    index: Int,
    width: Dp,
    alignment: TableAlignment,
    sortColumn: Int?,
    sortAscending: Boolean,
    onSort: (Int) -> Unit,
    fontSize: Float,
    context: MarkdownRenderContext,
) {
    val colors = HonerTheme.colors
    val sorted = sortColumn == index
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clickable { onSort(index) }
            .semantics { contentDescription = tr(context.english, "Сортировать по столбцу", "Sort by column") + " " + title }
            .padding(horizontal = 5.dp, vertical = 7.dp),
        contentAlignment = alignment.boxAlignment(),
    ) {
        Text(
            text = title.ifEmpty { "—" } + if (sorted) (if (sortAscending) " ↑" else " ↓") else "",
            fontSize = (fontSize * 0.9f).sp,
            lineHeight = (fontSize * 1.2f).sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                title.isEmpty() -> colors.secondary
                sorted -> colors.accent
                else -> colors.foreground
            },
            textAlign = alignment.textAlign(),
        )
    }
}

@Composable
private fun DataCell(value: String, modifier: Modifier, alignment: TableAlignment, fontSize: Float, context: MarkdownRenderContext) {
    val colors = HonerTheme.colors
    Box(modifier, contentAlignment = alignment.boxAlignment()) {
        if (value.isEmpty()) {
            Text("—", fontSize = (fontSize * 0.9f).sp, color = colors.secondary)
        } else {
            InlineText(value, fontSize * 0.92f, FontWeight.Normal, context, textAlign = alignment.textAlign())
        }
    }
}

/** Ячейка итогов: сумма и среднее по числовому столбцу. */
@Composable
private fun TotalsCell(column: Int, values: List<Double>?, modifier: Modifier, alignment: TableAlignment, fontSize: Float, english: Boolean) {
    val colors = HonerTheme.colors
    Column(modifier, horizontalAlignment = when (alignment) {
        TableAlignment.LEADING -> Alignment.Start
        TableAlignment.CENTER -> Alignment.CenterHorizontally
        TableAlignment.TRAILING -> Alignment.End
    }) {
        if (values.isNullOrEmpty()) {
            Text(if (column == 0) tr(english, "Итого", "Total") else "", fontSize = (fontSize * 0.85f).sp, fontWeight = FontWeight.SemiBold, color = colors.secondary)
        } else {
            val sum = values.sum()
            Text("Σ ${TableColumnLayout.formatted(sum)}", fontSize = (fontSize * 0.85f).sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
            Text(
                tr(english, "сред. ", "avg ") + TableColumnLayout.formatted(sum / values.size),
                fontSize = (fontSize * 0.75f).sp, color = colors.secondary,
            )
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(Modifier.width(0.5.dp).fillMaxHeight().background(HonerTheme.colors.divider.copy(alpha = 0.6f)))
}

@Composable
private fun HorizontalRule(thickness: Dp, alpha: Float, width: Dp? = null) {
    val modifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Box(modifier.height(thickness).background(HonerTheme.colors.divider.copy(alpha = alpha)))
}
