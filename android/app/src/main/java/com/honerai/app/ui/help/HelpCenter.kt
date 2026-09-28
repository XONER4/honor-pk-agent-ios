package com.honerai.app.ui.help

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.honerai.app.BuildConfig
import com.honerai.app.R
import com.honerai.app.ui.settings.SettingsTopBar
import com.honerai.app.ui.settings.appContainer
import com.honerai.app.ui.settings.appSettings
import com.honerai.app.ui.settings.rememberReduceMotion
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

// Руководство Honer AI (порт HelpCenter.swift): поиск, категории, статьи, лайфхаки,
// вопросы и ответы, скриншоты и анимированные мини-ролики.

/** Руководство Honer AI: статьи, вопросы-ответы, лайфхаки, мини-ролики. */
@Composable
fun HelpCenterScreen(onBack: () -> Unit) {
    val settings = appSettings()
    val language by settings.language.collectAsState()
    val english = language == "en"
    val reduceMotion = rememberReduceMotion()
    // Состояние главной страницы живёт здесь, чтобы сохраниться при переходе в статью и обратно.
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(HelpLibrary.allCategory) }
    val homeListState = rememberLazyListState()
    val stack = remember { mutableStateListOf<String>() }
    var viewer by remember { mutableStateOf<String?>(null) }
    var forward by remember { mutableStateOf(true) }
    val open: (String) -> Unit = { forward = true; stack.add(it) }
    val close: () -> Unit = { forward = false; if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    BackHandler {
        when {
            viewer != null -> viewer = null
            stack.isNotEmpty() -> close()
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(HonerTheme.colors.background)) {
        AnimatedContent(
            targetState = stack.lastOrNull(),
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                else {
                    (slideInHorizontally(tween(280)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(280)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
                }
            },
            label = "help.nav",
        ) { articleId ->
            val article = articleId?.let { HelpLibrary.article(it) }
            if (article == null) {
                HelpHome(
                    english = english, query = query, onQuery = { query = it }, category = category,
                    onCategory = { category = it }, listState = homeListState,
                    onBack = onBack, onOpen = open, onZoom = { viewer = it },
                )
            } else {
                HelpArticlePage(
                    article = article, english = english,
                    onBack = close, onOpen = open, onZoom = { viewer = it },
                )
            }
        }
    }
    viewer?.let { name -> HelpImageViewer(name) { viewer = null } }
}

// MARK: - Главная

@Composable
private fun HelpHome(
    english: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onZoom: (String) -> Unit,
) {
    val settings = appSettings()
    val trimmed = query.trim()
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(settings.text("Руководство Honer AI", "Honer AI Guide"), onBack, backLabel = settings.text("Назад", "Back"))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("help.page"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            ) {
                item(key = "hero") { HelpHeroHeader(english) }
                item(key = "search") {
                    HelpSearchField(
                        text = query, onText = onQuery,
                        placeholder = settings.text("Поиск: таблицы, память, фон…", "Search: tables, memory, background…"),
                        onDone = { focus.clearFocus() },
                    )
                }
                if (trimmed.isEmpty()) {
                    item(key = "chips") { HelpCategoryBar(category, english, onCategory) }
                    browseContent(category, english, onOpen, onZoom)
                } else {
                    searchResults(trimmed, english, onOpen, onZoom)
                }
                item(key = "bottom") { Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) }
            }
        }
    }
}

private fun LazyListScope.browseContent(category: String, english: Boolean, onOpen: (String) -> Unit, onZoom: (String) -> Unit) {
    val sections = if (category == HelpLibrary.allCategory) HelpLibrary.sections else HelpLibrary.sections.filter { it.id == category }
    for (section in sections) {
        item(key = "section.${section.id}") {
            HelpBlockHeader(section.symbol, section.tint.color(), section.title(english), section.articles.size, Modifier.padding(top = 26.dp).testTag("help.section.${section.id}"))
        }
        items(section.articles, key = { "a.${section.id}.${it.id}" }) { article ->
            HelpArticleCard(article, english, Modifier.padding(top = 10.dp)) { onOpen(article.id) }
        }
    }
    if (category == HelpLibrary.allCategory || category == HelpLibrary.lifehacksCategory) {
        lifehackBlock(HelpLibrary.lifehacks, english, "browse")
    }
    if (category == HelpLibrary.allCategory || category == HelpLibrary.faqCategory) {
        faqBlock(HelpLibrary.faqEntries(""), english, "browse", onZoom)
    }
}

