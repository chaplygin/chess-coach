package org.chesscoach.core.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Генератор сцены с доской под известным углом.
 *
 * Зачем свой генератор, а не готовые фотографии: чтобы мерить ошибку, нужно
 * знать правильный ответ до пикселя. На настоящей фотографии углы доски
 * пришлось бы размечать руками, и точность замера была бы хуже точности того,
 * что замеряется.
 *
 * Сцена строится честной камерой-обскурой, а не подобранным на глаз
 * четырёхугольником: доска — это квадрат в трёхмерном пространстве, его
 * наклоняют вокруг оси, лежащей в плоскости доски, и проецируют на плоскость
 * снимка. Углы доски в кадре получаются проекцией её углов, то есть эталон
 * считается независимо от того кода, который эти углы потом ищет.
 *
 * Рисуется сцена трассировкой луча: для каждого пикселя решается, куда он
 * попадает на плоскости доски. Это медленнее матричного преобразования, зато
 * не использует ни строчки из BoardGeometry — иначе ошибка в общем коде
 * скрывала бы сама себя.
 */
object PerspectiveScene {

    /** [corners] — углы доски в кадре в порядке углов картинки доски: левый верхний, правый верхний, правый нижний, левый нижний. */
    class Scene(val image: GrayImage, val corners: List<Point>)

    /**
     * @param tiltDegrees наклон доски относительно плоскости снимка.
     * @param axisDegrees направление оси наклона в плоскости доски (0 — наклон
     *   вокруг горизонтали, то есть верх доски уходит вдаль).
     * @param spinDegrees поворот доски в её собственной плоскости — снимают её
     *   редко строго по горизонту.
     * @param boardScale доля меньшей стороны кадра, которую заняла бы
     *   ненаклонённая доска.
     * @param illumination амплитуда линейного градиента яркости по кадру.
     * @param glare яркость блика (доля от полной шкалы) — пятно света на доске.
     * @param noise амплитуда равномерного шума на канал.
     */
    fun render(
        board: GrayImage,
        frameWidth: Int = 800,
        frameHeight: Int = 600,
        tiltDegrees: Double = 0.0,
        axisDegrees: Double = 0.0,
        spinDegrees: Double = 0.0,
        boardScale: Double = 0.62,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        background: Int = 0x4A4038,
        clutter: Boolean = true,
        illumination: Double = 0.0,
        illuminationAngle: Double = 35.0,
        glare: Double = 0.0,
        glareAt: Pair<Double, Double> = Pair(0.35, 0.35),
        noise: Double = 0.0,
        seed: Long = 20260917L,
    ): Scene {
        val pixels = IntArray(frameWidth * frameHeight)
        fillBackground(pixels, frameWidth, frameHeight, background, clutter)

        val focal = frameWidth.toDouble()
        val centerX = frameWidth / 2.0
        val centerY = frameHeight / 2.0
        val minSide = min(frameWidth, frameHeight).toDouble()
        // Дистанция подбирается так, чтобы ненаклонённая доска занимала
        // заданную долю кадра: иначе при каждом изменении наклона менялся бы
        // ещё и масштаб, и было бы непонятно, что именно повлияло на ошибку.
        val distance = focal / (boardScale * minSide)

        val tilt = tiltDegrees * PI / 180
        val axis = doubleArrayOf(cos(axisDegrees * PI / 180), sin(axisDegrees * PI / 180), 0.0)
        val spin = spinDegrees * PI / 180
        val axisU = rotate(axis, tilt, doubleArrayOf(cos(spin), sin(spin), 0.0))
        val axisV = rotate(axis, tilt, doubleArrayOf(-sin(spin), cos(spin), 0.0))
        val center = doubleArrayOf(offsetX, offsetY, distance)

        val corners = listOf(
            Pair(-0.5, -0.5),
            Pair(0.5, -0.5),
            Pair(0.5, 0.5),
            Pair(-0.5, 0.5),
        ).map { (a, b) ->
            project(center, axisU, axisV, a, b, focal, centerX, centerY)
        }

        // Трассировка: луч из камеры через пиксель пересекается с плоскостью
        // доски, и координаты пересечения в системе доски дают точку в её
        // картинке. По 2x2 подвыборки на пиксель — настоящая камера тоже
        // усредняет по площади пикселя, и без этого края клеток были бы
        // неестественно рваными.
        val samples = listOf(0.25, 0.75)
        for (y in 0 until frameHeight) {
            for (x in 0 until frameWidth) {
                var red = 0
                var green = 0
                var blue = 0
                var hits = 0
                for (sy in samples) {
                    for (sx in samples) {
                        val hit = traceBoard(
                            x + sx - centerX, y + sy - centerY, focal,
                            center, axisU, axisV, board,
                        ) ?: continue
                        red += (hit shr 16) and 0xFF
                        green += (hit shr 8) and 0xFF
                        blue += hit and 0xFF
                        hits++
                    }
                }
                if (hits == 0) continue
                val index = y * frameWidth + x
                val backdrop = pixels[index]
                // Пиксель на самом краю доски частично занят фоном — смешиваем,
                // иначе граница доски выглядит вырезанной ножницами и края
                // получаются контрастнее, чем бывает на фотографии.
                val weight = hits / 4.0
                val mixedRed = ((red / hits) * weight + ((backdrop shr 16) and 0xFF) * (1 - weight)).toInt()
                val mixedGreen = ((green / hits) * weight + ((backdrop shr 8) and 0xFF) * (1 - weight)).toInt()
                val mixedBlue = ((blue / hits) * weight + (backdrop and 0xFF) * (1 - weight)).toInt()
                pixels[index] = (mixedRed shl 16) or (mixedGreen shl 8) or mixedBlue
            }
        }

        applyLighting(pixels, frameWidth, frameHeight, illumination, illuminationAngle, glare, glareAt, noise, seed)
        return Scene(GrayImage(frameWidth, frameHeight, pixels), corners)
    }

