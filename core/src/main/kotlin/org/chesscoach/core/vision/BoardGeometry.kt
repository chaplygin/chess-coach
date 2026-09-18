package org.chesscoach.core.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Точка в координатах исходного кадра.
 *
 * Дробная, а не целая: углы доски почти никогда не попадают в центр пикселя,
 * а округление каждого угла до пикселя уже на стадии поиска стоило бы
 * полклетки ошибки на дальнем краю выпрямленной доски.
 */
data class Point(val x: Double, val y: Double)

/** Четыре угла доски в кадре, по часовой стрелке начиная с левого верхнего. */
data class Quad(val corners: List<Point>) {
    val topLeft: Point get() = corners[0]
    val topRight: Point get() = corners[1]
    val bottomRight: Point get() = corners[2]
    val bottomLeft: Point get() = corners[3]
}

/**
 * Доска, выпрямленная в квадрат.
 *
 * [quad] возвращается вместе с картинкой намеренно: по нему можно нарисовать
 * найденную доску поверх фотографии и показать человеку, что именно программа
 * приняла за доску. Без этого единственный способ объяснить неудачу — "не
 * получилось", а это худшая из подсказок.
 */
data class WarpedBoard(val image: GrayImage, val quad: Quad, val confidence: Double)

/**
 * Поиск доски на фотографии, снятой под углом.
 *
 * Зачем отдельно от [BoardRecognizer]: тот ищет осевой (неповёрнутый) квадрат
 * по чередованию яркости и на снимке монитора или настоящей доски на столе
 * честно отказывается — наклонённая доска даёт плохой показатель чередования
 * для любого осевого квадрата. Здесь решается именно геометрическая задача:
 * найти четырёхугольник доски и вернуть её выпрямленной, чтобы дальше работал
 * уже существующий распознаватель клеток.
 *
 * Почему Хаф, а не поиск контуров: контур доски на настоящей фотографии рвётся
 * тенью, рукой и краем стола, а замкнутый контур нужен целиком. Прямые же
 * видны кусками, и Хаф складывает куски одной прямой вместе независимо от
 * разрывов.
 *
 * Почему сетка, а не "самый крупный четырёхугольник": четырёхугольников в
 * кадре со столом, книгой и монитором десятки, и любой из них крупнее доски.
 * А вот две группы прямых по девять штук в каждой, с почти равномерным шагом
 * внутри группы — признак, который кроме шахматной доски (и шахматной доски на
 * экране) в кадре взяться практически неоткуда. На нём и строится уверенность:
 * сколько из восемнадцати ожидаемых линий сетки действительно нашлось.
 *
 * Порядок работы:
 *  1. уменьшение кадра до рабочего размера — всё дальнейшее линейно по
 *     пикселям, и на телефоне разница между 4 Мп и 0.6 Мп это разница между
 *     секундами и долями секунды;
 *  2. Собель, порог по доле самых сильных градиентов (не по абсолютному числу:
 *     на снимке монитора и в тени абсолютный контраст разный, а доля краёв в
 *     кадре примерно одна и та же);
 *  3. Хаф с голосованием только вокруг направления градиента — пиксель края
 *     знает нормаль своей прямой, и перебирать ради него все 180 углов незачем;
 *  4. деление прямых на две группы по направлению, поиск в каждой группы
 *     девятки с равномерным шагом;
 *  5. крайние прямые каждой группы дают четырёхугольник, он проверяется
 *     выпрямлением и чередованием яркости клеток.
 */
object BoardGeometry {

    /**
     * @param size сторона выпрямленной доски в пикселях.
     * @return null, если доска не найдена. Ложное срабатывание хуже отказа:
     *   по выдуманной доске распознаватель выдаст выдуманную позицию, и человек
     *   будет разбирать чужую партию, не понимая, откуда она взялась.
     */
    fun locateBoard(image: GrayImage, size: Int = 512): WarpedBoard? {
        if (image.width < MIN_FRAME_SIDE || image.height < MIN_FRAME_SIDE) return null
        if (size < 64) return null

        val scaled = downscale(image, WORK_MAX_SIDE)
        val work = scaled.image

        val lines = detectLines(work)
        if (lines.size < MIN_LINES_TOTAL) return null

        // Два захода: сначала по одним только длинным прямым, потом по всем.
        //
        // Длинные — это настоящие границы клеток, идущие через всю доску, и на
        // ровном снимке только они дают верные углы: рядом с каждой границей
        // живёт ложный двойник в десяток пикселей, шаг у двойников тот же, и
        // сетка садится на них не хуже, уводя углы на треть клетки. Но на
        // сильном наклоне часть настоящих прямых сама коротка, и строгий отбор
        // теряет доску целиком — поэтому второй заход без отбора, и он же был
        // единственным раньше.
        val attempts = listOf(true, false)
        var bestQuad: Quad? = null
        var bestConfidence = 0.0

        for (onlyStrongLines in attempts) {
            val families = splitIntoFamilies(work, lines, onlyStrongLines) ?: continue
            val runsA = gridRuns(families.first, work)
            val runsB = gridRuns(families.second, work)
            if (runsA.isEmpty() || runsB.isEmpty()) continue

            val found = bestOfRuns(runsA, runsB, work)
            if (found != null) {
                bestQuad = found.first
                bestConfidence = found.second
                break
            }
        }

        if (bestQuad == null || bestConfidence < MIN_CONFIDENCE) return null

        // Выпрямляем из исходного кадра, а не из уменьшенного: уменьшение было
        // нужно только чтобы быстро найти углы, и терять на нём резкость фигур,
        // по которой дальше определяется тип, незачем.
        val sourceQuad = Quad(bestQuad.corners.map { Point(it.x * scaled.factor, it.y * scaled.factor) })
        val mapping = boardMapping(sourceQuad, size) ?: return null
        return WarpedBoard(warp(image, mapping, size), sourceQuad, bestConfidence)
    }

