package com.honerai.app.games

import com.honerai.app.ui.games.engine.CheckersAI
import com.honerai.app.ui.games.engine.CheckersBoard
import com.honerai.app.ui.games.engine.CheckersBoard.Piece
import com.honerai.app.ui.games.engine.CheckersBoard.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CheckersEngineTest {
    private fun sq(row: Int, column: Int) = row * 8 + column

    private fun board(turn: Side, vararg pieces: Pair<Int, Piece>): CheckersBoard {
        val cells = arrayOfNulls<Piece>(64)
        for ((square, piece) in pieces) cells[square] = piece
        return CheckersBoard(cells.toList(), turn)
    }

    private var nextId = 0
    private fun white(king: Boolean = false) = Piece(Side.WHITE, king, nextId++)
    private fun black(king: Boolean = false) = Piece(Side.BLACK, king, nextId++)

    @Test
    fun initialPositionHasSevenMoves() {
        val start = CheckersBoard.initial()
        assertEquals(12, start.count(Side.WHITE))
        assertEquals(12, start.count(Side.BLACK))
        assertEquals(7, start.legalMoves().size)
    }

    @Test
    fun captureIsMandatoryAndBackwardsAllowed() {
        // Белая шашка может бить назад; тихие ходы других шашек запрещены.
        val position = board(Side.WHITE, sq(4, 3) to white(), sq(5, 4) to black(), sq(7, 0) to white())
        val moves = position.legalMoves()
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.captured.isNotEmpty() })
        assertEquals(sq(6, 5), moves.single().to)
    }

    @Test
    fun multiJumpCapturesSeveralPieces() {
        val position = board(Side.WHITE, sq(6, 1) to white(), sq(5, 2) to black(), sq(3, 4) to black(), sq(0, 7) to black())
        val move = position.legalMoves().maxBy { it.captured.size }
        assertEquals(2, move.captured.size)
        assertEquals(listOf(sq(6, 1), sq(4, 3), sq(2, 5)), move.path)
        position.apply(move)
        assertEquals(1, position.count(Side.BLACK))
    }

    @Test
    fun flyingKingMovesAndCapturesFromDistance() {
        val quiet = board(Side.WHITE, sq(7, 0) to white(king = true), sq(0, 1) to black())
        assertEquals(7, quiet.legalMoves().size)
        // Дамка бьёт издалека и может встать на любое свободное поле за шашкой.
        val capture = board(Side.WHITE, sq(7, 0) to white(king = true), sq(4, 3) to black(), sq(0, 1) to black())
        val landings = capture.legalMoves().map { it.to }.toSet()
        assertEquals(setOf(sq(3, 4), sq(2, 5), sq(1, 6), sq(0, 7)), landings)
        assertTrue(capture.legalMoves().all { it.captured == listOf(sq(4, 3)) })
    }

    @Test
    fun manPromotesDuringCaptureAndContinuesAsKing() {
        // Белая шашка бьёт на последний ряд и сразу продолжает бить как дамка.
        val position = board(Side.WHITE, sq(2, 1) to white(), sq(1, 2) to black(), sq(2, 5) to black(), sq(7, 6) to black())
        val move = position.legalMoves().maxBy { it.captured.size }
        assertEquals(2, move.captured.size)
        assertEquals(sq(0, 3), move.path[1])
        position.apply(move)
        assertTrue(position[move.to]!!.king)
    }

    @Test
    fun capturedPieceIsNotJumpedTwice() {
        // Турецкий удар запрещён: снятая шашка остаётся на доске до конца хода и не перепрыгивается снова.
        val position = board(
            Side.WHITE, sq(7, 0) to white(king = true), sq(5, 2) to black(), sq(2, 5) to black(), sq(4, 5) to black(),
        )
        for (move in position.legalMoves()) {
            assertEquals(move.captured.size, move.captured.toSet().size)
        }
    }

    @Test
    fun promotionOnQuietMove() {
        val position = board(Side.WHITE, sq(1, 2) to white(), sq(7, 7) to black())
        val move = position.legalMoves().first { it.to == sq(0, 1) }
        position.apply(move)
        assertTrue(position[sq(0, 1)]!!.king)
    }

    @Test
    fun noMovesMeansLoss() {
        val blocked = board(Side.BLACK, sq(7, 0) to black(), sq(0, 1) to white())
        assertEquals(Side.WHITE, blocked.winner)
    }

    @Test
    fun randomVersusAiGamesTerminate() {
        repeat(3) { game ->
            val random = Random(7 + game)
            val position = CheckersBoard.initial()
            var plies = 0
            while (position.winner == null && plies < 300) {
                val move = if (position.turn == Side.WHITE) position.legalMoves().random(random)
                else CheckersAI.bestMove(position, depth = 4, random = random)
                assertNotNull(move)
                position.apply(move!!)
                plies++
            }
            assertTrue(position.count(Side.WHITE) + position.count(Side.BLACK) <= 24)
        }
    }

    @Test
    fun aiTakesTheCapture() {
        val position = board(Side.BLACK, sq(2, 3) to black(), sq(3, 4) to white(), sq(7, 0) to white(), sq(0, 7) to black())
        val move = CheckersAI.bestMove(position)!!
        assertEquals(listOf(sq(3, 4)), move.captured)
    }
}
