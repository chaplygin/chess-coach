package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningPrinciplesTest {

    /** После 1.e4 e5 2.Nf3 Nc6 3.Bc4 Nf6 — ход белых, конь уже стоит на f3. */
    private val italianFen = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

    @Test
    fun `a second move by the same piece in the opening is flagged`() {
        val caveat = OpeningPrinciples.caveat(italianFen, "f3g5")

        checkNotNull(caveat)
        assertEquals(Square.F3, caveat.from)
        // Дома остались ферзевый конь и ферзевый слон: королевские уже вышли.
        assertEquals(listOf(Square.B1, Square.C1).sorted(), caveat.undevelopedSquares.sorted())
        assertTrue(caveat.castlingPending)
    }

    @Test
    fun `the first move of a piece is not flagged`() {
        // Конь ещё дома на g1 — это его первый ход, правило не нарушено.
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        assertNull(OpeningPrinciples.caveat(start, "g1f3"))
    }

    @Test
    fun `pawn and king moves are never flagged`() {
        assertNull(OpeningPrinciples.caveat(italianFen, "d2d3"))
        assertNull(OpeningPrinciples.caveat(italianFen, "e1g1"))
    }

    @Test
    fun `outside the opening the rule does not apply`() {
        // Голый эндшпиль: развивать нечего, и напоминать про развитие незачем.
        val endgame = "8/5k2/8/4n3/8/8/5K2/8 b - - 0 40"

        assertNull(OpeningPrinciples.caveat(endgame, "e5d3"))
    }

    @Test
    fun `nothing left to develop and castling done means no caveat`() {
        // Все лёгкие фигуры вышли, права на рокировку нет — упрекать не в чем.
        val developed = "r2q1rk1/pppb1ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPPB1PPP/R2Q1RK1 w - - 8 9"

        assertNull(OpeningPrinciples.caveat(developed, "f3g5"))
    }
}
