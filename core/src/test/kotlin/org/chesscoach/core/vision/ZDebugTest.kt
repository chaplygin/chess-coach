package org.chesscoach.core.vision

import org.junit.Test

class ZDebugTest {
    private val board = SyntheticBoard.render("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", 480)

    @Test
    fun debug() {
        for (tilt in listOf(0, 20)) {
            val scene = PerspectiveScene.render(board = board, tiltDegrees = tilt.toDouble(), spinDegrees = 4.0)
            println("TILT " + tilt + " corners " + scene.corners.map { "" + it.x.toInt() + "," + it.y.toInt() })
            println(debugGeometry(scene.image))
            println()
        }
    }
}
