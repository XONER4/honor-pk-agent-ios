package com.honerai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.theme.HonerTheme

/** Разобранная диаграмма Mermaid: направление и рёбра с подписями. */
@Immutable
data class MermaidGraph(val vertical: Boolean, val edges: List<MermaidGraph.Edge>) {
    @Immutable
    data class Edge(val from: String, val to: String, val label: String)

    /** Рёбра образуют одну цепочку A → B → C: её можно нарисовать схемой. */
    val isChain: Boolean
        get() = edges.isNotEmpty() && edges.zipWithNext().all { (a, b) -> a.to == b.from }
}

/**
 * Простой разбор flowchart (порт MermaidDiagramView.edges): `graph TD|LR`, стрелки
 * `-->`, `->`, `==>`, `-.->`, подписи `-->|да|` и `-- да -->`, узлы `A[Текст]`, `B(Текст)`,
 * `C{Текст}` и цепочки `A --> B --> C`. Названия узлов берутся из их объявлений.
 */
object MermaidParser {
    private val arrow = Regex("""\s*(-\.->|==>|-->|->)\s*""")
    private val node = Regex("""^([^\[\](){}>]+?)\s*(\[\[|\[\(|\(\(|\[|\(|\{\{|\{|>)(.*?)(\]\]|\)\]|\)\)|\]|\)|\}\}|\})?$""")

    fun parse(source: String): MermaidGraph {
        var vertical = true
        val labels = HashMap<String, String>()
        val raw = ArrayList<Triple<String, String, String>>()
        for (rawLine in source.split('\n')) {
            val line = rawLine.trimWs().removeSuffix(";")
            if (line.isEmpty() || line.startsWith("%%")) continue
            val lower = line.lowercase()
            if (lower.startsWith("graph") || lower.startsWith("flowchart")) {
                val direction = lower.substringAfter(' ', "td").trim()
                vertical = !(direction.startsWith("lr") || direction.startsWith("rl"))
                continue
            }
            if (!arrow.containsMatchIn(line)) {
                // Отдельное объявление узла: «A[Начало]».
                declare(line, labels)
                continue
            }
            val parts = line.split(arrow)
            var previous: String? = null
            var labelForNext = ""
            for ((index, part) in parts.withIndex()) {
                var text = part
                var label = labelForNext
                labelForNext = ""
                // Подпись «|да| B» сразу после стрелки.
                if (index > 0 && text.startsWith("|")) {
                    val end = text.indexOf('|', 1)
                    if (end > 0) {
                        label = text.substring(1, end).trim()
                        text = text.substring(end + 1)
                    }
                }
                // Подпись «A -- да -->» перед стрелкой.
                if (index < parts.size - 1 && text.contains(" -- ")) {
                    labelForNext = text.substringAfter(" -- ").trim()
                    text = text.substringBefore(" -- ")
                }
                val id = declare(text, labels)
                if (id.isEmpty()) { previous = null; continue }
                previous?.let { raw.add(Triple(it, id, label)) }
                previous = id
            }
        }
        val edges = raw.map { (from, to, label) -> MermaidGraph.Edge(labels[from] ?: from, labels[to] ?: to, label) }
        return MermaidGraph(vertical, edges)
    }

    /** Запомнить название узла и вернуть его идентификатор. */
    private fun declare(text: String, labels: HashMap<String, String>): String {
        val value = text.trim().trim('"', '\'', ' ')
        if (value.isEmpty()) return ""
        val match = node.find(value)
        if (match != null && match.groupValues[2].isNotEmpty()) {
            val id = match.groupValues[1].trim()
            val label = match.groupValues[3].trim().trim('"', '\'', ' ', '(', ')', '[', ']')
            if (id.isNotEmpty() && label.isNotEmpty()) labels[id] = label
            return id.ifEmpty { label }
        }
        return value
    }
}

/** Диаграмма Mermaid без веб-вью: цепочка — схемой со стрелками, граф — списком связей. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MermaidDiagramView(source: String, fontSize: Float) {
    val colors = HonerTheme.colors
    val graph = remember(source) { MermaidParser.parse(source) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.divider, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("message.diagram"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            graph.edges.isEmpty() -> Text(
                // Не удалось разобрать — показываем исходник, чтобы ничего не терялось.
                source, fontFamily = FontFamily.Monospace, fontSize = (fontSize * 0.82f).sp, color = colors.foreground,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            graph.isChain && graph.vertical -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DiagramNode(graph.edges.first().from, fontSize)
                graph.edges.forEach { edge ->
                    if (edge.label.isNotEmpty()) EdgeLabel(edge.label, fontSize)
                    Icon(Icons.Filled.ArrowDownward, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    DiagramNode(edge.to, fontSize)
                }
            }
            graph.isChain -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagramNode(graph.edges.first().from, fontSize)
                graph.edges.forEach { edge ->
                    EdgeArrow(edge.label, fontSize)
                    DiagramNode(edge.to, fontSize)
                }
            }
            else -> graph.edges.forEach { edge ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagramNode(edge.from, fontSize, Modifier.weight(1f, fill = false))
                    EdgeArrow(edge.label, fontSize)
                    DiagramNode(edge.to, fontSize, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun EdgeArrow(label: String, fontSize: Float) {
    val colors = HonerTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotEmpty()) EdgeLabel(label, fontSize)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.accent, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun EdgeLabel(label: String, fontSize: Float) {
    Text(label, fontSize = (fontSize * 0.72f).sp, color = HonerTheme.colors.secondary)
}

@Composable
private fun DiagramNode(title: String, fontSize: Float, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Text(
        title.ifEmpty { "?" },
        fontSize = (fontSize * 0.85f).sp,
        fontWeight = FontWeight.Medium,
        color = colors.foreground,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.raised)
            .border(0.6.dp, colors.divider, RoundedCornerShape(8.dp))
            .defaultMinSize(minHeight = 30.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
