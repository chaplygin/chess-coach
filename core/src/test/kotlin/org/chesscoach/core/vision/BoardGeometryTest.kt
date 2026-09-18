package org.chesscoach.core.vision

import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замер геометрии доски на сценах с известным ответом.
 *
 * Ничего здесь не подгоняется под красивый результат: настоящие цифры по
 * каждому углу наклона и по каждой помехе печатаются в вывод, а ассерты стоят
 * круглыми порогами заметно ниже измеренного — они ограждают от деградации, а
 * не описывают достигнутое. Там, где метод разваливается, ассерта нет, а есть
 * строчка в выводе и строчка в отчёте.
 *
 * Две метрики считаются разным кодом намеренно. Ошибка по углам сравнивает
 * найденные углы с проекцией трёхмерных углов доски и не пересекается с
 * BoardGeometry ни в одной строчке. Доля клеток пользуется тем же
 * преобразованием, что и само выпрямление, — она отвечает на вопрос "куда
 * попал центр клетки в готовой картинке", и другого честного способа его
 * задать нет.
 */
class BoardGeometryTest {

    private val startingPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    private val board = SyntheticBoard.render(startingPosition, 480)

    private class Measurement(
        val found: Boolean,
        val confidence: Double,
        val meanCornerError: Double,
        val maxCornerError: Double,
        val rotation: Int,
        val cellAccuracy: Double,
        val parityAccuracy: Double,
        val millis: Long,
    )

    @Test
    fun `меряем точность по углам наклона`() {
        warmUp()
        println("=== Наклон доски: ошибка положения углов и доля верных клеток ===")
        printHeader()

        val results = LinkedHashMap<Int, Measurement>()
        for (tilt in listOf(0, 5, 10, 20, 30, 40, 50)) {
            val scene = PerspectiveScene.render(
                board = board,
                tiltDegrees = tilt.toDouble(),
                axisDegrees = 20.0,
                spinDegrees = 4.0,
                offsetX = 0.05,
                offsetY = -0.03,
            )
            results[tilt] = measure("наклон " + tilt + " град", scene)
        }

        // Ограждения ставятся только там, где метод работает. Угол, на котором
        // он разваливается, остаётся в выводе как факт, а не как падающий тест
        // про недостижимое.
        for (tilt in listOf(0, 5, 10, 20, 30)) {
            val result = results.getValue(tilt)
            assertTrue("доска должна находиться при наклоне " + tilt, result.found)
            assertTrue(
                "ошибка углов при наклоне " + tilt + " выросла до " + round(result.meanCornerError),
                result.meanCornerError < 8.0,
            )
            assertTrue(
                "доля верных клеток при наклоне " + tilt + " упала до " + round(result.cellAccuracy),
                result.cellAccuracy >= 0.95,
            )
        }
    }

