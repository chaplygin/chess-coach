package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.detectors.AttackMap
import org.chesscoach.coach.detectors.SeeCalculator

/**
 * Что делает ход — глаголами, проверяемыми на доске: "уводит слона из-под
 * удара", "защищает пешку c2 от слона f5", "нападает на ладью d1". Так
 * объясняется ход человека и ход против угрозы, а не одной оценкой.
 */
internal object MoveEffects {

    /** Всё, что ход делает, по порядку важности. Пусто — доску прочитать не удалось. */
    fun phrases(fen: String, uci: String, withCapture: Boolean = true): List<String> =
        runCatching { describe(fen, uci, withCapture, attacksOnly = false) }.getOrDefault(emptyList())

    /** Только новые удары хода: "нападает на ладью d1". */
    fun attacks(fen: String, uci: String): List<String> =
        runCatching { describe(fen, uci, withCapture = false, attacksOnly = true) }.getOrDefault(emptyList())

    private fun describe(fen: String, uci: String, withCapture: Boolean, attacksOnly: Boolean): List<String> {
        val before = Board()
        before.loadFromFen(fen)
        val mover = before.sideToMove
        val opponent = mover.flip()
        val move = Move(uci, mover)
        val moved = before.getPiece(move.from)
        val movedType = moved.pieceType ?: return emptyList()
        val after = before.clone()
        if (!after.doMove(move)) return emptyList()
        val result = ArrayList<String>()

        if (!attacksOnly) {
            before.getPiece(move.to).takeIf { it != Piece.NONE }?.pieceType?.let { victim ->
                if (withCapture) result += "берёт " + PieceLexicon.name(victim, GrammaticalCase.ACCUSATIVE) + " " + sq(move.to)
            }
            // Мат и есть смысл хода: остальное на доске, где партия кончилась, не важно.
            if (after.isMated) return result + "ставит мат"
            val check = after.isKingAttacked
            if (check) result += "ставит шах"
            // Под шахом размен не посчитать: что висит, а что нет, после шаха не видно.
            if (check) return attackPhrases(before, after, move, mover, movedType, result)
            val wasHanging = movedType != PieceType.KING && hanging(before, move.from, opponent)
            if (wasHanging && !hanging(after, move.to, opponent)) {
                result += "уводит " + PieceLexicon.name(movedType, GrammaticalCase.ACCUSATIVE) + " из-под удара"
            }
            // Свои фигуры, которые висели до хода и больше не висят.
            for (square in Square.values()) {
                if (square == Square.NONE || square == move.from || square == move.to) continue
                val piece = before.getPiece(square)
                if (piece == Piece.NONE || piece.pieceSide != mover || piece.pieceType == PieceType.KING) continue
                if (!hanging(before, square, opponent) || hanging(after, square, opponent)) continue
                val attacker = SeeCalculator.firstCapture(before, square, opponent)?.from ?: continue
                val attackerType = before.getPiece(attacker).pieceType ?: continue
                if (after.getPiece(attacker) != before.getPiece(attacker)) continue
                val guards = AttackMap.attacksFrom(after, move.to) and square.bitboard != 0L
                val verb = if (guards) "защищает " else "закрывает "
                result += verb + PieceLexicon.name(piece.pieceType, GrammaticalCase.ACCUSATIVE) + " " + sq(square) +
                    " от " + PieceLexicon.name(attackerType, GrammaticalCase.GENITIVE) + " " + sq(attacker)
                if (result.size >= MAX_PHRASES) break
            }
        }

        return attackPhrases(before, after, move, mover, movedType, result)
    }

    private fun attackPhrases(
        before: Board,
        after: Board,
        move: Move,
        mover: com.github.bhlangonijr.chesslib.Side,
        movedType: PieceType,
        result: MutableList<String>,
    ): List<String> {
        val opponent = mover.flip()
        val targets = Square.values().filter { square ->
            if (square == Square.NONE) return@filter false
            val piece = after.getPiece(square)
            if (piece == Piece.NONE || piece.pieceSide != opponent) return@filter false
            val type = piece.pieceType ?: return@filter false
            if (type == PieceType.KING) return@filter false
            if (AttackMap.attacksFrom(after, move.to) and square.bitboard == 0L) return@filter false
            val wins = SeeCalculator.evaluateSquare(after, square, mover) >= SeeCalculator.WINNING_MIN_CP
            val bigger = SeeCalculator.value(type) > SeeCalculator.value(movedType)
            val new = before.getPiece(square) != piece || !hanging(before, square, mover)
            (wins || bigger) && new
        }.sortedByDescending { SeeCalculator.value(after.getPiece(it).pieceType) }.take(2)
        if (targets.isNotEmpty()) {
            result += "нападает на " + list(targets.map { PieceLexicon.name(after.getPiece(it).pieceType, GrammaticalCase.ACCUSATIVE) + " " + sq(it) })
        }
        return result.take(MAX_PHRASES)
    }

    private const val MAX_PHRASES = 3

    /** Фигура на [square] висит для [attacker]: размен на ней выигрывает. */
    private fun hanging(board: Board, square: Square, attacker: com.github.bhlangonijr.chesslib.Side): Boolean =
        board.getPiece(square) != Piece.NONE && SeeCalculator.evaluateSquare(board, square, attacker) >= SeeCalculator.WINNING_MIN_CP

    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.first()
        else -> items.dropLast(1).joinToString(", ") + " и " + items.last()
    }

    private fun sq(square: Square): String = square.toString().lowercase()
}
