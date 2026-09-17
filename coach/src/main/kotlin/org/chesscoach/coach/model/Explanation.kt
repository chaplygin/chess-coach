package org.chesscoach.coach.model

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.Score

/**
 * Тиры причин, в порядке важности для клубного игрока.
 *
 * Порядок не косметический: он решает, о чём тренер скажет первым, когда
 * сработало сразу пять детекторов.
 */
enum class ReasonTier {
    MATERIAL_TACTICS,
    KING_SAFETY,
    PROPHYLAXIS,
    STRUCTURE,
    ACTIVITY,
}

data class Reason(val tier: ReasonTier, val facts: List<Fact>) {
    init { require(facts.isNotEmpty()) { "Причина без фактов — это пустое утверждение" } }
}

enum class PlanIdea {
    OCCUPY_OUTPOST,
    OPEN_FILE_FOR_ROOK,
    ADVANCE_PASSED_PAWN,
    ATTACK_KING,
    TARGET_WEAK_PAWN,
    IMPROVE_WORST_PIECE,
    CENTRALISE,
    TRADE_INTO_ENDGAME,
    PREVENT_BREAK,
}

data class PlanStep(val idea: PlanIdea, val facts: List<Fact>, val evidence: Evidence)

/** Во что обходится ловушка. Считается по материалу, а не пишется от руки. */
enum class MaterialVerdict {
    LOSES_PAWN,
    LOSES_EXCHANGE,
    LOSES_PIECE,
    LOSES_ROOK,
    LOSES_QUEEN,
    GETS_MATED,
    POSITIONAL,
}

/**
 * Характер позиции. Решает, какие советы уместны: в закрытой позиции
 * "вскрыть линию" звучит разумно, а "быстрее разменяться" — нет.
 */
enum class PositionCharacter { OPEN, CLOSED, SEMI_OPEN }

/** Общая идея, которую стоит держать в голове в этой позиции. */
enum class StrategicIdea {
    ATTACK_KING,
    FORCE_EXCHANGES,
    DEFEND,
    IMPROVE_PIECES,
    PUSH_PAWNS,
    SIMPLIFY_TO_ENDGAME,
    OPEN_THE_POSITION,
    KEEP_IT_CLOSED,
}

data class AlternativeLine(
    val moveSan: String,
    val scoreFromWhite: Score,
    val lossVsBestCentipawns: Int,
    val continuationSan: List<String>,
)

/**
 * Разделы разбора. Раздел, для которого не хватило доказательств, просто
 * отсутствует в списке — пустых заголовков и фраз-заполнителей не бывает.
 */
sealed interface Section {

    /**
     * Общая оценка позиции — первое, что читает человек.
     *
     * Отвечает на вопрос "как у меня дела и что вообще делать", прежде чем
     * разговор уйдёт в конкретный ход.
     */
    data class PositionAssessment(
        val materialCentipawns: Int,
        val character: PositionCharacter,
        val ideas: List<StrategicIdea>,
    ) : Section

    /** Моя позиция: за что могут наказать прямо сейчас. */
    data class MyPosition(
        val kingSafety: List<Fact>,
        val loosePieces: List<Fact>,
    ) : Section

    /** Моя атака: чем я могу наказать соперника. */
    data class MyAttack(
        val mate: List<Fact>,
        val captures: List<Fact>,
        val activity: List<Fact>,
    ) : Section

    data class Threat(val threat: ThreatFact) : Section

    /**
     * Разбор хода, сыгранного в партии, в сравнении с лучшим.
     *
     * Ради этого раздела приложение и затевалось: увидеть лучший ход полезно,
     * но понять, чем именно был хуже собственный, — полезнее вдвое.
     */
    data class PlayedMove(
        val playedSan: String,
        val playedUci: String,
        val bestSan: String,
        val bestUci: String,
        val classification: MoveClassification,
        val centipawnLoss: Int,
        /** Чего лишился сыгранный ход по сравнению с лучшим. */
        val missed: List<Fact>,
    ) : Section {
        val matchesBest: Boolean get() = playedUci == bestUci
    }

    /**
     * [pointIsInTheLine] — смысл хода не сводится к признакам позиции.
     *
     * Так бывает у жертв: детекторы видят после хода только мелочи вроде
     * открытой линии, а настоящая причина раскрывается через несколько
     * полуходов. Выдавать мелочь за суть жертвы ладьи хуже, чем честно
     * отослать к варианту.
     */
    data class WhyThisMove(
        val moveSan: String,
        val reasons: List<Reason>,
        val pointIsInTheLine: Boolean = false,
    ) : Section

    data class Prophylaxis(val fact: ProphylaxisFact) : Section

    data class Plan(val steps: List<PlanStep>) : Section

    data class Trap(
        val moveSan: String,
        val lossCentipawns: Int,
        val refutationSan: List<String>,
        val verdict: MaterialVerdict,
    ) : Section

    data class Alternatives(val lines: List<AlternativeLine>) : Section

}

/**
 * Готовый разбор одной позиции — структура фактов, ещё не текст.
 *
 * Тесты проверяют именно её: иначе любая правка формулировки ломала бы тесты,
 * и через месяц их начали бы просто подгонять под вывод.
 */
data class PositionExplanation(
    val fen: String,
    val sideToMove: Side,
    val scoreFromWhite: Score?,
    val bestMoveSan: String,
    val bestMoveUci: String,
    val sections: List<Section>,
    val featuresBefore: FeatureSet,
    val playedMoveSan: String? = null,
    val classification: MoveClassification? = null,
) {
    inline fun <reified T : Section> section(): T? = sections.filterIsInstance<T>().firstOrNull()
}
