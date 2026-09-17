package org.chesscoach.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.chesscoach.core.EngineCalibration
import org.chesscoach.core.START_FEN
import org.chesscoach.core.catchingNonCancellation
import org.chesscoach.core.mapCatchingNonCancellation

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

    /** Threads/Hash пользователя и результат разовой калибровки скорости. */
    val settings: EngineSettingsStore by lazy { EngineSettingsStore(context) }

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
                created.initialise(threads = settings.threads(), hashMb = settings.hashMb())
                process = started
                analyzer = created
                calibrateOnce(created)
                created
            }
        }.onFailure { shutdownLocked() }
    }

    /**
     * Разбор позиции, устойчивый к падению движка.
     *
     * Между экраном и [StockfishAnalyzer.analyze] ровно один дополнительный
     * слой: если запрос не удался из-за смерти процесса, тот поднимается
     * заново и запрос повторяется один раз. Если движок падает второй раз
     * подряд — это уже не случайность, и вызывающий получает честную ошибку
     * вместо бесконечного цикла перезапусков.
     */
    suspend fun analyze(fen: String, limits: SearchLimits): Result<AnalysisResult> {
        val first = attemptAnalyze(fen, limits)
        if (first.isSuccess || process?.isAlive == true) return first
        return attemptAnalyze(fen, limits)
    }

    private suspend fun attemptAnalyze(fen: String, limits: SearchLimits): Result<AnalysisResult> =
        analyzer().mapCatchingNonCancellation { it.analyze(fen, limits) }

    /** Верхняя граница MultiPV на этом телефоне — см. [EngineCalibration]. */
    fun multiPvCap(): Int = EngineCalibration.multiPvFor(settings.measuredNps())

    /** Имя и версия движка из рукопожатия UCI, для экрана настроек. */
    suspend fun engineVersion(): Result<String> =
        analyzer().mapCatchingNonCancellation { it.engineId ?: "неизвестно" }

    fun shutdown() = shutdownLocked()

    /**
     * Применяет изменённые Threads/Hash.
     *
     * Тёплый процесс уже настроен на старые значения, а какие из опций
     * Stockfish подхватывает на лету, а какие — только на новую партию,
     * не стоит выяснять опытным путём. Перезапуск при следующем обращении
     * даёт тот же результат надёжно.
     */
    fun restartForSettings() = shutdownLocked()

    private fun shutdownLocked() {
        runCatching { process?.close() }
        process = null
        analyzer = null
    }

    /**
     * Разовый замер скорости движка: `go movetime 200` из начальной позиции.
     * Результат сохраняется навсегда — повторных замеров при следующих
     * холодных стартах не будет, телефон не становится быстрее или медленнее
     * между сессиями.
     */
    private suspend fun calibrateOnce(created: StockfishAnalyzer) {
        if (settings.measuredNps() != null) return

        // Неудачный замер — не повод ронять инициализацию движка целиком:
        // MultiPV просто останется дефолтным, а следующий холодный старт
        // попробует измерить скорость ещё раз.
        catchingNonCancellation {
            created.analyze(START_FEN, SearchLimits(movetimeMs = CALIBRATION_MOVETIME_MS, multiPv = 1))
        }.onSuccess { result ->
            result.nodesPerSecond?.let { settings.saveMeasuredNps(it) }
        }
    }

    private companion object {
        const val CALIBRATION_MOVETIME_MS = 200
    }
}
