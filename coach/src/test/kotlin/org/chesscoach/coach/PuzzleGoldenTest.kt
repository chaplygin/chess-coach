package org.chesscoach.coach

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import org.chesscoach.coach.detectors.BackRankDetector
import org.chesscoach.coach.detectors.Detector
import org.chesscoach.coach.detectors.DiscoveredAttackDetector
import org.chesscoach.coach.detectors.ForkDetector
import org.chesscoach.coach.detectors.HangingDetector
import org.chesscoach.coach.detectors.PinSkewerDetector
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.SkewerFact
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Проверка детекторов на задачах Lichess с чужой разметкой темы.
 *
 * Тесты, написанные на собственных FEN'ах, подтверждают лишь то, что мы сами
 * про эти позиции подумали. Здесь тему проставил не мы: если на задаче,
 * помеченной "fork", детектор вилки молчит вдоль всего решения, спорить не с
 * чем — он слеп.
 *
 * Проверка идёт по всей линии решения, а не только по начальной позиции:
 * мотив в задаче может возникать и на третьем полуходе. Строже требовать
 * нельзя, не выдумывая за Lichess, где именно он имел в виду мотив.
 */
class PuzzleGoldenTest {

    private val detectors: List<Detector> = listOf(
        ForkDetector(),
        PinSkewerDetector(),
        HangingDetector(),
        BackRankDetector(),
        DiscoveredAttackDetector(),
    )

    @Test
    fun `видит вилку там, где её разметил Lichess`() {
        checkTheme("fork") { it is ForkFact }
    }

    @Test
    fun `видит связку там, где её разметил Lichess`() {
        checkTheme("pin") { it is PinFact }
    }

    @Test
    fun `видит рентген там, где его разметил Lichess`() {
        checkTheme("skewer") { it is SkewerFact }
    }

    @Test
    fun `видит слабость последней горизонтали там, где её разметил Lichess`() {
        checkTheme("backRankMate") { it is BackRankFact }
    }

    @Test
    fun `видит повисшую фигуру там, где её разметил Lichess`() {
        checkTheme("hangingPiece") { it is HangingPieceFact }
    }

    @Test
    fun `видит вскрытое нападение там, где его разметил Lichess`() {
        checkTheme("discoveredAttack") { it is DiscoveredAttackFact }
    }

    private fun checkTheme(theme: String, matches: (Fact) -> Boolean) {
        val fixtures = PuzzleFixtures.of(theme)
        assumeTrue("нет задач с темой " + theme + ", пропускаю", fixtures.isNotEmpty())

        val blind = fixtures.filterNot { fixture ->
            positionsAlong(fixture).any { board ->
                detectors.flatMap { it.detect(board) }.any(matches)
            }
        }

        assertTrue(
            "детектор молчит на задачах " + theme + ": " +
                blind.joinToString(", ") { it.id + " (" + it.fen + ")" },
            blind.isEmpty(),
        )
    }

    /** Позиция задачи и все позиции вдоль её решения. */
    private fun positionsAlong(fixture: PuzzleFixture): List<Board> {
        val boards = ArrayList<Board>()
        val board = Board()
        if (!runCatching { board.loadFromFen(fixture.fen) }.isSuccess) return boards

        boards += board.clone()
        fixture.solution.forEach { uci ->
            val move = runCatching { Move(uci, board.sideToMove) }.getOrNull() ?: return boards
            if (!runCatching { board.doMove(move) }.getOrDefault(false)) return boards
            boards += board.clone()
        }
        return boards
    }
}
