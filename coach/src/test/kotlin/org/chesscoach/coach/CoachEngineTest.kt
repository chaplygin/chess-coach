package org.chesscoach.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEngineTest {

    @Test
    fun `reports its version`() {
        assertEquals("M5", CoachEngine.VERSION)
    }

    @Test
    fun `assembles the full set of detectors`() {
        // Детектор, забытый в этом списке, нигде больше не всплывёт: разбор
        // просто станет беднее, и заметить это по тестам самих детекторов
        // невозможно — они-то проходят.
        assertEquals(17,CoachEngine.ALL_DETECTORS.size)
        assertEquals(
            CoachEngine.ALL_DETECTORS.size,
            CoachEngine.ALL_DETECTORS.map { it.name }.distinct().size,
        )
        assertTrue(CoachEngine.ALL_DETECTORS.all { it.name.isNotBlank() })
    }
}
