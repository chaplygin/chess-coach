package org.chesscoach.lichess

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chesscoach.core.Pkce

object LichessEndpoints {
    const val AUTHORIZE = "https://lichess.org/oauth"
    const val TOKEN = "https://lichess.org/api/token"
    const val API_BASE = "https://lichess.org"

    /**
     * Lichess не требует регистрации приложения: client_id выбирается произвольно
     * и служит лишь тем, что видит пользователь на экране согласия.
     */
    const val CLIENT_ID = "org.chesscoach.app"

    const val REDIRECT_URI = "chesscoach://oauth-callback"
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/** Что положить в хранилище на время, пока пользователь ходит в браузер. */
data class PendingAuthorization(val verifier: String, val state: String)

sealed class AuthError(message: String) : Exception(message) {
    class UserDeclined(reason: String) : AuthError("Доступ не выдан: $reason")
    class StateMismatch : AuthError("Ответ не соответствует запросу — вход отклонён")
    class NoPendingRequest : AuthError("Нет начатого входа, попробуйте ещё раз")
    class TokenExchangeFailed(code: Int, body: String) :
        AuthError("Сервер Lichess отклонил обмен кода: HTTP $code $body")
}

/**
 * Вход в Lichess по схеме Authorization Code с PKCE.
 *
 * Важное решение: **не запрашиваем ни одного повышенного разрешения**. Чтение
 * своего профиля, списка партий и текущих партий работает на обычном токене.
 * Токен без разрешения board:play физически не может сделать ход на аккаунте —
 * это встроенная гарантия честной игры, а не обещание в описании.
 */
class LichessOAuth(
    private val httpClient: OkHttpClient,
    private val tokenStore: TokenStore,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Готовит запрос и возвращает адрес, который нужно открыть в браузере. */
    fun buildAuthorizationUri(): Uri {
        val pending = PendingAuthorization(
            verifier = Pkce.generateVerifier(),
            state = Pkce.generateState(),
        )
        // Сохраняем до открытия браузера: процесс приложения может быть убит,
        // пока пользователь вводит пароль, и verifier должен это пережить.
        tokenStore.savePendingAuthorization(pending)

        return Uri.parse(LichessEndpoints.AUTHORIZE).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", LichessEndpoints.CLIENT_ID)
            .appendQueryParameter("redirect_uri", LichessEndpoints.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Pkce.challengeS256(pending.verifier))
            .appendQueryParameter("state", pending.state)
            .build()
    }

    /** Разбирает возврат из браузера и меняет код на токен. */
    suspend fun completeAuthorization(redirect: Uri): Result<String> = runCatching {
        redirect.getQueryParameter("error")?.let { error ->
            tokenStore.clearPendingAuthorization()
            throw AuthError.UserDeclined(
                redirect.getQueryParameter("error_description") ?: error
            )
        }

        val pending = tokenStore.loadPendingAuthorization() ?: throw AuthError.NoPendingRequest()
        val returnedState = redirect.getQueryParameter("state")
        val code = redirect.getQueryParameter("code")

        // Сверка state — защита от подсунутого чужого ответа. Проверяем до
        // всего остального и в любом случае гасим начатый вход.
        if (returnedState == null || returnedState != pending.state) {
            tokenStore.clearPendingAuthorization()
            throw AuthError.StateMismatch()
        }
        if (code == null) {
            tokenStore.clearPendingAuthorization()
            throw AuthError.UserDeclined("сервер не вернул код")
        }

        val token = exchangeCodeForToken(code, pending.verifier)
        tokenStore.clearPendingAuthorization()
        tokenStore.saveAccessToken(token)
        token
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): String =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", LichessEndpoints.REDIRECT_URI)
                .add("client_id", LichessEndpoints.CLIENT_ID)
                .build()

            val request = Request.Builder()
                .url(LichessEndpoints.TOKEN)
                .header("User-Agent", LichessApi.USER_AGENT)
                .post(form)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AuthError.TokenExchangeFailed(response.code, body.take(300))
                }
                json.decodeFromString<TokenResponse>(body).accessToken
            }
        }
}
