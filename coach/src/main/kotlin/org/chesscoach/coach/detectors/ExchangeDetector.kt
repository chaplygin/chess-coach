package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.ExchangeFact
import org.chesscoach.coach.model.Fact
import kotlin.math.abs

/**
 * Почти равные размены: взять можно, но материал почти не меняется.
 *
 * Это справка, а не угроза: конь за слона по таблице цен даёт 0.1 пешки, и
 * называть такую фигуру "под боем" значило бы советовать размен, которого
 * позиция не требует.
 */
class ExchangeDetector : Detector {
    override val name: String = "exchange"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) continue

            val attacker = piece.pieceSide.flip()
            val first = SeeCalculator.firstCapture(board, square, attacker) ?: continue
            val delta = SeeCalculator.firstCaptureDelta(board, square, attacker) ?: continue
            if (abs(delta) >= SeeCalculator.WINNING_MIN_CP) continue

            val line = SeeCalculator.fullExchangeLine(board, square, attacker)
            facts += ExchangeFact(
                square = square,
                attacker = first.from,
                deltaCentipawns = delta,
                favours = attacker,
                evidence = Evidence.onBoard(board, square, first.from),
                lineSan = line.san,
                forcedPlies = line.forcedPlies,
            )
        }

        return facts
    }
}
