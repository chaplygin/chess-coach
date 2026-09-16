package org.chesscoach.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MultiPvCollector
import org.chesscoach.core.UciParser

/** Ограничения одного поиска. */
data class SearchLimits(
    val movetimeMs: Int,
    val depth: Int? = null,
    val multiPv: Int = 1,
)

data class AnalysisResult(
    val lines: List<EngineLine>,
    val bestMoveUci: String?,
    val elapsedMs: Long,
    val nodesPerSecond: Long?,
)

/**
 * Поиск на движке.
 *
 * `movetime` задаётся всегда и работает как жёсткий предел, `depth` — только
 * как мягкая цель: телефоны слишком разные по скорости, и поиск, ограниченный
 * лишь глубиной, на слабом устройстве думал бы неприемлемо долго.
 */
class StockfishAnalyzer(private val process: StockfishProcess) {

    private var configuredMultiPv: Int = -1

    suspend fun initialise(threads: Int = DEFAULT_THREADS, hashMb: Int = DEFAULT_HASH_MB) {
        withContext(Dispatchers.IO) {
            process.request("uci", HANDSHAKE_TIMEOUT_MS) { it.trim() == "uciok" }
                ?: error("Движок не ответил uciok")

            process.send("setoption name Threads value $threads")
            process.send("setoption name Hash value $hashMb")

            process.request("isready", HANDSHAKE_TIMEOUT_MS) { it.trim() == "readyok" }
                ?: error("Движок не ответил readyok")
        }
    }

    suspend fun analyze(fen: String, limits: SearchLimits): AnalysisResult =
        withContext(Dispatchers.IO) {
            if (configuredMultiPv != limits.multiPv) {
                process.send("setoption name MultiPV value ${limits.multiPv}")
                process.request("isready", HANDSHAKE_TIMEOUT_MS) { it.trim() == "readyok" }
                configuredMultiPv = limits.multiPv
            }

            val go = buildString {
                append("go movetime ${limits.movetimeMs}")
                limits.depth?.let { append(" depth $it") }
            }

            val startedAt = System.currentTimeMillis()
            // Запас поверх movetime: движку нужно время свернуть поиск и ответить.
            val timeout = limits.movetimeMs + SEARCH_TIMEOUT_SLACK_MS

            val output = process.request(
                setup = listOf("position fen $fen"),
                command = go,
                timeoutMs = timeout,
            ) { it.startsWith("bestmove") }

            val elapsed = System.currentTimeMillis() - startedAt

            if (output == null) {
                // Поиск не завершился в срок. Просим остановиться, чтобы движок
                // не остался считать в фоне и не испортил следующий запрос.
                process.stopSearch()
                return@withContext AnalysisResult(emptyList(), null, elapsed, null)
            }

            val collector = MultiPvCollector().acceptAll(output)
            val bestMove = output.firstNotNullOfOrNull(UciParser::parseBestMove)
            val nps = output.lastNotNullOfOrNull { UciParser.parseNumericField(it, "nps") }

            AnalysisResult(
                lines = collector.result(),
                bestMoveUci = bestMove,
                elapsedMs = elapsed,
                nodesPerSecond = nps,
            )
        }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val SEARCH_TIMEOUT_SLACK_MS = 4_000L
        const val DEFAULT_THREADS = 2
        const val DEFAULT_HASH_MB = 64
    }
}

private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? {
    for (index in indices.reversed()) {
        transform(this[index])?.let { return it }
    }
    return null
}
