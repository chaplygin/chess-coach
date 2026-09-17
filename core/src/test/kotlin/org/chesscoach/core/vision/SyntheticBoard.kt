package org.chesscoach.core.vision

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import org.chesscoach.core.BoardLayout

/**
 * Рисование синтетической доски — общее для всех тестов распознавания.
 *
 * Фигуры рисуются своими силуэтами, а не символами шрифта. Причина найдена
 * сборкой: на сборочной машине в системном шрифте шахматных символов нет
 * вовсе, и доска получалась из одинаковых пустых прямоугольников — тест мерил
 * что угодно, кроме распознавания фигур. Свои силуэты одинаковы на любой
 * машине, и калибровка эталонов перестаёт зависеть от того, какие шрифты
 * стоят в системе.
 *
 * Пропорции взяты как у обычного клубного набора: пешка самая низкая, за ней
 * ладья и конь, выше слон и ферзь, самый высокий король. Именно на разнице в
 * росте держится различение пешки и слона.
 *
 * Рисовальщик один на калибровку и на замер точности: как только их станет
 * два, эталоны будут описывать одни картинки, а проверяться на других — ровно
 * эта ошибка уже стоила путаницы ферзя с королём.
 */
object SyntheticBoard {

    private val lightColor = Color(0xED, 0xD6, 0xB0)
    private val darkColor = Color(0xB5, 0x88, 0x63)

    /**
     * Высота фигуры в долях клетки. Самая высокая укладывается внутри клетки
     * с запасом: распознаватель берёт клетку с отступом в 10% от края, и фигура
     * крупнее обрезалась бы по этой рамке — король с ферзём стали бы одного роста.
     * В шахматных диаграммах и на экранах фигуры так и рисуют — внутри своей клетки.
     */
    private val pieceHeight = mapOf(
        'k' to 0.76, 'q' to 0.70, 'b' to 0.64, 'n' to 0.58, 'r' to 0.54, 'p' to 0.44,
    )

    fun render(placementFen: String, boardPixels: Int = 480): GrayImage {
        val cellSize = boardPixels / 8
        val image = BufferedImage(boardPixels, boardPixels, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                g.color = if ((row + col) % 2 == 0) lightColor else darkColor
                g.fillRect(col * cellSize, row * cellSize, cellSize, cellSize)
            }
        }

        val placement = BoardLayout.parsePlacement(placementFen)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val piece = placement[row * 8 + col] ?: continue
                val kind = piece.lowercaseChar()
                val height = cellSize * pieceHeight.getValue(kind)
                val width = height * 0.72
                val x = col * cellSize + (cellSize - width) / 2
                val y = row * cellSize + (cellSize - height) / 2
                // Настоящая белая фигура на фотографии — такой же сплошной
                // силуэт, как чёрная, просто светлее фона, а не полый контур.
                g.color = if (piece.isUpperCase()) Color.WHITE else Color.BLACK
                g.fill(silhouette(kind, x, y, width, height))
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

    private fun silhouette(kind: Char, x: Double, y: Double, w: Double, h: Double): Shape = when (kind) {
        'p' -> pawn(x, y, w, h)
        'b' -> bishop(x, y, w, h)
        'n' -> knight(x, y, w, h)
        'r' -> rook(x, y, w, h)
        'q' -> queen(x, y, w, h)
        else -> king(x, y, w, h)
    }

    /** Общее для всех фигур основание: широкая устойчивая подставка. */
    private fun base(x: Double, y: Double, w: Double, h: Double): Path2D.Double {
        val path = Path2D.Double()
        path.moveTo(x, y + h)
        path.lineTo(x + w, y + h)
        path.lineTo(x + w * 0.82, y + h * 0.88)
        path.lineTo(x + w * 0.18, y + h * 0.88)
        path.closePath()
        return path
    }

    private fun pawn(x: Double, y: Double, w: Double, h: Double): Shape {
        val area = Area(base(x, y, w, h))
        area.add(Area(Ellipse2D.Double(x + w * 0.28, y, w * 0.44, h * 0.4)))
        val body = Path2D.Double()
        body.moveTo(x + w * 0.38, y + h * 0.3)
        body.lineTo(x + w * 0.62, y + h * 0.3)
        body.lineTo(x + w * 0.78, y + h * 0.9)
        body.lineTo(x + w * 0.22, y + h * 0.9)
        body.closePath()
        area.add(Area(body))
        return area
    }

