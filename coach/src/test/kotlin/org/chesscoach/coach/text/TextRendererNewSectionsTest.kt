package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.HumanMove
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.PositionCharacter
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.StrategicIdea
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разделы ПОЗИЦИЯ, ПЛАН СОПЕРНИКА, МОЙ ПЛАН и СЫГРАННЫЙ ХОД — самое новое
 * содержание разбора. Эти тесты закрывают их рендер отдельно от общих
 * механизмов дедупликации и робастности, уже проверенных в других файлах.
 */
class TextRendererNewSectionsTest {

    private val fen = "r1bq1rk1/ppp1bppp/2n1pn2/3p4/3P4/2N1PN2/PPP1BPPP/R1BQ1RK1 b - - 2 14"

    private fun explanation(sections: List<Section>) = PositionExplanation(
        fen = fen,
        sideToMove = Side.BLACK,
        scoreFromWhite = Score.Cp(35),
        bestMoveSan = "Rfe8",
        bestMoveUci = "f8e8",
        sections = sections,
        featuresBefore = FeatureSet.empty(fen),
    )

    // ——— ОЦЕНКА ПОЗИЦИИ ———

    @Test
    fun `a level material balance is stated plainly rather than passed over`() {
        val section = Section.PositionAssessment(materialCentipawns = 0, character = PositionCharacter.OPEN, ideas = emptyList())
        val text = TextRenderer.render(explanation(listOf(section)))

        // Молчание про материал читатель примет за недосмотр, а не за
        // "считать нечего": это первый вопрос, который он задаёт.
        assertTrue(text, text.contains("Материал: равный (0.0)."))
        assertTrue(text, text.contains("Позиция открытая"))
    }

    @Test
    fun `an open position and a closed position read differently`() {
        val open = Section.PositionAssessment(0, PositionCharacter.OPEN, emptyList())
        val closed = Section.PositionAssessment(0, PositionCharacter.CLOSED, emptyList())

        val openText = TextRenderer.render(explanation(listOf(open)))
        val closedText = TextRenderer.render(explanation(listOf(closed)))

        assertTrue(openText, openText.contains("открытая"))
        assertTrue(closedText, closedText.contains("закрытая"))
    }

    @Test
    fun `at most two ideas are joined into a single idea line`() {
        val section = Section.PositionAssessment(
            materialCentipawns = 0,
            character = PositionCharacter.OPEN,
            ideas = listOf(StrategicIdea.ATTACK_KING, StrategicIdea.FORCE_EXCHANGES),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        assertTrue(text, text.contains("Идея: атаковать короля, форсировать размены."))
    }

    @Test
    fun `no ideas means no idea line at all, not an empty one`() {
        val section = Section.PositionAssessment(0, PositionCharacter.OPEN, emptyList())
        val text = TextRenderer.render(explanation(listOf(section)))

        assertFalse(text, text.contains("Идея"))
    }

    // ——— ПЛАН СОПЕРНИКА ———

    @Test
    fun `my position prints every item, the empty ones as no`() {
        val myKing = KingSafetyFact(
            king = Square.G8,
            kingSide = Side.BLACK,
            attackerCount = 1,
            shieldPawnsMissing = 1,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G8),
        )
        val text = TextRenderer.render(explanation(listOf(Section.MyPosition(listOf(myKing), emptyList()))))

        // Список пунктов одинаков от позиции к позиции: иначе читатель не
        // знает, что тренер проверил, а что молча пропустил.
        assertTrue(text, text.contains("ПЛАН СОПЕРНИКА"))
        assertTrue(text, text.contains("1. Маты: нет."))
        assertTrue(text, text.contains("2. Взятия: нет."))
        assertTrue(text, text.contains("3. Активные ходы: король"))
    }

    @Test
    fun `my position with nothing at all still answers every item`() {
        val text = TextRenderer.render(explanation(listOf(Section.MyPosition(emptyList(), emptyList()))))

        assertTrue(text, text.contains("ПЛАН СОПЕРНИКА"))
        assertTrue(text, text.contains("1. Маты: нет."))
        assertTrue(text, text.contains("2. Взятия: нет."))
        assertTrue(text, text.contains("3. Активные ходы: нет."))
    }

    @Test
    fun `my hanging piece is reported under loose pieces`() {
        val hanging = HangingPieceFact(Square.D3, Piece.BLACK_BISHOP, 300, Side.WHITE, Evidence.of(Square.D3))
        val text = TextRenderer.render(explanation(listOf(Section.MyPosition(emptyList(), listOf(hanging)))))

        // После двоеточия строчная буква: это продолжение строки, а не новое
        // предложение.
        assertTrue(text, text.contains("2. Взятия: слон на d3 потерян"))
    }

    // ——— МОЙ ПЛАН ———

    @Test
    fun `capturing resources are reported under the captures line`() {
        val fork = ForkFact(
            from = Square.C3,
            piece = Piece.WHITE_KNIGHT,
            targets = listOf(Square.D1, Square.E2),
            favours = Side.WHITE,
            evidence = Evidence(
                squares = listOf(Square.C3, Square.D1, Square.E2),
                pieces = mapOf(Square.D1 to Piece.BLACK_ROOK, Square.E2 to Piece.BLACK_QUEEN),
            ),
        )
        val text = TextRenderer.render(explanation(listOf(Section.MyAttack(emptyList(), listOf(fork), emptyList()))))

        assertTrue(text, text.contains("МОЙ ПЛАН"))
        assertTrue(text, text.contains("2. Взятия:"))
        assertTrue(text, text.contains("вилкой нападает"))
    }

