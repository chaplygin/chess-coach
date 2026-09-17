package org.chesscoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.chesscoach.app.AppContainer

/**
 * Главный вход в приложение.
 *
 * Крупные кнопки, а не список пунктов: приложением пользуются с телефона и
 * часто одной рукой, и три главных действия должны попадаться пальцу без
 * прицеливания. Редкое и служебное (настройки, проверка движка, выход) уходит
 * вниз мелкой строкой — иначе главное тонет среди второстепенного.
 */
@Composable
fun MainMenuScreen(
    container: AppContainer,
    onOpenGames: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenLiveGame: () -> Unit,
    onOpenEngineCheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val username = container.tokenStore.username()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
            Text("Шахматный наставник", style = MaterialTheme.typography.headlineMedium)
            Text(
                username?.let { "Аккаунт Lichess: $it" } ?: "Разбор позиций и партий",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5F6368),
            )
        }

        BigMenuButton(
            title = "Разобрать партию",
            subtitle = "Мои партии с Lichess: ход за ходом с объяснениями",
            onClick = onOpenGames,
        )

        BigMenuButton(
            title = "Разбор по фото",
            subtitle = "Снять доску камерой или взять снимок из галереи",
            onClick = onOpenPhoto,
        )

        BigMenuButton(
            title = "Текущая партия",
            subtitle = "Подсказки идут только в партии против компьютера Lichess",
            onClick = onOpenLiveGame,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenSettings) { Text("Настройки") }
            TextButton(onClick = onOpenEngineCheck) { Text("Движок") }
            TextButton(onClick = onSignOut) { Text("Выйти") }
        }
    }
}

@Composable
private fun BigMenuButton(title: String, subtitle: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Start)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
        }
    }
}
