package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MaterialVerdict
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PawnWeaknessKind
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

/**
 * Прогон на настоящем движке показал: структурно честный текст ещё не значит
 * читаемый. Эти тесты закрывают то, что разбор на живых партиях обнажил —
 * согласование стороны по числам и падежам, дедупликацию повторов и
 * редакторскую сдержанность (не больше 1-2 фактов на пункт, сноски не ломают
 * предложение).
 */
class TextRendererEditorialTest {

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

    private fun reasonSection(vararg facts: org.chesscoach.coach.model.Fact) =
        Section.WhyThisMove("Rfe8", listOf(Reason(ReasonTier.MATERIAL_TACTICS, facts.toList())))

    // ——— Согласование стороны (пункт 1 правки) ———

    @Test
    fun `side lexicon declines by case, nominative and genitive differ`() {
        assertEquals("белые", SideLexicon.name(Side.WHITE))
        assertEquals("белых", SideLexicon.name(Side.WHITE, GrammaticalCase.GENITIVE))
        assertEquals("чёрные", SideLexicon.name(Side.BLACK))
        assertEquals("чёрных", SideLexicon.name(Side.BLACK, GrammaticalCase.GENITIVE))
    }

    @Test
    fun `center control fact uses the plural verb, not the singular one`() {
        val fact = CenterControlFact(
            owner = Side.BLACK,
            ownControl = 5,
            opponentControl = 1,
            favours = Side.BLACK,
            evidence = Evidence.of(Square.D4),
        )

        val text = TextRenderer.render(explanation(listOf(reasonSection(fact))))

        assertTrue(text, text.contains("Чёрные прочно держат центр."))
        assertFalse(text, text.contains("держит центр"))
    }

    @Test
    fun `a trap that only worsens the position uses the genitive after u, not the nominative`() {
        val trap = Section.Trap(
            moveSan = "Nd4",
            lossCentipawns = 40,
            refutationSan = listOf("Bxd4", "cxd4"),
            verdict = MaterialVerdict.POSITIONAL,
        )

        val text = TextRenderer.render(explanation(listOf(trap)))

        assertTrue(text, text.contains("позиция у чёрных заметно хуже"))
        assertFalse(text, text.contains("у чёрные"))
    }

    // ——— Дедупликация (пункт 2 правки) ———

    @Test
    fun `the same fact text is not repeated within one reason even if the underlying facts are not equal`() {
        val queenHangingAtMove = HangingPieceFact(
            square = Square.E2,
            piece = Piece.WHITE_QUEEN,
            lossCentipawns = 900,
            favours = Side.BLACK,
            evidence = Evidence.of(Square.E2),
        )
        // Тот же самый факт по смыслу (тот же ферзь на том же поле), но другой
        // объект: другая просадка и другое доказательство — ровно так, как
        // ReasonDiffAnalyzer мог найти его дважды (после хода и после ответа).
        val queenHangingAtReply = queenHangingAtMove.copy(lossCentipawns = 850, evidence = Evidence.of(Square.E2, Square.D1))

        val text = TextRenderer.render(explanation(listOf(reasonSection(queenHangingAtMove, queenHangingAtReply))))

        val occurrences = Regex("Ферзь на e2 под боем").findAll(text).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `a fork's own targets are not separately reported as hanging — the fork already names them`() {
        val fork = ForkFact(
            from = Square.C3,
            piece = Piece.BLACK_KNIGHT,
            targets = listOf(Square.D1, Square.E2),
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.C3, Square.D1, Square.E2),
                pieces = mapOf(Square.D1 to Piece.WHITE_ROOK, Square.E2 to Piece.WHITE_QUEEN),
            ),
        )
        val rookHanging = HangingPieceFact(Square.D1, Piece.WHITE_ROOK, 500, Side.BLACK, Evidence.of(Square.D1))
        val queenHanging = HangingPieceFact(Square.E2, Piece.WHITE_QUEEN, 900, Side.BLACK, Evidence.of(Square.E2))

        val text = TextRenderer.render(explanation(listOf(reasonSection(rookHanging, queenHanging, fork))))

