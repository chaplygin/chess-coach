package org.chesscoach.core.vision

import kotlin.math.hypot
import kotlin.random.Random
import org.chesscoach.core.BoardLayout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Снимок настоящей доски отличается от скриншота не только перспективой.
 *
 * Три отличия можно измерить, не имея ни одной настоящей фотографии, и именно
 * они ломают наивное чтение клеток:
 *  - "белая" фигура на деревянном наборе не белая, а кремовая, и по яркости
 *    почти совпадает со светлым полем; "чёрная" — тёмно-коричневая;
 *  - свет неравномерен: один край доски заметно ярче другого;
 *  - блик от солнца или лампы выбивает кусок доски в белое.
 *
 * Что здесь НЕ проверяется и проверено быть не может: настоящие фигуры
 * объёмные, снятые под углом они загораживают соседние поля и выглядят не
 * силуэтом сбоку, а сверху. Цифры ниже — про свет и цвет, а не про то, что
 * приложение справится с фотографией уличной партии.
 */
class WoodenBoardTest {

    private val startingPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    private val middlegame = "r2q1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1"
    private val endgame = "8/5k2/4p3/8/2P5/8/5K2/8"

    private val positions = listOf(
        "стартовая позиция" to startingPosition,
        "миттельшпиль" to middlegame,
        "эндшпиль" to endgame,
    )

    @Test
    fun `деревянный набор при неровном свете и блике читается без выдумок`() {
        println("=== Снимок деревянной доски: измеренная точность ===")

        for ((label, damage) in damageVariants()) {
            var occupancyRight = 0
            var occupancyTotal = 0
            var colorRight = 0
            var typeRight = 0
            var pieces = 0

            for ((_, position) in positions) {
                val image = damage(woodenPalette(SyntheticBoard.render(position, boardPixels = 480)))
                val expected = BoardLayout.parsePlacement(position)
                val raw = readAllCells(image)
                if (raw == null) {
                    println("    $label: доска не нашлась вовсе")
                    occupancyTotal += 64
                    continue
                }

                for (index in 0 until 64) {
                    occupancyTotal++
                    val want = expected[index]
                    if ((want != null) == raw.occupied[index]) occupancyRight++
                    if (want == null) continue

                    pieces++
                    val got = raw.letters[index] ?: continue
                    if (got.isUpperCase() == want.isUpperCase()) colorRight++
                    if (got == want) typeRight++
                }
            }

            println(
                "    " + label + ": пусто/занято " + percent(occupancyRight, occupancyTotal) +
                    ", цвет " + percent(colorRight, pieces) +
                    ", фигура целиком " + percent(typeRight, pieces)
            )

            // Ограждение стоит только на том, что действительно обязано
            // работать при любом освещении: отличить занятую клетку от пустой.
            // Цвет и тип печатаются как измерено — подгонять ассерт под
            // полученное число значило бы проверять, что код равен самому себе.
            assertTrue(
                "$label: пусто/занято должно определяться почти безошибочно",
                occupancyRight.toDouble() / occupancyTotal > 0.95,
            )
        }
    }

    @Test
    fun `на кремовых фигурах абсолютный порог яркости дал бы неверный цвет`() {
        // Проверка именно того, ради чего цвет решается по двум скоплениям на
        // самом снимке: обе фигуры этого набора ярче середины шкалы, и правило
        // "ярче 127 — значит белая" покрасило бы чёрные фигуры в белые.
        val image = woodenPalette(SyntheticBoard.render(startingPosition, boardPixels = 480))
        val raw = checkNotNull(readAllCells(image)) { "доска обязана находиться на чистом снимке" }
        val expected = BoardLayout.parsePlacement(startingPosition)

        var right = 0
        var total = 0
        for (index in 0 until 64) {
            val want = expected[index] ?: continue
            val got = raw.letters[index] ?: continue
            total++
            if (got.isUpperCase() == want.isUpperCase()) right++
        }

        assertTrue("цвет фигур на деревянном наборе: $right из $total", right == total)
    }

