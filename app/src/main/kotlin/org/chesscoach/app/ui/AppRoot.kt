package org.chesscoach.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.chesscoach.app.AppContainer

enum class Screen { LOGIN, GAMES, ENGINE }

@Composable
fun AppRoot(redirect: Uri?, onRedirectHandled: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }

    var screen by remember {
        mutableStateOf(if (container.tokenStore.isSignedIn()) Screen.GAMES else Screen.LOGIN)
    }
    var authError by remember { mutableStateOf<String?>(null) }
    var completingLogin by remember { mutableStateOf(false) }

    LaunchedEffect(redirect) {
        val uri = redirect ?: return@LaunchedEffect

        // Приложение может быть открыто и обычным способом — тогда ссылки нет
        // и завершать нечего.
        if (uri.scheme != REDIRECT_SCHEME) {
            onRedirectHandled()
            return@LaunchedEffect
        }

        completingLogin = true
        authError = null

        container.lichessOAuth.completeAuthorization(uri)
            .onSuccess { screen = Screen.GAMES }
            .onFailure { authError = it.message ?: "Не удалось войти" }

        completingLogin = false
        onRedirectHandled()
    }

    when (screen) {
        Screen.LOGIN -> LoginScreen(
            container = container,
            completingLogin = completingLogin,
            errorMessage = authError,
            onOpenEngineCheck = { screen = Screen.ENGINE },
        )

        Screen.GAMES -> GameListScreen(
            container = container,
            onSignOut = {
                container.tokenStore.signOut()
                screen = Screen.LOGIN
            },
            onOpenEngineCheck = { screen = Screen.ENGINE },
        )

        Screen.ENGINE -> EngineScreen(
            container = container,
            onBack = {
                screen = if (container.tokenStore.isSignedIn()) Screen.GAMES else Screen.LOGIN
            },
        )
    }
}

private const val REDIRECT_SCHEME = "chesscoach"