    @Test
    fun `меряем влияние помех`() {
        warmUp()
        println("=== Помехи при наклоне 20 градусов ===")
        printHeader()

        val cases = listOf(
            "без помех" to PerspectiveScene.render(board = board, tiltDegrees = 20.0, spinDegrees = 4.0),
            "градиент яркости 0.5" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0, illumination = 0.5,
            ),
            "градиент яркости 0.9" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0, illumination = 0.9,
            ),
            "блик 0.5" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0, glare = 0.5,
            ),
            "блик 0.9" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0, glare = 0.9,
            ),
            "шум 12" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0, noise = 12.0,
            ),
            "шум 30" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0, noise = 30.0,
            ),
            "всё сразу" to PerspectiveScene.render(
                board = board, tiltDegrees = 20.0, spinDegrees = 4.0,
                illumination = 0.6, glare = 0.5, noise = 14.0,
            ),
        )

        val results = cases.associate { (name, scene) -> name to measure(name, scene) }

        for (name in listOf("без помех", "градиент яркости 0.5", "блик 0.5", "шум 12")) {
            val result = results.getValue(name)
            assertTrue("помеха '" + name + "' должна оставаться проходимой", result.found)
            assertTrue(
                "помеха '" + name + "': ошибка углов " + round(result.meanCornerError),
                result.meanCornerError < 8.0,
            )
            assertTrue(
                "помеха '" + name + "': доля клеток " + round(result.cellAccuracy),
                result.cellAccuracy >= 0.95,
            )
        }
    }

    @Test
    fun `доска далеко и маленькая`() {
        warmUp()
        println("=== Доска занимает малую часть кадра (улица) ===")
        printHeader()

        val results = listOf(0.62, 0.45, 0.30, 0.20).associateWith { scale ->
            measure(
                "доля кадра " + scale,
                PerspectiveScene.render(
                    board = board,
                    tiltDegrees = 25.0,
                    axisDegrees = 25.0,
                    spinDegrees = 6.0,
                    boardScale = scale,
                    illumination = 0.4,
                ),
            )
        }

        val large = results.getValue(0.45)
        assertTrue("доска на 45% кадра должна находиться", large.found)
        assertTrue("ошибка углов " + round(large.meanCornerError), large.meanCornerError < 8.0)
    }

    @Test
    fun `на картинке без доски ответа нет`() {
        warmUp()
        println("=== Ложные срабатывания ===")

        val negatives = linkedMapOf(
            "однотонный фон" to solid(800, 600, 0x7A6A58),
            "равномерный шум" to noiseImage(800, 600, 1),
            "шум другой затравки" to noiseImage(800, 600, 77),
            "одна прямая линия" to singleLine(800, 600),
            "рамка стола без доски" to PerspectiveScene.render(
                board = solid(480, 480, 0x5A5148),
                tiltDegrees = 15.0,
                spinDegrees = 4.0,
            ).image,
            "полосатый плед" to stripes(800, 600, 26),
            "клетчатая скатерть в мелкую клетку" to grid(800, 600, 17),
        )

        for ((name, image) in negatives) {
            val result = BoardGeometry.locateBoard(image)
            val verdict = if (result == null) "null" else "НАШЛОСЬ, уверенность " + round(result.confidence)
            println(String.format(Locale.US, "%-38s %s", name, verdict))
        }

        for ((name, image) in negatives) {
            if (name == "клетчатая скатерть в мелкую клетку") continue
            assertNull("ложное срабатывание на '" + name + "'", BoardGeometry.locateBoard(image))
        }
    }

    @Test
    fun `скриншот без наклона выпрямляется без потерь`() {
        val scene = PerspectiveScene.render(
            board = board,
            frameWidth = 520,
            frameHeight = 520,
            tiltDegrees = 0.0,
            spinDegrees = 0.0,
            boardScale = 0.9,
            clutter = false,
        )
        val located = BoardGeometry.locateBoard(scene.image)
        assertNotNull("ровный скриншот обязан находиться", located)
        val measurement = evaluate(scene, located!!, 0)
        println(
            String.format(
                Locale.US,
                "скриншот без наклона: углы %.2f px, клетки %.0f%%, уверенность %.2f",
                measurement.meanCornerError,
                measurement.cellAccuracy * 100,
                measurement.confidence,
            )
        )
        assertTrue(measurement.meanCornerError < 5.0)
        assertTrue(measurement.cellAccuracy >= 0.95)
    }

    // -----------------------------------------------------------------------
    // Замер и печать.
    // -----------------------------------------------------------------------

    private fun printHeader() {
        println(
            String.format(
                Locale.US,
                "%-26s %-7s %-6s %-9s %-9s %-8s %-8s %s",
                "сцена", "нашли", "увер.", "углы ср.", "углы макс", "клетки", "чётность", "мс",
            )
        )
    }

    private fun measure(name: String, scene: PerspectiveScene.Scene): Measurement {
        val start = System.nanoTime()
        val located = BoardGeometry.locateBoard(scene.image)
        val millis = (System.nanoTime() - start) / 1_000_000

        val measurement = if (located == null) {
            Measurement(false, 0.0, Double.NaN, Double.NaN, -1, 0.0, 0.0, millis)
        } else {
            evaluate(scene, located, millis)
        }

        println(
            String.format(
                Locale.US,
                "%-26s %-7s %-6s %-9s %-9s %-8s %-8s %d",
                name,
                if (measurement.found) "да" else "НЕТ",
                if (measurement.found) round(measurement.confidence) else "-",
                if (measurement.found) round(measurement.meanCornerError) else "-",
                if (measurement.found) round(measurement.maxCornerError) else "-",
                if (measurement.found) percent(measurement.cellAccuracy) else "-",
                if (measurement.found) percent(measurement.parityAccuracy) else "-",
                millis,
            )
        )
        if (measurement.found && measurement.rotation != 0) {
            println("    внимание: доска найдена повёрнутой на " + (measurement.rotation * 90) + " градусов")
        }
        return measurement
    }

    private fun evaluate(scene: PerspectiveScene.Scene, located: WarpedBoard, millis: Long): Measurement {
        // Сдвиг перебирается, чтобы отличить "углы найдены с ошибкой" от
        // "доска найдена правильно, но повёрнута": это разные беды, и вторая
        // видна по доле клеток, а не по ошибке углов.
        var bestShift = 0
        var bestMean = Double.MAX_VALUE
        var bestMax = Double.MAX_VALUE
        for (shift in 0 until 4) {
            var sum = 0.0
            var worst = 0.0
            for (index in 0 until 4) {
                val expected = scene.corners[index]
                val actual = located.quad.corners[(index + shift) % 4]
                val error = sqrt(
                    (expected.x - actual.x) * (expected.x - actual.x) +
                        (expected.y - actual.y) * (expected.y - actual.y)
                )
                sum += error
                worst = maxOf(worst, error)
            }
            if (sum / 4 < bestMean) {
                bestMean = sum / 4
                bestMax = worst
                bestShift = shift
            }
        }

        val size = located.image.width
        val mapping = boardMapping(located.quad, size)
        var correctCells = 0
        if (mapping != null) {
            for (row in 0 until 8) {
                for (column in 0 until 8) {
                    val point = scene.boardPoint((column + 0.5) / 8, (row + 0.5) / 8)
                    val inBoard = mapping.toBoard(point.x, point.y)
                    val gotColumn = (inBoard.x / (size / 8.0)).toInt()
                    val gotRow = (inBoard.y / (size / 8.0)).toInt()
                    if (gotColumn == column && gotRow == row) correctCells++
                }
            }
        }

        return Measurement(
            found = true,
            confidence = located.confidence,
            meanCornerError = bestMean,
            maxCornerError = bestMax,
            rotation = bestShift,
            cellAccuracy = correctCells / 64.0,
            parityAccuracy = parityAccuracy(located.image),
            millis = millis,
        )
    }

    /**
     * Независимая от преобразования проверка: цвет клетки в выпрямленной доске
     * должен совпасть с тем, какой она была на исходной картинке. Сдвиг на
     * клетку или поворот на 90 градусов меняют чётность во всех клетках сразу,
     * и эта доля падает до нуля — то есть метрика ловит именно то, чего ошибка
     * по углам в одиночку не видит.
     */
    private fun parityAccuracy(warped: GrayImage): Double {
        val cellSize = warped.width / 8.0
        val values = Array(8) { DoubleArray(8) }
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                var sum = 0.0
                var count = 0
                for (offsetY in listOf(0.18, 0.82)) {
                    for (offsetX in listOf(0.18, 0.82)) {
                        val x = ((column + offsetX) * cellSize).toInt().coerceIn(0, warped.width - 1)
                        val y = ((row + offsetY) * cellSize).toInt().coerceIn(0, warped.height - 1)
                        val pixel = warped.pixels[y * warped.width + x]
                        sum += (((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)) / 3.0
                        count++
                    }
                }
                values[row][column] = sum / count
            }
        }
        val average = values.sumOf { row -> row.sum() } / 64
        var correct = 0
        for (row in 0 until 8) {
            for (column in 0 until 8) {
                // В SyntheticBoard клетка (0,0) светлая, дальше по чётности.
                val expectedLight = (row + column) % 2 == 0
                if ((values[row][column] > average) == expectedLight) correct++
            }
        }
        return correct / 64.0
    }

    private fun warmUp() {
        val scene = PerspectiveScene.render(board = board, tiltDegrees = 10.0)
        repeat(2) { BoardGeometry.locateBoard(scene.image) }
    }

    private fun round(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100)

    // -----------------------------------------------------------------------
    // Картинки без доски.
    // -----------------------------------------------------------------------

    private fun solid(width: Int, height: Int, color: Int): GrayImage =
        GrayImage(width, height, IntArray(width * height) { color })

    private fun noiseImage(width: Int, height: Int, seed: Int): GrayImage {
        val random = Random(seed)
        return GrayImage(width, height, IntArray(width * height) {
            val value = random.nextInt(256)
            (value shl 16) or (value shl 8) or value
        })
    }

    private fun singleLine(width: Int, height: Int): GrayImage {
        val pixels = IntArray(width * height) { 0x6A5F52 }
        for (y in height / 3 - 3..height / 3 + 3) {
            for (x in 0 until width) pixels[y * width + x] = 0xD8D0C4
        }
        return GrayImage(width, height, pixels)
    }

    /** Полосы: много параллельных прямых, но второй группы нет. */
    private fun stripes(width: Int, height: Int, period: Int): GrayImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val light = ((x + y / 2) / period) % 2 == 0
                pixels[y * width + x] = if (light) 0xD2C6AE else 0x6E6154
            }
        }
        return GrayImage(width, height, pixels)
    }

    /** Настоящая клетчатая ткань: сетка есть, но клеток заметно больше восьми. */
    private fun grid(width: Int, height: Int, period: Int): GrayImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val light = ((x / period) + (y / period)) % 2 == 0
                pixels[y * width + x] = if (light) 0xCFC4B0 else 0x706455
            }
        }
        return GrayImage(width, height, pixels)
    }
}

