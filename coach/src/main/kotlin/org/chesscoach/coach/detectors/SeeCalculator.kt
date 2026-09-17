package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Честный статический размен (SEE) на одном поле.
 *
 * Реализовано через настоящие ходы chesslib, а не вручную по битбордам:
 * board.legalMoves() сам отказывает во взятии абсолютно связанной фигурой,
 * потому что оно раскрыло бы своего короля. Это и есть самое важное правило
 * во всём модуле — пересчитывать связки своим кодом означало бы завести
 * второй источник истины и рано или поздно разойтись с движком.
 */
object SeeCalculator {
    const val PAWN_VALUE = 100
    const val KNIGHT_VALUE = 320
    const val BISHOP_VALUE = 330
    const val ROOK_VALUE = 500
    const val QUEEN_VALUE = 900

    /** Условная цена короля — только чтобы сравнивать "кем бить первым". Короля по-настоящему никогда не берут. */
    const val KING_VALUE = 20_000

    fun value(type: PieceType): Int = when (type) {
        PieceType.PAWN -> PAWN_VALUE
        PieceType.KNIGHT -> KNIGHT_VALUE
        PieceType.BISHOP -> BISHOP_VALUE
        PieceType.ROOK -> ROOK_VALUE
        PieceType.QUEEN -> QUEEN_VALUE
        PieceType.KING -> KING_VALUE
        else -> 0
    }

    /**
     * То же самое, но по фигуре, а не по типу. У Piece.NONE тип в chesslib —
     * настоящий Java null, а не PieceType.NONE, поэтому дёргать .pieceType
     * напрямую на пустой клетке нельзя: словишь NPE вместо честного нуля.
     */
    private fun pieceValue(piece: Piece): Int {
        val type: PieceType? = piece.pieceType
        return type?.let(::value) ?: 0
    }

    /**
     * Итог полного размена на [target], если первой бьёт сторона [attacker],
     * каждый раз наименее ценной из доступных фигур. Положительное число —
     * выгодно атакующему; ноль — бить нечем или невыгодно.
     */
    fun evaluateSquare(board: Board, target: Square, attacker: Side): Int {
        val working = board.clone()
        if (working.sideToMove != attacker) {
            working.sideToMove = attacker
            // Передавая очередь хода, обязательно сбрасываем взятие на проходе:
            // оно описывает пешку, только что прыгнувшую через клетку, и при
            // смене стороны превращается в разрешение на нелегальное взятие.
            working.enPassant = Square.NONE
            working.enPassantTarget = Square.NONE
        }
        // Под шахом размен считать нельзя: legalMoves() вернёт только защиты от
        // шаха, и разговор про "эта фигура висит" станет неправдой.
        if (working.isKingAttacked) return 0
        return exchange(working, target)
    }

    /**
     * Итог конкретного хода [move] прямо сейчас и последующего размена на том же
     * поле. Null, если сам ход в этой позиции не сыграть по правилам (например,
     * фигура связана) — тогда это не угроза, а мираж, и оценивать нечего.
     */
    fun evaluateMove(board: Board, move: Move): Int? {
        val mover = board.getPiece(move.from)
        if (mover == Piece.NONE) return null

        val working = board.clone()
        if (working.sideToMove != mover.pieceSide) working.sideToMove = mover.pieceSide
        if (working.legalMoves().none { it.from == move.from && it.to == move.to }) return null

        val capturedValue = pieceValue(board.getPiece(move.to))
        working.doMove(move)
        return capturedValue - exchange(working, move.to)
    }

    /**
     * Рекурсивный размен на [target]: сторона, чей сейчас ход, либо бьёт
     * наименее ценной фигурой из тех, что реально могут (легально) дойти до
     * [target], либо не бьёт вовсе, если это невыгодно.
     *
     * Ключевая строка тут — board.legalMoves(): она не включает ход абсолютно
     * связанной фигуры, потому что такой ход оставляет короля под боем. Так
     * связанный "защитник" сам исключается из размена, вместо того чтобы его
     * нужно было бы находить и вычёркивать вручную.
     */
    private fun exchange(board: Board, target: Square): Int {
        val captures = board.legalMoves().filter { it.to == target }
        if (captures.isEmpty()) return 0

        val move = captures.minByOrNull { pieceValue(board.getPiece(it.from)) }!!
        val victimValue = pieceValue(board.getPiece(target))

        val next = board.clone()
        next.doMove(move)
        val rest = exchange(next, target)

        return maxOf(0, victimValue - rest)
    }
}
