package org.chesscoach.coach.model

import com.github.bhlangonijr.chesslib.Square

/**
 * Чем рисуется стрелка. Цвет и начертание выбирает экран, здесь только смысл.
 */
enum class ArrowStyle {
    /** Лучший ход по мнению движка. */
    BEST_MOVE,

    /** Ход, сыгранный в партии на самом деле. */
    PLAYED_MOVE,

    /** Что соперник сделает, если ему дать сходить. */
    THREAT,

    /** Следствие лучшего хода: вилка, связка, рентген — то, ради чего он делается. */
    CONSEQUENCE,
}

data class BoardArrow(val from: String, val to: String, val style: ArrowStyle)

/**
 * Перевод разбора в стрелки на доске.
 *
 * Живёт рядом с моделью, а не в экране, по той же причине, что и разбор FEN:
 * это вывод из фактов, а не рисование. Ошибка здесь показала бы стрелку,
 * противоречащую тексту, — а доверие к тренеру держится ровно на том, что
 * доска и слова говорят одно и то же.
 */
object BoardMarks {

    fun of(explanation: PositionExplanation): List<BoardArrow> {
        val arrows = ArrayList<BoardArrow>()

        // Порядок важен: экран рисует в этом же порядке, и лучший ход должен
        // лечь поверх остальных, а не под ними.
        explanation.section<Section.Threat>()?.let { section ->
            move(section.threat.opponentMoveUci, ArrowStyle.THREAT)?.let(arrows::add)
        }

        explanation.section<Section.WhyThisMove>()?.let { section ->
            section.reasons.forEach { reason ->
                reason.facts.forEach { fact -> arrows += consequencesOf(fact) }
            }
        }

        val played = explanation.section<Section.PlayedMove>()
        if (played != null && !played.matchesBest) {
            move(played.playedUci, ArrowStyle.PLAYED_MOVE)?.let(arrows::add)
        }

        move(explanation.bestMoveUci, ArrowStyle.BEST_MOVE)?.let(arrows::add)

        return arrows.distinct()
    }

    /**
     * Следствия факта в виде стрелок «кто кого».
     *
     * Признаки без наглядного направления — повисшая фигура, пешечная слабость,
     * подвижность — стрелок не дают: рисовать их «примерно» значит навязывать
     * доске смысл, которого в факте нет.
     */
    private fun consequencesOf(fact: Fact): List<BoardArrow> = when (fact) {
        is ForkFact -> fact.targets.map { arrow(fact.from, it) }
        is PinFact -> listOf(arrow(fact.pinner, fact.pinned))
        is SkewerFact -> listOf(arrow(fact.attacker, fact.front))
        is DiscoveredAttackFact -> listOf(arrow(fact.revealed, fact.target))
        is BackRankFact -> listOf(arrow(fact.exploiter, fact.king))
        is OverloadedDefenderFact -> fact.duties.map { arrow(fact.defender, it) }
        else -> emptyList()
    }

    private fun arrow(from: Square, to: Square) =
        BoardArrow(name(from), name(to), ArrowStyle.CONSEQUENCE)

    private fun name(square: Square): String = square.value().lowercase()

    /** Ход записи движка вида "e2e4" — в стрелку. Null, если записи нет. */
    private fun move(uci: String, style: ArrowStyle): BoardArrow? {
        if (uci.length < 4) return null
        return BoardArrow(uci.take(2), uci.drop(2).take(2), style)
    }
}
