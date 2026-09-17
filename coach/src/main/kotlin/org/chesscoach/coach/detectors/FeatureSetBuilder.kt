package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.FeatureSource

/**
 * Собирает [FeatureSet] из набора детекторов.
 *
 * Два источника отказов, с которыми тут приходится жить: FEN может прийти
 * испорченным (из UI, из PGN стороннего сервиса — мы ему не доверяем), а
 * отдельный детектор может упасть на неожиданной форме позиции. Ни один из
 * этих отказов не должен уронить весь разбор партии — лучше вернуть пустой
 * FeatureSet или пропустить один факт, чем не показать тренеру ничего.
 */
class FeatureSetBuilder(private val detectors: List<Detector>) : FeatureSource {

    override fun features(fen: String): FeatureSet {
        val board = try {
            Board().apply { loadFromFen(fen) }
        } catch (e: Exception) {
            return FeatureSet.empty(fen)
        }

        return try {
            buildFeatureSet(fen, board)
        } catch (e: Exception) {
            FeatureSet.empty(fen)
        }
    }

    private fun buildFeatureSet(fen: String, board: Board): FeatureSet {
        val facts = ArrayList<Fact>()
        for (detector in detectors) {
            try {
                facts += detector.detect(board)
            } catch (e: Exception) {
                // Один сломанный детектор — не повод терять факты остальных.
            }
        }

        val phase = PhaseClassifier.classify(board)
        val mobility = Side.values().associateWith { legalMoveCount(board, it) }
        val centerControl = Side.values().associateWith { centerControlOf(board, it) }

        return FeatureSet(
            fen = fen,
            phase = phase,
            facts = facts,
            materialCentipawns = materialCentipawns(board),
            mobility = mobility,
            centerControl = centerControl,
        )
    }

    /** Материал в сантипешках от лица белых: король в счёт не идёт, он есть всегда у обеих сторон и сокращается. */
    private fun materialCentipawns(board: Board): Int {
        var total = 0
        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) continue
            val value = SeeCalculator.value(piece.pieceType)
            total += if (piece.pieceSide == Side.WHITE) value else -value
        }
        return total
    }

    private fun legalMoveCount(board: Board, side: Side): Int {
        val working = board.clone()
        if (working.sideToMove != side) {
            working.sideToMove = side
            // Смена очереди хода задним числом делает старое взятие на проходе
            // нелегальным миражом — сбрасываем его вместе с очередью.
            working.enPassant = Square.NONE
            working.enPassantTarget = Square.NONE
        }
        if (working.isKingAttacked) return 0
        return working.legalMoves().size
    }

    private fun centerControlOf(board: Board, side: Side): Int =
        CENTER_SQUARES.sumOf { AttackMap.attackersOf(board, it, side).size }

    private companion object {
        val CENTER_SQUARES = listOf(Square.D4, Square.D5, Square.E4, Square.E5)
    }
}
