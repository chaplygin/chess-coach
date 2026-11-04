package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.detectors.SeeCalculator
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat

/**
 * Приём нулевого хода даёт движок, а не выдумку: сколько теряет сторона,
 * которой сейчас якобы не давать ходить.
 *
 * Вынесено отдельной функцией, потому что тот же самый вопрос — "а что если
 * дать сопернику лишний ход прямо здесь" — задаётся дважды: один раз для
 * секции УГРОЗА (в исходной позиции) и второй раз в [ReasonDiffAnalyzer] для
 * проверки профилактики (в позиции на ход позже). Два разных вызова движка,
 * одна и та же логика — дублировать её было бы источником рассинхрона.
 */
internal data class NullMoveThreat(
    val opponentMoveUci: String,
    val opponentMoveSan: String,
    val dropCentipawns: Int,
    val flippedFen: String,
    val bestLine: EngineLine,
    /** Ход сам что-то выигрывает: мат или взятие с прибылью, а не просто лучший ход при пропуске. */
    val wins: Boolean,
    /** Ход — взятие без прибыли: падение оценки здесь значит лишь, что упущен свой ход. */
    val idleCapture: Boolean,
)

/**
 * Измеряет просадку приёмом нулевого хода в позиции [fen].
 *
 * [normalScore] — оценка позиции [fen] от лица стороны, чей сейчас ход,
 * взятая из обычного поиска (пересчитывать её самим нельзя). Null, если
 * приём неприменим (сторона под шахом) или движку нечего ответить.
 */
internal suspend fun measureNullMoveThreat(
    engineClient: EngineClient,
    fen: String,
    normalScore: Score,
): NullMoveThreat? {
    val flipped = NullMoveUtil.flipSideToMove(fen) ?: return null

    val lines = engineClient.analyse(flipped, CoachSearchLimits(movetimeMs = 400, multiPv = 1))
    val bestLine = lines.firstOrNull() ?: return null
    val opponentMoveUci = bestLine.pv.firstOrNull() ?: return null

    // Строка движка для flipped-позиции — это оценка с точки зрения того, кто
    // там ходит (соперника). Нашей стороне тот же самый исход стоит ровно
    // наоборот — отсюда negate, а не пересчёт с нуля.
    val ourScoreAfterThreat = ScoreFormat.negate(bestLine.score)
    val drop = ScoreFormat.centipawnLoss(normalScore, ourScoreAfterThreat)

    val opponentMoveSan = MoveNotation.uciToSanOrRaw(flipped, listOf(opponentMoveUci)).first()

    val mates = (bestLine.score as? Score.Mate)?.let { it.movesToMate > 0 } == true
    val capture = captureGain(flipped, opponentMoveUci)

    return NullMoveThreat(
        opponentMoveUci = opponentMoveUci,
        opponentMoveSan = opponentMoveSan,
        dropCentipawns = drop,
        flippedFen = flipped,
        bestLine = bestLine,
        wins = mates || (capture != null && capture >= SeeCalculator.WINNING_MIN_CP),
        idleCapture = !mates && capture != null && capture < SeeCalculator.WINNING_MIN_CP,
    )
}

/** Итог взятия по размену. Null — ход не взятие. */
private fun captureGain(fen: String, uci: String): Int? {
    val board = Board()
    if (!runCatching { board.loadFromFen(fen) }.isSuccess || uci.length < 4) return null
    val move = runCatching { Move(uci, board.sideToMove) }.getOrNull() ?: return null
    if (board.getPiece(move.to) == Piece.NONE) return null
    return SeeCalculator.evaluateMove(board, move)
}

/** Что реально сыпется: материал, король, мат. Отсталая пешка и чужая подвижность угрозу не объясняют. */
internal fun explainsDanger(fact: Fact): Boolean = when (fact) {
    is HangingPieceFact, is TrappedPieceFact, is ForkFact, is PinFact, is SkewerFact,
    is DiscoveredAttackFact, is BackRankFact, is OverloadedDefenderFact, is MateFact,
    -> true
    else -> false
}

private const val THREAT_LINE_PLIES = 3

