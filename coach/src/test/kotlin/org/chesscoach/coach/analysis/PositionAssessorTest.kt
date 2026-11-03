package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PositionCharacter
import org.chesscoach.coach.model.StrategicIdea
import org.chesscoach.core.START_FEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PositionAssessor не трогает движок вовсе: характер позиции — из доски,
 * идеи — из уже посчитанного [FeatureSet]. Тесты поэтому не поднимают
 * никакого движка, только доску и заранее собранные факты.
 */
class PositionAssessorTest {

    // Ни одной пешки вовсе — центр пуст, все линии открыты.
    private val kingsOnly = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"

    // Французский тип структуры: d4/d5 и e5/e6 заперты друг о друга, и на
    // остальных линиях тоже есть пешки — открытых линий нет вовсе.
    private val lockedCenter = "4k3/ppp2ppp/4p3/3pP3/3P4/8/PPP2PPP/4K3 w - - 0 1"

    private fun features(materialCentipawns: Int = 0, facts: List<org.chesscoach.coach.model.Fact> = emptyList()) =
        FeatureSet.empty(START_FEN).copy(materialCentipawns = materialCentipawns, facts = facts)

    // ——— Характер позиции ———

    @Test
    fun `no pawns at all and every file open reads as an open position`() {
        val section = PositionAssessor.assess(kingsOnly, features(), Side.WHITE)
        assertEquals(PositionCharacter.OPEN, section.character)
    }

    @Test
    fun `locked center pawn chains with no open file read as a closed position`() {
        val section = PositionAssessor.assess(lockedCenter, features(), Side.WHITE)
        assertEquals(PositionCharacter.CLOSED, section.character)
    }

    @Test
    fun `the starting position is neither open nor closed`() {
        val section = PositionAssessor.assess(START_FEN, features(), Side.WHITE)
        assertEquals(PositionCharacter.SEMI_OPEN, section.character)
    }

    // ——— План развития ———

    @Test
    fun `developed minor pieces with castling still pending give a plan, not a crash`() {
        // Позиция из партии пользователя: лёгкие фигуры белых вышли, король в центре.
        // Раньше план без неразвитых фигур оставался без доказательства и разбор падал.
        val fen = "rn1qkb1r/pp2p1pp/2p2p2/3nNb2/2BPp3/2N1B3/PPP2PPP/R2QK2R w KQkq - 0 8"
        val opening = FeatureSet.empty(fen).copy(phase = org.chesscoach.coach.model.GamePhase.OPENING)
        val plan = PositionAssessor.assess(fen, opening, Side.WHITE).developmentPlan
        assertTrue(plan != null && plan.castlingPending && plan.undeveloped.isEmpty())
    }

    // ——— Идеи ———

    @Test
    fun `nothing sharp in the position falls back to improving pieces`() {
        val section = PositionAssessor.assess(START_FEN, features(), Side.WHITE)
        assertEquals(listOf(StrategicIdea.IMPROVE_PIECES), section.ideas)
    }

    @Test
    fun `a moderate material edge suggests forcing exchanges, not simplifying into an endgame`() {
        val section = PositionAssessor.assess(START_FEN, features(materialCentipawns = 150), Side.WHITE)
        assertEquals(listOf(StrategicIdea.FORCE_EXCHANGES), section.ideas)
    }

    @Test
    fun `a large material edge suggests heading into an endgame instead`() {
        val section = PositionAssessor.assess(START_FEN, features(materialCentipawns = 600), Side.WHITE)
        assertEquals(listOf(StrategicIdea.SIMPLIFY_TO_ENDGAME), section.ideas)
    }

    @Test
    fun `material is read from the side to move, not always from white`() {
        // Белые проигрывают материал (плюс у чёрных), но ход чёрных — значит,
        // с их точки зрения это перевес, а не недостаток.
        val section = PositionAssessor.assess(START_FEN, features(materialCentipawns = -200), Side.BLACK)
        assertEquals(listOf(StrategicIdea.FORCE_EXCHANGES), section.ideas)
    }

