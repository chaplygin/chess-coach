package org.chesscoach.coach.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {

    @Test
    fun `mention never inlines the definition, repeated or not — a footnote must not break the sentence`() {
        val glossary = Glossary()

        val first = glossary.mention(GlossaryTerm.XRAY, "Рентген")
        val second = glossary.mention(GlossaryTerm.XRAY, "Рентген")

        assertEquals("Рентген", first)
        assertEquals("Рентген", second)
        assertFalse(first.contains(GlossaryTerm.XRAY.definition))
    }

    @Test
    fun `a term mentioned twice is listed once among the footnotes`() {
        val glossary = Glossary()

        glossary.mention(GlossaryTerm.XRAY, "Рентген")
        glossary.mention(GlossaryTerm.XRAY, "Рентген")

        assertEquals(listOf(GlossaryTerm.XRAY), glossary.footnotes())
    }

    @Test
    fun `different terms each land in the footnotes in the order they were first mentioned`() {
        val glossary = Glossary()

        glossary.mention(GlossaryTerm.BAD_BISHOP, "плохим слоном")
        glossary.mention(GlossaryTerm.OVERLOADED_PIECE, "Перегруженная фигура")

        assertEquals(listOf(GlossaryTerm.BAD_BISHOP, GlossaryTerm.OVERLOADED_PIECE), glossary.footnotes())
    }

    @Test
    fun `a fresh glossary has no footnotes yet`() {
        assertTrue(Glossary().footnotes().isEmpty())
    }

    @Test
    fun `a fresh glossary for a new explanation forgets what the previous one showed`() {
        val first = Glossary()
        first.mention(GlossaryTerm.XRAY, "Рентген")

        val second = Glossary()

        assertTrue(second.footnotes().isEmpty())
    }
}
