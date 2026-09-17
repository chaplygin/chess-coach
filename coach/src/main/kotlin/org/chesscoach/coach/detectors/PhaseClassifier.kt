package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.GamePhase

/**
 * Стадия партии по доске, без обращения к истории ходов.
 *
 * Эндшпиль решает материал — либо ферзей не осталось вовсе, либо суммарного
 * материала так мало, что королю уже пора выходить в центр. Оставшееся
 * разделение на дебют и миттельшпиль решает развитие: пока большинство лёгких
 * фигур и ладей стоят на начальных полях и не сыграно много ходов, тренеру
 * ещё рано говорить про миттельшпильные приёмы вроде плохого слона.
 */
object PhaseClassifier {

    /** Ниже такого суммарного не-пешечного материала (обе стороны, без королей) — эндшпиль. */
    private const val ENDGAME_NON_PAWN_MATERIAL_CP = 2600

    /** После этого номера хода дебютные оговорки больше не действуют. */
    private const val OPENING_MAX_MOVE_NUMBER = 10

    /** Из 12 лёгких фигур и ладей (по 6 на сторону) столько ещё должны стоять дома. */
    private const val OPENING_UNDEVELOPED_MIN = 5

    private val OPENING_SQUARES: List<Pair<Side, Square>> = listOf(
        Side.WHITE to Square.B1, Side.WHITE to Square.G1,
        Side.WHITE to Square.C1, Side.WHITE to Square.F1,
        Side.WHITE to Square.A1, Side.WHITE to Square.H1,
        Side.BLACK to Square.B8, Side.BLACK to Square.G8,
        Side.BLACK to Square.C8, Side.BLACK to Square.F8,
        Side.BLACK to Square.A8, Side.BLACK to Square.H8,
    )

    fun classify(board: Board): GamePhase {
        if (isEndgame(board)) return GamePhase.ENDGAME
        return if (isOpening(board)) GamePhase.OPENING else GamePhase.MIDDLEGAME
    }

    private fun isEndgame(board: Board): Boolean {
        val noQueens = board.getPieceLocation(Piece.WHITE_QUEEN).isEmpty() &&
            board.getPieceLocation(Piece.BLACK_QUEEN).isEmpty()
        if (noQueens) return true

        return nonPawnMaterial(board) <= ENDGAME_NON_PAWN_MATERIAL_CP
    }

    private fun nonPawnMaterial(board: Board): Int {
        var total = 0
        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE) continue
            val type = piece.pieceType
            if (type == PieceType.PAWN || type == PieceType.KING) continue
            total += SeeCalculator.value(type)
        }
        return total
    }

    private fun isOpening(board: Board): Boolean {
        val moveNumber = board.moveCounter ?: 1
        if (moveNumber > OPENING_MAX_MOVE_NUMBER) return false

        val undeveloped = OPENING_SQUARES.count { (side, square) ->
            val expectedType = startingPieceType(square)
            board.getPiece(square) == Piece.make(side, expectedType)
        }
        return undeveloped >= OPENING_UNDEVELOPED_MIN
    }

    private fun startingPieceType(square: Square): PieceType = when (square) {
        Square.B1, Square.G1, Square.B8, Square.G8 -> PieceType.KNIGHT
        Square.C1, Square.F1, Square.C8, Square.F8 -> PieceType.BISHOP
        else -> PieceType.ROOK
    }
}
