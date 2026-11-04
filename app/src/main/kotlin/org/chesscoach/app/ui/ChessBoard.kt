package org.chesscoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import org.chesscoach.app.R
import org.chesscoach.core.BoardLayout
import org.chesscoach.coach.model.ArrowStyle
import org.chesscoach.coach.model.BoardArrow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Цвета доски Lichess.
private val LIGHT_SQUARE = Color(0xFFF0D9B5)
private val DARK_SQUARE = Color(0xFFB58863)
private val LAST_MOVE_TINT = Color(0x699BC700)

// Прошлый ход соперника: куда пришёл — серым, откуда — бледнее.
private val PREVIOUS_TO_TINT = Color(0x66707070)
private val PREVIOUS_FROM_TINT = Color(0x33707070)
private val SELECTED_TINT = Color(0x8014551E)
private val TARGET_DOT = Color(0x4D14551E)

// Подпись координаты берёт цвет противоположной клетки, как на Lichess.
private val COORDINATE_LABEL_ON_LIGHT = DARK_SQUARE
private val COORDINATE_LABEL_ON_DARK = LIGHT_SQUARE

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

/** Набор фигур cburnett, тот же, что на Lichess по умолчанию. */
private val PIECE_DRAWABLES = mapOf(
    'K' to R.drawable.piece_wk, 'Q' to R.drawable.piece_wq, 'R' to R.drawable.piece_wr,
    'B' to R.drawable.piece_wb, 'N' to R.drawable.piece_wn, 'P' to R.drawable.piece_wp,
    'k' to R.drawable.piece_bk, 'q' to R.drawable.piece_bq, 'r' to R.drawable.piece_br,
    'b' to R.drawable.piece_bb, 'n' to R.drawable.piece_bn, 'p' to R.drawable.piece_bp,
)

@Composable
fun ChessBoard(
    fen: String,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    lastMoveFrom: String? = null,
    lastMoveTo: String? = null,
    /** Ход перед последним: прошлый ход соперника, на который отвечали. */
    previousMoveFrom: String? = null,
    previousMoveTo: String? = null,
    arrows: List<BoardArrow> = emptyList(),
    /** Выбранная фигура и поля, куда она может пойти: так ход делается двумя нажатиями. */
    selectedSquare: String? = null,
    targetSquares: Set<String> = emptySet(),
    onSquareTap: ((String) -> Unit)? = null,
) {
    val squares = remember(fen) { BoardLayout.parsePlacement(fen) }
    val highlight = remember(lastMoveFrom, lastMoveTo) {
        setOfNotNull(
            lastMoveFrom?.let(BoardLayout::squareIndex),
            lastMoveTo?.let(BoardLayout::squareIndex),
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val coordinateFontSize = (maxWidth.value / 8f * 0.22f).sp

        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 8) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (column in 0 until 8) {
                        val index = boardIndex(row, column, flipped)
                        val isLight = (row + column) % 2 == 0
                        val coordinateColor = if (isLight) COORDINATE_LABEL_ON_LIGHT else COORDINATE_LABEL_ON_DARK
                        val name = squareName(index)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(if (isLight) LIGHT_SQUARE else DARK_SQUARE)
                                .then(
                                    when {
                                        name == selectedSquare -> Modifier.background(SELECTED_TINT)
                                        index in highlight -> Modifier.background(LAST_MOVE_TINT)
                                        name == previousMoveTo -> Modifier.background(PREVIOUS_TO_TINT)
                                        name == previousMoveFrom -> Modifier.background(PREVIOUS_FROM_TINT)
                                        else -> Modifier
                                    }
                                )
                                .then(
                                    if (onSquareTap != null) Modifier.clickable { onSquareTap(name) } else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Как на Lichess: буквы файлов внизу слева, номера
                            // горизонталей справа сверху. Оба разворачиваются
                            // вместе с доской через flipped.
                            if (row == 7) {
                                Text(
                                    text = fileLabel(column, flipped).toString(),
                                    style = TextStyle(
                                        fontSize = coordinateFontSize,
                                        fontWeight = FontWeight.Bold,
                                        color = coordinateColor,
                                    ),
                                    modifier = Modifier.align(Alignment.BottomStart).padding(1.dp),
                                )
                            }
                            if (column == 7) {
                                Text(
                                    text = rankLabel(row, flipped).toString(),
                                    style = TextStyle(
                                        fontSize = coordinateFontSize,
                                        fontWeight = FontWeight.Bold,
                                        color = coordinateColor,
                                    ),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(1.dp),
                                )
                            }

                            squares[index]?.let { piece ->
                                PIECE_DRAWABLES[piece]?.let { drawable ->
                                    Image(
                                        painter = painterResource(drawable),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            // Точка на пустом поле, кольцо на поле со взятием — как на Lichess.
                            if (name in targetSquares) {
                                val occupied = squares[index] != null
                                Canvas(Modifier.fillMaxSize()) {
                                    if (occupied) {
                                        drawCircle(TARGET_DOT, size.minDimension * 0.46f, style = Stroke(size.minDimension * 0.08f))
                                    } else {
                                        drawCircle(TARGET_DOT, size.minDimension * 0.16f)
                                    }
                                }
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
                        drawMoveArrow(start, end, cell, arrow.style, arrow.label)
                    }
                }
            }
        }
    }
}

/** Имя поля по индексу раскладки: 0 — a8, 63 — h1. */
private fun squareName(index: Int): String = "" + ('a' + index % 8) + (8 - index / 8)

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
    ArrowStyle.LINE_MINE -> BEST_MOVE_ARROW_COLOR
    ArrowStyle.LINE_OPPONENT, ArrowStyle.LINE_OPPONENT_FAR -> THREAT_ARROW_COLOR
}

/** Пунктирная ли стрелка данного вида — тоже общее знание доски и легенды. */
fun arrowLegendDashed(style: ArrowStyle): Boolean = style == ArrowStyle.CONSEQUENCE ||
    style == ArrowStyle.LINE_MINE || style == ArrowStyle.LINE_OPPONENT || style == ArrowStyle.LINE_OPPONENT_FAR

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoveArrow(
    start: Offset,
    end: Offset,
    cell: Float,
    style: ArrowStyle,
    label: Int? = null,
) {
    val dashed = arrowLegendDashed(style)
    val baseColor = arrowLegendColor(style)
    val color = baseColor.copy(alpha = if (dashed) CONSEQUENCE_ALPHA else ARROW_ALPHA)
    val thin = style == ArrowStyle.CONSEQUENCE || style == ArrowStyle.LINE_OPPONENT_FAR
    val strokeWidth = cell * when {
        thin -> CONSEQUENCE_STROKE_FACTOR
        dashed -> LINE_STROKE_FACTOR
        else -> ARROW_STROKE_FACTOR
    }
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

    // Номер хода в продолжении — кружок у острия.
    if (label != null) {
        val center = Offset(end.x - cos(angle) * headLength * 1.4f, end.y - sin(angle) * headLength * 1.4f)
        val radius = cell * LABEL_RADIUS_FACTOR
        drawCircle(baseColor, radius, center)
        drawContext.canvas.nativeCanvas.drawText(
            label.toString(),
            center.x,
            center.y + radius * 0.38f,
            android.graphics.Paint().apply {
                this.color = android.graphics.Color.WHITE
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = radius * 1.15f
                isFakeBoldText = true
                isAntiAlias = true
            },
        )
    }
}

private const val LINE_STROKE_FACTOR = 0.11f
private const val LABEL_RADIUS_FACTOR = 0.17f
