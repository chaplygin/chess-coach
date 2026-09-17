package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PlanIdea
import org.chesscoach.coach.model.PlanStep

/**
 * План на позицию: 1–3 идеи, и каждая опирается на уже добытый факт, а не на
 * общие слова. Пустой список — нормальный ответ, если фактов, за которые
 * можно зацепиться, попросту нет.
 *
 * Порядок идей — от самой конкретной к самой общей: занятый форпост держать,
 * проходную двигать, слабость соперника продолжать бить, открытую линию под
 * своей ладьёй — не отдавать. Если кандидатов больше, чем помещается в план,
 * берём первых по этому порядку, а не все подряд.
 */
object PlanBuilder {

    private const val MAX_PLAN_STEPS = 3

    fun build(features: FeatureSet, forSide: Side): List<PlanStep> {
        val steps = ArrayList<PlanStep>()

        // Форпост уже занят нашей фигурой — держать его.
        features.pick<OutpostFact>()
            .filter { it.owner == forSide }
            .forEach { steps += PlanStep(PlanIdea.OCCUPY_OUTPOST, listOf(it), it.evidence) }

        // Проходная пешка — двигать вперёд.
        features.pick<PassedPawnFact>()
            .filter { it.owner == forSide }
            .forEach { steps += PlanStep(PlanIdea.ADVANCE_PASSED_PAWN, listOf(it), it.evidence) }

        // Слабая пешка соперника — продолжать давить на неё.
        features.pick<PawnWeaknessFact>()
            .filter { it.owner == forSide.flip() }
            .forEach { steps += PlanStep(PlanIdea.TARGET_WEAK_PAWN, listOf(it), it.evidence) }

        // Ладья уже вышла на открытую (или полуоткрытую) линию — держать её там.
        features.pick<OpenFileRookFact>()
            .filter { it.owner == forSide }
            .forEach { steps += PlanStep(PlanIdea.OPEN_FILE_FOR_ROOK, listOf(it), it.evidence) }

        return steps.take(MAX_PLAN_STEPS)
    }
}
