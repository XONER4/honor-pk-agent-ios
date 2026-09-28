package com.honerai.app.ui.games.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Русские шашки (порт CheckersEngine.swift): обязательное взятие, взятие назад, дамка ходит
 * на любое расстояние, шашка, дошедшая до последнего ряда во время взятия, продолжает бить как дамка.
 * Снятые шашки убираются после хода (турецкий удар запрещён).
 * Клетки 0…63: строка 0 — сверху (сторона чёрных), белые идут вверх.
 */
class CheckersBoard private constructor(private val cellsArray: Array<Piece?>, turn: Side) {
    enum class Side { WHITE, BLACK;
        val opponent: Side get() = if (this == WHITE) BLACK else WHITE
    }

    data class Piece(val side: Side, val king: Boolean, val id: Int)

    /** Ход: путь по клеткам и снятые шашки. */
    data class Move(val path: List<Int>, val captured: List<Int>) {
        val from: Int get() = path.first()
        val to: Int get() = path.last()
    }

    var turn: Side = turn; private set
    val cells: List<Piece?> get() = cellsArray.asList()
    operator fun get(square: Int): Piece? = cellsArray[square]

    constructor(cells: List<Piece?>, turn: Side) : this(cells.toTypedArray(), turn)

    fun copy(): CheckersBoard = CheckersBoard(cellsArray.copyOf(), turn)

    override fun equals(other: Any?): Boolean =
        other is CheckersBoard && other.cellsArray.contentEquals(cellsArray) && other.turn == turn
    override fun hashCode(): Int = cellsArray.contentHashCode() * 31 + turn.hashCode()

    /** Все допустимые ходы стороны, которая ходит. */
    fun legalMoves(): List<Move> {
        val captures = ArrayList<Move>()
        for (sq in 0 until 64) {
            val piece = cellsArray[sq] ?: continue
            if (piece.side == turn) captures.addAll(captureSequences(sq, piece))
        }
        if (captures.isNotEmpty()) return captures
        val quiet = ArrayList<Move>()
        for (sq in 0 until 64) {
            val piece = cellsArray[sq] ?: continue
            if (piece.side == turn) quiet.addAll(quietMoves(sq, piece))
        }
        return quiet
    }

    private fun quietMoves(from: Int, piece: Piece): List<Move> {
        val row = row(from)
        val column = column(from)
        val result = ArrayList<Move>()
        val forward = if (piece.side == Side.WHITE) -1 else 1
        for (d in DIRECTIONS) {
            if (piece.king) {
                var step = 1
                while (true) {
                    val target = square(row + d[0] * step, column + d[1] * step) ?: break
                    if (cellsArray[target] != null) break
                    result.add(Move(listOf(from, target), emptyList()))
                    step++
                }
            } else if (d[0] == forward) {
                val target = square(row + d[0], column + d[1]) ?: continue
                if (cellsArray[target] == null) result.add(Move(listOf(from, target), emptyList()))
            }
        }
        return result
    }

    private fun captureSequences(from: Int, piece: Piece): List<Move> {
        val results = ArrayList<Move>()
        val board = cellsArray.copyOf()
        board[from] = null
        search(board, from, piece.king, piece.side, listOf(from), emptyList(), results)
        return results
    }

    private fun search(
        board: Array<Piece?>, at: Int, king: Boolean, side: Side,
        path: List<Int>, captured: List<Int>, results: MutableList<Move>,
    ) {
        val row = row(at)
        val column = column(at)
        var extended = false
        for (d in DIRECTIONS) {
            if (king) {
                var step = 1
                var enemy: Int? = null
                while (true) {
                    val current = square(row + d[0] * step, column + d[1] * step) ?: break
                    val piece = board[current]
                    if (piece != null) {
                        if (enemy != null || piece.side == side || current in captured) break
                        enemy = current
                    } else if (enemy != null) {
                        extended = true
                        search(board, current, true, side, path + current, captured + enemy, results)
                    }
                    step++
                }
            } else {
                val middle = square(row + d[0], column + d[1]) ?: continue
                val landing = square(row + 2 * d[0], column + 2 * d[1]) ?: continue
                val piece = board[middle] ?: continue
                if (piece.side == side || middle in captured || board[landing] != null) continue
                extended = true
                val promotes = (side == Side.WHITE && row(landing) == 0) || (side == Side.BLACK && row(landing) == 7)
                search(board, landing, promotes, side, path + landing, captured + middle, results)
            }
        }
        if (!extended && captured.isNotEmpty()) results.add(Move(path, captured))
    }

