package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.ExchangeFact
import org.chesscoach.coach.model.HangingPieceFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeAndDiscoveredTest {

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }

    // Белый слон c4 бьёт чёрного коня d5, того защищает пешка e6.
    private val bishopVsKnight = "4k3/8/4p3/3n4/2B5/1P6/8/4K3 w - - 0 1"

    @Test
    fun `near equal trade is not hanging`() {
        val hanging = HangingDetector().detect(board(bishopVsKnight)).filterIsInstance<HangingPieceFact>()
        assertTrue(hanging.none { it.square == Square.D5 || it.square == Square.C4 })
    }

    @Test
    fun `near equal trade is reported with signed delta for whoever captures`() {
        val facts = ExchangeDetector().detect(board(bishopVsKnight)).filterIsInstance<ExchangeFact>()

        val bishopTakes = facts.single { it.square == Square.D5 }
        assertEquals(Square.C4, bishopTakes.attacker)
        assertEquals(-10, bishopTakes.deltaCentipawns)

        val knightTakes = ExchangeDetector().detect(board("4k3/8/8/8/2B5/1P2n3/8/K7 w - - 0 1"))
            .filterIsInstance<ExchangeFact>().single { it.square == Square.C4 }
        assertEquals(Square.E3, knightTakes.attacker)
        assertEquals(10, knightTakes.deltaCentipawns)
    }

    @Test
    fun `clear win is not an exchange`() {
        val facts = ExchangeDetector().detect(board("4k3/8/8/8/8/4p3/8/4R2K w - - 0 1"))
        assertEquals(emptyList<Any>(), facts)
    }

    @Test
    fun `discovered attack on a pawn protected by a pawn is not reported`() {
        // Ферзь d1, свой слон d2 уходит, цель d3 прикрыта пешкой c4: бить её ферзём невыгодно.
        val facts = DiscoveredAttackDetector().detect(board("4k3/8/8/8/2p5/3p4/3B4/3QK3 w - - 0 1"))
        assertTrue(facts.filterIsInstance<DiscoveredAttackFact>().none { it.target == Square.D3 })
    }

    @Test
    fun `discovered attack on an undefended pawn is reported`() {
        val facts = DiscoveredAttackDetector().detect(board("4k3/8/8/8/8/3p4/3B4/3QK3 w - - 0 1"))
        assertTrue(facts.filterIsInstance<DiscoveredAttackFact>().any { it.target == Square.D3 })
    }

    @Test
    fun `discovered check counts even without material`() {
        val facts = DiscoveredAttackDetector().detect(board("3k4/8/8/8/8/8/3B4/3RK3 w - - 0 1"))
        assertTrue(facts.filterIsInstance<DiscoveredAttackFact>().any { it.target == Square.D8 })
    }
}
