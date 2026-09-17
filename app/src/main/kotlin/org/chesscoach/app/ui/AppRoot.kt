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
import org.chesscoach.core.GameSummary

enum class Screen { LOGIN, MENU, GAMES, PHOTO, REVIEW, ENGINE, LIVE, SETTINGS }

@Composable
fun AppRoot(redirect: Uri?, onRedirectHandled: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }

    var screen by remember {
        mutableStateOf(if (container.tokenStore.isSignedIn()) Screen.MENU else Screen.LOGIN)
    }
    var authError by remember { mutableStateOf<String?>(null) }
    var completingLogin by remember { mutableStateOf(false) }
    var selectedGame by remember { mutableStateOf<GameSummary?>(null) }

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
            .onSuccess { screen = Screen.MENU }
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

        Screen.MENU -> MainMenuScreen(
            container = container,
            onOpenGames = { screen = Screen.GAMES },
            onOpenPhoto = { screen = Screen.PHOTO },
            onOpenLiveGame = { screen = Screen.LIVE },
            onOpenEngineCheck = { screen = Screen.ENGINE },
            onOpenSettings = { screen = Screen.SETTINGS },
            onSignOut = {
                container.tokenStore.signOut()
                screen = Screen.LOGIN
            },
        )

        Screen.GAMES -> GameListScreen(
            container = container,
            onBack = { screen = Screen.MENU },
            onOpenGame = { game ->
                selectedGame = game
                screen = Screen.REVIEW
            },
        )

        Screen.PHOTO -> PhotoAnalysisScreen(
            container = container,
            onBack = { screen = Screen.MENU },
        )

        Screen.REVIEW -> {
            val game = selectedGame
            if (game == null) {
                screen = Screen.GAMES
            } else {
                GameReviewScreen(
                    container = container,
                    game = game,
                    onBack = { screen = Screen.GAMES },
                )
            }
        }

        Screen.ENGINE -> EngineScreen(
            container = container,
            onBack = {
                screen = if (container.tokenStore.isSignedIn()) Screen.MENU else Screen.LOGIN
            },
        )

        Screen.LIVE -> LiveGameScreen(
            container = container,
            onBack = { screen = Screen.MENU },
        )

        Screen.SETTINGS -> SettingsScreen(
            container = container,
            onBack = { screen = Screen.MENU },
            onSignedOut = { screen = Screen.LOGIN },
        )
    }
}

private const val REDIRECT_SCHEME = "org.chesscoach.app"
