package org.chesscoach.app

import android.content.Context
import okhttp3.OkHttpClient
import org.chesscoach.engine.EngineController
import org.chesscoach.lichess.LichessApi
import org.chesscoach.lichess.LichessOAuth
import org.chesscoach.lichess.TokenStore

/**
 * Ручная сборка зависимостей.
 *
 * Библиотеки внедрения зависимостей сюда сознательно не берутся: на этом
 * размере они дают только лишний обработчик аннотаций, а перекос его версий —
 * самая частая причина падения сборки, отлаживать которую локально нечем.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    val httpClient: OkHttpClient by lazy { LichessApi.defaultHttpClient() }

    val tokenStore: TokenStore by lazy { TokenStore(applicationContext) }

    val lichessApi: LichessApi by lazy { LichessApi(httpClient, tokenStore) }

    val lichessOAuth: LichessOAuth by lazy { LichessOAuth(httpClient, tokenStore) }

    val engine: EngineController by lazy { EngineController(applicationContext) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
