package org.chesscoach.core.vision

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType as ChesslibPieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.chesscoach.core.BoardLayout

/**
 * Кадр без альфы, как он приходит с камеры или из галереи после уменьшения.
 *
 * Тип объявлен без единой ссылки на Android: модуль :core собирается и
 * тестируется как обычная JVM-программа, а Android-типы touch экрана нельзя
 * ни создать, ни прогнать в юнит-тесте без эмулятора.
 */
data class GrayImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Откуда взялся снимок. От этого зависит и поиск доски, и то, что советовать
 * человеку, когда распознать не удалось.
 *
 * Два случая действительно разные, а не два названия одного. На экране доска
 * плоская, нарисована ровно, фигуры лежат внутри своих клеток и покрашены в
 * два чистых цвета. На настоящей доске фигуры объёмные, свет неровный,
 * "белые" кремовые, а сам снимок почти всегда сделан под углом.
 */
enum class PhotoSource {
    /** Скриншот с телефона или фотография экрана монитора. */
    SCREEN,

    /** Фотография настоящей доски. */
    BOARD,
}

/**
 * Результат распознавания позиции с фотографии.
 *
 * Три исхода, а не два: "не нашли доску" и "нашли, но не уверены в фигурах" —
 * разные ситуации с разными подсказками человеку, и объединять их в общий
 * провал означало бы заставлять гадать, что именно переснять.
 */
sealed interface RecognitionResult {
    /** [cellConfidence] — уверенность по каждой из 64 клеток, в порядке a8..h1, как в [BoardLayout]. */
    data class Recognized(val fen: String, val cellConfidence: List<Double>) : RecognitionResult

    /** Доску нашли, но часть фигур или всю расстановку целиком нельзя показать не проверив. */
    data class NotSure(val reason: String, val unreadableCells: List<String>) : RecognitionResult

    data class NoBoardFound(val reason: String) : RecognitionResult
}

/**
 * Распознавание шахматной позиции по фотографии или скриншоту доски.
 *
 * Обученной модели здесь нет и быть не должно (офлайн-требование проекта),
 * поэтому вся цепочка — это геометрия и статистика яркости: сначала находим
 * квадрат доски по чередованию клеток, потом для каждой из 64 клеток решаем
 * "пусто или занято", потом по силуэту фигуры оцениваем её тип. Каждый шаг
 * прозрачно возвращает уверенность, а не только ответ — ровно так же, как
 * тренер объясняет причины, а не выносит приговоры (M7): молча показать не ту
 * позицию хуже, чем честно сказать "не смог разобрать".
 */
object BoardRecognizer {

    fun recognize(
        image: GrayImage,
        whiteToMove: Boolean,
        source: PhotoSource = PhotoSource.SCREEN,
    ): RecognitionResult {
        val raw = readAllCells(image, source)
            ?: return RecognitionResult.NoBoardFound(boardNotFoundAdvice(source))

        val unreadable = mutableListOf<String>()
        for (index in 0 until 64) {
            if (raw.occupied[index] && raw.confidence[index] < MIN_OCCUPIED_CONFIDENCE) {
                unreadable.add(squareNameOf(index))
            }
        }

        if (unreadable.isNotEmpty()) {
            return RecognitionResult.NotSure(
                "Не уверены в фигурах на части клеток — уверенное распознавание там ниже порога.",
                unreadable.distinct(),
            )
        }

        val placement = buildPlacement(raw.letters)
        val fen = placement + " " + (if (whiteToMove) "w" else "b") + " - - 0 1"

        val legality = validateLegalPlacement(fen)
        if (legality != null) {
            return RecognitionResult.NotSure(legality.reason, legality.squares)
        }

        return RecognitionResult.Recognized(fen, raw.confidence)
    }

    private fun squareNameOf(index: Int): String = BoardLayout.squareName(index) ?: "?"

    /**
     * Совет человеку зависит от того, что он снимал: переснять скриншот
     * "ровнее" нельзя, а вот обрезать лишнее вокруг доски можно.
     */
    private fun boardNotFoundAdvice(source: PhotoSource): String = when (source) {
        PhotoSource.SCREEN ->
            "На снимке не нашлась доска. Если это скриншот — обрежьте всё лишнее вокруг доски. " +
                "Если это фотография экрана — снимайте прямо напротив, чтобы доска не выглядела трапецией."

        PhotoSource.BOARD ->
            "На снимке не нашлась доска. Снимайте сверху, чтобы в кадр попала вся доска целиком " +
                "и её края были хорошо видны."
    }
}

/**
 * Сырой результат по всем 64 клеткам без порогов уверенности и без проверки
 * легальности — то, что накопил бы [BoardRecognizer.recognize] перед тем как
 * решить, показывать позицию или сказать "не уверены". Отдельный internal-вход
 * нужен тестам из части 2: там меряется честная доля верно распознанных
 * клеток, а не то, сколько раз алгоритм сам себе не поверил и предпочёл
 * промолчать (это тоже важная метрика, но другая).
 */
internal class RawCellReadings(val letters: List<Char?>, val confidence: List<Double>, val occupied: List<Boolean>)

/**
 * Признаки силуэта по всем 64 клеткам (null там, где пусто) — вход только для
 * калибровки таблицы [PROTOTYPES] в тестах.
 *
 * Это не удобство, а требование корректности: эталоны обязаны измеряться тем же
 * кодом, который потом сравнивает с ними реальные клетки. Первая версия таблицы
 * была посчитана отдельным скриптом с другим порогом бинаризации и без обрезки
 * клетки — и ферзь с королём путались почти всегда.
 */
