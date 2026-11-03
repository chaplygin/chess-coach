package org.chesscoach.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.GameAnalysisStore
import org.chesscoach.coach.analysis.AnalyzedGame
import org.chesscoach.core.GameReplay
import org.chesscoach.core.catchingNonCancellation
import org.chesscoach.core.GameSummary
import org.chesscoach.core.PlayerColor
import org.chesscoach.lichess.RateLimitedException
import org.chesscoach.lichess.UnauthorizedException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GameListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenGame: (GameSummary) -> Unit,
    /** Открыть сводку «Мои ошибки» сразу после загрузки: так её открывает главный экран. */
    openReport: Boolean = false,
) {
    var games by remember { mutableStateOf<List<GameSummary>>(emptyList()) }
    var username by remember { mutableStateOf(container.tokenStore.username()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        error = null

        catchingNonCancellation {
            val name = username ?: container.lichessApi.account().username.also {
                container.tokenStore.saveUsername(it)
                username = it
            }
            container.lichessApi.recentGames(name, max = 50)
        }.onSuccess {
            games = it
            if (openReport) report = buildReport(container, context, it)
        }.onFailure { failure ->
            error = when (failure) {
                is RateLimitedException ->
                    "Lichess просит подождать ${failure.retryAfterSeconds} с и повторить"

                is UnauthorizedException ->
                    "Срок действия входа истёк, войдите заново"

                else -> failure.message ?: "Не удалось загрузить партии"
            }
        }

        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Мои партии", style = MaterialTheme.typography.headlineSmall)
                username?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row {
                TextButton(
                    enabled = games.isNotEmpty(),
                    onClick = {
                        scope.launch { report = buildReport(container, context, games) }
                    },
                ) { Text("Мои ошибки") }
                TextButton(onClick = onBack) { Text("В меню") }
            }
        }

        when {
            loading -> Column(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("Загружаю партии…")
            }

            error != null -> Card(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ошибка", style = MaterialTheme.typography.titleSmall)
                    Text(error!!, style = MaterialTheme.typography.bodySmall)
                }
            }

            games.isEmpty() -> Text(
                "Партий не нашлось. Сыграйте партию на Lichess и вернитесь сюда.",
                modifier = Modifier.padding(top = 24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(games, key = { it.id }) { game ->
                    GameRow(game, onClick = { onOpenGame(game) })
                }
            }
        }

    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            confirmButton = { TextButton(onClick = { report = null }) { Text("Закрыть") } },
            title = { Text("Мои ошибки") },
            text = {
                SelectionContainer {
                    Text(text, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
        )
    }
}

/** Сводка повторяющихся ошибок по партиям, уже разобранным на этом телефоне. */
private suspend fun buildReport(container: AppContainer, context: android.content.Context, games: List<GameSummary>): String =
    catchingNonCancellation {
        withContext(Dispatchers.Default) {
            val analyzed = games.mapNotNull { game ->
                val evaluations = GameAnalysisStore.read(context, game.id) ?: return@mapNotNull null
                AnalyzedGame(
                    iAmWhite = game.myColor == PlayerColor.WHITE,
                    positions = GameReplay.replay(game.movesSan, game.initialFen),
                    classifications = evaluations.associate { it.ply to it.classification },
                )
            }
            container.coach.errorReport(analyzed).render()
        }
    }.getOrElse { "Не удалось собрать сводку." }

@Composable
private fun GameRow(game: GameSummary, onClick: () -> Unit) {
    val outcomeColor = when (game.outcome) {
        GameSummary.Outcome.WIN -> Color(0xFF1E8E3E)
        GameSummary.Outcome.LOSS -> Color(0xFFC5221F)
        GameSummary.Outcome.DRAW -> Color(0xFF5F6368)
    }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    game.opponent.name +
                        (game.opponent.rating?.let { " ($it)" } ?: ""),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (game.outcome) {
                        GameSummary.Outcome.WIN -> "победа"
                        GameSummary.Outcome.LOSS -> "поражение"
                        GameSummary.Outcome.DRAW -> "ничья"
                    },
                    color = outcomeColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Text(
                buildString {
                    append(if (game.myColor == PlayerColor.WHITE) "белыми" else "чёрными")
                    append(" · ")
                    append(game.speed)
                    append(" · ")
                    append("${game.movesCount} полуходов")
                    if (game.opponent.isComputer) append(" · против компьютера")
                },
                style = MaterialTheme.typography.bodySmall,
            )

            game.openingName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F6368))
            }

            Text(
                formatDate(game.createdAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5F6368),
            )
        }
    }
}

private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

private fun formatDate(epochMs: Long): String =
    if (epochMs <= 0) "" else dateFormat.format(Instant.ofEpochMilli(epochMs))