    @Test
    fun `my attack with nothing at all still answers every item`() {
        val text = TextRenderer.render(explanation(listOf(Section.MyAttack(emptyList(), emptyList(), emptyList()))))

        assertTrue(text, text.contains("МОЙ ПЛАН"))
        assertTrue(text, text.contains("1. Маты: нет."))
        assertTrue(text, text.contains("2. Взятия: нет."))
        assertTrue(text, text.contains("3. Активные ходы: нет."))
    }

    // ——— ШАПКА И ЛУЧШИЙ ХОД ———

    @Test
    fun `a move that changes the zone of the game is called a key moment`() {
        val played = Section.PlayedMove(
            playedSan = "Nd4",
            playedUci = "c6d4",
            bestSan = "Rfe8",
            bestUci = "f8e8",
            classification = MoveClassification.BLUNDER,
            centipawnLoss = 285,
            missed = emptyList(),
        )
        val text = TextRenderer.render(explanation(listOf(played)))

        assertTrue(text, text.contains("Ключевой момент"))
        assertTrue(text, text.contains("после хода +3.2"))
        assertTrue(text, text.contains("Коротко: Nd4 — зевок"))
        assertTrue(text, text.contains("Вопрос на будущее:"))
    }

    @Test
    fun `a small slip in the same zone is not a key moment`() {
        val played = Section.PlayedMove(
            playedSan = "Nd4",
            playedUci = "c6d4",
            bestSan = "Rfe8",
            bestUci = "f8e8",
            classification = MoveClassification.INACCURACY,
            centipawnLoss = 40,
            missed = emptyList(),
        )
        val text = TextRenderer.render(explanation(listOf(played)))

        assertFalse(text, text.contains("Ключевой момент"))
        assertFalse(text, text.contains("Вопрос на будущее"))
    }

    @Test
    fun `best moves show the engine line and say when the human move is the same`() {
        val text = TextRenderer.render(explanation(listOf(Section.BestMoves(listOf("Rfe8", "Bd3")))))

        assertTrue(text, text.contains("ЛУЧШИЙ ХОД"))
        assertTrue(text, text.contains("Компьютер: Rfe8 Bd3."))
        assertTrue(text, text.contains("Человек: тот же ход"))
    }

    @Test
    fun `a different human move shows its line, its cost and why`() {
        val human = HumanMove(listOf("Bd6", "Bd3"), 30, emptyList())
        val text = TextRenderer.render(explanation(listOf(Section.BestMoves(listOf("Rfe8"), human))))

        assertTrue(text, text.contains("Человек: Bd6 Bd3 (слабее лучшего на 0.3). Ход заметен сразу"))
    }

    // ——— СЫГРАННЫЙ ХОД ———

    @Test
    fun `playing the best move says so plainly`() {
        val section = Section.PlayedMove(
            playedSan = "Rfe8",
            playedUci = "f8e8",
            bestSan = "Rfe8",
            bestUci = "f8e8",
            classification = MoveClassification.BEST,
            centipawnLoss = 0,
            missed = emptyList(),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        assertTrue(text, text.contains("СЫГРАННЫЙ ХОД: Rfe8 — лучший"))
        assertTrue(text, text.contains("Сыгран лучший ход."))
    }

    @Test
    fun `the played move that equals the best is marked at the best move, not repeated below`() {
        val best = Section.WhyThisMove(
            moveSan = "Rfe8",
            reasons = listOf(
                Reason(
                    ReasonTier.MATERIAL_TACTICS,
                    listOf(HangingPieceFact(Square.D3, Piece.BLACK_BISHOP, 300, Side.WHITE, Evidence.of(Square.D3))),
                ),
            ),
        )
        val played = Section.PlayedMove(
            playedSan = "Rfe8",
            playedUci = "f8e8",
            bestSan = "Rfe8",
            bestUci = "f8e8",
            classification = MoveClassification.BEST,
            centipawnLoss = 0,
            missed = emptyList(),
        )
        val text = TextRenderer.render(explanation(listOf(best, played)))

        assertTrue(text, text.contains("ПОЧЕМУ Rfe8 (= сыгранный)"))
        assertFalse(text, text.contains("СЫГРАННЫЙ ХОД"))
    }

    @Test
    fun `a mistake names the better move and what it misses`() {
        val hangingQueen = HangingPieceFact(Square.A4, Piece.BLACK_QUEEN, 900, Side.WHITE, Evidence.of(Square.A4))
        val section = Section.PlayedMove(
            playedSan = "Nd4",
            playedUci = "c6d4",
            bestSan = "Qa4+",
            bestUci = "d8a4",
            classification = MoveClassification.MISTAKE,
            centipawnLoss = 250,
            missed = listOf(hangingQueen),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        assertTrue(text, text.contains("ошибка"))
        assertTrue(text, text.contains("Лучше было"))
        assertFalse(text, text.contains("Сыгран лучший ход"))
        assertFalse(text, text.contains("250"))
    }

    @Test
    fun `a mistake with nothing concrete missed still names the better move`() {
        val section = Section.PlayedMove(
            playedSan = "Nd4",
            playedUci = "c6d4",
            bestSan = "Qa4+",
            bestUci = "d8a4",
            classification = MoveClassification.INACCURACY,
            centipawnLoss = 40,
            missed = emptyList(),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        assertTrue(text, text.contains("Лучше было"))
        assertFalse(text, text.contains("этого не даёт"))
    }
}