    private fun bestOfRuns(runsA: List<GridRun>, runsB: List<GridRun>, work: GrayImage): Pair<Quad, Double>? {
        var bestQuad: Quad? = null
        var bestConfidence = 0.0

        for (runA in runsA) {
            for (runB in runsB) {
                val quad = quadOf(runA, runB, work) ?: continue
                // Проверяем чередование прямо по кадру через найденное
                // соответствие, без промежуточной выпрямленной картинки: на
                // шесть десятков кандидатов выпрямление было бы самой дорогой
                // частью поиска, а нужны из него всего четыре точки на клетку.
                val mapping = boardMapping(quad, 8) ?: continue
                val quality = checkerQuality(work, mapping) ?: continue
                if (quality.contrast < MIN_CELL_CONTRAST) continue

                // Уверенность — минимум двух независимых свидетельств, а не их
                // среднее: сетка без чередования это обои в клетку, чередование
                // без сетки это случайное пятно, и ни то ни другое не доска.
                val gridScore = (runA.matched + runB.matched) / 18.0
                val checkerScore = ((quality.consistency - 0.5) * 2.0).coerceIn(0.0, 1.0)
                val confidence = min(gridScore, checkerScore)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestQuad = quad
                }
            }
        }

        val quad = bestQuad ?: return null
        if (bestConfidence < MIN_CONFIDENCE) return null
        return quad to bestConfidence
    }
}

// ---------------------------------------------------------------------------
// Константы. Все пороги — в пикселях рабочего (уменьшенного) кадра.
// ---------------------------------------------------------------------------

/** Меньше этого доска не различима даже теоретически: клетка была бы меньше 8 пикселей. */
private const val MIN_FRAME_SIDE = 80

/**
 * Рабочий размер кадра. 768 выбрано как компромисс: ошибка положения угла
 * растёт пропорционально коэффициенту уменьшения, а время — квадрату размера.
 */
private const val WORK_MAX_SIDE = 768

private const val MIN_LINES_TOTAL = 8

/**
 * Доля пикселей кадра, которые считаются краями. Пять процентов — это с
 * запасом больше, чем занимают границы клеток, фигуры и край стола вместе,
 * поэтому на чистом снимке порог опускается до абсолютного пола, а на шумном
 * поднимается сам.
 */
private const val EDGE_FRACTION = 0.05

/** Абсолютный пол для градиента: без него на однотонном фоне краями объявится шум. */
private const val MIN_EDGE_MAGNITUDE = 12.0

/** На сколько градусов в стороны от нормали градиента голосует пиксель края. */
private const val THETA_SPREAD_DEGREES = 4

private const val MAX_LINES = 90
private const val MAX_LINES_PER_FAMILY = 24

/** Минимальный шаг сетки: доска меньше 64 пикселей нам всё равно не годится. */
private const val MIN_GRID_STEP = 8.0

/** Минимальный угол между группами прямых: меньше — это одна группа, а не сетка. */
private const val MIN_FAMILY_ANGLE_DEGREES = 25.0

/** Сколько из девяти прямых группы должно найтись по-настоящему. */
/**
 * Доля голосов самой уверенной прямой группы, ниже которой прямая считается
 * обрывком, а не границей клетки.
 */
private const val MIN_VOTE_FRACTION = 0.45

private const val MIN_MATCHED_LINES = 7

/** Допуск попадания прямой в узел сетки, в долях местного шага. */
private const val GRID_TOLERANCE = 0.25

/** Во сколько раз дальний край доски может быть уже ближнего. */
private const val MAX_STEP_RATIO = 2.2

/**
 * Сколько вариантов сетки из каждой группы доходит до проверки чередованием.
 * Вариантов с полным совпадением по прямым бывает несколько — прямые стола
 * иногда складываются с прямыми доски в столь же правильную на вид сетку, — и
 * выбирать между ними должно чередование клеток, а не случайный порядок.
 */
private const val MAX_RUNS_PER_FAMILY = 8

/** Разница средней яркости светлых и тёмных клеток; ниже — область одноцветная. */
private const val MIN_CELL_CONTRAST = 8.0

/**
 * Порог уверенности. При минимуме из двух свидетельств это значит одновременно
 * не меньше 13 из 18 прямых сетки и не меньше 82% правильно чередующихся пар
 * соседних клеток. Подбиралось от обратного — от того, чтобы шум, однотонный
 * фон и рамка стола не проходили ни при каких обстоятельствах.
 */
private const val MIN_CONFIDENCE = 0.64

// ---------------------------------------------------------------------------
// Уменьшение кадра.
// ---------------------------------------------------------------------------

private class Scaled(val image: GrayImage, val factor: Double)

private fun downscale(image: GrayImage, maxSide: Int): Scaled {
    val side = max(image.width, image.height)
    if (side <= maxSide) return Scaled(image, 1.0)

    val factor = side.toDouble() / maxSide
    val width = max(1, (image.width / factor).roundToInt())
    val height = max(1, (image.height / factor).roundToInt())
    val pixels = IntArray(width * height)

    for (y in 0 until height) {
        val sy0 = (y * factor).toInt().coerceIn(0, image.height - 1)
        val sy1 = ((y + 1) * factor).toInt().coerceIn(sy0 + 1, image.height)
        for (x in 0 until width) {
            val sx0 = (x * factor).toInt().coerceIn(0, image.width - 1)
            val sx1 = ((x + 1) * factor).toInt().coerceIn(sx0 + 1, image.width)
            // Усреднение по блоку, а не выборка одного пикселя: на фотографии
            // с шумом выборка сохранила бы шум целиком, а тонкие границы клеток
            // наоборот теряла бы через одну.
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (sy in sy0 until sy1) {
                var index = sy * image.width + sx0
                for (sx in sx0 until sx1) {
                    val pixel = image.pixels[index]
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    count++
                    index++
                }
            }
            pixels[y * width + x] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
        }
    }
    return Scaled(GrayImage(width, height, pixels), factor)
}

