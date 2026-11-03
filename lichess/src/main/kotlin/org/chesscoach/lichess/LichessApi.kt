package org.chesscoach.lichess

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chesscoach.core.GameSummary
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Lichess ответил «слишком часто». Повтор допустим не раньше указанного времени. */
class RateLimitedException(val retryAfterSeconds: Long) :
    IOException("Слишком много запросов к Lichess, повтор через $retryAfterSeconds с")

class UnauthorizedException : IOException("Токен Lichess недействителен, нужен повторный вход")

/**
 * Клиент публичного API Lichess.
 *
 * Все обращения идут через один замок: в рекомендациях Lichess прямо сказано
 * делать не больше одного запроса за раз, а параллельные всплески — верный путь
 * получить 429 и временную блокировку.
 */
class LichessApi internal constructor(
    private val httpClient: OkHttpClient,
    private val tokenStore: TokenStore,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()

    suspend fun account(): LichessAccount = withContext(Dispatchers.IO) {
        val body = get("${LichessEndpoints.API_BASE}/api/account")
        json.decodeFromString<AccountDto>(body).toDomain()
    }

    /**
     * Последние партии аккаунта.
     *
     * Ответ приходит в формате ndjson — по одной партии на строку, — поэтому
     * разбирается построчно: так одна испорченная запись не роняет весь список.
     */
    /** [sinceEpochMs] — только партии не старше этого момента: окно анализа ошибок. */
    suspend fun recentGames(username: String, max: Int = 50, sinceEpochMs: Long? = null): List<GameSummary> =
        withContext(Dispatchers.IO) {
            val url = "${LichessEndpoints.API_BASE}/api/games/user/$username" +
                "?max=$max&moves=true&opening=true&clocks=true&sort=dateDesc" +
                (sinceEpochMs?.let { "&since=$it" } ?: "")

            val body = get(url, accept = "application/x-ndjson")

            body.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<GameDto>(line) }.getOrNull()
                }
                .mapNotNull { it.toDomain(username) }
                .toList()
        }

    /**
     * Текущие партии аккаунта.
     *
     * Используется только для режима подсказок против компьютера. Поле
     * opponent.ai присутствует лишь у партий с движком Lichess — именно по нему
     * проводится граница честной игры, и проверка делается в коде до любого
     * обращения к движку.
     */
    suspend fun nowPlaying(): List<OngoingGame> = withContext(Dispatchers.IO) {
        val body = get("${LichessEndpoints.API_BASE}/api/account/playing?nb=10")
        json.decodeFromString<NowPlayingDto>(body).nowPlaying.map { it.toDomain() }
    }

    private suspend fun get(url: String, accept: String = "application/json"): String =
        requestMutex.withLock {
            val token = tokenStore.accessToken() ?: throw UnauthorizedException()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            ?: DEFAULT_RETRY_AFTER_SECONDS
                        throw RateLimitedException(retryAfter)
                    }

                    response.code == 401 -> throw UnauthorizedException()

                    !response.isSuccessful ->
                        throw IOException("Lichess вернул HTTP ${response.code}")

                    else -> response.body?.string().orEmpty()
                }
            }
        }

    companion object {
        /**
         * Только латиница и цифры.
         *
         * Значения заголовков HTTP передаются однобайтовой кодировкой, и
         * кириллица в них не проходит: OkHttp отвергает такой заголовок ещё до
         * отправки запроса. Русское пояснение тут стоило входа в аккаунт —
         * браузер до Lichess доходил, а обмен кода на токен падал уже внутри
         * приложения.
         */
        const val USER_AGENT = "ChessCoach/0.1 (Android; personal game review)"
        private const val DEFAULT_RETRY_AFTER_SECONDS = 60L

        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
