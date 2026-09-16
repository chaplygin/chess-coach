package org.chesscoach.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Proof Key for Code Exchange (RFC 7636) — защита обмена кода авторизации.
 *
 * Нужна потому, что у мобильного приложения нет и не может быть секрета:
 * всё, что зашито в APK, считается общедоступным. Вместо секрета приложение
 * доказывает, что код авторизации получило именно оно, предъявив прообраз
 * ранее отправленного хеша.
 *
 * Живёт в чистом JVM-модуле, чтобы проверяться тестами по эталонным векторам
 * из самого RFC, а не «на глаз» на телефоне.
 */
object Pkce {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Случайный code verifier.
     *
     * RFC требует от 43 до 128 символов из безопасного алфавита. 32 байта
     * энтропии дают ровно 43 символа в base64url — нижняя граница диапазона
     * и достаточная стойкость.
     */
    fun generateVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    /** Случайный параметр state — защита от подмены ответа (CSRF). */
    fun generateState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    /**
     * Code challenge методом S256: base64url(SHA-256(verifier)).
     *
     * Метод `plain` не поддерживаем сознательно — он не даёт защиты, а Lichess
     * его и не принимает.
     */
    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }

    /** Verifier должен укладываться в требования RFC, иначе сервер откажет. */
    fun isValidVerifier(verifier: String): Boolean =
        verifier.length in 43..128 && verifier.all { it.isUnreservedPkceChar() }

    private fun Char.isUnreservedPkceChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '-' || this == '.' || this == '_' || this == '~'
}
