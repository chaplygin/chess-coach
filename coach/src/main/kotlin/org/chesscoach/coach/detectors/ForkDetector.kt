package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
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
            forkFrom(board, square)?.let { facts += it }
        }

        return facts
    }

    /** Вилка именно этой фигуры. Отдельно, чтобы проверить поле, куда фигура только могла бы пойти. */
    fun forkFrom(board: Board, square: Square): ForkFact? {
        val piece = board.getPiece(square)
        if (piece == Piece.NONE || piece.pieceType == PieceType.KING) return null

        val side = piece.pieceSide
        val opponent = side.flip()

        // Фигуру, которую сейчас же бесплатно возьмут, вилкой не считаем.
        if (SeeCalculator.evaluateSquare(board, square, opponent) > 0) return null
        if (takenFromCheck(board, square, opponent)) return null

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
        if (targets.size < 2) return null

        return ForkFact(
            from = square,
            piece = piece,
            targets = targets,
            favours = side,
            evidence = Evidence.onBoard(board, square, *targets.toTypedArray()),
        )
    }

    /**
     * Фигура объявила шах, и её выгодно бьют. Размен под шахом SEE не считает,
     * поэтому такую фигуру приходится проверять отдельно, иначе шах прятал бы её от взятия.
     */
    private fun takenFromCheck(board: Board, square: Square, opponent: Side): Boolean {
        val working = board.clone()
        if (working.sideToMove != opponent) {
            working.sideToMove = opponent
            working.enPassant = Square.NONE
            working.enPassantTarget = Square.NONE
        }
        if (!working.isKingAttacked) return false
        return working.legalMoves().any { move ->
            move.to == square && SeeCalculator.evaluateMove(working, move).let { it != null && it > 0 }
        }
    }
}
