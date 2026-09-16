package org.chesscoach.lichess

import android.content.Context

/**
 * Единая точка входа в модуль Lichess.
 *
 * Существует затем, чтобы приложение не знало, какой библиотекой сделаны
 * запросы: HTTP-клиент — внутреннее дело этого модуля, и замена его не должна
 * трогать ни один экран.
 */
class LichessClient private constructor(
    val tokenStore: TokenStore,
    val api: LichessApi,
    val oauth: LichessOAuth,
) {
    companion object {
        fun create(context: Context): LichessClient {
            val httpClient = LichessApi.defaultHttpClient()
            val tokenStore = TokenStore(context.applicationContext)
            return LichessClient(
                tokenStore = tokenStore,
                api = LichessApi(httpClient, tokenStore),
                oauth = LichessOAuth(httpClient, tokenStore),
            )
        }
    }
}
