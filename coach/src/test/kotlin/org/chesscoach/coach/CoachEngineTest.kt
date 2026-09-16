package org.chesscoach.coach

import org.chesscoach.core.MoveClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachEngineTest {

    @Test
    fun `coach module sees core module`() {
        assertEquals(MoveClassification.BEST, MoveClassification.of(1))
        assertEquals("M0-skeleton", CoachEngine.VERSION)
    }
}
