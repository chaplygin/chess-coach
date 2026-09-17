package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.OverloadedDefenderFact

/**
 * Перегруженный защитник: одна фигура — единственная опора сразу двух вещей,
 * каждая из которых реально под боем.
 *
 * "Единственная опора" — значит, без неё атакованную фигуру дешёвая фигура
 * соперника заберёт даром. Если у обязанности есть второй защитник, увести
 * первую фигуру не страшно, и перегрузки нет.
 */
class OverloadDetector : Detector {
    override val name: String = "overload"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val defender = board.getPiece(square)
            if (defender == Piece.NONE || defender.pieceType == PieceType.KING) continue

            val side = defender.pieceSide
            val opponent = side.flip()
            val duties = AttackMap.defends(board, square).filter { duty ->
                isCriticalDuty(board, duty, side, opponent, sole = square)
            }

            if (duties.size >= 2) {
                facts += OverloadedDefenderFact(
                    defender = square,
                    duties = duties,
                    favours = opponent,
                    evidence = Evidence.of(square, *duties.toTypedArray()),
                )
            }
        }

        return facts
    }

    /**
     * [duty] реально нуждается в защитнике [sole]: она под ударом, [sole] —
     * её единственный защитник, а без него соперник честно отыгрывает
     * материал на этом поле.
     *
     * "Без него" проверяется буквально: фигуру-защитника убирают с клона
     * доски и пересчитывают SEE. Не эвристика по цене нападающего — она легко
     * ошибается, когда на поле смотрит рентген или защитников выручает связка.
     */
    private fun isCriticalDuty(board: Board, duty: Square, side: Side, opponent: Side, sole: Square): Boolean {
        val dutyPiece = board.getPiece(duty)
        if (dutyPiece == Piece.NONE) return false
        if (!AttackMap.isAttacked(board, duty, opponent)) return false

        val defenders = AttackMap.attackersOf(board, duty, side)
        if (defenders.size != 1 || defenders.first() != sole) return false

        val withoutDefender = board.clone()
        withoutDefender.unsetPiece(board.getPiece(sole), sole)
        return SeeCalculator.evaluateSquare(withoutDefender, duty, opponent) > 0
    }
}
