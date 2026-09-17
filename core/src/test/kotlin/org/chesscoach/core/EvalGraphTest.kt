package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalGraphTest {

    @Test
    fun `cp scores are proportional inside the clamp`() {
        assertEquals(0.5, EvalGraph.fraction(Score.Cp(400)), 0.0001)
        assertEquals(-0.25, EvalGraph.fraction(Score.Cp(-200)), 0.0001)
        assertEquals(0.0, EvalGraph.fraction(Score.Cp(0)), 0.0001)
    }

    @Test
    fun `cp scores clamp at the edges instead of running off scale`() {
        assertEquals(1.0, EvalGraph.fraction(Score.Cp(5000)), 0.0001)
        assertEquals(-1.0, EvalGraph.fraction(Score.Cp(-5000)), 0.0001)
    }

    @Test
    fun `mate pins the line to the edge, not to a huge number`() {
        assertEquals(1.0, EvalGraph.fraction(Score.Mate(4)), 0.0001)
        assertEquals(-1.0, EvalGraph.fraction(Score.Mate(-1)), 0.0001)
    }
}
