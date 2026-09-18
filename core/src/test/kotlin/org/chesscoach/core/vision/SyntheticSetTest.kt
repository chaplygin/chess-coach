package org.chesscoach.core.vision

import java.util.Locale
import org.chesscoach.core.BoardLayout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рисованный набор обязан совпадать по пропорциям с настоящим.
 *
 * Это ограждение против самой дорогой ошибки в этой части проекта. Раньше
 * эталоны фигур измерялись на той же синтетике, на которой потом проверялась
 * точность: система замыкалась сама на себя и показывала сто процентов, тогда
 * как на первом настоящем скриншоте не узнала ни одной фигуры. Теперь эталоны
 * сняты с настоящего снимка Lichess, а синтетика существует ради другого — ради
 * света, шума, перспективы и прочего, чего на одном снимке не проверишь.
 * Но полезна она только пока похожа на настоящий набор, и здесь это проверяется.
 *
 * Конь в сверку не входит: его не было на присланном скриншоте, и его эталон
 * пока не измерен, а оценён. Как только появится снимок начальной позиции —
 * сюда добавится и он.
 */
class SyntheticSetTest {

    private val calibrationPositions = listOf(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "r2q1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1",
        "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/8/PPPP1PPP/RNBQK1NR",
    )

    private val featureNames = listOf("рост", "ширина", "верх", "середина", "низ", "симметрия", "заполнение")

    /**
     * Допуск на отдельный признак — грубый: настоящую проверку делает второй
     * тест, где рисованная фигура обязана узнаваться таблицей настоящих
     * эталонов. Здесь ловится только заметный уход пропорций, чтобы синтетика
     * не уползла обратно в собственный мир незамеченной.
     */
    private val tolerance = 0.20

    @Test
    fun `рисованные фигуры повторяют пропорции настоящего набора`() {
        val measured = measureSyntheticSet()

        println("=== Рисованный набор против измеренного настоящего ===")
        var worst = 0.0
        var worstLabel = ""
        for (piece in listOf('k', 'q', 'r', 'b', 'p')) {
            val mine = measured.getValue(piece)
            val real = checkNotNull(prototypeSignature(piece))
            val line = StringBuilder("    " + piece + "  ")
            for (i in real.indices) {
                val delta = mine[i] - real[i]
                if (kotlin.math.abs(delta) > worst) {
                    worst = kotlin.math.abs(delta)
                    worstLabel = "$piece/${featureNames[i]}"
                }
                line.append(
                    String.format(
                        Locale.ROOT,
                        "%s %.2f→%.2f (%+.2f)  ",
                        featureNames[i],
                        real[i],
                        mine[i],
                        delta,
                    )
                )
            }
            println(line.toString())
        }
        println(String.format(Locale.ROOT, "    худшее расхождение %.3f на %s", worst, worstLabel))

        assertTrue(
            String.format(
                Locale.ROOT,
                "рисованный набор разошёлся с настоящим на %.3f (%s), допуск %.2f",
                worst,
                worstLabel,
                tolerance,
            ),
            worst <= tolerance,
        )
    }

    @Test
    fun `рисованные фигуры узнаются таблицей настоящих эталонов`() {
        val measured = measureSyntheticSet()
        for ((piece, signature) in measured) {
            val nearest = listOf('k', 'q', 'r', 'b', 'n', 'p').minByOrNull { other ->
                distance(signature, checkNotNull(prototypeSignature(other)))
            }
            assertTrue("рисованная '$piece' ближе к эталону '$nearest', чем к своему", nearest == piece)
        }
    }

    private fun measureSyntheticSet(): Map<Char, DoubleArray> {
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

        return collected.mapValues { (_, samples) ->
            DoubleArray(samples.first().size) { i -> samples.sumOf { it[i] } / samples.size }
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
}
