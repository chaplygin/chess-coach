package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Board

/** Законный ход из позиции: для доски, на которой ходы делаются нажатиями. */
data class LegalMove(val uci: String, val san: String, val fenAfter: String) {
    val from: String get() = uci.take(2)
    val to: String get() = uci.substring(2, 4)

    /** Буква фигуры превращения (q, r, b, n) или null. */
    val promotion: Char? get() = uci.getOrNull(4)
}

object BoardMoves {

    /** Все законные ходы с записью SAN и позицией после хода. Пусто — мат, пат или испорченный FEN. */
    fun legal(fen: String): List<LegalMove> {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return emptyList()
        return board.legalMoves().map { move ->
            val uci = move.toString().lowercase()
            val after = board.clone().apply { doMove(move) }
            LegalMove(uci, MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first(), after.fen)
        }
    }

    /** Номер хода и точки для записи "8. Nxd5" или "8... cxd5" по позиции до хода. */
    fun moveLabel(fenBefore: String, san: String): String {
        val number = MoveNotation.fullMoveNumber(fenBefore)
        return number.toString() + (if (MoveNotation.isWhiteToMove(fenBefore)) ". " else "... ") + san
    }
}
