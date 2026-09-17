package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.ProphylaxisFact
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.core.EngineLine
import org.chesscoach.core.START_FEN
import org.chesscoach.core.Score
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рендер не имеет права падать.
 *
 * Разбор — единственное содержимое экрана: исключение в одном предложении
 * стоит всего экрана, и пользователь видит пустоту без объяснения причин.
 */
class RendererRobustnessTest {

    private val line = EngineLine(multiPvIndex = 1, score = Score.Cp(30), pv = listOf("e2e4"), depth = 12)

    @Test
    fun `профилактика в списке причин рендерится, а не роняет разбор`() {
        // Профилактика — третий тир причин, и ReasonDiffAnalyzer кладёт её
        // именно туда. Рендер когда-то считал, что такого не бывает, и падал.
        // На замороженных ответах движка это не всплывало, а на живом поиске
        // разбор исчезал через раз.
        val fact = ProphylaxisFact(
            preventedMoveSan = "Ng5",
            dropRemovedCentipawns = 120,
            favours = Side.WHITE,
            evidence = Evidence.fromEngine(line),
        )

        val text = TextRenderer.render(
            explanationWith(
                Section.WhyThisMove(
                    moveSan = "h3",
                    reasons = listOf(Reason(ReasonTier.PROPHYLAXIS, listOf(fact))),
                ),
            ),
        )

        assertTrue(text, text.contains("ЛУЧШИЙ ХОД"))
        assertTrue(text, text.contains("Ng5".let(SanRuFormatter::move)))
    }

    @Test
    fun `пустой разбор не роняет рендер и не печатает пустых заголовков`() {
        val text = TextRenderer.render(explanationWith())

        assertTrue(text, text.isNotBlank())
        assertTrue(text, !text.contains("УГРОЗА"))
        assertTrue(text, !text.contains("ПЛАН"))
    }

    private fun explanationWith(vararg sections: Section) = PositionExplanation(
        fen = START_FEN,
        sideToMove = Side.WHITE,
        scoreFromWhite = Score.Cp(35),
        bestMoveSan = "h3",
        bestMoveUci = "h2h3",
        sections = sections.toList(),
        featuresBefore = FeatureSet.empty(START_FEN),
    )
}
