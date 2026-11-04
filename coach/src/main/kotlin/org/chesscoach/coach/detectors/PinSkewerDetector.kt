package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.SkewerFact

/**
 * Связки и рентгены: скользящая фигура бьёт вражескую фигуру, а за ней по той
 * же линии стоит вторая вражеская фигура (или король).
 *
 * Всё решает сравнение стоимостей передней и задней фигуры: король сзади —
 * абсолютная связка; фигура сзади дороже — относительная связка; фигура
 * спереди дороже — рентген. Равные по цене — ничья, факт не заявляем: тут
 * нет однозначной трактовки, а выдумывать нельзя.
 */
class PinSkewerDetector : Detector {
    override val name: String = "pin_skewer"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()
        val occupancy = board.getBitboard()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE) continue
            if (piece.pieceType !in SLIDING_TYPES) continue

            val side = piece.pieceSide
            val opponent = side.flip()
            val rayAttacks = RayUtil.slidingAttacks(piece.pieceType, square, occupancy)
            val blockers = Bitboard.bbToSquareList(rayAttacks and occupancy)

            for (front in blockers) {
                val frontPiece = board.getPiece(front)
                if (frontPiece.pieceSide != opponent) continue

                val behind = RayUtil.behindBlocker(piece.pieceType, square, occupancy, front) ?: continue
                val behindPiece = board.getPiece(behind)
                if (behindPiece.pieceSide != opponent) continue

                // Передняя фигура сама бьёт связывающую, и это ей выгодно или ход её:
                // тогда это не связка, а нападение на дальнобойщика. Пешка g5 против
                // слона h4 при коне f6 ничем не скована, она просто берёт слона.
                val pinnerDefended = AttackMap.isAttacked(board, square, side)
                val canTake = AttackMap.attacksFrom(board, front) and square.bitboard != 0L && (
                    !pinnerDefended ||
                        (frontPiece.pieceType != PieceType.KING &&
                            SeeCalculator.value(frontPiece.pieceType) <= SeeCalculator.value(piece.pieceType))
                    )
                if (behindPiece.pieceType != PieceType.KING && canTake && (
                        board.sideToMove == opponent ||
                            SeeCalculator.value(frontPiece.pieceType) < SeeCalculator.value(piece.pieceType)
                        )
                ) {
                    continue
                }

                val frontValue = SeeCalculator.value(frontPiece.pieceType)
                val evidence = Evidence.onBoard(board, square, front, behind)

                when {
                    behindPiece.pieceType == PieceType.KING -> facts += PinFact(
                        pinned = front,
                        pinner = square,
                        behind = behind,
                        absolute = true,
                        favours = side,
                        evidence = evidence,
                    )

                    SeeCalculator.value(behindPiece.pieceType) > frontValue -> facts += PinFact(
                        pinned = front,
                        pinner = square,
                        behind = behind,
                        absolute = false,
                        favours = side,
                        evidence = evidence,
                    )

                    frontValue > SeeCalculator.value(behindPiece.pieceType) &&
                        winsBehind(board, square, front, behind) -> facts += SkewerFact(
                        front = front,
                        behind = behind,
                        attacker = square,
                        favours = side,
                        evidence = evidence,
                    )
                }
            }
        }

        return facts
    }

    /**
     * Рентген чего-то стоит, только если после ухода передней фигуры заднюю
     * можно взять с выгодой: она не защищена никем, кроме самой передней, или
     * дороже нападающего. Пешка c7 за королём c6 при слоне d6 ничего не даёт.
     */
    private fun winsBehind(board: Board, attacker: Square, front: Square, behind: Square): Boolean {
        val behindPiece = board.getPiece(behind)
        if (SeeCalculator.value(behindPiece.pieceType) > SeeCalculator.value(board.getPiece(attacker).pieceType)) return true
        return AttackMap.attackersOf(board, behind, behindPiece.pieceSide).none { it != front }
    }

    private companion object {
        val SLIDING_TYPES = setOf(PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)
    }
}
