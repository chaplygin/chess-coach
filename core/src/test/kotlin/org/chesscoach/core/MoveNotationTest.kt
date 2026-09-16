package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

class MoveNotationTest {

    @Test
    fun `translates a variation into chess notation`() {
        val san = MoveNotation.uciToSan(START_FEN, listOf("e2e4", "e7e5", "g1f3", "b8c6"))
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), san)
    }

    @Test
    fun `handles castling`() {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals(listOf("O-O"), MoveNotation.uciToSan(fen, listOf("e1g1")))
        assertEquals(listOf("O-O-O"), MoveNotation.uciToSan(fen, listOf("e1c1")))
    }

    @Test
    fun `handles promotion`() {
        val fen = "8/P7/8/7k/8/8/8/K7 w - - 0 1"
        assertEquals(listOf("a8=Q"), MoveNotation.uciToSan(fen, listOf("a7a8q")))
    }

    @Test
    fun `disambiguates between two identical pieces`() {
        // Оба коня бьют на b3, и запись обязана указать, какой именно пошёл.
        // Точную форму (Nab3 или N1b3) выбирает chesslib — проверяем сам факт
        // уточнения, а не конкретную букву.
        val fen = "8/8/8/3k4/8/8/8/N1N4K w - - 0 1"
        val san = MoveNotation.uciToSan(fen, listOf("a1b3")).single()

        assertTrue("ожидалось уточнение в записи, получено: $san", san.length > 3)
        assertTrue(san.startsWith("N"))
        assertTrue(san.endsWith("b3"))
    }

    @Test
    fun `falls back to engine notation instead of crashing`() {
        val nonsense = MoveNotation.uciToSanOrRaw(START_FEN, listOf("z9z9"))
        assertEquals(listOf("z9z9"), nonsense)
    }

    @Test
    fun `reads side to move and move number from fen`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 3 3"
        assertEquals(Side.BLACK, MoveNotation.sideToMove(fen))
        assertEquals(3, MoveNotation.fullMoveNumber(fen))
        assertEquals(Side.WHITE, MoveNotation.sideToMove(START_FEN))
    }
}

class ScoreFormatTest {

    @Test
    fun `converts engine score to white perspective`() {
        // Движок считает от лица того, чей ход. Плюс у чёрных означает минус у белых.
        assertEquals(Score.Cp(50), ScoreFormat.toWhitePerspective(Score.Cp(50), Side.WHITE))
        assertEquals(Score.Cp(-50), ScoreFormat.toWhitePerspective(Score.Cp(50), Side.BLACK))
        assertEquals(Score.Mate(-3), ScoreFormat.toWhitePerspective(Score.Mate(3), Side.BLACK))
    }

    @Test
    fun `formats scores the way a human reads them`() {
        assertEquals("+0.85", ScoreFormat.display(Score.Cp(85)))
        assertEquals("-1.20", ScoreFormat.display(Score.Cp(-120)))
        assertEquals("+0.00", ScoreFormat.display(Score.Cp(0)))
        assertEquals("#3", ScoreFormat.display(Score.Mate(3)))
        assertEquals("#-2", ScoreFormat.display(Score.Mate(-2)))
    }

    @Test
    fun `prints the same score whatever the phone locale is`() {
        // На русской локали разделитель дробной части — запятая, и оценка
        // печаталась бы как «+0,85». Сверять её пользователь будет с разбором
        // на lichess.org, где всегда точка.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"))
            assertEquals("+0.85", ScoreFormat.display(Score.Cp(85)))
            assertEquals("-1.20", ScoreFormat.display(Score.Cp(-120)))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `measures how much a move gave away`() {
        assertEquals(70, ScoreFormat.centipawnLoss(Score.Cp(50), Score.Cp(-20)))
        assertEquals(0, ScoreFormat.centipawnLoss(Score.Cp(50), Score.Cp(50)))
        // Ход лучше «лучшего» невозможен, но шум глубины не должен давать минус.
        assertEquals(0, ScoreFormat.centipawnLoss(Score.Cp(50), Score.Cp(60)))
    }

    @Test
    fun `treats a missed mate as a large but finite loss`() {
        // Бесконечность сломала бы любую арифметику порогов классификации.
        val loss = ScoreFormat.centipawnLoss(Score.Mate(2), Score.Cp(300))
        assertTrue("упущенный мат должен стоить дорого, получено $loss", loss > 5000)
        assertEquals(MoveClassification.BLUNDER, MoveClassification.of(loss, missedForcedMate = true))
    }
}
