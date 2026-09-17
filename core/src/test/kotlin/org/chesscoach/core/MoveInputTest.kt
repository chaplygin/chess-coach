package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveInputTest {

    private val start = START_FEN

    private fun uciOf(fen: String, text: String): String {
        val result = MoveInput.parse(fen, text)
        assertTrue(text + " -> " + result, result is MoveInputResult.Parsed)
        return (result as MoveInputResult.Parsed).uci
    }

    @Test
    fun `san notation is accepted`() {
        assertEquals("g1f3", uciOf(start, "Nf3"))
        assertEquals("e2e4", uciOf(start, "e4"))
    }

    @Test
    fun `engine notation is accepted too`() {
        assertEquals("g1f3", uciOf(start, "g1f3"))
    }

    @Test
    fun `spaces and check marks around the move do not matter`() {
        assertEquals("e2e4", uciOf(start, "  e4 "))
        assertEquals("g1f3", uciOf(start, "Nf3!?"))
    }

    @Test
    fun `lower case is forgiven when only one move fits`() {
        assertEquals("g1f3", uciOf(start, "nf3"))
    }

    @Test
    fun `a move that is legal elsewhere but not here is rejected`() {
        // Ход коня с f3 законен во множестве позиций, но не в начальной.
        assertEquals(MoveInputResult.Unknown, MoveInput.parse(start, "Ng5"))
    }

    @Test
    fun `nonsense is rejected rather than guessed`() {
        assertEquals(MoveInputResult.Unknown, MoveInput.parse(start, "привет"))
        assertEquals(MoveInputResult.Unknown, MoveInput.parse(start, ""))
    }

    @Test
    fun `case decides between a pawn capture and a bishop capture, and the exact spelling wins`() {
        // Белая пешка b3 и белый слон f1 оба могут взять на c4.
        val fen = "4k3/8/8/8/2p5/1P6/8/4KB2 w - - 0 1"

        assertEquals("b3c4", uciOf(fen, "bxc4"))
        assertEquals("f1c4", uciOf(fen, "Bxc4"))
    }

    @Test
    fun `the same spelling in the wrong case is reported as ambiguous, not as a missing move`() {
        val fen = "4k3/8/8/8/2p5/1P6/8/4KB2 w - - 0 1"

        // "BXC4" не совпадает точно ни с bxc4, ни с Bxc4 — и подходит к обоим.
        // Ответ "такого хода нет" был бы неправдой: ходов как раз два.
        assertEquals(MoveInputResult.Ambiguous, MoveInput.parse(fen, "BXC4"))
    }

    @Test
    fun `castling is accepted with letters and with zeroes`() {
        val fen = "4k3/8/8/8/8/8/8/4K2R w K - 0 1"

        assertEquals("e1g1", uciOf(fen, "O-O"))
        assertEquals("e1g1", uciOf(fen, "0-0"))
    }
}