internal fun cellSignatures(image: GrayImage): List<DoubleArray?> {
    val region = findBoardRegion(image) ?: return List(64) { null }
    return readBoard(image, region).map { cell ->
        cell.shape?.let {
            doubleArrayOf(
                it.heightFrac,
                it.aspect,
                it.topFrac,
                it.midFrac,
                it.botFrac,
                it.symmetry,
                it.fill,
                it.holesNorm,
            )
        }
    }
}

/** Всё, что прочитано об одной клетке до того, как решается тип фигуры. */
private class ScannedCell(
    val occupied: Boolean,
    val occupancyConfidence: Double,
    val meanForeground: Double,
    val shape: ShapeFeatures?,
)

/**
 * Один проход по всей доске: чтение клеток плюс приведение роста фигур к
 * самой высокой из них.
 *
 * Рост относительно клетки бесполезен: в одном наборе фигур пешка занимает
 * три четверти клетки, в другом — половину, и таблица эталонов, снятая с одного
 * набора, на другом читает все пешки как слонов — это не догадка, а измеренный
 * результат: шрифты на Windows и на сборочной машине разные, и на второй точность
 * типа фигуры упала со 100% до 40%.
 *
 * Поэтому рост меряется от самой высокой фигуры на этой же доске. В любой
 * возможной позиции на доске есть оба короля, то есть мерка всегда на месте и не
 * зависит ни от набора фигур, ни от того, как близко снята доска.
 */
private fun readBoard(image: GrayImage, region: BoardRegion): List<ScannedCell> {
    val scanned = ArrayList<ScannedCell>(64)

    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val cell = extractCell(image, region, row, col)
            val expectedLight = (row + col) % 2 == region.lightParity
            val globalRef = if (expectedLight) region.lightAvg else region.darkAvg
            // Фон берётся из каёмки самой клетки, а к среднему по доске мы
            // откатываемся только если каёмка занята фигурой соседней клетки.
            // На снимке живой доски освещение неравномерно: один край ярче
            // другого сильнее, чем светлая клетка отличается от тёмной, и
            // среднее по доске там просто неверно.
            val localRef = borderBackground(cell) ?: globalRef
            // Порог контраста подстраивается под контраст самой доски: у тёмных
            // деревянных досок разница между клетками меньше, чем у ярких
            // пластиковых, и фиксированное число било бы мимо в обе стороны.
            val boardContrast = max(18.0, abs(region.lightAvg - region.darkAvg) / 3.0)
            // И под освещение конкретной клетки. Свет действует умножением: в
            // затенённом углу доски все яркости меньше в одно и то же число раз,
            // а вместе с ними и разница между фигурой и полем. Общий порог там
            // оказывается слишком высоким, и фигура в тени читается как пустая
            // клетка — на замере с неровным светом это и было главной потерей.
            val illumination = if (globalRef > 1.0) (localRef / globalRef).coerceIn(0.35, 2.5) else 1.0
            val contrastScale = max(8.0, boardContrast * illumination)
            val reading = readCell(cell, localRef, contrastScale)
            val shape = if (reading.occupied) shapeFeatures(reading.mask, cell.width, cell.height) else null
            scanned.add(
                ScannedCell(reading.occupied, reading.occupancyConfidence, reading.meanForeground, shape)
            )
        }
    }

    val tallest = scanned.mapNotNull { it.shape?.heightFrac }.maxOrNull() ?: return scanned
    if (tallest <= 0.0) return scanned
    return scanned.map { cell ->
        val shape = cell.shape ?: return@map cell
        ScannedCell(
            cell.occupied,
            cell.occupancyConfidence,
            cell.meanForeground,
            shape.copy(heightFrac = shape.heightFrac / tallest),
        )
    }
}

/**
 * Чтение всех 64 клеток. null, если доску на снимке найти не удалось.
 *
 * Два способа найти доску, и порядок зависит от того, что снимали. Осевой
 * поиск ищет ровный квадрат и на скриншоте отрабатывает точно и мгновенно.
 * Поиск по сетке прямых выпрямляет доску, снятую под углом, но стоит дороже
 * (десятки миллисекунд) и на идеально ровной картинке не даёт ничего сверх
 * осевого. Поэтому для снимка с экрана сначала пробуется осевой, а для
 * фотографии доски — сразу выпрямление.
 *
 * Оба способа остаются запасными друг для друга: фотография экрана бывает
 * почти ровной, а скриншот — обрезанным криво.
 */
internal fun readAllCells(image: GrayImage, source: PhotoSource = PhotoSource.SCREEN): RawCellReadings? {
    val order = when (source) {
        PhotoSource.SCREEN -> listOf(::readAxisAligned, ::readWarped)
        PhotoSource.BOARD -> listOf(::readWarped, ::readAxisAligned)
    }
    for (attempt in order) {
        val result = attempt(image)
        if (result != null) return result
    }
    return null
}

/** Доска как ровный квадрат прямо в кадре — случай скриншота. */
private fun readAxisAligned(image: GrayImage): RawCellReadings? {
    val region = findBoardRegion(image) ?: return null
    return readCells(image, region)
}

/**
 * Доска, снятая под углом: сначала находим её четырёхугольник и выпрямляем, а
 * дальше читаем ровно так же, как ровную.
 *
 * Выпрямленную доску всё равно прогоняем через осевой поиск, а не считаем, что
 * она занимает кадр ровно: так проверка чередования клеток делается ещё раз
 * уже на выпрямленной картинке, и неверно найденный четырёхугольник отсеется
 * здесь, а не превратится в уверенно показанную чужую позицию.
 */
