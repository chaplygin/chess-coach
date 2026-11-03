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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.GameAnalysisStore
import org.chesscoach.core.GameReplay
import org.chesscoach.core.GameSummary
import org.chesscoach.core.catchingNonCancellation

/**
 * Главный экран в духе настроек iPhone: крупный заголовок, профиль,
 * последняя партия и разделы сгруппированными списками. Настройки — шестерёнкой
 * сверху, выход из аккаунта живёт в настройках.
 */
@Composable
fun MainMenuScreen(
    container: AppContainer,
    onOpenGames: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenGame: (GameSummary) -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenLiveGame: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val username = container.tokenStore.username()
    var lastGame by remember { mutableStateOf<GameSummary?>(null) }
    var lastGameNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username) {
        val name = username ?: return@LaunchedEffect
        val game = catchingNonCancellation { container.lichessApi.recentGames(name, max = 1).firstOrNull() }.getOrNull()
            ?: return@LaunchedEffect
        lastGame = game
        // Главную ошибку показываем, только если партия уже разобрана: ради карточки движок не гоняем.
        lastGameNote = withContext(Dispatchers.Default) {
            val evaluations = GameAnalysisStore.read(context, game.id) ?: return@withContext "Ещё не разобрана: откройте, разбор запустится сам"
            val positions = GameReplay.replay(game.movesSan, game.initialFen)
            val moment = runCatching { container.coach.recap(recapInput(game, positions, evaluations)) }.getOrNull()
                ?.moments?.maxByOrNull { it.swing }
            moment?.let { it.title + " · " + it.detail } ?: "Крупных ошибок не нашлось"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSettings) { Text("⚙", fontSize = 22.sp, color = Palette.accent) }
        }
        Text("Наставник", style = MaterialTheme.typography.headlineLarge)

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card)
                .clickable(onClick = onOpenSettings).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.selected), contentAlignment = Alignment.Center) {
                Text((username ?: "?").take(1).uppercase(), color = Palette.accent, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text(username ?: "Без входа", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Аккаунт Lichess", color = Palette.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            Chevron()
        }

        lastGame?.let { game ->
            SectionLabel("Последняя партия")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card)
                    .clickable { onOpenGame(game) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("против " + game.opponent.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        outcomeWord(game.outcome),
                        color = when (game.outcome) {
                            GameSummary.Outcome.WIN -> Palette.success
                            GameSummary.Outcome.LOSS -> Palette.danger
                            GameSummary.Outcome.DRAW -> Palette.secondaryText
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                lastGameNote?.let { Text(it, color = Palette.secondaryText, style = MaterialTheme.typography.bodySmall) }
                Text("Открыть итоги", color = Palette.accent, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionLabel("Партии")
        MenuGroup(
            listOf(
                MenuItem("♞", Color(0xFF007AFF), "Мои партии", "Разбор ход за ходом", onOpenGames),
                MenuItem("◎", Color(0xFFFF3B30), "Мои ошибки", "Что повторяется из партии в партию", onOpenReport),
            ),
        )
        SectionLabel("Другое")
        MenuGroup(
            listOf(
                MenuItem("◉", Color(0xFF34C759), "Разбор по фото", "Камера или снимок из галереи", onOpenPhoto),
                MenuItem("♚", Color(0xFFAF52DE), "Игра с компьютером", "Подсказки только против ИИ Lichess", onOpenLiveGame),
            ),
        )
    }
}

private class MenuItem(val icon: String, val tint: Color, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Palette.secondaryText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun Chevron() {
    Text("›", color = Color(0xFFC7C7CC), style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun MenuGroup(items: List<MenuItem>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card)) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = Palette.separator, modifier = Modifier.padding(start = 56.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = item.onClick).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(item.tint), contentAlignment = Alignment.Center) {
                    Text(item.icon, color = Color.White, fontSize = 18.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    Text(item.subtitle, color = Palette.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Chevron()
            }
        }
    }
}
