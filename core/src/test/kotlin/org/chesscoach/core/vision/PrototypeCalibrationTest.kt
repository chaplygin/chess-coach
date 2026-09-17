package org.chesscoach.core.vision

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Пересчёт таблицы эталонов силуэтов тем же кодом, который потом с ней
 * сравнивает реальные клетки.
 *
 * Тест нужен не ради цифр в выводе, а как ограждение: если кто-то поменяет
 * обрезку клетки, порог бинаризации или набор признаков, таблица в
 * BoardRecognizer молча перестанет соответствовать тому, что меряет
 * продакшен-путь, и распознавание испортится без единого падающего теста.
 * Ровно так уже случилось один раз — ферзь с королём путались почти всегда,
 * потому что эталоны считал отдельный скрипт с другими порогами.
 */
class PrototypeCalibrationTest {

    private val lightColor = Color(0xED, 0xD6, 0xB0)
    private val darkColor = Color(0xB5, 0x88, 0x63)

    private val solidGlyphs = mapOf(
        'k' to "♚", 'q' to "♛", 'r' to "♜",
        'b' to "♝", 'n' to "♞", 'p' to "♟",
    )

    @Test
    fun `таблица эталонов совпадает с тем, что меряет сам распознаватель`() {
        println("=== Пересчитанные эталоны (вставлять в PROTOTYPES как есть) ===")

        val measured = mutableMapOf<Char, DoubleArray>()
        for (piece in listOf('k', 'q', 'r', 'b', 'n', 'p')) {
            val signatures = cellSignatures(renderCalibrationBoard(piece)).filterNotNull()
            // Каждая фигура ставится на 16 клеток — поровну белых и чёрных,
            // поровну на светлых и тёмных полях, поэтому среднее не перекошено
            // ни фоном клетки, ни цветом самой фигуры.
            assertEquals("Ожидали 16 занятых клеток на калибровочной доске '$piece'", 16, signatures.size)

            val average = DoubleArray(FEATURE_COUNT) { i -> signatures.sumOf { it[i] } / signatures.size }
            measured[piece] = average
            println("    " + prototypeLine(piece, average))
        }

        // Сами эталоны обязаны распознаваться как самих себя: если усреднённый
        // силуэт слона ближе к пешке, чем к слону, таблица бесполезна, каким бы
        // ни было её содержимое.
        for ((piece, average) in measured) {
            val nearest = measured.minByOrNull { (_, other) -> distance(average, other) }!!.key
            assertEquals("Эталон '$piece' ближе к чужому эталону '$nearest', чем к себе", piece, nearest)
        }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum
    }

    private fun prototypeLine(piece: Char, values: DoubleArray): String {
        val name = when (piece) {
            'k' -> "KING"
            'q' -> "QUEEN"
            'r' -> "ROOK"
            'b' -> "BISHOP"
            'n' -> "KNIGHT"
            else -> "PAWN"
        }
        val numbers = values.joinToString(", ") { String.format(Locale.ROOT, "%.3f", it) }
        return "Prototype(RecognizedPieceType.$name, $numbers),"
    }

    /** Доска с одной и той же фигурой на 16 клетках: оба цвета фигуры, обе чётности клетки. */
    private fun renderCalibrationBoard(piece: Char): GrayImage {
        val boardPixels = 480
        val cellSize = boardPixels / 8
        val image = BufferedImage(boardPixels, boardPixels, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                g.color = if ((row + col) % 2 == 0) lightColor else darkColor
                g.fillRect(col * cellSize, row * cellSize, cellSize, cellSize)
            }
        }

        g.font = Font("SansSerif", Font.PLAIN, (cellSize * 0.8).toInt())
        val metrics = g.fontMetrics
        val glyph = solidGlyphs.getValue(piece)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                // Фигура ставится на каждую четвёртую клетку: реже — и доска
                // остаётся узнаваемой по чередованию фона (иначе поиск доски
                // не найдёт её вовсе), но столбцы 0 и 4 дают обе чётности
                // клетки, а верхняя и нижняя половины — оба цвета фигуры.
                if (col % 4 != 0) continue
                g.color = if (row < 4) Color.WHITE else Color.BLACK
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
}

private const val FEATURE_COUNT = 8
