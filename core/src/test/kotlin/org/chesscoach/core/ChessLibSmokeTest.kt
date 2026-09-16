package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M0: доказываем, что chesslib реально разрешился с JitPack и работает. */
class ChessLibSmokeTest {

    @Test
    fun `startpos has twenty legal moves`() {
        val board = Board()
        assertEquals(20, board.legalMoves().size)
    }

    @Test
    fun `fen round trip keeps the position`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 3 3"
        val board = Board()
        board.loadFromFen(fen)
        assertEquals(fen, board.fen)
        assertTrue(board.getPiece(Square.B5).toString().contains("BISHOP"))
    }
}
