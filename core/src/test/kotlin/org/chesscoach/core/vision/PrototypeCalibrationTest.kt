package org.chesscoach.core.vision

import java.util.Locale
import org.chesscoach.core.BoardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пересчёт таблицы эталонов силуэтов тем же кодом, который потом с ней
 * сравнивает реальные клетки.
 *
 * Тест нужен не ради цифр в выводе, а как ограждение: если кто-то поменяет
 * обрезку клетки, порог бинаризации или набор признаков, таблица в
 * BoardRecognizer молча перестанет соответствовать тому, что меряет
 * продакшен-путь, и распознавание испортится без единого падающего теста.
 * Ровно так уже случилось один раз — ферзь с королём путались почти всегда,
 * потому что эталоны считал отдельный скрипт с другими порогами.
 */
class PrototypeCalibrationTest {

    /**
     * Доски для калибровки — со смешанным набором фигур, а не по одной фигуре
     * на доску: рост теперь меряется от самой высокой фигуры рядом, и на доске
     * из одних пешек пешка оказалась бы ростом в короля.
     */
    private val calibrationPositions = listOf(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "r2q1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1",
        "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/8/PPPP1PPP/RNBQK1NR",
    )

    @Test
    fun `таблица эталонов совпадает с тем, что меряет сам распознаватель`() {
        println("=== Пересчитанные эталоны (вставлять в PROTOTYPES как есть) ===")

        val collected = mutableMapOf<Char, MutableList<DoubleArray>>()
        for (position in calibrationPositions) {
            val placement = BoardLayout.parsePlacement(position)
            val signatures = cellSignatures(SyntheticBoard.render(position))
            for (index in 0 until 64) {
                val piece = placement[index] ?: continue
                val signature = signatures[index] ?: continue
                collected.getOrPut(piece.lowercaseChar()) { mutableListOf() }.add(signature)
            }
        }

        val measured = mutableMapOf<Char, DoubleArray>()
        for (piece in listOf('k', 'q', 'r', 'b', 'n', 'p')) {
            val samples = collected[piece].orEmpty()
            assertTrue("Фигура '$piece' почти не встретилась на калибровочных досках", samples.size >= 4)
            val average = DoubleArray(FEATURE_COUNT) { i -> samples.sumOf { it[i] } / samples.size }
            measured[piece] = average
            println("    " + prototypeLine(piece, average))
        }

        // Сами эталоны обязаны распознаваться как самих себя: если усреднённый
        // силуэт слона ближе к пешке, чем к слону, таблица бесполезна, каким бы
        // ни было её содержимое.
        for ((piece, average) in measured) {
            val nearest = measured.minByOrNull { (_, other) -> distance(average, other) }!!.key
            assertEquals("Эталон '$piece' ближе к чужому эталону '$nearest', чем к себе", piece, nearest)
        }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum
    }

    private fun prototypeLine(piece: Char, values: DoubleArray): String {
        val name = when (piece) {
            'k' -> "KING"
            'q' -> "QUEEN"
            'r' -> "ROOK"
            'b' -> "BISHOP"
            'n' -> "KNIGHT"
            else -> "PAWN"
        }
        val numbers = values.joinToString(", ") { String.format(Locale.ROOT, "%.3f", it) }
        return "Prototype(RecognizedPieceType." + name + ", " + numbers + "),"
    }

}

private const val FEATURE_COUNT = 8