    /** Поворот вектора вокруг единичной оси по формуле Родрига. */
    private fun rotate(axis: DoubleArray, angle: Double, vector: DoubleArray): DoubleArray {
        val c = cos(angle)
        val s = sin(angle)
        val cross = doubleArrayOf(
            axis[1] * vector[2] - axis[2] * vector[1],
            axis[2] * vector[0] - axis[0] * vector[2],
            axis[0] * vector[1] - axis[1] * vector[0],
        )
        val dot = axis[0] * vector[0] + axis[1] * vector[1] + axis[2] * vector[2]
        return DoubleArray(3) { i -> vector[i] * c + cross[i] * s + axis[i] * dot * (1 - c) }
    }

    private fun project(
        center: DoubleArray,
        axisU: DoubleArray,
        axisV: DoubleArray,
        a: Double,
        b: Double,
        focal: Double,
        centerX: Double,
        centerY: Double,
    ): Point {
        val px = center[0] + a * axisU[0] + b * axisV[0]
        val py = center[1] + a * axisU[1] + b * axisV[1]
        val pz = center[2] + a * axisU[2] + b * axisV[2]
        return Point(focal * px / pz + centerX, focal * py / pz + centerY)
    }

    /** Точка картинки доски, видимая в данном направлении, или null, если луч прошёл мимо. */
    private fun traceBoard(
        dirX: Double,
        dirY: Double,
        focal: Double,
        center: DoubleArray,
        axisU: DoubleArray,
        axisV: DoubleArray,
        board: GrayImage,
    ): Int? {
        // Система: t * направление - a * U - b * V = центр доски.
        val m = arrayOf(
            doubleArrayOf(dirX, -axisU[0], -axisV[0]),
            doubleArrayOf(dirY, -axisU[1], -axisV[1]),
            doubleArrayOf(focal, -axisU[2], -axisV[2]),
        )
        val determinant = determinant3(m)
        if (determinant == 0.0) return null
        val a = determinant3(replaceColumn(m, 1, center)) / determinant
        if (a < -0.5 || a > 0.5) return null
        val b = determinant3(replaceColumn(m, 2, center)) / determinant
        if (b < -0.5 || b > 0.5) return null
        val t = determinant3(replaceColumn(m, 0, center)) / determinant
        if (t <= 0) return null

        val u = ((a + 0.5) * board.width).toInt().coerceIn(0, board.width - 1)
        val v = ((b + 0.5) * board.height).toInt().coerceIn(0, board.height - 1)
        return board.pixels[v * board.width + u]
    }

