package org.chesscoach.core.vision

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import org.chesscoach.core.BoardLayout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Часть 2 задания: не угадываем качество распознавания, а измеряем его на
 * синтетических досках. java.awt доступен только здесь, в тестах обычной JVM
 * — в основном коде :core (и тем более в BoardRecognizer) его нет и быть не
 * может, потому что тот же код должен без изменений работать на Android.
 *
 * Тест намеренно не подгоняется под красивое число: пороги ниже — это
 * регрессионные ограждения на грани случайного угадывания (для типа фигуры —
 * 1 из 6, то есть ~17%), а не желаемый результат. Настоящие цифры печатаются
 * в вывод теста и идут в отчёт как есть.
 */
class BoardRecognizerTest {

    private val lightColor = Color(0xED, 0xD6, 0xB0)
    private val darkColor = Color(0xB5, 0x88, 0x63)

    /**
     * Силуэты фигур — одна и та же "сплошная" форма Unicode для обеих сторон,
     * а не пара "белый контур / чёрная заливка", которой :app рисует доску на
     * экране. Причина: настоящая белая фигура на фотографии — такой же
     * сплошной силуэт, как и чёрная, просто светлее фона, а не полый контур с
     * дыркой на месте короны. Эталоны в BoardRecognizer откалиброваны на эти
     * же сплошные формы, поэтому и тест рисует ими.
     */
    private val solidGlyphs = mapOf(
        'k' to "♚", 'q' to "♛", 'r' to "♜",
        'b' to "♝", 'n' to "♞", 'p' to "♟",
    )

    private val startingPosition =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    /** Реальная позиция из ChessLibSmokeTest — ход после 1.e4 e5 2.Nf3 Nc6 3.Bb5. */
    private val ruyLopez =
        "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/8/PPPP1PPP/RNBQK1NR"

    /** Разменянный миттельшпиль: кони, слоны, ладьи и ферзь на обеих сторонах вместе. */
    private val middlegame =
        "r2q1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1"

    /** Пешечный эндшпиль — почти пустая доска, только короли и одна пешка. */
    private val pawnEndgame =
        "8/8/4k3/8/8/4K3/4P3/8"

    /** Кони отдельно от прочих фигур — проверка именно на асимметрию силуэта. */
    private val knightsOnly =
        "8/8/3k4/8/2N1n3/8/3K4/8"

    private val testPositions = listOf(
        "стартовая позиция" to startingPosition,
        "Испанская партия (реальная игра)" to ruyLopez,
        "миттельшпиль после разменов" to middlegame,
        "пешечный эндшпиль" to pawnEndgame,
        "только кони" to knightsOnly,
    )

