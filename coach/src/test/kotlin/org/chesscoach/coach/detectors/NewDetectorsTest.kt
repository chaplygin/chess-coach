package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.EndgameFact
import org.chesscoach.coach.model.EndgameKind
import org.chesscoach.coach.model.ForkThreatFact
import org.chesscoach.coach.model.PawnBreakFact
import org.chesscoach.coach.model.RemoveDefenderFact
import org.chesscoach.coach.model.WeakSquareFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewDetectorsTest {

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }

    @Test
    fun `a knight that can jump into a fork is reported before the fork exists`() {
        val fact = ForkThreatDetector().detect(board("r3k3/8/8/3N4/8/8/8/4K3 w - - 0 1"))
            .filterIsInstance<ForkThreatFact>().single { it.favours == Side.WHITE }

        assertEquals(Square.D5, fact.from)
        assertEquals(Square.C7, fact.to)
    }

    @Test
    fun `a jump onto a square where the piece is simply taken is not a fork threat`() {
        // Ладья c8 сразу забирает коня на c7.
        val facts = ForkThreatDetector().detect(board("r1r1k3/8/8/3N4/8/8/8/4K3 w - - 0 1"))

        assertTrue(facts.toString(), facts.none { it is ForkThreatFact && it.to == Square.C7 })
    }

    @Test
    fun `taking the only defender exposes the piece it guarded`() {
        val fact = RemoveDefenderDetector().detect(board("7k/5b2/8/3n4/8/8/8/3RKR2 w - - 0 1"))
            .filterIsInstance<RemoveDefenderFact>().single()

        assertEquals(Square.F1, fact.capturer)
        assertEquals(Square.F7, fact.defender)
        assertEquals(Square.D5, fact.target)
    }

    @Test
    fun `a square next to the king that no pawn can cover is weak`() {
        val fact = WeakSquareDetector().detect(board("r1b2rk1/ppp2ppp/8/8/8/5q2/PPP4P/R1BQ1RK1 w - - 0 1"))
            .filterIsInstance<WeakSquareFact>().single { it.owner == Side.WHITE }

        assertEquals(Square.F2, fact.square)
    }

    @Test
    fun `the start position has no weak squares and no breaks`() {
        val start = board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        assertTrue(WeakSquareDetector().detect(start).isEmpty())
        assertTrue(PawnBreakDetector().detect(start).isEmpty())
    }

    @Test
    fun `a safe pawn push that hits a pawn is a break`() {
        val fact = PawnBreakDetector().detect(board("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"))
            .filterIsInstance<PawnBreakFact>().single { it.owner == Side.WHITE }

        assertEquals(Square.D2, fact.from)
        assertEquals(Square.D4, fact.to)
        assertEquals(Square.E5, fact.target)
    }

    private fun kinds(fen: String): Set<EndgameKind> =
        EndgameDetector().detect(board(fen)).filterIsInstance<EndgameFact>().map { it.kind }.toSet()

    @Test
    fun `a rook behind a passed pawn is noticed`() {
        assertTrue(EndgameKind.ROOK_BEHIND_PASSED in kinds("8/8/8/3P4/8/8/3R4/4K2k w - - 0 1"))
    }

    @Test
    fun `the rule of the square counts whose move it is`() {
        assertTrue(EndgameKind.KING_OUTSIDE_SQUARE in kinds("6K1/8/8/8/P7/8/8/7k w - - 0 1"))
        // Король на e4 входит в квадрат пешки a4 своим ходом.
        assertTrue(EndgameKind.KING_OUTSIDE_SQUARE !in kinds("6K1/8/8/8/P3k3/8/8/8 b - - 0 1"))
    }

    @Test
    fun `a centralised king against a king in the corner is active`() {
        assertTrue(EndgameKind.ACTIVE_KING in kinds("8/8/8/3K4/8/8/8/k7 w - - 0 1"))
    }

    @Test
    fun `bishops of opposite colours are noticed`() {
        assertTrue(EndgameKind.OPPOSITE_BISHOPS in kinds("4k3/8/4b3/8/8/4B3/8/4K3 w - - 0 1"))
    }
}
