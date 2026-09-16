package org.chesscoach.lichess

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранилище токена доступа и незавершённого входа.
 *
 * Шифрование ключом из Android Keystore, а не обычные настройки: токен живёт
 * около года и даёт доступ к аккаунту на чтение.
 *
 * Verifier сохраняется до открытия браузера намеренно — система может убить
 * процесс приложения, пока пользователь вводит пароль, и без сохранения обмен
 * кода на токен после возврата стал бы невозможен.
 */
class TokenStore(context: Context) {

    private val preferences: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun accessToken(): String? = preferences.getString(KEY_TOKEN, null)

    fun isSignedIn(): Boolean = accessToken() != null

    fun saveAccessToken(token: String) {
        preferences.edit().putString(KEY_TOKEN, token).apply()
    }

    fun username(): String? = preferences.getString(KEY_USERNAME, null)

    fun saveUsername(name: String) {
        preferences.edit().putString(KEY_USERNAME, name).apply()
    }

    fun signOut() {
        preferences.edit().clear().apply()
    }

    fun savePendingAuthorization(pending: PendingAuthorization) {
        preferences.edit()
            .putString(KEY_VERIFIER, pending.verifier)
            .putString(KEY_STATE, pending.state)
            .apply()
    }

    fun loadPendingAuthorization(): PendingAuthorization? {
        val verifier = preferences.getString(KEY_VERIFIER, null) ?: return null
        val state = preferences.getString(KEY_STATE, null) ?: return null
        return PendingAuthorization(verifier, state)
    }

    fun clearPendingAuthorization() {
        preferences.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
    }

    private companion object {
        const val FILE_NAME = "lichess_auth"
        const val KEY_TOKEN = "access_token"
        const val KEY_USERNAME = "username"
        const val KEY_VERIFIER = "pending_verifier"
        const val KEY_STATE = "pending_state"
    }
}