private fun LazyListScope.searchResults(query: String, english: Boolean, onOpen: (String) -> Unit, onZoom: (String) -> Unit) {
    val articles = HelpLibrary.search(query)
    val faqs = HelpLibrary.faqEntries(query)
    val hacks = HelpLibrary.searchLifehacks(query)
    if (articles.isEmpty() && faqs.isEmpty() && hacks.isEmpty()) {
        item(key = "empty") { HelpEmptyResults(query, english) }
        return
    }
    if (articles.isNotEmpty()) {
        item(key = "results.header") {
            HelpBlockHeader("doc.text.magnifyingglass", HonerTheme.colors.accent, if (english) "Articles" else "Статьи", articles.size, Modifier.padding(top = 22.dp))
        }
        items(articles, key = { "r.${it.id}" }) { article ->
            HelpArticleCard(article, english, Modifier.padding(top = 10.dp)) { onOpen(article.id) }
        }
    }
    if (hacks.isNotEmpty()) lifehackBlock(hacks, english, "search")
    if (faqs.isNotEmpty()) faqBlock(faqs, english, "search", onZoom)
}

private fun LazyListScope.lifehackBlock(items: List<HelpLifehack>, english: Boolean, scope: String) {
    item(key = "$scope.hacks.header") {
        HelpBlockHeader("lightbulb", HelpTint.yellow.color(), if (english) "Tips & tricks" else "Лайфхаки", items.size, Modifier.padding(top = 28.dp).testTag("help.lifehacks"))
    }
    items(items, key = { "$scope.hack.${it.id}" }) { hack -> HelpLifehackCard(hack, english, Modifier.padding(top = 10.dp)) }
}

private fun LazyListScope.faqBlock(entries: List<HelpIndexedFAQ>, english: Boolean, scope: String, onZoom: (String) -> Unit) {
    item(key = "$scope.faq.header") {
        HelpBlockHeader("questionmark.circle", HelpTint.orange.color(), if (english) "Questions & answers" else "Вопросы и ответы", entries.size, Modifier.padding(top = 28.dp, bottom = 10.dp).testTag("help.faq"))
    }
    items(entries, key = { "$scope.faq.${it.index}" }) { entry ->
        HelpFAQRow(entry, english, first = entry.index == entries.first().index, last = entry.index == entries.last().index, onZoom = onZoom)
    }
}

// MARK: - Шапка

@Composable
private fun HelpHeroHeader(english: Boolean) {
    val reduceMotion = rememberReduceMotion()
    val lowEnd = appContainer().isLowEndDevice
    var phase by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    if (!reduceMotion && visible) {
        LaunchedEffect(Unit) {
            // Продолжаем с того же места, где фон остановился.
            val base = phase
            val start = withFrameNanos { it }
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    // Фон медленный: 30 кадров в секунду достаточно (на слабых — 20).
                    if (now - last >= if (lowEnd) 50_000_000L else 33_000_000L) {
                        last = now
                        phase = base + ((now - start) / 1e9).toFloat()
                    }
                }
            }
        }
    }
    val version = BuildConfig.VERSION_NAME
    Box(
        Modifier.fillMaxWidth().heightIn(min = 236.dp)
            .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = HonerTheme.colors.accent, spotColor = HonerTheme.colors.accent)
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
            .onVisibilityChanged { visible = it }
            .drawBehind { drawHeroGradient(phase) }
            .testTag("help.hero"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Box(
                Modifier.size(86.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.honer_logo), contentDescription = null, modifier = Modifier.size(54.dp))
            }
            Text("Honer AI", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text(
                if (english) "Guide · version $version" else "Руководство · версия $version",
                color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(
                if (english) "Everything your AI assistant can do — with pictures, mini-videos and tips."
                else "Всё, что умеет ваш ИИ-помощник, — с картинками, мини-видео и лайфхаками.",
                color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            )
        }
    }
}

