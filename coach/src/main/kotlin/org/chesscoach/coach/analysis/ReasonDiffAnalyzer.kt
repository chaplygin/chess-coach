package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.BadBishopFact
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Evidence
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
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.ProphylaxisFact
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat

/**
 * Итог разбора «почему именно этот ход»: раздел для текста, отдельно вынесенный
 * факт профилактики (он же ложится в свою секцию [Section.Prophylaxis]) и
 * оценка самого хода с точки зрения того, кто его сделал — она же нужна
 * конвейеру для классификации сыгранного хода, и добывать её второй раз было
 * бы лишним обращением к движку.
 */
data class ReasonDiffResult(
    val whyThisMove: Section.WhyThisMove?,
    val prophylaxis: ProphylaxisFact?,
    val moveScoreFromMover: Score?,
    /** Лучший ответ соперника на разобранный ход — тот же поиск, что подтверждает оценку. */
    val replyMoveSan: String? = null,
    /**
     * Что соперник выигрывает от этого хода: признаки в его пользу, которых
     * до хода не было. Для лучшего хода список обычно пуст, для зевка в нём и
     * лежит ответ на вопрос «чем именно плохо».
     */
    val opponentGains: List<Fact> = emptyList(),
)

/**
 * «Почему именно этот ход»: разница признаков позиции до хода, после хода и
 * после лучшего ответа соперника, разложенная по тирам важности.
 *
 * Сантипешки по причинам не раскладываются никогда — раздел содержит только
 * категории (тир) и опорные факты, откуда они взялись. Это не забывчивость:
 * декомпозиции оценки на вклад отдельных идей у нас нет, а писать её от руки
 * значило бы выдумывать точность, которой не существует.
 */
