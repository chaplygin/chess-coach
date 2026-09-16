package org.chesscoach.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.chesscoach.app.ui.AppRoot

class MainActivity : ComponentActivity() {

    /**
     * Возврат из браузера после входа в Lichess.
     *
     * Активность объявлена как singleTask, поэтому система не создаёт вторую
     * копию, а доставляет ссылку в onNewIntent уже работающему экрану.
     */
    private var redirect by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        redirect = intent?.data

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        redirect = redirect,
                        onRedirectHandled = { redirect = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        redirect = intent.data
    }
}
