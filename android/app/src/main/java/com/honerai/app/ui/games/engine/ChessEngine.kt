package com.honerai.app.ui.games.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Шахматы по полным правилам (порт ChessEngine.swift): рокировка, взятие на проходе,
 * превращение пешки, шах, мат, пат и ничья при недостатке материала.
 * Клетки 0…63: строка 0 — восьмая горизонталь (чёрные сверху).
 */
class ChessBoard private constructor(
    private val cellsArray: Array<Piece?>,
    turn: Side,
    castling: Int,
    enPassantTarget: Int?,
    lastMove: Move?,
) {
    enum class Side { WHITE, BLACK;
        val opponent: Side get() = if (this == WHITE) BLACK else WHITE
    }

    enum class Kind { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }

    data class Piece(val kind: Kind, val side: Side, val id: Int)

    data class Move(
        val from: Int,
        val to: Int,
        val promotion: Kind? = null,
        val enPassant: Boolean = false,
        val castleRookFrom: Int? = null,
        val castleRookTo: Int? = null,
    )

    sealed class Status {
        data object Playing : Status()
        data object Check : Status()
        data class Checkmate(val winner: Side) : Status()
        data object Stalemate : Status()
        data object Draw : Status()
    }

    var turn: Side = turn; private set
    /** Права на рокировку: биты K=1, Q=2, k=4, q=8. */
    var castling: Int = castling; private set
    var enPassantTarget: Int? = enPassantTarget; private set
    var lastMove: Move? = lastMove; private set

    val cells: List<Piece?> get() = cellsArray.asList()
    operator fun get(square: Int): Piece? = cellsArray[square]

    constructor(cells: List<Piece?>, turn: Side, castling: Int = 0, enPassantTarget: Int? = null) :
        this(cells.toTypedArray(), turn, castling, enPassantTarget, null)

    fun copy(): ChessBoard = ChessBoard(cellsArray.copyOf(), turn, castling, enPassantTarget, lastMove)

    override fun equals(other: Any?): Boolean = other is ChessBoard && other.cellsArray.contentEquals(cellsArray) &&
        other.turn == turn && other.castling == castling && other.enPassantTarget == enPassantTarget
    override fun hashCode(): Int = cellsArray.contentHashCode() * 31 + turn.hashCode()

    fun kingSquare(side: Side): Int? {
        for (square in 0 until 64) {
            val piece = cellsArray[square] ?: continue
            if (piece.kind == Kind.KING && piece.side == side) return square
        }
        return null
    }

    /** Бьёт ли сторона [side] клетку [target]. */
    fun isAttacked(target: Int, side: Side): Boolean {
        val row = row(target)
        val column = column(target)
        val pawnRow = if (side == Side.WHITE) row + 1 else row - 1
        for (dc in intArrayOf(-1, 1)) {
            val sq = square(pawnRow, column + dc) ?: continue
            val piece = cellsArray[sq] ?: continue
            if (piece.side == side && piece.kind == Kind.PAWN) return true
        }
        for (step in KNIGHT_STEPS) {
            val sq = square(row + step[0], column + step[1]) ?: continue
            val piece = cellsArray[sq] ?: continue
            if (piece.side == side && piece.kind == Kind.KNIGHT) return true
        }
        for (step in KING_STEPS) {
            val sq = square(row + step[0], column + step[1]) ?: continue
            val piece = cellsArray[sq] ?: continue
            if (piece.side == side && piece.kind == Kind.KING) return true
        }
        if (slides(row, column, side, DIAGONALS, Kind.BISHOP)) return true
        if (slides(row, column, side, STRAIGHTS, Kind.ROOK)) return true
        return false
    }

    private fun slides(row: Int, column: Int, side: Side, directions: Array<IntArray>, kind: Kind): Boolean {
        for (d in directions) {
            var step = 1
            while (true) {
                val sq = square(row + d[0] * step, column + d[1] * step) ?: break
                val piece = cellsArray[sq]
                if (piece != null) {
                    if (piece.side == side && (piece.kind == kind || piece.kind == Kind.QUEEN)) return true
                    break
                }
                step++
            }
        }
        return false
    }

    fun inCheck(side: Side): Boolean {
        val king = kingSquare(side) ?: return false
        return isAttacked(king, side.opponent)
    }

    private fun pseudoMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        for (sq in 0 until 64) {
            val piece = cellsArray[sq] ?: continue
            if (piece.side != turn) continue
            val row = row(sq)
            val column = column(sq)
            when (piece.kind) {
                Kind.PAWN -> {
                    val dir = if (piece.side == Side.WHITE) -1 else 1
                    val startRow = if (piece.side == Side.WHITE) 6 else 1
                    val lastRow = if (piece.side == Side.WHITE) 0 else 7
                    fun add(to: Int, enPassant: Boolean = false) {
                        if (row(to) == lastRow) {
                            for (kind in PROMOTIONS) moves.add(Move(sq, to, promotion = kind))
                        } else {
                            moves.add(Move(sq, to, enPassant = enPassant))
                        }
                    }
                    val one = square(row + dir, column)
                    if (one != null && cellsArray[one] == null) {
                        add(one)
                        val two = square(row + 2 * dir, column)
                        if (row == startRow && two != null && cellsArray[two] == null) moves.add(Move(sq, two))
                    }
                    for (dc in intArrayOf(-1, 1)) {
                        val target = square(row + dir, column + dc) ?: continue
                        val other = cellsArray[target]
                        if (other != null) {
                            if (other.side != piece.side) add(target)
                        } else if (target == enPassantTarget) {
                            add(target, enPassant = true)
                        }
                    }
                }
                Kind.KNIGHT, Kind.KING -> {
                    for (step in if (piece.kind == Kind.KNIGHT) KNIGHT_STEPS else KING_STEPS) {
                        val target = square(row + step[0], column + step[1]) ?: continue
                        val other = cellsArray[target]
                        if (other != null && other.side == piece.side) continue
                        moves.add(Move(sq, target))
                    }
                    if (piece.kind == Kind.KING) moves.addAll(castlingMoves(sq))
                }
                Kind.BISHOP, Kind.ROOK, Kind.QUEEN -> {
                    val directions = when (piece.kind) {
                        Kind.BISHOP -> DIAGONALS
                        Kind.ROOK -> STRAIGHTS
                        else -> QUEEN_DIRECTIONS
                    }
                    for (d in directions) {
                        var step = 1
                        while (true) {
                            val target = square(row + d[0] * step, column + d[1] * step) ?: break
                            val other = cellsArray[target]
                            if (other != null) {
                                if (other.side != piece.side) moves.add(Move(sq, target))
                                break
                            }
                            moves.add(Move(sq, target))
                            step++
                        }
                    }
                }
            }
        }
        return moves
    }

    private fun castlingMoves(from: Int): List<Move> {
        val white = turn == Side.WHITE
        val home = if (white) 60 else 4
        if (from != home || inCheck(turn)) return emptyList()
        val result = ArrayList<Move>(2)
        val kingSide = if (white) CASTLE_K else CASTLE_k
        val queenSide = if (white) CASTLE_Q else CASTLE_q
        val enemy = turn.opponent
        val rookK = cellsArray[home + 3]
        if (castling and kingSide != 0 && cellsArray[home + 1] == null && cellsArray[home + 2] == null &&
            rookK?.kind == Kind.ROOK && rookK.side == turn &&
            !isAttacked(home + 1, enemy) && !isAttacked(home + 2, enemy)
        ) {
            result.add(Move(home, home + 2, castleRookFrom = home + 3, castleRookTo = home + 1))
        }
        val rookQ = cellsArray[home - 4]
        if (castling and queenSide != 0 && cellsArray[home - 1] == null && cellsArray[home - 2] == null &&
            cellsArray[home - 3] == null && rookQ?.kind == Kind.ROOK && rookQ.side == turn &&
            !isAttacked(home - 1, enemy) && !isAttacked(home - 2, enemy)
        ) {
            result.add(Move(home, home - 2, castleRookFrom = home - 4, castleRookTo = home - 1))
        }
        return result
    }

    /** Все допустимые ходы (свой король не остаётся под шахом). */
    fun legalMoves(): List<Move> {
        val mover = turn
        return pseudoMoves().filter { move ->
            val next = copy()
            next.apply(move)
            !next.inCheck(mover)
        }
    }

    fun legalMoves(from: Int): List<Move> = legalMoves().filter { it.from == from }

    fun apply(move: Move) {
        var piece = cellsArray[move.from] ?: return
        if (move.enPassant) {
            val captured = move.to + if (piece.side == Side.WHITE) 8 else -8
            cellsArray[captured] = null
        }
        if (move.castleRookFrom != null && move.castleRookTo != null) {
            cellsArray[move.castleRookTo] = cellsArray[move.castleRookFrom]
            cellsArray[move.castleRookFrom] = null
        }
        if (move.promotion != null) piece = piece.copy(kind = move.promotion)
        cellsArray[move.from] = null
        cellsArray[move.to] = piece
        // Права на рокировку теряются, когда ходят король или ладьи (или ладью бьют).
        for ((sq, flag) in CASTLE_SQUARES) {
            if (move.from == sq || move.to == sq) castling = castling and flag.inv()
        }
        enPassantTarget = if (piece.kind == Kind.PAWN && abs(move.to - move.from) == 16) (move.to + move.from) / 2 else null
        lastMove = move
        turn = turn.opponent
    }

    val status: Status
        get() {
            val moves = legalMoves()
            if (moves.isEmpty()) return if (inCheck(turn)) Status.Checkmate(turn.opponent) else Status.Stalemate
            val rest = cellsArray.filterNotNull().filter { it.kind != Kind.KING }
            if (rest.isEmpty() || (rest.size == 1 && (rest[0].kind == Kind.BISHOP || rest[0].kind == Kind.KNIGHT))) {
                return Status.Draw
            }
            return if (inCheck(turn)) Status.Check else Status.Playing
        }

    /** Оценка с точки зрения стороны, которая ходит. */
    fun evaluate(): Int {
        var score = 0
        for (sq in 0 until 64) {
            val piece = cellsArray[sq] ?: continue
            val index = if (piece.side == Side.WHITE) sq else (7 - row(sq)) * 8 + column(sq)
            var value = value(piece.kind)
            value += when (piece.kind) {
                Kind.PAWN -> PAWN_TABLE[index]
                Kind.KNIGHT -> KNIGHT_TABLE[index]
                Kind.BISHOP -> BISHOP_TABLE[index]
                Kind.KING -> KING_TABLE[index]
                else -> 0
            }
            score += if (piece.side == Side.WHITE) value else -value
        }
        return if (turn == Side.WHITE) score else -score
    }

    companion object {
        const val CASTLE_K = 1
        const val CASTLE_Q = 2
        const val CASTLE_k = 4
        const val CASTLE_q = 8
        const val CASTLE_ALL = 15

        private val CASTLE_SQUARES = listOf(
            60 to CASTLE_K, 60 to CASTLE_Q, 63 to CASTLE_K, 56 to CASTLE_Q,
            4 to CASTLE_k, 4 to CASTLE_q, 7 to CASTLE_k, 0 to CASTLE_q,
        )
        private val PROMOTIONS = arrayOf(Kind.QUEEN, Kind.ROOK, Kind.BISHOP, Kind.KNIGHT)
        private val KNIGHT_STEPS = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1),
        )
        private val KING_STEPS = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1),
            intArrayOf(0, 1), intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1),
        )
        private val DIAGONALS = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        private val STRAIGHTS = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))
        private val QUEEN_DIRECTIONS = DIAGONALS + STRAIGHTS

        fun row(square: Int) = square / 8
        fun column(square: Int) = square % 8
        private fun square(row: Int, column: Int): Int? =
            if (row in 0..7 && column in 0..7) row * 8 + column else null

        /** Начальная позиция. */
        fun initial(): ChessBoard {
            val cells = arrayOfNulls<Piece>(64)
            val back = arrayOf(Kind.ROOK, Kind.KNIGHT, Kind.BISHOP, Kind.QUEEN, Kind.KING, Kind.BISHOP, Kind.KNIGHT, Kind.ROOK)
            var id = 0
            for (column in 0 until 8) {
                cells[column] = Piece(back[column], Side.BLACK, id++)
                cells[8 + column] = Piece(Kind.PAWN, Side.BLACK, id++)
                cells[48 + column] = Piece(Kind.PAWN, Side.WHITE, id++)
                cells[56 + column] = Piece(back[column], Side.WHITE, id++)
            }
            return ChessBoard(cells, Side.WHITE, CASTLE_ALL, null, null)
        }

        fun value(kind: Kind): Int = when (kind) {
            Kind.PAWN -> 100
            Kind.KNIGHT -> 320
            Kind.BISHOP -> 330
            Kind.ROOK -> 500
            Kind.QUEEN -> 900
            Kind.KING -> 0
        }

        private val PAWN_TABLE = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        private val KNIGHT_TABLE = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50,
        )
        private val BISHOP_TABLE = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20,
        )
        private val KING_TABLE = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20,
        )
    }
}

