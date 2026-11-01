package org.chesscoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.GameAnalysisStore
import org.chesscoach.app.update.UpdateState
import org.chesscoach.engine.EngineSettingsStore

private const val HASH_STEP_MB = 16
private const val SUCCESS_TEXT_COLOR = 0xFF1E8E3E
private const val SITE_URL = "https://chaplygin.github.io/chess-coach/"

/**
 * Экран настроек (M8): Threads/Hash движка, выход из аккаунта, очистка кэша
 * разбора партий, версии приложения и движка, ссылка на сайт.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var siteOpenFailed by remember { mutableStateOf(false) }

    var threads by remember { mutableStateOf(container.engine.settings.threads()) }
    var hashMb by remember { mutableStateOf(container.engine.settings.hashMb()) }
    var settingsSaved by remember { mutableStateOf(false) }

    var engineVersion by remember { mutableStateOf<String?>(null) }
    var engineVersionError by remember { mutableStateOf<String?>(null) }

    var cacheCleared by remember { mutableStateOf(false) }

    val updateState by container.updater.state.collectAsState()

    LaunchedEffect(Unit) {
        container.engine.engineVersion()
            .onSuccess { engineVersion = it }
            .onFailure { engineVersionError = it.message ?: "движок недоступен" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Движок", style = MaterialTheme.typography.titleMedium)

                SettingStepper(
                    label = "Потоки (Threads)",
                    value = threads,
                    onDecrease = {
                        threads = (threads - 1).coerceAtLeast(EngineSettingsStore.MIN_THREADS)
                        settingsSaved = false
                    },
                    onIncrease = {
                        threads = (threads + 1).coerceAtMost(EngineSettingsStore.MAX_THREADS)
                        settingsSaved = false
                    },
                )

                SettingStepper(
                    label = "Память (Hash), МБ",
                    value = hashMb,
                    onDecrease = {
                        hashMb = (hashMb - HASH_STEP_MB).coerceAtLeast(EngineSettingsStore.MIN_HASH_MB)
                        settingsSaved = false
                    },
                    onIncrease = {
                        hashMb = (hashMb + HASH_STEP_MB).coerceAtMost(EngineSettingsStore.MAX_HASH_MB)
                        settingsSaved = false
                    },
                )

                TextButton(
                    onClick = {
                        container.engine.settings.saveThreads(threads)
                        container.engine.settings.saveHashMb(hashMb)
                        // Тёплый процесс уже настроен на старые значения —
                        // проще перезапустить его, чем выяснять, что из этого
                        // Stockfish подхватывает на лету.
                        container.engine.restartForSettings()
                        settingsSaved = true
                    },
                ) { Text("Сохранить") }

                if (settingsSaved) {
                    Text(
                        "Сохранено. Движок перезапустится при следующем обращении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(SUCCESS_TEXT_COLOR),
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Версии", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Установленная версия: ${container.updater.installedVersionName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Stockfish: " + (
                        engineVersion
                            ?: engineVersionError?.let { "недоступен ($it)" }
                            ?: "проверяю…"
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                    onClick = { container.updater.checkNow() },
                ) { Text("Проверить обновление") }

                settingsUpdateMessage(updateState)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сайт", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Разбор партий в браузере: $SITE_URL",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        // На телефоне может не оказаться браузера: это не повод падать.
                        siteOpenFailed = runCatching { uriHandler.openUri(SITE_URL) }.isFailure
                    },
                ) { Text("Открыть сайт") }

                if (siteOpenFailed) {
                    Text(
                        "Не удалось открыть браузер.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Кэш разбора партий", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Сохранённые разборы партий на диске. После очистки партии " +
                        "считаются движком заново при следующем открытии.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        GameAnalysisStore.clearAll(context)
                        cacheCleared = true
                    },
                ) { Text("Очистить кэш") }

                if (cacheCleared) {
                    Text(
                        "Кэш очищен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(SUCCESS_TEXT_COLOR),
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Аккаунт Lichess", style = MaterialTheme.typography.titleMedium)
                container.tokenStore.username()?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = {
                        container.tokenStore.signOut()
                        onSignedOut()
                    },
                ) { Text("Выйти из аккаунта") }
            }
        }
    }
}

private fun settingsUpdateMessage(state: UpdateState): String? = when (state) {
    UpdateState.Checking -> "Проверяю…"
    UpdateState.UpToDate -> "Установлена последняя версия."
    else -> null
}

@Composable
private fun SettingStepper(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDecrease) { Text("-") }
            Text(value.toString(), modifier = Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = onIncrease) { Text("+") }
        }
    }
}
