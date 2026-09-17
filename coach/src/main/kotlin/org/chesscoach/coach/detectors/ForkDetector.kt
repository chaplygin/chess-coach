package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.ForkFact

/**
 * Вилка: одна фигура нападает минимум на две стоящие цели, каждая из которых
 * реально того стоит.
 *
 * "Реально того стоит" — это либо честный плюс по SEE, либо цель дороже
 * нападающего (тогда она вынуждена уходить, даже если формально защищена).
 * Фигура, которую тут же бьют бесплатно, вилку не объявляет — нападать
 * какое-то время ей не придётся.
 */
class ForkDetector : Detector {
    override val name: String = "fork"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) continue

            val side = piece.pieceSide
            val opponent = side.flip()

            // Фигуру, которую сейчас же бесплатно возьмут, вилкой не считаем.
            if (SeeCalculator.evaluateSquare(board, square, opponent) > 0) continue

            val attackerValue = SeeCalculator.value(piece.pieceType)
            val enemyOccupancy = board.getBitboard(opponent)
            val attacked = Bitboard.bbToSquareList(AttackMap.attacksFrom(board, square) and enemyOccupancy)

            val targets = attacked.filter { target ->
                val targetPiece = board.getPiece(target)
                val targetValue = SeeCalculator.value(targetPiece.pieceType)
                val worthMore = targetValue > attackerValue
                val worthTaking = worthMore || SeeCalculator.evaluateMove(board, Move(square, target)).let { it != null && it > 0 }
                worthTaking
            }

            if (targets.size >= 2) {
                facts += ForkFact(
                    from = square,
                    piece = piece,
                    targets = targets,
                    favours = side,
                    evidence = Evidence.onBoard(board, square, *targets.toTypedArray()),
                )
            }
        }

        return facts
    }
}
