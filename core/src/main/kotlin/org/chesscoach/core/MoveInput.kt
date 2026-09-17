package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Board

/**
 * Что получилось из хода, который человек напечатал руками.
 *
 * Отдельные варианты для «не разобрали» и «подходит несколько» не формальность:
 * сообщения пользователю в этих случаях разные. «Такого хода здесь нет» на
 * набранное bxc4 при двух подходящих ходах — неправда, и человек будет искать
 * ошибку в позиции, а не в своей записи.
 */
sealed interface MoveInputResult {
    data class Parsed(val uci: String, val san: String) : MoveInputResult

    /** Запись не разобрана или такой ход в этой позиции невозможен. */
    object Unknown : MoveInputResult

    /** Без учёта регистра подходит больше одного хода — например bxc4 и Bxc4. */
    object Ambiguous : MoveInputResult
}

/**
 * Разбор хода, набранного человеком: SAN (Nf3, exd5, O-O) или запись движка
 * (g1f3).
 *
 * Своего парсера записи здесь нет и быть не должно: вместо разбора строки по
 * буквам перебираются легальные ходы позиции, и каждый сравнивается со
 * введённым текстом. Так невозможно принять ход, которого в позиции нет, —
 * список кандидатов изначально состоит только из законных ходов.
 */
object MoveInput {

    /** Знаки, которые ничего не меняют в самом ходе: шах, мат, оценки хода. */
    private const val DECORATIONS = "+#!?"

    fun parse(fen: String, text: String): MoveInputResult {
        val wanted = clean(text)
        if (wanted.isEmpty()) return MoveInputResult.Unknown

        val board = Board()
        if (runCatching { board.loadFromFen(fen) }.isFailure) return MoveInputResult.Unknown

        val moves = runCatching { board.legalMoves() }.getOrNull().orEmpty()
        if (moves.isEmpty()) return MoveInputResult.Unknown

        val candidates = moves.mapNotNull { move ->
            val uci = move.toString().lowercase()
            val san = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).firstOrNull() ?: return@mapNotNull null
            MoveInputResult.Parsed(uci, san)
        }

        // Точное совпадение — всегда первым: рокировка O-O и ход ладьи, взятие
        // пешкой bxc4 и взятие слоном Bxc4 различаются только регистром, и
        // угадывать за человека там, где он написал однозначно, нельзя.
        candidates.firstOrNull { clean(it.san) == wanted }?.let { return it }
        candidates.firstOrNull { it.uci == wanted }?.let { return it }

        val loose = candidates.filter { clean(it.san).equals(wanted, ignoreCase = true) }
        return when (loose.size) {
            0 -> MoveInputResult.Unknown
            1 -> loose.first()
            else -> MoveInputResult.Ambiguous
        }
    }

    /**
     * Ноль вместо буквы O в рокировке — самая частая опечатка при наборе с
     * телефонной клавиатуры, где цифры и буквы на разных раскладках.
     */
    private fun clean(text: String): String = text
        .trim()
        .filterNot { it.isWhitespace() || it in DECORATIONS }
        .replace('0', 'O')
}
