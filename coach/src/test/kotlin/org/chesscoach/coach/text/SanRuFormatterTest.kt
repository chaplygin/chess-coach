package org.chesscoach.coach.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор и лента ходов показывают ход одинаково.
 *
 * Раньше тренер переводил ходы в русскую фигурную нотацию, а лента ходов и
 * карточка быстрой оценки — нет: один и тот же ход оказывался на экране
 * одновременно как Bd7 и как Сd7. Пользователь прочёл это как ошибку, а не
 * как две традиции записи, и был прав — поэтому тест сторожит именно
 * тождество, а не таблицу букв.
 */
class SanRuFormatterTest {

    @Test
    fun `move is shown exactly as the engine and Lichess write it`() {
        listOf("Kd2", "Nd2", "Qd8#", "Rae1", "Bxf7+", "e8=Q", "cxd5", "O-O", "O-O-O")
            .forEach { san -> assertEquals(san, SanRuFormatter.move(san)) }
    }

    @Test
    fun `piece letters are not replaced by Russian ones`() {
        // Именно эти две замены пользователь и заметил как расхождение.
        assertEquals("Bd7", SanRuFormatter.move("Bd7"))
        assertEquals("Rfe8", SanRuFormatter.move("Rfe8"))
    }

    @Test
    fun `a line keeps every move and separates them with single spaces`() {
        assertEquals("Kd2 Nd2 O-O", SanRuFormatter.line(listOf("Kd2", "Nd2", "O-O")))
    }

    @Test
    fun `an empty line renders as empty text rather than a stray space`() {
        assertEquals("", SanRuFormatter.line(emptyList()))
    }
}
