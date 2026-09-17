package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.File
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Rank
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact

/**
 * Слабость последней горизонтали: король заперт своими же пешками и не может
 * убежать с первой (восьмой) горизонтали.
 *
 * Мало запертого короля — этим нужно кому-то воспользоваться. Поэтому факт
 * заявляется только вместе с [BackRankFact.exploiter]: конкретной вражеской
 * ладьёй или ферзём, у которой уже сейчас или за один ход есть чистая линия
 * на эту горизонталь до короля. Иначе это не слабость, а особенность позиции.
 */
class BackRankDetector : Detector {
    override val name: String = "back_rank"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (kingSide in Side.values()) {
            val kingSquare = board.getKingSquare(kingSide)
            if (kingSquare == Square.NONE) continue

            val backRank = if (kingSide == Side.WHITE) Rank.RANK_1 else Rank.RANK_8
            if (kingSquare.rank != backRank) continue

            val shieldRank = if (kingSide == Side.WHITE) Rank.RANK_2 else Rank.RANK_7
            val shieldFiles = listOf(kingSquare.file.ordinal - 1, kingSquare.file.ordinal, kingSquare.file.ordinal + 1)
                .filter { it in 0..7 }
            val shieldSquares = shieldFiles.map { fileIndex ->
                Square.encode(shieldRank, File.allFiles[fileIndex])
            }

            val blockedBy = shieldSquares.filter { sq ->
                val piece = board.getPiece(sq)
                piece != Piece.NONE && piece.pieceSide == kingSide
            }
            // Побег наверх свободен хотя бы через одну клетку — короля пока никто не запер.
            if (blockedBy.size != shieldSquares.size) continue

            val enemy = kingSide.flip()
            val exploiter = findExploiter(board, enemy, backRank, kingSquare) ?: continue

            facts += BackRankFact(
                king = kingSquare,
                blockedBy = blockedBy,
                exploiter = exploiter,
                favours = enemy,
                evidence = Evidence.of(kingSquare, exploiter, *blockedBy.toTypedArray()),
            )
        }

        return facts
    }

    /** Ладья или ферзь соперника с чистой линией на [backRank] до короля — сейчас или за один ход. */
    private fun findExploiter(board: Board, enemy: Side, backRank: Rank, kingSquare: Square): Square? {
        val candidates = Square.values().filter { sq ->
            sq != Square.NONE && board.getPiece(sq).let {
                it.pieceSide == enemy && (it.pieceType == PieceType.ROOK || it.pieceType == PieceType.QUEEN)
            }
        }

        for (sq in candidates) {
            if (sq.rank == backRank && hasClearRankPath(board, sq, kingSquare)) return sq

            val working = board.clone()
            if (working.sideToMove != enemy) working.sideToMove = enemy
            val landingMoves = working.legalMoves().filter { it.from == sq && it.to.rank == backRank }
            for (move in landingMoves) {
                val after = working.clone()
                after.doMove(move)
                if (hasClearRankPath(after, move.to, kingSquare)) return sq
            }
        }
        return null
    }

    /** Между [from] и [to] на одной горизонтали нет ни одной фигуры. */
    private fun hasClearRankPath(board: Board, from: Square, to: Square): Boolean {
        if (from.rank != to.rank || from == to) return false
        return RayUtil.squaresBetween(from, to).all { board.getPiece(it) == Piece.NONE }
    }
}
