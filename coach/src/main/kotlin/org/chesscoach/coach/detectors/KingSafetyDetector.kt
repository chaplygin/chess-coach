package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.File
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.KingSafetyFact

/**
 * Безопасность короля: пешечный щит, кто из соперника уже смотрит в зону
 * короля, и какие вертикали рядом не прикрыты своей пешкой.
 *
 * Специально не смотрим на факт рокировки: непроходная пешка e2-e4-e5 в
 * центре — не то же самое, что открытая линия перед королём, оставшимся
 * в центре. За саму невыполненную рокировку отвечает DevelopmentDetector,
 * а этот детектор молчит, если по доске нет ни одного из трёх конкретных
 * признаков — король может быть спокоен и без рокировки.
 */
class KingSafetyDetector : Detector {
    override val name: String = "king_safety"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (kingSide in Side.values()) {
            val king = board.getKingSquare(kingSide)
            if (king == Square.NONE) continue

            val enemy = kingSide.flip()
            val zone = kingZone(king)

            val attackerSquares = zone.flatMap { AttackMap.attackersOf(board, it, enemy) }.distinct()

            val missingShield = missingShieldSquares(board, king, kingSide)

            val openFiles = openFilesNearKing(board, king, kingSide)

            if (!isSerious(attackerSquares.size, missingShield.size, openFiles.size)) continue

            val evidenceSquares = (listOf(king) + missingShield + attackerSquares).distinct()

            facts += KingSafetyFact(
                king = king,
                kingSide = kingSide,
                attackerCount = attackerSquares.size,
                shieldPawnsMissing = missingShield.size,
                openFilesNearKing = openFiles,
                favours = enemy,
                evidence = Evidence.of(*evidenceSquares.toTypedArray()),
            )
        }

        return facts
    }

    /**
     * Одного признака мало — нужно сочетание.
     *
     * Раньше факт создавался от любого из трёх признаков по отдельности, и это
     * оказалось почти всегда: уже после 1.e4 у белого короля нет пешки щита на
     * e2. Тренер в итоге на каждой позиции советовал "укрепить оборону короля",
     * совет обесценился и стал шумом, а настоящую опасность в нём было не
     * разглядеть. Теперь нужно либо двое нападающих, либо нападающий вместе с
     * дырой в прикрытии, либо уже разобранный щит.
     */
    private fun isSerious(attackers: Int, missingShield: Int, openFiles: Int): Boolean = when {
        attackers >= 2 -> true
        attackers >= 1 && (missingShield >= 1 || openFiles >= 1) -> true
        missingShield >= 2 && openFiles >= 1 -> true
        else -> false
    }

    /** Клетка короля и восемь соседних — зона, откуда считаем нападающих. */
    private fun kingZone(king: Square): List<Square> =
        listOf(king) + Bitboard.bbToSquareList(Bitboard.getKingAttacks(king, -1L))

    /**
     * Поля пешечного щита — три клетки на ранге перед королём (по вертикалям
     * king-1, king, king+1), которые должна занимать своя пешка. Возвращает
     * только те из них, где своей пешки сейчас нет — щита там не оказалось.
     */
    private fun missingShieldSquares(board: Board, king: Square, kingSide: Side): List<Square> {
        val shieldRankIndex = king.rank.ordinal + if (kingSide == Side.WHITE) 1 else -1
        if (shieldRankIndex !in 0..7) return emptyList()
        val shieldRank = com.github.bhlangonijr.chesslib.Rank.allRanks[shieldRankIndex]

        val files = (king.file.ordinal - 1..king.file.ordinal + 1).filter { it in 0..7 }
        val shieldSquares = files.map { Square.encode(shieldRank, File.allFiles[it]) }

        return shieldSquares.filter { sq ->
            val piece = board.getPiece(sq)
            !(piece != Piece.NONE && piece.pieceSide == kingSide && piece.pieceType == PieceType.PAWN)
        }
    }

    /** Вертикали king-1, king, king+1, на которых у владельца короля нет своей пешки. */
    private fun openFilesNearKing(board: Board, king: Square, kingSide: Side): List<Char> {
        val ownPawns = board.getPieceLocation(Piece.make(kingSide, PieceType.PAWN))
        val files = (king.file.ordinal - 1..king.file.ordinal + 1).filter { it in 0..7 }

        return files.filter { fileIndex ->
            ownPawns.none { it.file.ordinal == fileIndex }
        }.map { fileLetter(it) }
    }

    private fun fileLetter(fileIndex: Int): Char = ('a' + fileIndex)
}
