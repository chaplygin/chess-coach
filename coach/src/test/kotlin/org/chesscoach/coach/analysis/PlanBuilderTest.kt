package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.Evidence
import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.OpenFileRookFact
import org.chesscoach.coach.model.OutpostFact
import org.chesscoach.coach.model.PassedPawnFact
import org.chesscoach.coach.model.PawnWeaknessFact
import org.chesscoach.coach.model.PawnWeaknessKind
import org.chesscoach.coach.model.PlanIdea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBuilderTest {

    private fun outpost(owner: Side, square: Square = Square.D5) = OutpostFact(
        square = square,
        piece = Piece.make(owner, com.github.bhlangonijr.chesslib.PieceType.KNIGHT),
        owner = owner,
        favours = owner,
        evidence = Evidence.of(square),
    )

    private fun passedPawn(owner: Side, square: Square = Square.A6) = PassedPawnFact(
        square = square,
        owner = owner,
        defended = false,
        squaresToPromotion = 2,
        favours = owner,
        evidence = Evidence.of(square),
    )

    private fun weakPawn(owner: Side, square: Square = Square.C6) = PawnWeaknessFact(
        kind = PawnWeaknessKind.ISOLATED,
        square = square,
        owner = owner,
        favours = owner.flip(),
        evidence = Evidence.of(square),
    )

    private fun openFileRook(owner: Side, square: Square = Square.D1) = OpenFileRookFact(
        rook = square,
        file = 'd',
        halfOpen = false,
        owner = owner,
        favours = owner,
        evidence = Evidence.of(square),
    )

    @Test
    fun `empty plan when there is nothing to hang a plan on`() {
        val steps = PlanBuilder.build(FeatureSet.empty("irrelevant"), Side.WHITE)

        assertTrue(steps.isEmpty())
    }

    @Test
    fun `plan step for our outpost, opponent's weak pawn and our open file rook`() {
        val features = FeatureSet.empty("irrelevant").copy(
            facts = listOf(
                outpost(Side.WHITE),
                passedPawn(Side.BLACK), // не наша проходная — план белых её не касается
                weakPawn(Side.BLACK),
                openFileRook(Side.WHITE),
            ),
        )

        val steps = PlanBuilder.build(features, Side.WHITE)

        assertEquals(
            listOf(PlanIdea.OCCUPY_OUTPOST, PlanIdea.TARGET_WEAK_PAWN, PlanIdea.OPEN_FILE_FOR_ROOK),
            steps.map { it.idea },
        )
        steps.forEach { assertTrue(it.facts.isNotEmpty()) }
    }

    @Test
    fun `plan never grows past three steps even with more candidates`() {
        val features = FeatureSet.empty("irrelevant").copy(
            facts = listOf(
                outpost(Side.WHITE, Square.D5),
                outpost(Side.WHITE, Square.F5),
                passedPawn(Side.WHITE),
                weakPawn(Side.BLACK),
                openFileRook(Side.WHITE),
            ),
        )

        val steps = PlanBuilder.build(features, Side.WHITE)

        assertEquals(3, steps.size)
        assertEquals(
            listOf(PlanIdea.OCCUPY_OUTPOST, PlanIdea.OCCUPY_OUTPOST, PlanIdea.ADVANCE_PASSED_PAWN),
            steps.map { it.idea },
        )
    }
}
