package org.chesscoach.core.vision

import java.io.File
import javax.imageio.ImageIO
import org.chesscoach.core.BoardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Настоящий скриншот с телефона — единственный тест распознавания, который
 * ничего не рисует сам.
 *
 * Все остальные тесты зрения синтетические, и это их общая слабость: они
 * проверяют код на картинке, нарисованной этим же проектом, по тем же
 * предположениям. Ровно на этом распознавание и сломалось у человека —
 * синтетическая доска всегда занимала кадр целиком, поэтому грубая сетка
 * поиска случайно попадала в неё точно, а на скриншоте телефона доска лежит
 * посреди экрана, и промах в десяток пикселей срезал фигурам головы.
 *
 * Снимок приложен пользователем: задача Lichess в мобильном приложении,
 * доска показана со стороны чёрных (снизу чёрные), вокруг доски обычная
 * обвязка приложения.
 */
class RealScreenshotTest {

    /** Расстановка так, как её видит человек: сверху ряд 1, слева вертикаль h. */
    private val asDisplayed = "2B1R3/1P1K4/P3RPP1/1Q1P1P1P/2q2p2/2p4p/1p4p1/3r1rbk"

    /** Та же позиция в обычной записи: сверху ряд 8, слева вертикаль a. */
    private val truePlacement = "kbr1r3/1p4p1/p4p2/2p2q2/P1P1P1Q1/1PPR3P/4K1P1/3R1B2"

    private fun screenshot(): GrayImage {
        val file = File("src/test/resources/vision/lichess-screenshot.png")
        val image = ImageIO.read(file)
        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                pixels[y * image.width + x] = image.getRGB(x, y) and 0xFFFFFF
            }
        }
        return GrayImage(image.width, image.height, pixels)
    }

    @Test
    fun `скриншот телефона читается по клеткам`() {
        val raw = readAllCells(screenshot(), PhotoSource.SCREEN)
        assertNotNull("доска на скриншоте обязана находиться", raw)

        val expected = BoardLayout.parsePlacement(asDisplayed)
        var right = 0
        var occupancyRight = 0
        println("=== Настоящий скриншот: что прочитано ===")
        for (row in 0 until 8) {
            val got = StringBuilder("    ")
            val want = StringBuilder("        ")
            for (col in 0 until 8) {
                val index = row * 8 + col
                got.append(raw!!.letters[index] ?: if (raw.occupied[index]) '?' else '.')
                want.append(expected[index] ?: '.')
                if (raw.letters[index] == expected[index]) right++
                if (raw.occupied[index] == (expected[index] != null)) occupancyRight++
            }
            println(got.toString() + "   эталон " + want.toString().trim())
        }
        println("    клеток верно $right из 64, пусто/занято верно $occupancyRight из 64")
        val weak = (0 until 64).filter { raw!!.occupied[it] && raw.confidence[it] < 0.35 }
        println("    слабые клетки: " + weak.joinToString(", ") {
            (expected[it] ?: '?').toString() + String.format(java.util.Locale.ROOT, " %.2f", raw!!.confidence[it])
        })

        assertEquals("все 64 клетки скриншота обязаны читаться верно", 64, right)
    }

    @Test
    fun `позиция со скриншота разворачивается в правильную сторону`() {
        val result = BoardRecognizer.recognize(screenshot(), whiteToMove = false, source = PhotoSource.SCREEN)
        val recognized = result as? RecognitionResult.Recognized
        assertNotNull("ожидали разобранную позицию, получили $result", recognized)
        assertEquals(
            "доска снята со стороны чёрных и должна быть развёрнута",
            "$truePlacement b - - 0 1",
            recognized!!.fen,
        )
    }
}
