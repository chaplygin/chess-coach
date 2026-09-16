package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UciParserTest {

    @Test
    fun `reads depth score and principal variation`() {
        val line = "info depth 20 seldepth 27 multipv 1 score cp 28 nodes 1234567 " +
            "nps 1500000 hashfull 120 tbhits 0 time 823 pv e2e4 e7e5 g1f3 b8c6"

        val parsed = UciParser.parseInfo(line)!!

        assertEquals(20, parsed.depth)
        assertEquals(1, parsed.multiPvIndex)
        assertEquals(Score.Cp(28), parsed.score)
        assertEquals(listOf("e2e4", "e7e5", "g1f3", "b8c6"), parsed.pv)
    }

    @Test
    fun `reads forced mate score`() {
        val line = "info depth 15 multipv 1 score mate 3 nodes 900 time 10 pv d1h5 g7g6 h5h6"
        assertEquals(Score.Mate(3), UciParser.parseInfo(line)!!.score)
    }

    @Test
    fun `reads negative mate as being mated`() {
        val line = "info depth 12 multipv 1 score mate -2 nodes 900 time 10 pv a1a2 b8b1"
        assertEquals(Score.Mate(-2), UciParser.parseInfo(line)!!.score)
    }

    @Test
    fun `ignores bounded scores`() {
        // lowerbound и upperbound — это границы окна поиска, а не оценка позиции.
        // Показать их пользователю значило бы соврать.
        val lower = "info depth 18 multipv 1 score cp 45 lowerbound nodes 100 time 5 pv e2e4"
        val upper = "info depth 18 multipv 1 score cp 45 upperbound nodes 100 time 5 pv e2e4"

        assertNull(UciParser.parseInfo(lower))
        assertNull(UciParser.parseInfo(upper))
    }

    @Test
    fun `ignores service lines without a variation`() {
        assertNull(UciParser.parseInfo("info depth 5 currmove e2e4 currmovenumber 1"))
        assertNull(UciParser.parseInfo("info string NNUE evaluation using nn-1234.nnue"))
        assertNull(UciParser.parseInfo("readyok"))
    }

    @Test
    fun `reads best move and recognises its absence`() {
        assertEquals("e2e4", UciParser.parseBestMove("bestmove e2e4 ponder e7e5"))
        assertEquals("e2e4", UciParser.parseBestMove("bestmove e2e4"))
        // Движок отвечает bestmove даже когда ходов нет — мат или пат.
        assertNull(UciParser.parseBestMove("bestmove (none)"))
        assertNull(UciParser.parseBestMove("info depth 1 pv e2e4"))
    }
}

class MultiPvCollectorTest {

    @Test
    fun `keeps the deepest result for each variation`() {
        // Движок присылает один и тот же вариант много раз по мере углубления.
        val lines = listOf(
            "info depth 4 multipv 1 score cp 10 pv e2e4",
            "info depth 4 multipv 2 score cp 5 pv d2d4",
            "info depth 20 multipv 1 score cp 28 pv e2e4 e7e5 g1f3",
            "info depth 20 multipv 2 score cp 20 pv d2d4 d7d5",
        )

        val result = MultiPvCollector().acceptAll(lines).result()

        assertEquals(2, result.size)
        assertEquals(listOf(1, 2), result.map { it.multiPvIndex })
        assertTrue(result.all { it.depth == 20 })
        assertEquals(Score.Cp(28), result[0].score)
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), result[0].pv)
    }

    @Test
    fun `survives a stream with noise`() {
        val result = MultiPvCollector().acceptAll(
            listOf(
                "info string starting search",
                "info depth 3 currmove e2e4 currmovenumber 1",
                "info depth 10 multipv 1 score cp 15 upperbound pv e2e4",
                "info depth 10 multipv 1 score cp 12 pv e2e4 e7e5",
                "bestmove e2e4",
            )
        ).result()

        assertEquals(1, result.size)
        assertEquals(Score.Cp(12), result[0].score)
    }
}
