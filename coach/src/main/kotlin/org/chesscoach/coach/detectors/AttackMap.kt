package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

/**
 * Обёртка над атаками chesslib.
 *
 * Детекторам нужен не голый long-битборд, а "какие поля бьёт эта фигура" и
 * "кто бьёт это поле" — то же самое, что делает Board.squareAttackedBy внутри
 * себя, но в одном месте, а не размазано по каждому детектору со своим шансом
 * ошибиться в направлении атаки пешки.
 */
object AttackMap {

    /** Маска "без фильтра" для конник/король — свои клетки не исключаем: держать защиту — законная атака. */
    private const val ALL_SQUARES: Long = -1L

    /** Какие поля бьёт фигура, стоящая на [square], при нынешней занятости доски. Пусто, если клетка пуста. */
    fun attacksFrom(board: Board, square: Square): Long {
        val piece = board.getPiece(square)
        if (piece == Piece.NONE) return 0L
        val occupancy = board.getBitboard()
        return when (piece.pieceType) {
            PieceType.PAWN -> Bitboard.getPawnAttacks(piece.pieceSide, square)
            PieceType.KNIGHT -> Bitboard.getKnightAttacks(square, ALL_SQUARES)
            PieceType.KING -> Bitboard.getKingAttacks(square, ALL_SQUARES)
            else -> RayUtil.slidingAttacks(piece.pieceType, square, occupancy)
        }
    }

    /** Свои поля, которые фигура на [square] защищает (та же атака, но по своим фигурам). */
    fun defends(board: Board, square: Square): List<Square> {
        val piece = board.getPiece(square)
        if (piece == Piece.NONE) return emptyList()
        val own = board.getBitboard(piece.pieceSide)
        return Bitboard.bbToSquareList(attacksFrom(board, square) and own)
    }

    /** Фигуры стороны [side], атакующие поле [square] прямо сейчас. */
    fun attackersOf(board: Board, square: Square, side: Side): List<Square> =
        Bitboard.bbToSquareList(board.squareAttackedBy(square, side))

    fun isAttacked(board: Board, square: Square, side: Side): Boolean =
        board.squareAttackedBy(square, side) != 0L
}