private fun readWarped(image: GrayImage): RawCellReadings? {
    val located = BoardGeometry.locateBoard(image) ?: return null
    val region = findBoardRegion(located.image) ?: return null
    return readCells(located.image, region)
}

private fun readCells(image: GrayImage, region: BoardRegion): RawCellReadings? {
    val scanned = readBoard(image, region)
    val colors = colorSplit(scanned)

    val cellLetters = arrayOfNulls<Char>(64)
    val cellConfidence = DoubleArray(64)
    val cellOccupied = BooleanArray(64)

    for (index in 0 until 64) {
        val cell = scanned[index]
        if (!cell.occupied) {
            cellConfidence[index] = cell.occupancyConfidence
            continue
        }

        cellOccupied[index] = true
        val shape = cell.shape
        if (shape == null) {
            // occupied=true, но силуэт не вычислился (пустая маска на границе
            // порога) — честнее отметить клетку непрочитанной, чем гадать.
            cellConfidence[index] = 0.0
            continue
        }

        val (type, typeConfidence) = classifyPieceType(shape)
        val combined = minOf(cell.occupancyConfidence, colors.confidence(cell.meanForeground), typeConfidence)
        cellLetters[index] = pieceLetter(type, colors.isWhite(cell.meanForeground))
        cellConfidence[index] = combined
    }

    return RawCellReadings(cellLetters.toList(), cellConfidence.toList(), cellOccupied.toList())
}

/**
 * Граница между белыми и чёрными фигурами, посчитанная по самой доске.
 *
 * Абсолютный порог "ярче половины шкалы — значит белая" верен для экрана, где
 * фигуры почти чисто белые и чёрные, и неверен для настоящего набора: там
 * "белая" фигура кремовая, "чёрная" тёмно-коричневая, и обе могут оказаться по
 * одну сторону от середины шкалы. Поэтому порог ищется между двумя скоплениями
 * яркости прямо на этом снимке — методом Оцу, то есть перебором границы с
 * максимальной межгрупповой дисперсией.
 */
private class ColorSplit(val threshold: Double, val separation: Double) {

    fun isWhite(meanForeground: Double): Boolean = meanForeground > threshold

    /**
     * Уверенность падает и когда фигура близка к границе, и когда сами
     * скопления плохо разделены: во втором случае неуверенны все клетки сразу,
     * и это верно — на снимке, где фигуры обоих цветов неразличимы, нечего
     * показывать с уверенным видом.
     */
    fun confidence(meanForeground: Double): Double {
        if (separation < MIN_COLOR_SEPARATION) return 0.0
        return (abs(meanForeground - threshold) / (separation / 2.0)).coerceIn(0.0, 1.0)
    }
}

/**
 * Ниже этой разницы средней яркости между двумя группами фигур считается, что
 * цвета на снимке не разделяются вовсе. Взято заметно выше шума яркости внутри
 * одной фигуры, но заметно ниже разницы кремового и тёмного дерева.
 */
private const val MIN_COLOR_SEPARATION = 30.0

private fun colorSplit(scanned: List<ScannedCell>): ColorSplit {
    val values = scanned.filter { it.occupied }.map { it.meanForeground }.sorted()
    if (values.size < 2) return ColorSplit(127.5, 255.0)

    var bestThreshold = 127.5
    var bestScore = -1.0
    var bestSeparation = 0.0
    for (cut in 1 until values.size) {
        val low = values.subList(0, cut)
        val high = values.subList(cut, values.size)
        val lowMean = low.average()
        val highMean = high.average()
        // Межгрупповая дисперсия: чем крупнее обе группы и чем дальше их
        // средние, тем лучше граница между ними.
        val score = low.size.toDouble() * high.size * (highMean - lowMean) * (highMean - lowMean)
        if (score > bestScore) {
            bestScore = score
            bestThreshold = (lowMean + highMean) / 2.0
            bestSeparation = highMean - lowMean
        }
    }

    return ColorSplit(bestThreshold, bestSeparation)
}

/**
 * Яркость фона клетки по её каёмке.
 *
 * Фигура стоит в середине клетки, поэтому каёмка — это почти наверняка само
 * поле. Берётся медиана, а не среднее: край фигуры, заехавший в каёмку,
 * сдвинул бы среднее, а медиану нет. null — если каёмка слишком узкая, чтобы
 * судить (крошечная клетка на мелком снимке).
 */
private fun borderBackground(cell: Cell): Double? {
    val band = maxOf(1, minOf(cell.width, cell.height) / 6)
    val ring = ArrayList<Int>(2 * band * (cell.width + cell.height))
    for (y in 0 until cell.height) {
        for (x in 0 until cell.width) {
            val onBorder = x < band || y < band || x >= cell.width - band || y >= cell.height - band
            if (onBorder) ring.add(cell.brightness[y * cell.width + x])
        }
    }
    if (ring.size < 8) return null
    ring.sort()
    return ring[ring.size / 2].toDouble()
}

// ---------------------------------------------------------------------------
// Этап а: поиск доски по периодичности яркости.
// ---------------------------------------------------------------------------

/** Квадратная область доски и то, что о ней уже известно после поиска. */
private data class BoardRegion(
    val x0: Int,
    val y0: Int,
    val size: Int,
    val lightAvg: Double,
    val darkAvg: Double,
    /** Клетка (row, col) светлая, если (row+col) % 2 равен этому числу. */
    val lightParity: Int,
)

private const val MIN_BOARD_SIZE = 48

