package com.honerai.app.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.device.ParentalControl
import com.honerai.app.ui.theme.HonerTheme

/** Мини-игры с Honer AI (как GameKind на iOS). */
enum class GameKind(
    val raw: String,
    val titleRu: String, val titleEn: String,
    val subtitleRu: String, val subtitleEn: String,
    val colors: List<Color>,
) {
    CHESS("chess", "Шахматы", "Chess", "Партия против Honer AI", "A match against Honer AI",
        listOf(Color(0.35f, 0.45f, 1f), Color(0.6f, 0.35f, 1f))),
    CHECKERS("checkers", "Шашки", "Checkers", "Русские шашки", "Russian draughts",
        listOf(Color(0.95f, 0.45f, 0.3f), Color(0.85f, 0.25f, 0.45f))),
    DURAK("durak", "Дурак", "Durak", "Подкидной, 36 карт", "Throw-in, 36 cards",
        listOf(Color(0.15f, 0.7f, 0.5f), Color(0.1f, 0.5f, 0.75f))),
    SLOTS("slots", "Удача", "Lucky", "Крутите барабаны", "Spin the reels",
        listOf(Color(1f, 0.7f, 0.2f), Color(1f, 0.4f, 0.4f)));

    companion object {
        /** Разбор названия игры, которое прислала модель. */
        fun from(raw: String): GameKind? {
            val value = raw.lowercase()
            return when {
                "chess" in value || "шахмат" in value -> CHESS
                "checker" in value || "draught" in value || "шашк" in value -> CHECKERS
                "durak" in value || "дурак" in value || "карт" in value -> DURAK
                "slot" in value || "казино" in value || "удач" in value || "рулет" in value -> SLOTS
                else -> null
            }
        }
    }
}

/** Язык интерфейса игр: t(русский, английский). */
@Composable
internal fun rememberGameText(): (String, String) -> String {
    val context = LocalContext.current
    val settings = remember { AppContainer.get(context).settings }
    val language by settings.language.collectAsState()
    val english = language == "en"
    return remember(english) { { ru: String, en: String -> if (english) en else ru } }
}

@Composable
private fun GameIcon(kind: GameKind) {
    when (kind) {
        GameKind.CHESS -> Text("♚︎", color = Color.White, fontSize = 30.sp)
        GameKind.CHECKERS -> Icon(Icons.Filled.GridView, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        GameKind.DURAK -> Text("♠︎", color = Color.White, fontSize = 30.sp)
        GameKind.SLOTS -> Icon(Icons.Filled.Casino, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

/** Витрина игр. [onPlay] получает "chess", "checkers", "durak" или "slots". */
@Composable
fun GameHub(onPlay: (String) -> Unit, onClose: () -> Unit) {
    val t = rememberGameText()
    val colors = HonerTheme.colors
    val kinds = remember { GameKind.entries.filter { ParentalControl.isGameAllowed(it.raw) } }
    BackHandler { onClose() }
    Column(
        Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing).testTag("games.hub"),
    ) {
        GameTopBar(t("Игры с Honer AI", "Games with Honer AI"), t("Закрыть", "Close"), "games.close", onClose)
        if (kinds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(t("Игры отключены родительским контролем", "Games are turned off by parental controls"),
                    color = colors.secondary, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 158.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(kinds, key = { it.raw }) { kind ->
                val shape = RoundedCornerShape(22.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp)
                        .shadow(10.dp, shape, ambientColor = kind.colors[0], spotColor = kind.colors[0])
                        .clip(shape)
                        .background(Brush.linearGradient(kind.colors))
                        .clickable { onPlay(kind.raw) }
                        .padding(16.dp)
                        .testTag("games.play." + kind.raw),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GameIcon(kind)
                    Spacer(Modifier.weight(1f))
                    Text(t(kind.titleRu, kind.titleEn), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(t(kind.subtitleRu, kind.subtitleEn), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
        }
    }
}

/** Экран игры на весь экран. [onResult] — итог партии уходит в чат. */
@Composable
fun GameScreen(kind: String, onResult: (String) -> Unit, onClose: () -> Unit) {
    val t = rememberGameText()
    val colors = HonerTheme.colors
    val game = remember(kind) { GameKind.from(kind) }
    BackHandler { onClose() }
    Column(
        Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("game.screen." + (game?.raw ?: kind)),
    ) {
        GameTopBar(game?.let { t(it.titleRu, it.titleEn) } ?: t("Игра", "Game"), t("Закрыть", "Close"), "game.close", onClose)
        Box(Modifier.fillMaxSize()) {
            when {
                game == null -> Message(t("Такой игры нет. Доступны: шахматы, шашки, дурак, «Удача».",
                    "Unknown game. Available: chess, checkers, durak, slots."))
                !ParentalControl.isGameAllowed(game.raw) -> Message(t("Эта игра отключена родительским контролем",
                    "This game is turned off by parental controls"))
                else -> when (game) {
                    GameKind.CHESS -> ChessGame(onResult)
                    GameKind.CHECKERS -> CheckersGame(onResult)
                    GameKind.DURAK -> DurakGameView(onResult)
                    GameKind.SLOTS -> SlotsGame(onResult)
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = HonerTheme.colors.secondary, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GameTopBar(title: String, close: String, closeTag: String, onClose: () -> Unit) {
    val colors = HonerTheme.colors
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).testTag(closeTag)) {
            Text(close, color = colors.accent, fontSize = 16.sp)
        }
        Text(title, color = colors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center))
    }
}

/** Строка состояния партии (как GameStatusBar на iOS). */
@Composable
internal fun GameStatusBar(text: String, thinking: Boolean, modifier: Modifier = Modifier) {
    val colors = HonerTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .border(0.7.dp, colors.divider, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag("game.status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thinking) CircularProgressIndicator(Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
        AnimatedContent(targetState = text, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "status") { value ->
            Text(value, color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

/** Кнопка «Новая игра» и подобные (как .bordered на iOS). */
@Composable
internal fun GameButton(title: String, tag: String, modifier: Modifier = Modifier, prominent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = HonerTheme.colors
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (prominent) colors.accent.copy(alpha = if (enabled) 1f else 0.5f) else colors.raised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (prominent) Color.White else colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
