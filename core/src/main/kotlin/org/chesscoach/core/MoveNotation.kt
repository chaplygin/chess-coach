package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.MoveList

/** Перевод ходов между записью движка (e2e4) и шахматной нотацией (e4, Кf3). */
object MoveNotation {

    /**
     * Переводит главный вариант из записи UCI в SAN.
     *
     * Движок всегда говорит «поле-поле», а человек читает «Кf3». Разбор делает
     * chesslib: он же отвечает за неочевидные части — снятие неоднозначности
     * между двумя одинаковыми фигурами, взятия, рокировку, превращение и знаки
     * шаха. Писать это вручную — верный способ ошибиться на редком случае.
     */
    fun uciToSan(fen: String, uciMoves: List<String>): List<String> {
        if (uciMoves.isEmpty()) return emptyList()
        val list = MoveList(fen)
        list.loadFromText(uciMoves.joinToString(" "))
        return list.toSanArray().toList()
    }

    /**
     * То же, но при неудаче возвращает исходную запись UCI вместо исключения.
     *
     * Показать пользователю «e2e4» некрасиво, но честно. Уронить экран разбора
     * из-за одного странного варианта — хуже.
     */
    fun uciToSanOrRaw(fen: String, uciMoves: List<String>): List<String> =
        runCatching { uciToSan(fen, uciMoves) }.getOrElse { uciMoves }

    /**
     * Запись хода значком фигуры, как в списке ходов на Lichess: ♞f3, ♛xd5, e8=♛.
     * Заглавные буквы в SAN означают только фигуры, рокировка записана буквой O.
     */
    fun figurine(san: String): String = buildString {
        for (char in san) {
            val glyph = FIGURINES[char]
            if (glyph == null) {
                append(char)
            } else {
                append(glyph)
                append(TEXT_PRESENTATION)
            }
        }
    }

    private val FIGURINES = mapOf('K' to '♚', 'Q' to '♛', 'R' to '♜', 'B' to '♝', 'N' to '♞')

    // Без этого знака телефон может нарисовать фигуру цветным эмодзи.
    private val TEXT_PRESENTATION = 0xFE0E.toChar()

    /** Чей ход в позиции. */
    fun sideToMove(fen: String): Side = Board().apply { loadFromFen(fen) }.sideToMove

    /** Удобная проверка для интерфейса, чтобы не тянуть типы chesslib в экраны. */
    fun isWhiteToMove(fen: String): Boolean = sideToMove(fen) == Side.WHITE

    /** Номер хода, как его пишут в записи партии. */
    fun fullMoveNumber(fen: String): Int =
        fen.trim().split(' ').getOrNull(5)?.toIntOrNull() ?: 1
}
