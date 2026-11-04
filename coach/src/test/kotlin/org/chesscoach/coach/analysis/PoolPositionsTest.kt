package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.detectors.HangingDetector
import org.chesscoach.coach.engine.NullMoveUtil
import org.chesscoach.coach.model.AllowedMateReason
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.text.MoveEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Позиции из пула 0.2.50: замечания по скриншотам и проверка тренера на
 * партиях bufreg. FEN — позиция до разбираемого хода, сверены с записью партий.
 */
class PoolPositionsTest {

    @Test
    fun `bishop f4 is the only guard of h2 against Qxh2 mate and g3 blocks it`() {
        // BunkerMate, 14. g3?? Qxh2#.
        val guards = MateGuards.find(BUNKER_MATE, Side.BLACK)
        assertTrue(guards.toString(), guards.any { it.defender == Square.F4 && it.target == Square.H2 && it.mateSan == "Qxh2#" })
        val allowed = MateGuards.allowed(BUNKER_MATE, "g2g3")
        assertNotNull(allowed)
        assertEquals(AllowedMateReason.BLOCKS_DEFENDER, allowed!!.reason)
        assertEquals(Square.F4, allowed.defender)
        assertEquals("Qxh2#", allowed.mateSan)
    }

    @Test
    fun `a move that leaves the mating square names the guard it took away`() {
        // Srihari23, 22. Qg4?? Re1#: ферзь d1 держал e1.
        val queen = MateGuards.allowed(SRIHARI, "d1g4")!!
        assertEquals(AllowedMateReason.MOVES_DEFENDER, queen.reason)
        assertEquals(Square.D1, queen.defender)
        assertEquals("Re1#", queen.mateSan)
        // Jerzy_SNU, 20. Ke1?? Qxg1#: король f1 держал g1.
        val king = MateGuards.allowed(JERZY_20, "f1e1")!!
        assertEquals(AllowedMateReason.MOVES_DEFENDER, king.reason)
        assertEquals(PieceType.KING, king.defenderPiece)
        assertEquals("Qxg1#", king.mateSan)
        // nonamex435, 12... Nxa1?? Qxh7#: угроза стояла и до хода.
        val ignored = MateGuards.allowed(NONAMEX, "c2a1")!!
        assertEquals(AllowedMateReason.IGNORES_THREAT, ignored.reason)
        assertEquals("Qxh7#", ignored.mateSan)
    }

    @Test
    fun `rook e5 is the only guard against Qe8 mate`() {
        // iliaabbasiii, 26... Rg5?? Qe8#.
        val guards = MateGuards.find(ILIA, Side.WHITE)
        assertTrue(guards.toString(), guards.any { it.defender == Square.E5 && it.mateSan == "Qe8#" })
        val allowed = MateGuards.allowed(ILIA, "e5g5")!!
        assertEquals(AllowedMateReason.MOVES_DEFENDER, allowed.reason)
        assertEquals("Qe8#", allowed.mateSan)
    }

    @Test
    fun `no false hanging pieces`() {
        // Jerzy_SNU, 20. Ke1: линию g закрывает свой конь g3, ладья g8 коня g1 не бьёт.
        val jerzy = HangingDetector().detect(Board().apply { loadFromFen(JERZY_20) }).filterIsInstance<HangingPieceFact>()
        assertTrue(jerzy.toString(), jerzy.none { it.square == Square.G1 && it.firstAttacker == Square.G8 })
        // mikklev_84, 17... Bd7: c6 защищена b7, но dxc6 даёт вскрытый шах — так и пишем.
        val mikklev = HangingDetector().detect(Board().apply { loadFromFen(MIKKLEV) }).filterIsInstance<HangingPieceFact>()
        mikklev.filter { it.square == Square.C6 }.forEach { assertTrue(it.toString(), it.capturedWithCheck) }
    }

    @Test
    fun `capture names the blow that comes with it`() {
        // DelphicKnight, 26. Rd7+: Bxc2 не только берёт пешку, но и нападает на ладью d1.
        val flipped = NullMoveUtil.flipSideToMove(DELPHIC_26)!!
        val blows = MoveEffects.attacks(flipped, "f5c2")
        assertTrue(blows.toString(), blows.any { it.contains("ладью d1") })
    }

    @Test
    fun `human move is explained by what it does on the board`() {
        // Там же после 26... Bxd7 27. Nxb7 Bf5: Bb3 защищает пешку c2 от слона f5.
        val fen = listOf("d1d7", "f5d7", "d8b7", "d7f5").fold(DELPHIC_26) { current, uci -> applyUciMove(current, uci)!! }
        val phrases = MoveEffects.phrases(fen, "c4b3")
        assertTrue(phrases.toString(), phrases.any { it == "защищает пешку c2 от слона f5" })
    }

    private companion object {
        const val DELPHIC_26 = "r2N4/pp5k/2p4p/5bp1/2B2p2/7P/P1P2PP1/3R2K1 w - - 0 26"
        const val BUNKER_MATE = "r4rk1/ppp2p1p/3pb3/3N2p1/3QPBnq/3B4/PPP2PPP/R4RK1 w - - 0 14"
        const val JERZY_20 = "4R1r1/2p2Q1p/p1kb4/3p4/8/2N3n1/PPPP1P2/R1B2KNq w - - 0 20"
        const val SRIHARI = "5rk1/p5p1/2p1r2p/8/1P1P4/8/PP4PP/R1BQ2K1 w - - 0 22"
        const val NONAMEX = "r2q1rk1/ppp1bppp/4b3/7Q/3nB3/8/PPnP1PPP/RNBK2NR b - - 1 12"
        const val MIKKLEV = "r1bk3r/pp4pp/2p5/3P1p2/2BR1Bn1/2N5/PPP2bPP/5R1K b - - 1 17"
        const val ILIA = "Q7/pppk1pNp/3b4/4r3/2P5/1P5P/P4PP1/3qn1K1 b - - 2 26"
    }
}
