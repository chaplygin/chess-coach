package org.chesscoach.core.vision

import java.io.File
import javax.imageio.ImageIO
import org.chesscoach.core.BoardLayout
import org.junit.Test

/**
 * Измерение признаков настоящего набора фигур — отсюда взяты числа таблицы
 * PROTOTYPES.
 *
 * Тест ничего не утверждает, он печатает. Смысл в том, чтобы эталоны мерились
 * ровно тем же кодом, который потом сравнивает с ними клетки: как только
 * поменяется обрезка клетки, порог или набор признаков, вывод этого теста
 * разойдётся с таблицей, и это будет видно. Прошлая версия эталонов считалась
 * отдельным скриптом с другими порогами — и распознавание молча испортилось,
 * не уронив ни одного теста.
 */
class RealSetMeasurementTest {

    private val asDisplayed = "2B1R3/1P1K4/P3RPP1/1Q1P1P1P/2q2p2/2p4p/1p4p1/3r1rbk"

    private fun screenshot(): GrayImage {
        val image = ImageIO.read(File("src/test/resources/vision/lichess-screenshot.png"))
        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                pixels[y * image.width + x] = image.getRGB(x, y) and 0xFFFFFF
            }
        }
        return GrayImage(image.width, image.height, pixels)
    }

    @Test
    fun `признаки настоящего набора Lichess`() {
        val signatures = cellSignatures(screenshot())
        val expected = BoardLayout.parsePlacement(asDisplayed)
        println("=== признаки по клеткам настоящего скриншота ===")
        println("     фигура  height width   top    mid    bot    symm   fill")
        for (index in 0 until 64) {
            val piece = expected[index] ?: continue
            val s = signatures[index]
            if (s == null) {
                println("    $piece  силуэт не посчитан")
                continue
            }
            println(
                "    " + piece + "  " + s.joinToString("  ") {
                    String.format(java.util.Locale.ROOT, "%.3f", it)
                }
            )
        }
    }
}
