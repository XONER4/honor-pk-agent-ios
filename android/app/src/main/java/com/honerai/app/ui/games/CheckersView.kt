package com.honerai.app.ui.games

import android.os.SystemClock
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.honerai.app.ui.games.engine.CheckersAI
import com.honerai.app.ui.games.engine.CheckersBoard
import com.honerai.app.ui.theme.HonerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Русские шашки против Honer AI (порт CheckersGameView). */
@Composable
internal fun CheckersGame(onResult: (String) -> Unit) {
    val t = rememberGameText()
    val colors = HonerTheme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    var board by remember { mutableStateOf(CheckersBoard.initial()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var lastPath by remember { mutableStateOf<List<Int>>(emptyList()) }
    val aiJob = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) { onDispose { aiJob[0]?.cancel() } }

    val moves = remember(board) { if (board.turn == CheckersBoard.Side.WHITE) board.legalMoves() else emptyList() }
    val winner = remember(board) { board.winner }
    val targets = remember(moves, selected) { moves.filter { it.from == selected }.map { it.to }.toSet() }

    val status = when {
        winner != null -> if (winner == CheckersBoard.Side.WHITE) t("Вы победили! 🎉", "You won! 🎉") else t("Победил Honer AI", "Honer AI won")
        thinking -> t("Honer AI думает…", "Honer AI is thinking…")
        moves.any { it.captured.isNotEmpty() } -> t("Ваш ход — бить обязательно", "Your move — capturing is mandatory")
        else -> t("Ваш ход", "Your move")
    }

    fun checkFinish(position: CheckersBoard) {
        if (finished) return
        val win = position.winner ?: return
        finished = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        result(
            if (win == CheckersBoard.Side.WHITE) t("🏆 Партия в шашки: вы победили Honer AI! Отличная игра.", "🏆 Checkers: you beat Honer AI! Great game.")
            else t("♟️ Партия в шашки: победил Honer AI. Сыграем ещё?", "♟️ Checkers: Honer AI won. Play again?"),
        )
    }

    fun play(move: CheckersBoard.Move) {
        val next = board.copy()
        next.apply(move)
        board = next
        lastPath = move.path
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        checkFinish(next)
    }

    fun aiTurn() {
        if (board.winner != null || board.turn != CheckersBoard.Side.BLACK) return
        thinking = true
        val snapshot = board.copy()
        aiJob[0] = scope.launch {
            val started = SystemClock.uptimeMillis()
            val move = withContext(Dispatchers.Default) { CheckersAI.bestMove(snapshot) }
            val wait = 600 - (SystemClock.uptimeMillis() - started)
            if (wait > 0) delay(wait)
            thinking = false
            if (move != null && !finished) play(move)
        }
    }

    fun tap(square: Int) {
        if (thinking || board.turn != CheckersBoard.Side.WHITE || winner != null) return
        val piece = board[square]
        if (piece != null && piece.side == CheckersBoard.Side.WHITE && moves.any { it.from == square }) {
            selected = square
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            return
        }
        val from = selected ?: return
        val move = moves.filter { it.from == from && it.to == square }.maxByOrNull { it.captured.size } ?: return
        play(move)
        selected = null
        aiTurn()
    }

    fun reset() {
        aiJob[0]?.cancel()
        board = CheckersBoard.initial()
        selected = null
        lastPath = emptyList()
        finished = false
        thinking = false
    }

    BoardGameLayout(
        status = { GameStatusBar(status, thinking) },
        board = { side -> CheckersBoardView(board, side, selected, targets, lastPath, ::tap) },
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CountLabel(board.count(CheckersBoard.Side.WHITE), Color.White, colors.foreground)
                CountLabel(board.count(CheckersBoard.Side.BLACK), Color(0.8f, 0.2f, 0.2f), Color(0.8f, 0.2f, 0.2f))
                Spacer(Modifier.weight(1f))
                GameButton(t("Новая игра", "New game"), "game.new", onClick = ::reset)
            }
        },
    )
}

@Composable
private fun CountLabel(count: Int, dot: Color, text: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(14.dp)) {
            drawCircle(dot)
            drawCircle(Color.Black.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()))
        }
        Text("$count", color = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CheckersBoardView(
    board: CheckersBoard, side: Dp, selected: Int?, targets: Set<Int>, lastPath: List<Int>, onTap: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val cellPx = with(density) { side.toPx() } / 8
    // Жест создаётся один раз — вызываем всегда свежий обработчик.
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        Modifier.size(side)
            .shadow(10.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .testTag("checkers.board")
            .pointerInput(cellPx) {
                detectTapGestures { offset ->
                    val column = (offset.x / cellPx).toInt().coerceIn(0, 7)
                    val row = (offset.y / cellPx).toInt().coerceIn(0, 7)
                    currentOnTap(row * 8 + column)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (square in 0 until 64) {
                val row = square / 8
                val column = square % 8
                val topLeft = Offset(column * cellPx, row * cellPx)
                val cell = Size(cellPx, cellPx)
                val dark = (row + column) % 2 == 1
                drawRect(if (dark) Color(0.45f, 0.3f, 0.2f) else Color(0.93f, 0.85f, 0.7f), topLeft, cell)
                if (square in lastPath) drawRect(Color.Yellow.copy(alpha = 0.25f), topLeft, cell)
                if (square == selected) drawRect(Color.Yellow.copy(alpha = 0.35f), topLeft, cell)
                if (square in targets) {
                    drawCircle(Color.Green.copy(alpha = 0.55f), cellPx * 0.16f, Offset(topLeft.x + cellPx / 2, topLeft.y + cellPx / 2))
                }
            }
        }
        val pieceSize = side / 8 * 0.8f
        val inset = cellPx * 0.1f
        for (square in 0 until 64) {
            val piece = board[square] ?: continue
            key(piece.id) {
                val target = IntOffset((square % 8 * cellPx + inset).roundToInt(), (square / 8 * cellPx + inset).roundToInt())
                val position = animateIntOffsetAsState(target, tween(350), label = "checker")
                CheckersPiece(piece, Modifier.offset { position.value }.size(pieceSize))
            }
        }
    }
}

@Composable
private fun CheckersPiece(piece: CheckersBoard.Piece, modifier: Modifier) {
    val white = piece.side == CheckersBoard.Side.WHITE
    val gradient = if (white) listOf(Color.White, Color(0.8f, 0.8f, 0.8f)) else listOf(Color(0.9f, 0.3f, 0.3f), Color(0.5f, 0.08f, 0.08f))
    val density = LocalDensity.current
    Box(modifier.shadow(3.dp, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(gradient, center = Offset(r * 0.35f, r * 0.35f), radius = size.minDimension), r)
            drawCircle(Color.Black.copy(alpha = 0.35f), r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.3f), r * 0.72f, style = Stroke(1.dp.toPx()))
        }
        if (piece.king) {
            BoxSizeText(if (white) Color(0.85f, 0.6f, 0.1f) else Color(1f, 0.85f, 0.3f), density)
        }
    }
}

/** Корона дамки — глиф в текстовом виде, размер от размера шашки. */
@Composable
private fun BoxSizeText(color: Color, density: androidx.compose.ui.unit.Density) {
    androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
        val size = with(density) { (constraints.maxWidth * 0.5f).toSp() }
        Text("♛︎", color = color, fontSize = size, lineHeight = size)
    }
}
