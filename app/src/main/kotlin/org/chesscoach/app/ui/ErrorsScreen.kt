package org.chesscoach.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.GameAnalysisService
import org.chesscoach.app.analysis.GameAnalysisStore
import org.chesscoach.coach.analysis.AnalyzedGame
import org.chesscoach.coach.analysis.ErrorExample
import org.chesscoach.coach.analysis.ErrorReport
import org.chesscoach.coach.analysis.scoreChange
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.core.GameReplay
import org.chesscoach.core.GameSummary
import org.chesscoach.core.PlayerColor
import org.chesscoach.core.catchingNonCancellation

/** Окно анализа ошибок в месяцах: партии старше в сводку не попадают. */
private val WINDOWS = listOf(1 to "1 мес", 3 to "3 мес", 6 to "6 мес", 12 to "Год")
private const val DEFAULT_WINDOW_MONTHS = 12
private const val PREFS = "errors_report"
private const val KEY_WINDOW = "window_months"
private const val MAX_GAMES = 300
private const val SHOWN_EXAMPLES = 3
private const val DAY_MS = 24L * 60 * 60 * 1000

fun errorWindowMonths(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_WINDOW, DEFAULT_WINDOW_MONTHS)

private fun saveErrorWindow(context: Context, months: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_WINDOW, months).apply()
}

/**
 * «Мои ошибки»: сверху главное — самая частая ошибка и совет, ниже виды
 * ошибок с тремя самыми дорогими примерами. Нажатие на пример открывает
 * разбор той партии сразу на этом ходу.
 */
