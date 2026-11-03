package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.CoachEngine
import org.chesscoach.coach.detectors.FeatureSetBuilder
import org.chesscoach.core.GameEnd
import org.chesscoach.core.GameReplay
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveHeadline
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecapTest {

    // Детский мат: 3... Nf6 зевает, 4. Qxf7#.
    private val moves = listOf("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#")
    private val positions = GameReplay.replay(moves, null)

    private fun eval(ply: Int, cp: Int, classification: MoveClassification = MoveClassification.BEST) =
        PlyEvaluation(ply, classification, 0, false, Score.Cp(cp))

    private val evaluations = listOf(
        eval(0, 30), eval(1, 30), eval(2, 0), eval(3, 0), eval(4, 0),
        PlyEvaluation(5, MoveClassification.BLUNDER, 10_000, false, Score.Mate(1)),
        eval(6, 100_000),
    )

    private val builder = GameRecapBuilder(FeatureSetBuilder(CoachEngine.ALL_DETECTORS))

    @Test
    fun `the opponent blunder that allows mate is the main turning point`() {
        val recap = builder.build(RecapInput(iAmWhite = true, positions = positions, evaluations = evaluations, outcome = "WIN", status = "mate"))

        val blunder = recap.moments.single { it.ply == 5 }
        assertEquals(MomentKind.OPPONENT_ERROR, blunder.kind)
        assertEquals("3... Nf6 — ошибка соперника", blunder.title)
        assertTrue(recap.verdict, recap.verdict.startsWith("Победа, мат. Главный перелом — ошибка соперника 3... Nf6"))
        assertEquals(1, recap.opponent.blunders)
        assertEquals(0, recap.me.blunders)
        assertTrue(recap.me.accuracy > recap.opponent.accuracy)
    }

    @Test
    fun `the same blunder seen from the other side is my error`() {
        val recap = builder.build(RecapInput(iAmWhite = false, positions = positions, evaluations = evaluations, outcome = "LOSS", status = "mate"))

        assertEquals(MomentKind.MY_ERROR, recap.moments.single { it.ply == 5 }.kind)
    }

    @Test
    fun `a quiet game has no moments and says so`() {
        val quiet = evaluations.map { it.copy(classification = MoveClassification.BEST, scoreAfterWhite = Score.Cp(20)) }
        val recap = builder.build(RecapInput(true, positions.take(5), quiet.take(5), "DRAW"))

        assertTrue(recap.moments.isEmpty())
        assertTrue(recap.verdict, recap.verdict.contains("Крупных переломов не было"))
    }

    @Test
    fun `move headline reads like the move list and shows both scores`() {
        val headline = MoveHeadline.of(positions[5], Score.Cp(0), Score.Mate(1), MoveClassification.BLUNDER)
        assertEquals("3... Nf6 — зевок: 0.0 → #1", headline)
    }

    @Test
    fun `the final mate is read from the board`() {
        assertEquals("Мат. Победили белые.", GameEnd.of(positions.last().fenAfter))
        assertEquals(null, GameEnd.of(positions.first().fenAfter))
    }

    @Test
    fun `mate in one is found on the board for the side to move`() {
        assertEquals(listOf("Qxf7#"), MatingMoves.of(positions[5].fenAfter, Side.WHITE))
        assertTrue(MatingMoves.gives(positions[5].fenAfter, "h5f7"))
    }
}
