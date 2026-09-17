package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Rank
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PawnWeaknessKind

/**
 * Пешечная структура: слабости своих пешек, проходные и форпосты в лагере
 * соперника.
 *
 * Каждая слабость и каждая проходная проверяются буквально по соседним
 * вертикалям на доске — никаких "обычно так бывает". Форпост — отдельная
 * история: это не про геометрию пешек, а про уже стоящую там фигуру,
 * поэтому проверяется последним и только по занятым полям.
 */
class PawnStructureDetector : Detector {
    override val name: String = "pawn_structure"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (side in Side.values()) {
            val ownPawns = board.getPieceLocation(Piece.make(side, PieceType.PAWN))
            val enemyPawns = board.getPieceLocation(Piece.make(side.flip(), PieceType.PAWN))

            for (pawn in ownPawns) {
                detectIsolated(pawn, ownPawns, side)?.let { facts += it }
                detectDoubled(pawn, ownPawns, side)?.let { facts += it }
                detectBackward(board, pawn, ownPawns, side)?.let { facts += it }
                detectPassed(board, pawn, enemyPawns, side)?.let { facts += it }
            }
        }

        facts += detectOutposts(board)

        return facts
    }

    private fun detectIsolated(pawn: Square, ownPawns: List<Square>, side: Side): Fact? {
        val file = pawn.file.ordinal
        val neighborFiles = listOf(file - 1, file + 1).filter { it in 0..7 }
        val hasNeighbor = ownPawns.any { it != pawn && it.file.ordinal in neighborFiles }
        if (hasNeighbor) return null

        return PawnWeaknessFact(
            kind = PawnWeaknessKind.ISOLATED,
            square = pawn,
            owner = side,
            favours = side.flip(),
            evidence = Evidence.of(pawn),
        )
    }

    private fun detectDoubled(pawn: Square, ownPawns: List<Square>, side: Side): Fact? {
        val sameFile = ownPawns.filter { it != pawn && it.file == pawn.file }
        if (sameFile.isEmpty()) return null

        return PawnWeaknessFact(
            kind = PawnWeaknessKind.DOUBLED,
            square = pawn,
            owner = side,
            favours = side.flip(),
            evidence = Evidence.of(pawn, *sameFile.toTypedArray()),
        )
    }

    /**
     * Отсталая пешка: обе соседние вертикали с своими пешками есть, и все они
     * дальше продвинуты, чем эта, а поле прямо перед ней держит пешка
     * соперника — шагнуть вперёд без потерь нельзя.
     */
    private fun detectBackward(board: Board, pawn: Square, ownPawns: List<Square>, side: Side): Fact? {
        val file = pawn.file.ordinal
        val rank = pawn.rank.ordinal
        val neighborFiles = listOf(file - 1, file + 1).filter { it in 0..7 }

        val neighborRanks = ownPawns
            .filter { it.file.ordinal in neighborFiles }
            .map { it.rank.ordinal }
        if (neighborRanks.isEmpty()) return null // соседних пешек вовсе нет — это изоляция, а не отсталость

        val allMoreAdvanced = if (side == Side.WHITE) neighborRanks.all { it > rank } else neighborRanks.all { it < rank }
        if (!allMoreAdvanced) return null

        val frontRankIndex = rank + if (side == Side.WHITE) 1 else -1
        if (frontRankIndex !in 0..7) return null
        val frontSquare = Square.encode(Rank.allRanks[frontRankIndex], pawn.file)

        val enemyPawnControllers = AttackMap.attackersOf(board, frontSquare, side.flip())
            .filter { board.getPiece(it).pieceType == PieceType.PAWN }
        if (enemyPawnControllers.isEmpty()) return null

        return PawnWeaknessFact(
            kind = PawnWeaknessKind.BACKWARD,
            square = pawn,
            owner = side,
            favours = side.flip(),
            evidence = Evidence.of(pawn, frontSquare, *enemyPawnControllers.toTypedArray()),
        )
    }

    private fun detectPassed(board: Board, pawn: Square, enemyPawns: List<Square>, side: Side): Fact? {
        val file = pawn.file.ordinal
        val rank = pawn.rank.ordinal
        val relevantFiles = (file - 1..file + 1).filter { it in 0..7 }

        val blockers = enemyPawns.filter { enemy ->
            enemy.file.ordinal in relevantFiles && if (side == Side.WHITE) enemy.rank.ordinal > rank else enemy.rank.ordinal < rank
        }
        if (blockers.isNotEmpty()) return null

        val squaresToPromotion = if (side == Side.WHITE) 7 - rank else rank
        val defenders = AttackMap.attackersOf(board, pawn, side)
            .filter { board.getPiece(it).pieceType == PieceType.PAWN }

        return PassedPawnFact(
            square = pawn,
            owner = side,
            defended = defenders.isNotEmpty(),
            squaresToPromotion = squaresToPromotion,
            favours = side,
            evidence = Evidence.of(pawn),
        )
    }

    private fun detectOutposts(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE) continue
            if (piece.pieceType != PieceType.KNIGHT && piece.pieceType != PieceType.BISHOP) continue

            val owner = piece.pieceSide
            if (!inOpponentCamp(square, owner)) continue

            val pawnDefenders = AttackMap.attackersOf(board, square, owner)
                .filter { board.getPiece(it).pieceType == PieceType.PAWN }
            if (pawnDefenders.isEmpty()) continue

            if (!isSafeFromPawnAttack(board, square, owner)) continue

            facts += OutpostFact(
                square = square,
                piece = piece,
                owner = owner,
                favours = owner,
                evidence = Evidence.of(square, *pawnDefenders.toTypedArray()),
            )
        }

        return facts
    }

    private fun inOpponentCamp(square: Square, owner: Side): Boolean {
        val rank = square.rank.ordinal
        return if (owner == Side.WHITE) rank >= 4 else rank <= 3
    }

    /** Ни сейчас, ни впредь пешка соперника не сможет дойти и разменять форпост. */
    private fun isSafeFromPawnAttack(board: Board, square: Square, owner: Side): Boolean {
        val file = square.file.ordinal
        val rank = square.rank.ordinal
        val adjacentFiles = listOf(file - 1, file + 1).filter { it in 0..7 }

        val enemyPawns = board.getPieceLocation(Piece.make(owner.flip(), PieceType.PAWN))
        return enemyPawns.none { p ->
            p.file.ordinal in adjacentFiles && if (owner == Side.WHITE) p.rank.ordinal > rank else p.rank.ordinal < rank
        }
    }
}
