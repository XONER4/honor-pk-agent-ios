package com.honerai.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.core.SearchEngine
import com.honerai.app.data.WebSource
import com.honerai.app.ui.theme.HonerTheme

// media: маленькие значки поисковиков, которые нашли источник («Яндекс», «Google», «Bing», «DDG»).

/** Значки поисковиков одного источника. */
@Composable
fun SearchEngineChips(engines: List<String>?, english: Boolean, modifier: Modifier = Modifier) {
    val list = remember(engines) { engines.orEmpty().mapNotNull { SearchEngine.from(it) }.sortedBy { it.ordinal } }
    if (list.isEmpty()) return
    val description = (if (english) "Found by: " else "Нашли: ") + list.joinToString(", ") { it.title(english) }
    Row(
        modifier.semantics { contentDescription = description }.testTag("sources.engines"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        list.forEach { engine -> EngineChip(engine, english) }
    }
}

/** Все поисковики, нашедшие хотя бы один источник ответа. */
@Composable
fun SearchEngineSummaryChips(sources: List<WebSource>, english: Boolean, modifier: Modifier = Modifier) {
    val engines = remember(sources) { sources.flatMap { it.engines.orEmpty() }.distinct() }
    SearchEngineChips(engines, english, modifier)
}

@Composable
private fun EngineChip(engine: SearchEngine, english: Boolean) {
    val colors = HonerTheme.colors
    val tint = when (engine) {
        SearchEngine.YANDEX -> Color(0xFFFC3F1D)
        SearchEngine.GOOGLE -> Color(0xFF4285F4)
        SearchEngine.BING -> Color(0xFF00A4A6)
        SearchEngine.DUCKDUCKGO -> Color(0xFFDE5833)
        SearchEngine.BRAVE -> Color(0xFFFB542B)
        SearchEngine.WIKIPEDIA -> colors.secondary
    }
    Text(
        engine.title(english),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
