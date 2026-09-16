package org.chesscoach.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Результат проверки «движок вообще стартует на этом телефоне».
 *
 * Это самое рискованное допущение всего проекта: если запуск нативного
 * бинарника не сработает, потребуется другой движок. Поэтому проверка
 * отдельная, подробная и выполняется раньше любой шахматной логики.
 */
data class EngineSmokeReport(
    val ok: Boolean,
    val binaryPath: String?,
    val binarySizeBytes: Long?,
    val engineName: String?,
    val uciOk: Boolean,
    val readyOk: Boolean,
    val supportedAbis: List<String>,
    val diagnostics: List<String>,
) {
    val binarySizeMb: String
        get() = binarySizeBytes?.let { "%.1f МБ".format(it / 1024.0 / 1024.0) } ?: "—"
}

object EngineSmokeTest {

    private const val HANDSHAKE_TIMEOUT_MS = 5_000L

    suspend fun run(context: Context): EngineSmokeReport = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        val abis = Build.SUPPORTED_ABIS.toList()
        diagnostics += "ABI устройства: ${abis.joinToString()}"

        val binary = StockfishBinary.locate(context).getOrElse { error ->
            diagnostics += "Бинарник не найден: ${error.message}"
            return@withContext EngineSmokeReport(
                ok = false,
                binaryPath = null,
                binarySizeBytes = null,
                engineName = null,
                uciOk = false,
                readyOk = false,
                supportedAbis = abis,
                diagnostics = diagnostics,
            )
        }
        diagnostics += "Бинарник: ${binary.absolutePath}"

        val engine = StockfishProcess(binary)
        try {
            engine.start()
            diagnostics += "Процесс запущен"

            val uciLines = engine.request("uci", HANDSHAKE_TIMEOUT_MS) { it.trim() == "uciok" }
            if (uciLines == null) {
                diagnostics += "Таймаут: ответа uciok не было за ${HANDSHAKE_TIMEOUT_MS} мс"
                diagnostics += describeFailure(engine)
                return@withContext EngineSmokeReport(
                    ok = false,
                    binaryPath = binary.absolutePath,
                    binarySizeBytes = binary.length(),
                    engineName = null,
                    uciOk = false,
                    readyOk = false,
                    supportedAbis = abis,
                    diagnostics = diagnostics,
                )
            }

            val engineName = uciLines
                .firstOrNull { it.startsWith("id name ") }
                ?.removePrefix("id name ")
                ?.trim()
            diagnostics += "Получено uciok, строк в ответе: ${uciLines.size}"

            val readyLines = engine.request("isready", HANDSHAKE_TIMEOUT_MS) { it.trim() == "readyok" }
            val readyOk = readyLines != null
            diagnostics += if (readyOk) "Получено readyok" else "Таймаут: readyok не пришёл"

            EngineSmokeReport(
                ok = readyOk,
                binaryPath = binary.absolutePath,
                binarySizeBytes = binary.length(),
                engineName = engineName,
                uciOk = true,
                readyOk = readyOk,
                supportedAbis = abis,
                diagnostics = diagnostics,
            )
        } catch (e: Exception) {
            diagnostics += "Исключение: ${e::class.java.simpleName}: ${e.message}"
            diagnostics += describeFailure(engine)
            EngineSmokeReport(
                ok = false,
                binaryPath = binary.absolutePath,
                binarySizeBytes = binary.length(),
                engineName = null,
                uciOk = false,
                readyOk = false,
                supportedAbis = abis,
                diagnostics = diagnostics,
            )
        } finally {
            engine.close()
        }
    }

    private fun describeFailure(engine: StockfishProcess): String {
        val exit = engine.exitCodeOrNull()
        val stderr = engine.stderrSnapshot()
        return buildString {
            append(if (exit == null) "Процесс ещё жив" else "Процесс завершился с кодом $exit")
            if (stderr.isNotEmpty()) {
                append("\nstderr:\n")
                append(stderr.joinToString("\n"))
            }
        }
    }
}
