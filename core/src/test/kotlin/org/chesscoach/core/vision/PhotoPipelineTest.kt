package org.chesscoach.core.vision

import org.chesscoach.core.BoardLayout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Весь путь целиком: кадр -> поиск доски -> выпрямление -> чтение клеток -> FEN.
 *
 * Отдельные части измеряются своими тестами, но человеку важен только итог:
 * получил он свою позицию или чужую. Здесь и меряется итог, по обоим режимам
 * съёмки.
 *
 * Чего этот тест не доказывает: фигуры здесь плоские силуэты, нарисованные
 * внутри своих клеток. На фотографии уличной доски фигуры объёмные, стоят
 * выше клетки и загораживают соседние поля — этого не воспроизводит ни одна
 * синтетика, и цифры ниже на такой случай не распространяются.
 */
class PhotoPipelineTest {

    private val positions = listOf(
        "стартовая" to "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "миттельшпиль" to "r2q1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1",
        "эндшпиль" to "8/5k2/4p3/8/2P5/8/5K2/8",
    )

    @Test
    fun `скриншот разбирается точно`() {
        for ((label, placement) in positions) {
            val image = SyntheticBoard.render(placement, boardPixels = 480)
            val result = BoardRecognizer.recognize(image, whiteToMove = true, source = PhotoSource.SCREEN)
            val recognized = result as? RecognitionResult.Recognized
            assertTrue("$label: ожидали Recognized, получили $result", recognized != null)
            assertTrue(
                "$label: расстановка прочитана неверно: " + recognized!!.fen,
                recognized.fen.startsWith("$placement w "),
            )
        }
    }

    @Test
    fun `доска под углом — сколько клеток доходит до FEN`() {
        println("=== Весь путь на доске, снятой под углом ===")

        for (tilt in listOf(0.0, 10.0, 20.0, 30.0, 40.0)) {
            var right = 0
            var total = 0
            var lost = 0

            for ((_, placement) in positions) {
                val scene = PerspectiveScene.render(
                    board = SyntheticBoard.render(placement, boardPixels = 480),
                    frameWidth = 800,
                    frameHeight = 600,
                    tiltDegrees = tilt,
                    spinDegrees = 12.0,
                    boardScale = 0.62,
                    clutter = true,
                )
                val raw = readAllCells(scene.image, PhotoSource.BOARD)
                if (raw == null) {
                    lost++
                    continue
                }

                val expected = BoardLayout.parsePlacement(placement)
                for (index in 0 until 64) {
                    total++
                    if (raw.letters[index] == expected[index]) right++
                }
            }

            val share = if (total == 0) 0.0 else right.toDouble() / total
            println(
                "    наклон " + tilt.toInt() + " град: клеток верно " +
                    String.format(java.util.Locale.ROOT, "%.1f%%", share * 100) +
                    ", досок не найдено " + lost + " из " + positions.size
            )

            // Ограждение только на том, что доска вообще находится: доля верных
            // клеток печатается как измерено. Подогнать её порогом значило бы
            // превратить измерение в самоподтверждение.
            assertTrue("при наклоне $tilt доска обязана находиться", lost == 0)
        }
    }
}
