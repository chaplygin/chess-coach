package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт "Шахи и маты" перебирает ходы соперника так, будто сейчас его очередь.
 * Когда своему королю шах, среди таких ходов оказывалось взятие короля, и
 * разбор любого ухода от шаха падал с "length=64; index=64".
 */
class MovesUnderCheckTest {

    @Test
    fun `no opponent checks or mates are counted while the side to move is in check`() {
        // Партия против SmaccyD, 46. Rc4+: чёрный король g4 под шахом.
        val endgame = "8/1p6/5p2/8/2R3k1/2P3P1/p4K1P/1q6 b - - 1 46"
        assertTrue(SafeChecks.of(endgame, Side.WHITE).isEmpty())
        assertTrue(MatingMoves.of(endgame, Side.WHITE).isEmpty())

        // 3. Bb5+ c6: ответ на шах в дебюте.
        val opening = "rnbqkbnr/ppp2ppp/3p4/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 3"
        assertTrue(SafeChecks.of(opening, Side.WHITE).isEmpty())
    }
}
