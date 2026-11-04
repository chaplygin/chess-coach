package org.chesscoach.coach.model

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.core.EngineLine

/**
 * Доказательство факта: конкретные поля доски и/или строка движка.
 *
 * Пустым быть не может. Факт без опоры — это и есть выдумка, а весь смысл
 * такого устройства в том, чтобы выдумка не собиралась, а не «встречалась редко».
 */
data class Evidence(
    val squares: List<Square> = emptyList(),
    val engineLine: EngineLine? = null,
    val seeCentipawns: Int? = null,
    /**
     * Кто стоял на этих полях в тот момент, когда факт был найден.
     *
     * Без этого тренер вынужден говорить «под боем сразу d8 и e6» вместо
     * «нападает на ферзя d8 и слона e6» — а именно называние фигур и отличает
     * человеческое объяснение от машинного. Подсмотреть их позже по позиции
     * нельзя: факты об угрозе относятся к доске после чужого хода, и фигура
     * на том же поле там уже может быть другой.
     */
    val pieces: Map<Square, Piece> = emptyMap(),
) {
    init {
        require(squares.isNotEmpty() || engineLine != null) {
            "Факт без доказательства создавать нельзя"
        }
    }

    /** Фигура на поле, если она была записана при обнаружении факта. */
    fun pieceAt(square: Square): Piece? = pieces[square]?.takeIf { it != Piece.NONE }

    companion object {
        fun of(vararg squares: Square): Evidence = Evidence(squares.toList())

        fun see(square: Square, centipawns: Int, vararg extra: Square): Evidence =
            Evidence(listOf(square) + extra.toList(), seeCentipawns = centipawns)

        fun fromEngine(line: EngineLine, vararg squares: Square): Evidence =
            Evidence(squares.toList(), engineLine = line)

        /**
         * То же, но с запоминанием фигур, стоящих на этих полях прямо сейчас.
         * Предпочтительный способ для тактических фактов: именно их поля потом
         * называются в тексте.
         */
        fun onBoard(board: Board, vararg squares: Square): Evidence {
            val list = squares.toList()
            return Evidence(
                squares = list,
                pieces = list.associateWith { board.getPiece(it) }.filterValues { it != Piece.NONE },
            )
        }
    }
}

/**
 * Единица смысла, из которых собирается текст тренера.
 *
 * У рендерера нет метода, принимающего произвольную строку: каждое предложение
 * происходит отсюда.
 */
sealed interface Fact {
    val evidence: Evidence

    /**
     * Сторона, которой этот признак на пользу.
     *
     * Для слабости это противник её владельца, для тактики — тот, кто её проводит.
     * Именно в таком виде признак нужен дифф-анализу: «стало ли лучше мне».
     */
    val favours: Side
}

// ——— Материал и тактика ———

/**
 * Фигура, которую можно забрать с выгодой по честному размену (SEE).
 *
 * [firstAttacker] и [defended] нужны, чтобы тренер называл причину, а не
 * выносил приговор: «конь на d5 потерян» без объяснения читается как каприз
 * движка, а «бьёт пешка e4, защиты нет» человек проверяет глазами за секунду.
 * Оба поля со значением по умолчанию — старые факты (ручные тесты) остаются
 * валидными, просто причина у них не называется.
 */
data class HangingPieceFact(
    val square: Square,
    val piece: Piece,
    val lossCentipawns: Int,
    override val favours: Side,
    override val evidence: Evidence,
    /** Кто бьёт первым — та же фигура, с которой начинает размен SEE. */
    val firstAttacker: Square? = null,
    /** Есть ли чем взять обратно после первого взятия. */
    val defended: Boolean = false,
    /** Что будет, если взять обратно: первое взятие, ответ и продолжение размена. */
    val recaptureLineSan: List<String> = emptyList(),
    /** Фигура, которой пришлось бы брать обратно. */
    val recapturer: Piece? = null,
    /** Защитник есть, но первое взятие с шахом: взять обратно сразу нельзя. */
    val capturedWithCheck: Boolean = false,
) : Fact

/**
 * Почти равный размен: [attacker] может взять на [square], и материал почти не
 * меняется. [deltaCentipawns] со знаком, с точки зрения того, кто берёт первым.
 * [lineSan] — весь размен: первое взятие без ответа рассказывало бы полдела.
 * Первые [forcedPlies] ходов обязательны, дальше идут равные взятия, которые
 * можно и не делать.
 */
data class ExchangeFact(
    val square: Square,
    val attacker: Square,
    val deltaCentipawns: Int,
    override val favours: Side,
    override val evidence: Evidence,
    val lineSan: List<String> = emptyList(),
    val forcedPlies: Int = lineSan.size,
    /** Ход движка вместо равного продолжения, если лучшая линия от него отказывается. */
    val engineChoiceSan: String? = null,
) : Fact

