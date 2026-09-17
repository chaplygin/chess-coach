package org.chesscoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import org.chesscoach.core.BoardLayout
import org.chesscoach.coach.model.ArrowStyle
import org.chesscoach.coach.model.BoardArrow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val LIGHT_SQUARE = Color(0xFFEDD6B0)
private val DARK_SQUARE = Color(0xFFB58863)
private val LAST_MOVE_TINT = Color(0x8FCDD26A)

// Подписи координат должны быть контрастны клетке, на которой лежат,
// а не самой доске: своя пара цветов для светлой и тёмной клетки.
private val COORDINATE_LABEL_ON_LIGHT = Color(0xFF3E2C1C)
private val COORDINATE_LABEL_ON_DARK = Color(0xFFF5EBDD)

// Цвет стрелки зависит только от её смысла (ArrowStyle), не от вызывающего
// кода: так экран разбора и легенда под доской не могут разъехаться в цвете.
private val BEST_MOVE_ARROW_COLOR = Color(0xFF1B7F37)
private val PLAYED_MOVE_ARROW_COLOR = Color(0xFF757575)
private val THREAT_ARROW_COLOR = Color(0xFFD32F2F)
private val CONSEQUENCE_ARROW_COLOR = Color(0xFF1B7F37)

private const val ARROW_ALPHA = 0.80f
private const val CONSEQUENCE_ALPHA = 0.65f

// Стрелка следствия тоньше и с более коротким наконечником — она поясняет
// лучший ход, а не спорит с ним за внимание.
private const val ARROW_STROKE_FACTOR = 0.16f
private const val CONSEQUENCE_STROKE_FACTOR = 0.08f
private const val ARROW_HEAD_FACTOR = 0.38f
private const val CONSEQUENCE_HEAD_FACTOR = 0.28f
private const val DASH_ON_FACTOR = 0.14f
private const val DASH_OFF_FACTOR = 0.10f

/**
 * Фигуры символами Unicode, а не картинками.
 *
 * Двенадцать векторных файлов пришлось бы рисовать вслепую, без возможности
 * посмотреть на результат, тогда как символы масштабируются сами и дают
 * одинаковый вид на любом размере доски.
 */
private val PIECE_GLYPHS = mapOf(
    'K' to "♔", 'Q' to "♕", 'R' to "♖",
    'B' to "♗", 'N' to "♘", 'P' to "♙",
    'k' to "♚", 'q' to "♛", 'r' to "♜",
    'b' to "♝", 'n' to "♞", 'p' to "♟",
)

