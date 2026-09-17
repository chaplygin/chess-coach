package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.TrappedPieceFact

/**
 * Пойманная фигура: у неё есть куда пойти, но каждый ход теряет материал.
 *
 * Не "ходы некрасивые" — а буквально любой ход после честного размена на
 * итоговом поле оставляет сторону в минусе. Фигуру, которая уже висит прямо
 * сейчас, сюда не относим — это забота HangingDetector, а не этого.
 */
class TrappedPieceDetector : Detector {
    override val name: String = "trapped_piece"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE || piece.pieceType !in TRAPPABLE_TYPES) continue

            val side = piece.pieceSide
            val opponent = side.flip()

            // Уже висит прямо сейчас — это отдельный факт, не ловля.
            if (SeeCalculator.evaluateSquare(board, square, opponent) > 0) continue

            val working = board.clone()
            if (working.sideToMove != side) working.sideToMove = side
            val moves = working.legalMoves().filter { it.from == square }
            if (moves.isEmpty()) continue // фигуре некуда пойти — это не "ловля", а просто нет ходов

            val netOutcomes = moves.map { move ->
                val capturedPiece = working.getPiece(move.to)
                val capturedValue = if (capturedPiece == Piece.NONE) 0 else SeeCalculator.value(capturedPiece.pieceType)
                val after = working.clone()
                after.doMove(move)
                val loss = SeeCalculator.evaluateSquare(after, move.to, opponent)
                capturedValue - loss
            }

            val bestOutcome = netOutcomes.maxOrNull()!!
            // Мелкую потерю не объявляем ловлей: порог общий для всего тренера.
            if (bestOutcome < 0 && -bestOutcome >= CoachThresholds.TRAP_MIN_LOSS_CP) {
                facts += TrappedPieceFact(
                    square = square,
                    piece = piece,
                    lossCentipawns = -bestOutcome,
                    favours = opponent,
                    evidence = Evidence.see(square, -bestOutcome),
                )
            }
        }

        return facts
    }

    private companion object {
        val TRAPPABLE_TYPES = setOf(PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)
    }
}
