package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Side
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

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

    /**
     * Например: +0.85, -1.20, #3, #-2.
     *
     * Локаль задана явно. По умолчанию разделитель берётся из настроек телефона,
     * и на русском устройстве оценка печаталась бы как «+0,85» — а сверять её
     * пользователь будет с разбором на lichess.org, где всегда точка.
     */
    fun display(score: Score): String = when (score) {
        is Score.Cp -> {
            val pawns = score.centipawns / 100.0
            val body = String.format(Locale.ROOT, "%.2f", pawns)
            if (pawns >= 0) "+" + body else body
        }

        is Score.Mate -> if (score.movesToMate >= 0) "#${score.movesToMate}" else "#${score.movesToMate}"
    }

    /**
     * Короткая оценка для списка ходов: +2.5, -4.3, 0.0, #3, #-2.
     * Поставленный мат приходит предельной оценкой в сантипешках и печатается как #.
     */
    fun short(score: Score): String = when (score) {
        is Score.Mate -> "#${score.movesToMate}"
        is Score.Cp -> when {
            abs(score.centipawns) >= GameAnalyzer.MATED_CP -> "#"
            else -> {
                val body = String.format(Locale.ROOT, "%.1f", score.centipawns / 100.0)
                when {
                    body == "0.0" || body == "-0.0" -> "0.0"
                    score.centipawns > 0 -> "+$body"
                    else -> body
                }
            }
        }
    }

    /**
     * Доля белых на полоске оценки, от 0 до 1. Оценка от лица белых.
     * Кривая та же, что у Lichess: перевес в пешку заметен, а разница между +8 и +9 уже нет.
     */
    fun whiteShare(score: Score): Float = when (score) {
        is Score.Mate -> if (score.movesToMate >= 0) 1f else 0f
        is Score.Cp -> (1.0 / (1.0 + exp(-0.00368208 * score.centipawns))).toFloat()
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