@Composable
fun ChessBoard(
    fen: String,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    lastMoveFrom: String? = null,
    lastMoveTo: String? = null,
    arrows: List<BoardArrow> = emptyList(),
) {
    val squares = remember(fen) { BoardLayout.parsePlacement(fen) }
    val highlight = remember(lastMoveFrom, lastMoveTo) {
        setOfNotNull(
            lastMoveFrom?.let(BoardLayout::squareIndex),
            lastMoveTo?.let(BoardLayout::squareIndex),
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        // Размер символа считается от размера клетки, иначе на планшете фигуры
        // окажутся крошечными, а на узком экране вылезут за края.
        val glyphSize = (maxWidth.value / 8f * 0.74f).sp
        val coordinateFontSize = (maxWidth.value / 8f * 0.22f).sp

        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 8) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (column in 0 until 8) {
                        val index = boardIndex(row, column, flipped)
                        val isLight = (row + column) % 2 == 0
                        val coordinateColor = if (isLight) COORDINATE_LABEL_ON_LIGHT else COORDINATE_LABEL_ON_DARK

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(if (isLight) LIGHT_SQUARE else DARK_SQUARE)
                                .then(
                                    if (index in highlight) Modifier.background(LAST_MOVE_TINT)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            squares[index]?.let { piece ->
                                Text(
                                    text = PIECE_GLYPHS[piece].orEmpty(),
                                    style = TextStyle(
                                        fontSize = glyphSize,
                                        textAlign = TextAlign.Center,
                                        color = if (piece.isUpperCase()) Color.White else Color.Black,
                                    ),
                                )
                            }

                            // Нижняя displayed-строка несёт буквы файлов, левый
                            // displayed-столбец — номера горизонталей. Оба
                            // разворачиваются вместе с доской через flipped.
                            if (row == 7) {
                                Text(
                                    text = fileLabel(column, flipped).toString(),
                                    style = TextStyle(fontSize = coordinateFontSize, color = coordinateColor),
                                    modifier = Modifier.align(Alignment.BottomStart).padding(1.dp),
                                )
                            }
                            if (column == 0) {
                                Text(
                                    text = rankLabel(row, flipped).toString(),
                                    style = TextStyle(fontSize = coordinateFontSize, color = coordinateColor),
                                    modifier = Modifier.align(Alignment.TopStart).padding(1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Стрелки рисуются поверх клеток, чтобы не зависеть от их раскладки.
        // Порядок — тот, что вернул BoardMarks.of: последняя стрелка в списке
        // (лучший ход) ложится поверх остальных.
        if (arrows.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                val cell = size.width / 8f
                arrows.forEach { arrow ->
                    val fromIndex = BoardLayout.squareIndex(arrow.from)
                    val toIndex = BoardLayout.squareIndex(arrow.to)
                    if (fromIndex != null && toIndex != null) {
                        val start = squareCenter(fromIndex, cell, flipped)
                        val end = squareCenter(toIndex, cell, flipped)
                        drawMoveArrow(start, end, cell, arrow.style)
                    }
                }
            }
        }
    }
}

private fun boardIndex(row: Int, column: Int, flipped: Boolean): Int =
    if (flipped) (7 - row) * 8 + (7 - column) else row * 8 + column

/** Буква файла, которую видит зритель в этом displayed-столбце. */
private fun fileLabel(column: Int, flipped: Boolean): Char =
    'a' + (if (flipped) 7 - column else column)

/** Номер горизонтали, который видит зритель в этой displayed-строке. */
private fun rankLabel(row: Int, flipped: Boolean): Int =
    if (flipped) row + 1 else 8 - row

private fun squareCenter(index: Int, cell: Float, flipped: Boolean): Offset {
    val row = index / 8
    val column = index % 8
    val displayRow = if (flipped) 7 - row else row
    val displayColumn = if (flipped) 7 - column else column
    return Offset((displayColumn + 0.5f) * cell, (displayRow + 0.5f) * cell)
}

/** Цвет стрелки данного вида — используется и доской, и легендой под ней. */
fun arrowLegendColor(style: ArrowStyle): Color = when (style) {
    ArrowStyle.BEST_MOVE -> BEST_MOVE_ARROW_COLOR
    ArrowStyle.PLAYED_MOVE -> PLAYED_MOVE_ARROW_COLOR
    ArrowStyle.THREAT -> THREAT_ARROW_COLOR
    ArrowStyle.CONSEQUENCE -> CONSEQUENCE_ARROW_COLOR
}

/** Пунктирная ли стрелка данного вида — тоже общее знание доски и легенды. */
fun arrowLegendDashed(style: ArrowStyle): Boolean = style == ArrowStyle.CONSEQUENCE

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoveArrow(
    start: Offset,
    end: Offset,
    cell: Float,
    style: ArrowStyle,
) {
    val dashed = arrowLegendDashed(style)
    val baseColor = arrowLegendColor(style)
    val color = baseColor.copy(alpha = if (dashed) CONSEQUENCE_ALPHA else ARROW_ALPHA)
    val strokeWidth = cell * (if (dashed) CONSEQUENCE_STROKE_FACTOR else ARROW_STROKE_FACTOR)
    val headLength = cell * (if (dashed) CONSEQUENCE_HEAD_FACTOR else ARROW_HEAD_FACTOR)
    val pathEffect = if (dashed) {
        PathEffect.dashPathEffect(floatArrayOf(cell * DASH_ON_FACTOR, cell * DASH_OFF_FACTOR))
    } else {
        null
    }

    val angle = atan2(end.y - start.y, end.x - start.x)
    // Линия не доходит до центра поля, чтобы наконечник не накрыл фигуру.
    val shaftEnd = Offset(
        end.x - cos(angle) * headLength * 0.8f,
        end.y - sin(angle) * headLength * 0.8f,
    )

    drawLine(
        color = color,
        start = start,
        end = shaftEnd,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
        pathEffect = pathEffect,
    )

    val spread = 0.45f
    val head = Path().apply {
        moveTo(end.x, end.y)
        lineTo(
            end.x - cos(angle - spread) * headLength,
            end.y - sin(angle - spread) * headLength,
        )
        lineTo(
            end.x - cos(angle + spread) * headLength,
            end.y - sin(angle + spread) * headLength,
        )
        close()
    }
    drawPath(head, color)
}
