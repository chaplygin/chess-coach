package org.chesscoach.coach

import org.chesscoach.coach.analysis.PipelineOrchestrator
import org.chesscoach.coach.detectors.BackRankDetector
import org.chesscoach.coach.detectors.DevelopmentDetector
import org.chesscoach.coach.detectors.Detector
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
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.text.TextRenderer

/**
 * Единственная публичная точка входа движка объяснений.
 *
 * Экранам приложения не нужно знать ни про детекторы, ни про конвейер, ни про
 * рендерер — им нужен разбор позиции текстом. Всё остальное остаётся внутри и
 * может меняться, не трогая интерфейс.
 */
class CoachEngine(engineClient: EngineClient) {

    private val featureSource = FeatureSetBuilder(ALL_DETECTORS)
    private val pipeline = PipelineOrchestrator(engineClient, featureSource)

    /** Структура разбора: факты и разделы, ещё не текст. */
    suspend fun explain(fen: String, playedMoveUci: String? = null): PositionExplanation =
        pipeline.explain(fen, playedMoveUci)

    /** Готовый разбор тренера на русском. */
    suspend fun explainAsText(fen: String, playedMoveUci: String? = null): String =
        TextRenderer.render(explain(fen, playedMoveUci))

    companion object {
        const val VERSION: String = "M5"

        /**
         * Порядок детекторов не влияет на смысл: раскладкой по важности
         * занимается дифф-анализ, а не то, кто отработал раньше.
         */
        val ALL_DETECTORS: List<Detector> = listOf(
            HangingDetector(),
            ForkDetector(),
            PinSkewerDetector(),
            DiscoveredAttackDetector(),
            BackRankDetector(),
            OverloadDetector(),
            TrappedPieceDetector(),
            KingSafetyDetector(),
            PawnStructureDetector(),
            PieceActivityDetector(),
            DevelopmentDetector(),
        )
    }
}