    @Test
    fun `измеряем реальную точность распознавания на синтетических досках`() {
        var totalCells = 0
        var correctCells = 0

        var occupiedCells = 0
        var occupancyCorrect = 0

        var colorChecked = 0
        var colorCorrect = 0

        var typeChecked = 0
        var typeCorrect = 0

        // Путаницы по типам: сколько раз ожидали один тип, а получили другой.
        val confusion = HashMap<Pair<Char, Char>, Int>()

        val confidenceWhenCorrect = mutableListOf<Double>()
        val confidenceWhenWrong = mutableListOf<Double>()

        for ((label, fen) in testPositions) {
            val image = renderBoardImage(fen, boardPixels = 480)
            val raw = readAllCells(image)
            assertNotNull("Доска не нашлась на синтетическом снимке: $label", raw)
            checkNotNull(raw)

            val expected = BoardLayout.parsePlacement(fen)

            for (index in 0 until 64) {
                totalCells++
                val expectedLetter = expected[index]
                val gotLetter = raw.letters[index]

                if (expectedLetter == gotLetter) correctCells++
                if (expectedLetter != null) {
                    if (expectedLetter == gotLetter) {
                        confidenceWhenCorrect.add(raw.confidence[index])
                    } else {
                        confidenceWhenWrong.add(raw.confidence[index])
                    }
                }

                val expectedOccupied = expectedLetter != null
                if (expectedOccupied) {
                    occupiedCells++
                    if (gotLetter != null) occupancyCorrect++
                }

                if (expectedOccupied && gotLetter != null) {
                    colorChecked++
                    val sameColor = expectedLetter!!.isUpperCase() == gotLetter.isUpperCase()
                    if (sameColor) colorCorrect++

                    if (sameColor) {
                        typeChecked++
                        val expectedType = expectedLetter.lowercaseChar()
                        val gotType = gotLetter.lowercaseChar()
                        if (expectedType == gotType) {
                            typeCorrect++
                        } else {
                            val key = expectedType to gotType
                            confusion[key] = (confusion[key] ?: 0) + 1
                        }
                    }
                }
            }
        }

        val overallAccuracy = correctCells.toDouble() / totalCells
        val occupancyAccuracy = occupancyCorrect.toDouble() / occupiedCells
        val colorAccuracy = colorCorrect.toDouble() / colorChecked
        val typeAccuracy = typeCorrect.toDouble() / typeChecked

        println("=== Реальная точность BoardRecognizer на синтетических досках ===")
        println("Клеток всего: $totalCells, точное совпадение (пусто/фигура+цвет+тип): $correctCells (" + percent(overallAccuracy) + ")")
        println("Из занятых клеток распознаны как занятые: $occupancyCorrect из $occupiedCells (" + percent(occupancyAccuracy) + ")")
        println("Цвет угадан верно (из тех, что распознаны занятыми): $colorCorrect из $colorChecked (" + percent(colorAccuracy) + ")")
        println("Тип фигуры угадан верно (из тех, где цвет верный): $typeCorrect из $typeChecked (" + percent(typeAccuracy) + ")")
        println()
        println("Точность по типам фигур:")
        for (type in listOf('k', 'q', 'r', 'b', 'n', 'p')) {
            reportPerType(type, testPositions, confusion)
        }
        println()
        println("Самые частые путаницы (ожидали -> получили: сколько раз):")
        confusion.entries.sortedByDescending { it.value }.take(10).forEach { (pair, count) ->
            println("  " + pair.first + " -> " + pair.second + ": " + count)
        }
        println()
        println(
            "Уверенность занятых клеток: верно распознанных в среднем " +
                percent(confidenceWhenCorrect.average()) + " (мин " + percent(confidenceWhenCorrect.min()) + "), " +
                "ошибочных в среднем " + percent(confidenceWhenWrong.averageOrZero()) +
                (if (confidenceWhenWrong.isNotEmpty()) " (макс " + percent(confidenceWhenWrong.max()) + ")" else "")
        )

        // Простые честные ограждения от регресса — не подогнаны под красивое
        // число, а построены от случайного угадывания.
        // На этой синтетике измерено 100% по всем трём показателям, поэтому ограждения
        // стоят высоко: их задача — поймать возврат старой беды (слон и ладья читались
        // как пешка), а не разрешить тихое сползание качества до уровня угадывания.
        assertTrue("Доля пусто/занято упала ниже измеренного ранее уровня", occupancyAccuracy > 0.95)
        assertTrue("Цвет фигуры упал ниже измеренного ранее уровня", colorAccuracy > 0.95)
        assertTrue("Тип фигуры упал ниже измеренного ранее уровня", typeAccuracy > 0.90)
    }

    private fun reportPerType(
        type: Char,
        positions: List<Pair<String, String>>,
        confusion: Map<Pair<Char, Char>, Int>,
    ) {
        var expectedTotal = 0
        for ((_, fen) in positions) {
            expectedTotal += BoardLayout.parsePlacement(fen).count { it != null && it.lowercaseChar() == type }
        }
        val wrong = confusion.entries.filter { it.key.first == type }.sumOf { it.value }
        val name = pieceRussianName(type)
        println("  $name: встретилась $expectedTotal раз, ошиблись в типе $wrong раз (из тех, где хотя бы цвет распознан верно)")
    }

