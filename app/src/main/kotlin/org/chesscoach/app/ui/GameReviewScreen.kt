package org.chesscoach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.chesscoach.app.AppContainer
import org.chesscoach.core.GameReplay
import org.chesscoach.core.GameSummary
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.PlayerColor
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.START_FEN
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.engine.SearchLimits

private data class PositionAdvice(
    val scoreFromWhite: Score,
    val bestMoveSan: String,
    val bestMoveFrom: String,
    val bestMoveTo: String,
    val depth: Int,
)

/**
 * Пауза перед обращением к движку.
 *
 * Пока пользователь щёлкает по ходам, считать каждую промежуточную позицию
 * бессмысленно: разбор всё равно не успеет показаться, а движок будет занят.
 */
private const val SETTLE_MS = 350L

@Composable
fun GameReviewScreen(
    container: AppContainer,
    game: GameSummary,
    onBack: () -> Unit,
) {
    val positions = remember(game.id) {
        GameReplay.replay(game.movesSan, game.initialFen)
    }

    // -1 — начальная позиция, до первого хода.
    var currentPly by remember(game.id) { mutableStateOf(-1) }
    var advice by remember(game.id) { mutableStateOf<PositionAdvice?>(null) }
    var coachText by remember(game.id) { mutableStateOf<String?>(null) }
    var thinking by remember(game.id) { mutableStateOf(false) }
    var engineError by remember(game.id) { mutableStateOf<String?>(null) }

    val fen = currentFen(positions, currentPly)
    val movesState = rememberLazyListState()

    // Ключ включает и номер полухода: одна и та же позиция может встретиться
    // дважды при повторении ходов, а сыгранный из неё ход будет разным.
    LaunchedEffect(fen, currentPly) {
        advice = null
        coachText = null
        engineError = null
        if (positions.isEmpty()) return@LaunchedEffect

        delay(SETTLE_MS)
        thinking = true

        // Сначала короткий поиск: оценка и стрелка появляются сразу, а не
        // через две с половиной секунды полного разбора.
        container.engine.analyzer()
            .mapCatching { it.analyze(fen, SearchLimits(movetimeMs = 600, multiPv = 1)) }
            .onSuccess { result ->
                val line = result.lines.firstOrNull()
                val uci = line?.pv?.firstOrNull()
                if (line != null && uci != null) {
                    advice = PositionAdvice(
                        scoreFromWhite = ScoreFormat.toWhitePerspective(
                            line.score,
                            MoveNotation.sideToMove(fen),
                        ),
                        bestMoveSan = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first(),
                        bestMoveFrom = uci.take(2),
                        bestMoveTo = uci.drop(2).take(2),
                        depth = line.depth,
                    )
                }
            }
            .onFailure { engineError = it.message ?: "Движок недоступен" }

        if (engineError == null) {
            // Ход, который был сыгран в партии из этой позиции: тренеру он
            // нужен, чтобы сказать, чем он хуже лучшего.
            val playedUci = positions.getOrNull(currentPly + 1)?.uci
            coachText = runCatching { container.coach.explainAsText(fen, playedUci) }
                .getOrNull()
        }

        thinking = false
    }

    LaunchedEffect(currentPly) {
        if (currentPly >= 0) movesState.animateScrollToItem(currentPly)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("К списку") }
            Text(
                "против " + game.opponent.name,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ChessBoard(
            fen = fen,
            // Доска разворачивается так, как её видел игрок за своими фигурами.
            flipped = game.myColor == PlayerColor.BLACK,
            lastMoveFrom = positions.getOrNull(currentPly)?.fromSquare,
            lastMoveTo = positions.getOrNull(currentPly)?.toSquare,
            arrowFrom = advice?.bestMoveFrom,
            arrowTo = advice?.bestMoveTo,
        )

        MoveStrip(
            positions = positions,
            currentPly = currentPly,
            state = movesState,
            onSelect = { currentPly = it },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { if (currentPly >= 0) currentPly-- },
                enabled = currentPly >= 0,
                modifier = Modifier.weight(1f),
            ) { Text("Назад") }

            Button(
                onClick = { if (currentPly < positions.lastIndex) currentPly++ },
                enabled = currentPly < positions.lastIndex,
                modifier = Modifier.weight(1f),
            ) { Text("Вперёд") }
        }

        AdviceCard(
            fen = fen,
            advice = advice,
            coachText = coachText,
            thinking = thinking,
            error = engineError,
        )
    }
}

private fun currentFen(positions: List<PlyPosition>, currentPly: Int): String = when {
    positions.isEmpty() -> START_FEN
    currentPly < 0 -> positions.first().fenBefore
    else -> positions[currentPly].fenAfter
}

@Composable
private fun MoveStrip(
    positions: List<PlyPosition>,
    currentPly: Int,
    state: LazyListState,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(positions) { index, ply ->
            val selected = index == currentPly
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Color(0xFFD7E3FC) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    buildString {
                        if (ply.whiteMoved) append(ply.moveNumber.toString() + ". ")
                        append(ply.san)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AdviceCard(
    fen: String,
    advice: PositionAdvice?,
    coachText: String?,
    thinking: Boolean,
    error: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) Color(0xFFFCE8E6) else Color(0xFFF1F3F4),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val whiteToMove = MoveNotation.isWhiteToMove(fen)
            Text(
                "Ход " + MoveNotation.fullMoveNumber(fen) + ", " +
                    if (whiteToMove) "белые" else "чёрные",
                style = MaterialTheme.typography.labelLarge,
            )

            when {
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall)

                advice != null -> {
                    Text(
                        "Оценка " + ScoreFormat.display(advice.scoreFromWhite),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Лучший ход: " + advice.bestMoveSan,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    when {
                        coachText != null -> Text(
                            coachText,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        thinking -> Text(
                            "Тренер разбирает позицию…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                        )

                        else -> Text(
                            "Для этой позиции тренеру нечего добавить.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                        )
                    }
                }

                thinking -> Text("Движок считает…", style = MaterialTheme.typography.bodyMedium)

                else -> Text("Нет данных для этой позиции.")
            }
        }
    }
}