private val heroColors = listOf(Color(0xFF385CF2), Color(0xFF8554F5), Color(0xFF21ADED))

/** Переливающийся градиент с двумя мягкими пятнами (без размытия — дёшево на слабых телефонах). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeroGradient(phase: Float) {
    val angle = phase * 0.35f
    val w = size.width
    val h = size.height
    val start = Offset(w * (0.5f + 0.5f * cos(angle)), h * (0.5f + 0.5f * sin(angle)))
    val end = Offset(w * (0.5f - 0.5f * cos(angle)), h * (0.5f - 0.5f * sin(angle)))
    drawRect(Brush.linearGradient(heroColors, start, end))
    val d = density
    fun blob(color: Color, dx: Float, dy: Float) {
        val center = Offset(w / 2 + dx * d, h / 2 + dy * d)
        val radius = 135f * d
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0f)), center, radius), radius, center)
    }
    blob(Color(0xFF59E6FF), 110 * cos(phase * 0.5f), -60 + 30 * sin(phase * 0.7f))
    blob(Color(0xFFF273E6), -120 + 40 * sin(phase * 0.4f), 70 * cos(phase * 0.6f))
}

// MARK: - Поиск и категории

@Composable
private fun HelpSearchField(text: String, onText: (String) -> Unit, placeholder: String, onDone: () -> Unit) {
    val colors = HonerTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
            .border(if (focused) 1.4.dp else 0.8.dp, if (focused) colors.accent else colors.divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.secondary)
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(placeholder, color = colors.secondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = text, onValueChange = onText, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.foreground, fontSize = 17.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("help.search"),
            )
        }
        if (text.isNotEmpty()) {
            Icon(
                Icons.Filled.Cancel, contentDescription = "Clear", tint = colors.secondary,
                modifier = Modifier.clip(CircleShape).clickable { onText("") }.testTag("help.search.clear"),
            )
        }
    }
}

@Composable
private fun HelpCategoryBar(selected: String, english: Boolean, onSelect: (String) -> Unit) {
    val colors = HonerTheme.colors
    LazyRow(
        Modifier.padding(top = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(HelpLibrary.chips, key = { it.id }) { chip ->
            val isSelected = chip.id == selected
            Row(
                Modifier.clip(CircleShape)
                    .background(if (isSelected) colors.accent else colors.surface)
                    .border(0.8.dp, if (isSelected) Color.Transparent else colors.divider, CircleShape)
                    .clickable(role = Role.Tab) { onSelect(chip.id) }
                    .padding(horizontal = 13.dp, vertical = 8.dp)
                    .testTag("help.chip.${chip.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint = if (isSelected) Color.White else colors.foreground
                Icon(helpIcon(chip.symbol), null, tint = tint, modifier = Modifier.size(15.dp))
                Text(chip.title(english), color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HelpEmptyResults(query: String, english: Boolean) {
    val colors = HonerTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp).testTag("help.search.empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(helpIcon("doc.text.magnifyingglass"), null, tint = colors.secondary, modifier = Modifier.size(38.dp))
        Text(
            if (english) "Nothing found for “$query”" else "По запросу «$query» ничего не найдено",
            color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Text(
            if (english) "Try a different word — for example “photo”, “memory” or “table”."
            else "Попробуйте другое слово — например «фото», «память» или «таблица».",
            color = colors.secondary, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Списки

@Composable
private fun HelpBlockHeader(symbol: String, tint: Color, title: String, count: Int, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HelpIconBadge(symbol, tint, 30.dp)
        Text(title, color = colors.foreground, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            "$count", color = colors.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(colors.raised).padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun HelpIconBadge(symbol: String, tint: Color, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.18f)).border(0.8.dp, tint.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(helpIcon(symbol), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun HelpArticleCard(article: HelpArticle, english: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface)
            .border(0.7.dp, colors.divider, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp)
            .testTag("help.article.${article.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HelpIconBadge(article.symbol, article.tint.color(), 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(article.title(english), color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(article.summary(english), color = colors.secondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (article.demo != null) Icon(Icons.Filled.PlayCircle, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            if (article.screenshot != null) Icon(Icons.Outlined.PhoneAndroid, null, tint = colors.secondary, modifier = Modifier.size(16.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HelpLifehackCard(hack: HelpLifehack, english: Boolean, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    val tint = hack.tint.color()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = 0.08f))
            .border(0.8.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(14.dp)
            .testTag("help.lifehack.${hack.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HelpIconBadge(hack.symbol, tint, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("💡 " + hack.title(english), color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(hack.text(english), color = colors.secondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun HelpFAQRow(entry: HelpIndexedFAQ, english: Boolean, first: Boolean, last: Boolean, onZoom: (String) -> Unit) {
    val colors = HonerTheme.colors
    var expanded by rememberSaveable(entry.index) { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, if (reduceMotion) tween(0) else spring(0.86f, 400f), label = "faq.chevron")
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp, bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(16.dp).testTag("help.faq.${entry.index}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(entry.item.question(english), color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.KeyboardArrowDown, null, tint = if (expanded) colors.accent else colors.secondary,
                modifier = Modifier.padding(top = 1.dp).size(20.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) fadeIn(tween(0)) else expandVertically(spring(0.86f, 400f)) + fadeIn(),
            exit = if (reduceMotion) fadeOut(tween(0)) else shrinkVertically(spring(0.86f, 400f)) + fadeOut(),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(helpRichText(entry.item.answer(english)), color = colors.secondary, fontSize = 15.sp, lineHeight = 21.sp)
                entry.item.screenshot?.let { HelpScreenshotFrame(it, 190.dp, onZoom) }
                entry.item.demo?.let { HelpDemoView(it, english) }
            }
        }
        if (!last) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.7.dp).background(colors.divider))
    }
}

// MARK: - Статья

@Composable
private fun HelpArticlePage(article: HelpArticle, english: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit, onZoom: (String) -> Unit) {
    val colors = HonerTheme.colors
    val settings = appSettings()
    val fontScale by settings.fontScale.collectAsState()
    val scale = fontScale.toFloat()
    val section = remember(article.id) { HelpLibrary.section(article.id) }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(article.title(english), onBack, backLabel = settings.text("Назад", "Back"))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("help.articlePage.${article.id}"),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HelpIconBadge(article.symbol, article.tint.color(), 58.dp)
                            section?.let {
                                Text(it.title(english).uppercase(), color = article.tint.color(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(article.title(english), color = colors.foreground, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                        Text(article.summary(english), color = colors.secondary, fontSize = 17.sp, lineHeight = 23.sp)
                    }
                }
                article.screenshot?.let { shot -> item(key = "shot") { HelpScreenshotFrame(shot, 250.dp, onZoom) } }
                article.demo?.let { demo -> item(key = "demo") { HelpDemoView(demo, english) } }
                item(key = "body") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        article.paragraphs(english).forEach {
                            Text(helpRichText(it), color = colors.foreground, fontSize = (17 * scale).sp, lineHeight = (24 * scale).sp)
                        }
                    }
                }
                val steps = article.steps(english)
                if (steps.isNotEmpty()) item(key = "steps") { HelpStepsBlock(if (english) "How to do it" else "Как это сделать", steps, scale) }
                val tips = article.tips(english)
                if (tips.isNotEmpty()) item(key = "tips") { HelpTipsBlock(if (english) "Tips & tricks" else "Лайфхаки", tips, scale) }
                if (article.related.isNotEmpty()) {
                    item(key = "related") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HelpSubheading("link", if (english) "Related" else "Смотрите также", colors.accent)
                            article.related.mapNotNull { HelpLibrary.article(it) }.forEach { related ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface)
                                        .clickable(role = Role.Button) { onOpen(related.id) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("help.related.${related.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    HelpIconBadge(related.symbol, related.tint.color(), 32.dp)
                                    Text(related.title(english), color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                item(key = "bottom") { Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) }
            }
        }
    }
}

@Composable
private fun HelpSubheading(symbol: String, title: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(helpIcon(symbol), null, tint = tint, modifier = Modifier.size(18.dp))
        Text(title, color = HonerTheme.colors.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

private val stepGradient = Brush.linearGradient(listOf(Color(0xFF4D8CFF), Color(0xFF8C66FF)))

@Composable
private fun HelpStepsBlock(title: String, steps: List<String>, scale: Float) {
    val colors = HonerTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("help.steps")) {
        HelpSubheading("list.number", title, colors.accent)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface)
                .border(0.7.dp, colors.divider, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            steps.forEachIndexed { index, text ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(stepGradient), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(helpRichText(text), color = colors.foreground, fontSize = (16 * scale).sp, lineHeight = (22 * scale).sp, modifier = Modifier.padding(top = 3.dp).weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HelpTipsBlock(title: String, tips: List<String>, scale: Float) {
    val colors = HonerTheme.colors
    val yellow = HelpTint.yellow.color()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("help.tips")) {
        HelpSubheading("lightbulb", title, yellow)
        tips.forEach { tip ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(yellow.copy(alpha = 0.10f))
                    .border(0.8.dp, yellow.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("💡", fontSize = 18.sp)
                Text(helpRichText(tip), color = colors.foreground, fontSize = (15 * scale).sp, lineHeight = (21 * scale).sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Скриншоты

/** Скриншоты из assets/guide: декодируются вне главного потока в нужном размере и кэшируются. */
internal object HelpImages {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(name: String, targetWidth: Int): Bitmap? = cache.get("$name@$targetWidth")