    /** Сделать ход (ход обязан быть из legalMoves()). */
    fun apply(move: Move) {
        var piece = cellsArray[move.from] ?: return
        cellsArray[move.from] = null
        for (sq in move.captured) cellsArray[sq] = null
        // Дамка: дошла до последнего ряда в любой точке пути.
        for (sq in move.path.drop(1)) {
            if ((piece.side == Side.WHITE && row(sq) == 0) || (piece.side == Side.BLACK && row(sq) == 7)) {
                piece = piece.copy(king = true)
            }
        }
        cellsArray[move.to] = piece
        turn = turn.opponent
    }

    fun count(side: Side): Int = cellsArray.count { it?.side == side }

    /** Победитель: у проигравшего нет шашек или ходов. */
    val winner: Side?
        get() = if (legalMoves().isEmpty()) turn.opponent else null

    /** Оценка позиции с точки зрения белых. */
    fun evaluate(): Int {
        var score = 0
        for (sq in 0 until 64) {
            val piece = cellsArray[sq] ?: continue
            val row = row(sq)
            val column = column(sq)
            var value = if (piece.king) 320 else 100
            if (!piece.king) {
                value += (if (piece.side == Side.WHITE) 7 - row else row) * 6
                if (column in 2..5 && row in 2..5) value += 8
                if ((piece.side == Side.WHITE && row == 7) || (piece.side == Side.BLACK && row == 0)) value += 10
            }
            score += if (piece.side == Side.WHITE) value else -value
        }
        return score
    }

    companion object {
        private val DIRECTIONS = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))

        fun row(square: Int) = square / 8
        fun column(square: Int) = square % 8
        private fun square(row: Int, column: Int): Int? =
            if (row in 0..7 && column in 0..7) row * 8 + column else null

        fun initial(): CheckersBoard {
            val cells = arrayOfNulls<Piece>(64)
            var id = 0
            for (row in 0 until 8) {
                for (column in 0 until 8) {
                    if ((row + column) % 2 != 1) continue
                    if (row < 3) cells[row * 8 + column] = Piece(Side.BLACK, false, id++)
                    if (row > 4) cells[row * 8 + column] = Piece(Side.WHITE, false, id++)
                }
            }
            return CheckersBoard(cells, Side.WHITE)
        }
    }
}

/** Соперник в шашках: поиск с отсечением на несколько ходов вперёд (порт CheckersAI). */
object CheckersAI {
    fun bestMove(board: CheckersBoard, depth: Int = 6, random: kotlin.random.Random = kotlin.random.Random.Default): CheckersBoard.Move? {
        val moves = board.legalMoves()
        if (moves.size <= 1) return moves.firstOrNull()
        val maximizing = board.turn == CheckersBoard.Side.WHITE
        var best: CheckersBoard.Move? = null
        var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        for (move in moves.shuffled(random)) {
            val next = board.copy()
            next.apply(move)
            val score = minimax(next, depth - 1, Int.MIN_VALUE, Int.MAX_VALUE)
            if (if (maximizing) score > bestScore else score < bestScore) {
                bestScore = score
                best = move
            }
        }
        return best ?: moves.first()
    }

    private fun minimax(board: CheckersBoard, depth: Int, alphaIn: Int, betaIn: Int): Int {
        val moves = board.legalMoves()
        if (moves.isEmpty()) return if (board.turn == CheckersBoard.Side.WHITE) -100_000 - depth else 100_000 + depth
        // Размены доигрываются до конца, но не бесконечно.
        if (depth <= -6 || (depth <= 0 && moves.first().captured.isEmpty())) return board.evaluate()
        var alpha = alphaIn
        var beta = betaIn
        if (board.turn == CheckersBoard.Side.WHITE) {
            var value = Int.MIN_VALUE
            for (move in moves) {
                val next = board.copy(); next.apply(move)
                value = max(value, minimax(next, depth - 1, alpha, beta))
                alpha = max(alpha, value)
                if (alpha >= beta) break
            }
            return value
        } else {
            var value = Int.MAX_VALUE
            for (move in moves) {
                val next = board.copy(); next.apply(move)
                value = min(value, minimax(next, depth - 1, alpha, beta))
                beta = min(beta, value)
                if (alpha >= beta) break
            }
            return value
        }
    }
}
