package org.chesscoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.chesscoach.coach.analysis.KeyMoment
import org.chesscoach.core.EvalGraph
import org.chesscoach.core.PlyEvaluation

/**
 * График оценки партии от лица белых: столбик на полуход, выше середины лучше
 * белым. Ширина задана числом полуходов всей партии, поэтому пока скан идёт,
 * график растёт слева направо. Вертикальная линия — текущий ход, цветные
 * точки — ключевые моменты. Тап переводит на полуход.
 */
@Composable
fun EvalGraphBar(
    evaluations: List<PlyEvaluation>,
    totalPlies: Int,
    currentPly: Int,
    moments: List<KeyMoment>,
    onSelect: (Int) -> Unit,
    height: Dp = 36.dp,
) {
    if (totalPlies <= 0) return
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(totalPlies) {
                detectTapGestures { offset ->
                    onSelect((offset.x / size.width * totalPlies).toInt().coerceIn(0, totalPlies - 1))
                }
            },
    ) {
        val barWidth = size.width / totalPlies
        val half = size.height / 2f

        evaluations.forEach { evaluation ->
            val fraction = EvalGraph.fraction(evaluation.scoreAfterWhite).toFloat()
            val barHeight = half * fraction
            val top = if (barHeight >= 0f) half - barHeight else half
            drawRect(
                color = Color(0xFFC7C7CC),
                topLeft = Offset(evaluation.ply * barWidth, top),
                size = Size(barWidth, abs(barHeight).coerceAtLeast(1f)),
            )
        }

        drawLine(Color(0xFF8E8E93), Offset(0f, half), Offset(size.width, half), strokeWidth = 1f)

        if (currentPly in 0 until totalPlies) {
            val x = (currentPly + 0.5f) * barWidth
            drawLine(Palette.accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }

        val radius = 3.5.dp.toPx()
        moments.forEach { moment ->
            val evaluation = evaluations.firstOrNull { it.ply == moment.ply } ?: return@forEach
            val fraction = EvalGraph.fraction(evaluation.scoreAfterWhite).toFloat()
            val y = (half - half * fraction).coerceIn(radius, size.height - radius)
            drawCircle(momentColor(moment.kind), radius, Offset((moment.ply + 0.5f) * barWidth, y))
        }
    }
}
