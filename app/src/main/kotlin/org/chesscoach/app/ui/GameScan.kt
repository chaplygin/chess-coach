package org.chesscoach.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.GameAnalysisProgress
import org.chesscoach.app.analysis.GameAnalysisService
import org.chesscoach.app.analysis.GameAnalysisStore
import org.chesscoach.app.analysis.ScanState
import org.chesscoach.coach.analysis.GameRecap
import org.chesscoach.coach.analysis.RecapInput
import org.chesscoach.core.GameReplay
import org.chesscoach.core.GameSummary
import org.chesscoach.core.PlayerColor
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.PlyPosition

/**
 * Разбор всей партии движком, как его видят экраны итогов и ходов.
 *
 * [evaluations] — готовые оценки, а пока скан идёт, уже посчитанная часть:
 * по ней рисуется предварительный график. [final] — скан закончен.
 */
class GameScan(
    val positions: List<PlyPosition>,
    val state: ScanState?,
    val evaluations: List<PlyEvaluation>,
    val final: Boolean,
    val recap: GameRecap?,
    val start: () -> Unit,
)

@Composable
fun rememberGameScan(container: AppContainer, game: GameSummary): GameScan {
    val context = LocalContext.current
    val positions = remember(game.id) { GameReplay.replay(game.movesSan, game.initialFen) }
    var stored by remember(game.id) { mutableStateOf<List<PlyEvaluation>?>(null) }
    val progress = remember(game.id) { GameAnalysisProgress.flowFor(game.id) }
    val state by progress.collectAsState(initial = null)

    LaunchedEffect(game.id) {
        // Кэш первым делом: партия могла быть разобрана раньше.
        stored = GameAnalysisStore.read(context, game.id)
        // Разбор всей партии стартует сам: сразу видно, какие ходы ключевые.
        val current = progress.first()
        if (stored == null && current !is ScanState.Running && current !is ScanState.Done) startScan(context, game)
    }

    val done = (state as? ScanState.Done)?.evaluations
    val final = done ?: stored
    val evaluations = final ?: (state as? ScanState.Running)?.partial.orEmpty()

    // Итоги считаются и по неполному скану: первый перелом виден, пока движок досчитывает остальное.
    val recap by produceState<GameRecap?>(null, evaluations) {
        if (evaluations.isEmpty()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching { container.coach.recap(recapInput(game, positions, evaluations)) }.getOrNull()
        }
    }

    return GameScan(
        positions = positions,
        state = state,
        evaluations = evaluations,
        final = final != null,
        recap = recap,
        start = { startScan(context, game) },
    )
}

fun recapInput(game: GameSummary, positions: List<PlyPosition>, evaluations: List<PlyEvaluation>) = RecapInput(
    iAmWhite = game.myColor == PlayerColor.WHITE,
    positions = positions.take(evaluations.size),
    evaluations = evaluations,
    outcome = game.outcome.name,
    status = game.status,
    clocksCs = game.clocksCs,
    initialSeconds = game.initialClockSeconds,
)

private fun startScan(context: Context, game: GameSummary) {
    runCatching { GameAnalysisService.start(context, game) }
        .onFailure { error ->
            GameAnalysisProgress.update(game.id, ScanState.Failed(error.message ?: "Не удалось запустить разбор"))
        }
}
