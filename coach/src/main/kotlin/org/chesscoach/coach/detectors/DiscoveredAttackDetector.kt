package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Bitboard
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact

/**
 * Вскрытая атака: своя фигура стоит между своим же дальнобойщиком и вражеской
 * целью. Стоит ей уйти с этой линии — и дальнобойщик бьёт цель напрямую.
 *
 * Геометрически это та же связка, только по другую сторону — блокирует свой,
 * а не чужой. Мало того, что фигура стоит на линии: нужно ещё, чтобы у неё
 * был законный ход, который действительно уводит её с этой линии — иначе это
 * не вскрытая атака, а просто пешка на своей вертикали перед своей ладьёй,
 * как в стартовой позиции. Прямой ход пешки вперёд её не уводит: она там же
 * и остаётся, поэтому его недостаточно — нужен ход, после которого дальнобойщик
 * реально бьёт цель.
 *
 * Линии мало: удар должен что-то давать. Поэтому цель — либо король (вскрытый
 * шах), либо фигура, которую после ухода блокирующей становится выгодно забрать
 * и которую нельзя было так же забрать до этого. Пешка под защитой пешки или
 * слон под защитой, которого бьёт ферзь, ударом не считаются.
 */
class DiscoveredAttackDetector : Detector {
    override val name: String = "discovered_attack"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()
        val occupancy = board.getBitboard()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val slider = board.getPiece(square)
            if (slider == Piece.NONE || slider.pieceType !in SLIDING_TYPES) continue

            val side = slider.pieceSide
            val rayAttacks = RayUtil.slidingAttacks(slider.pieceType, square, occupancy)
            val blockers = Bitboard.bbToSquareList(rayAttacks and occupancy)

            for (moving in blockers) {
                val blockerPiece = board.getPiece(moving)
                if (blockerPiece.pieceSide != side) continue // раскрыть может только своя фигура

                val target = RayUtil.behindBlocker(slider.pieceType, square, occupancy, moving) ?: continue
                val targetPiece = board.getPiece(target)
                if (targetPiece.pieceSide != side.flip()) continue // цель должна быть вражеской

                if (!canStepOffRay(board, side, square, slider.pieceType, moving, target)) continue

                facts += DiscoveredAttackFact(
                    moving = moving,
                    revealed = square,
                    target = target,
                    favours = side,
                    evidence = Evidence.onBoard(board, square, moving, target),
                )
            }
        }

        return facts
    }

    /**
     * Есть ли у фигуры на [moving] законный ход, после которого дальнобойщик
     * с [square] действительно бьёт [target] и что-то этим выигрывает. Пешка,
     * шагнувшая вперёд по той же вертикали, линию не открывает — считается
     * только реальный уход с луча.
     */
    private fun canStepOffRay(
        board: Board,
        side: Side,
        square: Square,
        sliderType: PieceType,
        moving: Square,
        target: Square,
    ): Boolean {
        val working = board.clone()
        if (working.sideToMove != side) working.sideToMove = side

        val targetIsKing = board.getPiece(target).pieceType == PieceType.KING
        val gainBefore = if (targetIsKing) 0 else SeeCalculator.evaluateSquare(board, target, side)

        return working.legalMoves().filter { it.from == moving && it.to != target }.any { move ->
            val after = working.clone()
            after.doMove(move)
            val opened = RayUtil.slidingAttacks(sliderType, square, after.getBitboard()) and target.bitboard != 0L
            opened && (targetIsKing || winsMaterial(after, target, side, gainBefore))
        }
    }

    private fun winsMaterial(after: Board, target: Square, side: Side, gainBefore: Int): Boolean {
        val gain = SeeCalculator.evaluateSquare(after, target, side)
        return gain >= SeeCalculator.WINNING_MIN_CP && gain > gainBefore
    }

    private companion object {
        val SLIDING_TYPES = setOf(PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)
    }
}
