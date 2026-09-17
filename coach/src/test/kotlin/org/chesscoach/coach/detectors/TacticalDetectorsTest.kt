package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalDetectorsTest {

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }
    private fun startingPosition() = Board()

    // ——— HangingDetector ———

    @Test
    fun `hanging detector finds an undefended pawn`() {
        val facts = HangingDetector().detect(board("4k3/8/8/8/8/4p3/8/4R2K w - - 0 1"))

        val fact = facts.filterIsInstance<HangingPieceFact>().single()
        assertEquals(Square.E3, fact.square)
        assertEquals(SeeCalculator.PAWN_VALUE, fact.lossCentipawns)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `hanging detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), HangingDetector().detect(startingPosition()))
    }

    // ——— ForkDetector ———

    @Test
    fun `fork detector finds a knight forking two rooks`() {
        val facts = ForkDetector().detect(board("6k1/r3r3/2N5/8/8/8/8/6K1 w - - 0 1"))

        val fact = facts.filterIsInstance<ForkFact>().single()
        assertEquals(Square.C6, fact.from)
        assertEquals(setOf(Square.A7, Square.E7), fact.targets.toSet())
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `fork detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), ForkDetector().detect(startingPosition()))
    }

    // ——— PinSkewerDetector ———

    @Test
    fun `pin skewer detector finds an absolute pin on the king`() {
        val facts = PinSkewerDetector().detect(board("6k1/8/4n3/8/2B5/8/8/K7 w - - 0 1"))

        val fact = facts.filterIsInstance<PinFact>().single()
        assertEquals(Square.E6, fact.pinned)
        assertEquals(Square.C4, fact.pinner)
        assertEquals(Square.G8, fact.behind)
        assertTrue(fact.absolute)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `pin skewer detector finds a skewer through a queen onto a rook`() {
        val facts = PinSkewerDetector().detect(board("r6k/8/8/q7/8/8/8/R6K w - - 0 1"))

        val fact = facts.filterIsInstance<SkewerFact>().single()
        assertEquals(Square.A5, fact.front)
        assertEquals(Square.A8, fact.behind)
        assertEquals(Square.A1, fact.attacker)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `pin skewer detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), PinSkewerDetector().detect(startingPosition()))
    }

    // ——— DiscoveredAttackDetector ———

    @Test
    fun `discovered attack detector finds a knight uncovering a rook`() {
        val facts = DiscoveredAttackDetector().detect(board("r6k/8/8/8/N7/8/8/R6K w - - 0 1"))

        val fact = facts.filterIsInstance<DiscoveredAttackFact>().single()
        assertEquals(Square.A4, fact.moving)
        assertEquals(Square.A1, fact.revealed)
        assertEquals(Square.A8, fact.target)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `discovered attack detector does not fire on a pawn that cannot leave the file`() {
        // Стартовая позиция: пешка a2 стоит между ладьёй a1 и пешкой a7, но
        // прямой ход пешки её с вертикали не уводит — вскрывать нечего.
        assertEquals(emptyList<Any>(), DiscoveredAttackDetector().detect(startingPosition()))
    }

    // ——— BackRankDetector ———

    @Test
    fun `back rank detector finds a rook already on the back rank`() {
        val facts = BackRankDetector().detect(board("k7/8/8/8/8/8/5PPP/r5K1 w - - 0 1"))

        val fact = facts.filterIsInstance<BackRankFact>().single()
        assertEquals(Square.G1, fact.king)
        assertEquals(setOf(Square.F2, Square.G2, Square.H2), fact.blockedBy.toSet())
        assertEquals(Square.A1, fact.exploiter)
        assertEquals(Side.BLACK, fact.favours)
    }

    @Test
    fun `back rank detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), BackRankDetector().detect(startingPosition()))
    }

    // ——— OverloadDetector ———

    @Test
    fun `overload detector finds a rook defending two attacked pawns alone`() {
        val facts = OverloadDetector().detect(board("7k/p3r3/8/8/4p3/8/8/R3R2K w - - 0 1"))

        val fact = facts.filterIsInstance<OverloadedDefenderFact>().single()
        assertEquals(Square.E7, fact.defender)
        assertEquals(setOf(Square.A7, Square.E4), fact.duties.toSet())
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `overload detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), OverloadDetector().detect(startingPosition()))
    }

    // ——— TrappedPieceDetector ———

    @Test
    fun `trapped piece detector finds a cornered knight with no safe square`() {
        val facts = TrappedPieceDetector().detect(board("n6k/8/8/3N4/8/8/8/7K w - - 0 1"))

        val fact = facts.filterIsInstance<TrappedPieceFact>().single()
        assertEquals(Square.A8, fact.square)
        assertEquals(SeeCalculator.KNIGHT_VALUE, fact.lossCentipawns)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `trapped piece detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), TrappedPieceDetector().detect(startingPosition()))
    }
}
