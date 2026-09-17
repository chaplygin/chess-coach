package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ThreatFact
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

    return NullMoveThreat(
        opponentMoveUci = opponentMoveUci,
        opponentMoveSan = opponentMoveSan,
        dropCentipawns = drop,
        flippedFen = flipped,
        bestLine = bestLine,
    )
}

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

    suspend fun analyse(fen: String, normalScore: Score): ThreatFact? {
        val ourSide = NullMoveUtil.sideToMoveOf(fen)
        val threat = measureNullMoveThreat(engineClient, fen, normalScore) ?: return null

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

        return ThreatFact(
            opponentMoveUci = threat.opponentMoveUci,
            opponentMoveSan = threat.opponentMoveSan,
            dropCentipawns = threat.dropCentipawns,
            consequences = consequences,
            favours = opponentSide,
            evidence = Evidence.fromEngine(threat.bestLine),
        )
    }
}

/**
 * Опознание факта без учёта доказательства.
 *
 * Сравнивать факты целиком нельзя: у одного и того же признака в двух
 * позициях отличаются приложенные строки движка и записанные фигуры, и любой
 * признак выглядел бы новым.
 */
private fun signature(fact: Fact): String =
    fact.javaClass.simpleName + ":" + fact.evidence.squares.joinToString(",")
