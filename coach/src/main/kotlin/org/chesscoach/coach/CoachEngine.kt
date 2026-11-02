package org.chesscoach.coach

import org.chesscoach.coach.analysis.AnalyzedGame
import org.chesscoach.coach.analysis.ErrorReport
import org.chesscoach.coach.analysis.ErrorReportBuilder
import org.chesscoach.coach.analysis.PipelineOrchestrator
import org.chesscoach.coach.detectors.BackRankDetector
import org.chesscoach.coach.detectors.DevelopmentDetector
import org.chesscoach.coach.detectors.Detector
import org.chesscoach.coach.detectors.DiscoveredAttackDetector
import org.chesscoach.coach.detectors.ExchangeDetector
import org.chesscoach.coach.detectors.EndgameDetector
import org.chesscoach.coach.detectors.FeatureSetBuilder
import org.chesscoach.coach.detectors.ForkDetector
import org.chesscoach.coach.detectors.ForkThreatDetector
import org.chesscoach.coach.detectors.PawnBreakDetector
import org.chesscoach.coach.detectors.RemoveDefenderDetector
import org.chesscoach.coach.detectors.WeakSquareDetector
import org.chesscoach.coach.detectors.HangingDetector
import org.chesscoach.coach.detectors.KingSafetyDetector
import org.chesscoach.coach.detectors.OverloadDetector
import org.chesscoach.coach.detectors.PawnStructureDetector
import org.chesscoach.coach.detectors.PieceActivityDetector
import org.chesscoach.coach.detectors.PinSkewerDetector
import org.chesscoach.coach.detectors.TrappedPieceDetector
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Variation
import org.chesscoach.coach.model.Variations
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.MoveInput
import org.chesscoach.core.MoveInputResult

/**
 * Что вышло из хода, который человек предложил сам.
 *
 * Отдельные варианты для "такого хода нет" и "подходит несколько" не
 * формальность: на набранное bxc4 при двух подходящих ходах ответ "такого хода
 * здесь нет" — неправда, и человек пойдёт искать ошибку в позиции, а не в
 * своей записи.
 */
sealed interface CandidateResult {
    data class Explained(
        val uci: String,
        val text: String,
        /** Вариант с этим ходом: его можно проиграть на доске. */
        val variation: Variation? = null,
    ) : CandidateResult

    /** Запись не разобрана или такой ход в этой позиции невозможен. */
    object NotLegal : CandidateResult

    /** Подходит больше одного хода — например bxc4 и Bxc4. */
    object Ambiguous : CandidateResult

    /** Ход законный, но движку нечего о нём сказать. */
    object NoVerdict : CandidateResult
}

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
    suspend fun explain(
        fen: String,
        playedMoveUci: String? = null,
        opening: String? = null,
    ): PositionExplanation = pipeline.explain(fen, playedMoveUci, opening)

    /** Готовый разбор тренера на русском. */
    suspend fun explainAsText(
        fen: String,
        playedMoveUci: String? = null,
        opening: String? = null,
    ): String = TextRenderer.render(explain(fen, playedMoveUci, opening))

    /**
     * Разбор хода, который человек предложил сам.
     *
     * Считается тем же конвейером, что и обычный разбор: сравнение своего
     * варианта с лучшим — это ровно та же задача, что и разбор сыгранного хода,
     * и заводить для неё второй путь означало бы завести второй источник
     * истины, который рано или поздно разойдётся с первым.
     */
    suspend fun tryCandidate(fen: String, input: String): CandidateResult {
        val parsed = when (val result = MoveInput.parse(fen, input)) {
            MoveInputResult.Unknown -> return CandidateResult.NotLegal
            MoveInputResult.Ambiguous -> return CandidateResult.Ambiguous
            is MoveInputResult.Parsed -> result
        }

        val explanation = pipeline.explain(fen, parsed.uci)
        val text = TextRenderer.renderCandidate(explanation) ?: return CandidateResult.NoVerdict
        val variation = Variations.of(explanation, CANDIDATE_TITLE).firstOrNull { it.title == CANDIDATE_TITLE }
        return CandidateResult.Explained(parsed.uci, text, variation)
    }

    /** Сводка повторяющихся ошибок по уже разобранным партиям: движок здесь не нужен. */
    suspend fun errorReport(games: List<AnalyzedGame>): ErrorReport = ErrorReportBuilder(featureSource).build(games)

    companion object {
        const val VERSION: String = "M5"

        private const val CANDIDATE_TITLE = "Ваш вариант"

        /**
         * Порядок детекторов не влияет на смысл: раскладкой по важности
         * занимается дифф-анализ, а не то, кто отработал раньше.
         */
        val ALL_DETECTORS: List<Detector> = listOf(
            HangingDetector(),
            ExchangeDetector(),
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
            ForkThreatDetector(),
            RemoveDefenderDetector(),
            WeakSquareDetector(),
            PawnBreakDetector(),
            EndgameDetector(),
        )
    }
}
