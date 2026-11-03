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

        /** Позиция, сыгранный ход и ход, который человек проверяет сам. */
        val USER_POSITIONS = listOf(
            Triple("r1b1k1nr/pppq2pp/2nb4/3pp1B1/8/2NBP3/PPPN1PPP/R2QK2R b KQkq - 0 9", "g8f6", "d4"),
            Triple("r3kb1r/ppp3pp/5p2/3Ppb2/2PNq3/8/PP2BPPP/R1BQ1RK1 b kq - 0 10", "e5d4", "Qxd4"),
            Triple("rn1qkb1r/pp2p1pp/2p2p2/3nNb2/2BPp3/2N1B3/PPP2PPP/R2QK2R w KQkq - 0 8", "d1h5", "Nxd5"),
            // Мат конём в один ход: должен стоять в "Шахи и маты", а не вилкой на пешку.
            Triple("r2qkb2/pp2p1r1/1np3b1/8/2BPN1p1/8/PPP1RPPP/6K1 w - - 0 21", "c4f7", "Nf6"),
        )
    }

    @Test
    fun `печатает разбор живых позиций`() {
        val binary = LocalStockfishClient.locate()
        assumeTrue("нет CHESSCOACH_STOCKFISH, пропускаю", binary != null)

        val fixtures = PuzzleFixtures.load()

        LocalStockfishClient(binary!!).use { engine ->
            val coach = CoachEngine(engine)
            runBlocking {
                // Позиция из настоящей партии: на ней отрабатывался формат текста.
                println("=".repeat(72))
                println(coach.explainAsText(REAL_GAME_FEN, REAL_GAME_MOVE))

                // Позиции, на которых пользователь поймал тренера на ошибках.
                USER_POSITIONS.forEach { (fen, move, candidate) ->
                    println()
                    println("=".repeat(72))
                    println("FEN: $fen, сыгран $move")
                    val started = System.currentTimeMillis()
                    println(coach.explainAsText(fen, move))
                    println("[разбор занял " + (System.currentTimeMillis() - started) + " мс]")
                    println()
                    println((coach.tryCandidate(fen, candidate) as? CandidateResult.Explained)?.text)
                }

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