/**
 * Минимальная разница средней яркости между светлыми и тёмными клетками:
 * ниже неё область одноцветная и поиск доски в ней бессмыслен, даже если
 * формально нашёлся локальный максимум показателя.
 */
private const val MIN_LIGHT_DARK_DIFF = 8.0

/**
 * Порог показателя чередования (см. [checkerScore]). Подобран так, чтобы
 * реальная шахматная доска (разница клеток заметно больше разброса внутри
 * своей клетки) проходила, а случайный шум — нет.
 */
private const val MIN_BOARD_SCORE = 1.15

/**
 * Ищем доску по сетке кандидатов вместо честного перебора всех
 * прямоугольников — тот перебор квадратичен по числу пикселей и на
 * фотографии в несколько мегапикселей не окупается. Интегральное изображение
 * делает каждую проверку кандидата O(1), поэтому сетка из полутора тысяч
 * кандидатов считается мгновенно.
 *
 * Перспективу фотографии намеренно не выправляем: наклонная доска даёт
 * плохой показатель чередования для любого осевого квадрата и естественно
 * ведёт к NoBoardFound, как и требуется, без отдельного кода на этот случай.
 */
private fun findBoardRegion(image: GrayImage): BoardRegion? {
    val minDim = minOf(image.width, image.height)
    if (minDim < MIN_BOARD_SIZE) return null

    val integral = IntegralImage(image)

    var best: BoardRegion? = null
    var bestScore = MIN_BOARD_SCORE

    val sizeSteps = 10
    val posSteps = 8
    for (sizeIndex in 0 until sizeSteps) {
        val fraction = 1.0 - sizeIndex * 0.05
        val size = (minDim * fraction).toInt()
        if (size < MIN_BOARD_SIZE) continue

        val maxX = image.width - size
        val maxY = image.height - size
        val xStep = max(1, maxX / posSteps)
        val yStep = max(1, maxY / posSteps)

        var x0 = 0
        while (x0 <= maxX) {
            var y0 = 0
            while (y0 <= maxY) {
                val candidate = checkerScore(integral, x0, y0, size)
                if (candidate != null && candidate.score > bestScore) {
                    bestScore = candidate.score
                    best = BoardRegion(x0, y0, size, candidate.lightAvg, candidate.darkAvg, candidate.lightParity)
                }
                if (maxY == 0) break
                y0 += yStep
            }
            if (maxX == 0) break
            x0 += xStep
        }
    }

    return best
}

private data class CheckerCandidate(val score: Double, val lightAvg: Double, val darkAvg: Double, val lightParity: Int)

/**
 * Показатель чередования: делим квадрат на 64 клетки, считаем среднюю
 * яркость каждой (через интегральное изображение), делим на две группы по
 * чётности (row+col) и сравниваем разницу средних с разбросом внутри групп.
 * Фигуры на клетках увеличивают разброс внутри группы, но не убивают саму
 * разницу между светлыми и тёмными клетками — метод остаётся рабочим и на
 * частично занятой доске.
 */
private fun checkerScore(integral: IntegralImage, x0: Int, y0: Int, size: Int): CheckerCandidate? {
    val cellSize = size / 8.0
    val cellAvg = Array(8) { DoubleArray(8) }
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val cx0 = x0 + (col * cellSize).toInt()
            val cy0 = y0 + (row * cellSize).toInt()
            val cx1 = x0 + ((col + 1) * cellSize).toInt()
            val cy1 = y0 + ((row + 1) * cellSize).toInt()
            cellAvg[row][col] = integral.average(cx0, cy0, cx1, cy1)
        }
    }

    val groupEven = ArrayList<Double>(32)
    val groupOdd = ArrayList<Double>(32)
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            if ((row + col) % 2 == 0) groupEven.add(cellAvg[row][col]) else groupOdd.add(cellAvg[row][col])
        }
    }

    val meanEven = groupEven.average()
    val meanOdd = groupOdd.average()
    val diff = abs(meanEven - meanOdd)
    if (diff < MIN_LIGHT_DARK_DIFF) return null

    val spread = stddev(groupEven, meanEven) + stddev(groupOdd, meanOdd) + 1.0
    val score = diff / spread

    val lightParity = if (meanEven > meanOdd) 0 else 1
    val lightAvg = maxOf(meanEven, meanOdd)
    val darkAvg = minOf(meanEven, meanOdd)
    return CheckerCandidate(score, lightAvg, darkAvg, lightParity)
}

private fun stddev(values: List<Double>, mean: Double): Double {
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    return sqrt(variance)
}

/** Таблица частичных сумм яркости — среднее по любому прямоугольнику за O(1). */
private class IntegralImage(image: GrayImage) {
    private val width = image.width
    private val height = image.height
    private val sums = DoubleArray((width + 1) * (height + 1))

    init {
        // Стандартная формула интегрального изображения: sums на одну строку и
        // один столбец больше исходного, нулевая строка и нулевой столбец — сама
        // граница (сумма по пустому прямоугольнику равна нулю).
        for (y in 0 until height) {
            for (x in 0 until width) {
                val above = sums[y * (width + 1) + (x + 1)]
                val left = sums[(y + 1) * (width + 1) + x]
                val aboveLeft = sums[y * (width + 1) + x]
                val here = brightnessOf(image.pixels[y * width + x]).toDouble()
                sums[(y + 1) * (width + 1) + (x + 1)] = here + above + left - aboveLeft
            }
        }
    }

