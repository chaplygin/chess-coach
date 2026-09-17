package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.PieceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PieceLexiconTest {

    @Test
    fun `rook is feminine and knight is masculine`() {
        assertEquals(Gender.FEMININE, PieceLexicon.genderOf(PieceType.ROOK))
        assertEquals(Gender.MASCULINE, PieceLexicon.genderOf(PieceType.KNIGHT))
    }

    @Test
    fun `all six piece genders match how a club player actually calls them`() {
        assertEquals(Gender.MASCULINE, PieceLexicon.genderOf(PieceType.KING))
        assertEquals(Gender.MASCULINE, PieceLexicon.genderOf(PieceType.QUEEN))
        assertEquals(Gender.MASCULINE, PieceLexicon.genderOf(PieceType.BISHOP))
        assertEquals(Gender.FEMININE, PieceLexicon.genderOf(PieceType.PAWN))
    }

    @Test
    fun `same agreeing word gives grammatical and different forms for knight and rook`() {
        val knightForm = PieceLexicon.Agreements.LOST.agree(PieceLexicon.genderOf(PieceType.KNIGHT))
        val rookForm = PieceLexicon.Agreements.LOST.agree(PieceLexicon.genderOf(PieceType.ROOK))

        assertEquals("потерян", knightForm)
        assertEquals("потеряна", rookForm)
        assertNotEquals(knightForm, rookForm)
    }

    @Test
    fun `pinned adjective agrees the same way as short participle`() {
        assertEquals("связанный", PieceLexicon.Agreements.PINNED_ADJECTIVE.agree(Gender.MASCULINE))
        assertEquals("связанная", PieceLexicon.Agreements.PINNED_ADJECTIVE.agree(Gender.FEMININE))
    }

    @Test
    fun `case forms are declined, not just the nominative repeated`() {
        val knight = PieceLexicon.word(PieceType.KNIGHT)

        assertEquals("конь", knight.case(GrammaticalCase.NOMINATIVE))
        assertEquals("коня", knight.case(GrammaticalCase.ACCUSATIVE))
        assertEquals("конём", knight.case(GrammaticalCase.INSTRUMENTAL))
    }

    @Test(expected = IllegalStateException::class)
    fun `empty square has no word in Russian`() {
        PieceLexicon.word(PieceType.NONE)
    }
}
