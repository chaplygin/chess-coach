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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import org.chesscoach.core.BoardLayout
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val LIGHT_SQUARE = Color(0xFFEDD6B0)
private val DARK_SQUARE = Color(0xFFB58863)
private val LAST_MOVE_TINT = Color(0x8FCDD26A)
private val ARROW_COLOR = Color(0xCC1B7F37)

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
    arrowFrom: String? = null,
    arrowTo: String? = null,
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

        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 8) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (column in 0 until 8) {
                        val index = boardIndex(row, column, flipped)
                        val isLight = (row + column) % 2 == 0

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
                        }
                    }
                }
            }
        }

        // Стрелка рисуется поверх клеток, чтобы не зависеть от их раскладки.
        if (arrowFrom != null && arrowTo != null) {
            val fromIndex = BoardLayout.squareIndex(arrowFrom)
            val toIndex = BoardLayout.squareIndex(arrowTo)

            if (fromIndex != null && toIndex != null) {
                Canvas(Modifier.matchParentSize()) {
                    val cell = size.width / 8f
                    val start = squareCenter(fromIndex, cell, flipped)
                    val end = squareCenter(toIndex, cell, flipped)
                    drawMoveArrow(start, end, cell)
                }
            }
        }
    }
}

private fun boardIndex(row: Int, column: Int, flipped: Boolean): Int =
    if (flipped) (7 - row) * 8 + (7 - column) else row * 8 + column

private fun squareCenter(index: Int, cell: Float, flipped: Boolean): Offset {
    val row = index / 8
    val column = index % 8
    val displayRow = if (flipped) 7 - row else row
    val displayColumn = if (flipped) 7 - column else column
    return Offset((displayColumn + 0.5f) * cell, (displayRow + 0.5f) * cell)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoveArrow(
    start: Offset,
    end: Offset,
    cell: Float,
) {
    val angle = atan2(end.y - start.y, end.x - start.x)
    val headLength = cell * 0.38f
    // Линия не доходит до центра поля, чтобы наконечник не накрыл фигуру.
    val shaftEnd = Offset(
        end.x - cos(angle) * headLength * 0.8f,
        end.y - sin(angle) * headLength * 0.8f,
    )

    drawLine(
        color = ARROW_COLOR,
        start = start,
        end = shaftEnd,
        strokeWidth = cell * 0.16f,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
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
    drawPath(head, ARROW_COLOR)
}