    fun average(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val ax0 = x0.coerceIn(0, width)
        val ay0 = y0.coerceIn(0, height)
        val ax1 = x1.coerceIn(ax0, width)
        val ay1 = y1.coerceIn(ay0, height)
        val area = (ax1 - ax0) * (ay1 - ay0)
        if (area <= 0) return 0.0
        val total = sums[ay1 * (width + 1) + ax1] - sums[ay0 * (width + 1) + ax1] -
            sums[ay1 * (width + 1) + ax0] + sums[ay0 * (width + 1) + ax0]
        return total / area
    }
}

private fun brightnessOf(pixel: Int): Int {
    val rgb = pixel and 0xFFFFFF
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    return (r + g + b) / 3
}

// ---------------------------------------------------------------------------
// Этапы б и в: клетки с отступом, пусто/занято, цвет фигуры.
// ---------------------------------------------------------------------------

/** Отступ от краёв клетки, чтобы не зацепить соседнюю фигуру или линию доски. */
private const val CELL_INSET_FRACTION = 0.10

private class Cell(val brightness: IntArray, val width: Int, val height: Int)

private fun extractCell(image: GrayImage, region: BoardRegion, row: Int, col: Int): Cell {
    val cellSize = region.size / 8.0
    val fullX0 = region.x0 + col * cellSize
    val fullY0 = region.y0 + row * cellSize
    val inset = cellSize * CELL_INSET_FRACTION

    val x0 = (fullX0 + inset).toInt().coerceIn(0, image.width - 1)
    val y0 = (fullY0 + inset).toInt().coerceIn(0, image.height - 1)
    val x1 = (fullX0 + cellSize - inset).toInt().coerceIn(x0 + 1, image.width)
    val y1 = (fullY0 + cellSize - inset).toInt().coerceIn(y0 + 1, image.height)

    val w = x1 - x0
    val h = y1 - y0
    val brightness = IntArray(w * h)
    for (yy in 0 until h) {
        for (xx in 0 until w) {
            brightness[yy * w + xx] = brightnessOf(image.pixels[(y0 + yy) * image.width + (x0 + xx)])
        }
    }
    return Cell(brightness, w, h)
}

/**
 * Порог по доле "чужих" пикселей в клетке. Ниже — точно пусто, выше — точно
 * занято, между ними — пограничная зона, в которой уверенность снижается
 * линейно, а не падает резко до нуля.
 */
private const val EMPTY_UPPER_BOUND = 0.045
private const val OCCUPIED_LOWER_CONFIDENT = 0.12

private class CellReading(
    val mask: BooleanArray,
    val occupied: Boolean,
    val occupancyConfidence: Double,
    /** Средняя яркость пикселей фигуры. Белая она или чёрная, решается по всей доске сразу. */
    val meanForeground: Double,
)

/**
 * Пусто или занято решаем через отклонение от ожидаемого фона клетки, а не
 * абсолютную яркость: одна и та же фигура на светлой и тёмной клетке даёт
 * разный контраст, и абсолютный порог был бы верен только для одной из них.
 */
private fun readCell(cell: Cell, backgroundRef: Double, contrastScale: Double): CellReading {
    val mask = BooleanArray(cell.brightness.size)
    var fgCount = 0
    var fgSum = 0L
    for (i in cell.brightness.indices) {
        val diff = cell.brightness[i] - backgroundRef
        if (abs(diff) > contrastScale) {
            mask[i] = true
            fgCount++
            fgSum += cell.brightness[i]
        }
    }

    val total = cell.brightness.size.coerceAtLeast(1)
    val fillRatio = fgCount.toDouble() / total
    val occupied = fillRatio > EMPTY_UPPER_BOUND

    val occupancyConfidence = when {
        fillRatio <= EMPTY_UPPER_BOUND * 0.4 -> 1.0
        fillRatio >= OCCUPIED_LOWER_CONFIDENT -> 1.0
        fillRatio <= EMPTY_UPPER_BOUND ->
            1.0 - (fillRatio - EMPTY_UPPER_BOUND * 0.4) / (EMPTY_UPPER_BOUND * 0.6) * 0.4
        else ->
            0.6 + (fillRatio - EMPTY_UPPER_BOUND) / (OCCUPIED_LOWER_CONFIDENT - EMPTY_UPPER_BOUND) * 0.4
    }.coerceIn(0.0, 1.0)

    val meanForeground = if (fgCount > 0) fgSum.toDouble() / fgCount else backgroundRef

    return CellReading(mask, occupied, occupancyConfidence, meanForeground)
}

// ---------------------------------------------------------------------------
// Этап г: тип фигуры по геометрии силуэта.
// ---------------------------------------------------------------------------

private enum class RecognizedPieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

private data class ShapeFeatures(
    /** Высота силуэта к высоте клетки: единственный признак, отличающий пешку от слона. */
    val heightFrac: Double,
    val aspect: Double,
    val topFrac: Double,
    val midFrac: Double,
    val botFrac: Double,
    val symmetry: Double,
    val fill: Double,
    val holesNorm: Double,
)

/**
 * Признаки формы считаются в рамке собственного bounding box силуэта — иначе
 * фигура, снятая чуть крупнее, читалась бы как другая фигура.
 *
 * Исключение одно — [ShapeFeatures.heightFrac]. Пешка и слон по форме почти
 * одинаковы (круглая голова на расширяющемся основании), и без размера относительно
 * клетки они путались постоянно — именно так стартовая позиция получала девятую
 * белую пешку вместо слона на f1. Человек различает их точно так же — по росту.
 */
