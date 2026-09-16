package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Side

/**
 * Приведение оценки к виду, привычному человеку.
 *
 * Движок всегда считает от лица той стороны, чей ход. Для графика партии и для
 * фразы «у белых лучше» нужна оценка от лица белых, поэтому перевод делается
 * явно и в одном месте — молчаливая путаница со знаком тут самая частая ошибка.
 */
object ScoreFormat {

    fun toWhitePerspective(score: Score, sideToMove: Side): Score =
        if (sideToMove == Side.WHITE) score else negate(score)

    fun negate(score: Score): Score = when (score) {
        is Score.Cp -> Score.Cp(-score.centipawns)
        is Score.Mate -> Score.Mate(-score.movesToMate)
    }

    /** Например: +0.85, -1.20, #3, #-2. */
    fun display(score: Score): String = when (score) {
        is Score.Cp -> {
            val pawns = score.centipawns / 100.0
            if (pawns >= 0) "+%.2f".format(pawns) else "%.2f".format(pawns)
        }

        is Score.Mate -> if (score.movesToMate >= 0) "#${score.movesToMate}" else "#${score.movesToMate}"
    }

    /**
     * Потеря в сантипешках при сравнении сыгранного хода с лучшим.
     *
     * Мат приводится к большому, но конечному числу: иначе любая арифметика с
     * бесконечностью ломает пороги классификации. Упущенный мат всё равно
     * помечается отдельным флагом, а не этим числом.
     */
    fun centipawnLoss(best: Score, played: Score): Int {
        val bestCp = toComparableCp(best)
        val playedCp = toComparableCp(played)
        return (bestCp - playedCp).coerceAtLeast(0)
    }

    private const val MATE_SCALE = 10_000

    private fun toComparableCp(score: Score): Int = when (score) {
        is Score.Cp -> score.centipawns
        is Score.Mate ->
            if (score.movesToMate >= 0) MATE_SCALE - score.movesToMate
            else -MATE_SCALE - score.movesToMate
    }
}