class ReasonDiffAnalyzer(
    private val engineClient: EngineClient,
    private val featureSource: FeatureSource,
) {

    suspend fun analyse(fen: String, moveUci: String, mainScore: Score): ReasonDiffResult {
        val mover = NullMoveUtil.sideToMoveOf(fen)
        val fenAfterMove = applyUciMove(fen, moveUci)
            ?: return ReasonDiffResult(null, null, null)

        val featuresBefore = featureSource.features(fen)
        val featuresAfterMove = featureSource.features(fenAfterMove)

        // Тот же вызов движка одновременно подтверждает оценку хода (через
        // негамакс от лучшего ответа соперника) и даёт позицию для третьего
        // набора признаков — второе обращение специально под это не заводим.
        val replyLines = engineClient.analyse(fenAfterMove, CoachSearchLimits(movetimeMs = 400, multiPv = 1))
        val replyLine = replyLines.firstOrNull()
        val replyUci = replyLine?.pv?.firstOrNull()
        val fenAfterReply = replyUci?.let { applyUciMove(fenAfterMove, it) } ?: fenAfterMove
        val featuresAfterReply = featureSource.features(fenAfterReply)
        val moveScoreFromMover = replyLine?.let { ScoreFormat.negate(it.score) }

        val newFacts = collectNewFacts(mover, featuresBefore, featuresAfterMove, featuresAfterReply)
        val prophylaxis = detectProphylaxis(fen, mainScore, fenAfterReply, moveScoreFromMover, mover)

        val reasonFacts = newFacts + listOfNotNull(prophylaxis)
        val reasons = buildReasons(reasonFacts)

        val moveSan = MoveNotation.uciToSanOrRaw(fen, listOf(moveUci)).first()
        val whyThisMove = if (reasons.isEmpty()) null else Section.WhyThisMove(moveSan, reasons)

        return ReasonDiffResult(
            whyThisMove = whyThisMove,
            prophylaxis = prophylaxis,
            moveScoreFromMover = moveScoreFromMover,
            replyMoveSan = replyUci?.let { MoveNotation.uciToSanOrRaw(fenAfterMove, listOf(it)).first() },
            opponentGains = collectOpponentGains(mover, featuresBefore, featuresAfterMove),
        )
    }

    /**
     * Что появилось у соперника именно от этого хода.
     *
     * Сравнение идёт по [signature], а не по самим фактам: у одного и того же
     * признака в двух позициях отличаются приложенные строки движка и
     * записанные фигуры, и без этого новым выглядел бы каждый. Иначе тренер
     * объявил бы негативом хода то, что стояло на доске и до него.
     */
    private fun collectOpponentGains(
        mover: Side,
        before: FeatureSet,
        afterMove: FeatureSet,
    ): List<Fact> {
        val opponent = mover.flip()
        val known = before.favouring(opponent).map(::signature).toSet()
        return afterMove.favouring(opponent).filterNot { signature(it) in known }
    }

    /** Факты, которых не было до хода (или не было ещё и после ответа соперника) и которые на пользу ходившему. */
    private fun collectNewFacts(
        mover: Side,
        before: FeatureSet,
        afterMove: FeatureSet,
        afterReply: FeatureSet,
    ): List<Fact> {
        val beforeOwn = before.favouring(mover)
        val afterMoveOwn = afterMove.favouring(mover)
        val afterReplyOwn = afterReply.favouring(mover)

        val newAtMove = afterMoveOwn.filterNot { it in beforeOwn }
        val newAtReply = afterReplyOwn.filterNot { it in afterMoveOwn }

        return (newAtMove + newAtReply).distinct()
    }

    /**
     * Профилактика проверяется буквально: та же самая проба нулевым ходом, что
     * и для секции УГРОЗА, но во второй раз — в позиции через полный ход (наш
     * ход и ответ соперника), где снова очередь ходить нашей стороне. Только
     * там условия обеих проб сравнимы: один и тот же вопрос "а что если дать
     * сопернику лишний ход" задан дважды с разницей ровно в один полный ход.
     *
     * Одного падения просадки мало. Факт называет конкретный ход соперника, а
     * общий уровень угрозы мог снизиться совсем по другой причине — тогда мы
     * написали бы, что сняли выпад, который на самом деле остался в силе.
     * Поэтому требуется ещё и то, чтобы соперник перестал выбирать именно этот
     * ход.
     */
    private suspend fun detectProphylaxis(
        fen: String,
        mainScore: Score,
        fenAfterReply: String,
        moveScoreFromMover: Score?,
        mover: Side,
    ): ProphylaxisFact? {
        if (moveScoreFromMover == null) return null

        val before = measureNullMoveThreat(engineClient, fen, mainScore) ?: return null
        // Раз угрозы толком не было (просадка ниже порога), снимать нечего.
        if (before.dropCentipawns < CoachThresholds.THREAT_MIN_CP) return null

        val after = measureNullMoveThreat(engineClient, fenAfterReply, moveScoreFromMover) ?: return null

        val removed = before.dropCentipawns - after.dropCentipawns
        if (removed < CoachThresholds.PROPHYLAXIS_MIN_DROP_CP) return null
        if (after.opponentMoveUci == before.opponentMoveUci) return null

        return ProphylaxisFact(
            preventedMoveSan = before.opponentMoveSan,
            dropRemovedCentipawns = removed,
            favours = mover,
            evidence = Evidence.fromEngine(before.bestLine),
        )
    }

    private fun buildReasons(facts: List<Fact>): List<Reason> {
        val grouped: Map<ReasonTier, List<Fact>> = facts
            .groupBy { tierOf(it) }
            .mapNotNull { (tier, tierFacts) -> tier?.let { it to tierFacts } }
            .toMap()

        return ReasonTier.entries
            .mapNotNull { tier -> grouped[tier]?.let { Reason(tier, it) } }
            .take(CoachThresholds.MAX_REASONS)
    }

    /**
     * Тир причины по типу факта — порядок важности задан условием задачи, а не
     * порядком объявления в этом файле.
     */
    private fun tierOf(fact: Fact): ReasonTier? = when (fact) {
        is HangingPieceFact -> ReasonTier.MATERIAL_TACTICS
        is ForkFact -> ReasonTier.MATERIAL_TACTICS
        is PinFact -> ReasonTier.MATERIAL_TACTICS
        is SkewerFact -> ReasonTier.MATERIAL_TACTICS
        is DiscoveredAttackFact -> ReasonTier.MATERIAL_TACTICS
        is BackRankFact -> ReasonTier.MATERIAL_TACTICS
        is OverloadedDefenderFact -> ReasonTier.MATERIAL_TACTICS
        is TrappedPieceFact -> ReasonTier.MATERIAL_TACTICS
        is MateFact -> ReasonTier.MATERIAL_TACTICS
        is KingSafetyFact -> ReasonTier.KING_SAFETY
        is ProphylaxisFact -> ReasonTier.PROPHYLAXIS
        is PawnWeaknessFact -> ReasonTier.STRUCTURE
        is PassedPawnFact -> ReasonTier.STRUCTURE
        is OutpostFact -> ReasonTier.STRUCTURE
        is OpenFileRookFact -> ReasonTier.STRUCTURE
        is BadBishopFact -> ReasonTier.STRUCTURE
        is DevelopmentFact -> ReasonTier.ACTIVITY
        is MobilityEdgeFact -> ReasonTier.ACTIVITY
        is CenterControlFact -> ReasonTier.ACTIVITY
        // Угроза — самостоятельная секция, а не одна из причин "почему этот ход".
        is ThreatFact -> null
    }
}
