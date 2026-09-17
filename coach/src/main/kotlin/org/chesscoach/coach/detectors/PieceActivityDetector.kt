package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BadBishopFact
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.CoachThresholds
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OpenFileRookFact

/**
 * Активность фигур: ладья на открытой линии, слон, запертый своими же
 * пешками, и заметный (не любой) перевес в подвижности и контроле центра.
 *
 * Последние два факта — не про конкретную фигуру, а про позицию в целом,
 * поэтому создаются не всегда, а только когда разрыв реально big — иначе
 * тренер будет хвалить за перевес на пол-хода, которого никто не заметит.
 */
class PieceActivityDetector : Detector {
    override val name: String = "piece_activity"

    override fun detect(board: Board): List<Fact> {
        val facts = ArrayList<Fact>()

        for (square in Square.values()) {
            if (square == Square.NONE) continue
            val piece = board.getPiece(square)
            if (piece == Piece.NONE) continue

            when (piece.pieceType) {
                PieceType.ROOK -> detectOpenFileRook(board, square, piece.pieceSide)?.let { facts += it }
                PieceType.BISHOP -> detectBadBishop(board, square, piece.pieceSide)?.let { facts += it }
                else -> {}
            }
        }

        detectMobilityEdge(board)?.let { facts += it }
        detectCenterControlEdge(board)?.let { facts += it }

        return facts
    }

    private fun detectOpenFileRook(board: Board, rook: Square, owner: Side): Fact? {
        val ownPawns = board.getPieceLocation(Piece.make(owner, PieceType.PAWN))
        if (ownPawns.any { it.file == rook.file }) return null // своя пешка на линии — линия не открыта для этой ладьи

        val enemyPawns = board.getPieceLocation(Piece.make(owner.flip(), PieceType.PAWN))
        val halfOpen = enemyPawns.any { it.file == rook.file }

        return OpenFileRookFact(
            rook = rook,
            file = ('a' + rook.file.ordinal),
            halfOpen = halfOpen,
            owner = owner,
            favours = owner,
            evidence = Evidence.of(rook),
        )
    }

    private fun detectBadBishop(board: Board, bishop: Square, owner: Side): Fact? {
        val bishopIsLight = squareIsLight(bishop)
        val ownPawns = board.getPieceLocation(Piece.make(owner, PieceType.PAWN))

        val blockedSameColor = ownPawns.filter { pawn ->
            squareIsLight(pawn) == bishopIsLight && isBlocked(board, pawn, owner)
        }
        if (blockedSameColor.size < BAD_BISHOP_MIN_BLOCKED_PAWNS) return null

        return BadBishopFact(
            bishop = bishop,
            blockedPawns = blockedSameColor,
            owner = owner,
            favours = owner.flip(),
            evidence = Evidence.of(bishop, *blockedSameColor.toTypedArray()),
        )
    }

    private fun squareIsLight(square: Square): Boolean = (square.file.ordinal + square.rank.ordinal) % 2 == 1

    /** Пешка [pawn] не может продвинуться прямо сейчас — поле перед ней занято, кем угодно. */
    private fun isBlocked(board: Board, pawn: Square, owner: Side): Boolean {
        val frontRankIndex = pawn.rank.ordinal + if (owner == Side.WHITE) 1 else -1
        if (frontRankIndex !in 0..7) return false
        val frontSquare = Square.encode(com.github.bhlangonijr.chesslib.Rank.allRanks[frontRankIndex], pawn.file)
        return board.getPiece(frontSquare) != Piece.NONE
    }

    private fun detectMobilityEdge(board: Board): Fact? {
        val whiteMoves = legalMoveCount(board, Side.WHITE)
        val blackMoves = legalMoveCount(board, Side.BLACK)
        val diff = whiteMoves - blackMoves

        if (diff > CoachThresholds.MOBILITY_EDGE) {
            return MobilityEdgeFact(
                owner = Side.WHITE,
                ownMoves = whiteMoves,
                opponentMoves = blackMoves,
                favours = Side.WHITE,
                evidence = Evidence.of(board.getKingSquare(Side.WHITE), board.getKingSquare(Side.BLACK)),
            )
        }
        if (-diff > CoachThresholds.MOBILITY_EDGE) {
            return MobilityEdgeFact(
                owner = Side.BLACK,
                ownMoves = blackMoves,
                opponentMoves = whiteMoves,
                favours = Side.BLACK,
                evidence = Evidence.of(board.getKingSquare(Side.BLACK), board.getKingSquare(Side.WHITE)),
            )
        }
        return null
    }

    private fun legalMoveCount(board: Board, side: Side): Int {
        val working = board.clone()
        if (working.sideToMove != side) {
            working.sideToMove = side
            // Взятие на проходе описывает пешку соперника — при смене очереди хода
            // оно превращается в мираж, разрешающий нелегальное взятие.
            working.enPassant = Square.NONE
            working.enPassantTarget = Square.NONE
        }
        if (working.isKingAttacked) return 0
        return working.legalMoves().size
    }

    private fun detectCenterControlEdge(board: Board): Fact? {
        val whiteControl = CENTER_SQUARES.sumOf { AttackMap.attackersOf(board, it, Side.WHITE).size }
        val blackControl = CENTER_SQUARES.sumOf { AttackMap.attackersOf(board, it, Side.BLACK).size }
        val diff = whiteControl - blackControl

        if (diff > CoachThresholds.CENTER_EDGE) {
            return CenterControlFact(
                owner = Side.WHITE,
                ownControl = whiteControl,
                opponentControl = blackControl,
                favours = Side.WHITE,
                evidence = Evidence.of(*CENTER_SQUARES.toTypedArray()),
            )
        }
        if (-diff > CoachThresholds.CENTER_EDGE) {
            return CenterControlFact(
                owner = Side.BLACK,
                ownControl = blackControl,
                opponentControl = whiteControl,
                favours = Side.BLACK,
                evidence = Evidence.of(*CENTER_SQUARES.toTypedArray()),
            )
        }
        return null
    }

    private companion object {
        /** Столько своих пешек цвета слона должны быть заперты, чтобы слона назвать плохим. */
        const val BAD_BISHOP_MIN_BLOCKED_PAWNS = 3

        val CENTER_SQUARES = listOf(Square.D4, Square.D5, Square.E4, Square.E5)
    }
}
