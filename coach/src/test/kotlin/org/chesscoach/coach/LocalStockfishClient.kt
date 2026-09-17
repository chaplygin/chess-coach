package org.chesscoach.coach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.core.EngineLine
import org.chesscoach.core.MultiPvCollector
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * Настоящий Stockfish, запущенный на машине разработчика.
 *
 * Нужен для одной вещи, которую нельзя получить никаким тестом на замороженных
 * ответах: прочитать глазами, что тренер говорит на живой позиции. Разбор может
 * быть безупречен структурно и при этом читаться как протокол, и увидеть это
 * можно только на настоящем выводе движка.
 *
 * В общую сборку не попадает: путь берётся из переменной окружения, и без неё
 * тесты, которые сюда обращаются, пропускаются. Недетерминированный тест в
 * общем прогоне — вредитель, потому что рано или поздно его начнут
 * перезапускать до зелёного.
 */
class LocalStockfishClient(binary: File) : EngineClient, AutoCloseable {

    private val process: Process = ProcessBuilder(binary.absolutePath)
        .redirectErrorStream(true)
        .start()

    private val input: BufferedReader = process.inputStream.bufferedReader()
    private val output: BufferedWriter = process.outputStream.bufferedWriter()

    private var configuredMultiPv = -1

    init {
        send("uci")
        readUntil { it.trim() == "uciok" }
        send("setoption name Threads value 2")
        send("setoption name Hash value 64")
        send("isready")
        readUntil { it.trim() == "readyok" }
    }

    override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> =
        withContext(Dispatchers.IO) {
            if (configuredMultiPv != limits.multiPv) {
                send("setoption name MultiPV value " + limits.multiPv)
                send("isready")
                readUntil { it.trim() == "readyok" }
                configuredMultiPv = limits.multiPv
            }

            send("position fen " + fen)
            send("go movetime " + limits.movetimeMs)

            val collector = MultiPvCollector()
            readUntil { line ->
                collector.accept(line)
                line.startsWith("bestmove")
            }
            collector.result()
        }

    private fun send(command: String) {
        output.write(command)
        output.newLine()
        output.flush()
    }

    private fun readUntil(isTerminal: (String) -> Boolean) {
        while (true) {
            val line = input.readLine() ?: return
            if (isTerminal(line)) return
        }
    }

    override fun close() {
        runCatching { send("quit") }
        process.waitFor()
    }

    companion object {
        private const val ENV = "CHESSCOACH_STOCKFISH"

        /** Путь к движку или null, если локального прогона не просили. */
        fun locate(): File? = System.getenv(ENV)
            ?.let(::File)
            ?.takeIf { it.isFile && it.canExecute() }
    }
}
