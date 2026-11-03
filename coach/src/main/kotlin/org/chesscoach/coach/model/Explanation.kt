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

/**
 * Совет, который на первый взгляд спорит с дебютным правилом: второй ход той
 * же фигурой, пока другие не развиты и не сделана рокировка.
 *
 * Это не факт о позиции в смысле [Fact] — это оговорка к конкретной
 * рекомендации, поэтому живёт при [Section.WhyThisMove], а не в наборе
 * признаков. Доказательство всё равно обязательно: поля, с которых фигуры так
 * и не тронулись.
 */
data class OpeningCaveat(
    val movedPiece: com.github.bhlangonijr.chesslib.Piece,
    val from: com.github.bhlangonijr.chesslib.Square,
    val undevelopedSquares: List<com.github.bhlangonijr.chesslib.Square>,
    val castlingPending: Boolean,
    val evidence: Evidence,
)

data class AlternativeLine(
    val moveSan: String,
    val scoreFromWhite: Score,
    val lossVsBestCentipawns: Int,
    val continuationSan: List<String>,
)

/**
 * Что осталось в дебюте: лёгкие фигуры на исходных полях, рокировка и центр.
 * Считается по доске: "развивайте фигуры" без названий проверить нельзя.
 */
data class DevelopmentPlan(
    val undeveloped: List<com.github.bhlangonijr.chesslib.Square>,
    val castlingPending: Boolean,
    val needsCenter: Boolean,
    val evidence: Evidence,
)

/**
 * Удар следующим ходом, проверенный движком. [scoreFromWhite] — оценка после
 * этого хода; у ударов соперника её нет: она считалась бы при пропуске моего хода.
 */
data class CheckedAdvice(val fact: Fact, val scoreFromWhite: Score? = null)

/** Ход, заметный уже при малой глубине расчёта и почти не уступающий лучшему. */
/**
 * Прошлый ход соперника и что он дал: взятие, шах, новые удары и угрозы.
 * Тренер, подсевший к доске, начинает с вопроса "зачем он так сыграл".
 */
data class PreviousMove(
    val san: String,
    val moveNumberLabel: String,
    val captured: com.github.bhlangonijr.chesslib.PieceType?,
    val check: Boolean,
    val facts: List<Fact>,
)

data class HumanMove(
    val lineSan: List<String>,
    val lossVsBestCentipawns: Int,
    val facts: List<Fact>,
    /** Чем ход естественен: по этому объясняется, почему он проще компьютерного. */
    val kind: NaturalMove = NaturalMove.OTHER,
    /** Ход компьютера тихий: не берёт, не шахует и не развивает фигуру. */
    val computerQuiet: Boolean = false,
)

