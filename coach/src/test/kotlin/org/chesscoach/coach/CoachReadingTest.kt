package org.chesscoach.coach

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Прогон всего разбора на настоящем движке и настоящих позициях.
 *
 * Это не проверка утверждений, а чтение вслух: печатает то, что увидит
 * пользователь. Разбор может быть безупречен по структуре и при этом
 * читаться как протокол — заметить такое можно только глазами.
 *
 * Без переменной окружения CHESSCOACH_STOCKFISH тест пропускается, поэтому в
 * общей сборке он не участвует и ничего не замедляет.
 */
class CoachReadingTest {

    private companion object {
        const val REAL_GAME_FEN = "r1bqk2r/ppp2ppp/2n2n2/b2pp3/3PPP2/P1N5/1PP1N1PP/R1BQKB1R w KQkq - 1 7"
        const val REAL_GAME_MOVE = "c1e3"
    }

    @Test
    fun `печатает разбор живых позиций`() {
        val binary = LocalStockfishClient.locate()
        assumeTrue("нет CHESSCOACH_STOCKFISH, пропускаю", binary != null)

        val fixtures = PuzzleFixtures.load()
        assumeTrue("нет собранных фикстур, пропускаю", fixtures.isNotEmpty())

        LocalStockfishClient(binary!!).use { engine ->
            val coach = CoachEngine(engine)
            runBlocking {
                // Позиция из настоящей партии: на ней отрабатывался формат текста.
                println("=".repeat(72))
                println(coach.explainAsText(REAL_GAME_FEN, REAL_GAME_MOVE))

                // По одной задаче на тему: смотреть шесть однотипных вилок
                // бесполезно, а рентген и последняя горизонталь звучат иначе.
                fixtures.distinctBy { it.theme }.forEach { fixture ->
                    println()
                    println("=".repeat(72))
                    println("Задача " + fixture.id + ", тема " + fixture.theme +
                        ", рейтинг " + fixture.rating)
                    println("FEN: " + fixture.fen)
                    println("Решение Lichess: " + fixture.solution.joinToString(" "))
                    println("-".repeat(72))
                    // Сыгранным ходом подставляем решение задачи: без него
                    // раздел про сыгранный ход не появляется, а это главное,
                    // ради чего разбор и читают.
                    println(coach.explainAsText(fixture.fen, fixture.solution.firstOrNull()))

                    // И тот же разбор с заведомо другим ходом: так видно, как
                    // звучит разговор об ошибке, а не о попадании в лучший.
                    val other = otherLegalMove(fixture.fen, fixture.solution.firstOrNull())
                    if (other != null) {
                        println()
                        println("--- тот же разбор, но сыгран " + other + " ---")
                        println(coach.explainAsText(fixture.fen, other))
                    }
                }
            }
        }
    }

    /** Любой легальный ход, кроме [avoid] — чтобы увидеть разговор об ошибке. */
    private fun otherLegalMove(fen: String, avoid: String?): String? {
        val board = com.github.bhlangonijr.chesslib.Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return null
        return board.legalMoves()
            .map { it.toString() }
            .firstOrNull { it != avoid }
    }
}
