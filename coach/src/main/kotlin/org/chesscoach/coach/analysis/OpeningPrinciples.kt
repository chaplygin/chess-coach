package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.CastleRight
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.detectors.PhaseClassifier
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.OpeningCaveat

/**
 * Дебютное правило «каждой фигурой по одному разу, пока не развиты остальные».
 *
 * Клубного игрока учат ему в первый же месяц, и когда тренер вдруг советует
 * второй ход той же фигурой, совет выглядит как противоречие всему, что человек
 * знает. Промолчать тут хуже, чем объяснить: непонятая рекомендация не
 * выполняется, а доверие к остальному разбору падает.
 *
 * Определяется целиком из позиции, без истории партии: если фигура стоит не на
 * своём начальном поле, то этот ход для неё — уже не первый. Для нестандартной
 * начальной расстановки (Chess960, позиции из задач) вывод будет неверным,
 * поэтому всё ограничено дебютной стадией обычной партии.
 */
object OpeningPrinciples {

    /** Начальные поля лёгких фигур — только они считаются «домом» для развития. */
    private val WHITE_MINOR_HOME = mapOf(
        Square.B1 to PieceType.KNIGHT,
        Square.G1 to PieceType.KNIGHT,
        Square.C1 to PieceType.BISHOP,
        Square.F1 to PieceType.BISHOP,
    )

    private val BLACK_MINOR_HOME = mapOf(
        Square.B8 to PieceType.KNIGHT,
        Square.G8 to PieceType.KNIGHT,
        Square.C8 to PieceType.BISHOP,
        Square.F8 to PieceType.BISHOP,
    )

    private val HOME_ROOK = mapOf(
        Square.A1 to Side.WHITE,
        Square.H1 to Side.WHITE,
        Square.A8 to Side.BLACK,
        Square.H8 to Side.BLACK,
    )

    fun caveat(fen: String, bestMoveUci: String): OpeningCaveat? {
        if (bestMoveUci.length < 4) return null

        val board = Board()
        if (runCatching { board.loadFromFen(fen) }.isFailure) return null
        if (PhaseClassifier.classify(board) != GamePhase.OPENING) return null

        val from = runCatching { Square.fromValue(bestMoveUci.take(2).uppercase()) }.getOrNull() ?: return null
        val piece = board.getPiece(from)
        if (piece == Piece.NONE) return null

        val type = piece.pieceType ?: return null
        // Пешки правилу не подчиняются (пешечные ходы и есть дебют), а ход
        // королём — это либо рокировка, либо вынужденный уход, и мерить его
        // правилом развития бессмысленно.
        if (type == PieceType.PAWN || type == PieceType.KING) return null

        val mover = piece.pieceSide
        val homes = homeSquares(mover)
        // Фигура ещё дома — значит, это её первый ход, и правило не нарушено.
        if (homes[from] == type) return null
        if (HOME_ROOK[from] == mover && type == PieceType.ROOK) return null

        val undeveloped = homes
            .filter { (square, homeType) -> board.getPiece(square) == Piece.make(mover, homeType) }
            .keys
            .toList()

        val castlingPending = board.getCastleRight(mover) != CastleRight.NONE

        // Развивать больше нечего и рокировка уже не нужна — упрекать ход не в
        // чем, оговорка была бы придиркой на пустом месте.
        if (undeveloped.isEmpty() && !castlingPending) return null

        return OpeningCaveat(
            movedPiece = piece,
            from = from,
            undevelopedSquares = undeveloped,
            castlingPending = castlingPending,
            evidence = Evidence.onBoard(board, *(listOf(from) + undeveloped).toTypedArray()),
        )
    }

    private fun homeSquares(side: Side): Map<Square, PieceType> =
        if (side == Side.WHITE) WHITE_MINOR_HOME else BLACK_MINOR_HOME
}
