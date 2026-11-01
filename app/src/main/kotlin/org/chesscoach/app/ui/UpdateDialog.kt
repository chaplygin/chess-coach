package org.chesscoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.chesscoach.app.update.AppUpdater
import org.chesscoach.app.update.UpdateState

private const val BYTES_IN_MB = 1024 * 1024

/** Предложение обновиться. Показывается поверх любого экрана: и при запуске, и из настроек. */
@Composable
fun UpdateDialog(updater: AppUpdater) {
    val state by updater.state.collectAsState()

    when (val current = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Доступно обновление") },
            text = {
                Text(
                    "Установлена версия ${updater.installedVersionName}, " +
                        "доступна ${current.update.versionName} " +
                        "(${current.update.sizeBytes / BYTES_IN_MB} МБ). Загрузить?",
                )
            },
            confirmButton = {
                TextButton(onClick = { updater.download(current.update) }) { Text("Загрузить") }
            },
            dismissButton = {
                TextButton(onClick = updater::dismiss) { Text("Позже") }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Загрузка обновления") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${current.percent}%")
                    LinearProgressIndicator(
                        progress = { current.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Нужно разрешение") },
            text = {
                Text(
                    "Android спрашивает, можно ли этому приложению устанавливать обновления. " +
                        "Включите разрешение, вернитесь сюда и нажмите «Установить».",
                )
            },
            confirmButton = {
                TextButton(onClick = { updater.offerInstall(current.file) }) { Text("Установить") }
            },
            dismissButton = {
                TextButton(onClick = updater::dismiss) { Text("Позже") }
            },
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Обновление скачано") },
            text = { Text("Подтвердите установку в окне Android.") },
            confirmButton = {
                TextButton(onClick = { updater.offerInstall(current.file) }) { Text("Установить") }
            },
            dismissButton = {
                TextButton(onClick = updater::dismiss) { Text("Позже") }
            },
        )

        // Загрузку могли начать с любого экрана, а сообщение в настройках видно только там.
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Обновление не удалось") },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = updater::dismiss) { Text("Понятно") }
            },
        )

        else -> Unit
    }
}
