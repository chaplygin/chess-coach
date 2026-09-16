package org.chesscoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.chesscoach.engine.EngineSmokeReport
import org.chesscoach.engine.EngineSmokeTest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineCheckScreen()
                }
            }
        }
    }
}

/**
 * M1 проверяет самое рискованное допущение проекта: запускается ли вообще
 * нативный Stockfish на этом телефоне. Шахматной логики здесь нет намеренно —
 * если движок не стартует, всё остальное не имеет смысла.
 */
@Composable
private fun EngineCheckScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<EngineSmokeReport?>(null) }
    var running by remember { mutableStateOf(true) }

    suspend fun check() {
        running = true
        report = EngineSmokeTest.run(context)
        running = false
    }

    LaunchedEffect(Unit) { check() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Шахматный наставник", style = MaterialTheme.typography.headlineSmall)
        Text("M1 — проверка движка", style = MaterialTheme.typography.titleMedium)

        val current = report
        when {
            running -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("Запускаю Stockfish…")
            }

            current != null -> ReportCard(current)
        }

        Button(onClick = { scope.launch { check() } }, enabled = !running) {
            Text("Повторить проверку")
        }
    }
}

@Composable
private fun ReportCard(report: EngineSmokeReport) {
    val ok = report.ok
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) Color(0xFFE6F4EA) else Color(0xFFFCE8E6),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (ok) "Движок работает" else "Движок не запустился",
                style = MaterialTheme.typography.titleMedium,
            )

            LabelValue("Название", report.engineName ?: "—")
            LabelValue("uciok", if (report.uciOk) "получен" else "нет")
            LabelValue("readyok", if (report.readyOk) "получен" else "нет")
            LabelValue("Размер бинарника", report.binarySizeMb)
            LabelValue("ABI устройства", report.supportedAbis.joinToString())

            Text("Подробности", style = MaterialTheme.typography.labelLarge)
            report.diagnostics.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}
