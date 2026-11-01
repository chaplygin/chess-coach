package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.AlternativeLine
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.HumanMove
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.coach.model.ReasonTier
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

    private companion object {
        /** Насколько ход должен превосходить второй по силе, чтобы молчание детекторов стало подозрительным. */
        const val UNEXPLAINED_EDGE_CP = 150

        /** Сколько полуходов варианта показывать: дальше он уже не читается. */
        const val LINE_PLIES = 5
    }

    suspend fun explain(
        fen: String,
        playedMoveUci: String? = null,
        opening: String? = null,
    ): PositionExplanation {
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

        // 2. Нулевой ход: что соперник сделал бы, будь ход его. Считается до
        // сборки разделов, потому что угроза — это пункт внутри "моей позиции",
        // а не самостоятельная новость где-то ниже по тексту.
        val threat = bestLine?.let { threatAnalyzer.analyse(fen, it.score) }

        // 3. Оценка позиции и расклад "моя позиция / моя атака" — без движка,
        // прямо из уже посчитанных признаков. Это первое, что читает человек,
        // поэтому идёт в списке раньше конкретного хода.
        sections += PositionAssessor.assess(fen, featuresBefore, sideToMove, opening)
        sections += myPositionSection(featuresBefore, sideToMove, threat)
        sections += myAttackSection(featuresBefore, sideToMove)

        // 4. Почему лучший ход хорош, профилактика и разбор сыгранного хода
        // в сравнении с лучшим.
        var classification: MoveClassification? = null
        if (bestLine != null && bestMoveUci != null) {
            val bestDiff = reasonDiffAnalyzer.analyse(fen, bestMoveUci, bestLine.score)
            bestDiff.whyThisMove
                ?.let {
                    it.copy(
                        pointIsInTheLine = pointIsInTheLine(it, bestLine, mainLines),
                        openingCaveat = OpeningPrinciples.caveat(fen, bestMoveUci),
                    )
                }
                ?.let { sections += it }

            if (playedMoveUci != null) {
                // Если сыгран лучший ход, второй раз спрашивать движок незачем —
                // диф уже посчитан выше.
                val playedDiff = if (playedMoveUci == bestMoveUci) {
                    bestDiff
                } else {
                    reasonDiffAnalyzer.analyse(fen, playedMoveUci, bestLine.score)
                }

                val evaluation = evaluatePlayedMove(
                    bestScore = bestLine.score,
                    playedMoveUci = playedMoveUci,
                    bestMoveUci = bestMoveUci,
                    mainLines = mainLines,
                    fallbackPlayedScore = playedDiff.moveScoreFromMover,
                )

                if (evaluation != null) {
                    classification = evaluation.classification

                    // Чего лишился сыгранный ход по сравнению с лучшим: факты,
                    // которые дал бы лучший ход, но не дал сыгранный. Когда ходы
                    // совпадают, playedDiff — это тот же самый bestDiff, и
                    // разница закономерно пустая.
                    val bestFacts = bestDiff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList()
                    val playedFacts = playedDiff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList()
                    val missed = bestFacts.filterNot { it in playedFacts }

                    val playedSan = MoveNotation.uciToSanOrRaw(fen, listOf(playedMoveUci)).first()
                    sections += Section.PlayedMove(
                        playedSan = playedSan,
                        playedUci = playedMoveUci,
                        bestSan = bestMoveSan,
                        bestUci = bestMoveUci,
                        classification = evaluation.classification,
                        centipawnLoss = evaluation.centipawnLoss,
                        missed = missed,
                        // Когда сыгран лучший ход, наказывать не за что: его
                        // "опровержение" — это просто лучший ответ соперника в
                        // нормальной позиции, и подавать его как расплату
                        // значило бы пугать читателя на ровном месте.
                        refutationSan = playedDiff.replyMoveSan.takeIf { playedMoveUci != bestMoveUci },
                        punishment = if (playedMoveUci == bestMoveUci) emptyList() else playedDiff.opponentGains,
                    )
                }
            }

            // Профилактика — третий тир причин, и обычно она уже стоит пунктом
            // в списке "почему этот ход". Отдельный раздел тогда повторяет то
            // же самое другими словами, и разбор выглядит так, будто тренер
            // забыл, что уже это сказал.
            val alreadyAmongReasons = bestDiff.whyThisMove
                ?.reasons
                ?.any { reason -> reason.facts.any { it === bestDiff.prophylaxis } }
                ?: false
            if (!alreadyAmongReasons) {
                bestDiff.prophylaxis?.let { sections += Section.Prophylaxis(it) }
            }
        }

        // 5-6. Опровержение ловушки и быстрый поиск малой глубины — внутри TrapSelector,
        // и только если вообще есть из чего выбирать (нужна хотя бы одна альтернатива лучшему ходу).
        var quickLines = emptyList<EngineLine>()
        if (bestLine != null && mainLines.size > 1) {
            quickLines = engineClient.analyse(fen, CoachSearchLimits(movetimeMs = 50, depth = 4, multiPv = 3))
            trapSelector.select(fen, sideToMove, mainLines, quickLines)?.let { sections += it }
        }

        if (bestLine != null && bestMoveUci != null) {
            sections += Section.BestMoves(
                computerLineSan = MoveNotation.uciToSanOrRaw(fen, bestLine.pv.take(LINE_PLIES)),
                human = humanMove(fen, bestLine, mainLines, quickLines),
            )
        }

        if (bestLine != null) {
            alternativesSection(fen, sideToMove, bestLine, mainLines)?.let { sections += it }
        }

        return PositionExplanation(
            fen = fen,
            sideToMove = sideToMove,
            scoreFromWhite = scoreFromWhite,
            bestMoveSan = bestMoveSan,
            bestMoveUci = bestMoveUci ?: "",
            sections = sections.sortedBy(::readingOrder),
            featuresBefore = featuresBefore,
            playedMoveSan = playedMoveUci?.let { MoveNotation.uciToSanOrRaw(fen, listOf(it)).first() },
            classification = classification,
        )
    }

    /**
     * МОЯ ПОЗИЦИЯ: за что могут наказать прямо сейчас — с точки зрения
     * стороны, чей сейчас ход.
     *
     * `favours` у факта — это сторона, которой он на пользу (см. [Fact]), а не
     * его владелец: моя повисшая фигура на пользу сопернику, поэтому
     * "мои" факты ищутся через `favouring(mover.flip())`, а не через
     * фильтрацию по стороне фигуры.
     */
    private fun myPositionSection(features: FeatureSet, mover: Side, threat: ThreatFact?): Section {
        val opponent = mover.flip()
        val kingSafety = features.pick<KingSafetyFact>().filter { it.kingSide == mover }
        val loosePieces = features.favouring(opponent).filter {
            it is HangingPieceFact || it is TrappedPieceFact || it is BackRankFact
        }
        // Раздел собирается всегда, даже когда все четыре пункта пусты:
        // "Угроза: нет" — это ответ на вопрос читателя, а пропущенный раздел он
        // прочтёт как недосмотр тренера.
        return Section.MyPosition(
            kingSafety = kingSafety,
            loosePieces = loosePieces,
            threat = threat,
            opponentPlan = PlanBuilder.build(features, opponent),
        )
    }

    /** МОЯ АТАКА: чем можно наказать соперника — те же факты, но на пользу мне, и к чему я веду дело. */
    private fun myAttackSection(features: FeatureSet, mover: Side): Section {
        val mine = features.favouring(mover)
        val mate = mine.filter { it is MateFact || it is BackRankFact }
        val captures = mine.filter {
            it is HangingPieceFact || it is ForkFact || it is PinFact || it is SkewerFact ||
                it is DiscoveredAttackFact || it is OverloadedDefenderFact
        }
        val activity = mine.filter {
            it is MobilityEdgeFact || it is OutpostFact || it is OpenFileRookFact ||
                it is CenterControlFact || it is DevelopmentFact
        }
        return Section.MyAttack(
            mate = mate,
            captures = captures,
            activity = activity,
            plan = PlanBuilder.build(features, mover),
        )
    }

    /**
     * «Человеческий» ход: первый ход поиска малой глубины, который основной
     * поиск оценил почти как лучший. Ход, замеченный при малой глубине, виден и
     * человеку; глубокий поиск отсекает ходы, которые только кажутся хорошими.
     */
    private suspend fun humanMove(
        fen: String,
        bestLine: EngineLine,
        mainLines: List<EngineLine>,
        quickLines: List<EngineLine>,
    ): HumanMove? {
        val bestUci = bestLine.pv.firstOrNull() ?: return null
        val quickFirst = quickLines.sortedBy { it.multiPvIndex }.firstOrNull()?.pv?.firstOrNull() ?: return null
        if (quickFirst == bestUci) return null
        val line = mainLines.firstOrNull { it.pv.firstOrNull() == quickFirst } ?: return null
        val loss = ScoreFormat.centipawnLoss(bestLine.score, line.score)
        if (loss > CoachThresholds.HUMAN_MOVE_MAX_LOSS_CP) return null

        val diff = reasonDiffAnalyzer.analyse(fen, quickFirst, bestLine.score)
        return HumanMove(
            lineSan = MoveNotation.uciToSanOrRaw(fen, line.pv.take(LINE_PLIES)),
            lossVsBestCentipawns = loss,
            facts = diff.whyThisMove?.reasons?.flatMap { it.facts } ?: emptyList(),
        )
    }

    private data class PlayedMoveEvaluation(val classification: MoveClassification, val centipawnLoss: Int)

    /** Null — движку нечем оценить сыгранный ход (совсем экзотическая позиция), классифицировать нечего. */
    private fun evaluatePlayedMove(
        bestScore: Score,
        playedMoveUci: String,
        bestMoveUci: String,
        mainLines: List<EngineLine>,
        fallbackPlayedScore: Score?,
    ): PlayedMoveEvaluation? {
        val playedScore = playedScoreOf(bestScore, playedMoveUci, bestMoveUci, mainLines, fallbackPlayedScore)
            ?: return null

        val cpLoss = ScoreFormat.centipawnLoss(bestScore, playedScore)
        val missedForcedMate = bestScore is Score.Mate && bestScore.movesToMate > 0 &&
            !(playedScore is Score.Mate && playedScore.movesToMate > 0)

        return PlayedMoveEvaluation(MoveClassification.of(cpLoss, missedForcedMate), cpLoss)
    }

    /**
     * Оценка сыгранного хода почти всегда уже известна: либо это лучший ход
     * (потери нет), либо один из показанных MultiPV-альтернатив (оценка есть
     * прямо в нём), и только если сыгранный ход выпал даже из тройки лучших —
     * пригождается запасной вариант, добытый в [ReasonDiffAnalyzer] через
     * подтверждающий поиск после этого самого хода.
     */
    private fun playedScoreOf(
        bestScore: Score,
        playedMoveUci: String,
        bestMoveUci: String,
        mainLines: List<EngineLine>,
        fallbackPlayedScore: Score?,
    ): Score? = when {
        playedMoveUci == bestMoveUci -> bestScore
        else -> mainLines.firstOrNull { it.pv.firstOrNull() == playedMoveUci }?.score ?: fallbackPlayedScore
    }

    /**
     * Ход заметно сильнее остальных, а объяснить его нечем, кроме мелочей.
     *
     * Так выглядят жертвы: детекторы видят после хода разве что открытую
     * линию, потому что смысл раскрывается через несколько полуходов. Выдать
     * открытую линию за причину отдать ладью — значит соврать читателю
     * уверенным тоном, поэтому в таком случае честнее отослать к варианту.
     */
    private fun pointIsInTheLine(
        why: Section.WhyThisMove,
        bestLine: EngineLine?,
        mainLines: List<EngineLine>,
    ): Boolean {
        if (bestLine == null || mainLines.size < 2) return false

        val weighty = setOf(
            ReasonTier.MATERIAL_TACTICS,
            ReasonTier.KING_SAFETY,
            ReasonTier.PROPHYLAXIS,
        )
        if (why.reasons.any { it.tier in weighty }) return false

        val second = mainLines.first { it !== bestLine }
        return ScoreFormat.centipawnLoss(bestLine.score, second.score) >= UNEXPLAINED_EDGE_CP
    }

    /**
     * Порядок чтения разбора.
     *
     * Задаётся отдельно от порядка сборки: движок опрашивается в том порядке,
     * в каком дешевле считать, а читать надо в том, в каком тренер объясняет.
     * Сначала общая картина (оценка позиции, что мне грозит, чем я отвечаю),
     * потом конкретный ход и разбор сыгранного.
     */
    private fun readingOrder(section: Section): Int = when (section) {
        is Section.PositionAssessment -> 0
        is Section.MyPosition -> 1
        is Section.MyAttack -> 2
        is Section.BestMoves -> 3
        is Section.WhyThisMove -> 4
        is Section.PlayedMove -> 5
        is Section.Prophylaxis -> 6
        is Section.Trap -> 7
        is Section.Alternatives -> 8
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
}
