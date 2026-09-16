package org.chesscoach.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над процессом Stockfish, говорящая по протоколу UCI.
 *
 * Процесс запускается один раз и держится тёплым: старт и рукопожатие стоят
 * сотни миллисекунд, а при разборе партии таких обращений десятки.
 *
 * У UCI нет идентификаторов запросов, поэтому корреляция строго
 * последовательная — один [commandMutex] на логический запрос.
 */
class StockfishProcess(private val binary: File) : Closeable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val started = AtomicBoolean(false)
    private val commandMutex = Mutex()

    private val _output = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    /** Последние строки stderr — нужны, чтобы объяснить пользователю причину отказа. */
    private val stderrTail = ArrayDeque<String>()

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        check(started.compareAndSet(false, true)) { "Процесс уже запущен" }

        val proc = ProcessBuilder(binary.absolutePath)
            .directory(binary.parentFile)
            .redirectErrorStream(false)
            .start()
        process = proc
        writer = proc.outputStream.bufferedWriter()

        // readLine() блокирующий, поэтому это выделенный поток, а не корутина.
        thread("stockfish-stdout") {
            proc.inputStream.bufferedReader().forEachLineSafely { _output.tryEmit(it) }
        }
        thread("stockfish-stderr") {
            proc.errorStream.bufferedReader().forEachLineSafely { line ->
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    while (stderrTail.size > 20) stderrTail.removeFirst()
                }
            }
        }
    }

    fun stderrSnapshot(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    fun exitCodeOrNull(): Int? = try {
        process?.exitValue()
    } catch (_: IllegalThreadStateException) {
        null // ещё жив
    }

    /**
     * Отправляет команду и собирает строки ответа до терминальной включительно.
     *
     * Подписка на поток открывается до записи команды ([onSubscription]) — иначе
     * быстрый ответ движка успел бы проскочить мимо коллектора.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun request(
        command: String,
        timeoutMs: Long,
        isTerminal: (String) -> Boolean,
    ): List<String>? = commandMutex.withLock {
        withTimeoutOrNull(timeoutMs) {
            _output
                .onSubscription { writeLine(command) }
                .transformWhile { line ->
                    emit(line)
                    !isTerminal(line)
                }
                .toList()
        }
    }

    suspend fun send(command: String) = commandMutex.withLock { writeLine(command) }

    private fun writeLine(command: String) {
        val w = writer ?: error("Процесс не запущен")
        w.write(command)
        w.write("\n")
        w.flush()
    }

    override fun close() {
        runCatching { writeLine("quit") }
        val proc = process ?: return
        if (!proc.waitForQuietly(timeoutMs = 500)) {
            proc.destroyForcibly()
        }
        process = null
        writer = null
    }
}

private fun thread(name: String, body: () -> Unit) {
    Thread(body, name).apply { isDaemon = true }.start()
}

private inline fun BufferedReader.forEachLineSafely(onLine: (String) -> Unit) {
    try {
        use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        }
    } catch (_: Exception) {
        // Процесс закрыт или убит — поток чтения просто завершается.
    }
}

private fun Process.waitForQuietly(timeoutMs: Long): Boolean = try {
    waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
} catch (_: InterruptedException) {
    false
}
