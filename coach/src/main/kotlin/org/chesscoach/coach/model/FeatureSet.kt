package org.chesscoach.coach.model

import com.github.bhlangonijr.chesslib.Side

enum class GamePhase { OPENING, MIDDLEGAME, ENDGAME }

/**
 * Всё, что мы знаем о позиции без обращения к движку.
 *
 * Считается для позиции до хода, после хода и после лучшего ответа соперника —
 * разница между этими наборами и есть ответ на вопрос «почему именно так».
 */
data class FeatureSet(
    val fen: String,
    val phase: GamePhase,
    val facts: List<Fact>,
    /** Материал в сантипешках от лица белых. */
    val materialCentipawns: Int,
    val mobility: Map<Side, Int>,
    val centerControl: Map<Side, Int>,
) {
    fun favouring(side: Side): List<Fact> = facts.filter { it.favours == side }

    inline fun <reified T : Fact> pick(): List<T> = facts.filterIsInstance<T>()

    fun mobilityOf(side: Side): Int = mobility[side] ?: 0

    fun centerControlOf(side: Side): Int = centerControl[side] ?: 0

    companion object {
        fun empty(fen: String): FeatureSet = FeatureSet(
            fen = fen,
            phase = GamePhase.MIDDLEGAME,
            facts = emptyList(),
            materialCentipawns = 0,
            mobility = emptyMap(),
            centerControl = emptyMap(),
        )
    }
}
