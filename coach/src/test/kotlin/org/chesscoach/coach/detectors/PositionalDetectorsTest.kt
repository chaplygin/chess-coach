package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BadBishopFact
import org.chesscoach.coach.model.CenterControlFact
import org.chesscoach.coach.model.DevelopmentFact
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.KingSafetyFact
import org.chesscoach.coach.model.MobilityEdgeFact
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PawnWeaknessKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionalDetectorsTest {

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }
    private fun startingPosition() = Board()

    // ——— PhaseClassifier ———

    @Test
    fun `phase classifier calls the starting position an opening`() {
        assertEquals(GamePhase.OPENING, PhaseClassifier.classify(startingPosition()))
    }

    @Test
    fun `phase classifier calls a king and pawn position with no queens an endgame`() {
        val phase = PhaseClassifier.classify(board("8/8/8/4k3/8/8/4P3/4K3 w - - 0 1"))
        assertEquals(GamePhase.ENDGAME, phase)
    }

    @Test
    fun `phase classifier calls the starting setup at move twenty a middlegame`() {
        // Расстановка не изменилась, но столько ходов уже сыграно быть не может —
        // счётчик хода перевешивает голое "фигуры ещё дома".
        val phase = PhaseClassifier.classify(board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 20"))
        assertEquals(GamePhase.MIDDLEGAME, phase)
    }

    // ——— KingSafetyDetector ———

    @Test
    fun `king safety detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), KingSafetyDetector().detect(startingPosition()))
    }

    @Test
    fun `king safety detector finds an open file and a missing shield pawn near the white king`() {
        val facts = KingSafetyDetector().detect(board("k5r1/8/8/8/8/8/5P1P/6K1 w - - 0 1"))

        val fact = facts.filterIsInstance<KingSafetyFact>().single { it.kingSide == Side.WHITE }
        assertEquals(Square.G1, fact.king)
        assertTrue(fact.attackerCount >= 1)
        assertEquals(1, fact.shieldPawnsMissing)
        assertEquals(listOf('g'), fact.openFilesNearKing)
        assertEquals(Side.BLACK, fact.favours)
    }

    // ——— PawnStructureDetector ———

    @Test
    fun `pawn structure detector is quiet on the starting position`() {
        val facts = PawnStructureDetector().detect(startingPosition())
        assertEquals(emptyList<Any>(), facts.filterIsInstance<PawnWeaknessFact>())
        assertEquals(emptyList<Any>(), facts.filterIsInstance<PassedPawnFact>())
        assertEquals(emptyList<Any>(), facts.filterIsInstance<OutpostFact>())
    }

    @Test
    fun `pawn structure detector finds an isolated pawn with no neighbours on either file`() {
        val facts = PawnStructureDetector().detect(board("4k3/8/8/8/3P4/8/8/4K3 w - - 0 1"))

        val fact = facts.filterIsInstance<PawnWeaknessFact>().single { it.kind == PawnWeaknessKind.ISOLATED }
        assertEquals(Square.D4, fact.square)
        assertEquals(Side.WHITE, fact.owner)
        assertEquals(Side.BLACK, fact.favours)
    }

    @Test
    fun `pawn structure detector finds doubled pawns on the same file`() {
        val facts = PawnStructureDetector().detect(board("4k3/8/8/8/3P4/8/3P4/4K3 w - - 0 1"))

        val doubled = facts.filterIsInstance<PawnWeaknessFact>().filter { it.kind == PawnWeaknessKind.DOUBLED }
        assertEquals(setOf(Square.D2, Square.D4), doubled.map { it.square }.toSet())
    }

    @Test
    fun `pawn structure detector finds a backward pawn held back by a pawn controlling the square ahead`() {
        val facts = PawnStructureDetector().detect(board("7k/8/8/4p3/2P1P3/3P4/8/7K w - - 0 1"))

        val fact = facts.filterIsInstance<PawnWeaknessFact>().single { it.kind == PawnWeaknessKind.BACKWARD }
        assertEquals(Square.D3, fact.square)
        assertEquals(Side.WHITE, fact.owner)
        assertEquals(Side.BLACK, fact.favours)
    }

    @Test
    fun `pawn structure detector finds a passed pawn with no enemy pawns ahead on nearby files`() {
        val facts = PawnStructureDetector().detect(board("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1"))

        val fact = facts.filterIsInstance<PassedPawnFact>().single()
        assertEquals(Square.E5, fact.square)
        assertEquals(Side.WHITE, fact.owner)
        assertFalse(fact.defended)
        assertEquals(3, fact.squaresToPromotion)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `pawn structure detector finds an outpost knight defended by a pawn and safe from enemy pawns`() {
        val facts = PawnStructureDetector().detect(board("4k3/p6p/8/3N4/4P3/8/8/6K1 w - - 0 1"))

        val fact = facts.filterIsInstance<OutpostFact>().single()
        assertEquals(Square.D5, fact.square)
        assertEquals(Side.WHITE, fact.owner)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `pawn structure detector does not call an outpost square without a piece on it`() {
        val facts = PawnStructureDetector().detect(board("4k3/p6p/8/8/4P3/8/8/6K1 w - - 0 1"))

        assertEquals(emptyList<Any>(), facts.filterIsInstance<OutpostFact>())
    }

    // ——— PieceActivityDetector ———

    @Test
    fun `piece activity detector is quiet on the starting position`() {
        assertEquals(emptyList<Any>(), PieceActivityDetector().detect(startingPosition()))
    }

    @Test
    fun `piece activity detector finds a rook on a fully open file`() {
        val facts = PieceActivityDetector().detect(board("4k3/8/8/8/8/8/8/3RK3 w - - 0 1"))

        val fact = facts.filterIsInstance<OpenFileRookFact>().single()
        assertEquals(Square.D1, fact.rook)
        assertEquals('d', fact.file)
        assertFalse(fact.halfOpen)
        assertEquals(Side.WHITE, fact.favours)
    }

    @Test
    fun `piece activity detector marks the file half open when an enemy pawn remains on it`() {
        val facts = PieceActivityDetector().detect(board("4k3/3p4/8/8/8/8/8/3RK3 w - - 0 1"))

        val fact = facts.filterIsInstance<OpenFileRookFact>().single()
        assertTrue(fact.halfOpen)
    }

    @Test
    fun `piece activity detector finds a bad bishop behind three blocked same colour pawns`() {
        val facts = PieceActivityDetector().detect(board("4k3/8/8/8/8/p1p1p3/P1P1P3/5BK1 w - - 0 1"))

        val fact = facts.filterIsInstance<BadBishopFact>().single()
        assertEquals(Square.F1, fact.bishop)
        assertEquals(setOf(Square.A2, Square.C2, Square.E2), fact.blockedPawns.toSet())
        assertEquals(Side.BLACK, fact.favours)
    }

    @Test
    fun `piece activity detector finds a large mobility and centre control edge`() {
        val facts = PieceActivityDetector().detect(board("4k3/8/8/8/8/8/2N5/R2QK3 w - - 0 1"))

        val mobility = facts.filterIsInstance<MobilityEdgeFact>().single()
        assertEquals(Side.WHITE, mobility.owner)
        assertTrue(mobility.ownMoves - mobility.opponentMoves > org.chesscoach.coach.model.CoachThresholds.MOBILITY_EDGE)

        val center = facts.filterIsInstance<CenterControlFact>().single()
        assertEquals(Side.WHITE, center.owner)
        assertTrue(center.ownControl - center.opponentControl > org.chesscoach.coach.model.CoachThresholds.CENTER_EDGE)
    }

    // ——— DevelopmentDetector ———

    @Test
    fun `development detector lists all six undeveloped pieces for both sides at the start`() {
        val facts = DevelopmentDetector().detect(startingPosition()).filterIsInstance<DevelopmentFact>()

        assertEquals(2, facts.size)
        facts.forEach { fact ->
            assertEquals(6, fact.undeveloped.size)
            assertFalse(fact.castled)
        }
    }

    @Test
    fun `development detector stays quiet for a side that finished developing and castled`() {
        val facts = DevelopmentDetector()
            .detect(board("rnbqkbnr/pppppppp/8/8/8/2NBBN2/PPPPPPPP/3R1RK1 w kq - 0 8"))
            .filterIsInstance<DevelopmentFact>()

        val fact = facts.single()
        assertEquals(Side.BLACK, fact.owner)
        assertEquals(
            setOf(Square.B8, Square.G8, Square.C8, Square.F8, Square.A8, Square.H8),
            fact.undeveloped.toSet(),
        )
        assertFalse(fact.castled)
    }

    @Test
    fun `development detector is silent outside the opening even if pieces never moved`() {
        val facts = DevelopmentDetector()
            .detect(board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 20"))

        assertEquals(emptyList<Any>(), facts)
    }
}
