package org.chesscoach.core.vision

import java.awt.Color
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import org.chesscoach.core.BoardLayout

/**
 * Рисование синтетической доски — общее для всех тестов распознавания.
 *
 * Фигуры заданы вертикальным профилем: по высоте силуэта выписаны полуширины,
 * и из них собирается симметричный контур. Числа профилей подобраны так, чтобы
 * повторить пропорции настоящего набора Lichess, измеренные на присланном
 * скриншоте (core/src/test/resources/vision/lichess-screenshot.png): рост от
 * низа клетки, ширина, ширина в верхней, средней и нижней трети, доля
 * заполнения рамки.
 *
 * Так сделано после дорогой ошибки. Раньше силуэты рисовались "на глаз", и на
 * них же измерялись эталоны распознавания: получалась замкнутая система, где
 * код проверялся на картинках, нарисованных под него. На первом же настоящем
 * скриншоте всё посыпалось — у выдуманного набора пешка была вдвое ниже
 * короля, а у настоящего они почти одного роста, и все фигуры прочитались как
 * короли и ферзи. Теперь эталоны берутся с настоящего снимка, а этот
 * рисовальщик обязан им соответствовать — за этим следит SyntheticSetTest.
 *
 * Фигуры стоят на своей клетке, то есть прижаты к её низу, а не висят по
 * центру: и на экране, и на фотографии основание фигуры находится внизу
 * клетки, а различаются фигуры макушкой.
 */
object SyntheticBoard {

    private val lightColor = Color(0xED, 0xD6, 0xB0)
    private val darkColor = Color(0xB5, 0x88, 0x63)

    /** Рост фигуры от низа клетки, в долях высоты клетки. */
    private val pieceHeight = mapOf(
        'k' to 0.97, 'q' to 0.985, 'b' to 0.985, 'n' to 0.93, 'r' to 0.90, 'p' to 0.90,
    )

    /** Ширина фигуры в долях ширины клетки. */
    private val pieceWidth = mapOf(
        'k' to 0.822, 'q' to 0.920, 'b' to 0.870, 'n' to 0.760, 'r' to 0.685, 'p' to 0.590,
    )

