package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardLayoutTest {

    @Test
    fun `lays out the starting position`() {
        val squares = BoardLayout.parsePlacement(START_FEN)

        assertEquals(64, squares.size)
        // Первая клетка раскладки — a8, там стоит чёрная ладья.
        assertEquals('r', squares[0])
        assertEquals('k', squares[4])
        // Последняя — h1, белая ладья.
        assertEquals('R', squares[63])
        assertEquals('K', squares[60])
        assertEquals(32, squares.count { it == null })
    }

    @Test
    fun `expands runs of empty squares`() {
        val squares = BoardLayout.parsePlacement("8/8/8/4P3/8/8/8/8 w - - 0 1")

        assertEquals(64, squares.size)
        assertEquals(1, squares.count { it != null })
        assertEquals('P', squares[BoardLayout.squareIndex("e5")!!])
    }

    @Test
    fun `square names and indices agree with each other`() {
        assertEquals(0, BoardLayout.squareIndex("a8"))
        assertEquals(63, BoardLayout.squareIndex("h1"))
        assertEquals(28, BoardLayout.squareIndex("e5"))

        assertEquals("a8", BoardLayout.squareName(0))
        assertEquals("h1", BoardLayout.squareName(63))
        assertEquals("e5", BoardLayout.squareName(28))

        for (index in 0..63) {
            assertEquals(index, BoardLayout.squareIndex(BoardLayout.squareName(index)!!))
        }
    }

    @Test
    fun `rejects nonsense instead of guessing`() {
        assertNull(BoardLayout.squareIndex("z9"))
        assertNull(BoardLayout.squareIndex("a"))
        assertNull(BoardLayout.squareIndex(""))
        assertNull(BoardLayout.squareName(64))
        assertNull(BoardLayout.squareName(-1))
    }

    @Test
    fun `a broken record does not bring down the screen`() {
        // Лучше показать неполную доску, чем уронить экран разбора.
        assertEquals(64, BoardLayout.parsePlacement("8/8 w - - 0 1").size)
        assertEquals(64, BoardLayout.parsePlacement("").size)
    }

    @Test
    fun `places pieces of a real middlegame correctly`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"
        val squares = BoardLayout.parsePlacement(fen)

        assertEquals('B', squares[BoardLayout.squareIndex("b5")!!])
        assertEquals('n', squares[BoardLayout.squareIndex("c6")!!])
        assertEquals('P', squares[BoardLayout.squareIndex("e4")!!])
        assertEquals('p', squares[BoardLayout.squareIndex("e5")!!])
        assertNull(squares[BoardLayout.squareIndex("d4")!!])
    }
}
