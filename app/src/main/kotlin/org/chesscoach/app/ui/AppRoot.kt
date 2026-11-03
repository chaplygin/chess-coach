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

enum class Screen { LOGIN, MENU, GAMES, PHOTO, RECAP, REVIEW, ENGINE, LIVE, SETTINGS }

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
    var reviewPly by remember { mutableStateOf(-1) }
    var openReport by remember { mutableStateOf(false) }

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

    // Сам апдейтер помнит, что уже проверял: поворот экрана не повторит запрос.
    LaunchedEffect(Unit) { container.updater.checkOnStartup() }
    UpdateDialog(container.updater)

    when (screen) {
        Screen.LOGIN -> LoginScreen(
            container = container,
            completingLogin = completingLogin,
            errorMessage = authError,
        )

        Screen.MENU -> MainMenuScreen(
            container = container,
            onOpenGames = {
                openReport = false
                screen = Screen.GAMES
            },
            onOpenReport = {
                openReport = true
                screen = Screen.GAMES
            },
            onOpenGame = { game ->
                selectedGame = game
                screen = Screen.RECAP
            },
            onOpenPhoto = { screen = Screen.PHOTO },
            onOpenLiveGame = { screen = Screen.LIVE },
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.GAMES -> GameListScreen(
            container = container,
            openReport = openReport,
            onBack = { screen = Screen.MENU },
            onOpenGame = { game ->
                selectedGame = game
                screen = Screen.RECAP
            },
        )

        Screen.PHOTO -> PhotoAnalysisScreen(
            container = container,
            onBack = { screen = Screen.MENU },
        )

        Screen.RECAP -> {
            val game = selectedGame
            if (game == null) {
                screen = Screen.GAMES
            } else {
                GameRecapScreen(
                    container = container,
                    game = game,
                    onBack = { screen = Screen.GAMES },
                    onOpenMove = { ply ->
                        reviewPly = ply
                        screen = Screen.REVIEW
                    },
                )
            }
        }

        Screen.REVIEW -> {
            val game = selectedGame
            if (game == null) {
                screen = Screen.GAMES
            } else {
                GameReviewScreen(
                    container = container,
                    game = game,
                    initialPly = reviewPly,
                    onBack = { screen = Screen.RECAP },
                )
            }
        }

        Screen.ENGINE -> EngineScreen(
            container = container,
            onBack = { screen = Screen.SETTINGS },
        )

        Screen.LIVE -> LiveGameScreen(
            container = container,
            onBack = { screen = Screen.MENU },
        )

        Screen.SETTINGS -> SettingsScreen(
            container = container,
            onBack = { screen = Screen.MENU },
            onOpenEngineCheck = { screen = Screen.ENGINE },
            onSignedOut = { screen = Screen.LOGIN },
        )
    }
}

private const val REDIRECT_SCHEME = "org.chesscoach.app"
