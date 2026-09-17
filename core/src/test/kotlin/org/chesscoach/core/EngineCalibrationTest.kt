package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineCalibrationTest {

    @Test
    fun `no measurement yet keeps the default MultiPV`() {
        assertEquals(EngineCalibration.DEFAULT_MULTI_PV, EngineCalibration.multiPvFor(null))
    }

    @Test
    fun `slow hardware reduces MultiPV`() {
        assertEquals(EngineCalibration.REDUCED_MULTI_PV, EngineCalibration.multiPvFor(100_000L))
    }

    @Test
    fun `fast hardware keeps the default MultiPV`() {
        assertEquals(EngineCalibration.DEFAULT_MULTI_PV, EngineCalibration.multiPvFor(2_000_000L))
    }

    @Test
    fun `threshold is the first value that no longer counts as weak`() {
        val threshold = EngineCalibration.WEAK_HARDWARE_NPS_THRESHOLD
        assertEquals(EngineCalibration.REDUCED_MULTI_PV, EngineCalibration.multiPvFor(threshold - 1))
        assertEquals(EngineCalibration.DEFAULT_MULTI_PV, EngineCalibration.multiPvFor(threshold))
    }
}