private fun luma(pixel: Int): Double {
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    return (r + g + b) / 3.0
}

// ---------------------------------------------------------------------------
// Собель и Хаф.
// ---------------------------------------------------------------------------

/**
 * Прямая в виде нормали и расстояния: x * nx + y * ny = rho.
 *
 * Нормаль, а не "y = kx + b": вертикальные прямые в форме с угловым
 * коэффициентом не выражаются вовсе, а на доске они как раз есть всегда.
 */
private class Line(val nx: Double, val ny: Double, val rho: Double, val votes: Double) {
    fun angle(): Double = atan2(ny, nx)

    /** Расстояние от точки до прямой со знаком — по нему прямые группы упорядочиваются. */
    fun signedDistance(x: Double, y: Double): Double = rho - (x * nx + y * ny)

    fun flipped(): Line = Line(-nx, -ny, -rho, votes)
}

private fun detectLines(image: GrayImage): List<Line> {
    val width = image.width
    val height = image.height
    val brightness = DoubleArray(width * height) { luma(image.pixels[it]) }

    val magnitude = DoubleArray(width * height)
    val direction = DoubleArray(width * height)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            val topLeft = brightness[index - width - 1]
            val top = brightness[index - width]
            val topRight = brightness[index - width + 1]
            val left = brightness[index - 1]
            val right = brightness[index + 1]
            val bottomLeft = brightness[index + width - 1]
            val bottom = brightness[index + width]
            val bottomRight = brightness[index + width + 1]

            val gx = (topRight + 2 * right + bottomRight) - (topLeft + 2 * left + bottomLeft)
            val gy = (bottomLeft + 2 * bottom + bottomRight) - (topLeft + 2 * top + topRight)
            // Делим на 4 — сумму весов половины ядра: тогда величина градиента
            // это прямо перепад яркости на границе, и порог можно осмысленно
            // сравнивать с контрастом клеток доски.
            magnitude[index] = sqrt(gx * gx + gy * gy) / 4.0
            direction[index] = atan2(gy, gx)
        }
    }

    // Утоньшение края до одного пикселя поперёк (как в детекторе Канни).
    // Без него граница клетки шириной в два-три пикселя раскладывалась на две
    // соседние ячейки накопителя, и каждая набирала половину голосов: линии
    // доски оказывались слабее одной жирной линии стола, хотя длиннее её.
    val thin = DoubleArray(width * height)
    for (y in 2 until height - 2) {
        for (x in 2 until width - 2) {
            val index = y * width + x
            val value = magnitude[index]
            if (value <= 0.0) continue
            val angle = direction[index]
            // Направление градиента округляется до одного из четырёх: сравнивать
            // надо именно поперёк края, вдоль края соседи законно такие же.
            val sector = Math.floorMod(Math.round(angle * 4 / PI).toInt(), 4)
            val stepX = when (sector) {
                0 -> 1
                1 -> 1
                2 -> 0
                else -> -1
            }
            val stepY = when (sector) {
                0 -> 0
                1 -> 1
                2 -> 1
                else -> 1
            }
            val before = magnitude[index - stepY * width - stepX]
            val after = magnitude[index + stepY * width + stepX]
            if (value < before || value < after) continue
            thin[index] = value
        }
    }

    val threshold = edgeThreshold(thin)
    // Полградуса на ячейку, а не градус. Причина измерена: при шаге в градус
    // прямая длиной в треть кадра при неудачном попадании между ячейками
    // размазывалась по трём ячейкам rho (полградуса ошибки на длине 350
    // пикселей — это полтора пикселя на конце), набирала вместо 350 голосов
    // 220 и проваливалась под порог наравне с мусором.
    val thetaBins = 360
    val cosTable = DoubleArray(thetaBins) { cos(it * PI / thetaBins) }
    val sinTable = DoubleArray(thetaBins) { sin(it * PI / thetaBins) }
    val spreadBins = THETA_SPREAD_DEGREES * thetaBins / 180

    val diagonal = sqrt((width * width + height * height).toDouble())
    val rhoOffset = diagonal + 2.0
    val rhoBins = (2 * rhoOffset).toInt() + 2
    val accumulator = FloatArray(thetaBins * rhoBins)

    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            if (thin[index] < threshold) continue
            // Направление градиента и есть нормаль прямой — голосовать за все
            // 180 углов незачем, достаточно узкой окрестности. Это разница
            // между десятками миллионов голосов и сотнями тысяч.
            val center = Math.floorMod(Math.round(direction[index] * thetaBins / PI).toInt(), thetaBins)
            for (delta in -spreadBins..spreadBins) {
                val bin = Math.floorMod(center + delta, thetaBins)
                // rho считается от угла уже приведённого в [0,180): когда угол
                // заворачивается через 180 градусов, знак rho меняется сам.
                val rho = x * cosTable[bin] + y * sinTable[bin]
                val rhoIndex = (rho + rhoOffset).toInt()
                if (rhoIndex < 0 || rhoIndex >= rhoBins) continue
                accumulator[bin * rhoBins + rhoIndex] += 1f
            }
        }
    }

    var peak = 0f
    for (value in accumulator) if (value > peak) peak = value
    if (peak < 1f) return emptyList()
    // Доля от сильнейшей прямой, а не абсолютное число: длина прямых зависит
    // от того, как близко снята доска. Доля небольшая, потому что сильнейшая
    // прямая кадра — почти всегда край стола во весь кадр, а линии доски
    // заведомо короче её.
    val minVotes = max(25.0, peak * 0.06)

    val found = ArrayList<Line>()
    for (bin in 0 until thetaBins) {
        for (rhoIndex in 1 until rhoBins - 1) {
            val value = accumulator[bin * rhoBins + rhoIndex]
            if (value < minVotes) continue
            if (!isLocalMaximum(accumulator, thetaBins, rhoBins, bin, rhoIndex)) continue

            // Уточнение центром тяжести: положение угла доски получается из
            // пересечения прямых, и целочисленная ячейка накопителя стоила бы
            // до полутора пикселей ошибки на каждом угле.
            var weight = 0.0
            var thetaSum = 0.0
            var rhoSum = 0.0
            for (dt in -2..2) {
                for (dr in -2..2) {
                    val tb = Math.floorMod(bin + dt, thetaBins)
                    val rb = rhoIndex + dr
                    if (rb < 0 || rb >= rhoBins) continue
                    val w = accumulator[tb * rhoBins + rb].toDouble()
                    weight += w
                    thetaSum += w * (bin + dt)
                    rhoSum += w * rb
                }
            }
            if (weight <= 0.0) continue
            val theta = (thetaSum / weight) * PI / thetaBins
            val rho = rhoSum / weight - rhoOffset + 0.5
            found.add(Line(cos(theta), sin(theta), rho, value.toDouble()))
        }
    }

    return found.sortedByDescending { it.votes }.take(MAX_LINES)
}