private fun shapeFeatures(mask: BooleanArray, width: Int, height: Int): ShapeFeatures? {
    var minX = width
    var maxX = -1
    var minY = height
    var maxY = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (mask[y * width + x]) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return null

    val boxW = maxX - minX + 1
    val boxH = maxY - minY + 1
    val aspect = boxW.toDouble() / boxH

    fun columnHasForegroundInBand(x: Int, y0: Int, y1: Int): Boolean {
        for (y in y0 until y1) {
            if (mask[(minY + y) * width + (minX + x)]) return true
        }
        return false
    }

    val thirds = DoubleArray(3)
    for (third in 0..2) {
        val y0 = third * boxH / 3
        val y1 = (third + 1) * boxH / 3
        var count = 0
        for (x in 0 until boxW) {
            if (columnHasForegroundInBand(x, y0, y1)) count++
        }
        thirds[third] = count.toDouble() / boxW
    }

    var matches = 0
    var total = 0
    for (y in 0 until boxH) {
        for (x in 0 until boxW / 2) {
            val left = mask[(minY + y) * width + (minX + x)]
            val right = mask[(minY + y) * width + (minX + boxW - 1 - x)]
            total++
            if (left == right) matches++
        }
    }
    val symmetry = if (total == 0) 1.0 else matches.toDouble() / total

    var fgCount = 0
    for (y in 0 until boxH) {
        for (x in 0 until boxW) {
            if (mask[(minY + y) * width + (minX + x)]) fgCount++
        }
    }
    val fill = fgCount.toDouble() / (boxW * boxH)

    val holes = countHoles(mask, width, minX, minY, maxX, maxY)
    val holesNorm = holes.coerceAtMost(3) / 3.0

    val heightFrac = boxH.toDouble() / height
    return ShapeFeatures(heightFrac, aspect, thirds[0], thirds[1], thirds[2], symmetry, fill, holesNorm)
}

/**
 * Число замкнутых "дыр" в силуэте — фон внутри bounding box, не связанный с
 * его границей. Мелкий шум сглаживания (один-два случайных пикселя на стыке
 * контура) отфильтровываем по площади: иначе на низком разрешении реальной
 * клетки это число становится случайным, а не признаком формы.
 */
private fun countHoles(mask: BooleanArray, fullWidth: Int, minX: Int, minY: Int, maxX: Int, maxY: Int): Int {
    val boxW = maxX - minX + 1
    val boxH = maxY - minY + 1
    val background = BooleanArray(boxW * boxH)
    for (y in 0 until boxH) {
        for (x in 0 until boxW) {
            background[y * boxW + x] = !mask[(minY + y) * fullWidth + (minX + x)]
        }
    }

    fun idx(y: Int, x: Int) = y * boxW + x

    val outside = BooleanArray(boxW * boxH)
    val stack = ArrayDeque<Int>()
    for (x in 0 until boxW) {
        if (background[idx(0, x)] && !outside[idx(0, x)]) {
            outside[idx(0, x)] = true
            stack.addLast(idx(0, x))
        }
        if (background[idx(boxH - 1, x)] && !outside[idx(boxH - 1, x)]) {
            outside[idx(boxH - 1, x)] = true
            stack.addLast(idx(boxH - 1, x))
        }
    }
    for (y in 0 until boxH) {
        if (background[idx(y, 0)] && !outside[idx(y, 0)]) {
            outside[idx(y, 0)] = true
            stack.addLast(idx(y, 0))
        }
        if (background[idx(y, boxW - 1)] && !outside[idx(y, boxW - 1)]) {
            outside[idx(y, boxW - 1)] = true
            stack.addLast(idx(y, boxW - 1))
        }
    }
    floodFillOutside(stack, background, outside, boxW, boxH)

    val visited = BooleanArray(boxW * boxH)
    val minArea = max(2, (boxW * boxH * 0.01).toInt())
    var holes = 0
    for (y in 0 until boxH) {
        for (x in 0 until boxW) {
            val i = idx(y, x)
            if (background[i] && !outside[i] && !visited[i]) {
                val area = floodFillArea(i, background, outside, visited, boxW, boxH)
                if (area >= minArea) holes++
            }
        }
    }
    return holes
}

private fun floodFillOutside(
    stack: ArrayDeque<Int>,
    background: BooleanArray,
    outside: BooleanArray,
    boxW: Int,
    boxH: Int,
) {
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val cy = current / boxW
        val cx = current % boxW
        forEachNeighbor(cy, cx, boxW, boxH) { ny, nx ->
            val ni = ny * boxW + nx
            if (background[ni] && !outside[ni]) {
                outside[ni] = true
                stack.addLast(ni)
            }
        }
    }
}

private fun floodFillArea(
    start: Int,
    background: BooleanArray,
    outside: BooleanArray,
    visited: BooleanArray,
    boxW: Int,
    boxH: Int,
): Int {
    val stack = ArrayDeque<Int>()
    stack.addLast(start)
    visited[start] = true
    var area = 0
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        area++
        val cy = current / boxW
        val cx = current % boxW
        forEachNeighbor(cy, cx, boxW, boxH) { ny, nx ->
            val ni = ny * boxW + nx
            if (background[ni] && !outside[ni] && !visited[ni]) {
                visited[ni] = true
                stack.addLast(ni)
            }
        }
    }
    return area
}

private inline fun forEachNeighbor(y: Int, x: Int, boxW: Int, boxH: Int, action: (Int, Int) -> Unit) {
    if (y - 1 >= 0) action(y - 1, x)
    if (y + 1 < boxH) action(y + 1, x)
    if (x - 1 >= 0) action(y, x - 1)
    if (x + 1 < boxW) action(y, x + 1)
}

