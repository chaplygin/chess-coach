package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.ForkThreatFact
import org.chesscoach.coach.model.RemoveDefenderFact

/** Законные ходы стороны [side], даже если сейчас очередь соперника. Пусто, если её король под шахом. */
internal fun legalMovesFor(board: Board, side: Side): List<Move> {
    // Сторона на очереди под шахом: ходить за соперника ей нельзя, иначе среди
    // его "ходов" окажется взятие короля, и доска без короля роняет chesslib.
    if (board.sideToMove != side && board.isKingAttacked) return emptyList()
    val working = board.clone()
    if (working.sideToMove != side) {
        working.sideToMove = side
        working.enPassant = Square.NONE
        working.enPassantTarget = Square.NONE
    }
    if (working.isKingAttacked) return emptyList()
    return working.legalMoves().filter { working.getPiece(it.to).pieceType != PieceType.KING }
}

/** Ход на копии доски. Стороне не обязательно быть на очереди. */
internal fun boardAfter(board: Board, side: Side, move: Move): Board {
    val working = board.clone()
    if (working.sideToMove != side) {
        working.sideToMove = side
        working.enPassant = Square.NONE
        working.enPassantTarget = Square.NONE
    }
    working.doMove(move)
    return working
}

/**
 * Вилка на один ход вперёд: фигура ещё не нападает, но может встать на поле,
 * откуда нападёт сразу на две цели и где её нельзя выгодно побить.
 *
 * Всё считает [ForkDetector] на копии доски после этого хода, поэтому своего
 * понимания вилки здесь нет. На сторону не больше одного факта, самого дорогого.
 */
class ForkThreatDetector : Detector {
    override val name: String = "fork_threat"

    private val forks = ForkDetector()

    override fun detect(board: Board): List<Fact> = listOf(Side.WHITE, Side.BLACK).mapNotNull { side ->
        legalMovesFor(board, side)
            .filter { board.getPiece(it.from).pieceType != PieceType.KING && board.getPiece(it.to) == Piece.NONE }
            .mapNotNull { move ->
                val after = boardAfter(board, side, move)
                forks.forkFrom(after, move.to)?.let { fork ->
                    val opponent = side.flip()
                    val delta = SeeCalculator.firstCaptureDelta(after, move.to, opponent)
                    val traded = delta != null && delta > -SeeCalculator.WINNING_MIN_CP
                    val taker = if (traded) SeeCalculator.firstCapture(after, move.to, opponent)?.from else null
                    ForkThreatFact(
                        from = move.from,
                        to = move.to,
                        piece = board.getPiece(move.from),
                        targets = fork.targets,
                        favours = side,
                        evidence = Evidence.onBoard(board, move.from, move.to, *fork.targets.toTypedArray()),
                        tradeLineSan = if (traded) SeeCalculator.exchangeLineSan(after, move.to, opponent) else emptyList(),
                        tradeDeltaCentipawns = delta.takeIf { traded } ?: 0,
                        tradedBy = taker?.let(after::getPiece),
                    )
                }
            }
            .maxByOrNull { fact -> fact.targets.sumOf { SeeCalculator.value(board.getPiece(it).pieceType) } }
    }
}

/**
 * Снятие защитника: взятие не проигрывает материал, а другая фигура после него
 * выгодно бьёт то, что до взятия было защищено.
 *
 * Сравниваются две настоящие доски, до взятия и после. Цель от лёгкой фигуры и
 * выше: потеря защиты у пешки редко стоит отдельной фразы.
 */
class RemoveDefenderDetector : Detector {
    override val name: String = "remove_defender"

    override fun detect(board: Board): List<Fact> = listOf(Side.WHITE, Side.BLACK).mapNotNull { side ->
        firstFor(board, side)
    }

    private fun firstFor(board: Board, side: Side): Fact? {
        val opponent = side.flip()
        val targets = Square.values().filter { square ->
            square != Square.NONE && board.getPiece(square).let { piece ->
                piece != Piece.NONE && piece.pieceSide == opponent &&
                    piece.pieceType != PieceType.KING &&
                    SeeCalculator.value(piece.pieceType) >= SeeCalculator.KNIGHT_VALUE
            }
        }.filter { AttackMap.isAttacked(board, it, side) && SeeCalculator.evaluateSquare(board, it, side) <= 0 }

        for (move in legalMovesFor(board, side)) {
            val defender = move.to
            val captured = board.getPiece(defender)
            if (captured == Piece.NONE || captured.pieceSide != opponent) continue
            val see = SeeCalculator.evaluateMove(board, move) ?: continue
            if (see < 0) continue

            val after = boardAfter(board, side, move)
            for (target in targets) {
                if (target == defender) continue
                if (defender !in AttackMap.attackersOf(board, target, opponent)) continue
                if (SeeCalculator.evaluateSquare(after, target, side) <= 0) continue
                // Бить должна другая фигура: взявшую защитника могут сразу забрать.
                val first = SeeCalculator.firstCapture(after, target, side) ?: continue
                if (first.from == move.to) continue
                return RemoveDefenderFact(
                    capturer = move.from,
                    defender = defender,
                    target = target,
                    favours = side,
                    evidence = Evidence.onBoard(board, move.from, defender, target),
                )
            }
        }
        return null
    }
}