@Composable
fun ErrorsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenExample: (GameSummary, Int) -> Unit,
) {
    val context = LocalContext.current
    var months by remember { mutableIntStateOf(errorWindowMonths(context)) }
    var games by remember { mutableStateOf<List<GameSummary>>(emptyList()) }
    var report by remember { mutableStateOf<ErrorReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var queued by remember { mutableStateOf(false) }

    LaunchedEffect(months, refresh) {
        loading = true
        error = null
        catchingNonCancellation {
            val name = container.tokenStore.username() ?: container.lichessApi.account().username.also {
                container.tokenStore.saveUsername(it)
            }
            val since = System.currentTimeMillis() - months * 30L * DAY_MS
            val loaded = container.lichessApi.recentGames(name, max = MAX_GAMES, sinceEpochMs = since)
            games = loaded
            report = buildErrorReport(container, context, loaded)
        }.onFailure { error = it.message ?: "Не удалось собрать сводку" }
        loading = false
    }

    val unanalyzed = remember(games, report) { games.filter { GameAnalysisStore.read(context, it.id) == null } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Назад") }
                TextButton(onClick = { refresh++ }) { Text("Обновить") }
            }
            Text("Мои ошибки", style = MaterialTheme.typography.headlineLarge)
        }

        item {
            Text("Окно анализа ошибок", style = MaterialTheme.typography.labelLarge, color = Palette.secondaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WINDOWS.forEach { (value, label) ->
                    FilterChip(
                        selected = months == value,
                        onClick = {
                            months = value
                            saveErrorWindow(context, value)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        when {
            loading -> item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Загружаю партии и считаю ошибки…")
                }
            }

            error != null -> item { Text(error!!, color = Palette.danger) }

            else -> {
                item {
                    AnalyzedLine(
                        analyzed = games.size - unanalyzed.size,
                        total = games.size,
                        months = months,
                        unanalyzed = unanalyzed.size,
                        queued = queued,
                        onAnalyze = {
                            unanalyzed.forEach { GameAnalysisService.start(context, it) }
                            queued = true
                        },
                    )
                }
                val current = report
                if (current == null || current.games == 0) {
                    item { Text("Разобранных партий за это время нет. Разберите их кнопкой выше или откройте партию.") }
                } else {
                    item { MainCard(current, games, onOpenExample) }
                    item { CountsCard(current) }
                    items(current.byKind, key = { it.kind.name }) { count ->
                        KindCard(
                            title = count.kind.title,
                            moves = count.moves,
                            gamesCount = count.games,
                            maxMoves = current.byKind.maxOf { it.moves },
                            advice = count.kind.advice,
                            examples = current.examples[count.kind].orEmpty(),
                            games = games,
                            onOpenExample = onOpenExample,
                        )
                    }
                    item { Box(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AnalyzedLine(analyzed: Int, total: Int, months: Int, unanalyzed: Int, queued: Boolean, onAnalyze: () -> Unit) {
    val period = if (months == 12) "за год" else "за $months мес."
    Card(colors = CardDefaults.cardColors(containerColor = Palette.card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Разобрано $analyzed из $total партий $period", fontWeight = FontWeight.SemiBold)
            when {
                unanalyzed == 0 -> Text("Все партии за это время разобраны.", color = Palette.secondaryText)
                queued -> Text(
                    "Разбор идёт в фоне, по полминуты-минуте на партию. Нажмите «Обновить», чтобы увидеть новые партии в сводке.",
                    color = Palette.secondaryText,
                )
                else -> Button(onClick = onAnalyze) { Text("Разобрать $unanalyzed " + gamesWord(unanalyzed)) }
            }
        }
    }
}

@Composable
private fun MainCard(report: ErrorReport, games: List<GameSummary>, onOpenExample: (GameSummary, Int) -> Unit) {
    val main = report.main ?: return
    Card(colors = CardDefaults.cardColors(containerColor = Palette.accent)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ГЛАВНОЕ", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Text(
                "Вы чаще всего " + main.kind.label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp,
            )
            Text("${main.moves} " + movesWord(main.moves) + " в ${main.games} " + gamesWordIn(main.games), color = Color.White)
            Text("Совет: " + main.kind.advice + ".", color = Color.White)
            report.examples[main.kind]?.firstOrNull()?.let { example ->
                val game = games.firstOrNull { it.id == example.gameId }
                if (game != null) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.18f))
                            .clickable { onOpenExample(game, example.ply) }.padding(12.dp),
                    ) {
                        Text(
                            "Посмотреть самый дорогой пример: " + example.move + " против " + example.opponent + " ›",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountsCard(report: ErrorReport) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("Зевки", report.blunders, Palette.danger)
                Counter("Ошибки", report.mistakes, Palette.warning)
                Counter("Неточности", report.inaccuracies, Palette.secondaryText)
            }
            Text(
                "Зевки и ошибки по стадиям: дебют " + (report.byPhase[GamePhase.OPENING] ?: 0) +
                    ", миттельшпиль " + (report.byPhase[GamePhase.MIDDLEGAME] ?: 0) +
                    ", эндшпиль " + (report.byPhase[GamePhase.ENDGAME] ?: 0) + ".",
                color = Palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Партий: ${report.games}, ваших ходов: ${report.myMoves}.",
                color = Palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Counter(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
    }
}

@Composable
private fun KindCard(
    title: String,
    moves: Int,
    gamesCount: Int,
    maxMoves: Int,
    advice: String,
    examples: List<ErrorExample>,
    games: List<GameSummary>,
    onOpenExample: (GameSummary, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Palette.card)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("$moves " + movesWord(moves) + " · $gamesCount " + gamesWordIn(gamesCount), color = Palette.secondaryText)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Palette.separator)) {
                Box(
                    Modifier.fillMaxWidth(moves.toFloat() / maxMoves.coerceAtLeast(1)).fillMaxHeight()
                        .clip(CircleShape).background(Palette.accent),
                )
            }
            Text(advice.replaceFirstChar { it.uppercase() } + ".", style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
            val shown = if (expanded) examples else examples.take(SHOWN_EXAMPLES)
            shown.forEach { example ->
                val game = games.firstOrNull { it.id == example.gameId }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = game != null) { game?.let { onOpenExample(it, example.ply) } }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(example.move, fontWeight = FontWeight.SemiBold, color = Palette.accent)
                        Text(
                            "против " + example.opponent + " · " + example.scoreChange(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.secondaryText,
                        )
                    }
                    Text("›", color = Palette.secondaryText)
                }
            }
            if (examples.size > SHOWN_EXAMPLES) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Ещё " + (examples.size - SHOWN_EXAMPLES))
                }
            }
        }
    }
}

/** Сводка по партиям окна, которые уже разобраны на этом телефоне. */
private suspend fun buildErrorReport(container: AppContainer, context: Context, games: List<GameSummary>): ErrorReport =
    withContext(Dispatchers.Default) {
        val analyzed = games.mapNotNull { game ->
            val evaluations = GameAnalysisStore.read(context, game.id) ?: return@mapNotNull null
            AnalyzedGame(
                iAmWhite = game.myColor == PlayerColor.WHITE,
                positions = GameReplay.replay(game.movesSan, game.initialFen),
                classifications = evaluations.associate { it.ply to it.classification },
                scoresAfterWhite = evaluations.associate { it.ply to it.scoreAfterWhite },
                gameId = game.id,
                opponent = game.opponent.name,
            )
        }
        container.coach.errorReport(analyzed)
    }

private fun movesWord(n: Int): String = plural(n, "ход", "хода", "ходов")
private fun gamesWord(n: Int): String = plural(n, "партию", "партии", "партий")
private fun gamesWordIn(n: Int): String = plural(n, "партии", "партиях", "партиях")

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
