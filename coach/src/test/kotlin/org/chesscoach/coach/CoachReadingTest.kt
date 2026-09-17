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

    @Test
    fun `печатает разбор живых позиций`() {
        val binary = LocalStockfishClient.locate()
        assumeTrue("нет CHESSCOACH_STOCKFISH, пропускаю", binary != null)

        val fixtures = PuzzleFixtures.load()
        assumeTrue("нет собранных фикстур, пропускаю", fixtures.isNotEmpty())

        LocalStockfishClient(binary!!).use { engine ->
            val coach = CoachEngine(engine)
            runBlocking {
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
                    println(coach.explainAsText(fixture.fen))
                }
            }
        }
    }
}