private fun isLocalMaximum(
    accumulator: FloatArray,
    thetaBins: Int,
    rhoBins: Int,
    bin: Int,
    rhoIndex: Int,
): Boolean {
    val value = accumulator[bin * rhoBins + rhoIndex]
    for (dt in -4..4) {
        for (dr in -3..3) {
            if (dt == 0 && dr == 0) continue
            val tb = Math.floorMod(bin + dt, thetaBins)
            val rb = rhoIndex + dr
            if (rb < 0 || rb >= rhoBins) continue
            val other = accumulator[tb * rhoBins + rb]
            // Строго больше только с одной стороны, иначе плато из одинаковых
            // ячеек не даст ни одного максимума.
            if (other > value) return false
            if (other == value && (dt < 0 || (dt == 0 && dr < 0))) return false
        }
    }
    return true
}

/**
 * Порог градиента по доле самых сильных, а не по абсолютной величине.
 *
 * На снимке монитора контраст клеток вдвое ниже, чем на скриншоте, а в тени на
 * улице — ещё вдвое ниже. Абсолютный порог, подобранный по одному случаю, на
 * двух других отсекает либо всё, либо ничего. Доля краёв в кадре при этом
 * держится примерно одинаковой.
 */
private fun edgeThreshold(magnitude: DoubleArray): Double {
    val histogram = IntArray(257)
    var any = false
    for (value in magnitude) {
        if (value <= 0.0) continue
        histogram[min(256, value.toInt())]++
        any = true
    }
    if (!any) return Double.MAX_VALUE

    // Доля считается от площади кадра, а не от числа ненулевых градиентов.
    // Разница принципиальная: после утоньшения ненулевых остаётся несколько
    // процентов пикселей, и "десять процентов от них" — это уже только самые
    // контрастные края, то есть фигуры, а границы клеток отсекались целиком.
    val wanted = (magnitude.size * EDGE_FRACTION).toInt()
    var seen = 0
    for (bucket in 256 downTo 0) {
        seen += histogram[bucket]
        if (seen >= wanted) return max(MIN_EDGE_MAGNITUDE, bucket.toDouble())
    }
    return MIN_EDGE_MAGNITUDE
}

// ---------------------------------------------------------------------------
// Две группы прямых.
// ---------------------------------------------------------------------------

private class Family(val lines: List<Line>, val positions: DoubleArray)

private class Families(val first: Family, val second: Family)

/**
 * Деление прямых на две группы по направлению методом k-средних на удвоенном
 * угле.
 *
 * Удвоенный угол нужен потому, что направление прямой определено с точностью до
 * 180 градусов: прямые под 1 и под 179 градусов почти параллельны, а как числа
 * они на разных концах отрезка. После удвоения они оказываются рядом, и обычное
 * расстояние снова работает.
 */
private fun splitIntoFamilies(image: GrayImage, lines: List<Line>, onlyStrongLines: Boolean): Families? {
    if (lines.size < MIN_LINES_TOTAL) return null

    val vectors = lines.map { line ->
        val doubled = 2 * line.angle()
        doubleArrayOf(cos(doubled), sin(doubled))
    }

    // Первый центр — самая уверенная прямая, второй — самая непохожая на неё.
    // Случайная инициализация на десятке точек регулярно давала два кластера
    // внутри одной группы, а это ровно тот случай, когда сетки не получится.
    var centerA = vectors[0]
    var centerB = vectors[0]
    var worst = Double.MAX_VALUE
    for (vector in vectors) {
        val dot = vector[0] * centerA[0] + vector[1] * centerA[1]
        if (dot < worst) {
            worst = dot
            centerB = vector
        }
    }

    val assignment = IntArray(lines.size)
    repeat(8) {
        for (index in vectors.indices) {
            val vector = vectors[index]
            val toA = vector[0] * centerA[0] + vector[1] * centerA[1]
            val toB = vector[0] * centerB[0] + vector[1] * centerB[1]
            assignment[index] = if (toA >= toB) 0 else 1
        }
        // Опустевшая группа не повод сдаваться: оставляем прежний центр, и на
        // следующем шаге к нему что-нибудь притянется. Ранний отказ здесь
        // стоил потери доски на ровном месте — при наклоне 20 градусов.
        centerA = meanDirection(vectors, assignment, 0) ?: centerA
        centerB = meanDirection(vectors, assignment, 1) ?: centerB
    }

    val angleA = atan2(centerA[1], centerA[0]) / 2
    val angleB = atan2(centerB[1], centerB[0]) / 2
    val between = angleBetween(angleA, angleB) * 180 / PI
    if (between < MIN_FAMILY_ANGLE_DEGREES) return null

    val familyA = buildFamily(image, lines.filterIndexed { index, _ -> assignment[index] == 0 }, angleA, onlyStrongLines)
    val familyB = buildFamily(image, lines.filterIndexed { index, _ -> assignment[index] == 1 }, angleB, onlyStrongLines)
    if (familyA == null || familyB == null) return null
    return Families(familyA, familyB)
}

