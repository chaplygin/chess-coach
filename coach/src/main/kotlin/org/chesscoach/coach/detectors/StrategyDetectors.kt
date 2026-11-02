package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.EndgameFact
import org.chesscoach.coach.model.EndgameKind
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.PawnBreakFact
import org.chesscoach.coach.model.WeakSquareFact
import kotlin.math.abs
import kotlin.math.max

private const val BOARD_SIZE = 8

private fun fileOf(square: Square): Int = "ABCDEFGH".indexOf(square.value()[0])

private fun rankOf(square: Square): Int = square.value()[1].digitToInt() - 1

private fun squareAt(file: Int, rank: Int): Square? {
    if (file !in 0 until BOARD_SIZE || rank !in 0 until BOARD_SIZE) return null
    return Square.valueOf("" + "ABCDEFGH"[file] + (rank + 1))
}

private fun piecesOf(board: Board, side: Side, type: PieceType): List<Square> =
    board.getPieceLocation(Piece.make(side, type))

/**
 * Слабое поле у короля: стоит перед королём, стоящим дома, своя пешка его уже
 * никогда не прикроет, а соперник на него смотрит.
 *
 * Без последнего условия дырой считался бы любой ход пешкой вперёд.
 */
class WeakSquareDetector : Detector {
    override val name: String = "weak_square"

    override fun detect(board: Board): List<Fact> {
        if (PhaseClassifier.classify(board) == GamePhase.ENDGAME) return emptyList()
        return listOf(Side.WHITE, Side.BLACK).mapNotNull { owner -> weakSquare(board, owner) }
    }

    private fun weakSquare(board: Board, owner: Side): Fact? {
        val king = board.getKingSquare(owner)
        val forward = if (owner == Side.WHITE) 1 else -1
        val home = if (owner == Side.WHITE) 0 else BOARD_SIZE - 1
        if (rankOf(king) != home) return null

        val opponent = owner.flip()
        val pawns = piecesOf(board, owner, PieceType.PAWN)
        for (distance in 1..2) {
            for (file in fileOf(king) - 1..fileOf(king) + 1) {
                val square = squareAt(file, home + forward * distance) ?: continue
                val occupant = board.getPiece(square)
                if (occupant != Piece.NONE && occupant.pieceSide == owner) continue
                if (!AttackMap.isAttacked(board, square, opponent)) continue
                val canBeCovered = pawns.any { pawn ->
                    abs(fileOf(pawn) - file) == 1 && (rankOf(square) - rankOf(pawn)) * forward > 0
                }
                if (canBeCovered) continue
                return WeakSquareFact(square, owner, opponent, Evidence.onBoard(board, square, king))
            }
        }
        return null
    }
}

/**
 * Пешечный рычаг: пешка идёт вперёд, безопасно нападает на пешку соперника и
 * вскрывает игру. Безопасность проверяет размен на копии доски. Центральные
 * вертикали идут первыми: там вскрытие меняет больше всего.
 */
class PawnBreakDetector : Detector {
    override val name: String = "pawn_break"

    override fun detect(board: Board): List<Fact> {
        if (PhaseClassifier.classify(board) == GamePhase.ENDGAME) return emptyList()
        return listOf(Side.WHITE, Side.BLACK).mapNotNull { side -> pawnBreak(board, side) }
    }

    private fun pawnBreak(board: Board, side: Side): Fact? {
        val opponent = side.flip()
        val enemyPawns = board.getBitboard(Piece.make(opponent, PieceType.PAWN))
        return legalMovesFor(board, side)
            .filter { board.getPiece(it.from).pieceType == PieceType.PAWN && fileOf(it.from) == fileOf(it.to) }
            .sortedBy { abs(fileOf(it.to) * 2 - (BOARD_SIZE - 1)) }
            .firstNotNullOfOrNull { move ->
                val after = boardAfter(board, side, move)
                val hit = Bitboard.bbToSquareList(AttackMap.attacksFrom(after, move.to) and enemyPawns).firstOrNull()
                if (hit == null || SeeCalculator.evaluateSquare(after, move.to, opponent) > 0) {
                    null
                } else {
                    PawnBreakFact(
                        from = move.from,
                        to = move.to,
                        target = hit,
                        owner = side,
                        favours = side,
                        evidence = Evidence.onBoard(board, move.from, hit),
                    )
                }
            }
    }
}

/** Эндшпильные признаки: ладья за проходной, правило квадрата, активный король, разноцветные слоны. */
class EndgameDetector : Detector {
    override val name: String = "endgame"

