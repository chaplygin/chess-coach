package org.chesscoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.chesscoach.app.AppContainer
import org.chesscoach.lichess.openLichessAuthorization

@Composable
fun LoginScreen(
    container: AppContainer,
    completingLogin: Boolean,
    errorMessage: String?,
    onOpenEngineCheck: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Шахматный наставник", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Разбирает ваши партии с Lichess и объясняет, почему тот или иной ход лучше.",
            style = MaterialTheme.typography.bodyLarge,
        )

        if (completingLogin) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("Завершаю вход…")
            }
        } else {
            Button(
                onClick = {
                    val uri = container.lichessOAuth.buildAuthorizationUri()
                    openLichessAuthorization(context, uri)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Войти через Lichess")
            }
        }

        errorMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Вход не удался", style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Пользователь имеет право заранее знать, что приложение может делать
        // с его аккаунтом, а чего не может в принципе.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что получает приложение", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Только чтение: имя пользователя, список партий и текущие партии. " +
                        "Права делать ходы не запрашиваются вовсе, поэтому сыграть за вас " +
                        "приложение не сможет физически.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Честная игра", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Подсказки в реальном времени работают только в партиях против " +
                        "компьютера Lichess, где это разрешено правилами. Партии с людьми " +
                        "доступны лишь для разбора после их завершения.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        TextButton(onClick = onOpenEngineCheck) {
            Text("Проверить движок")
        }
    }
}