    /**
     * Вертикальный профиль: (доля высоты сверху вниз, полуширина в долях
     * ширины фигуры). Полуширина 0.5 — фигура во всю свою ширину.
     */
    private val profiles = mapOf(
        // Пешка: круглая голова, узкая шейка, широкое основание.
        'p' to listOf(
            0.00 to 0.03, 0.08 to 0.24, 0.15 to 0.275, 0.24 to 0.25,
            0.32 to 0.15, 0.44 to 0.16, 0.58 to 0.30, 0.66 to 0.374,
            0.82 to 0.42, 0.93 to 0.45, 0.96 to 0.50, 1.00 to 0.50,
        ),
        // Ладья: широкие зубцы сверху, ровный корпус, широкое основание.
        'r' to listOf(
            0.00 to 0.43, 0.16 to 0.43, 0.22 to 0.35, 0.34 to 0.33,
            0.66 to 0.33, 0.74 to 0.38, 0.86 to 0.43, 0.92 to 0.50, 1.00 to 0.50,
        ),
        // Слон: узкая митра на высокой ножке и очень широкое основание.
        'b' to listOf(
            0.00 to 0.04, 0.08 to 0.16, 0.18 to 0.234, 0.28 to 0.20,
            0.36 to 0.12, 0.50 to 0.13, 0.62 to 0.267, 0.70 to 0.22,
            0.80 to 0.26, 0.90 to 0.40, 0.95 to 0.50, 1.00 to 0.50,
        ),
        // Король: крест на макушке, широкие "плечи" посередине, основание уже них.
        'k' to listOf(
            0.00 to 0.05, 0.09 to 0.05, 0.10 to 0.17, 0.15 to 0.17,
            0.16 to 0.05, 0.22 to 0.06, 0.26 to 0.40, 0.33 to 0.36,
            0.45 to 0.46, 0.55 to 0.50, 0.66 to 0.45, 0.72 to 0.34,
            0.86 to 0.30, 0.92 to 0.39, 1.00 to 0.39,
        ),
        // Ферзь: корона из зубцов поверху, узкая талия, основание уже короны.
        'q' to listOf(
            0.00 to 0.50, 0.10 to 0.50, 0.20 to 0.44, 0.30 to 0.44,
            0.40 to 0.42, 0.50 to 0.28, 0.62 to 0.30, 0.74 to 0.33,
            0.86 to 0.29, 0.93 to 0.35, 1.00 to 0.35,
        ),
        // Конь рисуется отдельно: он единственный несимметричный.
        'n' to listOf(
            0.00 to 0.35, 0.20 to 0.39, 0.40 to 0.39, 0.60 to 0.36,
            0.80 to 0.42, 0.90 to 0.47, 1.00 to 0.475,
        ),
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
                val width = cellSize * pieceWidth.getValue(kind)
                val x = col * cellSize + (cellSize - width) / 2
                val y = (row + 1) * cellSize - height
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

    /**
     * Вырезы, идущие от макушки вниз: (смещение центра от оси, полуширина,
     * глубина) — всё в долях размера фигуры.
     *
     * Ими держится "воздух" внутри рамки силуэта: у настоящего ферзя корона
     * состоит из отдельных зубцов, и просветы между ними занимают заметную
     * часть верхней трети. Без вырезов нарисованный ферзь получался сплошным
     * пятном и по заполнению рамки становился похож на ладью.
     */
    private val notches = mapOf(
        'q' to listOf(
            -0.34 to 0.05, -0.12 to 0.05, 0.12 to 0.05, 0.34 to 0.05,
        ),
    )

    private const val NOTCH_DEPTH = 0.34

    private fun silhouette(kind: Char, x: Double, y: Double, w: Double, h: Double): Shape {
        val profile = profiles.getValue(kind)
        val body = if (kind == 'n') knight(profile, x, y, w, h) else symmetric(profile, x, y, w, h)
        val cuts = notches[kind] ?: return body

        val area = Area(body)
        for ((offset, half) in cuts) {
            val cx = x + w / 2 + offset * w
            val cut = Path2D.Double()
            cut.moveTo(cx - half * w, y - h * 0.02)
            cut.lineTo(cx + half * w, y - h * 0.02)
            cut.lineTo(cx + half * w * 0.4, y + NOTCH_DEPTH * h)
            cut.lineTo(cx - half * w * 0.4, y + NOTCH_DEPTH * h)
            cut.closePath()
            area.subtract(Area(cut))
        }
        return area
    }

    /** Симметричный силуэт: профиль отражается относительно вертикальной оси. */
    private fun symmetric(
        profile: List<Pair<Double, Double>>,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Shape {
        val path = Path2D.Double()
        val center = x + w / 2
        path.moveTo(center - profile.first().second * w, y + profile.first().first * h)
        for ((ty, half) in profile) path.lineTo(center - half * w, y + ty * h)
        for ((ty, half) in profile.reversed()) path.lineTo(center + half * w, y + ty * h)
        path.closePath()
        return Area(path)
    }

    /**
     * Конь: тот же профиль, но верхняя половина сдвинута вбок.
     *
     * Асимметрия здесь не украшение, а единственный признак, по которому конь
     * отличим от любой другой фигуры в любом наборе: он один смотрит вбок.
     */
    private fun knight(
        profile: List<Pair<Double, Double>>,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Shape {
        val path = Path2D.Double()
        val center = x + w / 2
        fun shift(ty: Double) = if (ty < 0.55) w * 0.16 * (0.55 - ty) / 0.55 else 0.0

        path.moveTo(center - profile.first().second * w + shift(profile.first().first), y)
        for ((ty, half) in profile) path.lineTo(center - half * w + shift(ty), y + ty * h)
        for ((ty, half) in profile.reversed()) path.lineTo(center + half * w + shift(ty) * 2.0, y + ty * h)
        path.closePath()
        return Area(path)
    }
}
