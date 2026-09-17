package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.GamePhase

/**
 * Развитие в дебюте: какие лёгкие фигуры и ладьи ещё стоят дома, сделана ли
 * рокировка.
 *
 * Работает только пока PhaseClassifier считает партию дебютом — в
 * миттельшпиле "конь ещё на b1" уже не новость, а в эндшпиле это вообще не
 * то, о чём стоит говорить.
 */
class DevelopmentDetector : Detector {
    override val name: String = "development"

    override fun detect(board: Board): List<Fact> {
        if (PhaseClassifier.classify(board) != GamePhase.OPENING) return emptyList()

        val facts = ArrayList<Fact>()
        for (side in Side.values()) {
            val undeveloped = START_SQUARES[side]!!.filter { (square, type) ->
                board.getPiece(square) == Piece.make(side, type)
            }.map { it.first }

            if (undeveloped.isEmpty()) continue // всё уже выведено — говорить не о чем

            facts += DevelopmentFact(
                undeveloped = undeveloped,
                owner = side,
                castled = hasCastled(board, side),
                favours = side.flip(),
                evidence = Evidence.of(*undeveloped.toTypedArray()),
            )
        }
        return facts
    }

    /**
     * Король стоит на своём поле после рокировки. Это упрощение: chesslib не
     * хранит отдельного флага "рокировка была", а права на рокировку (getCastleRight)
     * теряются и по другим причинам (например, ладью съели). Поле короля —
     * единственный надёжный след рокировки, который можно увидеть прямо на доске.
     */
    private fun hasCastled(board: Board, side: Side): Boolean {
        val king = board.getKingSquare(side)
        val castledSquares = if (side == Side.WHITE) setOf(Square.G1, Square.C1) else setOf(Square.G8, Square.C8)
        return king in castledSquares
    }

    private companion object {
        val START_SQUARES: Map<Side, List<Pair<Square, PieceType>>> = mapOf(
            Side.WHITE to listOf(
                Square.B1 to PieceType.KNIGHT, Square.G1 to PieceType.KNIGHT,
                Square.C1 to PieceType.BISHOP, Square.F1 to PieceType.BISHOP,
                Square.A1 to PieceType.ROOK, Square.H1 to PieceType.ROOK,
            ),
            Side.BLACK to listOf(
                Square.B8 to PieceType.KNIGHT, Square.G8 to PieceType.KNIGHT,
                Square.C8 to PieceType.BISHOP, Square.F8 to PieceType.BISHOP,
                Square.A8 to PieceType.ROOK, Square.H8 to PieceType.ROOK,
            ),
        )
    }
}