    private fun replaceColumn(m: Array<DoubleArray>, column: Int, values: DoubleArray): Array<DoubleArray> =
        Array(3) { row -> DoubleArray(3) { c -> if (c == column) values[row] else m[row][c] } }

    private fun determinant3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    /**
     * Фон: ровный стол и на нём посторонние прямые — край стола и тетрадь
     * рядом. Без них проверка была бы нечестной: на настоящем снимке прямых
     * линий всегда больше, чем у доски, и весь вопрос в том, сумеет ли метод
     * отличить сетку от просто прямых.
     */
    private fun fillBackground(pixels: IntArray, width: Int, height: Int, background: Int, clutter: Boolean) {
        pixels.fill(background)
        if (!clutter) return

        val edge = 0x8C7B66
        drawThickLine(pixels, width, height, 24.0, 18.0, (width - 20).toDouble(), 34.0, 3, edge)
        drawThickLine(pixels, width, height, 18.0, 24.0, 30.0, (height - 16).toDouble(), 3, edge)
        drawThickLine(pixels, width, height, 12.0, (height - 40).toDouble(), (width - 8).toDouble(), (height - 22).toDouble(), 3, edge)
        drawThickLine(pixels, width, height, (width - 46).toDouble(), 10.0, (width - 26).toDouble(), (height - 8).toDouble(), 3, edge)
        // Длинная диагональ через весь кадр — самая соблазнительная для Хафа
        // прямая из всех, что бывают на столе.
        drawThickLine(pixels, width, height, 0.0, (height * 0.92), width.toDouble(), (height * 0.55), 2, 0x6E6155)
    }

    private fun drawThickLine(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        thickness: Int,
        color: Int,
    ) {
        val steps = (max(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)) * 2).toInt() + 1
        for (step in 0..steps) {
            val t = step.toDouble() / steps
            val x = (x0 + (x1 - x0) * t).toInt()
            val y = (y0 + (y1 - y0) * t).toInt()
            for (dy in -thickness..thickness) {
                for (dx in -thickness..thickness) {
                    val px = x + dx
                    val py = y + dy
                    if (px < 0 || px >= width || py < 0 || py >= height) continue
                    pixels[py * width + px] = color
                }
            }
        }
    }

    private fun applyLighting(
        pixels: IntArray,
        width: Int,
        height: Int,
        illumination: Double,
        illuminationAngle: Double,
        glare: Double,
        glareAt: Pair<Double, Double>,
        noise: Double,
        seed: Long,
    ) {
        if (illumination == 0.0 && glare == 0.0 && noise == 0.0) return
        val random = Random(seed)
        val angle = illuminationAngle * PI / 180
        val dirX = cos(angle)
        val dirY = sin(angle)
        val glareX = glareAt.first * width
        val glareY = glareAt.second * height
        val glareSigma = min(width, height) * 0.16

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]
                // Свет действует умножением (тень делает всё темнее в одно и то
                // же число раз), блик — сложением (отражённый свет добавляется
                // к отражённому от доски), шум — сложением со случайным знаком.
                val gradient = 1.0 + illumination * ((x.toDouble() / width - 0.5) * dirX + (y.toDouble() / height - 0.5) * dirY) * 2
                val distanceSquared = (x - glareX) * (x - glareX) + (y - glareY) * (y - glareY)
                val spot = glare * 255.0 * exp(-distanceSquared / (2 * glareSigma * glareSigma))

                var result = 0
                for (shift in intArrayOf(16, 8, 0)) {
                    val channel = (pixel shr shift) and 0xFF
                    var value = channel * gradient + spot
                    if (noise > 0) value += (random.nextDouble() * 2 - 1) * noise
                    result = result or (value.toInt().coerceIn(0, 255) shl shift)
                }
                pixels[index] = result
            }
        }
    }
}
