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

    @Test
    fun `a piece defended by a non-king piece is not hanging just because its side is in check`() {
        // Как в партии против Jerzy_SNU после Qc3+: конь e7 защищён слоном d6,
        // пешка c7 за королём c6 тоже. Взять обратно под шахом нельзя, но
        // сначала чёрные ответят на шах, и защита никуда не денется.
        val board = com.github.bhlangonijr.chesslib.Board().apply { loadFromFen("8/2p1n3/2kb4/8/8/2Q5/5PPP/4R1K1 b - - 0 1") }
        val hanging = org.chesscoach.coach.detectors.HangingDetector().detect(board)
            .filterIsInstance<org.chesscoach.coach.model.HangingPieceFact>()
        assertTrue(hanging.none { it.square == com.github.bhlangonijr.chesslib.Square.E7 })
        val skewers = org.chesscoach.coach.detectors.PinSkewerDetector().detect(board)
            .filterIsInstance<org.chesscoach.coach.model.SkewerFact>()
        assertTrue(skewers.isEmpty())
    }

    @Test
    fun `an attacked piece still hangs when its side is in check`() {
        // Партия против Jerzy_SNU, 13. Re1+: слон g4 под ферзём d1, защиты нет.
        val board = com.github.bhlangonijr.chesslib.Board().apply { loadFromFen(JERZY) }
        val hanging = org.chesscoach.coach.detectors.HangingDetector().detect(board)
            .filterIsInstance<org.chesscoach.coach.model.HangingPieceFact>()
        assertTrue(hanging.any { it.square == com.github.bhlangonijr.chesslib.Square.G4 })
    }

    @Test
    fun `mate in two is found behind the check`() {
        // Там же: Bh2 и Qxg1# — конь g1 связан, поле e2 держит слон g4.
        val found = MateIdeas.find(JERZY)
        assertTrue(found.toString(), found?.prepSan == "Bh2" && found.mateSan == "Qxg1#")
        // После Be6 Qf3 слон ушёл с g4, поле e2 свободно — мата нет.
        val after = listOf("g4e6", "d1f3").fold(JERZY) { fen, uci -> applyUciMove(fen, uci)!! }
        assertTrue(!MateIdeas.stillWorks(after, found!!))
    }

    private companion object {
        /** Восстановлено по скриншоту, ход чёрных после 13. Re1+. */
        const val JERZY = "r3k1nr/2p2ppp/p2b4/3p4/6b1/8/PPPP1PP1/RNBQRKNq b kq - 0 13"
    }
}
