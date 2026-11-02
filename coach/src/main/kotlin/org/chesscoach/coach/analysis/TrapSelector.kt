package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.detectors.AttackMap
import org.chesscoach.coach.detectors.PhaseClassifier
import org.chesscoach.coach.detectors.SeeCalculator
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.MaterialVerdict
import org.chesscoach.coach.model.Section
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat

/**
 * Секция «ЛОВУШКА»: ход, который клубный игрок сыграет с высокой вероятностью,
 * потому что он выглядит хорошо при беглом взгляде, и который реально плох
 * при точном счёте.
 *
 * Кандидаты берутся только из уже посчитанного MultiPV=3: у нас есть честная
 * оценка только для этих ходов, оценивать все легальные ходы отдельным
 * поиском никто не заказывал. Среди альтернатив (не лучший ход) выбирается
 * та, что набрала больше всего очков "естественности", и только если её
 * реальная цена — не меньше [CoachThresholds.TRAP_MIN_LOSS_CP].
 */
class TrapSelector(
    private val engineClient: EngineClient,
) {

    suspend fun select(
        fen: String,
        mover: Side,
        mainLines: List<EngineLine>,
        quickLines: List<EngineLine>,
        playedUci: String? = null,
    ): Section.Trap? {
        val sorted = mainLines.sortedBy { it.multiPvIndex }
        val bestLine = sorted.firstOrNull() ?: return null
        // Сыгранный ход разбирается своим разделом: второй раз про него незачем.
        val alternatives = sorted.drop(1).filter { it.pv.firstOrNull() != playedUci }
        if (alternatives.isEmpty()) return null

        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return null

        val phase = PhaseClassifier.classify(board)
        val quickTop = quickLines.mapNotNull { it.pv.firstOrNull() }.toSet()

        val chosen = alternatives
            .mapNotNull { line -> candidateOf(board, mover, bestLine.score, line, phase, quickTop) }
            .maxWithOrNull(compareBy({ it.naturalness }, { it.loss }))
            ?: return null

        val fenAfterTrap = applyUciMove(fen, chosen.uci) ?: return null
        val refutationLines = engineClient.analyse(fenAfterTrap, CoachSearchLimits(movetimeMs = 300, multiPv = 1))
        val refutationLine = refutationLines.firstOrNull() ?: return null
        val refutationUci = refutationLine.pv.take(MAX_REFUTATION_PLIES)
        // Меньше двух полуходов не показываем: это уже не опровержение, а намёк.
        if (refutationUci.size < MIN_REFUTATION_PLIES) return null

        val refutationSan = MoveNotation.uciToSanOrRaw(fenAfterTrap, refutationUci)
        val fenAfterRefutation = refutationUci.fold(fenAfterTrap) { acc, uci -> applyUciMove(acc, uci) ?: return null }

        val verdict = materialVerdict(fen, fenAfterRefutation, mover, refutationLine.score)
        val trapMoveSan = MoveNotation.uciToSanOrRaw(fen, listOf(chosen.uci)).first()

        return Section.Trap(
            moveSan = trapMoveSan,
            lossCentipawns = chosen.loss,
            refutationSan = refutationSan,
            verdict = verdict,
            scoreFromWhite = ScoreFormat.toWhitePerspective(refutationLine.score, mover.flip()),
            lineUci = listOf(chosen.uci) + refutationUci,
        )
    }

    private data class Candidate(val uci: String, val loss: Int, val naturalness: Int)

    private fun candidateOf(
        board: Board,
        mover: Side,
        bestScore: Score,
        line: EngineLine,
        phase: GamePhase,
        quickTop: Set<String>,
    ): Candidate? {
        val uci = line.pv.firstOrNull() ?: return null
        val loss = ScoreFormat.centipawnLoss(bestScore, line.score)
        if (loss < CoachThresholds.TRAP_MIN_LOSS_CP) return null

        val move = runCatching { Move(uci, mover) }.getOrNull() ?: return null
        val naturalness = naturalness(board, move, mover, phase, quickTop, uci)
        return Candidate(uci, loss, naturalness)
    }

    /**
     * "Естественность" хода для человека, а не для движка: взятие, шах,
     * развитие в дебюте и нападение на равную или старшую фигуру добавляют
     * очки, попадание в топ короткого поиска — тоже. Тихое отступление —
     * единственный отрицательный пункт, и он тут главный педагогический
     * смысл: то, что выглядит хорошо при беглом взгляде, но опровергается
     * глубоким поиском, — ровно то, на чём клубный игрок и попадается.
     */
    private fun naturalness(
        board: Board,
        move: Move,
        mover: Side,
        phase: GamePhase,
        quickTop: Set<String>,
        uci: String,
    ): Int {
        var score = 0

        val moverPiece = board.getPiece(move.from)
        val moverType = moverPiece.pieceType ?: return score
        val targetPiece = board.getPiece(move.to)
        val isCapture = targetPiece.pieceType != null
        if (isCapture) score += 3

        val afterMove = board.clone()
        val gaveCheck = runCatching { afterMove.doMove(move) }.getOrDefault(false) && afterMove.isKingAttacked
        if (gaveCheck) score += 3

        if (phase == GamePhase.OPENING && isDevelopingMove(move, moverType)) score += 2

        if (attacksEqualOrHigherValue(board, move, moverType, mover)) score += 2

        if (uci in quickTop) score += 1

        if (!isCapture && !gaveCheck && isRetreat(move, mover)) score -= 5

        return score
    }

    private fun isDevelopingMove(move: Move, moverType: PieceType): Boolean {
        if (moverType != PieceType.KNIGHT && moverType != PieceType.BISHOP) return false
        val homeRankIndex = move.from.rank.ordinal
        return homeRankIndex == 0 || homeRankIndex == 7
    }

    private fun attacksEqualOrHigherValue(board: Board, move: Move, moverType: PieceType, mover: Side): Boolean {
        val after = board.clone()
        if (!runCatching { after.doMove(move) }.getOrDefault(false)) return false

        val moverValue = SeeCalculator.value(moverType)
        val enemyOccupancy = after.getBitboard(mover.flip())
        val attacked = Bitboard.bbToSquareList(AttackMap.attacksFrom(after, move.to) and enemyOccupancy)

        return attacked.any { square ->
            val value = after.getPiece(square).pieceType?.let(SeeCalculator::value) ?: 0
            value >= moverValue
        }
    }

    private fun isRetreat(move: Move, mover: Side): Boolean {
        val delta = move.to.rank.ordinal - move.from.rank.ordinal
        return if (mover == Side.WHITE) delta < 0 else delta > 0
    }

    /** Материал считается по доске, а не пишется от руки: только это честно. */
    private fun materialVerdict(fenBefore: String, fenAfter: String, mover: Side, finalScore: Score): MaterialVerdict {
        // finalScore — оценка после хода в ловушку с точки зрения того, кто ищет
        // опровержение, то есть соперника. Мат в его пользу — мат для попавшегося.
        if (finalScore is Score.Mate && finalScore.movesToMate > 0) return MaterialVerdict.GETS_MATED

        val before = materialCentipawns(fenBefore)
        val after = materialCentipawns(fenAfter)
        val swingForWhite = after - before
        val loss = if (mover == Side.WHITE) -swingForWhite else swingForWhite

        return when {
            loss >= SeeCalculator.QUEEN_VALUE - 50 -> MaterialVerdict.LOSES_QUEEN
            loss >= SeeCalculator.ROOK_VALUE - 50 -> MaterialVerdict.LOSES_ROOK
            loss >= SeeCalculator.BISHOP_VALUE - 30 -> MaterialVerdict.LOSES_PIECE
            loss >= 150 -> MaterialVerdict.LOSES_EXCHANGE
            loss >= SeeCalculator.PAWN_VALUE - 20 -> MaterialVerdict.LOSES_PAWN
            else -> MaterialVerdict.POSITIONAL
        }
    }

    private fun materialCentipawns(fen: String): Int {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return 0

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
        const val MAX_REFUTATION_PLIES = 4
        const val MIN_REFUTATION_PLIES = 2
    }
}