        assertTrue(text, text.contains("вилкой нападает сразу на ладью d1 и ферзя e2"))
        assertFalse(text, text.contains("под боем"))
    }

    @Test
    fun `a king safety fact already said in a reason is not repeated in its own standalone section`() {
        val kingSafety = KingSafetyFact(
            king = Square.G8,
            kingSide = Side.BLACK,
            attackerCount = 2,
            shieldPawnsMissing = 1,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G8),
        )

        // Порядок важен: рендер идёт по списку секций именно в этом порядке, и
        // память о сказанном заполняется по ходу дела — причина хода должна
        // "занять" фразу раньше, чем до неё дойдёт ПЛАН СОПЕРНИКА.
        val sections = listOf(
            Section.WhyThisMove("Rfe8", listOf(Reason(ReasonTier.KING_SAFETY, listOf(kingSafety)))),
            Section.MyPosition(kingSafety = listOf(kingSafety), loosePieces = emptyList()),
        )

        val text = TextRenderer.render(explanation(sections), viewer = Side.BLACK)

        // Факт целиком ушёл в причины хода, поэтому свою строку в ПЛАН СОПЕРНИКА
        // он занять уже не может. Раздел всё равно печатается — с честным
        // "нет" вместо повтора: одинаковый список пунктов от позиции к позиции
        // важнее, чем экономия одной строки.
        assertTrue(text, text.contains("План соперника"))
        assertTrue(text, text.contains("3. Активные ходы: нет."))
        // Первая строка "Вывод" пересказывает главную причину: это сводка, а не повтор раздела.
        assertEquals(1, Regex("под давлением").findAll(text.substringAfter(TextRenderer.SECTION_MARK + "Почему")).count())
    }

    // ——— Редакторская сдержанность (пункт 3 правки) ———

    @Test
    fun `a reason keeps at most two facts even when the tier has more`() {
        val facts = (0..4).map { index ->
            HangingPieceFact(
                square = Square.values()[index],
                piece = Piece.WHITE_PAWN,
                lossCentipawns = 100,
                favours = Side.BLACK,
                evidence = Evidence.of(Square.values()[index]),
            )
        }

        val text = TextRenderer.render(explanation(listOf(reasonSection(*facts.toTypedArray()))))

        val whyThisMove = text.substringAfter(TextRenderer.SECTION_MARK + "Почему")
        assertEquals(2, Regex("под боем").findAll(whyThisMove).count())
    }

    @Test
    fun `a threat keeps the opponent's move and at most two of its dangerous consequences, nothing structural`() {
        val hanging1 = HangingPieceFact(Square.D8, Piece.BLACK_QUEEN, 900, Side.WHITE, Evidence.of(Square.D8))
        val hanging2 = HangingPieceFact(Square.E6, Piece.BLACK_BISHOP, 300, Side.WHITE, Evidence.of(Square.E6))
        val hanging3 = HangingPieceFact(Square.A8, Piece.BLACK_ROOK, 500, Side.WHITE, Evidence.of(Square.A8))
        val notDanger = PawnWeaknessFact(PawnWeaknessKind.BACKWARD, Square.E6, Side.BLACK, Side.WHITE, Evidence.of(Square.E6))
        val alsoNotDanger = MobilityEdgeFact(Side.WHITE, 20, 10, Side.WHITE, Evidence.of(Square.E4))

        val threat = ThreatFact(
            opponentMoveUci = "f3g5",
            opponentMoveSan = "Ng5",
            dropCentipawns = 200,
            consequences = listOf(hanging1, hanging2, hanging3, notDanger, alsoNotDanger),
            favours = Side.WHITE,
            evidence = Evidence.fromEngine(EngineLine(1, Score.Cp(200), listOf("f3g5"), 12)),
        )

        val text = TextRenderer.render(explanation(listOf(Section.MyPosition(emptyList(), emptyList(), threat))))
        val threatBlock = text.substringAfter("Угроза:")

        assertFalse(threatBlock, threatBlock.contains("отсталая"))
        assertFalse(threatBlock, threatBlock.contains("пространства для манёвра"))
        assertTrue(threatBlock, threatBlock.contains("После этого: "))
        assertEquals(2, Regex("под боем").findAll(threatBlock).count())
    }

    // ——— Сноска не ломает предложение (пункт 4 правки) ———

    @Test
    fun `the glossary footnote lands in its own block at the end, not inside the sentence`() {
        val overload = org.chesscoach.coach.model.OverloadedDefenderFact(
            defender = Square.D2,
            duties = listOf(Square.D5, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.D2, Square.D5, Square.F3),
        )

        val text = TextRenderer.render(explanation(listOf(reasonSection(overload))))

        // Внутри пункта причины термин голый, без скобок с определением.
        val whyThisMove = text.substringBefore(TextRenderer.REFERENCE_TITLE)
        assertFalse(whyThisMove, whyThisMove.contains(GlossaryTerm.OVERLOADED_PIECE.definition))
        // Определение — только в отдельном блоке в конце.
        assertTrue(text, text.contains(TextRenderer.REFERENCE_TITLE))
        assertTrue(text, text.substringAfter(TextRenderer.REFERENCE_TITLE).contains(GlossaryTerm.OVERLOADED_PIECE.definition))
    }

    // ——— Перечисление через запятые, не через повторное "и" (пункт 6) ———

    @Test
    fun `three or more targets are listed with commas and a single final and, not and repeated`() {
        val fork = ForkFact(
            from = Square.E1,
            piece = Piece.BLACK_QUEEN,
            targets = listOf(Square.D1, Square.F2, Square.C3),
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.E1, Square.D1, Square.F2, Square.C3),
                pieces = mapOf(Square.D1 to Piece.WHITE_QUEEN, Square.F2 to Piece.WHITE_PAWN, Square.C3 to Piece.WHITE_PAWN),
            ),
        )

        val text = TextRenderer.render(explanation(listOf(reasonSection(fork))))

        assertTrue(text, text.contains("нападает сразу на ферзя d1, пешку f2 и пешку c3."))
        assertFalse(text, text.contains("f2 и пешку c3 и"))
        assertFalse(text, text.contains("d1 и пешку f2 и"))
    }

    // ——— Величина материального перевеса соразмерна словам (пункт 7 правки) ———

    @Test
    fun `a pawn-sized imbalance names the pawn and the balance in pawns`() {
        val section = org.chesscoach.coach.model.Section.PositionAssessment(
            materialCentipawns = -100,
            character = org.chesscoach.coach.model.PositionCharacter.SEMI_OPEN,
            ideas = emptyList(),
            pieceDifference = mapOf(PieceType.PAWN to -1),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        assertTrue(text, text.contains("Материал: у чёрных лишняя пешка (-1.0)."))
    }

    @Test
    fun `an uneven exchange names what each side has instead of a single verdict`() {
        val section = org.chesscoach.coach.model.Section.PositionAssessment(
            materialCentipawns = 180,
            character = org.chesscoach.coach.model.PositionCharacter.SEMI_OPEN,
            ideas = emptyList(),
            pieceDifference = mapOf(PieceType.ROOK to 1, PieceType.KNIGHT to -1),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        // Именно это "заметный перевес" и скрывал: качество — это размен
        // ладьи на лёгкую фигуру, а не просто плюс два.
        assertTrue(text, text.contains("Материал: у белых лишняя ладья, у чёрных лишний конь (+1.8)."))
    }

    @Test
    fun `several pieces of one kind are counted grammatically`() {
        val two = org.chesscoach.coach.model.Section.PositionAssessment(
            materialCentipawns = -200,
            character = org.chesscoach.coach.model.PositionCharacter.SEMI_OPEN,
            ideas = emptyList(),
            pieceDifference = mapOf(PieceType.PAWN to -2),
        )
        val five = two.copy(materialCentipawns = -500, pieceDifference = mapOf(PieceType.PAWN to -5))

        assertTrue(TextRenderer.render(explanation(listOf(two))).contains("лишние две пешки"))
        // После пяти — родительный множественного: "пять пешек", а не "пять пешки".
        assertTrue(TextRenderer.render(explanation(listOf(five))).contains("лишние пять пешек"))
    }

    @Test
    fun `a big edge names the pieces rather than calling it noticeable`() {
        val section = org.chesscoach.coach.model.Section.PositionAssessment(
            materialCentipawns = -600,
            character = org.chesscoach.coach.model.PositionCharacter.SEMI_OPEN,
            ideas = emptyList(),
            pieceDifference = mapOf(PieceType.ROOK to -1, PieceType.PAWN to -1),
        )
        val text = TextRenderer.render(explanation(listOf(section)))

        assertTrue(text, text.contains("Материал: у чёрных лишние ладья и пешка (-6.0)."))
        assertFalse(text, text.contains("заметный перевес"))
    }

    // ——— Свой и чужой король не путаются (пункт 9 правки) ———

    @Test
    fun `king safety always names whose king it is`() {
        val whiteKing = KingSafetyFact(
            king = Square.E1,
            kingSide = Side.WHITE,
            attackerCount = 1,
            shieldPawnsMissing = 1,
            openFilesNearKing = emptyList(),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.E1),
        )
        val blackKing = whiteKing.copy(king = Square.G8, kingSide = Side.BLACK, favours = Side.WHITE, evidence = Evidence.of(Square.G8))

        val whiteText = TextRenderer.render(explanation(listOf(reasonSection(whiteKing))))
        val blackText = TextRenderer.render(explanation(listOf(reasonSection(blackKing))))

        assertTrue(whiteText, whiteText.contains("Король белых на e1 под давлением"))
        assertTrue(blackText, blackText.contains("Король чёрных на g8 под давлением"))
    }

    @Test
    fun `a reason never names two different kings — only the first one survives`() {
        val opponentKing = KingSafetyFact(
            king = Square.H7,
            kingSide = Side.WHITE,
            attackerCount = 1,
            shieldPawnsMissing = 1,
            openFilesNearKing = emptyList(),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.H7),
        )
        val ownKing = KingSafetyFact(
            king = Square.G8,
            kingSide = Side.BLACK,
            attackerCount = 1,
            shieldPawnsMissing = 1,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G8),
        )

        val text = TextRenderer.render(explanation(listOf(reasonSection(opponentKing, ownKing))))
        val whyThisMove = text.substringAfter(TextRenderer.SECTION_MARK + "Почему")

        assertEquals(1, Regex("Король").findAll(whyThisMove).count())
        assertTrue(whyThisMove, whyThisMove.contains("Король белых на h7"))
        assertFalse(whyThisMove, whyThisMove.contains("Король чёрных на g8"))
    }
}
