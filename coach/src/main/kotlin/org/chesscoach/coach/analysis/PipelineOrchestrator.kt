package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.AlternativeLine
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat

/**
 * Единственная точка сборки разбора позиции.
 *
 * Сама ничего не выдумывает и не пересчитывает оценку: каждое число либо
 * приходит от движка дословно, либо посчитано уже готовым анализатором.
 * Здесь только порядок обращений к движку и решение, какие секции попали в
 * список — раздел без доказательств просто не появляется, вместо пустого
 * заголовка.
 */
class PipelineOrchestrator(
    private val engineClient: EngineClient,
    private val featureSource: FeatureSource,
) {
    private val threatAnalyzer = ThreatAnalyzer(engineClient, featureSource)
    private val reasonDiffAnalyzer = ReasonDiffAnalyzer(engineClient, featureSource)
    private val trapSelector = TrapSelector(engineClient)

    suspend fun explain(fen: String, playedMoveUci: String? = null): PositionExplanation {
        val sideToMove = NullMoveUtil.sideToMoveOf(fen)
        val featuresBefore = featureSource.features(fen)

        // 1. Основной поиск: он даёт лучший ход, альтернативы и саму оценку позиции.
        val mainLines = engineClient
            .analyse(fen, CoachSearchLimits(movetimeMs = 900, multiPv = 3))
            .sortedBy { it.multiPvIndex }
        val bestLine = mainLines.firstOrNull()

        val bestMoveUci = bestLine?.pv?.firstOrNull()
        val bestMoveSan = bestMoveUci?.let { MoveNotation.uciToSanOrRaw(fen, listOf(it)).first() } ?: ""
        val scoreFromWhite = bestLine?.score?.let { ScoreFormat.toWhitePerspective(it, sideToMove) }

        val sections = mutableListOf<Section>()

        // 2. Нулевой ход: секция УГРОЗА.
        if (bestLine != null) {
            threatAnalyzer.analyse(fen, bestLine.score)?.let { sections += Section.Threat(it) }
        }

        // 3. Подтверждение хода: секции "почему этот ход" и "профилактика",
        // плюс классификация сыгранного хода, если он был.
        var classification: MoveClassification? = null
        if (bestLine != null && bestMoveUci != null) {
            val moveToExplain = playedMoveUci ?: bestMoveUci
            val diff = reasonDiffAnalyzer.analyse(fen, moveToExplain, bestLine.score)
            diff.whyThisMove?.let { sections += it }
            diff.prophylaxis?.let { sections += Section.Prophylaxis(it) }

            if (playedMoveUci != null) {
                classification = classify(bestLine.score, playedMoveUci, bestMoveUci, mainLines, diff.moveScoreFromMover)
            }
        }

        // План строится без движка — прямо из уже посчитанных признаков позиции.
        val planSteps = PlanBuilder.build(featuresBefore, sideToMove)
        if (planSteps.isNotEmpty()) sections += Section.Plan(planSteps)

        // 4-5. Опровержение ловушки и быстрый поиск малой глубины — внутри TrapSelector,
        // и только если вообще есть из чего выбирать (нужна хотя бы одна альтернатива лучшему ходу).
        if (bestLine != null && mainLines.size > 1) {
            val quickLines = engineClient.analyse(fen, CoachSearchLimits(movetimeMs = 50, depth = 4, multiPv = 3))
            trapSelector.select(fen, sideToMove, mainLines, quickLines)?.let { sections += it }
        }

        if (bestLine != null) {
            alternativesSection(fen, sideToMove, bestLine, mainLines)?.let { sections += it }
            expectedContinuationSection(fen, bestLine)?.let { sections += it }
        }

        kingSafetySection(featuresBefore.facts, sideToMove)?.let { sections += it }
        materialSection(featuresBefore.materialCentipawns, featuresBefore.facts)?.let { sections += it }

        return PositionExplanation(
            fen = fen,
            sideToMove = sideToMove,
            scoreFromWhite = scoreFromWhite,
            bestMoveSan = bestMoveSan,
            bestMoveUci = bestMoveUci ?: "",
            sections = sections,
            featuresBefore = featuresBefore,
            playedMoveSan = playedMoveUci?.let { MoveNotation.uciToSanOrRaw(fen, listOf(it)).first() },
            classification = classification,
        )
    }

    /**
     * Оценка сыгранного хода почти всегда уже известна: либо это лучший ход
     * (потери нет), либо один из показанных MultiPV-альтернатив (оценка есть
     * прямо в нём), и только если сыгранный ход выпал даже из тройки лучших —
     * пригождается запасной вариант, добытый в [ReasonDiffAnalyzer] через
     * подтверждающий поиск после этого самого хода.
     */
    private fun classify(
        bestScore: Score,
        playedMoveUci: String,
        bestMoveUci: String,
        mainLines: List<EngineLine>,
        fallbackPlayedScore: Score?,
    ): MoveClassification? {
        val playedScore = when {
            playedMoveUci == bestMoveUci -> bestScore
            else -> mainLines.firstOrNull { it.pv.firstOrNull() == playedMoveUci }?.score ?: fallbackPlayedScore
        } ?: return null

        val cpLoss = ScoreFormat.centipawnLoss(bestScore, playedScore)
        val missedForcedMate = bestScore is Score.Mate && bestScore.movesToMate > 0 &&
            !(playedScore is Score.Mate && playedScore.movesToMate > 0)

        return MoveClassification.of(cpLoss, missedForcedMate)
    }

    private fun alternativesSection(
        fen: String,
        sideToMove: Side,
        bestLine: EngineLine,
        mainLines: List<EngineLine>,
    ): Section? {
        val alternatives = mainLines.filter { it.multiPvIndex != bestLine.multiPvIndex }
        if (alternatives.isEmpty()) return null

        val lines = alternatives.mapNotNull { line ->
            val uci = line.pv.firstOrNull() ?: return@mapNotNull null
            val san = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first()
            val afterFirstFen = applyUciMove(fen, uci)
            val continuationSan = if (afterFirstFen != null && line.pv.size > 1) {
                MoveNotation.uciToSanOrRaw(afterFirstFen, line.pv.drop(1).take(3))
            } else {
                emptyList()
            }

            AlternativeLine(
                moveSan = san,
                scoreFromWhite = ScoreFormat.toWhitePerspective(line.score, sideToMove),
                lossVsBestCentipawns = ScoreFormat.centipawnLoss(bestLine.score, line.score),
                continuationSan = continuationSan,
            )
        }

        return if (lines.isEmpty()) null else Section.Alternatives(lines)
    }

    private fun expectedContinuationSection(fen: String, bestLine: EngineLine): Section? {
        if (bestLine.pv.isEmpty()) return null
        val san = MoveNotation.uciToSanOrRaw(fen, bestLine.pv)
        return Section.ExpectedContinuation(san)
    }

    /** Предупреждаем о безопасности именно своего короля — не короля соперника. */
    private fun kingSafetySection(facts: List<Fact>, sideToMove: Side): Section? {
        val fact = facts.filterIsInstance<KingSafetyFact>().firstOrNull { it.kingSide == sideToMove } ?: return null
        return Section.KingSafetyNote(fact)
    }

    private fun materialSection(materialCentipawns: Int, facts: List<Fact>): Section? {
        if (kotlin.math.abs(materialCentipawns) < CoachThresholds.MATERIAL_NOTE_CP) return null
        val supporting = facts.filter { it is HangingPieceFact || it is TrappedPieceFact || it is MateFact }
        return Section.MaterialNote(materialCentipawns, supporting)
    }
}
