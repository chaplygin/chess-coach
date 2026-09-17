package org.chesscoach.coach.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatePoolTest {

    private val fourVariants: List<(Unit) -> String> = listOf({ "A" }, { "B" }, { "C" }, { "D" })

    @Test
    fun `same FEN gives the same sequence of choices every time`() {
        val templates = listOf<(Unit) -> String>({ "вариант A" }, { "вариант B" }, { "вариант C" })
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"

        val poolOne = TemplatePool(fen)
        val poolTwo = TemplatePool(fen)

        val picksOne = (1..6).map { poolOne.choose(Unit, templates) }
        val picksTwo = (1..6).map { poolTwo.choose(Unit, templates) }

        assertEquals(picksOne, picksTwo)
    }

    @Test
    fun `all four variants get used before any one of them repeats`() {
        val pool = TemplatePool("irrelevant-fen")

        val firstFour = (1..4).map { pool.choose(Unit, fourVariants) }

        assertEquals(setOf("A", "B", "C", "D"), firstFour.toSet())
    }

    @Test
    fun `with four variants and a memory of three, the fifth pick returns to the first`() {
        val pool = TemplatePool("irrelevant-fen")

        val picks = (1..5).map { pool.choose(Unit, fourVariants) }

        assertEquals(picks[0], picks[4])
    }

    @Test
    fun `a single template is returned even though it was just used`() {
        val templates = listOf<(Unit) -> String>({ "единственный вариант" })
        val pool = TemplatePool("irrelevant-fen")

        repeat(3) { assertEquals("единственный вариант", pool.choose(Unit, templates)) }
    }
}
