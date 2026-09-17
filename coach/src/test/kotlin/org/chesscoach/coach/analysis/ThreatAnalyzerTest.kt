package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlinx.coroutines.test.runTest
import org.chesscoach.coach.engine.FakeEngineClient
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.core.EngineLine
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatAnalyzerTest {

    @Test
    fun `threat is not printed when the drop is below the threshold`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                CommonFens.FLIP_FORK_SETUP to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(20), pv = listOf("d4c2"), depth = 10),
                ),
            ),
        )
        val analyzer = ThreatAnalyzer(engine, MapFeatureSource(emptyMap()))

        // Просадка тут всего 20 сантипешек — заметно ниже THREAT_MIN_CP (60).
        val threat = analyzer.analyse(CommonFens.FORK_SETUP, normalScore = Score.Cp(0))

        assertNull(threat)
    }

    @Test
    fun `threat is not printed when the side to move is in check`() = runTest {
        // Пустая карта ответов нарочно: приём нулевого хода должен отказать ещё
        // до обращения к движку, а не после.
        val engine = FakeEngineClient(emptyMap())
        val analyzer = ThreatAnalyzer(engine, MapFeatureSource(emptyMap()))

        val threat = analyzer.analyse(CommonFens.CHECK_FEN, normalScore = Score.Cp(0))

        assertNull(threat)
    }

    @Test
    fun `threat carries consequences straight from the detectors, not invented`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                CommonFens.FLIP_FORK_SETUP to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(200), pv = listOf("d4c2"), depth = 10),
                ),
            ),
        )

        val forkFact = ForkFact(
            from = Square.C2,
            piece = Piece.BLACK_KNIGHT,
            targets = listOf(Square.A1, Square.E1),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.C2, Square.A1, Square.E1),
        )
        val features = MapFeatureSource(
            mapOf(
                CommonFens.AFTER_FORK_MOVE to FeatureSet.empty(CommonFens.AFTER_FORK_MOVE).copy(facts = listOf(forkFact)),
            ),
        )

        val analyzer = ThreatAnalyzer(engine, features)

        // FORK_SETUP — белые ходят, но угроза — конь чёрных, взявший бы вилку.
        val threat = analyzer.analyse(CommonFens.FORK_SETUP, normalScore = Score.Cp(0))

        checkNotNull(threat)
        assertEquals("d4c2", threat.opponentMoveUci)
        assertEquals(200, threat.dropCentipawns)
        assertEquals(Side.BLACK, threat.favours)
        assertEquals(listOf(forkFact), threat.consequences)
    }

    @Test
    fun `threat only keeps consequences favouring the threatening side`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                CommonFens.FLIP_FORK_SETUP to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(200), pv = listOf("d4c2"), depth = 10),
                ),
            ),
        )

        val forkFact = ForkFact(
            from = Square.C2,
            piece = Piece.BLACK_KNIGHT,
            targets = listOf(Square.A1, Square.E1),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.C2, Square.A1, Square.E1),
        )
        // Факт "на пользу" стороне, которой не угрожали, — не последствие этой угрозы.
        val unrelatedFact = ForkFact(
            from = Square.A1,
            piece = Piece.WHITE_ROOK,
            targets = listOf(Square.A8, Square.H1),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.A1, Square.A8, Square.H1),
        )
        val features = MapFeatureSource(
            mapOf(
                CommonFens.AFTER_FORK_MOVE to FeatureSet.empty(CommonFens.AFTER_FORK_MOVE)
                    .copy(facts = listOf(forkFact, unrelatedFact)),
            ),
        )

        val threat = ThreatAnalyzer(engine, features).analyse(CommonFens.FORK_SETUP, normalScore = Score.Cp(0))

        checkNotNull(threat)
        assertEquals(listOf(forkFact), threat.consequences)
        assertTrue(unrelatedFact !in threat.consequences)
    }
}
