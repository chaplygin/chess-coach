package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.File
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Rank
import com.github.bhlangonijr.chesslib.Square

/**
 * Геометрия линий доски.
 *
 * Связка, рентген и вскрытая атака — по сути один и тот же вопрос: что стоит
 * на луче между атакующей фигурой и её целью. Вынесено отдельно, чтобы три
 * детектора не разошлись в трактовке одной и той же линии.
 */
object RayUtil {

    enum class Direction { FILE, RANK, DIAGONAL }

    /** Направление луча из [from] в [to]. Null, если поля совпадают или не лежат на одной линии. */
    fun direction(from: Square, to: Square): Direction? {
        if (from == to) return null
        val df = to.file.ordinal - from.file.ordinal
        val dr = to.rank.ordinal - from.rank.ordinal
        return when {
            df == 0 -> Direction.FILE
            dr == 0 -> Direction.RANK
            kotlin.math.abs(df) == kotlin.math.abs(dr) -> Direction.DIAGONAL
            else -> null
        }
    }

    /**
     * Поля строго между [from] и [to] по прямой. Пустой список, если поля не
     * выровнены — в таком случае "между" не имеет смысла, а не является ошибкой.
     */
    fun squaresBetween(from: Square, to: Square): List<Square> {
        if (direction(from, to) == null) return emptyList()
        val stepFile = signOf(to.file.ordinal - from.file.ordinal)
        val stepRank = signOf(to.rank.ordinal - from.rank.ordinal)

        val result = ArrayList<Square>()
        var file = from.file.ordinal + stepFile
        var rank = from.rank.ordinal + stepRank
        while (file != to.file.ordinal || rank != to.rank.ordinal) {
            result += Square.encode(Rank.allRanks[rank], File.allFiles[file])
            file += stepFile
            rank += stepRank
        }
        return result
    }

    /**
     * Атака скользящей фигуры типа [pieceType] с поля [square] с учётом занятости
     * [occupancy]. Считается через таблицы chesslib, а не вручную по клеткам —
     * это ровно то место, где легко смещением на клетку испортить луч.
     */
    fun slidingAttacks(pieceType: PieceType, square: Square, occupancy: Long): Long = when (pieceType) {
        PieceType.BISHOP -> Bitboard.getBishopAttacks(occupancy, square)
        PieceType.ROOK -> Bitboard.getRookAttacks(occupancy, square)
        PieceType.QUEEN -> Bitboard.getQueenAttacks(occupancy, square)
        else -> 0L
    }

    /**
     * Фигура, что стоит сразу за [blocker] на луче скользящей атаки из [from]
     * (рентген). Null, если за ней в этом направлении никого нет.
     *
     * Считается так: убираем [blocker] из занятости и смотрим, что луч задел
     * заново — там не может быть ничего, кроме следующей фигуры на пути, ведь
     * атака по определению останавливается на первой преграде.
     */
    fun behindBlocker(pieceType: PieceType, from: Square, occupancy: Long, blocker: Square): Square? {
        val direct = slidingAttacks(pieceType, from, occupancy)
        val withoutBlocker = occupancy and blocker.bitboard.inv()
        val extended = slidingAttacks(pieceType, from, withoutBlocker)
        val newlyOccupied = extended and direct.inv() and withoutBlocker
        return Bitboard.bbToSquareList(newlyOccupied).firstOrNull()
    }

    private fun signOf(value: Int): Int = when {
        value > 0 -> 1
        value < 0 -> -1
        else -> 0
    }
}
