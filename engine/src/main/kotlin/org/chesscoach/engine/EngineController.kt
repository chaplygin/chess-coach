package org.chesscoach.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.chesscoach.core.catchingNonCancellation

/**
 * Владеет процессом движка на время работы экрана.
 *
 * Процесс запускается лениво и держится тёплым: старт и рукопожатие стоят
 * сотни миллисекунд, а при разборе партии обращений к движку десятки.
 */
class EngineController(private val context: Context) {

    private val startMutex = Mutex()
    private var process: StockfishProcess? = null
    private var analyzer: StockfishAnalyzer? = null

    suspend fun analyzer(): Result<StockfishAnalyzer> = startMutex.withLock {
        analyzer?.let { existing ->
            // Процесс мог умереть, пока экран был свёрнут.
            if (process?.isAlive == true) return@withLock Result.success(existing)
            shutdownLocked()
        }

        val binary = StockfishBinary.locate(context).getOrElse { return@withLock Result.failure(it) }

        // Не runCatching: на отмене он вернул бы Result.failure, и onFailure
        // закрыл бы только что запущенный процесс. Экран, который просто
        // переключили на другой ход, платил бы за это новым запуском движка.
        catchingNonCancellation {
            withContext(Dispatchers.IO) {
                val started = StockfishProcess(binary).apply { start() }
                val created = StockfishAnalyzer(started)
                created.initialise()
                process = started
                analyzer = created
                created
            }
        }.onFailure { shutdownLocked() }
    }

    fun shutdown() = shutdownLocked()

    private fun shutdownLocked() {
        runCatching { process?.close() }
        process = null
        analyzer = null
    }
}
