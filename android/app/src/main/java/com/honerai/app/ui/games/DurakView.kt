package com.honerai.app.ui.games

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.ui.games.engine.DurakCard
import com.honerai.app.ui.games.engine.DurakGame
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** «Дурак» подкидной против Honer AI (порт DurakGameView). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DurakGameView(onResult: (String) -> Unit) {
    val t = rememberGameText()
    val english = t("ru", "en") == "en"
    val colors = HonerTheme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val reduceMotion by container.settings.reduceMotion.collectAsState()
    val animate = !reduceMotion && !container.isLowEndDevice
    var game by remember { mutableStateOf(DurakGame.create()) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val aiJob = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) { onDispose { aiJob[0]?.cancel() } }

    val status = game.outcome?.let {
        when (it) {
            DurakGame.Outcome.HUMAN_WON -> t("Вы выиграли! Honer AI — дурак 😄", "You won! Honer AI is the fool 😄")
            DurakGame.Outcome.AI_WON -> t("Выиграл Honer AI. Вы дурак 🙃", "Honer AI won. You're the fool 🙃")
            DurakGame.Outcome.DRAW -> t("Ничья", "Draw")
        }
    } ?: when {
        busy -> t("Honer AI ходит…", "Honer AI is playing…")
        game.attacker == DurakGame.Player.HUMAN -> when {
            game.defenderTakes -> t("Honer AI берёт — подкиньте или «Бери»", "Honer AI takes — throw in or «Take»")
            game.table.isEmpty() -> t("Ваш ход — атакуйте", "Your move — attack")
            game.allBeaten -> t("Отбито — подкиньте или «Бито»", "Beaten — throw in or «Done»")
            else -> t("Honer AI отбивается…", "Honer AI is defending…")
        }
        game.table.any { it.defense == null } -> t("Отбивайтесь или «Беру»", "Defend or «I take»")
        else -> t("Ход Honer AI", "Honer AI's move")
    }

    fun checkFinish() {
        val outcome = game.outcome ?: return
        if (finished) return
        finished = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        result(when (outcome) {
            DurakGame.Outcome.HUMAN_WON -> t("🃏 Дурак: вы выиграли, Honer AI остался в дураках! 😄", "🃏 Durak: you won, Honer AI is the fool! 😄")
            DurakGame.Outcome.AI_WON -> t("🃏 Дурак: выиграл Honer AI. Отыграемся?", "🃏 Durak: Honer AI won. Rematch?")
            DurakGame.Outcome.DRAW -> t("🃏 Дурак: ничья.", "🃏 Durak: a draw.")
        })
    }

    /** Соперник делает шаги с паузами, пока ход не перейдёт к человеку. */
    fun runAI() {
        if (busy || game.outcome != null) return
        busy = true
        aiJob[0] = scope.launch {
            try {
                var steps = 0
                while (steps < 12) {
                    delay(650)
                    if (game.outcome != null) return@launch
                    val copy = game.copy()
                    if (!copy.aiStep()) return@launch
                    game = copy
                    steps++
                    // После защиты или атаки соперника решение за человеком.
                    if (game.attacker == DurakGame.Player.HUMAN && !game.defenderTakes) return@launch
                    if (game.attacker == DurakGame.Player.AI && game.table.any { it.defense == null } && !game.defenderTakes) return@launch
                }
            } finally {
                busy = false
                checkFinish()
            }
        }
    }

    fun afterHuman() {
        checkFinish()
        runAI()
    }

    fun isPlayable(card: DurakCard): Boolean {
        if (busy || game.outcome != null) return false
        if (game.attacker == DurakGame.Player.HUMAN) return game.canAttack(card, DurakGame.Player.HUMAN)
        if (game.defenderTakes) return false
        val open = game.table.firstOrNull { it.defense == null } ?: return false
        return game.beats(card, open.attack) || game.table.any { it.defense == null && game.beats(card, it.attack) }
    }

    fun play(card: DurakCard) {
        if (!isPlayable(card)) return
        val copy = game.copy()
        if (copy.attacker == DurakGame.Player.HUMAN) {
            copy.attack(card, DurakGame.Player.HUMAN)
        } else {
            val target = copy.table.firstOrNull { it.defense == null && copy.beats(card, it.attack) } ?: return
            copy.defend(target.attack, card)
        }
        game = copy
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        afterHuman()
    }

    fun action(block: (DurakGame) -> Unit) {
        val copy = game.copy()
        block(copy)
        game = copy
        afterHuman()
    }

    fun reset() {
        aiJob[0]?.cancel()
        game = DurakGame.create()
        finished = false
        busy = false
        if (game.attacker == DurakGame.Player.AI) runAI()
    }

    LaunchedEffect(Unit) { if (game.attacker == DurakGame.Player.AI) runAI() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GameStatusBar(status, busy)
        // Карты соперника рубашкой вверх.
        Row(
            Modifier.height(70.dp).animateContentSize().testTag("durak.ai.hand"),
            horizontalArrangement = Arrangement.spacedBy((-22).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (card in game.hand(DurakGame.Player.AI)) key(card.id) { CardBack(Modifier.size(46.dp, 66.dp)) }
        }
        // Колода и козырь.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                if (game.deck.isNotEmpty()) {
                    PlayingCard(game.trump, 54.dp, english, Modifier.offset(x = 14.dp).rotate(90f))
                    if (game.deck.size > 1) CardBack(Modifier.size(54.dp, 78.dp))
                } else {
                    Text(game.trumpSuit.symbol + "︎", fontSize = 40.sp,
                        color = if (game.trumpSuit.isRed) Color(0.85f, 0.1f, 0.15f) else colors.foreground)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("Козырь ", "Trump ") + game.trumpSuit.symbol, color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(t("В колоде: ", "In deck: ") + game.deck.size, color = colors.secondary, fontSize = 13.sp)
                Text(t("Бито: ", "Discarded: ") + game.discard.size, color = colors.secondary, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
        }
        // Стол.
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 120.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0.1f, 0.4f, 0.25f).copy(alpha = 0.85f))
                .padding(10.dp)
                .animateContentSize()
                .testTag("durak.table"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (pair in game.table) {
                key(pair.id) {
                    Box(Modifier.size(80.dp, 104.dp)) {
                        PopIn(pair.attack.id, animate) { PlayingCard(pair.attack, 58.dp, english) }
                        val defense = pair.defense
                        if (defense != null) {
                            Box(Modifier.offset(x = 14.dp, y = 16.dp).rotate(12f)) {
                                PopIn(defense.id, animate) { PlayingCard(defense, 58.dp, english) }
                            }
                        }
                    }
                }
            }
        }
        // Кнопки.
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (game.attacker == DurakGame.Player.HUMAN && game.allBeaten && !game.defenderTakes) {
                GameButton(t("Бито", "Done"), "durak.done", prominent = true, enabled = !busy) { action { it.finishRound() } }
            }
            if (game.attacker == DurakGame.Player.HUMAN && game.defenderTakes) {
                GameButton(t("Бери", "Take them"), "durak.give", prominent = true, enabled = !busy) { action { it.finishRound() } }
            }
            if (game.attacker == DurakGame.Player.AI && game.table.any { it.defense == null } && !game.defenderTakes) {
                GameButton(t("Беру", "I take"), "durak.take", prominent = true, enabled = !busy) { action { it.declareTake() } }
            }
            Spacer(Modifier.weight(1f))
            GameButton(t("Заново", "Restart"), "game.new", onClick = ::reset)
        }
        // Мои карты.
        LazyRow(
            Modifier.fillMaxWidth().height(112.dp).testTag("durak.hand"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy((-14).dp),
        ) {
            items(game.hand(DurakGame.Player.HUMAN), key = { it.id }) { card ->
                val playable = isPlayable(card)
                val lift by animateDpAsState(if (playable) (-8).dp else 0.dp, label = "lift")
                val alpha by animateFloatAsState(if (playable || game.outcome != null) 1f else 0.55f, label = "alpha")
                PlayingCard(
                    card, 64.dp, english,
                    Modifier
                        .then(if (animate) Modifier.animateItem() else Modifier)
                        .offset(y = lift)
                        .graphicsLayer { this.alpha = alpha }
                        .clickable { play(card) }
                        .testTag("durak.card.${card.id}"),
                )
            }
        }
    }
}