private fun meanDirection(vectors: List<DoubleArray>, assignment: IntArray, group: Int): DoubleArray? {
    var sumX = 0.0
    var sumY = 0.0
    var count = 0
    for (index in vectors.indices) {
        if (assignment[index] != group) continue
        sumX += vectors[index][0]
        sumY += vectors[index][1]
        count++
    }
    if (count == 0) return null
    val length = sqrt(sumX * sumX + sumY * sumY)
    if (length < 1e-9) return null
    return doubleArrayOf(sumX / length, sumY / length)
}

/** Угол между двумя направлениями с точностью до 180 градусов, в радианах, из [0, PI/2]. */
private fun angleBetween(a: Double, b: Double): Double {
    var diff = abs(a - b) % PI
    if (diff > PI / 2) diff = PI - diff
    return diff
}

/**
 * Подготовка группы: нормали разворачиваются в одну сторону, прямые
 * упорядочиваются по расстоянию от центра кадра и дубликаты сливаются.
 *
 * От центра кадра, а не от начала координат: при разбросе направлений внутри
 * группы (а он есть всегда — это и есть перспектива) rho, отсчитанное от угла
 * картинки, у соседних прямых гуляет сильнее, чем шаг сетки, и порядок ломается.
 */
private fun buildFamily(image: GrayImage, lines: List<Line>, meanAngle: Double, onlyStrongLines: Boolean): Family? {
    if (lines.size < 3) return null
    val meanX = cos(meanAngle)
    val meanY = sin(meanAngle)
    val centerX = image.width / 2.0
    val centerY = image.height / 2.0

    val oriented = lines.map { if (it.nx * meanX + it.ny * meanY >= 0) it else it.flipped() }
    val sorted = oriented.sortedBy { it.rho - (centerX * it.nx + centerY * it.ny) }

    val kept = ArrayList<Line>()
    val positions = ArrayList<Double>()
    for (line in sorted) {
        val position = line.rho - (centerX * line.nx + centerY * line.ny)
        val previous = positions.lastOrNull()
        if (previous != null && position - previous < MIN_GRID_STEP * 0.6) {
            // Две ячейки накопителя об одной и той же границе клетки. Оставляем
            // более уверенную, иначе шаг сетки в этом месте окажется нулевым.
            if (line.votes > kept.last().votes) {
                kept[kept.size - 1] = line
                positions[positions.size - 1] = position
            }
            continue
        }
        kept.add(line)
        positions.add(position)
    }
    if (kept.size < 3) return null

    // Прямая, проходящая через всю доску, набирает голоса по всей своей длине,
    // а край фигуры или тень — лишь по своему куску. На ровном скриншоте это
    // различие втрое: у девяти настоящих границ клеток голосов было 448, у
    // ложных двойников в десяти пикселях от них — заметно меньше. Именно эти
    // двойники и уводили сетку: шаг у них тот же, поэтому модель садилась на
    // них не хуже, чем на настоящие, а углы доски уезжали на треть клетки.
    //
    // Порог берётся от самой уверенной прямой этой же группы, а не абсолютным:
    // длина прямой зависит и от размера доски в кадре, и от перспективы —
    // дальний край короче ближнего, но не вдвое, пока доска вообще читаема.
    val strongest = kept.maxOf { it.votes }
    val strong = kept.indices.filter { kept[it].votes >= strongest * MIN_VOTE_FRACTION }
    val survivors = if (onlyStrongLines && strong.size >= MIN_MATCHED_LINES) strong else kept.indices.toList()

    val limited = if (survivors.size <= MAX_LINES_PER_FAMILY) {
        survivors
    } else {
        survivors.sortedByDescending { kept[it].votes }.take(MAX_LINES_PER_FAMILY).sorted()
    }
    return Family(limited.map { kept[it] }, DoubleArray(limited.size) { positions[limited[it]] })
}

// ---------------------------------------------------------------------------
// Девятка прямых с равномерным шагом.
// ---------------------------------------------------------------------------

/** Найденная в группе сетка: крайние прямые и сколько из девяти нашлось по-настоящему. */
private class GridRun(val first: Line, val last: Line, val matched: Int, val firstIndex: Int, val lastIndex: Int)

/**
 * Дробно-линейная зависимость положения прямой от её номера в сетке:
 * p(k) = (a * k + b) / (c * k + 1).
 *
 * Это не приближение, а точный закон. Равноотстоящие прямые в плоскости доски
 * после перспективной съёмки ложатся именно так — перспектива сохраняет
 * двойное отношение, а любое отображение, сохраняющее двойное отношение на
 * прямой, дробно-линейно. Поэтому модель с тремя параметрами описывает и
 * ровный скриншот (c = 0), и доску под сорок градусов одинаково точно, а
 * допуск на совпадение можно держать узким.
 *
 * Три параметра — три опорные прямые: две крайние и одна между ними с
 * известным номером. Перебор троек по паре десятков прямых группы стоит
 * тысячи проверок, то есть ничего.
 */
private class GridModel(private val a: Double, private val b: Double, private val c: Double) {
    fun positionAt(k: Int): Double {
        val denominator = c * k + 1.0
        return (a * k + b) / denominator
    }

    fun isUsable(): Boolean {
        for (k in 0..8) {
            if (abs(c * k + 1.0) < 0.2) return false
        }
        return true
    }

