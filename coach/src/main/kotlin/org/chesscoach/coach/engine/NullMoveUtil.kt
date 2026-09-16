package org.chesscoach.coach.engine

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side

/**
 * Приём нулевого хода: «что сделал бы соперник, будь ход его».
 *
 * Так получается секция «УГРОЗА» — самая полезная для клубного игрока, потому
 * что зевают обычно не свой план, а чужой.
 */
object NullMoveUtil {

    /**
     * Позиция та же, но очередь хода передана сопернику.
     *
     * Поле взятия на проходе сбрасывается обязательно. Оно описывает пешку,
     * которая только что прыгнула через клетку, и при передаче очереди хода
     * становится бессмыслицей: движок предложит нелегальное взятие и объявит
     * его «угрозой». Это классическая ошибка такой реализации.
     *
     * Права рокировки, наоборот, не трогаем: они привязаны к истории ходов
     * короля и ладей, а не к тому, чья сейчас очередь.
     *
     * Возвращает null, если приём неприменим: под шахом вопрос «а что если я
     * ничего не сделаю» не имеет смысла, и выдумывать угрозу в такой позиции
     * запрещено.
     */
    fun flipSideToMove(fen: String): String? {
        val parts = fenFields(fen)
        if (parts.size < 4) return null

        val board = Board()
        if (!runCatching { board.loadFromFen(fen) }.isSuccess) return null
        if (board.isKingAttacked) return null

        val flipped = if (parts[1] == "w") "b" else "w"
        val rebuilt = buildList {
            add(parts[0])
            add(flipped)
            add(parts[2])
            add("-")
            add("0")
            // Номер хода растёт, когда очередь переходит к белым.
            add(if (flipped == "w") ((parts.getOrNull(5)?.toIntOrNull() ?: 1) + 1).toString()
                else (parts.getOrNull(5) ?: "1"))
        }.joinToString(" ")

        // Перевёрнутая позиция может оказаться нелегальной: например, соперник
        // сейчас под шахом, то есть ходить ему уже поздно.
        val check = Board()
        if (!runCatching { check.loadFromFen(rebuilt) }.isSuccess) return null
        if (check.isKingAttacked) return null

        return rebuilt
    }

    /** Поля FEN. Разделитель — пробел, лишние пробелы отбрасываем. */
    private fun fenFields(fen: String): List<String> =
        fen.trim().split(" ").filter { it.isNotEmpty() }

    fun sideToMoveOf(fen: String): Side =
        if (fenFields(fen).getOrNull(1) == "b") Side.BLACK else Side.WHITE
}
