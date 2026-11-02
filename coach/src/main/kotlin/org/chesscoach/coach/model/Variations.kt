package org.chesscoach.coach.model

import org.chesscoach.coach.analysis.applyUciMove
import org.chesscoach.core.MoveNotation

/**
 * Вариант, который можно проиграть на доске. [fens] — позиция после каждого хода,
 * на один элемент на ход: читать пять ходов в уме трудно.
 */
data class Variation(
    val title: String,
    val startFen: String,
    val san: List<String>,
    val uci: List<String>,
    val fens: List<String>,
)

/** Какие варианты разбора можно показать. Как и стрелки, это вывод из фактов, а не рисование. */
object Variations {

    fun of(explanation: PositionExplanation, playedTitle: String = "Сыгранный ход"): List<Variation> {
        val fen = explanation.fen
        return listOfNotNull(
            explanation.section<Section.BestMoves>()?.let { build("Лучший ход", fen, it.computerLineUci) },
            explanation.section<Section.PlayedMove>()?.takeUnless { it.matchesBest }?.let { build(playedTitle, fen, it.lineUci) },
            explanation.section<Section.Trap>()?.let { build("Если сыграть иначе", fen, it.lineUci) },
        )
    }

    private fun build(title: String, fen: String, uci: List<String>): Variation? {
        val fens = ArrayList<String>()
        var current = fen
        for (move in uci) {
            current = applyUciMove(current, move) ?: break
            fens += current
        }
        if (fens.isEmpty()) return null
        val played = uci.take(fens.size)
        return Variation(title, fen, MoveNotation.uciToSanOrRaw(fen, played), played, fens)
    }
}
