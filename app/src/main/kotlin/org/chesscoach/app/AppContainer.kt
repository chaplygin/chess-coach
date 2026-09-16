package org.chesscoach.app

import android.content.Context
import org.chesscoach.engine.EngineController
import org.chesscoach.lichess.LichessApi
import org.chesscoach.lichess.LichessClient
import org.chesscoach.lichess.LichessOAuth
import org.chesscoach.lichess.TokenStore

/**
 * Ручная сборка зависимостей.
 *
 * Библиотеки внедрения зависимостей сюда сознательно не берутся: на этом
 * размере они дают только лишний обработчик аннотаций, а перекос его версий —
 * самая частая причина падения сборки, отлаживать которую локально нечем.
 *
 * HTTP-клиент здесь не упоминается намеренно: какой библиотекой сделаны
 * запросы — внутреннее дело модуля :lichess.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    private val lichess: LichessClient by lazy { LichessClient.create(applicationContext) }

    val tokenStore: TokenStore get() = lichess.tokenStore

    val lichessApi: LichessApi get() = lichess.api

    val lichessOAuth: LichessOAuth get() = lichess.oauth

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
