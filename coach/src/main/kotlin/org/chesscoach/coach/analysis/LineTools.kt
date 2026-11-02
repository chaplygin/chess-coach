package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.detectors.SeeCalculator
import org.chesscoach.coach.detectors.boardAfter
import org.chesscoach.coach.detectors.legalMovesFor
import org.chesscoach.core.MoveNotation

/** Материал вдоль варианта: то, что можно проверить на доске, в отличие от оценки. */
internal object LineMaterial {

    /**
     * Сколько материала [side] получает к концу варианта [pv]. Считаем по
     * последней позиции, где следующий ход не взятие: посреди размена вариант
     * показал бы потерю фигуры, которую тут же заберут обратно. Null — такой нет.
     */
    fun swing(fen: String, pv: List<String>, side: Side): Int? {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return null
        val start = material(board)
        var quiet: Int? = null
        for ((index, uci) in pv.withIndex()) {
            val captured = uci.length >= 4 && board.getPiece(squareOf(uci.substring(2, 4))) != Piece.NONE
            if (!runCatching { board.doMove(uci) }.getOrDefault(false)) break
            val next = pv.getOrNull(index + 1)
            val nextCaptures = next != null && next.length >= 4 && board.getPiece(squareOf(next.substring(2, 4))) != Piece.NONE
            // Вариант обрывается на взятии или шахе: ответ не виден, материал ещё не устоялся.
            val unsettled = next == null && (captured || board.isKingAttacked)
            if (!nextCaptures && !unsettled) quiet = material(board)
        }
        val end = quiet ?: return null
        return if (side == Side.WHITE) end - start else start - end
    }

    private fun squareOf(name: String): Square = Square.valueOf(name.uppercase())

    private fun material(board: Board): Int {
        var total = 0
        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            val type = piece.pieceType ?: continue
            if (type == PieceType.KING) continue
            val value = SeeCalculator.value(type)
            total += if (piece.pieceSide == Side.WHITE) value else -value
        }
        return total
    }
}

/**
 * Шахи, после которых фигуру не забирают с выгодой. Шах первый пункт проверки
 * каждого хода, а зевают его чаще мата: он ничего не обещает, но вынуждает ответ.
 */
internal object SafeChecks {
    private const val MAX_CHECKS = 2

    fun of(fen: String, side: Side): List<String> {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return emptyList()
        val checks = legalMovesFor(board, side).filter { move ->
            val after = boardAfter(board, side, move)
            after.isKingAttacked && !after.isMated && isSafe(after, move)
        }.take(MAX_CHECKS)
        if (checks.isEmpty()) return emptyList()

        val turned = board.clone()
        turned.sideToMove = side
        turned.enPassant = Square.NONE
        turned.enPassantTarget = Square.NONE
        return checks.map { MoveNotation.uciToSanOrRaw(turned.fen, listOf(it.toString())).first() }
    }

    /** Под шахом размен считать нельзя, поэтому смотрим сами взятия фигуры с шахом. */
    private fun isSafe(after: Board, move: Move): Boolean = after.legalMoves()
        .filter { it.to == move.to }
        .none { SeeCalculator.evaluateMove(after, it).let { gain -> gain != null && gain > 0 } }
}