    private fun pieceRussianName(type: Char): String = when (type) {
        'k' -> "король"
        'q' -> "ферзь"
        'r' -> "ладья"
        'b' -> "слон"
        'n' -> "конь"
        'p' -> "пешка"
        else -> type.toString()
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun percent(fraction: Double): String {
        val hundredths = (fraction * 10000).toInt()
        return (hundredths / 100).toString() + "." + (hundredths % 100).toString().padStart(2, '0') + "%"
    }

    /** Рисуем доску клетками двух цветов и фигуры символами Unicode покрупнее. */
    private fun renderBoardImage(fen: String, boardPixels: Int): GrayImage {
        val cellSize = boardPixels / 8
        val image = BufferedImage(boardPixels, boardPixels, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val isLight = (row + col) % 2 == 0
                g.color = if (isLight) lightColor else darkColor
                g.fillRect(col * cellSize, row * cellSize, cellSize, cellSize)
            }
        }

        val placement = BoardLayout.parsePlacement(fen)
        g.font = Font("SansSerif", Font.PLAIN, (cellSize * 0.8).toInt())
        val metrics = g.fontMetrics

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val piece = placement[row * 8 + col] ?: continue
                val glyph = solidGlyphs[piece.lowercaseChar()] ?: continue
                g.color = if (piece.isUpperCase()) Color.WHITE else Color.BLACK
                val textWidth = metrics.stringWidth(glyph)
                val x = col * cellSize + (cellSize - textWidth) / 2
                val y = row * cellSize + cellSize / 2 + metrics.ascent / 2 - metrics.descent / 4
                g.drawString(glyph, x, y)
            }
        }
        g.dispose()

        val pixels = IntArray(boardPixels * boardPixels)
        for (y in 0 until boardPixels) {
            for (x in 0 until boardPixels) {
                pixels[y * boardPixels + x] = image.getRGB(x, y) and 0xFFFFFF
            }
        }
        return GrayImage(boardPixels, boardPixels, pixels)
    }

    @Test
    fun `итоговый recognize отдаёт валидный FEN на стартовой позиции`() {
        val image = renderBoardImage(startingPosition, boardPixels = 480)
        val result = BoardRecognizer.recognize(image, whiteToMove = true)
        println("recognize() на стартовой позиции: $result")
        // Стартовая позиция на чистом синтетическом снимке распознаётся без единой
        // ошибки (см. основной тест точности), и если при этом функция отвечает
        // "не уверен", она не работает вообще ни на каком входе. Поэтому здесь
        // требуется именно Recognized с точной расстановкой, а не "хоть что-то".
        val recognized = result as? RecognitionResult.Recognized
        assertNotNull("На чистом снимке стартовой позиции ожидали Recognized, получили $result", recognized)
        assertTrue(
            "Расстановка распознана неверно: " + recognized!!.fen,
            recognized.fen.startsWith(startingPosition + " w "),
        )
    }

    @Test
    fun `невозможная расстановка с двумя королями уходит в NotSure а не показывается как есть`() {
        // Два белых короля — фигуры, которые сами по себе распознаются почти
        // без ошибок (см. основной тест), поэтому здесь ожидаем NotSure в любом
        // случае: либо от порога уверенности, либо от проверки легальности —
        // но никогда не тихий Recognized с невозможной позицией. Сама ветка
        // проверки легальности проверяется отдельно, напрямую, ниже.
        val illegalFen = "8/8/3k4/8/2N1n3/8/2K1K3/8"
        val image = renderBoardImage(illegalFen, boardPixels = 480)
        val result = BoardRecognizer.recognize(image, whiteToMove = true)
        println("recognize() на позиции с двумя королями: $result")

        assertTrue(
            "Невозможная расстановка не должна тихо превращаться в Recognized",
            result is RecognitionResult.NotSure,
        )
    }

    @Test
    fun `validateLegalPlacement ловит лишних королей, лишние пешки и пешку на краю`() {
        val legal = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1"
        assertTrue("Обычная стартовая позиция не должна считаться невозможной", validateLegalPlacement(legal) == null)

        val twoWhiteKings = "8/8/3k4/8/8/8/2K1K3/8 w - - 0 1"
        val twoKingsProblem = validateLegalPlacement(twoWhiteKings)
        assertNotNull("Два белых короля должны считаться невозможной позицией", twoKingsProblem)
        assertTrue(twoKingsProblem!!.reason.contains("корол"))

        val ninePawns = "8/8/8/8/8/P7/PPPPPPPP/4K2k w - - 0 1"
        val ninePawnsProblem = validateLegalPlacement(ninePawns)
        assertNotNull("Девять пешек одного цвета должны считаться невозможной позицией", ninePawnsProblem)
        assertTrue(ninePawnsProblem!!.reason.contains("пешек"))

        val pawnOnBackRank = "P3k2K/8/8/8/8/8/8/8 w - - 0 1"
        val pawnOnBackRankProblem = validateLegalPlacement(pawnOnBackRank)
        assertNotNull("Пешка на восьмой горизонтали должна считаться невозможной", pawnOnBackRankProblem)
        assertTrue(pawnOnBackRankProblem!!.reason.contains("горизонтал"))
    }
}