    override fun detect(board: Board): List<Fact> {
        if (PhaseClassifier.classify(board) != GamePhase.ENDGAME) return emptyList()
        val facts = ArrayList<Fact>()
        for (side in listOf(Side.WHITE, Side.BLACK)) {
            for (pawn in piecesOf(board, side, PieceType.PAWN).filter { isPassed(board, it, side) }) {
                rookBehind(board, pawn, side)?.let { facts += it }
                kingOutsideSquare(board, pawn, side)?.let { facts += it }
            }
        }
        activeKing(board)?.let { facts += it }
        oppositeBishops(board)?.let { facts += it }
        return facts
    }

    private fun isPassed(board: Board, pawn: Square, side: Side): Boolean {
        val forward = if (side == Side.WHITE) 1 else -1
        return piecesOf(board, side.flip(), PieceType.PAWN).none { enemy ->
            abs(fileOf(enemy) - fileOf(pawn)) <= 1 && (rankOf(enemy) - rankOf(pawn)) * forward > 0
        }
    }

    private fun rookBehind(board: Board, pawn: Square, side: Side): Fact? {
        val forward = if (side == Side.WHITE) 1 else -1
        val rook = piecesOf(board, side, PieceType.ROOK).firstOrNull {
            fileOf(it) == fileOf(pawn) && (rankOf(pawn) - rankOf(it)) * forward > 0
        } ?: return null
        return EndgameFact(EndgameKind.ROOK_BEHIND_PASSED, pawn, side, side, Evidence.onBoard(board, pawn, rook))
    }

    /** Правило квадрата работает только когда у соперника нет фигур: иначе пешку ловит не король. */
    private fun kingOutsideSquare(board: Board, pawn: Square, side: Side): Fact? {
        val opponent = side.flip()
        val hasPieces = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
            .any { piecesOf(board, opponent, it).isNotEmpty() }
        if (hasPieces) return null

        val promotion = if (side == Side.WHITE) BOARD_SIZE - 1 else 0
        val start = if (side == Side.WHITE) 1 else BOARD_SIZE - 2
        // С начальной горизонтали пешка идёт на два поля сразу.
        val steps = abs(promotion - rankOf(pawn)) - if (rankOf(pawn) == start) 1 else 0
        val king = board.getKingSquare(opponent)
        val distance = max(abs(fileOf(king) - fileOf(pawn)), abs(rankOf(king) - promotion))
        val allowance = if (board.sideToMove == opponent) steps else steps - 1
        if (distance <= allowance) return null
        return EndgameFact(EndgameKind.KING_OUTSIDE_SQUARE, pawn, side, side, Evidence.onBoard(board, pawn, king))
    }

    private fun centreDistance(square: Square): Int {
        val file = max(max(3 - fileOf(square), fileOf(square) - 4), 0)
        val rank = max(max(3 - rankOf(square), rankOf(square) - 4), 0)
        return max(file, rank)
    }

    private fun activeKing(board: Board): Fact? {
        val white = board.getKingSquare(Side.WHITE)
        val black = board.getKingSquare(Side.BLACK)
        val gap = centreDistance(black) - centreDistance(white)
        if (abs(gap) < ACTIVE_KING_GAP) return null
        val side = if (gap > 0) Side.WHITE else Side.BLACK
        val king = if (gap > 0) white else black
        return EndgameFact(EndgameKind.ACTIVE_KING, king, side, side, Evidence.onBoard(board, king))
    }

    private fun oppositeBishops(board: Board): Fact? {
        val white = piecesOf(board, Side.WHITE, PieceType.BISHOP)
        val black = piecesOf(board, Side.BLACK, PieceType.BISHOP)
        if (white.size != 1 || black.size != 1) return null
        val colour = { square: Square -> (fileOf(square) + rankOf(square)) % 2 }
        if (colour(white[0]) == colour(black[0])) return null
        // Такой эндшпиль помогает отстающему: ему и идёт на пользу факт.
        val weaker = if (materialOf(board, Side.WHITE) < materialOf(board, Side.BLACK)) Side.WHITE else Side.BLACK
        return EndgameFact(EndgameKind.OPPOSITE_BISHOPS, white[0], weaker, weaker, Evidence.onBoard(board, white[0], black[0]))
    }

    private fun materialOf(board: Board, side: Side): Int =
        listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN)
            .sumOf { type -> SeeCalculator.value(type) * piecesOf(board, side, type).size }

    private companion object {
        const val ACTIVE_KING_GAP = 2
    }
}