    companion object {
        /** Модель по первой прямой (k = 0), последней (k = 8) и промежуточной с номером [middleIndex]. */
        fun through(first: Double, middle: Double, middleIndex: Int, last: Double): GridModel? {
            val denominator = middleIndex * (last - middle)
            if (abs(denominator) < 1e-9) return null
            val c = ((middle - first) - middleIndex * (last - first) / 8.0) / denominator
            val a = last * c + (last - first) / 8.0
            val model = GridModel(a, first, c)
            return if (model.isUsable()) model else null
        }
    }
}

/**
 * Поиск в группе девяти прямых, идущих примерно равным шагом.
 *
 * "Примерно" — потому что перспектива шаг меняет: дальний край доски сжат
 * сильнее ближнего. Для наклона, при котором доска вообще остаётся читаемой,
 * отношение крайних шагов не превышает полутора, поэтому сначала берётся
 * линейная модель с запасом по допуску, а потом она уточняется квадратичной —
 * парабола описывает перспективное сжатие с ошибкой заметно меньше допуска и,
 * в отличие от честной дробно-линейной подгонки, считается одним решением
 * системы три на три.
 *
 * Перебор пар "первая прямая — вторая прямая" квадратичен по числу прямых
 * группы (их не больше двух дюжин), то есть пара сотен вариантов — на порядки
 * дешевле, чем перебирать четвёрки прямых как четырёхугольники.
 */
private fun gridRuns(family: Family, image: GrayImage): List<GridRun> {
    val positions = family.positions
    val count = positions.size
    if (count < MIN_MATCHED_LINES) return emptyList()

    val maxSpan = 1.4 * max(image.width, image.height)
    val minSpan = 8 * MIN_GRID_STEP
    val candidates = ArrayList<GridRun>()

    for (a in 0 until count) {
        for (b in a + 1 until count) {
            val span = positions[b] - positions[a]
            if (span < minSpan || span > maxSpan) continue

            var bestMatches: IntArray? = null
            var bestCount = MIN_MATCHED_LINES - 1
            for (middle in a + 1 until b) {
                for (middleIndex in 1..7) {
                    val model = GridModel.through(
                        positions[a], positions[middle], middleIndex, positions[b],
                    ) ?: continue
                    if (!isPlausibleGrid(model)) continue
                    val matches = matchGrid(positions, model)
                    val matched = matches.count { it >= 0 }
                    if (matched > bestCount) {
                        bestCount = matched
                        bestMatches = matches
                    }
                }
            }

            val matches = bestMatches ?: continue
            candidates.add(
                GridRun(
                    family.lines[matches[0]],
                    family.lines[matches[8]],
                    bestCount,
                    matches[0],
                    matches[8],
                )
            )
        }
    }

    // Из вариантов с одинаковыми краями оставляем лучший, дальше берём пятёрку
    // лучших: проверка выпрямлением дорогая, но не настолько, чтобы гонять её
    // по сотне кандидатов.
    val unique = HashMap<Long, GridRun>()
    for (run in candidates) {
        val key = run.firstIndex.toLong() * 1000 + run.lastIndex
        val previous = unique[key]
        if (previous == null || run.matched > previous.matched) unique[key] = run
    }
    return unique.values.sortedByDescending { it.matched }.take(MAX_RUNS_PER_FAMILY)
}

/**
 * Отсев заведомо негодных моделей до того, как считать совпадения: шаг сетки
 * должен расти (или убывать) монотонно, оставаться положительным и не
 * меняться в разы. Доска, у которой дальний ряд втрое уже ближнего, снята под
 * таким углом, что фигуры на ней всё равно закрывают друг друга.
 */
private fun isPlausibleGrid(model: GridModel): Boolean {
    val firstStep = model.positionAt(1) - model.positionAt(0)
    if (firstStep < MIN_GRID_STEP) return false
    var previous = firstStep
    for (k in 2..8) {
        val step = model.positionAt(k) - model.positionAt(k - 1)
        if (step < MIN_GRID_STEP) return false
        previous = step
    }
    val ratio = previous / firstStep
    return ratio in (1.0 / MAX_STEP_RATIO)..MAX_STEP_RATIO
}

/**
 * Какие из девяти прямых сетки действительно нашлись. Допуск берётся от
 * местного шага, а не от среднего: на дальнем краю наклонённой доски клетки
 * вдвое уже, и общий допуск там склеивал бы соседние прямые.
 */
