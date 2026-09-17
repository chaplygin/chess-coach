package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.test.runTest
import org.chesscoach.coach.detectors.BackRankDetector
import org.chesscoach.coach.detectors.DevelopmentDetector
import org.chesscoach.coach.detectors.DiscoveredAttackDetector
import org.chesscoach.coach.detectors.FeatureSetBuilder
import org.chesscoach.coach.detectors.ForkDetector
import org.chesscoach.coach.detectors.HangingDetector
import org.chesscoach.coach.detectors.KingSafetyDetector
import org.chesscoach.coach.detectors.OverloadDetector
import org.chesscoach.coach.detectors.PawnStructureDetector
import org.chesscoach.coach.detectors.PieceActivityDetector
import org.chesscoach.coach.detectors.PinSkewerDetector
import org.chesscoach.coach.detectors.TrappedPieceDetector
import org.chesscoach.coach.engine.FakeEngineClient
import org.chesscoach.coach.model.Section
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сквозной прогон конвейера: настоящие детекторы (через [FeatureSetBuilder]),
 * но движок — замороженный [FakeEngineClient], чтобы разбор был
 * детерминированным.
 */
class PipelineOrchestratorTest {

    private fun realFeatureSource() = FeatureSetBuilder(
        listOf(
            HangingDetector(),
            ForkDetector(),
            PinSkewerDetector(),
            BackRankDetector(),
            OverloadDetector(),
            DiscoveredAttackDetector(),
            TrappedPieceDetector(),
            KingSafetyDetector(),
            PawnStructureDetector(),
            PieceActivityDetector(),
            DevelopmentDetector(),
        ),
    )

    @Test
    fun `played move that loses more than the top line is classified as a mistake`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                // Основной поиск: единственная найденная линия — 1.e4 e5 2.Kf3.
                CommonFens.START to listOf(
                    EngineLine(
                        multiPvIndex = 1,
                        score = Score.Cp(30),
                        pv = listOf("e2e4", "e7e5", "g1f3"),
                        depth = 20,
                    ),
                ),
                // Нулевой ход: угроза слабая (просадка 40 — ниже порога в 60), секции не будет.
                CommonFens.FLIP_START to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("d7d5"), depth = 10),
                ),
                // Подтверждение лучшего хода 1.e4: конвейер теперь считает причины
                // ЛУЧШЕГО хода всегда, а не только сыгранного.
                CommonFens.AFTER_E4 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(25), pv = listOf("e7e5"), depth = 12),
                ),
                // Подтверждение сыгранного хода 1.Kf3: чёрные отвечают d5, и по факту
                // это заметно хуже, чем 1.e4 из основной линии.
                CommonFens.AFTER_NF3 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(50), pv = listOf("d7d5"), depth = 12),
                ),
            ),
        )

        val orchestrator = PipelineOrchestrator(engine, realFeatureSource())

        val explanation = orchestrator.explain(CommonFens.START, playedMoveUci = "g1f3")

        assertEquals(Side.WHITE, explanation.sideToMove)
        assertEquals("e2e4", explanation.bestMoveUci)
        assertEquals("e4", explanation.bestMoveSan)
        assertEquals(Score.Cp(30), explanation.scoreFromWhite)
        assertEquals("Nf3", explanation.playedMoveSan)
        assertEquals(MoveClassification.MISTAKE, explanation.classification)

        // Угрозы нет (просадка ниже порога в 60 сантипешек).
        assertNull(explanation.section<Section.MyPosition>()?.threat)

        // СЫГРАННЫЙ ХОД — главное новое содержание: сыгран не лучший ход,
        // классификация и потеря в сантипешках совпадают с тем, что уже
        // проверено выше через explanation.classification.
        val playedMove = explanation.section<Section.PlayedMove>()
        checkNotNull(playedMove)
        assertEquals("Nf3", playedMove.playedSan)
        assertEquals("e4", playedMove.bestSan)
        assertFalse(playedMove.matchesBest)
        assertEquals(MoveClassification.MISTAKE, playedMove.classification)
        // Лучший ход держит +30, а после Nf3 d5 подтверждающий поиск даёт -50
        // с точки зрения белых — просадка ровно 80 сантипешек.
        assertEquals(80, playedMove.centipawnLoss)

        // ОЦЕНКА ПОЗИЦИИ строится без движка и появляется всегда.
        assertNotNull(explanation.section<Section.PositionAssessment>())
    }

    @Test
    fun `played best move is classified as best with no centipawn loss`() = runTest {
        val engine = FakeEngineClient(
            mapOf(
                CommonFens.START to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(30), pv = listOf("e2e4"), depth = 20),
                ),
                CommonFens.FLIP_START to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("d7d5"), depth = 10),
                ),
                CommonFens.AFTER_E4 to listOf(
                    EngineLine(multiPvIndex = 1, score = Score.Cp(-25), pv = listOf("e7e5"), depth = 12),
                ),
            ),
        )

        val orchestrator = PipelineOrchestrator(engine, realFeatureSource())

        val explanation = orchestrator.explain(CommonFens.START, playedMoveUci = "e2e4")

        assertEquals("e2e4", explanation.bestMoveUci)
        assertEquals(MoveClassification.BEST, explanation.classification)

        // Сыгран лучший ход: раздел всё равно есть — читателю важно увидеть,
        // что он ничего не упустил, а не молчание вместо подтверждения.
        val playedMove = explanation.section<Section.PlayedMove>()
        checkNotNull(playedMove)
        assertTrue(playedMove.matchesBest)
        assertEquals(0, playedMove.centipawnLoss)
        assertTrue(playedMove.missed.isEmpty())
    }
}
