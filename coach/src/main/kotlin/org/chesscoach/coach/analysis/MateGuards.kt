package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.detectors.AttackMap
import org.chesscoach.coach.detectors.boardAfter
import org.chesscoach.coach.detectors.legalMovesFor
import org.chesscoach.coach.model.AllowedMate
import org.chesscoach.coach.model.AllowedMateReason
import org.chesscoach.coach.model.MateGuard
import org.chesscoach.core.MoveNotation

/**
 * Мат, которого нет только благодаря одной фигуре: шах [attacker] пока не мат,
 * потому что фигура защитника держит поле, куда бьёт шахующая. Уберите её — и
 * шах становится матом. Проверяется правилами, без движка.
 */
internal object MateGuards {
    private const val MAX_GUARDS = 2

    fun find(fen: String, attacker: Side): List<MateGuard> {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return emptyList()
        return runCatching { search(board, attacker) }.getOrDefault(emptyList())
    }

    private fun search(board: Board, attacker: Side): List<MateGuard> {
        val moves = legalMovesFor(board, attacker)
        if (moves.isEmpty()) return emptyList()
        val base = turned(board, attacker)
        val defender = attacker.flip()
        val found = ArrayList<MateGuard>()
        for (move in moves) {
            val after = boardAfter(base, attacker, move)
            if (!after.isKingAttacked || after.isMated) continue
            for (square in AttackMap.attackersOf(base, move.to, defender)) {
                val piece = base.getPiece(square)
                if (piece.pieceType == PieceType.KING) continue
                val probe = base.clone()
                probe.unsetPiece(piece, square)
                if (probe.legalMoves().none { it == move }) continue
                val mated = boardAfter(probe, attacker, move)
                if (!mated.isMated) continue
                val san = MoveNotation.uciToSanOrRaw(probe.fen, listOf(move.toString())).first()
                found += MateGuard(square, piece.pieceType, move.to, san)
            }
            if (found.size >= MAX_GUARDS) break
        }
        return found.distinctBy { it.defender.name + it.target.name }.take(MAX_GUARDS)
    }

    /**
     * Ход [playedUci] пропускает мат в один ход: какой и почему. Причина ищется
     * по доске до хода: сходивший ушёл с защиты поля мата, перекрыл линию
     * другому защитнику или просто не ответил на уже стоявшую угрозу.
     */
    fun allowed(fen: String, playedUci: String): AllowedMate? = runCatching { allowedOrThrow(fen, playedUci) }.getOrNull()

    private fun allowedOrThrow(fen: String, playedUci: String): AllowedMate? {
        val before = Board()
        if (!runCatching { before.loadFromFen(fen) }.isSuccess) return null
        val afterFen = applyUciMove(fen, playedUci) ?: return null
        val after = Board().also { it.loadFromFen(afterFen) }
        if (after.isMated) return null
        val mate = after.legalMoves().firstOrNull { boardAfter(after, after.sideToMove, it).isMated } ?: return null
        val mateSan = MoveNotation.uciToSanOrRaw(afterFen, listOf(mate.toString())).first()
        val mover = before.sideToMove
        val played = Move(playedUci, mover)
        val moved = before.getPiece(played.from)
        val target = mate.to

        // Сходившая фигура держала поле мата.
        if (played.from != target && AttackMap.attacksFrom(before, played.from) and target.bitboard != 0L) {
            return AllowedMate(mateSan, 1, AllowedMateReason.MOVES_DEFENDER, played.from, moved.pieceType, target)
        }
        // Другая фигура держала поле, а ход встал на её линию.
        for (square in AttackMap.attackersOf(before, target, mover)) {
            if (square == played.from) continue
            if (after.getPiece(square) != before.getPiece(square)) continue
            if (AttackMap.attacksFrom(after, square) and target.bitboard == 0L) {
                return AllowedMate(mateSan, 1, AllowedMateReason.BLOCKS_DEFENDER, square, before.getPiece(square).pieceType, target)
            }
        }
        // Угроза мата стояла и до хода.
        val base = turned(before, mover.flip())
        val threatened = !runCatching { before.isKingAttacked }.getOrDefault(true) &&
            base.legalMoves().any { it == mate && boardAfter(base, base.sideToMove, it).isMated }
        return AllowedMate(mateSan, 1, if (threatened) AllowedMateReason.IGNORES_THREAT else AllowedMateReason.OTHER)
    }

    private fun turned(board: Board, side: Side): Board {
        val working = board.clone()
        if (working.sideToMove != side) {
            working.sideToMove = side
            working.enPassant = Square.NONE
            working.enPassantTarget = Square.NONE
        }
        return working
    }
}
