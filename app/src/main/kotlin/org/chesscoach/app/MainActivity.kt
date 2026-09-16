package org.chesscoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.chesscoach.coach.CoachEngine
import org.chesscoach.core.MoveClassification

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MilestoneZeroScreen()
                }
            }
        }
    }
}

/**
 * M0 существует ровно для одного: доказать, что связка
 * AGP / Kotlin / Compose / GitHub Actions даёт устанавливаемый APK.
 * Шахматной логики здесь нет намеренно.
 */
@Composable
private fun MilestoneZeroScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Шахматный наставник", style = MaterialTheme.typography.headlineSmall)
        Text("M0 — тулчейн собран", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Модуль coach: ${CoachEngine.VERSION}\n" +
                "Порог зевка: свыше ${MoveClassification.MISTAKE.maxCentipawnLoss} сп",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
