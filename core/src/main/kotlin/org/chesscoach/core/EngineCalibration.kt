package org.chesscoach.core

/**
 * Решает, сколько вариантов (MultiPV) движок может считать одновременно на
 * конкретном телефоне.
 *
 * Три линии стоят заметно дороже одной: при том же movetime поиск по трём
 * успевает пройти меньшую глубину, чем поиск по одной. Порог взят с большим
 * запасом вниз от типичной скорости телефона разработчика (около 2 млн
 * nps) — это резерв на будущее слабое устройство, а не текущая необходимость.
 */
object EngineCalibration {
    const val DEFAULT_MULTI_PV = 3
    const val REDUCED_MULTI_PV = 2
    const val WEAK_HARDWARE_NPS_THRESHOLD = 500_000L

    /** [measuredNps] — результат разового замера `go movetime 200`, либо null, если замера ещё не было. */
    fun multiPvFor(measuredNps: Long?): Int =
        if (measuredNps != null && measuredNps < WEAK_HARDWARE_NPS_THRESHOLD) REDUCED_MULTI_PV else DEFAULT_MULTI_PV
}