/** Появление карты на столе: лёгкое увеличение из меньшего размера. */
@Composable
private fun PopIn(key: Int, animate: Boolean, content: @Composable () -> Unit) {
    val progress = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) { if (animate) progress.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 420f)) }
    Box(Modifier.graphicsLayer {
        val p = progress.value
        scaleX = 0.7f + 0.3f * p
        scaleY = 0.7f + 0.3f * p
        this.alpha = p.coerceIn(0f, 1f)
    }) { content() }
}

/** Игральная карта (как PlayingCardView). */
@Composable
internal fun PlayingCard(card: DurakCard, width: Dp, english: Boolean, modifier: Modifier = Modifier) {
    val color = if (card.suit.isRed) Color(0.85f, 0.1f, 0.15f) else Color.Black
    val shape = RoundedCornerShape(width * 0.12f)
    val unit = width.value
    val rank = card.rankTitle(english)
    val suit = card.suit.symbol + "︎"
    Box(
        modifier
            .size(width, width * 1.42f)
            .shadow(3.dp, shape)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = 0.2f), shape)
            .semantics { contentDescription = "$rank ${card.suit.symbol}" },
    ) {
        Column(Modifier.padding(width * 0.08f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-4).dp)) {
            Text(rank, color = color, fontSize = (unit * 0.26f).sp, fontWeight = FontWeight.Bold, lineHeight = (unit * 0.28f).sp)
            Text(suit, color = color, fontSize = (unit * 0.22f).sp, lineHeight = (unit * 0.24f).sp)
        }
        Text(suit, color = color, fontSize = (unit * 0.5f).sp, lineHeight = (unit * 0.52f).sp,
            modifier = Modifier.align(Alignment.Center).offset(x = width * 0.1f, y = width * 0.14f))
    }
}

/** Рубашка карты с фирменной отметкой. */
@Composable
internal fun CardBack(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .shadow(2.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0.25f, 0.4f, 0.95f), Color(0.45f, 0.25f, 0.85f))))
            .padding(4.dp)
            .border(1.2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("H", color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}
