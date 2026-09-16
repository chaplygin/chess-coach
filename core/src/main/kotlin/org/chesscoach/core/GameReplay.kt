package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveList

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

/**
 * Одна позиция партии: положение до хода, сам ход и положение после него.
 *
 * Хранится и запись хода для человека (SAN), и запись для движка (UCI):
 * первая нужна на экране, вторая — для подсветки полей на доске.
 */
data class PlyPosition(
    /** Номер полухода с нуля. */
    val index: Int,
    val fenBefore: String,
    val fenAfter: String,
    val san: String,
    val uci: String,
    /** Номер хода так, как его пишут в записи партии. */
    val moveNumber: Int,
    val whiteMoved: Boolean,
) {
    val fromSquare: String get() = uci.take(2)
    val toSquare: String get() = uci.drop(2).take(2)
}

/**
 * Разворачивает партию в последовательность позиций.
 *
 * Lichess отдаёт только список ходов, а для разбора нужна позиция перед каждым
 * из них: движок считает от положения на доске, а не от номера хода.
 */
object GameReplay {

    /**
     * Возвращает позиции по ходам партии.
     *
     * Если запись обрывается на недопустимом ходе, возвращается всё, что удалось
     * разобрать до него, а не пустой список: лучше показать первые тридцать
     * ходов, чем ничего.
     */
    fun replay(sanMoves: List<String>, initialFen: String? = null): List<PlyPosition> {
        if (sanMoves.isEmpty()) return emptyList()

        val startFen = initialFen ?: START_FEN
        val board = Board().apply { loadFromFen(startFen) }

        val moves = runCatching {
            MoveList(startFen).apply { loadFromSan(sanMoves.joinToString(" ")) }.toList()
        }.getOrElse { return emptyList() }

        val positions = ArrayList<PlyPosition>(moves.size)

        moves.forEachIndexed { index, move ->
            val fenBefore = board.fen
            val whiteMoved = MoveNotation.isWhiteToMove(fenBefore)
            val moveNumber = MoveNotation.fullMoveNumber(fenBefore)

            if (!runCatching { board.doMove(move) }.getOrDefault(false)) return positions

            positions += PlyPosition(
                index = index,
                fenBefore = fenBefore,
                fenAfter = board.fen,
                san = sanMoves.getOrElse(index) { move.toString() },
                uci = move.toString(),
                moveNumber = moveNumber,
                whiteMoved = whiteMoved,
            )
        }

        return positions
    }
}
