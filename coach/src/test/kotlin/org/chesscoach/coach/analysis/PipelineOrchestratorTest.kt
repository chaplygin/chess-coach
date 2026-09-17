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
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
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

        // Угрозы нет (просадка ниже порога), плана нет (в дебюте начальной
        // расстановки не за что зацепиться), ловушки нет (в основном поиске
        // только одна линия). Зато настоящий KingSafetyDetector честно замечает:
        // ...d5 отняло у чёрного короля пешку прикрытия на d7 — это и есть
        // причина, добытая диффом признаков, а не выдуманная.
        val whyThisMove = explanation.section<Section.WhyThisMove>()
        checkNotNull(whyThisMove)
        assertEquals("Nf3", whyThisMove.moveSan)
        assertEquals(listOf(ReasonTier.KING_SAFETY), whyThisMove.reasons.map { it.tier })

        assertEquals(
            listOf(whyThisMove, Section.ExpectedContinuation(listOf("e4", "e5", "Nf3"))),
            explanation.sections,
        )
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
    }
}
