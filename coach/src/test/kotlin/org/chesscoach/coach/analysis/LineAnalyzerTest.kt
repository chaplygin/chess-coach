package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.runBlocking
import org.chesscoach.coach.CoachEngine
import org.chesscoach.coach.detectors.FeatureSetBuilder
import org.chesscoach.coach.model.MatePattern
import org.chesscoach.core.EngineLine
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAnalyzerTest {

    private val analyzer = LineAnalyzer(FeatureSetBuilder(CoachEngine.ALL_DETECTORS))

    @Test
    fun `the line from the real game shows the exchange black wins`() = runBlocking {
        val fen = "r2q1rk1/p1p2ppp/1pn5/3pp3/3PnPb1/P2QB3/1PP1N1PP/2KR1B1R b - - 1 12"
        val line = EngineLine(1, Score.Cp(484), listOf("e5f4", "e2f4", "g4d1", "d3d1", "c6e7", "f1d3"), 20)

        val reason = analyzer.analyse(fen, line)

        assertNotNull(reason)
        assertEquals(Side.BLACK, reason!!.leader)
        assertTrue(reason.toString(), (reason.materialSwing ?: 0) >= 150)
    }

    @Test
    fun `an equal score has no leader`() = runBlocking {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val reason = analyzer.analyse(fen, EngineLine(1, Score.Cp(20), listOf("e2e4", "e7e5"), 20))

        assertNull(reason!!.leader)
    }

    @Test
    fun `a finished back rank mate gets its name`() = runBlocking {
        val fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"
        val reason = analyzer.analyse(fen, EngineLine(1, Score.Mate(1), listOf("a1a8"), 20))

        assertEquals(1, reason!!.mateIn)
        assertEquals(MatePattern.BACK_RANK, reason.matePattern)
    }

    @Test
    fun `a smothered mate gets its name`() = runBlocking {
        val fen = "6rk/6pp/8/6N1/8/8/8/6K1 w - - 0 1"
        val reason = analyzer.analyse(fen, EngineLine(1, Score.Mate(1), listOf("g5f7"), 20))

        assertEquals(MatePattern.SMOTHERED, reason!!.matePattern)
    }
}