    private fun percent(part: Int, total: Int): String =
        if (total == 0) "нет данных" else String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * part / total)

    private fun damageVariants(): List<Pair<String, (GrayImage) -> GrayImage>> = listOf(
        "ровный свет" to { image -> image },
        "неровный свет" to { image -> lightingGradient(image) },
        "блик" to { image -> glare(image) },
        "шум" to { image -> noise(image) },
        "всё вместе" to { image -> noise(glare(lightingGradient(image))) },
    )

    /**
     * Цвета обычного деревянного набора вместо экранных.
     *
     * Кремовая фигура намеренно почти совпадает по яркости со светлым полем, а
     * тёмная — с тёмным: на настоящей доске так и есть, и именно на этом
     * ломается чтение "фигура темнее или светлее середины шкалы".
     */
    private fun woodenPalette(image: GrayImage): GrayImage {
        val lightSquare = rgb(222, 196, 158)
        val darkSquare = rgb(140, 98, 62)
        val whitePiece = rgb(240, 228, 202)
        val blackPiece = rgb(58, 38, 24)

        val pixels = IntArray(image.pixels.size)
        for (i in image.pixels.indices) {
            val source = image.pixels[i]
            pixels[i] = when (nearestScreenColor(source)) {
                0 -> lightSquare
                1 -> darkSquare
                2 -> whitePiece
                else -> blackPiece
            }
        }
        return GrayImage(image.width, image.height, pixels)
    }

    /** Какой из четырёх экранных цветов ближе: светлое поле, тёмное поле, белая фигура, чёрная. */
    private fun nearestScreenColor(pixel: Int): Int {
        val screen = listOf(rgb(0xED, 0xD6, 0xB0), rgb(0xB5, 0x88, 0x63), rgb(255, 255, 255), rgb(0, 0, 0))
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for ((index, reference) in screen.withIndex()) {
            val distance = colorDistance(pixel, reference)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    private fun colorDistance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return (dr * dr + dg * dg + db * db).toDouble()
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    /** Один край доски ярче другого — обычное дело при съёмке у окна или на улице. */
    private fun lightingGradient(image: GrayImage): GrayImage = transform(image) { x, _, pixel ->
        scale(pixel, 0.62 + 0.55 * x / image.width)
    }

    /** Блик: яркое пятно, выбивающее часть доски почти в белое. */
    private fun glare(image: GrayImage): GrayImage {
        val centerX = image.width * 0.68
        val centerY = image.height * 0.3
        val radius = image.width * 0.22
        return transform(image) { x, y, pixel ->
            val distance = hypot(x - centerX, y - centerY)
            if (distance > radius) pixel else scale(pixel, 1.0 + 1.4 * (1.0 - distance / radius))
        }
    }

    private fun noise(image: GrayImage): GrayImage {
        // Зерно фиксировано: тест обязан быть воспроизводимым, иначе он
        // случайно краснеет и его начинают перезапускать вместо починки.
        val random = Random(20260917)
        return transform(image) { _, _, pixel ->
            val shift = random.nextInt(-7, 8)
            rgb(
                (((pixel shr 16) and 0xFF) + shift).coerceIn(0, 255),
                (((pixel shr 8) and 0xFF) + shift).coerceIn(0, 255),
                ((pixel and 0xFF) + shift).coerceIn(0, 255),
            )
        }
    }

    private fun scale(pixel: Int, factor: Double): Int = rgb(
        (((pixel shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255),
        (((pixel shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255),
        ((pixel and 0xFF) * factor).toInt().coerceIn(0, 255),
    )

    private fun transform(image: GrayImage, body: (Double, Double, Int) -> Int): GrayImage {
        val pixels = IntArray(image.pixels.size)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val index = y * image.width + x
                pixels[index] = body(x.toDouble(), y.toDouble(), image.pixels[index])
            }
        }
        return GrayImage(image.width, image.height, pixels)
    }
}
