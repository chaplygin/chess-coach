package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import kotlin.math.exp

/**
 * Заголовок разобранного хода: "8. Qh5+ — зевок: +0.5 → -2.9".
 *
 * Обе оценки после хода, от лица белых: слева оценка после предыдущего хода,
 * то есть до этого, справа после него. Это те же числа, что стоят под ходами в
 * ленте и на графике, поэтому на экране у одной позиции одно число.
 */
object MoveHeadline {

    fun of(ply: PlyPosition, before: Score?, after: Score?, classification: MoveClassification?): String {
        val move = moveLabel(ply)
        val label = classification?.let { " — " + label(it) }.orEmpty()
        val scores = when {
            before != null && after != null -> ": " + ScoreFormat.short(before) + " → " + ScoreFormat.short(after)
            after != null -> ": " + ScoreFormat.short(after)
            else -> ""
        }
        return move + label + scores
    }

    /** "8. Qh5+" или "8... g6", как ход пишут в записи партии. */
    fun moveLabel(ply: PlyPosition): String =
        ply.moveNumber.toString() + (if (ply.whiteMoved) ". " else "... ") + ply.san

    fun label(classification: MoveClassification): String = when (classification) {
        MoveClassification.BEST -> "лучший ход"
        MoveClassification.GOOD -> "хороший ход"
        MoveClassification.INACCURACY -> "неточность"
        MoveClassification.MISTAKE -> "ошибка"
        MoveClassification.BLUNDER -> "зевок"
    }
}

/** Конец партии на доске: мат или пат. Тут движку считать нечего, ответ даёт сама доска. */
object GameEnd {

    /** "Мат. Победили чёрные." или "Пат. Ничья." Null — ходы есть, партия на доске не кончилась. */
    fun of(fen: String): String? {
        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return null
        if (board.legalMoves().isNotEmpty()) return null
        if (!board.isMated) return "Пат. Ничья."
        return if (board.sideToMove == Side.WHITE) "Мат. Победили чёрные." else "Мат. Победили белые."
    }
}

/** Шанс на победу в процентах от лица белых, как его считает Lichess: перевес в пешку заметен, +8 и +9 почти равны. */
object WinChance {

    fun white(score: Score): Double = when (score) {
        is Score.Mate -> if (score.movesToMate >= 0) 100.0 else 0.0
        is Score.Cp -> when {
            score.centipawns >= GameAnalyzer.MATED_CP -> 100.0
            score.centipawns <= -GameAnalyzer.MATED_CP -> 0.0
            else -> 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * score.centipawns)) - 1.0)
        }
    }

    fun forSide(score: Score, white: Boolean): Double = if (white) white(score) else 100.0 - white(score)
}
