package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreFormatWhiteShareTest {

    @Test
    fun `equal position splits the bar in half`() {
        assertEquals(0.5f, ScoreFormat.whiteShare(Score.Cp(0)), 0.001f)
    }

    @Test
    fun `black advantage shrinks the white part symmetrically`() {
        val white = ScoreFormat.whiteShare(Score.Cp(480))
        val black = ScoreFormat.whiteShare(Score.Cp(-480))

        assertTrue(black < 0.2f)
        assertEquals(1f, white + black, 0.001f)
    }

    @Test
    fun `mate fills the bar for the mating side`() {
        assertEquals(1f, ScoreFormat.whiteShare(Score.Mate(3)), 0f)
        assertEquals(0f, ScoreFormat.whiteShare(Score.Mate(-3)), 0f)
    }
}