/**
 * Эталонные значения признаков для каждого типа фигуры, измеренные один раз
 * заранее на эталонном рендере: символ Unicode фигуры (сплошной силуэт, тот
 * же для обеих сторон — как выглядит настоящая фигура на фото, а не полый
 * контур) в клетке с тем же 10%-отступом, что и extractCell. Не оценка "на
 * глаз": числа получены прогоном этого же кода [shapeFeatures] на чистом
 * изображении одной фигуры, поэтому расстояние до них — честная мера
 * похожести силуэта, а не подогнанный порог.
 */
private class Prototype(
    val type: RecognizedPieceType,
    val height: Double,
    val aspect: Double,
    val top: Double,
    val mid: Double,
    val bot: Double,
    val symmetry: Double,
    val fill: Double,
    val holesNorm: Double,
)

// Числа измерены этим же кодом (extractCell с 10%-отступом + shapeFeatures) на
// эталонном рендере каждой фигуры, а не на изолированном скрипте с другим
// порогом бинаризации: первая версия эталонов была откалибрована отдельным
// скриптом с иным порогом и числом "дыр", из-за чего пешка и слон, ферзь и
// король путались почти всегда — расстояние до чужого эталона оказывалось
// меньше расстояния до своего же. Смотри BoardRecognizerTest за реальными
// цифрами точности на этой версии.
private val PROTOTYPES = listOf(
    Prototype(RecognizedPieceType.KING, 1.000, 0.681, 0.500, 0.625, 1.000, 1.000, 0.523, 0.000),
    Prototype(RecognizedPieceType.QUEEN, 0.894, 0.714, 0.733, 0.600, 1.000, 1.000, 0.526, 0.000),
    Prototype(RecognizedPieceType.ROOK, 0.713, 0.672, 0.801, 0.623, 1.000, 1.000, 0.672, 0.000),
    Prototype(RecognizedPieceType.BISHOP, 0.818, 0.704, 0.444, 0.517, 1.000, 0.972, 0.437, 0.121),
    Prototype(RecognizedPieceType.KNIGHT, 0.757, 0.675, 0.774, 0.708, 1.000, 0.891, 0.621, 0.000),
    Prototype(RecognizedPieceType.PAWN, 0.572, 0.670, 0.470, 0.454, 1.000, 0.999, 0.502, 0.000),
)

// Асимметрия — единственный надёжный признак коня среди всех шести фигур,
// поэтому у неё вес заметно выше остальных.
// Рост фигуры в клетке — единственное, что надёжно отделяет пешку от остальных,
// поэтому вес высокий. Расплата: на фото сбоку ближние фигуры выше дальних,
// но такой кадр и так не проходит поиск доски (требуется съёмка сверху).
private const val WEIGHT_HEIGHT = 2.0

private const val WEIGHT_ASPECT = 1.0
private const val WEIGHT_TOP = 1.2
private const val WEIGHT_MID = 1.0
private const val WEIGHT_BOT = 1.0
private const val WEIGHT_SYMMETRY = 1.5
private const val WEIGHT_FILL = 1.0

// Число "дыр" оказалось самым шумным признаком: на реальных клетках оно
// прыгает от фона (светлая клетка/тёмная, белая фигура/чёрная) сильнее, чем
// меняется между типами фигур (см. отчёт по BoardRecognizerTest — ладья
// путалась с пешкой именно из-за этого). Вес небольшой, признак остаётся
// только слабым добавлением, а не решающим голосом.
private const val WEIGHT_HOLES = 0.15

/** Типичное расстояние до "плохого" совпадения — калибровано по разбросу самих эталонов. */
private const val DISTANCE_SCALE = 0.6

private fun featureDistance(features: ShapeFeatures, prototype: Prototype): Double {
    val dHeight = features.heightFrac - prototype.height
    val dAspect = features.aspect - prototype.aspect
    val dTop = features.topFrac - prototype.top
    val dMid = features.midFrac - prototype.mid
    val dBot = features.botFrac - prototype.bot
    val dSymmetry = features.symmetry - prototype.symmetry
    val dFill = features.fill - prototype.fill
    val dHoles = features.holesNorm - prototype.holesNorm
    val sumSquares = WEIGHT_HEIGHT * dHeight * dHeight +
        WEIGHT_ASPECT * dAspect * dAspect +
        WEIGHT_TOP * dTop * dTop +
        WEIGHT_MID * dMid * dMid +
        WEIGHT_BOT * dBot * dBot +
        WEIGHT_SYMMETRY * dSymmetry * dSymmetry +
        WEIGHT_FILL * dFill * dFill +
        WEIGHT_HOLES * dHoles * dHoles
    return sqrt(sumSquares)
}

/**
 * Уверенность — произведение двух независимых проверок: похоже ли вообще на
 * лучший эталон (абсолютное расстояние) и насколько он лучше второго
 * кандидата (относительный отрыв). Хорошее совпадение с большим отрывом от
 * второго места даёт высокую уверенность; слабое совпадение или два похожих
 * кандидата — низкую, даже если формально победитель есть.
 */
private fun classifyPieceType(features: ShapeFeatures): Pair<RecognizedPieceType, Double> {
    val ranked = PROTOTYPES.map { it.type to featureDistance(features, it) }.sortedBy { it.second }
    val best = ranked[0]
    val second = ranked[1]

    val absoluteConfidence = (1.0 - best.second / DISTANCE_SCALE).coerceIn(0.0, 1.0)
    val margin = (second.second - best.second) / (second.second + best.second + 0.001)
    val relativeConfidence = margin.coerceIn(0.0, 1.0)

    val confidence = sqrt(absoluteConfidence * relativeConfidence)
    return best.first to confidence
}

