package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.HangingPieceFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureSetBuilderTest {

    private val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    /** Детектор, который всегда падает — проверяет, что сборка не рушится из-за одного сломанного источника. */
    private class ExplodingDetector : Detector {
        override val name: String = "exploding"
        override fun detect(board: Board): List<Fact> = throw IllegalStateException("boom")
    }

    @Test
    fun `feature set builder returns an empty feature set for a broken fen`() {
        val builder = FeatureSetBuilder(listOf(HangingDetector()))

        val result = builder.features("this is not a fen at all")

        assertEquals(FeatureSet.empty("this is not a fen at all"), result)
    }

    @Test
    fun `feature set builder keeps facts from other detectors when one throws`() {
        val builder = FeatureSetBuilder(listOf(ExplodingDetector(), HangingDetector()))

        // Чёрная пешка e3 висит под белой ладьёй — HangingDetector должен её найти
        // несмотря на то, что первый детектор в списке всегда падает.
        val result = builder.features("4k3/8/8/8/8/4p3/8/4R2K w - - 0 1")

        assertTrue(result.facts.any { it is HangingPieceFact })
    }

    @Test
    fun `feature set builder reports zero material and the opening phase for the starting position`() {
        val builder = FeatureSetBuilder(emptyList())

        val result = builder.features(STARTING_FEN)

        assertEquals(0, result.materialCentipawns)
        assertEquals(GamePhase.OPENING, result.phase)
        assertEquals(20, result.mobilityOf(com.github.bhlangonijr.chesslib.Side.WHITE))
        assertEquals(20, result.mobilityOf(com.github.bhlangonijr.chesslib.Side.BLACK))
    }
}