/** Соперник в шахматах: поиск с отсечением и доигрыванием взятий (порт ChessAI). */
object ChessAI {
    private const val INF = 1_000_000_000
    private const val MATE = 100_000

    fun bestMove(board: ChessBoard, depth: Int = 3, random: kotlin.random.Random = kotlin.random.Random.Default): ChessBoard.Move? {
        // Разнообразие партий — перемешиванием до сортировки (сортировка устойчива): при отсечениях
        // оценка хода лишь граница, поэтому лучший выбирается строго по «больше» — равенство не побеждает.
        val moves = ordered(board.legalMoves().shuffled(random), board)
        if (moves.size <= 1) return moves.firstOrNull()
        var best: ChessBoard.Move? = null
        var bestScore = -INF
        var alpha = -INF
        for (move in moves) {
            val next = board.copy()
            next.apply(move)
            val score = -negamax(next, depth - 1, -INF, -alpha)
            if (score > bestScore) {
                bestScore = score
                best = move
            }
            alpha = max(alpha, score)
        }
        return best
    }

    private fun ordered(moves: List<ChessBoard.Move>, board: ChessBoard): List<ChessBoard.Move> =
        moves.sortedByDescending { score(it, board) }

    private fun score(move: ChessBoard.Move, board: ChessBoard): Int {
        var value = 0
        val victim = board[move.to]
        val attacker = board[move.from]
        if (victim != null && attacker != null) {
            value += 10 * ChessBoard.value(victim.kind) - ChessBoard.value(attacker.kind)
        }
        if (move.promotion == ChessBoard.Kind.QUEEN) value += 800
        return value
    }

    private fun negamax(board: ChessBoard, depth: Int, alphaIn: Int, beta: Int): Int {
        val moves = board.legalMoves()
        if (moves.isEmpty()) return if (board.inCheck(board.turn)) -MATE - depth else 0
        if (depth <= 0) return quiescence(board, alphaIn, beta, 2)
        var alpha = alphaIn
        var best = -INF
        for (move in ordered(moves, board)) {
            val next = board.copy()
            next.apply(move)
            val value = -negamax(next, depth - 1, -beta, -alpha)
            best = max(best, value)
            alpha = max(alpha, value)
            if (alpha >= beta) break
        }
        return best
    }

    private fun quiescence(board: ChessBoard, alphaIn: Int, beta: Int, depth: Int): Int {
        val stand = board.evaluate()
        if (stand >= beta || depth == 0) return stand
        var alpha = max(alphaIn, stand)
        val captures = ordered(board.legalMoves().filter { board[it.to] != null || it.enPassant }, board)
        for (move in captures) {
            val next = board.copy()
            next.apply(move)
            val value = -quiescence(next, -beta, -alpha, depth - 1)
            if (value >= beta) return value
            alpha = max(alpha, value)
        }
        return alpha
    }
}
