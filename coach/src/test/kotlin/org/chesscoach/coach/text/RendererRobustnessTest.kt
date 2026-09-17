package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.core.MoveClassification
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.PlanIdea
import org.chesscoach.coach.model.PlanStep
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

    @Test
    fun `ход без внятных признаков честно отсылает к варианту`() {
        // Жертва, смысл которой раскрывается через несколько полуходов:
        // выдать открытую линию за причину отдать ладью — значит соврать
        // уверенным тоном.
        val text = TextRenderer.render(
            explanationWith(
                Section.WhyThisMove(moveSan = "Rxe3", reasons = emptyList(), pointIsInTheLine = true),
            ),
        )

        assertTrue(text, text.contains("раскрывается в варианте"))
    }

    @Test
    fun `обычный ход к варианту не отсылает`() {
        val text = TextRenderer.render(
            explanationWith(
                Section.WhyThisMove(moveSan = "h3", reasons = emptyList(), pointIsInTheLine = false),
            ),
        )

        assertTrue(text, !text.contains("раскрывается в варианте"))
    }

    @Test
    fun `местоимение форпоста согласуется с фигурой`() {
        val knight = OutpostFact(
            square = Square.D5,
            piece = Piece.WHITE_KNIGHT,
            owner = Side.WHITE,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D5),
        )
        val rook = knight.copy(piece = Piece.WHITE_ROOK)

        // Через "Активизацию", а не через план: план перечисляет цели полями
        // ("Держать фигуру на форпосте: d5."), и согласование местоимения там
        // просто не встречается.
        val knightText = TextRenderer.render(explanationWith(
            Section.MyAttack(emptyList(), emptyList(), listOf(knight)),
        ))
        val rookText = TextRenderer.render(explanationWith(
            Section.MyAttack(emptyList(), emptyList(), listOf(rook)),
        ))

        assertTrue(knightText, knightText.contains("пешкой его оттуда не согнать"))
        assertTrue(rookText, rookText.contains("пешкой её оттуда не согнать"))
    }

    @Test
    fun `сыгранный ход называет упущенное своими словами, а не молчит`() {
        // Ровно ради этого раздела приложение и затевалось: увидеть лучший ход
        // полезно, но понять, чем именно был хуже собственный, — вдвое.
        // Дословный повтор фразы из раздела про лучший ход глушится защитой от
        // повторов, поэтому упущенное называется отдельной формулировкой.
        val fork = ForkFact(
            from = Square.C3,
            piece = Piece.BLACK_KNIGHT,
            targets = listOf(Square.D1, Square.E2),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.C3, Square.D1, Square.E2),
        )
        val section = Section.PlayedMove(
            playedSan = "fxe4",
            playedUci = "f5e4",
            bestSan = "Nc3",
            bestUci = "d5c3",
            classification = MoveClassification.BLUNDER,
            centipawnLoss = 320,
            missed = listOf(fork),
        )

        val text = TextRenderer.render(explanationWith(section))

        assertTrue(text, text.contains("упускает вилку на d1 и e2"))
    }

    @Test
    fun `вилка поглощает перечисление своих же целей в упущенном`() {
        val fork = ForkFact(
            from = Square.C3,
            piece = Piece.BLACK_KNIGHT,
            targets = listOf(Square.D1, Square.E2),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.C3, Square.D1, Square.E2),
        )
        val hangingOnTarget = HangingPieceFact(Square.D1, Piece.WHITE_ROOK, 500, Side.BLACK, Evidence.of(Square.D1))
        val section = Section.PlayedMove(
            playedSan = "fxe4",
            playedUci = "f5e4",
            bestSan = "Nc3",
            bestUci = "d5c3",
            classification = MoveClassification.BLUNDER,
            centipawnLoss = 320,
            missed = listOf(fork, hangingOnTarget),
        )

        val text = TextRenderer.render(explanationWith(section))

        // Сказать "упускает вилку и заодно ладью на том же поле" — трижды
        // назвать одно и то же.
        assertTrue(text, text.contains("вилку на d1 и e2"))
        assertTrue(text, !text.contains("выигрыш ладьи на d1"))
    }

    @Test
    fun `совпадение с лучшим ходом проговаривается прямо`() {
        val section = Section.PlayedMove(
            playedSan = "Nc3",
            playedUci = "d5c3",
            bestSan = "Nc3",
            bestUci = "d5c3",
            classification = MoveClassification.BEST,
            centipawnLoss = 0,
            missed = emptyList(),
        )

        val text = TextRenderer.render(explanationWith(section))

        assertTrue(text, text.contains("Сыгран лучший ход."))
    }
}