data class ForkFact(
    val from: Square,
    val piece: Piece,
    val targets: List<Square>,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact {
    init { require(targets.size >= 2) { "Вилка — это не менее двух целей" } }
}

/**
 * Связка. Абсолютная — за связанной фигурой король, уйти нельзя по правилам;
 * относительная — за ней фигура дороже, уйти можно, но дорого.
 */
data class PinFact(
    val pinned: Square,
    val pinner: Square,
    val behind: Square,
    val absolute: Boolean,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/** Рентген: впереди стоит фигура дороже той, что за ней. */
data class SkewerFact(
    val front: Square,
    val behind: Square,
    val attacker: Square,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class DiscoveredAttackFact(
    val moving: Square,
    val revealed: Square,
    val target: Square,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/**
 * Слабость последней горизонтали.
 *
 * [exploiter] обязателен: без конкретной фигуры, способной этим воспользоваться,
 * это не слабость, а особенность позиции, и говорить о ней нельзя.
 */
data class BackRankFact(
    val king: Square,
    val blockedBy: List<Square>,
    val exploiter: Square,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class OverloadedDefenderFact(
    val defender: Square,
    val duties: List<Square>,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact {
    init { require(duties.size >= 2) { "Перегрузка начинается с двух обязанностей" } }
}

/** Фигура поймана: все её ходы теряют материал, а не просто выглядят некрасиво. */
data class TrappedPieceFact(
    val square: Square,
    val piece: Piece,
    val lossCentipawns: Int,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/** Мат в N. Только со слов движка — свой код мат не считает никогда. */
data class MateFact(
    val movesToMate: Int,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact {
    init {
        require(evidence.engineLine != null) { "Мат объявляется только со слов движка" }
    }
}

// ——— Король ———

data class KingSafetyFact(
    val king: Square,
    val kingSide: Side,
    val attackerCount: Int,
    val shieldPawnsMissing: Int,
    val openFilesNearKing: List<Char>,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

// ——— Пешечная структура ———

enum class PawnWeaknessKind { ISOLATED, DOUBLED, BACKWARD }

data class PawnWeaknessFact(
    val kind: PawnWeaknessKind,
    val square: Square,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class PassedPawnFact(
    val square: Square,
    val owner: Side,
    val defended: Boolean,
    val squaresToPromotion: Int,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/**
 * Форпост. [piece] обязателен: объявляем только если фигура там уже стоит либо
 * попадает туда по главному варианту. «Теоретически мог бы» — не форпост.
 */
data class OutpostFact(
    val square: Square,
    val piece: Piece,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

// ——— Активность и развитие ———

data class OpenFileRookFact(
    val rook: Square,
    val file: Char,
    val halfOpen: Boolean,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class BadBishopFact(
    val bishop: Square,
    val blockedPawns: List<Square>,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class DevelopmentFact(
    val undeveloped: List<Square>,
    val owner: Side,
    val castled: Boolean,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class MobilityEdgeFact(
    val owner: Side,
    val ownMoves: Int,
    val opponentMoves: Int,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

data class CenterControlFact(
    val owner: Side,
    val ownControl: Int,
    val opponentControl: Int,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/**
 * Вилки ещё нет, но фигура может встать на [to] и напасть сразу на [targets]: проверено ходом на копии доски.
 *
 * [tradeLineSan] — фигуру там сразу меняют: равный размен снимает вилку, хотя
 * материал и не теряется. [tradeDeltaCentipawns] — итог для того, кто взял первым.
 */
data class ForkThreatFact(
    val from: Square,
    val to: Square,
    val piece: Piece,
    val targets: List<Square>,
    override val favours: Side,
    override val evidence: Evidence,
    val tradeLineSan: List<String> = emptyList(),
    val tradeDeltaCentipawns: Int = 0,
    /** Кем берут фигуру на [to]: одинаковые фигуры называем "разменом ферзей". */
    val tradedBy: Piece? = null,
) : Fact

/** Взятие [defender] не проигрывает материал и оставляет [target] под выгодным боем другой фигуры. */
data class RemoveDefenderFact(
    val capturer: Square,
    val defender: Square,
    val target: Square,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/** Поле у короля, которое своя пешка уже не прикроет, а соперник на него смотрит. */
data class WeakSquareFact(
    val square: Square,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

/**
 * Ход пешкой, которая безопасно нападает на пешку соперника и вскрывает игру.
 * [alsoAttacks] — другие цели той же пешки: удар по коню важнее удара по пешке.
 */
data class PawnBreakFact(
    val from: Square,
    val to: Square,
    val target: Square,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
    val alsoAttacks: List<Square> = emptyList(),
) : Fact

enum class EndgameKind { ROOK_BEHIND_PASSED, KING_OUTSIDE_SQUARE, ACTIVE_KING, OPPOSITE_BISHOPS }

/** Признак, который имеет смысл только в эндшпиле. [square] зависит от [kind]: пешка, король или слон. */
data class EndgameFact(
    val kind: EndgameKind,
    val square: Square,
    val owner: Side,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact

// ——— Выводы аналитического слоя ———

/**
 * Что соперник сделает, если дать ему сходить. Получается приёмом нулевого хода.
 *
 * [consequences] — вывод тех же детекторов, применённых к позиции после угрозы,
 * поэтому фраза «конь нападает сразу на ладью и слона» не сочиняется, а берётся
 * из детектора вилки.
 */
data class ThreatFact(
    val opponentMoveUci: String,
    val opponentMoveSan: String,
    val dropCentipawns: Int,
    val consequences: List<Fact>,
    override val favours: Side,
    override val evidence: Evidence,
    /** Продолжение угрозы: ход без варианта проверить нечем. */
    val lineSan: List<String> = emptyList(),
) : Fact

/** Ход снял ту самую угрозу: проверено повторным нулевым ходом после него. */
data class ProphylaxisFact(
    val preventedMoveSan: String,
    val dropRemovedCentipawns: Int,
    override val favours: Side,
    override val evidence: Evidence,
) : Fact
