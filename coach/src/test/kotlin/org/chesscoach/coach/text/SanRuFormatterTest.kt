package org.chesscoach.coach.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SanRuFormatterTest {

    @Test
    fun `king letter becomes Kr and knight letter becomes K`() {
        assertEquals("Крd2", SanRuFormatter.move("Kd2"))
        assertEquals("Кd2", SanRuFormatter.move("Nd2"))
    }

    @Test
    fun `Kd2 and Nd2 in the same line do not collapse into each other`() {
        // Ровно тот случай, о котором предупреждает задание: цепочка
        // последовательных .replace() превратила бы оба хода в один и тот же
        // текст, потому что N сначала стало бы К, а затем К стало бы Кр.
        val line = SanRuFormatter.line(listOf("Kd2", "Nd2"))

        assertEquals("Крd2 Кd2", line)
    }

    @Test
    fun `every piece letter maps to its own Russian letter`() {
        assertEquals("Ф", SanRuFormatter.move("Q"))
        assertEquals("Л", SanRuFormatter.move("R"))
        assertEquals("С", SanRuFormatter.move("B"))
    }

    @Test
    fun `board squares are never translated`() {
        assertEquals("e4", SanRuFormatter.move("e4"))
        assertEquals("cxd5", SanRuFormatter.move("cxd5"))
    }

    @Test
    fun `captures, check, mate and promotion markers survive untouched`() {
        assertEquals("Кxe4", SanRuFormatter.move("Nxe4"))
        assertEquals("Крh1+", SanRuFormatter.move("Kh1+"))
        assertEquals("Фd8#", SanRuFormatter.move("Qd8#"))
        assertEquals("e8=Ф", SanRuFormatter.move("e8=Q"))
    }

    @Test
    fun `castling is preserved as-is`() {
        assertEquals("O-O", SanRuFormatter.move("O-O"))
        assertEquals("O-O-O", SanRuFormatter.move("O-O-O"))
    }

    @Test
    fun `disambiguation letters and ranks on non-piece positions are left alone`() {
        assertEquals("Кbd2", SanRuFormatter.move("Nbd2"))
        assertEquals("Л1e2", SanRuFormatter.move("R1e2"))
    }
}