    suspend fun load(context: android.content.Context, name: String, targetWidth: Int): Bitmap? {
        val key = "$name@$targetWidth"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val path = "guide/$name.jpg"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}

/** Скриншот в рамке телефона. Нажатие открывает полноэкранный просмотр с зумом. */
@Composable
private fun HelpScreenshotFrame(name: String, width: Dp, onZoom: (String) -> Unit) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { width.roundToPx() }
    val bitmap by produceState(HelpImages.cached(name, targetPx), name, targetPx) {
        if (value == null) value = HelpImages.load(context, name, targetPx)
    }
    val height = width * (1169f / 540f)
    val outer = RoundedCornerShape(width * 0.13f)
    val inner = RoundedCornerShape(width * 0.105f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(width).height(height + width * 0.056f)
                .shadow(18.dp, outer)
                .clip(outer).background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.16f), outer)
                .clickable(role = Role.Image) { if (bitmap != null) onZoom(name) }
                .padding(width * 0.028f)
                .testTag("help.screenshot"),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) {
                Image(image.asImageBitmap(), contentDescription = "Screenshot", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(inner))
            } else {
                Box(Modifier.fillMaxSize().clip(inner).background(HonerTheme.colors.surface), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = HonerTheme.colors.accent, strokeWidth = 2.dp)
                }
            }
            Icon(
                Icons.Filled.OpenInFull, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(8.dp).size(12.dp),
            )
        }
    }
}

/** Полноэкранный просмотр скриншота: щипок — зум, двойное касание — приблизить/отдалить. */
@Composable
private fun HelpImageViewer(name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, name) { value = HelpImages.load(context, name, 1080) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("help.screenshot.viewer")) {
            bitmap?.let { image ->
                Image(
                    image.asImageBitmap(), contentDescription = "Screenshot", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInputZoom(
                            onTransform = { zoom, pan ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            },
                            onDoubleTap = { tap ->
                                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else { scale = 2.6f; offset = -tap * 1.6f }
                            },
                        )
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 14.dp, end = 16.dp)
                    .size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onClose)
                    .testTag("help.screenshot.close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
        }
    }
}

private fun Modifier.pointerInputZoom(onTransform: (Float, Offset) -> Unit, onDoubleTap: (Offset) -> Unit): Modifier =
    this.pointerInput("zoom") { detectTransformGestures { _, pan, zoom, _ -> onTransform(zoom, pan) } }
        .pointerInput("tap") {
            detectTapGestures(onDoubleTap = { point -> onDoubleTap(Offset(point.x - size.width / 2f, point.y - size.height / 2f)) })
        }
