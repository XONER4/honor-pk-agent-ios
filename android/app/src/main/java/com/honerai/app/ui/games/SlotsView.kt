package com.honerai.app.ui.games

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.honerai.app.AppContainer
import com.honerai.app.ui.games.engine.SlotsEngine
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

private const val PREFS = "honer.games"

/** Слоты «Удача» (порт SlotsGameView): барабаны с весами символов, ставки, баланс сохраняется. */
@Composable
internal fun SlotsGame(onResult: (String) -> Unit) {
    val t = rememberGameText()
    val colors = HonerTheme.colors
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val reduceMotion by container.settings.reduceMotion.collectAsState()
    val quick = reduceMotion || container.isLowEndDevice
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)

    var balance by remember { mutableIntStateOf(prefs.getInt(SlotsEngine.BALANCE_KEY, SlotsEngine.START_BALANCE)) }
    var bet by remember { mutableIntStateOf(50) }
    val strips = remember { mutableStateListOf(listOf("🍒", "💎", "🍋"), listOf("🍒", "💎", "🍋"), listOf("🍒", "💎", "🍋")) }
    val offsets = remember { List(3) { Animatable(0f) } }
    var spinning by remember { mutableStateOf(false) }
    var lastWin by remember { mutableStateOf<Int?>(null) }
    var bigWinGlow by remember { mutableStateOf(false) }

    fun saveBalance(value: Int) {
        balance = value
        prefs.edit().putInt(SlotsEngine.BALANCE_KEY, value).apply()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cell: Dp = min(86.dp, (maxWidth - 40.dp - 24.dp - 20.dp) / 3)
        val density = LocalDensity.current
        val cellPx = with(density) { cell.toPx() }

        fun spin() {
            if (spinning || balance < bet) return
            spinning = true
            lastWin = null
            bigWinGlow = false
            saveBalance(balance - bet)
            val line = List(3) { SlotsEngine.randomSymbol() }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            for (index in 0 until 3) {
                val visible = strips[index].takeLast(3)
                val fillers = List((if (quick) 9 else 18) + index * (if (quick) 4 else 7)) { SlotsEngine.randomSymbol() }
                strips[index] = visible + fillers + listOf(SlotsEngine.randomSymbol(), line[index], SlotsEngine.randomSymbol())
            }
            val stake = bet
            scope.launch {
                offsets.forEach { it.snapTo(0f) }
                val reels = (0 until 3).map { index ->
                    async {
                        val target = -(strips[index].size - 3) * cellPx
                        val duration = ((1400 + index * 450) * (if (quick) 0.6 else 1.0)).toInt()
                        offsets[index].animateTo(target, tween(duration, easing = CubicBezierEasing(0.15f, 0.75f, 0.25f, 1f)))
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                reels.awaitAll()
                val win = SlotsEngine.payout(line, stake)
                lastWin = win
                saveBalance(balance + win)
                if (SlotsEngine.isBigWin(win, stake)) {
                    bigWinGlow = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    result(t("🎰 Удача: выпало ${line.joinToString("")} — выигрыш $win монет! 🎉",
                        "🎰 Lucky: rolled ${line.joinToString("")} — you won $win coins! 🎉"))
                } else if (win > 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                delay(50)
                spinning = false
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("slots.balance")) {
                    Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = Color(1f, 0.75f, 0.2f))
                    Text("$balance", color = Color(1f, 0.75f, 0.2f), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(lastWin != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    val win = lastWin ?: 0
                    Text(if (win > 0) "+$win" else t("Мимо", "Miss"),
                        color = if (win > 0) Color(0.2f, 0.8f, 0.35f) else colors.secondary,
                        fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.testTag("slots.result"))
                }
            }
            val glow by animateFloatAsState(if (bigWinGlow) 1f else 0f, tween(400), label = "glow")
            val frame = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .shadow((20 * glow).dp, frame, ambientColor = Color.Yellow, spotColor = Color.Yellow)
                    .clip(frame)
                    .background(Brush.verticalGradient(listOf(Color(0.5f, 0.1f, 0.2f), Color(0.25f, 0.05f, 0.15f))))
                    .border((2 + 3 * glow).dp, Color.Yellow.copy(alpha = 0.35f + 0.6f * glow), frame)
                    .padding(12.dp)
                    .testTag("slots.reels"),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (index in 0 until 3) Reel(strips[index], offsets[index], cell, cellPx)
                }
                // Линия выигрыша.
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.Yellow.copy(alpha = 0.8f)))
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("slots.bet")) {
                SlotsEngine.bets.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = bet == value, onClick = { bet = value }, enabled = !spinning,
                        shape = SegmentedButtonDefaults.itemShape(index, SlotsEngine.bets.size), icon = {},
                    ) { Text("$value") }
                }
            }
            val canSpin = !spinning && balance >= bet
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(1f, 0.6f, 0.15f), Color(1f, 0.35f, 0.3f))))
                    .clickable(enabled = canSpin) { spin() }
                    .padding(vertical = 16.dp)
                    .testTag("slots.spin"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (spinning) t("Крутится…", "Spinning…") else t("Крутить", "Spin"),
                    color = Color.White.copy(alpha = if (canSpin || spinning) 1f else 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (balance < 10 && !spinning) {
                TextButton(onClick = { saveBalance(SlotsEngine.START_BALANCE) }, modifier = Modifier.testTag("slots.refill")) {
                    Text(t("Пополнить до 1000 монет", "Top up to 1000 coins"), color = colors.accent)
                }
            }
            Text(
                t("Три одинаковых: 💎 ×50 · 7️⃣ ×30 · ⭐️ ×15 · 🔔 ×10 · 🍀 ×8 · 🍋 ×5 · 🍒 ×3. Две вишни — ×2. Монеты игровые.",
                    "Three of a kind: 💎 ×50 · 7️⃣ ×30 · ⭐️ ×15 · 🔔 ×10 · 🍀 ×8 · 🍋 ×5 · 🍒 ×3. Two cherries — ×2. Play coins only."),
                color = colors.secondary, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Барабан: рисуются только видимые символы, сдвиг читается на этапе раскладки. */
@Composable
private fun Reel(strip: List<String>, offset: Animatable<Float, *>, cell: Dp, cellPx: Float) {
    Box(
        Modifier.size(cell, cell * 3)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0.97f, 0.97f, 0.97f), Color.White, Color(0.97f, 0.97f, 0.97f))))
            .clipToBounds(),
    ) {
        val value = offset.value
        val first = floor(-value / cellPx).toInt().coerceAtLeast(0)
        for (i in first until minOf(strip.size, first + 4)) {
            Box(
                Modifier.offset { IntOffset(0, (i * cellPx + offset.value).roundToInt()) }.size(cell),
                contentAlignment = Alignment.Center,
            ) {
                Text(strip[i], fontSize = with(LocalDensity.current) { (cellPx * 0.56f).toSp() })
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.35f), 0.33f to Color.Transparent, 0.67f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f),
        )))
    }
}
