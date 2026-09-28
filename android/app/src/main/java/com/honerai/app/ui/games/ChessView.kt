package com.honerai.app.ui.games

import android.os.SystemClock
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.honerai.app.ui.games.engine.ChessAI
import com.honerai.app.ui.games.engine.ChessBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Раскладка настольной игры: статус, доска, панель; на широком экране — доска слева, остальное справа. */
@Composable
internal fun BoardGameLayout(
    status: @Composable () -> Unit,
    board: @Composable (Dp) -> Unit,
    footer: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val landscape = maxWidth > maxHeight * 1.15f
        if (landscape) {
            val side = min(maxHeight, maxWidth - 240.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                board(side)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    status()
                    footer()
                }
            }
        } else {
            val side = min(maxWidth, maxHeight - 140.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                status()
                board(side)
                footer()
            }
        }
    }
}

/** Шахматы против Honer AI (порт ChessGameView). */
@Composable
internal fun ChessGame(onResult: (String) -> Unit) {
    val t = rememberGameText()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    var board by remember { mutableStateOf(ChessBoard.initial()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var promotionChoices by remember { mutableStateOf<List<ChessBoard.Move>>(emptyList()) }
    val aiJob = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) { onDispose { aiJob[0]?.cancel() } }

    val legal = remember(board) { if (board.turn == ChessBoard.Side.WHITE) board.legalMoves() else emptyList() }
    val boardStatus = remember(board) { board.status }
    val targets = remember(legal, selected) { legal.filter { it.from == selected }.map { it.to }.toSet() }

    val status = when (val s = boardStatus) {
        is ChessBoard.Status.Checkmate -> if (s.winner == ChessBoard.Side.WHITE) t("Мат! Вы победили 🎉", "Checkmate! You won 🎉")
            else t("Мат. Победил Honer AI", "Checkmate. Honer AI won")
        ChessBoard.Status.Stalemate -> t("Пат — ничья", "Stalemate — draw")
        ChessBoard.Status.Draw -> t("Ничья", "Draw")
        ChessBoard.Status.Check -> if (thinking) t("Шах! Honer AI думает…", "Check! Honer AI is thinking…") else t("Шах!", "Check!")
        ChessBoard.Status.Playing -> if (thinking) t("Honer AI думает…", "Honer AI is thinking…") else t("Ваш ход", "Your move")
    }

    fun checkFinish(position: ChessBoard): Boolean {
        val text = when (val s = position.status) {
            is ChessBoard.Status.Checkmate -> if (s.winner == ChessBoard.Side.WHITE) {
                t("🏆 Шахматы: вы поставили мат Honer AI! Блестяще.", "🏆 Chess: you checkmated Honer AI! Brilliant.")
            } else {
                t("♟️ Шахматы: Honer AI поставил мат. Реванш?", "♟️ Chess: Honer AI delivered checkmate. Rematch?")
            }
            ChessBoard.Status.Stalemate -> t("🤝 Шахматы: пат, ничья.", "🤝 Chess: stalemate, a draw.")
            ChessBoard.Status.Draw -> t("🤝 Шахматы: ничья — на доске недостаточно фигур.", "🤝 Chess: a draw — not enough material.")
            else -> return false
        }
        if (finished) return true
        finished = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        result(text)
        return true
    }

    fun aiTurn() {
        thinking = true
        val snapshot = board.copy()
        aiJob[0] = scope.launch {
            val started = SystemClock.uptimeMillis()
            // Поиск хода — вне главного потока.
            val move = withContext(Dispatchers.Default) { ChessAI.bestMove(snapshot) }
            val wait = 500 - (SystemClock.uptimeMillis() - started)
            if (wait > 0) delay(wait)
            thinking = false
            if (move == null || finished) return@launch
            val next = board.copy()
            next.apply(move)
            board = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            checkFinish(next)
        }
    }

    fun commit(move: ChessBoard.Move) {
        val next = board.copy()
        next.apply(move)
        board = next
        selected = null
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (!checkFinish(next)) aiTurn()
    }

    fun tap(square: Int) {
        if (thinking || board.turn != ChessBoard.Side.WHITE || finished) return
        val piece = board[square]
        if (piece != null && piece.side == ChessBoard.Side.WHITE) {
            selected = if (legal.any { it.from == square }) square else null
            return
        }
        val from = selected ?: return
        val options = legal.filter { it.from == from && it.to == square }
        if (options.isEmpty()) return
        if (options.size > 1) { promotionChoices = options; return }
        commit(options[0])
    }

    fun reset() {
        aiJob[0]?.cancel()
        board = ChessBoard.initial()
        selected = null
        finished = false
        thinking = false
        promotionChoices = emptyList()
    }

    BoardGameLayout(
        status = { GameStatusBar(status, thinking) },
        board = { side ->
            ChessBoardView(board, side, selected, targets, ::tap)
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GameButton(t("Новая партия", "New game"), "game.new", onClick = ::reset)
            }
        },
    )

    if (promotionChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { promotionChoices = emptyList() },
            title = { Text(t("Во что превратить пешку?", "Promote the pawn to?")) },
            text = {
                Column {
                    for (move in promotionChoices) {
                        TextButton(onClick = { promotionChoices = emptyList(); commit(move) }, modifier = Modifier.fillMaxWidth()) {
                            Text(promotionTitle(move.promotion, t))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { promotionChoices = emptyList() }) { Text(t("Отмена", "Cancel")) } },
        )
    }
}

private fun promotionTitle(kind: ChessBoard.Kind?, t: (String, String) -> String): String = when (kind) {
    ChessBoard.Kind.ROOK -> t("Ладья", "Rook")
    ChessBoard.Kind.BISHOP -> t("Слон", "Bishop")
    ChessBoard.Kind.KNIGHT -> t("Конь", "Knight")
    else -> t("Ферзь", "Queen")
}

/** Фигуры рисуются одним набором глифов в текстовом (не эмодзи) виде. */
internal fun chessGlyph(kind: ChessBoard.Kind): String = when (kind) {
    ChessBoard.Kind.KING -> "♚"
    ChessBoard.Kind.QUEEN -> "♛"
    ChessBoard.Kind.ROOK -> "♜"
    ChessBoard.Kind.BISHOP -> "♝"
    ChessBoard.Kind.KNIGHT -> "♞"
    ChessBoard.Kind.PAWN -> "♟"
} + "︎"

private val LightSquare = Color(0.93f, 0.93f, 0.82f)
private val DarkSquare = Color(0.46f, 0.59f, 0.34f)

@Composable
private fun ChessBoardView(board: ChessBoard, side: Dp, selected: Int?, targets: Set<Int>, onTap: (Int) -> Unit) {
    val density = LocalDensity.current
    val cellPx = with(density) { side.toPx() } / 8
    // Жест создаётся один раз — вызываем всегда свежий обработчик.
    val currentOnTap by rememberUpdatedState(onTap)
    val checkSquare = if (board.inCheck(board.turn)) board.kingSquare(board.turn) else null
    val last = board.lastMove
    Box(
        Modifier.size(side)
            .shadow(10.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .testTag("chess.board")
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
                drawRect(if ((row + column) % 2 == 0) LightSquare else DarkSquare, topLeft, cell)
                val highlight = when {
                    checkSquare == square -> Color.Red.copy(alpha = 0.5f)
                    selected == square -> Color.Yellow.copy(alpha = 0.4f)
                    last != null && (last.from == square || last.to == square) -> Color.Yellow.copy(alpha = 0.3f)
                    else -> null
                }
                if (highlight != null) drawRect(highlight, topLeft, cell)
                if (square in targets) {
                    val center = Offset(topLeft.x + cellPx / 2, topLeft.y + cellPx / 2)
                    if (board[square] != null) {
                        drawCircle(Color.Black.copy(alpha = 0.3f), cellPx / 2 - cellPx * 0.08f, center, style = Stroke(cellPx * 0.08f))
                    } else {
                        drawCircle(Color.Black.copy(alpha = 0.22f), cellPx * 0.14f, center)
                    }
                }
            }
        }
        val cellDp = side / 8
        for (square in 0 until 64) {
            val piece = board[square] ?: continue
            key(piece.id) {
                val target = IntOffset((square % 8 * cellPx).roundToInt(), (square / 8 * cellPx).roundToInt())
                val position = animateIntOffsetAsState(target, tween(300), label = "piece")
                // Позиция читается на этапе раскладки — перемещение без лишних рекомпозиций.
                ChessPiece(piece, cellPx, Modifier.offset { position.value }.size(cellDp))
            }
        }
    }
}

@Composable
private fun ChessPiece(piece: ChessBoard.Piece, cellPx: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val fontSize = with(density) { (cellPx * 0.78f).toSp() }
    val white = piece.side == ChessBoard.Side.WHITE
    val glyph = chessGlyph(piece.kind)
    val base = TextStyle(fontSize = fontSize, lineHeight = fontSize)
    Box(modifier, contentAlignment = Alignment.Center) {
        // Контур: белые фигуры — тёмная обводка, чёрные — светлая, плюс мягкая тень.
        Text(glyph, style = base.copy(
            color = if (white) Color.Black.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f),
            drawStyle = Stroke(width = cellPx * 0.035f, join = StrokeJoin.Round),
        ))
        Text(glyph, style = base.copy(
            color = if (white) Color.White else Color.Black,
            shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, cellPx * 0.02f), cellPx * 0.05f),
        ))
    }
}