private fun matchGrid(positions: DoubleArray, model: GridModel): IntArray {
    val matches = IntArray(9) { -1 }
    for (k in 0..8) {
        val target = model.positionAt(k)
        val localStep = if (k < 8) {
            model.positionAt(k + 1) - target
        } else {
            target - model.positionAt(k - 1)
        }
        val tolerance = localStep * GRID_TOLERANCE
        var bestIndex = -1
        var bestDistance = tolerance
        for (index in positions.indices) {
            val distance = abs(positions[index] - target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        matches[k] = bestIndex
    }
    return matches
}

// ---------------------------------------------------------------------------
// Четырёхугольник из четырёх крайних прямых.
// ---------------------------------------------------------------------------

private fun intersection(a: Line, b: Line): Point? {
    val determinant = a.nx * b.ny - a.ny * b.nx
    if (abs(determinant) < 1e-9) return null
    val x = (a.rho * b.ny - a.ny * b.rho) / determinant
    val y = (a.nx * b.rho - a.rho * b.nx) / determinant
    return Point(x, y)
}

private fun quadOf(runA: GridRun, runB: GridRun, image: GrayImage): Quad? {
    val corners = listOfNotNull(
        intersection(runA.first, runB.first),
        intersection(runA.first, runB.last),
        intersection(runA.last, runB.last),
        intersection(runA.last, runB.first),
    )
    if (corners.size != 4) return null

    val margin = 0.3
    for (corner in corners) {
        if (!corner.x.isFinite() || !corner.y.isFinite()) return null
        if (corner.x < -margin * image.width || corner.x > (1 + margin) * image.width) return null
        if (corner.y < -margin * image.height || corner.y > (1 + margin) * image.height) return null
    }

    val ordered = orderClockwise(corners)
    if (!isConvex(ordered)) return null

    var shortest = Double.MAX_VALUE
    var longest = 0.0
    for (i in 0 until 4) {
        val from = ordered[i]
        val to = ordered[(i + 1) % 4]
        val length = distance(from, to)
        shortest = min(shortest, length)
        longest = max(longest, length)
    }
    if (shortest < 8 * MIN_GRID_STEP) return null
    // Доска под любым углом, при котором её ещё можно прочитать, остаётся
    // примерно квадратной: сторона, втрое короче противоположной, означает, что
    // прямые собраны из разных объектов.
    if (longest / shortest > 3.0) return null

    return Quad(ordered)
}

private fun distance(a: Point, b: Point): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

/**
 * Углы по часовой стрелке начиная с левого верхнего.
 *
 * Сортировка по углу от центра даёт обход по кругу (в экранных координатах, где
 * y растёт вниз, возрастание угла — это именно по часовой), а начало обхода
 * выбирается как угол с наименьшей суммой координат.
 */
private fun orderClockwise(corners: List<Point>): List<Point> {
    val centerX = corners.sumOf { it.x } / corners.size
    val centerY = corners.sumOf { it.y } / corners.size
    val sorted = corners.sortedBy { atan2(it.y - centerY, it.x - centerX) }
    var start = 0
    var best = Double.MAX_VALUE
    for (index in sorted.indices) {
        val score = sorted[index].x + sorted[index].y
        if (score < best) {
            best = score
            start = index
        }
    }
    return List(4) { sorted[(start + it) % 4] }
}

private fun isConvex(corners: List<Point>): Boolean {
    var positive = false
    var negative = false
    for (i in 0 until 4) {
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        val c = corners[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (cross > 0) positive = true
        if (cross < 0) negative = true
    }
    return positive != negative
}

// ---------------------------------------------------------------------------
// Перспективное преобразование.
// ---------------------------------------------------------------------------

/**
 * Соответствие между выпрямленной доской и кадром в обе стороны.
 *
 * Обратное преобразование считается не обращением матрицы, а той же подгонкой с
 * переставленными местами точками: результат тот же, кода вдвое меньше, а
 * возможностей ошибиться в формуле обратной матрицы три на три — ни одной.
 */
internal class BoardMapping(private val toFrameMatrix: DoubleArray, private val toBoardMatrix: DoubleArray) {

    fun toFrame(u: Double, v: Double): Point = apply(toFrameMatrix, u, v)

    fun toBoard(x: Double, y: Double): Point = apply(toBoardMatrix, x, y)

    private fun apply(h: DoubleArray, x: Double, y: Double): Point {
        val denominator = h[6] * x + h[7] * y + 1.0
        if (abs(denominator) < 1e-12) return Point(Double.NaN, Double.NaN)
        return Point(
            (h[0] * x + h[1] * y + h[2]) / denominator,
            (h[3] * x + h[4] * y + h[5]) / denominator,
        )
    }
}

/** Соответствие между квадратом [0, size] x [0, size] и четырёхугольником [quad] в кадре. */
internal fun boardMapping(quad: Quad, size: Int): BoardMapping? {
    val square = listOf(
        Point(0.0, 0.0),
        Point(size.toDouble(), 0.0),
        Point(size.toDouble(), size.toDouble()),
        Point(0.0, size.toDouble()),
    )
    val forward = solveProjective(square, quad.corners) ?: return null
    val backward = solveProjective(quad.corners, square) ?: return null
    return BoardMapping(forward, backward)
}

/**
 * Восемь коэффициентов преобразования по четырём парам точек (девятый равен
 * единице — преобразование задано с точностью до множителя).
 */
private fun solveProjective(from: List<Point>, to: List<Point>): DoubleArray? {
    val matrix = Array(8) { DoubleArray(8) }
    val rhs = DoubleArray(8)
    for (i in 0 until 4) {
        val source = from[i]
        val target = to[i]
        val evenRow = 2 * i
        matrix[evenRow][0] = source.x
        matrix[evenRow][1] = source.y
        matrix[evenRow][2] = 1.0
        matrix[evenRow][6] = -source.x * target.x
        matrix[evenRow][7] = -source.y * target.x
        rhs[evenRow] = target.x

        val oddRow = evenRow + 1
        matrix[oddRow][3] = source.x
        matrix[oddRow][4] = source.y
        matrix[oddRow][5] = 1.0
        matrix[oddRow][6] = -source.x * target.y
        matrix[oddRow][7] = -source.y * target.y
        rhs[oddRow] = target.y
    }
    return solveLinearSystem(matrix, rhs)
}

/** Метод Гаусса с выбором ведущего элемента. null, если система вырождена. */
private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val n = rhs.size
    val work = Array(n) { row -> DoubleArray(n + 1) { column -> if (column < n) matrix[row][column] else rhs[row] } }

    for (column in 0 until n) {
        var pivot = column
        for (row in column + 1 until n) {
            if (abs(work[row][column]) > abs(work[pivot][column])) pivot = row
        }
        if (abs(work[pivot][column]) < 1e-10) return null
        val swap = work[column]
        work[column] = work[pivot]
        work[pivot] = swap

        for (row in 0 until n) {
            if (row == column) continue
            val factor = work[row][column] / work[column][column]
            if (factor == 0.0) continue
            for (c in column..n) work[row][c] -= factor * work[column][c]
        }
    }

    val solution = DoubleArray(n)
    for (row in 0 until n) {
        val value = work[row][n] / work[row][row]
        if (!value.isFinite()) return null
        solution[row] = value
    }
    return solution
}

/**
 * Обратное отображение с билинейной интерполяцией: идём по пикселям результата
 * и берём цвет из кадра. Прямое отображение оставляло бы в результате дырки —
 * при растяжении дальнего края доски соседние пиксели кадра расходятся на
 * несколько пикселей результата.
 */
private fun warp(image: GrayImage, mapping: BoardMapping, size: Int): GrayImage {
    val pixels = IntArray(size * size)
    for (v in 0 until size) {
        for (u in 0 until size) {
            val source = mapping.toFrame(u + 0.5, v + 0.5)
            pixels[v * size + u] = sampleBilinear(image, source.x, source.y)
        }
    }
    return GrayImage(size, size, pixels)
}

private fun sampleBilinear(image: GrayImage, x: Double, y: Double): Int {
    if (!x.isFinite() || !y.isFinite()) return 0
    val clampedX = x.coerceIn(0.0, (image.width - 1).toDouble())
    val clampedY = y.coerceIn(0.0, (image.height - 1).toDouble())
    val x0 = clampedX.toInt()
    val y0 = clampedY.toInt()
    val x1 = min(x0 + 1, image.width - 1)
    val y1 = min(y0 + 1, image.height - 1)
    val fx = clampedX - x0
    val fy = clampedY - y0

    val topLeft = image.pixels[y0 * image.width + x0]
    val topRight = image.pixels[y0 * image.width + x1]
    val bottomLeft = image.pixels[y1 * image.width + x0]
    val bottomRight = image.pixels[y1 * image.width + x1]

    var result = 0
    for (shift in intArrayOf(16, 8, 0)) {
        val a = (topLeft shr shift) and 0xFF
        val b = (topRight shr shift) and 0xFF
        val c = (bottomLeft shr shift) and 0xFF
        val d = (bottomRight shr shift) and 0xFF
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        val value = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
        result = result or (value shl shift)
    }
    return result
}

// ---------------------------------------------------------------------------
// Проверка: действительно ли это доска.
// ---------------------------------------------------------------------------

private class CheckerQuality(val consistency: Double, val contrast: Double)

/**
 * Насколько найденный четырёхугольник ведёт себя как шахматная доска.
 *
 * Клетки берутся не из выпрямленной картинки, а прямо из кадра через
 * соответствие: выпрямление шестидесяти кандидатов было бы самой дорогой
 * частью поиска, а нужно из него всего несколько точек на клетку.
 *
 * Меряются две вещи. [CheckerQuality.consistency] — доля клеток, чья яркость
 * совпала с ожидаемой чётностью: у настоящей доски светлые и тёмные поля
 * строго чередуются, у случайного четырёхугольника на скатерти — нет.
 * [CheckerQuality.contrast] — насколько вообще различаются светлые и тёмные
 * поля: без этого однотонная стена показала бы идеальное "чередование" из
 * одинаковых клеток.
 *
 * Обе чётности проверяются и берётся лучшая: какой угол доски оказался левым
 * верхним, заранее неизвестно.
 */
private fun checkerQuality(image: GrayImage, mapping: BoardMapping): CheckerQuality? {
    val lightSamples = ArrayList<Double>(32)
    val darkSamples = ArrayList<Double>(32)

    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val brightness = cellBrightness(image, mapping, row, col) ?: return null
            if ((row + col) % 2 == 0) lightSamples.add(brightness) else darkSamples.add(brightness)
        }
    }

    val lightLevel = median(lightSamples) ?: return null
    val darkLevel = median(darkSamples) ?: return null
    val contrast = abs(lightLevel - darkLevel)
    val threshold = (lightLevel + darkLevel) / 2.0

    var agreeing = 0
    for (sample in lightSamples) if (sample >= threshold) agreeing++
    for (sample in darkSamples) if (sample < threshold) agreeing++

    val consistency = agreeing.toDouble() / 64.0
    return CheckerQuality(max(consistency, 1.0 - consistency), contrast)
}