/** Применяет ход в UCI-записи ("e2e4", "e7e8q") к позиции. Null — ход не сыграть по правилам. */
internal fun applyUciMove(fen: String, uci: String): String? {
    val board = Board()
    if (!runCatching { board.loadFromFen(fen) }.isSuccess) return null
    if (!runCatching { board.doMove(uci) }.getOrDefault(false)) return null
    return board.fen
}

/**
 * Секция «УГРОЗА»: что соперник сделал бы, будь сейчас его ход.
 *
 * Строится строго через [NullMoveUtil.flipSideToMove]: если приём неприменим
 * (сторона под шахом), секции не будет вовсе — придумывать угрозу в такой
 * позиции запрещено условием задачи.
 */
class ThreatAnalyzer(
    private val engineClient: EngineClient,
    private val featureSource: FeatureSource,
) {

    suspend fun analyse(fen: String, normalScore: Score): ThreatFact? =
        analyse(fen, measureNullMoveThreat(engineClient, fen, normalScore))

    /** То же по уже посчитанному нулевому ходу: его лучший ход нужен и для проверки шахов соперника. */
    internal fun analyse(fen: String, measured: NullMoveThreat?): ThreatFact? {
        val ourSide = NullMoveUtil.sideToMoveOf(fen)
        val threat = measured ?: return null

        if (threat.dropCentipawns < CoachThresholds.THREAT_MIN_CP) return null

        // Последствия хода угрозы берём из тех же детекторов, что смотрят любую
        // другую позицию — фраза про вилку должна происходить из ForkDetector,
        // а не из этого класса.
        val afterThreatFen = applyUciMove(threat.flippedFen, threat.opponentMoveUci) ?: threat.flippedFen
        val opponentSide = ourSide.flip()

        // Следствие угрозы — то, что появилось ИМЕННО от этого хода, а не всё
        // хорошее, что у соперника и так было. Иначе получается неправда:
        // "соперник сыграет Сd7, и конь окажется связан" — хотя связывает его
        // слон, стоящий на доске с самого начала, и ход тут ни при чём.
        val before = featureSource.features(fen).facts.map(::signature).toSet()
        val consequences = featureSource.features(afterThreatFen).facts
            .filter { it.favours == opponentSide && signature(it) !in before }
            .filterNot { answeredByCapture(it, afterThreatFen, threat.opponentMoveUci, ourSide) }

        // Падение оценки при пропуске хода само по себе не угроза: так же падает
        // оценка, когда у меня есть что забрать. Угрозой называем только ход,
        // который сам что-то выигрывает: конкретно и проверяемо на доске.
        if (!threat.wins && consequences.none(::explainsDanger)) return null

        return ThreatFact(
            opponentMoveUci = threat.opponentMoveUci,
            opponentMoveSan = threat.opponentMoveSan,
            dropCentipawns = threat.dropCentipawns,
            consequences = consequences,
            favours = opponentSide,
            evidence = Evidence.fromEngine(threat.bestLine),
            lineSan = MoveNotation.uciToSanOrRaw(threat.flippedFen, threat.bestLine.pv.take(THREAT_LINE_PLIES)),
        )
    }
}

/**
 * Удар той самой фигуры, которая пошла угрозой, если её можно выгодно побить.
 * Такой удар существует только на бумаге: пешка, которую сразу забирают, ни на
 * коня, ни на слона уже не нападает.
 */
private fun answeredByCapture(fact: Fact, afterFen: String, moveUci: String, ourSide: Side): Boolean {
    val moved = moveUci.drop(2).take(2).uppercase()
    val fromMoved = when (fact) {
        is HangingPieceFact -> fact.firstAttacker?.value() == moved
        is ForkFact -> fact.from.value() == moved
        else -> false
    }
    if (!fromMoved) return false
    val board = Board()
    if (!runCatching { board.loadFromFen(afterFen) }.isSuccess) return false
    return SeeCalculator.evaluateSquare(board, Square.valueOf(moved), ourSide) > 0
}

/**
 * Опознание факта без учёта доказательства.
 *
 * Сравнивать факты целиком нельзя: у одного и того же признака в двух
 * позициях отличаются приложенные строки движка и записанные фигуры, и любой
 * признак выглядел бы новым.
 */
internal fun signature(fact: Fact): String =
    fact.javaClass.simpleName + ":" + fact.evidence.squares.joinToString(",")
