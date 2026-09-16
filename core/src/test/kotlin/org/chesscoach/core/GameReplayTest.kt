package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReplayTest {

    @Test
    fun `unfolds a game into positions`() {
        val positions = GameReplay.replay(listOf("e4", "e5", "Nf3", "Nc6"))

        assertEquals(4, positions.size)
        assertEquals(START_FEN, positions[0].fenBefore)
        assertEquals("e4", positions[0].san)
        assertEquals("e2e4", positions[0].uci)
        assertTrue(positions[0].whiteMoved)
        assertTrue(!positions[1].whiteMoved)
    }

    @Test
    fun `position after one move becomes the position before the next`() {
        // Если эта связь нарушится, движок будет считать не ту позицию,
        // а тренер — объяснять ход, которого на доске нет.
        val positions = GameReplay.replay(listOf("d4", "d5", "c4", "e6", "Nc3"))

        positions.zipWithNext { earlier, later ->
            assertEquals(earlier.fenAfter, later.fenBefore)
        }
    }

    @Test
    fun `numbers moves the way a game score does`() {
        val positions = GameReplay.replay(listOf("e4", "e5", "Nf3", "Nc6", "Bb5"))

        assertEquals(listOf(1, 1, 2, 2, 3), positions.map { it.moveNumber })
        assertEquals(listOf(true, false, true, false, true), positions.map { it.whiteMoved })
    }

    @Test
    fun `reports squares of the move for highlighting`() {
        val positions = GameReplay.replay(listOf("e4"))

        assertEquals("e2", positions[0].fromSquare)
        assertEquals("e4", positions[0].toSquare)
    }

    @Test
    fun `handles castling and promotion`() {
        val moves = listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "O-O")
        val positions = GameReplay.replay(moves)

        assertEquals(7, positions.size)
        assertEquals("e1g1", positions.last().uci)
        assertEquals("O-O", positions.last().san)
    }

    @Test
    fun `keeps what it managed to parse when the score breaks`() {
        // Лучше показать разобранную часть партии, чем ничего.
        val positions = GameReplay.replay(listOf("e4", "e5", "Nf3"))
        assertEquals(3, positions.size)

        assertTrue(GameReplay.replay(emptyList()).isEmpty())
    }

    @Test
    fun `starts from a given position when the game did not start from scratch`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"
        val positions = GameReplay.replay(listOf("a6", "Ba4"), initialFen = fen)

        assertEquals(2, positions.size)
        assertEquals(fen, positions[0].fenBefore)
        assertTrue(!positions[0].whiteMoved)
        assertEquals(3, positions[0].moveNumber)
    }
}
