package org.chesscoach.engine

import android.content.Context

/**
 * Настройки движка, которые выбирает пользователь: Threads, Hash и результат
 * разовой калибровки скорости под конкретный телефон.
 *
 * Обычные SharedPreferences, а не EncryptedSharedPreferences, как в
 * TokenStore из :lichess: здесь нет ничего похожего на секрет, а шифрование
 * добавило бы код без всякой выгоды.
 */
class EngineSettingsStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun threads(): Int = preferences.getInt(KEY_THREADS, DEFAULT_THREADS)

    fun hashMb(): Int = preferences.getInt(KEY_HASH_MB, DEFAULT_HASH_MB)

    fun saveThreads(value: Int) {
        preferences.edit().putInt(KEY_THREADS, value.coerceIn(MIN_THREADS, MAX_THREADS)).apply()
    }

    fun saveHashMb(value: Int) {
        preferences.edit().putInt(KEY_HASH_MB, value.coerceIn(MIN_HASH_MB, MAX_HASH_MB)).apply()
    }

    /**
     * Скорость движка в нодах в секунду по разовому замеру `go movetime 200`
     * из начальной позиции, либо null, если замера ещё не было.
     */
    fun measuredNps(): Long? {
        val stored = preferences.getLong(KEY_MEASURED_NPS, NO_MEASUREMENT)
        return stored.takeIf { it != NO_MEASUREMENT }
    }

    fun saveMeasuredNps(value: Long) {
        preferences.edit().putLong(KEY_MEASURED_NPS, value).apply()
    }

    companion object {
        private const val FILE_NAME = "engine_settings"
        private const val KEY_THREADS = "threads"
        private const val KEY_HASH_MB = "hash_mb"
        private const val KEY_MEASURED_NPS = "measured_nps"
        private const val NO_MEASUREMENT = -1L

        const val DEFAULT_THREADS = 2
        const val DEFAULT_HASH_MB = 64
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4
        const val MIN_HASH_MB = 16
        const val MAX_HASH_MB = 256
    }
}
