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

    /** Продолжение лучшей линии, ход той же стороны: зелёный пунктир. */
    LINE_MINE,

    /** Продолжение лучшей линии, ответ соперника: красный пунктир. */
    LINE_OPPONENT,

    /** Второй ответ соперника: красный пунктир тоньше. */
    LINE_OPPONENT_FAR,
}

/** [label] — номер хода в продолжении лучшей линии: 1 у самого лучшего хода, дальше по порядку. */
data class BoardArrow(val from: String, val to: String, val style: ArrowStyle, val label: Int? = null)

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
        explanation.section<Section.MyPosition>()?.threat?.let { threat ->
            move(threat.opponentMoveUci, ArrowStyle.THREAT)?.let(arrows::add)
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
     * Стрелки с продолжением лучшей линии: лучший ход получает номер 1, дальше
     * пунктиром ответ соперника (2), следующий свой ход (3) и ещё один ответ (4).
     * Линия идёт из позиции до хода: экран показывает её по нажатию на номер.
     */
    fun withContinuation(arrows: List<BoardArrow>, explanation: PositionExplanation): List<BoardArrow> {
        val line = explanation.section<Section.BestMoves>()?.computerLineUci.orEmpty()
        if (line.size < 2) return arrows
        val styles = listOf(ArrowStyle.LINE_OPPONENT, ArrowStyle.LINE_MINE, ArrowStyle.LINE_OPPONENT_FAR)
        val continuation = line.drop(1).take(styles.size).mapIndexedNotNull { index, uci ->
            move(uci, styles[index])?.copy(label = index + 2)
        }
        // Пунктир ложится под сплошные стрелки: продолжение — подсказка, а не главный ход.
        return continuation + arrows.map { if (it.style == ArrowStyle.BEST_MOVE) it.copy(label = 1) else it }
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
