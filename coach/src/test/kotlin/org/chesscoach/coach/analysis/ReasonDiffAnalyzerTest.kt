package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlinx.coroutines.test.runTest
import org.chesscoach.coach.engine.FakeEngineClient
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.core.EngineLine
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasonDiffAnalyzerTest {

    @Test
    fun `no more than three reasons even when four tiers qualify`() = runTest {
        val forkFact = ForkFact(
            from = Square.D5,
            piece = Piece.WHITE_KNIGHT,
            targets = listOf(Square.C7, Square.E7),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D5, Square.C7, Square.E7),
        )
        val kingSafetyFact = KingSafetyFact(
            king = Square.E8,
            kingSide = Side.BLACK,
            attackerCount = 1,
            shieldPawnsMissing = 0,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.E8),
        )
        val outpostFact = OutpostFact(
            square = Square.D5,
            piece = Piece.WHITE_KNIGHT,
            owner = Side.WHITE,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D5),
        )
        val mobilityFact = MobilityEdgeFact(
            owner = Side.WHITE,
            ownMoves = 30,
            opponentMoves = 10,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.E1, Square.E8),
        )

        val afterMoveFeatures = FeatureSet.empty(CommonFens.AFTER_E4)
            .copy(facts = listOf(forkFact, kingSafetyFact, outpostFact, mobilityFact))

        val features = MapFeatureSource(mapOf(CommonFens.AFTER_E4 to afterMoveFeatures))

        // Пустая карта у движка — неизвестный FEN тихо даёт пустой список, и
        // проба профилактики (ей тут взяться неоткуда) просто не сработает.
        val engine = FakeEngineClient.ofOrEmpty(
            mapOf(
                CommonFens.AFTER_E4 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(-10), pv = listOf("e7e5"), depth = 12),
                ),
            ),
        )

        val analyzer = ReasonDiffAnalyzer(engine, features)
        val result = analyzer.analyse(CommonFens.START, "e2e4", mainScore = Score.Cp(30))

        checkNotNull(result.whyThisMove)
        val reasons = result.whyThisMove!!.reasons
        assertEquals(3, reasons.size)
        assertEquals(
            listOf(ReasonTier.MATERIAL_TACTICS, ReasonTier.KING_SAFETY, ReasonTier.STRUCTURE),
            reasons.map { it.tier },
        )
        assertTrue(reasons.none { it.tier == ReasonTier.ACTIVITY })
    }

    @Test
    fun `prophylaxis fact appears when the drop shrinks enough after the move`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                // Подтверждение хода: чем ответит соперник после e2e4.
                CommonFens.AFTER_E4 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(-30), pv = listOf("e7e5"), depth = 12),
                ),
                // Проба нулевым ходом ДО хода: угроза серьёзная (просадка 130).
                CommonFens.FLIP_START to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(100), pv = listOf("d7d5"), depth = 12),
                ),
                // Та же проба через полный ход: угроза почти сошла на нет (просадка 40).
                CommonFens.FLIP_AFTER_E4_E5 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("b8c6"), depth = 12),
                ),
            ),
        )
        val analyzer = ReasonDiffAnalyzer(engine, MapFeatureSource(emptyMap()))

        val result = analyzer.analyse(CommonFens.START, "e2e4", mainScore = Score.Cp(30))

        checkNotNull(result.prophylaxis)
        assertEquals("d5", result.prophylaxis!!.preventedMoveSan)
        assertEquals(90, result.prophylaxis!!.dropRemovedCentipawns)
        assertEquals(Side.WHITE, result.prophylaxis!!.favours)

        checkNotNull(result.whyThisMove)
        assertTrue(result.whyThisMove!!.reasons.any { it.tier == ReasonTier.PROPHYLAXIS })
    }

    @Test
    fun `no prophylaxis fact when the drop barely moves`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                CommonFens.AFTER_E4 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(-30), pv = listOf("e7e5"), depth = 12),
                ),
                CommonFens.FLIP_START to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(100), pv = listOf("d7d5"), depth = 12),
                ),
                // Просадка почти не изменилась: 130 -> 110, меньше PROPHYLAXIS_MIN_DROP_CP (40).
                CommonFens.FLIP_AFTER_E4_E5 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(80), pv = listOf("b8c6"), depth = 12),
                ),
            ),
        )
        val analyzer = ReasonDiffAnalyzer(engine, MapFeatureSource(emptyMap()))

        val result = analyzer.analyse(CommonFens.START, "e2e4", mainScore = Score.Cp(30))

        assertNull(result.prophylaxis)
    }

    @Test
    fun `no prophylaxis fact while the opponent still wants the same move`() = runTest {
        // Просадка упала достаточно, но соперник по-прежнему выбирает d7d5.
        // Значит выпад никуда не делся, а угроза ослабла по другой причине —
        // написать "ход снял d5" тут было бы неправдой.
        val engine = FakeEngineClient(
            mapOf(
                CommonFens.AFTER_E4 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(-30), pv = listOf("e7e5"), depth = 12),
                ),
                CommonFens.FLIP_START to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(100), pv = listOf("d7d5"), depth = 12),
                ),
                CommonFens.FLIP_AFTER_E4_E5 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("d7d5"), depth = 12),
                ),
            ),
        )
        val analyzer = ReasonDiffAnalyzer(engine, MapFeatureSource(emptyMap()))

        val result = analyzer.analyse(CommonFens.START, "e2e4", mainScore = Score.Cp(30))

        assertNull(result.prophylaxis)
    }
}
