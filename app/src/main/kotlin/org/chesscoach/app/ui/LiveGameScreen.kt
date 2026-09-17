package org.chesscoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.chesscoach.app.AppContainer
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.PlayerColor
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.core.catchingNonCancellation
import org.chesscoach.core.mapCatchingNonCancellation
import org.chesscoach.engine.SearchLimits
import org.chesscoach.lichess.OngoingGame
import org.chesscoach.lichess.RateLimitedException
import org.chesscoach.lichess.UnauthorizedException

/** Опрос идущей партии — раз в несколько секунд, не чаще: это фон, а не разбор. */
private const val POLL_INTERVAL_MS = 3_500L

/** Один поиск на живую позицию: короче, чем в разборе, — ответ нужен, пока ход ещё актуален. */
private val LIVE_SEARCH_LIMITS = SearchLimits(movetimeMs = 1200, multiPv = 1)

private val BANNER_COLOR = Color(0xFFFEF3D9)
private val ERROR_COLOR = Color(0xFFFCE8E6)

private data class EngineSnapshot(val whiteScore: Score?, val bestMoveSan: String?)

/**
 * Экран «Текущая партия».
 *
 * Опрос /api/account/playing идёт только пока экран виден: LaunchedEffect
 * держит цикл внутри repeatOnLifecycle(STARTED), и как только экран уходит с
 * переднего плана, цикл отменяется вместе с корутиной — без этого приложение
 * опрашивало бы Lichess из фона и просто жгло батарею впустую.
 *
 * Фейр-плей здесь — это свойство кода, а не текста на экране: движок
 * вызывается только внутри ветки `current.liveCoachingAllowed`, то есть только
 * когда OngoingGame сам, по данным Lichess, подтвердил компьютерного
 * соперника. Против человека или при неизвестном сопернике запрос к движку
 * просто не создаётся — его негде создать, если убрать баннер с вёрстки.
 */
@Composable
fun LiveGameScreen(container: AppContainer, onBack: () -> Unit) {
    var game by remember { mutableStateOf<OngoingGame?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pollError by remember { mutableStateOf<String?>(null) }

    var analysis by remember { mutableStateOf<EngineSnapshot?>(null) }
    var analyzing by remember { mutableStateOf(false) }

    // Отдельно от analysis: нужен именно последний ПРОАНАЛИЗИРОВАННЫЙ FEN,
    // чтобы повторный опрос с той же позицией (соперник ещё думает) не запускал
    // движок заново каждые несколько секунд.
    var lastAnalyzedFen by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                catchingNonCancellation { container.lichessApi.nowPlaying() }
                    .onSuccess { games ->
                        loading = false
                        pollError = null
                        game = games.firstOrNull()
                    }
                    .onFailure { failure ->
                        loading = false
                        pollError = when (failure) {
                            is RateLimitedException ->
                                "Lichess просит подождать ${failure.retryAfterSeconds} с"

                            is UnauthorizedException -> "Срок действия входа истёк, войдите заново"
                            else -> failure.message ?: "Не удалось получить партию"
                        }
                    }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // Ключ эффекта — сам FEN и признак дозволенности: смена одного из них и
    // есть смена позиции или её статуса, а не каждый очередной опрос.
    LaunchedEffect(game?.fen, game?.liveCoachingAllowed) {
        val current = game

        if (current == null || !current.liveCoachingAllowed) {
            analysis = null
            analyzing = false
            lastAnalyzedFen = null
            return@LaunchedEffect
        }

        if (current.fen == lastAnalyzedFen) return@LaunchedEffect
        lastAnalyzedFen = current.fen

        analyzing = true

        container.engine.analyzer()
            .mapCatchingNonCancellation { it.analyze(current.fen, LIVE_SEARCH_LIMITS) }
            .onSuccess { result ->
                val line = result.lines.firstOrNull()
                val bestUci = result.bestMoveUci ?: line?.pv?.firstOrNull()
                analysis = EngineSnapshot(
                    whiteScore = line?.let {
                        ScoreFormat.toWhitePerspective(it.score, MoveNotation.sideToMove(current.fen))
                    },
                    bestMoveSan = bestUci?.let {
                        MoveNotation.uciToSanOrRaw(current.fen, listOf(it)).firstOrNull()
                    },
                )
            }
            .onFailure { analysis = null }

        analyzing = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Текущая партия", style = MaterialTheme.typography.headlineSmall)

        // Копия в локальный val — как и в AppRoot.kt с selectedGame: `game`
        // здесь var, захваченный в LaunchedEffect выше, и без этого компилятор
        // не сузит его тип до не-null в ветке else.
        val currentGame = game

        when {
            loading -> Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("Ищу партию…")
            }

            pollError != null -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ERROR_COLOR),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ошибка", style = MaterialTheme.typography.titleSmall)
                    Text(pollError!!, style = MaterialTheme.typography.bodySmall)
                }
            }

            currentGame == null -> Text("Сейчас партий нет. Начните партию на Lichess и вернитесь сюда.")

            else -> LiveGameContent(game = currentGame, analysis = analysis, analyzing = analyzing)
        }
    }
}

@Composable
private fun LiveGameContent(game: OngoingGame, analysis: EngineSnapshot?, analyzing: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!game.liveCoachingAllowed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BANNER_COLOR),
            ) {
                Text(
                    "Живые подсказки доступны только в партиях против ИИ Lichess",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        ChessBoard(
            fen = game.fen,
            flipped = game.myColor == PlayerColor.BLACK,
        )

        Text(
            if (game.isMyTurn) "Ваш ход" else "Ход соперника",
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            game.opponent.name +
                (game.opponent.aiLevel?.let { " · уровень движка $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (game.liveCoachingAllowed) {
            // Значение сохраняется в локальный val: доступ к analysis.whiteScore
            // напрямую в условии when не даёт компилятору сузить тип обратно
            // к не-null через цепочку из двух свойств.
            val snapshot = analysis
            val score = snapshot?.whiteScore

            when {
                snapshot != null && score != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Оценка: ${ScoreFormat.display(score)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    snapshot.bestMoveSan?.let { Text("Лучший ход: $it") }
                }

                analyzing -> Text("Считаю позицию…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
