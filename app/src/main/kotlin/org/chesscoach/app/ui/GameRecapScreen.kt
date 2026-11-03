package org.chesscoach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.ScanState
import org.chesscoach.coach.analysis.KeyMoment
import org.chesscoach.coach.analysis.SideStats
import org.chesscoach.core.GameSummary
import org.chesscoach.core.PlayerColor

/**
 * Итоги партии — первый экран при входе в партию: что решило партию, как
 * играли обе стороны и до пяти ходов, к которым стоит вернуться. Нажатие на
 * момент или на график открывает доску на этом ходу.
 */
@Composable
fun GameRecapScreen(
    container: AppContainer,
    game: GameSummary,
    onBack: () -> Unit,
    onOpenMove: (Int) -> Unit,
) {
    val scan = rememberGameScan(container, game)
    val recap = scan.recap

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹ Партии") }
            TextButton(onClick = { onOpenMove(-1) }) { Text("Ходы") }
        }
        Text("Итоги партии", style = MaterialTheme.typography.headlineLarge)
        Text(
            (if (game.myColor == PlayerColor.WHITE) "Вы белыми" else "Вы чёрными") + " против " + game.opponent.name +
                " · " + outcomeWord(game.outcome),
            color = Palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )

        (scan.state as? ScanState.Running)?.takeIf { !scan.final }?.let { running ->
            GroupCard {
                Text("Движок разбирает партию: ${running.done} из ${running.total}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (running.total > 0) running.done.toFloat() / running.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                recap?.moments?.firstOrNull()?.let { first ->
                    TextButton(onClick = { onOpenMove(first.ply) }) { Text("Первый перелом: " + first.title.substringBefore(" — ") + " ›") }
                }
            }
        }
        (scan.state as? ScanState.Failed)?.takeIf { !scan.final }?.let { failed ->
            GroupCard {
                Text(failed.message, color = Palette.danger, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = scan.start) { Text("Разобрать ещё раз") }
            }
        }

        recap?.let {
            GroupCard { Text(it.verdict, style = MaterialTheme.typography.bodyLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Вы", it.me, Modifier.weight(1f))
                StatCard("Соперник", it.opponent, Modifier.weight(1f))
            }
        }

        if (scan.evaluations.isNotEmpty()) {
            GroupCard {
                EvalGraphBar(
                    evaluations = scan.evaluations,
                    totalPlies = scan.positions.size,
                    currentPly = -1,
                    moments = recap?.moments.orEmpty(),
                    onSelect = onOpenMove,
                    height = 72.dp,
                )
            }
        }

        val moments = recap?.moments.orEmpty()
        if (moments.isNotEmpty()) {
            Text("Ключевые моменты", color = Palette.secondaryText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card)) {
                moments.forEachIndexed { index, moment ->
                    if (index > 0) HorizontalDivider(color = Palette.separator, modifier = Modifier.padding(start = 34.dp))
                    MomentRow(moment, onClick = { onOpenMove(moment.ply) })
                }
            }
        }

        Button(onClick = { onOpenMove(moments.firstOrNull()?.ply ?: 0) }, modifier = Modifier.fillMaxWidth()) {
            Text("Разобрать по ходам")
        }
    }
}

@Composable
private fun MomentRow(moment: KeyMoment, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(momentColor(moment.kind)))
        Column(Modifier.weight(1f)) {
            Text(moment.title, style = MaterialTheme.typography.bodyLarge)
            Text(moment.detail, color = Palette.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = Color(0xFFC7C7CC), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun StatCard(title: String, stats: SideStats, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.card).padding(12.dp)) {
        Text(title, color = Palette.secondaryText, style = MaterialTheme.typography.labelMedium)
        Text("${stats.accuracy}%", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.headlineSmall)
        Text(
            "зевки ${stats.blunders} · ошибки ${stats.mistakes} · неточн. ${stats.inaccuracies}",
            color = Palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun GroupCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card).padding(14.dp)) {
        content()
    }
}

fun outcomeWord(outcome: GameSummary.Outcome): String = when (outcome) {
    GameSummary.Outcome.WIN -> "победа"
    GameSummary.Outcome.LOSS -> "поражение"
    GameSummary.Outcome.DRAW -> "ничья"
}