private fun pieceLetter(type: RecognizedPieceType, isWhite: Boolean): Char {
    val base = when (type) {
        RecognizedPieceType.KING -> 'k'
        RecognizedPieceType.QUEEN -> 'q'
        RecognizedPieceType.ROOK -> 'r'
        RecognizedPieceType.BISHOP -> 'b'
        RecognizedPieceType.KNIGHT -> 'n'
        RecognizedPieceType.PAWN -> 'p'
    }
    return if (isWhite) base.uppercaseChar() else base
}

/**
 * Порог итоговой уверенности занятой клетки: ниже него клетка идёт в NotSure.
 *
 * Замеры на синтетике (BoardRecognizerTest, 102 фигуры) показали неприятное:
 * у верно распознанных клеток уверенность в среднем 65% при минимуме 21%, у
 * ошибочных — 54% при максимуме 74%. Диапазоны перекрываются почти полностью,
 * то есть отделить верное от ошибочного по этому числу нельзя ни при каком пороге.
 * Порог 0.5 при этом отправлял в NotSure даже стартовую позицию, распознанную без единой
 * ошибки — функция, которая не работает даже на идеальном входе, бесполезна.
 *
 * Поэтому порог оставлен только как защита от клеток, где силуэт не разобрался вовсе
 * (там уверенность ровно 0), а реальных защит от неверной позиции две другие:
 * проверка легальности расстановки через chesslib и то, что экран рисует распознанную
 * позицию на доске рядом с разбором и прямо просит её сверить.
 */
private const val MIN_OCCUPIED_CONFIDENCE = 0.15

// ---------------------------------------------------------------------------
// Сборка FEN и проверка легальности через chesslib.
// ---------------------------------------------------------------------------

private fun buildPlacement(cells: List<Char?>): String {
    val result = StringBuilder()
    for (row in 0 until 8) {
        var emptyRun = 0
        for (col in 0 until 8) {
            val piece = cells[row * 8 + col]
            if (piece == null) {
                emptyRun++
            } else {
                if (emptyRun > 0) {
                    result.append(emptyRun)
                    emptyRun = 0
                }
                result.append(piece)
            }
        }
        if (emptyRun > 0) result.append(emptyRun)
        if (row < 7) result.append('/')
    }
    return result.toString()
}

internal class LegalityProblem(val reason: String, val squares: List<String>)

/**
 * Проверка не собственным парсером FEN, а через уже загруженную в chesslib
 * доску: правила "ровно один король", "не больше восьми пешек", "пешка не на
 * первой и восьмой горизонтали" смотрят на то же представление позиции,
 * которое участвует в остальной игре, а не на отдельную копию логики,
 * способную незаметно разойтись с ней.
 */
internal fun validateLegalPlacement(fen: String): LegalityProblem? {
    val board = Board()
    val loaded = try {
        board.loadFromFen(fen)
        true
    } catch (error: Exception) {
        false
    }
    if (!loaded) return LegalityProblem("Не удалось прочитать собранную позицию.", emptyList())

    var whiteKings = 0
    var blackKings = 0
    val whiteKingSquares = mutableListOf<String>()
    val blackKingSquares = mutableListOf<String>()

    var whitePawns = 0
    var blackPawns = 0
    val whitePawnSquares = mutableListOf<String>()
    val blackPawnSquares = mutableListOf<String>()
    val edgePawnSquares = mutableListOf<String>()

    for (square in Square.values()) {
        if (square == Square.NONE) continue
        val piece = board.getPiece(square)
        if (piece == Piece.NONE) continue

        val squareName = square.toString().lowercase()
        val rank = squareName.getOrNull(1)

        when (piece.pieceType) {
            ChesslibPieceType.KING -> if (piece.pieceSide == Side.WHITE) {
                whiteKings++
                whiteKingSquares.add(squareName)
            } else {
                blackKings++
                blackKingSquares.add(squareName)
            }

            ChesslibPieceType.PAWN -> {
                if (piece.pieceSide == Side.WHITE) {
                    whitePawns++
                    whitePawnSquares.add(squareName)
                } else {
                    blackPawns++
                    blackPawnSquares.add(squareName)
                }
                if (rank == '1' || rank == '8') edgePawnSquares.add(squareName)
            }

            else -> Unit
        }
    }

    val problems = mutableListOf<String>()
    val badSquares = mutableListOf<String>()

    if (whiteKings != 1) {
        problems.add("у белых " + whiteKings + " король(ей) вместо одного")
        badSquares.addAll(whiteKingSquares)
    }
    if (blackKings != 1) {
        problems.add("у чёрных " + blackKings + " король(ей) вместо одного")
        badSquares.addAll(blackKingSquares)
    }
    if (whitePawns > 8) {
        problems.add("у белых " + whitePawns + " пешек, больше восьми")
        badSquares.addAll(whitePawnSquares)
    }
    if (blackPawns > 8) {
        problems.add("у чёрных " + blackPawns + " пешек, больше восьми")
        badSquares.addAll(blackPawnSquares)
    }
    if (edgePawnSquares.isNotEmpty()) {
        problems.add("пешка стоит на первой или восьмой горизонтали")
        badSquares.addAll(edgePawnSquares)
    }

    if (problems.isEmpty()) return null
    return LegalityProblem(
        "Расстановка невозможна по правилам: " + problems.joinToString("; ") + ".",
        badSquares.distinct(),
    )
}
