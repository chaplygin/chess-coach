package org.chesscoach.core.vision

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import org.chesscoach.core.BoardLayout

/**
 * Рисование синтетической доски — общее для всех тестов распознавания.
 *
 * Общее намеренно: таблица эталонов калибруется на этих картинках, и точность
 * меряется на них же. Как только у калибровки и у замера окажутся два разных
 * рисовальщика, эталоны будут описывать одни картинки, а проверяться на
 * других — ровно эта ошибка уже стоила путаницы ферзя с королём.
 */
object SyntheticBoard {

    private val lightColor = Color(0xED, 0xD6, 0xB0)
    private val darkColor = Color(0xB5, 0x88, 0x63)

    /**
     * Сплошные силуэты Unicode для обеих сторон, а не пара "полый контур /
     * заливка", которой :app рисует доску на экране: настоящая белая фигура на
     * фотографии — такой же сплошной силуэт, просто светлее фона.
     */
    private val solidGlyphs = mapOf(
        'k' to "♚", 'q' to "♛", 'r' to "♜",
        'b' to "♝", 'n' to "♞", 'p' to "♟",
    )

    /** Доля высоты клетки, которую занимает самая высокая фигура набора. */
    private const val TALLEST_PIECE_IN_CELL = 0.82

    fun render(placementFen: String, boardPixels: Int = 480): GrayImage {
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

        // Размер шрифта подбирается по самому высокому глифу набора, а не берётся
        // долей от клетки. Разные шрифты рисуют шахматные символы в разном
        // масштабе, и на сборочной машине они вылезали за клетку — обрезка по
        // краю делала все шесть фигур одинаковой высоты, то есть уничтожала
        // единственный признак, отличающий пешку от слона. Общий множитель для
        // всех фигур сохраняет разницу в росте между ними, как на настоящем
        // наборе, и гарантирует, что ни одна не обрежется.
        g.font = Font("SansSerif", Font.PLAIN, fittingFontSize(g, cellSize))
        val metrics = g.fontMetrics

        val placement = BoardLayout.parsePlacement(placementFen)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val piece = placement[row * 8 + col] ?: continue
                val glyph = solidGlyphs.getValue(piece.lowercaseChar())
                g.color = if (piece.isUpperCase()) Color.WHITE else Color.BLACK
                val bounds = g.font.createGlyphVector(g.fontRenderContext, glyph).visualBounds
                // Глиф ставится по центру клетки по своему видимому прямоугольнику,
                // а не по базовой линии: у шахматных символов она в разных шрифтах
                // проходит по-разному, и фигура уезжала бы к краю.
                val x = col * cellSize + (cellSize - bounds.width) / 2 - bounds.x
                val y = row * cellSize + (cellSize - bounds.height) / 2 - bounds.y
                g.drawString(glyph, x.toFloat(), y.toFloat())
                // metrics нужен только чтобы шрифт точно инициализировался до
                // первого вызова createGlyphVector на некоторых JDK.
                metrics.height
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

    private fun fittingFontSize(g: java.awt.Graphics2D, cellSize: Int): Int {
        val probeSize = 100
        val probe = Font("SansSerif", Font.PLAIN, probeSize)
        val tallest = solidGlyphs.values.maxOf { glyph ->
            probe.createGlyphVector(g.fontRenderContext, glyph).visualBounds.height
        }
        if (tallest <= 0.0) return (cellSize * 0.8).toInt()
        return (probeSize * cellSize * TALLEST_PIECE_IN_CELL / tallest).toInt().coerceAtLeast(8)
    }
}
