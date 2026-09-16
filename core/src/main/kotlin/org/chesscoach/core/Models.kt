package org.chesscoach.core

/** Оценка позиции глазами движка. Либо сантипешки, либо форсированный мат. */
sealed interface Score {
    data class Cp(val centipawns: Int) : Score
    data class Mate(val movesToMate: Int) : Score
}

/**
 * Один вариант из вывода движка. Поля заполняются строго из UCI-строк `info ...`
 * и никогда не пересчитываются нашим кодом.
 */
data class EngineLine(
    val multiPvIndex: Int,
    val score: Score,
    val pv: List<String>,
    val depth: Int,
)

/** Насколько сыгранный ход хуже лучшего. Пороги — единственный источник правды. */
enum class MoveClassification(val maxCentipawnLoss: Int) {
    BEST(10),
    GOOD(25),
    INACCURACY(50),
    MISTAKE(100),
    BLUNDER(Int.MAX_VALUE);

    companion object {
        /** Упущенный форсированный мат — всегда зевок, какой бы ни была разница в сантипешках. */
        fun of(centipawnLoss: Int, missedForcedMate: Boolean = false): MoveClassification =
            if (missedForcedMate) BLUNDER
            else entries.first { centipawnLoss < it.maxCentipawnLoss }
    }
}
