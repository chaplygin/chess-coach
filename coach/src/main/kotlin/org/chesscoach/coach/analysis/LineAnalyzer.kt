package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.detectors.AttackMap
import org.chesscoach.coach.detectors.SeeCalculator
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MatePattern
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.RemoveDefenderFact
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import kotlin.math.abs

/**
 * Почему оценка именно такая: те же детекторы, но вдоль варианта движка.
 *
 * Материал сравнивается только в спокойной позиции: посреди размена вариант
 * показал бы выигрыш фигуры, которую следующим ходом заберут обратно.
 */
internal class LineAnalyzer(private val featureSource: FeatureSource) {

    suspend fun analyse(fen: String, line: EngineLine): Section.EvalReason? {
        val sideToMove = MoveNotation.sideToMove(fen)
        val score = ScoreFormat.toWhitePerspective(line.score, sideToMove)
        val start = Board()
        if (!runCatching { start.loadFromFen(fen) }.isSuccess) return null
        val materialNow = material(start)

        if (score is Score.Mate) return mateReason(fen, line, score, materialNow)

        val centipawns = (score as Score.Cp).centipawns
        val leader = when {
            centipawns >= LEADER_MIN_CP -> Side.WHITE
            centipawns <= -LEADER_MIN_CP -> Side.BLACK
            else -> null
        }
        val startFeatures = featureSource.features(fen)
        val known = startFeatures.facts.map(::signature).toSet()

        var tactic: Fact? = null
        var tacticPlies = 0
        val quiet = ArrayList<Pair<Int, Int>>()
        val board = start.clone()
        for ((index, uci) in line.pv.take(MAX_PLIES).withIndex()) {
            val mover = board.sideToMove
            if (!runCatching { board.doMove(uci) }.getOrDefault(false)) break
            val features = featureSource.features(board.fen)
            val plies = index + 1

            if (tactic == null && mover == leader && plies <= MAX_TACTIC_PLIES) {
                tactic = features.favouring(mover).firstOrNull { isTactic(it) && signature(it) !in known }
                if (tactic != null) tacticPlies = plies
            }
            val inExchange = features.favouring(board.sideToMove).any { it is HangingPieceFact }
            if (!inExchange && !board.isKingAttacked) quiet += plies to material(board)
        }

        // Итог берём из последней спокойной позиции, а показываем самый короткий
        // вариант с тем же материалом: дальше ходы к делу уже не относятся.
        val quietMaterial = quiet.lastOrNull()?.second ?: materialNow
        val quietPlies = quiet.firstOrNull { it.second == quietMaterial }?.first ?: 0

        val swing = when (leader) {
            Side.WHITE -> quietMaterial - materialNow
            Side.BLACK -> materialNow - quietMaterial
            else -> 0
        }

        return Section.EvalReason(
            materialNow = materialNow,
            leader = leader,
            materialSwing = swing.takeIf { quietPlies > 0 },
            swingLineSan = MoveNotation.uciToSanOrRaw(fen, line.pv.take(quietPlies)),
            tactic = tactic,
            tacticLineSan = MoveNotation.uciToSanOrRaw(fen, line.pv.take(tacticPlies)),
            positional = leader?.let { side ->
                startFeatures.favouring(side).filter { isPositional(it) }
            }.orEmpty(),
            lineSan = MoveNotation.uciToSanOrRaw(fen, line.pv.take(SHOWN_PLIES)),
            materialAfterLine = quietMaterial.takeIf { quietPlies > 0 },
        )
    }

    private fun mateReason(fen: String, line: EngineLine, score: Score.Mate, materialNow: Int): Section.EvalReason {
        val board = Board()
        board.loadFromFen(fen)
        val played = line.pv.takeWhile { runCatching { board.doMove(it) }.getOrDefault(false) }
        val finished = played.size == line.pv.size && board.isMated
        return Section.EvalReason(
            materialNow = materialNow,
            leader = if (score.movesToMate >= 0) Side.WHITE else Side.BLACK,
            mateIn = abs(score.movesToMate),
            matePattern = if (finished) patternOf(board) else null,
            lineSan = MoveNotation.uciToSanOrRaw(fen, played),
        )
    }

    /** Название рисунка только там, где оно проверяется по доске. Иначе рисунок не называем. */
    private fun patternOf(board: Board): MatePattern? {
        val mated = board.sideToMove
        val king = board.getKingSquare(mated)
        val checkers = AttackMap.attackersOf(board, king, mated.flip())
        val checker = checkers.singleOrNull()?.let(board::getPiece) ?: return null
        val neighbours = Bitboard.bbToSquareList(Bitboard.getKingAttacks(king, -1L))
        val smothered = neighbours.all { board.getPiece(it).let { p -> p != Piece.NONE && p.pieceSide == mated } }
        val edgeRank = if (mated == Side.WHITE) "1" else "8"
        return when {
            checker.pieceType == PieceType.KNIGHT && smothered -> MatePattern.SMOTHERED
            (checker.pieceType == PieceType.ROOK || checker.pieceType == PieceType.QUEEN) &&
                king.value().endsWith(edgeRank) && checkers.single().value().endsWith(edgeRank) -> MatePattern.BACK_RANK
            else -> null
        }
    }

    /** Рентген на пешку за конём — не тот удар, которым объясняют оценку. */
    private fun isTactic(fact: Fact): Boolean = fact is ForkFact ||
        (fact is SkewerFact && fact.evidence.pieceAt(fact.behind)?.pieceType != PieceType.PAWN) ||
        (fact is PinFact && fact.absolute) || fact is OverloadedDefenderFact ||
        fact is TrappedPieceFact || fact is RemoveDefenderFact

    private fun isPositional(fact: Fact): Boolean = fact is KingSafetyFact || fact is OutpostFact ||
        fact is PassedPawnFact || fact is MobilityEdgeFact

    private fun material(board: Board): Int {
        var total = 0
        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            val type = piece.pieceType ?: continue
            if (type == PieceType.KING) continue
            val value = SeeCalculator.value(type)
            total += if (piece.pieceSide == Side.WHITE) value else -value
        }
        return total
    }

    private companion object {
        const val LEADER_MIN_CP = 50
        const val MAX_PLIES = 8
        const val MAX_TACTIC_PLIES = 5
        const val SHOWN_PLIES = 5
    }
}