/** Точка картинки доски (доли от 0 до 1) в координатах кадра. */
private fun PerspectiveScene.Scene.boardPoint(u: Double, v: Double): Point {
    // Билинейная интерполяция по углам здесь была бы неверна — перспектива не
    // билинейна. Поэтому точка считается тем же способом, что и углы: через
    // проекцию, восстановленную по четырём углам как дробно-линейное
    // отображение квадрата. Коэффициенты считаются здесь же, независимо от
    // BoardGeometry.
    val projection = squareToQuad(corners)
    val denominator = projection[6] * u + projection[7] * v + 1.0
    return Point(
        (projection[0] * u + projection[1] * v + projection[2]) / denominator,
        (projection[3] * u + projection[4] * v + projection[5]) / denominator,
    )
}

/**
 * Отображение единичного квадрата в четырёхугольник по классическим формулам
 * Хекберта: три вспомогательные разности и два коэффициента перспективы, без
 * решения системы восемь на восемь.
 */
private fun squareToQuad(corners: List<Point>): DoubleArray {
    val x0 = corners[0].x
    val y0 = corners[0].y
    val x1 = corners[1].x
    val y1 = corners[1].y
    val x2 = corners[2].x
    val y2 = corners[2].y
    val x3 = corners[3].x
    val y3 = corners[3].y

    val sumX = x0 - x1 + x2 - x3
    val sumY = y0 - y1 + y2 - y3
    if (sumX == 0.0 && sumY == 0.0) {
        return doubleArrayOf(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0.0, 0.0)
    }
    val dx1 = x1 - x2
    val dx2 = x3 - x2
    val dy1 = y1 - y2
    val dy2 = y3 - y2
    val determinant = dx1 * dy2 - dx2 * dy1
    val g = (sumX * dy2 - dx2 * sumY) / determinant
    val h = (dx1 * sumY - sumX * dy1) / determinant
    return doubleArrayOf(
        x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
        y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
        g, h,
    )
}
