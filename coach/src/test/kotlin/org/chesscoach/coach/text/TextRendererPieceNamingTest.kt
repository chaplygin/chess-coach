package org.chesscoach.coach.text

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.OverloadedDefenderFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Reason
import org.chesscoach.coach.model.ReasonTier
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.core.Score
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Evidence.pieces` появилось позже самой подсистемы текста: детекторы вилки,
 * связки, рентгена, вскрытого нападения и перегрузки теперь запоминают, кто
 * стоял на полях доказательства в момент обнаружения факта. Эти тесты
 * проверяют обе стороны контракта: фигуры названы, когда они записаны, и
 * прежний, более сухой текст с голыми полями — когда нет (старые факты,
 * написанные вручную тесты).
 */
class TextRendererPieceNamingTest {

    private val fen = "8/8/8/8/8/8/8/8 w - - 0 1"

    private fun render(fact: org.chesscoach.coach.model.Fact) = TextRenderer.render(
        PositionExplanation(
            fen = fen,
            sideToMove = Side.WHITE,
            scoreFromWhite = Score.Cp(0),
            bestMoveSan = "Kf1",
            bestMoveUci = "e1f1",
            sections = listOf(Section.WhyThisMove("Kf1", listOf(Reason(ReasonTier.MATERIAL_TACTICS, listOf(fact))))),
            featuresBefore = FeatureSet.empty(fen),
        ),
    )

    // ——— Вилка ———

    @Test
    fun `fork names both target pieces when the evidence recorded them`() {
        val fork = ForkFact(
            from = Square.G5,
            piece = Piece.WHITE_KNIGHT,
            targets = listOf(Square.D8, Square.E6),
            favours = Side.WHITE,
            evidence = Evidence(
                squares = listOf(Square.G5, Square.D8, Square.E6),
                pieces = mapOf(Square.D8 to Piece.BLACK_QUEEN, Square.E6 to Piece.BLACK_BISHOP),
            ),
        )

        val text = render(fork)

        assertTrue(text, text.contains("Конь на g5 вилкой нападает сразу на ферзя d8 и слона e6."))
    }

    @Test
    fun `fork without recorded pieces stays on the older bare-square wording`() {
        val fork = ForkFact(
            from = Square.G5,
            piece = Piece.WHITE_KNIGHT,
            targets = listOf(Square.D8, Square.E6),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G5, Square.D8, Square.E6),
        )

        val text = render(fork)

        assertTrue(text, text.contains("Конь на g5 держит вилку: под боем сразу d8 и e6."))
    }

    // ——— Связка ———

    @Test
    fun `absolute pin names the pinned piece and agrees the pronoun with its gender`() {
        val pin = PinFact(
            pinned = Square.C2,
            pinner = Square.C8,
            behind = Square.E1,
            absolute = true,
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.C2, Square.C8, Square.E1),
                pieces = mapOf(Square.C2 to Piece.WHITE_KNIGHT),
            ),
        )

        val text = render(pin)

        assertTrue(text, text.contains("Конь на c2 связан намертво — за ним король на e1."))
    }

    @Test
    fun `absolute pin without a recorded piece stays on the older generic wording`() {
        val pin = PinFact(
            pinned = Square.C2,
            pinner = Square.C8,
            behind = Square.E1,
            absolute = true,
            favours = Side.BLACK,
            evidence = Evidence.of(Square.C2, Square.C8, Square.E1),
        )

        val text = render(pin)

        assertTrue(text, text.contains("Фигура на c2 связана намертво — за ней король на e1."))
    }

    @Test
    fun `relative pin names both the pinned piece and the piece behind it`() {
        val pin = PinFact(
            pinned = Square.C2,
            pinner = Square.C8,
            behind = Square.E1,
            absolute = false,
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.C2, Square.C8, Square.E1),
                pieces = mapOf(Square.C2 to Piece.WHITE_KNIGHT, Square.E1 to Piece.WHITE_QUEEN),
            ),
        )

        val text = render(pin)

        assertTrue(text, text.contains("Конь на c2 связан: за ним на e1 стоит ферзь дороже."))
    }

    // ——— Рентген ———

    @Test
    fun `skewer names the front and the behind piece when recorded`() {
        val skewer = SkewerFact(
            front = Square.D4,
            behind = Square.D8,
            attacker = Square.D1,
            favours = Side.WHITE,
            evidence = Evidence(
                squares = listOf(Square.D4, Square.D8, Square.D1),
                pieces = mapOf(Square.D4 to Piece.BLACK_QUEEN, Square.D8 to Piece.BLACK_ROOK),
            ),
        )

        val text = render(skewer)

        assertTrue(text, text.contains("уйдёт ферзь с d4 — под боем окажется ладья d8."))
    }

    @Test
    fun `skewer without recorded pieces stays on the older generic wording`() {
        val skewer = SkewerFact(
            front = Square.D4,
            behind = Square.D8,
            attacker = Square.D1,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D4, Square.D8, Square.D1),
        )

        val text = render(skewer)

        assertTrue(text, text.contains("уйдёт фигура с d4 — под боем окажется d8."))
    }

    // ——— Вскрытое нападение ———

    @Test
    fun `discovered attack names the target and the piece it was revealed from`() {
        val discovered = DiscoveredAttackFact(
            moving = Square.F3,
            revealed = Square.B5,
            target = Square.D8,
            favours = Side.WHITE,
            evidence = Evidence(
                squares = listOf(Square.F3, Square.B5, Square.D8),
                pieces = mapOf(Square.D8 to Piece.BLACK_QUEEN, Square.B5 to Piece.WHITE_BISHOP),
            ),
        )

        val text = render(discovered)

        assertTrue(text, text.contains("открывает атаку на ферзя d8 от слона на b5."))
    }

    @Test
    fun `discovered attack without recorded pieces stays on the older generic wording`() {
        val discovered = DiscoveredAttackFact(
            moving = Square.F3,
            revealed = Square.B5,
            target = Square.D8,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.F3, Square.B5, Square.D8),
        )

        val text = render(discovered)

        assertTrue(text, text.contains("открывает атаку на d8 от фигуры на b5."))
    }

    // ——— Перегрузка ———

    @Test
    fun `overloaded knight is masculine both in the adjective and in the verb`() {
        val overload = OverloadedDefenderFact(
            defender = Square.D2,
            duties = listOf(Square.D5, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.D2, Square.D5, Square.F3),
                pieces = mapOf(Square.D2 to Piece.BLACK_KNIGHT),
            ),
        )

        val text = render(overload)

        // Первое упоминание термина несёт сноску-определение в скобках между
        // "конь" и "на d2" (см. GlossaryTest) — поэтому проверяем начало и
        // хвост предложения отдельно, а не одной сплошной строкой.
        assertTrue(text, text.contains("Перегруженный конь"))
        assertTrue(text, text.contains("на d2 должен успевать сразу"))
    }

    @Test
    fun `overloaded rook is feminine both in the adjective and in the verb`() {
        val overload = OverloadedDefenderFact(
            defender = Square.D2,
            duties = listOf(Square.D5, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.D2, Square.D5, Square.F3),
                pieces = mapOf(Square.D2 to Piece.BLACK_ROOK),
            ),
        )

        val text = render(overload)

        assertTrue(text, text.contains("Перегруженная ладья"))
        assertTrue(text, text.contains("на d2 должна успевать сразу"))
    }

    @Test
    fun `overload without a recorded defender piece stays on the older generic wording`() {
        val overload = OverloadedDefenderFact(
            defender = Square.D2,
            duties = listOf(Square.D5, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.D2, Square.D5, Square.F3),
        )

        val text = render(overload)

        assertTrue(text, text.contains("Перегруженная фигура"))
        assertTrue(text, text.contains("на d2 должна успевать сразу на d5 и f3."))
    }

    @Test
    fun `overload also names the duty squares when their pieces are recorded`() {
        val overload = OverloadedDefenderFact(
            defender = Square.D2,
            duties = listOf(Square.D5, Square.F3),
            favours = Side.BLACK,
            evidence = Evidence(
                squares = listOf(Square.D2, Square.D5, Square.F3),
                pieces = mapOf(Square.D5 to Piece.WHITE_KNIGHT, Square.F3 to Piece.WHITE_ROOK),
            ),
        )

        val text = render(overload)

        assertTrue(text, text.contains("должна успевать сразу на коня d5 и ладью f3."))
    }
}
