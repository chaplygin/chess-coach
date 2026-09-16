package org.chesscoach.core

/**
 * Перевод позиции из FEN в раскладку клеток для отрисовки.
 *
 * Лежит в чистом модуле, а не рядом с экраном: это разбор формата, а не
 * рисование, и ошибка здесь показала бы неверную позицию — самая обидная
 * порода ошибок, потому что тренер будет объяснять правильно, а доска врать.
 */
object BoardLayout {

    /**
     * 64 клетки, сверху вниз начиная с восьмой горизонтали.
     *
     * Буква обозначает фигуру по правилам FEN: заглавная — белая, строчная —
     * чёрная. Пустая клетка — null.
     */
    fun parsePlacement(fen: String): List<Char?> {
        val placement = fen.trim().substringBefore(' ')
        val squares = ArrayList<Char?>(64)

        placement.split('/').forEach { rank ->
            rank.forEach { symbol ->
                if (symbol.isDigit()) {
                    repeat(symbol - '0') { squares.add(null) }
                } else {
                    squares.add(symbol)
                }
            }
        }

        // Испорченная запись не должна ронять экран: дополняем или обрезаем до доски.
        while (squares.size < 64) squares.add(null)
        return squares.take(64)
    }

    /** Номер клетки 0..63, считая от a8, либо null для неразборчивого обозначения. */
    fun squareIndex(square: String): Int? {
        if (square.length < 2) return null
        val file = square[0] - 'a'
        val rank = square[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        return (7 - rank) * 8 + file
    }

    /** Обратный перевод: из номера клетки в обозначение вида "e4". */
    fun squareName(index: Int): String? {
        if (index !in 0..63) return null
        val file = 'a' + (index % 8)
        val rank = '8' - (index / 8)
        return "$file$rank"
    }
}
