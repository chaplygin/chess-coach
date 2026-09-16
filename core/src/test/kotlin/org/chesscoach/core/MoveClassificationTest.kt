package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveClassificationTest {

    @Test
    fun `thresholds map centipawn loss to labels`() {
        assertEquals(MoveClassification.BEST, MoveClassification.of(0))
        assertEquals(MoveClassification.BEST, MoveClassification.of(9))
        assertEquals(MoveClassification.GOOD, MoveClassification.of(10))
        assertEquals(MoveClassification.INACCURACY, MoveClassification.of(25))
        assertEquals(MoveClassification.MISTAKE, MoveClassification.of(50))
        assertEquals(MoveClassification.BLUNDER, MoveClassification.of(100))
        assertEquals(MoveClassification.BLUNDER, MoveClassification.of(5000))
    }

    @Test
    fun `missed forced mate is always a blunder`() {
        assertEquals(MoveClassification.BLUNDER, MoveClassification.of(0, missedForcedMate = true))
    }
}
