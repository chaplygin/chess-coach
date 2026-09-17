package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.HangingPieceFact

/**
 * Фигуры, которые прямо сейчас можно забрать с выгодой по честному размену.
 *
 * "Выгодно" решает SeeCalculator, а не подсчёт атакующих: одна лишняя фигура
 * защитника ничего не значит, если она связана и не может ответить.
 */
class HangingDetector : Detector {
    override val name: String = "hanging"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) continue

            val attacker = piece.pieceSide.flip()
            val loss = SeeCalculator.evaluateSquare(board, square, attacker)
            if (loss > 0) {
                facts += HangingPieceFact(
                    square = square,
                    piece = piece,
                    lossCentipawns = loss,
                    favours = attacker,
                    evidence = Evidence.see(square, loss),
                )
            }
        }

        return facts
    }
}
