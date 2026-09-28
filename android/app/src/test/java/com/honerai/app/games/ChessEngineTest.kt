package com.honerai.app.games

import com.honerai.app.ui.games.engine.ChessAI
import com.honerai.app.ui.games.engine.ChessBoard
import com.honerai.app.ui.games.engine.ChessBoard.Kind
import com.honerai.app.ui.games.engine.ChessBoard.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChessEngineTest {
    /** Позиция из FEN (строка 0 — восьмая горизонталь, как в движке). */
    private fun fen(text: String): ChessBoard {
        val parts = text.trim().split(" ")
        val cells = arrayOfNulls<ChessBoard.Piece>(64)
        var id = 0
        parts[0].split("/").forEachIndexed { row, line ->
            var column = 0
            for (ch in line) {
                if (ch.isDigit()) { column += ch - '0'; continue }
                val kind = when (ch.lowercaseChar()) {
                    'p' -> Kind.PAWN; 'n' -> Kind.KNIGHT; 'b' -> Kind.BISHOP
                    'r' -> Kind.ROOK; 'q' -> Kind.QUEEN; else -> Kind.KING
                }
                cells[row * 8 + column] = ChessBoard.Piece(kind, if (ch.isUpperCase()) Side.WHITE else Side.BLACK, id++)
                column++
            }
        }
        val turn = if (parts.getOrElse(1) { "w" } == "w") Side.WHITE else Side.BLACK
        var castling = 0
        val rights = parts.getOrElse(2) { "-" }
        if ('K' in rights) castling = castling or ChessBoard.CASTLE_K
        if ('Q' in rights) castling = castling or ChessBoard.CASTLE_Q
        if ('k' in rights) castling = castling or ChessBoard.CASTLE_k
        if ('q' in rights) castling = castling or ChessBoard.CASTLE_q
        val ep = parts.getOrElse(3) { "-" }.takeIf { it != "-" }?.let { square(it) }
        return ChessBoard(cells.toList(), turn, castling, ep)
    }

    private fun square(name: String): Int = (8 - (name[1] - '0')) * 8 + (name[0] - 'a')

    private fun perft(board: ChessBoard, depth: Int): Long {
        if (depth == 0) return 1
        val moves = board.legalMoves()
        if (depth == 1) return moves.size.toLong()
        var total = 0L
        for (move in moves) {
            val next = board.copy()
            next.apply(move)
            total += perft(next, depth - 1)
        }
        return total
    }

    @Test
    fun perftFromStartPosition() {
        val board = ChessBoard.initial()
        assertEquals(20L, perft(board, 1))
        assertEquals(400L, perft(board, 2))
        assertEquals(8902L, perft(board, 3))
    }

    @Test
    fun perftKiwipeteCoversCastlingEnPassantAndPromotion() {
        val board = fen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -")
        assertEquals(48L, perft(board, 1))
        assertEquals(2039L, perft(board, 2))
    }

    @Test
    fun perftEndgameAndPromotionPositions() {
        assertEquals(191L, perft(fen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -"), 2))
        assertEquals(2812L, perft(fen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -"), 3))
        assertEquals(264L, perft(fen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"), 2))
    }

    @Test
    fun enPassantCaptureRemovesPawn() {
        val board = fen("4k3/3p4/8/4P3/8/8/8/4K3 b - -")
        board.apply(board.legalMoves().first { it.from == square("d7") && it.to == square("d5") })
        assertEquals(square("d6"), board.enPassantTarget)
        val ep = board.legalMoves().firstOrNull { it.enPassant }
        assertNotNull(ep)
        assertEquals(square("e5"), ep!!.from)
        assertEquals(square("d6"), ep.to)
        board.apply(ep)
        assertNull(board[square("d5")])
        assertEquals(Kind.PAWN, board[square("d6")]?.kind)
    }

    @Test
    fun enPassantOnlyImmediately() {
        val board = fen("4k3/3p3p/8/4P3/8/8/8/4K3 b - -")
        board.apply(board.legalMoves().first { it.from == square("d7") && it.to == square("d5") })
        board.apply(board.legalMoves().first { it.from == square("e1") && it.to == square("f1") })
        board.apply(board.legalMoves().first { it.from == square("h7") && it.to == square("h6") })
        assertFalse(board.legalMoves().any { it.enPassant })
    }

    @Test
    fun castlingBothSidesAndRightsLost() {
        val board = fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq -")
        val castles = board.legalMoves().filter { it.castleRookFrom != null }
        assertEquals(2, castles.size)
        val kingSide = castles.first { it.to == square("g1") }
        board.apply(kingSide)
        assertEquals(Kind.ROOK, board[square("f1")]?.kind)
        assertEquals(Kind.KING, board[square("g1")]?.kind)
        assertNull(board[square("h1")])
        assertEquals(0, board.castling and (ChessBoard.CASTLE_K or ChessBoard.CASTLE_Q))
        // Чёрные ещё могут рокировать, но только в длинную: ладья f1 бьёт поле f8.
        assertEquals(listOf(square("c8")), board.legalMoves().filter { it.castleRookFrom != null }.map { it.to })
    }

    @Test
    fun noCastlingThroughOrOutOfCheck() {
        // Слон на c4 бьёт f1 — короткая рокировка запрещена, длинная разрешена.
        val throughCheck = fen("4k3/8/8/8/2b5/8/8/R3K2R w KQ -")
        val castles = throughCheck.legalMoves().filter { it.castleRookFrom != null }
        assertEquals(listOf(square("c1")), castles.map { it.to })
        // Король под шахом — никаких рокировок.
        val inCheck = fen("4k3/8/8/8/8/8/4r3/R3K2R w KQ -")
        assertTrue(inCheck.legalMoves().none { it.castleRookFrom != null })
    }

    @Test
    fun promotionOffersFourPieces() {
        val board = fen("4k3/P7/8/8/8/8/8/4K3 w - -")
        val promotions = board.legalMoves().filter { it.from == square("a7") }
        assertEquals(setOf(Kind.QUEEN, Kind.ROOK, Kind.BISHOP, Kind.KNIGHT), promotions.mapNotNull { it.promotion }.toSet())
        board.apply(promotions.first { it.promotion == Kind.KNIGHT })
        assertEquals(Kind.KNIGHT, board[square("a8")]?.kind)
    }

    @Test
    fun checkmateStalemateAndDraw() {
        val fools = ChessBoard.initial()
        for ((from, to) in listOf("f2" to "f3", "e7" to "e5", "g2" to "g4", "d8" to "h4")) {
            fools.apply(fools.legalMoves().first { it.from == square(from) && it.to == square(to) })
        }
        assertEquals(ChessBoard.Status.Checkmate(Side.BLACK), fools.status)
        assertEquals(ChessBoard.Status.Stalemate, fen("7k/5Q2/6K1/8/8/8/8/8 b - -").status)
        assertEquals(ChessBoard.Status.Draw, fen("7k/8/6K1/8/8/8/8/5B2 b - -").status)
        assertEquals(ChessBoard.Status.Check, fen("4k3/8/8/8/8/8/8/K3R3 b - -").status)
    }

    @Test
    fun aiCapturesHangingQueen() {
        // Ферзь чёрных на d5 никем не защищён — ладья d1 должна его взять при любом перемешивании.
        repeat(12) { seed ->
            val board = fen("6k1/5ppp/8/3q4/8/8/5PPP/3R2K1 w - -")
            val move = ChessAI.bestMove(board, random = Random(seed))
            assertEquals(square("d1"), move?.from)
            assertEquals(square("d5"), move?.to)
        }
    }

    @Test
    fun aiFindsMateInOne() {
        repeat(6) { seed ->
            val board = fen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - -")
            val move = ChessAI.bestMove(board, random = Random(seed))!!
            val next = board.copy()
            next.apply(move)
            assertEquals(ChessBoard.Status.Checkmate(Side.WHITE), next.status)
        }
    }

    @Test
    fun randomVersusAiGamesTerminate() {
        repeat(2) { game ->
            val random = Random(100 + game)
            val board = ChessBoard.initial()
            var plies = 0
            while (plies < 160) {
                val status = board.status
                if (status is ChessBoard.Status.Checkmate || status == ChessBoard.Status.Stalemate || status == ChessBoard.Status.Draw) break
                val move = if (board.turn == Side.WHITE) board.legalMoves().random(random) else ChessAI.bestMove(board, depth = 2, random = random)
                assertNotNull(move)
                board.apply(move!!)
                assertNotNull(board.kingSquare(Side.WHITE))
                assertNotNull(board.kingSquare(Side.BLACK))
                plies++
            }
        }
    }
}