/**
 * Точки, по которым читается яркость поля: каёмка клетки, а не её середина.
 * В середине стоит фигура, а до каёмки она не достаёт почти никогда — ни на
 * экранной диаграмме, ни на снимке сверху.
 */
private val CELL_PROBES = listOf(
    0.12 to 0.12, 0.5 to 0.12, 0.88 to 0.12,
    0.12 to 0.5, 0.88 to 0.5,
    0.12 to 0.88, 0.5 to 0.88, 0.88 to 0.88,
)

/**
 * Яркость поля берётся по каёмке клетки, а не по её середине: в середине стоит
 * фигура. Из восьми точек каёмки берётся медиана — даже если пара из них
 * занята краем фигуры или тенью, медиана всё равно опишет поле.
 */
private fun cellBrightness(image: GrayImage, mapping: BoardMapping, row: Int, col: Int): Double? {
    val samples = ArrayList<Double>(CELL_PROBES.size)
    for ((du, dv) in CELL_PROBES) {
        // Соответствие построено на квадрате со стороной в восемь клеток,
        // поэтому единица здесь — это клетка, а не доля доски.
        val point = mapping.toFrame(col + du, row + dv)
        if (point.x.isNaN() || point.y.isNaN()) return null
        if (point.x < 0 || point.y < 0 || point.x >= image.width || point.y >= image.height) return null
        samples.add(neighbourhoodLuma(image, point.x, point.y))
    }
    return median(samples)
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

/** Средняя яркость по окрестности три на три — один пиксель слишком шумный. */
private fun neighbourhoodLuma(image: GrayImage, x: Double, y: Double): Double {
    val centerX = x.roundToInt()
    val centerY = y.roundToInt()
    var sum = 0.0
    var count = 0
    for (dy in -1..1) {
        val py = centerY + dy
        if (py < 0 || py >= image.height) continue
        for (dx in -1..1) {
            val px = centerX + dx
            if (px < 0 || px >= image.width) continue
            sum += luma(image.pixels[py * image.width + px])
            count++
        }
    }
    if (count == 0) return 0.0
    return sum / count
}
