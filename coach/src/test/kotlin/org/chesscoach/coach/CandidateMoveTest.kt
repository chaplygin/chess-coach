package org.chesscoach.coach

import kotlinx.coroutines.test.runTest
import org.chesscoach.coach.engine.FakeEngineClient
import org.chesscoach.core.EngineLine
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Свой вариант хода: человек набирает ход и спрашивает, чем он хуже лучшего.
 *
 * Движок заморожен — проверяется не сила анализа, а то, что разбор своего
 * варианта идёт тем же путём, что и разбор сыгранного хода, и что на плохой
 * ввод тренер отвечает по делу, а не общим "не получилось".
 */
class CandidateMoveTest {

    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val afterNf3 = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"

    private fun engine() = FakeEngineClient.ofOrEmpty(
        mapOf(
            start to listOf(EngineLine(1, Score.Cp(30), listOf("e2e4"), 18)),
            afterE4 to listOf(EngineLine(1, Score.Cp(-25), listOf("e7e5"), 16)),
            afterNf3 to listOf(EngineLine(1, Score.Cp(-10), listOf("d7d5"), 16)),
        ),
    )

    @Test
    fun `a move that cannot be played here is named as such, not analysed`() = runTest {
        val result = CoachEngine(engine()).tryCandidate(start, "Ng5")

        assertEquals(CandidateResult.NotLegal, result)
    }

    @Test
    fun `nonsense is rejected`() = runTest {
        assertEquals(CandidateResult.NotLegal, CoachEngine(engine()).tryCandidate(start, "тут бы конём"))
    }

    @Test
    fun `a legal candidate is compared with the best move`() = runTest {
        val result = CoachEngine(engine()).tryCandidate(start, "Nf3")

        assertTrue(result.toString(), result is CandidateResult.Explained)
        val explained = result as CandidateResult.Explained
        assertEquals("g1f3", explained.uci)
        assertTrue(explained.text, explained.text.startsWith("ВАШ ВАРИАНТ: Nf3"))
        // Именно сравнение с лучшим — ради него весь разбор и затевается.
        assertTrue(explained.text, explained.text.contains("Лучше e4"))
    }

    @Test
    fun `a candidate that is the best move is told so plainly`() = runTest {
        val result = CoachEngine(engine()).tryCandidate(start, "e4")

        val explained = result as CandidateResult.Explained
        assertTrue(explained.text, explained.text.contains("Это и есть лучший ход."))
        // Про "лучше было" тут говорить нечего — лучше уже некуда.
        assertTrue(explained.text, !explained.text.contains("Лучше "))
    }

    @Test
    fun `engine notation works as well as chess notation`() = runTest {
        val result = CoachEngine(engine()).tryCandidate(start, "g1f3") as CandidateResult.Explained

        assertEquals("g1f3", result.uci)
    }
}