    private fun bishop(x: Double, y: Double, w: Double, h: Double): Shape {
        val area = Area(base(x, y, w, h))
        val body = Path2D.Double()
        body.moveTo(x + w * 0.5, y)
        body.lineTo(x + w * 0.74, y + h * 0.36)
        body.lineTo(x + w * 0.66, y + h * 0.52)
        body.lineTo(x + w * 0.8, y + h * 0.9)
        body.lineTo(x + w * 0.2, y + h * 0.9)
        body.lineTo(x + w * 0.34, y + h * 0.52)
        body.lineTo(x + w * 0.26, y + h * 0.36)
        body.closePath()
        area.add(Area(body))
        // Прорезь митры — деталь, из-за которой силуэт слона это не просто
        // высокая пешка.
        val slit = Path2D.Double()
        slit.moveTo(x + w * 0.46, y + h * 0.14)
        slit.lineTo(x + w * 0.62, y + h * 0.34)
        slit.lineTo(x + w * 0.54, y + h * 0.38)
        slit.lineTo(x + w * 0.38, y + h * 0.18)
        slit.closePath()
        area.subtract(Area(slit))
        return area
    }

    private fun knight(x: Double, y: Double, w: Double, h: Double): Shape {
        val area = Area(base(x, y, w, h))
        val head = Path2D.Double()
        head.moveTo(x + w * 0.22, y + h * 0.9)
        head.lineTo(x + w * 0.3, y + h * 0.42)
        head.lineTo(x + w * 0.12, y + h * 0.3)
        head.lineTo(x + w * 0.34, y + h * 0.06)
        head.lineTo(x + w * 0.52, y)
        head.lineTo(x + w * 0.86, y + h * 0.24)
        head.lineTo(x + w * 0.8, y + h * 0.9)
        head.closePath()
        area.add(Area(head))
        return area
    }

    private fun rook(x: Double, y: Double, w: Double, h: Double): Shape {
        val area = Area(base(x, y, w, h))
        val body = Path2D.Double()
        body.moveTo(x + w * 0.24, y + h * 0.22)
        body.lineTo(x + w * 0.76, y + h * 0.22)
        body.lineTo(x + w * 0.82, y + h * 0.9)
        body.lineTo(x + w * 0.18, y + h * 0.9)
        body.closePath()
        area.add(Area(body))
        area.add(Area(Rectangle2D.Double(x + w * 0.12, y, w * 0.76, h * 0.22)))
        // Зубцы: два выреза сверху — то, по чему ладью узнают с первого взгляда.
        area.subtract(Area(Rectangle2D.Double(x + w * 0.3, y - 1, w * 0.14, h * 0.13)))
        area.subtract(Area(Rectangle2D.Double(x + w * 0.56, y - 1, w * 0.14, h * 0.13)))
        return area
    }

    private fun queen(x: Double, y: Double, w: Double, h: Double): Shape {
        val area = Area(base(x, y, w, h))
        val body = Path2D.Double()
        body.moveTo(x + w * 0.5, y + h * 0.1)
        body.lineTo(x + w * 0.72, y + h * 0.3)
        body.lineTo(x + w * 0.82, y + h * 0.9)
        body.lineTo(x + w * 0.18, y + h * 0.9)
        body.lineTo(x + w * 0.28, y + h * 0.3)
        body.closePath()
        area.add(Area(body))
        // Корона из трёх зубцов с просветами между ними.
        for (offset in listOf(0.14, 0.43, 0.72)) {
            area.add(Area(Ellipse2D.Double(x + w * offset, y, w * 0.14, h * 0.16)))
        }
        return area
    }

    private fun king(x: Double, y: Double, w: Double, h: Double): Shape {
        val area = Area(base(x, y, w, h))
        val body = Path2D.Double()
        body.moveTo(x + w * 0.5, y + h * 0.18)
        body.lineTo(x + w * 0.74, y + h * 0.36)
        body.lineTo(x + w * 0.84, y + h * 0.9)
        body.lineTo(x + w * 0.16, y + h * 0.9)
        body.lineTo(x + w * 0.26, y + h * 0.36)
        body.closePath()
        area.add(Area(body))
        // Крест на макушке: узкая вершина поверх самого высокого силуэта.
        val stroke = BasicStroke((w * 0.12).toFloat())
        val vertical = Path2D.Double()
        vertical.moveTo(x + w * 0.5, y)
        vertical.lineTo(x + w * 0.5, y + h * 0.22)
        area.add(Area(stroke.createStrokedShape(vertical)))
        val horizontal = Path2D.Double()
        horizontal.moveTo(x + w * 0.34, y + h * 0.09)
        horizontal.lineTo(x + w * 0.66, y + h * 0.09)
        area.add(Area(stroke.createStrokedShape(horizontal)))
        return area
    }
}