/** Что делает ход, который человек находит сразу. Порядок — от самого заметного. */
enum class NaturalMove { CAPTURE, CHECK, DEVELOPMENT, OTHER }

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
        /**
         * Название дебюта, как его определил Lichess для этой партии.
         *
         * Своего определителя тут нет и не будет: дебютный справочник — это
         * несколько тысяч строк данных, а Lichess уже отдаёт название вместе с
         * партией. Null — значит сервер названия не дал (например, разбирается
         * позиция вне партии), и тогда строки про дебют просто нет.
         */
        val opening: String? = null,
        /**
         * Разница в фигурах по видам: плюс — лишние у белых, минус — у чёрных.
         *
         * Одного числа в сантипешках мало: "заметный перевес" ничего не говорит
         * о том, что именно разменяно, а "у белых лишняя ладья, у чёрных конь и
         * пешка" человек видит на доске и проверяет за секунду.
         */
        val pieceDifference: Map<com.github.bhlangonijr.chesslib.PieceType, Int> = emptyMap(),
        /** Что осталось сделать в дебюте: называет фигуры, а не общие правила. */
        val developmentPlan: DevelopmentPlan? = null,
        /** То же для соперника: неразвитые фигуры и король в центре — это его слабости. */
        val opponentDevelopmentPlan: DevelopmentPlan? = null,
    ) : Section

    /**
     * Моя позиция: за что могут наказать прямо сейчас.
     *
     * Угроза и план соперника живут здесь, а не отдельными разделами: и то и
     * другое отвечает на один вопрос — «что мне грозит». Разнесённые по
     * разбору, они читались как две несвязанные новости.
     *
     * Пункты печатаются все и всегда, даже пустые: «Угроза: нет» — это ответ,
     * а молчание читатель принимает за недосмотр тренера.
     */
    data class MyPosition(
        val kingSafety: List<Fact>,
        val loosePieces: List<Fact>,
        val threat: ThreatFact? = null,
        val opponentPlan: List<PlanStep> = emptyList(),
        val exchanges: List<ExchangeFact> = emptyList(),
        /** Безопасные шахи соперника записью SAN. */
        val checks: List<String> = emptyList(),
        /** Удары следующим ходом, проверенные движком. */
        val advice: List<CheckedAdvice> = emptyList(),
        /** Маты соперника в один ход, будь ход его, записью SAN. */
        val mates: List<String> = emptyList(),
        /** Зачем соперник сделал свой прошлый ход. Null — прошлый ход неизвестен. */
        val previousMove: PreviousMove? = null,
    ) : Section

    /**
     * После хода: оценка позиции, которая на доске, и почему она такая.
     *
     * Разбор до хода объясняет прежнюю оценку, а полоска рядом с доской
     * показывает уже новую. Без этого раздела −4.5 на полоске не объяснено ничем.
     */
    data class AfterMove(
        val moveSan: String,
        val scoreFromWhite: Score?,
        val reason: EvalReason?,
    ) : Section

    /** Моя атака: чем я могу наказать соперника, и к чему веду дело. */
    data class MyAttack(
        val mate: List<Fact>,
        val captures: List<Fact>,
        val activity: List<Fact>,
        val plan: List<PlanStep> = emptyList(),
        val exchanges: List<ExchangeFact> = emptyList(),
        /** Свои безопасные шахи записью SAN. */
        val checks: List<String> = emptyList(),
        /** Удары следующим ходом, проверенные движком. */
        val advice: List<CheckedAdvice> = emptyList(),
        /** Свои маты в один ход записью SAN. */
        val mates: List<String> = emptyList(),
    ) : Section

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
        /** Чем соперник наказывает — его лучший ответ на сыгранное. */
        val refutationSan: String? = null,
        /**
         * Что соперник получил от этого хода: признаки, которых до хода не
         * было. Без них «зевок» — это ярлык с числом, а не объяснение.
         */
        val punishment: List<Fact> = emptyList(),
        /** Оценка после этого хода и после лучшего, от лица белых. */
        val scoreAfterFromWhite: Score? = null,
        val bestScoreFromWhite: Score? = null,
        /** Вариант с этим ходом первым. */
        val lineSan: List<String> = emptyList(),
        val lineUci: List<String> = emptyList(),
        /** Что к концу варианта с материалом ходившего: минус — потерял. Null — вариант кончается посреди размена. */
        val materialSwing: Int? = null,
        /** Что соперник получил к концу варианта: без этого позиционная потеря остаётся без объяснения. */
        val lineGains: List<Fact> = emptyList(),
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
        /** Ход спорит с дебютным правилом развития — это надо оговорить вслух. */
        val openingCaveat: OpeningCaveat? = null,
    ) : Section

    /**
     * Два взгляда на лучший ход: самый сильный по движку и тот, что нашёл бы
     * человек. [human] равен null, когда замены нет или она совпала с лучшим.
     */
    data class BestMoves(
        val computerLineSan: List<String>,
        val human: HumanMove? = null,
        val computerLineUci: List<String> = emptyList(),
    ) : Section

    data class Prophylaxis(val fact: ProphylaxisFact) : Section

    data class Trap(
        val moveSan: String,
        val lossCentipawns: Int,
        val refutationSan: List<String>,
        val verdict: MaterialVerdict,
        val scoreFromWhite: Score? = null,
        /** Ход и опровержение одним вариантом: его можно проиграть на доске. */
        val lineUci: List<String> = emptyList(),
    ) : Section

    data class Alternatives(val lines: List<AlternativeLine>) : Section

    /**
     * Почему оценка такая. Материал в сантипешках от лица белых.
     *
     * [materialSwing] — сколько материала ведущая сторона прибавляет к концу
     * [swingLineSan] (спокойная позиция варианта). Null, если спокойной позиции нет.
     * [tactic] — первый удар в варианте, новый по сравнению с исходной доской.
     */
    data class EvalReason(
        val materialNow: Int,
        val leader: Side?,
        val lineSan: List<String>,
        val materialSwing: Int? = null,
        val swingLineSan: List<String> = emptyList(),
        val tactic: Fact? = null,
        val tacticLineSan: List<String> = emptyList(),
        val positional: List<Fact> = emptyList(),
        val mateIn: Int? = null,
        val matePattern: MatePattern? = null,
        /** Материал к концу варианта, от лица белых: лишний сейчас конь мог быть уже под боем. */
        val materialAfterLine: Int? = null,
    ) : Section
}

/** Рисунок мата, проверенный по итоговой доске. */
enum class MatePattern { SMOTHERED, BACK_RANK }

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
