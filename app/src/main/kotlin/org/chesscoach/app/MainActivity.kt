package org.chesscoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.ScoreFormat
import org.chesscoach.engine.AnalysisResult
import org.chesscoach.engine.EngineController
import org.chesscoach.engine.SearchLimits

private data class Preset(val title: String, val fen: String)

private val PRESETS = listOf(
    Preset(
        "Испанская партия",
        "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",
    ),
    Preset(
        "Мат в 1",
        "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
    ),
    Preset(
        "Начальная позиция",
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    ),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AnalysisScreen()
                }
            }
        }
    }
}

/**
 * M2: настоящий поиск на захардкоженных позициях.
 *
 * Доски и разбора тренера здесь ещё нет — задача вехи в том, чтобы обкатать
 * разбор вывода UCI и увидеть реальную скорость движка на телефоне. От неё
 * зависит бюджет времени, заложенный в будущий конвейер объяснений.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisScreen() {
    val context = LocalContext.current
    val controller = remember { EngineController(context) }
    var preset by remember { mutableStateOf(PRESETS.first()) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }

    LaunchedEffect(preset) {
        running = true
        error = null
        result = null

        controller.analyzer()
            .mapCatching { it.analyze(preset.fen, SearchLimits(movetimeMs = 1500, multiPv = 3)) }
            .onSuccess { result = it }
            .onFailure { error = "${it::class.java.simpleName}: ${it.message}" }

        running = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Шахматный наставник", style = MaterialTheme.typography.headlineSmall)
        Text("M2 — поиск на движке", style = MaterialTheme.typography.titleMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { candidate ->
                FilterChip(
                    selected = candidate == preset,
                    onClick = { if (!running) preset = candidate },
                    label = { Text(candidate.title) },
                )
            }
        }

        when {
            running -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("Движок считает…")
            }

            error != null -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ошибка", style = MaterialTheme.typography.titleMedium)
                    Text(error!!, fontFamily = FontFamily.Monospace)
                }
            }

            result != null -> ResultCard(preset.fen, result!!)
        }
    }
}

@Composable
private fun ResultCard(fen: String, result: AnalysisResult) {
    val whiteToMove = remember(fen) { MoveNotation.isWhiteToMove(fen) }
    val sideToMove = remember(fen) { MoveNotation.sideToMove(fen) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Ход " + MoveNotation.fullMoveNumber(fen) + ", " +
                    if (whiteToMove) "белые" else "чёрные",
                style = MaterialTheme.typography.titleMedium,
            )

            if (result.lines.isEmpty()) {
                Text("Движок не вернул ни одного варианта за отведённое время.")
            }

            result.lines.forEach { line ->
                // Оценку показываем от лица белых: так её читают на доске и в графике.
                val whiteScore = ScoreFormat.toWhitePerspective(line.score, sideToMove)
                val san = MoveNotation.uciToSanOrRaw(fen, line.pv)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${line.multiPvIndex}.  ${ScoreFormat.display(whiteScore)}   " +
                            "глубина ${line.depth}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        san.take(8).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Счёт времени", style = MaterialTheme.typography.labelLarge)
            Text(
                "поиск ${result.elapsedMs} мс, скорость " +
                    (result.nodesPerSecond?.let { "${it / 1000} тыс. узлов/с" } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
