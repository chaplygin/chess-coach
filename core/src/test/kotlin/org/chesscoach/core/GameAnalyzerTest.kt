package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GameAnalyzerTest {

    private fun ply(index: Int, whiteMoved: Boolean) = PlyPosition(
        index = index,
        fenBefore = "before-$index",
        fenAfter = "after-$index",
        san = "san-$index",
        uci = "e2e4",
        moveNumber = index / 2 + 1,
        whiteMoved = whiteMoved,
    )

    @Test
    fun `best move keeps almost the same evaluation for the mover`() {
        val positions = listOf(ply(0, whiteMoved = true))
        // До хода +30 белым. После хода чёрные (их ход) оценивают себя в -28,
        // то есть с точки зрения белых это +28 — почти та же оценка.
        val scores = listOf(Score.Cp(30), Score.Cp(-28))

        val result = GameAnalyzer.classify(positions, scores)

        assertEquals(MoveClassification.BEST, result[0].classification)
        assertEquals(2, result[0].centipawnLoss)
        assertFalse(result[0].missedForcedMate)
    }

    @Test
    fun `losing a big advantage is a blunder`() {
        val positions = listOf(ply(0, whiteMoved = true))
        // Белые упускали +300, а после хода у чёрных, чей ход, тоже +250.
        val scores = listOf(Score.Cp(300), Score.Cp(250))

        val result = GameAnalyzer.classify(positions, scores)

        assertEquals(MoveClassification.BLUNDER, result[0].classification)
        assertEquals(550, result[0].centipawnLoss)
    }

    @Test
    fun `missed forced mate is a blunder regardless of centipawn loss`() {
        val positions = listOf(ply(0, whiteMoved = true))
        val scores = listOf(Score.Mate(3), Score.Cp(50))

        val result = GameAnalyzer.classify(positions, scores)

        assertTrue(result[0].missedForcedMate)
        assertEquals(MoveClassification.BLUNDER, result[0].classification)
    }

    @Test
    fun `keeping a forced mate is not a miss`() {
        val positions = listOf(ply(0, whiteMoved = true))
        val scores = listOf(Score.Mate(3), Score.Mate(-2))

        val result = GameAnalyzer.classify(positions, scores)

        assertFalse(result[0].missedForcedMate)
    }

    @Test
    fun `score after move is reported from white's perspective`() {
        val positions = listOf(ply(0, whiteMoved = true), ply(1, whiteMoved = false))
        // Позиция после хода белых оценена с точки зрения чёрных: +40 им.
        val scores = listOf(Score.Cp(0), Score.Cp(40), Score.Cp(0))

        val result = GameAnalyzer.classify(positions, scores)

        assertEquals(Score.Cp(-40), result[0].scoreAfterWhite)
    }

    @Test
    fun `requires one more score than plies`() {
        val positions = listOf(ply(0, whiteMoved = true))
        try {
            GameAnalyzer.classify(positions, listOf(Score.Cp(0)))
            fail("должно упасть на несовпадении размеров")
        } catch (expected: IllegalArgumentException) {
            // ожидаемо
        }
    }
}
