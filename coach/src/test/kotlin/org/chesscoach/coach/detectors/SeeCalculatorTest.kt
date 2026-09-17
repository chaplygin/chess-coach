package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeeCalculatorTest {

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }

    @Test
    fun `undefended pawn is a clean win for the attacker`() {
        val board = board("4k3/8/8/8/8/4p3/8/4R2K w - - 0 1")

        assertEquals(SeeCalculator.PAWN_VALUE, SeeCalculator.evaluateSquare(board, Square.E3, Side.WHITE))
    }

    @Test
    fun `capturing a defended pawn with a rook is not worth it`() {
        // Ладья бьёт пешку, но пешку тут же отбивает вторая ладья: 100 за пешку минус 500 за свою — невыгодно.
        val board = board("r3k3/8/8/p7/R7/8/8/4K3 w - - 0 1")

        assertEquals(0, SeeCalculator.evaluateSquare(board, Square.A5, Side.WHITE))
    }

    @Test
    fun `quiet position has nothing to win on an empty square`() {
        val board = Board() // стартовая позиция

        assertEquals(0, SeeCalculator.evaluateSquare(board, Square.E4, Side.WHITE))
        assertEquals(0, SeeCalculator.evaluateSquare(board, Square.E5, Side.BLACK))
    }

    @Test
    fun `an absolutely pinned defender is excluded from the exchange`() {
        // Чёрная ладья a5 связана по вертикали "a" ладьёй белых на a1 к своему королю на a8.
        // Она стоит на одной горизонтали с пешкой b5 и геометрически "защищает" её,
        // но взять на b5 не может — это раскрыло бы короля. Конь должен выиграть пешку чисто.
        val board = board("k7/8/8/rp6/3N4/8/8/R3K3 w - - 0 1")

        assertEquals(SeeCalculator.PAWN_VALUE, SeeCalculator.evaluateSquare(board, Square.B5, Side.WHITE))
    }

    @Test
    fun `without the pin the same defender would save the pawn`() {
        // Контрольный вариант той же позиции без связывающей ладьи на a1:
        // теперь чёрная ладья свободна и честно отбивает коня.
        val board = board("k7/8/8/rp6/3N4/8/8/4K3 w - - 0 1")

        assertEquals(0, SeeCalculator.evaluateSquare(board, Square.B5, Side.WHITE))
    }

    @Test
    fun `evaluateMove rejects a move the position does not allow`() {
        val board = board("4k3/8/8/8/8/4p3/8/4R2K w - - 0 1")
        val illegal = Move(Square.H1, Square.E3) // король так не ходит

        assertNull(SeeCalculator.evaluateMove(board, illegal))
    }

    @Test
    fun `evaluateMove matches evaluateSquare for the same capture`() {
        val board = board("4k3/8/8/8/8/4p3/8/4R2K w - - 0 1")
        val capture = Move(Square.E1, Square.E3)

        assertEquals(SeeCalculator.PAWN_VALUE, SeeCalculator.evaluateMove(board, capture))
    }
}