    @Test
    fun `a passed pawn of mine suggests pushing pawns`() {
        val passedPawn = PassedPawnFact(
            square = Square.D6,
            owner = Side.WHITE,
            defended = true,
            squaresToPromotion = 2,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D6),
        )
        val section = PositionAssessor.assess(START_FEN, features(facts = listOf(passedPawn)), Side.WHITE)
        assertEquals(listOf(StrategicIdea.PUSH_PAWNS), section.ideas)
    }

    @Test
    fun `my own exposed king suggests defending`() {
        val myKing = KingSafetyFact(
            king = Square.E1,
            kingSide = Side.WHITE,
            attackerCount = 2,
            shieldPawnsMissing = 2,
            openFilesNearKing = emptyList(),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.E1),
        )
        val section = PositionAssessor.assess(START_FEN, features(facts = listOf(myKing)), Side.WHITE)
        assertEquals(listOf(StrategicIdea.DEFEND), section.ideas)
    }

    @Test
    fun `an exposed enemy king with attacking resources on hand suggests attacking it`() {
        val enemyKing = KingSafetyFact(
            king = Square.G8,
            kingSide = Side.BLACK,
            attackerCount = 2,
            shieldPawnsMissing = 2,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G8),
        )
        val hangingRook = HangingPieceFact(
            square = Square.D8,
            piece = Piece.BLACK_ROOK,
            lossCentipawns = 500,
            favours = Side.WHITE,
            evidence = Evidence.of(Square.D8),
        )
        val section = PositionAssessor.assess(
            START_FEN,
            features(facts = listOf(enemyKing, hangingRook)),
            Side.WHITE,
        )
        assertEquals(listOf(StrategicIdea.ATTACK_KING), section.ideas)
    }

    @Test
    fun `an exposed enemy king without anything to attack with is not called an attack`() {
        // Король соперника раскрыт, но у меня самого ни одной зацепки: врать
        // про атаку хуже, чем промолчать.
        val enemyKing = KingSafetyFact(
            king = Square.G8,
            kingSide = Side.BLACK,
            attackerCount = 2,
            shieldPawnsMissing = 2,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G8),
        )
        val section = PositionAssessor.assess(START_FEN, features(facts = listOf(enemyKing)), Side.WHITE)
        assertEquals(listOf(StrategicIdea.IMPROVE_PIECES), section.ideas)
    }

    @Test
    fun `a material edge in a closed position also suggests opening it up`() {
        val section = PositionAssessor.assess(lockedCenter, features(materialCentipawns = 150), Side.WHITE)
        assertEquals(listOf(StrategicIdea.FORCE_EXCHANGES, StrategicIdea.OPEN_THE_POSITION), section.ideas)
    }

    @Test
    fun `a material deficit in a closed position suggests keeping it closed`() {
        val section = PositionAssessor.assess(lockedCenter, features(materialCentipawns = -150), Side.WHITE)
        assertEquals(listOf(StrategicIdea.KEEP_IT_CLOSED), section.ideas)
    }

    @Test
    fun `a bare king of my own outweighs the chance to attack theirs`() {
        val myKing = KingSafetyFact(
            king = Square.E1,
            kingSide = Side.WHITE,
            attackerCount = 1,
            shieldPawnsMissing = 1,
            openFilesNearKing = emptyList(),
            favours = Side.BLACK,
            evidence = Evidence.of(Square.E1),
        )
        val enemyKing = KingSafetyFact(
            king = Square.G8,
            kingSide = Side.BLACK,
            attackerCount = 2,
            shieldPawnsMissing = 2,
            openFilesNearKing = emptyList(),
            favours = Side.WHITE,
            evidence = Evidence.of(Square.G8),
        )
        val hangingRook = HangingPieceFact(Square.D8, Piece.BLACK_ROOK, 500, Side.WHITE, Evidence.of(Square.D8))

        val section = PositionAssessor.assess(
            START_FEN,
            features(materialCentipawns = 600, facts = listOf(myKing, enemyKing, hangingRook)),
            Side.WHITE,
        )

        // "Атаковать короля и укрепить оборону" — это шум: сделать можно
        // что-то одно. Свой король важнее, потому что мат заканчивает партию,
        // а упущенная атака — нет.
        assertTrue(section.ideas.toString(), StrategicIdea.DEFEND in section.ideas)
        assertTrue(section.ideas.toString(), StrategicIdea.ATTACK_KING !in section.ideas)
        assertTrue(section.ideas.toString(), section.ideas.size <= 2)
    }
}
