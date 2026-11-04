package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.AlternativeLine
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.MaterialVerdict
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PositionCharacter
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.ThreatFact
import org.chesscoach.core.EngineLine
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRendererTest {

    // Ход 14, чёрные на ходу — тот самый FEN из примера в задании.
    private val fen = "r1bq1rk1/ppp1bppp/2n1pn2/3p4/3P4/2N1PN2/PPP1BPPP/R1BQ1RK1 b - - 2 14"

    private fun explanation(sections: List<Section>, score: Score = Score.Cp(35)) = PositionExplanation(
        fen = fen,
        sideToMove = Side.BLACK,
        scoreFromWhite = score,
        bestMoveSan = "Rfe8",
        bestMoveUci = "f8e8",
        sections = sections,
        featuresBefore = FeatureSet.empty(fen),
    )

    @Test
    fun `header names the move number, the side to move and the score, dot decimal as ScoreFormat gives it`() {
        val text = TextRenderer.render(explanation(emptyList()))

        val lines = text.lines()
        assertEquals("Миттельшпиль · Ход 14, чёрные", lines[0])
        assertTrue(text, lines[1].startsWith(TextRenderer.BULLET + "Вывод: играть Rfe8 (оценка +0.4)"))
        assertEquals(TextRenderer.BULLET + "Оценка +0.35", lines[2])
    }

    @Test
    fun `a section with no facts behind it is not printed, no header and no filler phrase`() {
        val text = TextRenderer.render(explanation(listOf(Section.Alternatives(emptyList()))))

        // Ни строкой не длиннее шапки: раздел без шагов не появляется вовсе.
        assertEquals(3, text.lines().size)
        assertFalse(text.contains("ДРУГИЕ ХОДЫ"))
    }

    @Test
    fun `same situation with a knight and with a rook is grammatical and different`() {
        val knightHanging = HangingPieceFact(
            square = Square.D5,
            piece = Piece.BLACK_KNIGHT,
            lossCentipawns = 300,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D5),
        )
        val rookHanging = knightHanging.copy(piece = Piece.BLACK_ROOK)

        val knightSection = Section.WhyThisMove("Rfe8", listOf(Reason(ReasonTier.MATERIAL_TACTICS, listOf(knightHanging))))
        val rookSection = Section.WhyThisMove("Rfe8", listOf(Reason(ReasonTier.MATERIAL_TACTICS, listOf(rookHanging))))

        val knightText = TextRenderer.render(explanation(listOf(knightSection)))
        val rookText = TextRenderer.render(explanation(listOf(rookSection)))

        assertTrue(knightText, knightText.contains("Конь на d5 под боем"))
        assertTrue(rookText, rookText.contains("Ладья на d5 под боем"))
    }

    @Test
    fun `glossary footnote for an overloaded piece appears once per explanation, not twice`() {
        val firstOverload = OverloadedDefenderFact(
            defender = Square.D2,
            duties = listOf(Square.D5, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.D2, Square.D5, Square.F3),
        )
        val secondOverload = OverloadedDefenderFact(
            defender = Square.E2,
            duties = listOf(Square.D3, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.E2, Square.D3, Square.F3),
        )
        val section = Section.WhyThisMove(
            "Rfe8",
            listOf(
                Reason(ReasonTier.MATERIAL_TACTICS, listOf(firstOverload)),
                Reason(ReasonTier.STRUCTURE, listOf(secondOverload)),
            ),
        )

        val text = TextRenderer.render(explanation(listOf(section)))

        val occurrences = Regex(Regex.escape(GlossaryTerm.OVERLOADED_PIECE.definition)).findAll(text).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `rendering the same FEN twice produces the exact same text`() {
        val threat = ThreatFact(
            opponentMoveUci = "f3g5",
            opponentMoveSan = "Ng5",
            dropCentipawns = 200,
            consequences = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.fromEngine(EngineLine(1, Score.Cp(200), listOf("f3g5"), 12)),
        )
        val sections = listOf(Section.MyPosition(emptyList(), emptyList(), threat))

        val first = TextRenderer.render(explanation(sections))
        val second = TextRenderer.render(explanation(sections))

        assertEquals(first, second)
    }

    @Test
    fun `no centipawn numbers leak into the body, only the header may carry the score`() {
        val threat = ThreatFact(
            opponentMoveUci = "f3g5",
            opponentMoveSan = "Ng5",
            dropCentipawns = 222,
            consequences = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.fromEngine(EngineLine(1, Score.Cp(222), listOf("f3g5"), 12)),
        )
        val trap = Section.Trap(
            moveSan = "Nd4",
            lossCentipawns = 333,
            refutationSan = listOf("Bxd4", "cxd4"),
            verdict = MaterialVerdict.LOSES_PIECE,
        )
        val alternatives = Section.Alternatives(
            listOf(
                AlternativeLine(
                    moveSan = "Rad8",
                    scoreFromWhite = Score.Cp(11),
                    lossVsBestCentipawns = 444,
                    continuationSan = emptyList(),
                ),
            ),
        )
        val material = Section.PositionAssessment(
            materialCentipawns = 555,
            character = PositionCharacter.SEMI_OPEN,
            ideas = emptyList(),
        )

        val text = TextRenderer.render(explanation(listOf(Section.MyPosition(emptyList(), emptyList(), threat), trap, alternatives, material)))
        val body = text.substringAfter(System.lineSeparator())

        assertFalse(body.contains("222"))
        assertFalse(body.contains("333"))
        assertFalse(body.contains("444"))
        assertFalse(body.contains("555"))
    }
}
