package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.test.runTest
import org.chesscoach.coach.engine.FakeEngineClient
import org.chesscoach.coach.model.MaterialVerdict
import org.chesscoach.core.EngineLine
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrapSelectorTest {

    private val trapFen = "4k3/3p4/8/8/8/8/8/3RK3 w - - 0 1"
    private val afterTrapFen = "4k3/3R4/8/8/8/8/8/4K3 b - - 0 1"

    @Test
    fun `trap is not printed when the loss is below the threshold`() = runTest {
        val mainLines = listOf(
            EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("e1e2"), depth = 20),
            // Потеря всего 40 — заметно ниже TRAP_MIN_LOSS_CP (100).
            EngineLine(multiPvIndex = 2, score = Score.Cp(-30), pv = listOf("d1d7"), depth = 20),
        )

        // Пустая карта нарочно строгая: если бы код всё же попытался искать
        // опровержение при недостаточной потере, вызов бы тут же упал.
        val engine = FakeEngineClient(emptyMap())
        val trap = TrapSelector(engine).select(trapFen, Side.WHITE, mainLines, emptyList())

        assertNull(trap)
    }

    @Test
    fun `trap fires for a natural-looking capture that hangs the rook back`() = runTest {
        val mainLines = listOf(
            EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("e1e2"), depth = 20),
            // "Съесть пешку" — естественный ход (взятие), но ладья тут же гибнет
            // от короля: 110 сантипешек потери, с запасом выше порога.
            EngineLine(multiPvIndex = 2, score = Score.Cp(-100), pv = listOf("d1d7"), depth = 20),
        )
        val engine = FakeEngineClient(
            mapOf(
                afterTrapFen to listOf(
                    EngineLine(
                        multiPvIndex = 1,
                        score = Score.Cp(400),
                        pv = listOf("e8d7", "e1d2"),
                        depth = 10,
                    ),
                ),
            ),
        )

        val trap = TrapSelector(engine).select(trapFen, Side.WHITE, mainLines, emptyList())

        checkNotNull(trap)
        assertEquals(110, trap.lossCentipawns)
        assertEquals(listOf("Kxd7", "Kd2"), trap.refutationSan)
        assertEquals(MaterialVerdict.LOSES_PIECE, trap.verdict)
    }

    @Test
    fun `trap does not fire when there is no alternative to the best move`() = runTest {
        val mainLines = listOf(
            EngineLine(multiPvIndex = 1, score = Score.Cp(10), pv = listOf("e1e2"), depth = 20),
        )

        val engine = FakeEngineClient(emptyMap())
        val trap = TrapSelector(engine).select(trapFen, Side.WHITE, mainLines, emptyList())

        assertNull(trap)
    }
}
