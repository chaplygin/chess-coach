package org.chesscoach.coach

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import kotlinx.coroutines.runBlocking
import org.chesscoach.coach.analysis.AnalyzedGame
import org.chesscoach.coach.analysis.ErrorKind
import org.chesscoach.coach.detectors.ExchangeDetector
import org.chesscoach.coach.detectors.ForkThreatDetector
import org.chesscoach.coach.detectors.HangingDetector
import org.chesscoach.coach.detectors.PawnBreakDetector
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.model.ExchangeFact
import org.chesscoach.coach.model.ForkThreatFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.PawnBreakFact
import org.chesscoach.coach.model.Section
import org.chesscoach.coach.model.Variations
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.EngineLine
import org.chesscoach.core.GameReplay
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Позиции из партий, на которых тренер ошибался, и то, как он должен отвечать теперь. */
class CoachAdviceTest {

    /** Чёрные могут забрать коня d4 ферзём, а пешкой брать хуже. */
    private val knightPosition = "r3kb1r/ppp3pp/5p2/3Ppb2/2PNq3/8/PP2BPPP/R1BQ1RK1 b kq - 0 10"

    /** Чёрные: Qg4 нападает на g2 и g5, но ферзя сразу меняют. d5-d4 бьёт коня и пешку. */
    private val queenPosition = "r1b1k1nr/pppq2pp/2nb4/3pp1B1/8/2NBP3/PPPN1PPP/R2QK2R b KQkq - 0 9"

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }

    /** Отвечает заранее заданной строкой по позиции, а на остальное — нейтральным ходом. */
    private class ScriptedEngine(private val lines: Map<String, EngineLine>) : EngineClient {
        override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> {
            val key = lines.keys.firstOrNull { fen.startsWith(it) } ?: return emptyList()
            return listOf(lines.getValue(key))
        }
    }

    @Test
    fun `recapture explains itself with moves`() {
        val fact = HangingDetector().detect(board(knightPosition)).filterIsInstance<HangingPieceFact>()
            .single { it.square == Square.D4 }
        assertEquals(listOf("exd4", "Qxd4", "Qxd4"), fact.recaptureLineSan)

        val text = TextRenderer.render(
            sample(Section.MyAttack(emptyList(), listOf(fact), emptyList())),
        )
        assertTrue(text, text.contains("если взять обратно Qxd4, то Qxd4 и ферзь потерян"))
    }

    @Test
    fun `exchange names the whole line and who gains`() {
        val fact = ExchangeDetector().detect(board(knightPosition)).filterIsInstance<ExchangeFact>()
            .single { it.square == Square.F5 }
        assertEquals(listOf("Nxf5", "Qxf5"), fact.lineSan)

        val text = TextRenderer.render(
            sample(Section.MyPosition(emptyList(), emptyList(), exchanges = listOf(fact))),
        )
        assertTrue(text, text.contains("Nxf5 Qxf5: конь за слона, материал +0.1 в пользу белых."))
    }

    @Test
    fun `fork that is traded at once says so`() {
        val fact = ForkThreatDetector().detect(board(queenPosition)).filterIsInstance<ForkThreatFact>()
            .first { it.to == Square.G4 }
        assertEquals(listOf("Qxg4", "Bxg4"), fact.tradeLineSan)

        val text = TextRenderer.render(
            sample(Section.MyAttack(emptyList(), emptyList(), emptyList(), advice = listOf(
                org.chesscoach.coach.model.CheckedAdvice(fact, Score.Cp(40)),
            ))),
        )
        assertTrue(text, text.contains("но Qxg4 Bxg4 — размен ферзей"))
        assertTrue(text, text.contains("(оценка +0.4)"))
        assertTrue(text, text.substringBefore("3. Активные").contains("2. Взятия: нет."))
    }

    @Test
    fun `pawn break names every target`() {
        val fact = PawnBreakDetector().detect(board(queenPosition)).filterIsInstance<PawnBreakFact>()
            .first { it.to == Square.D4 }
        assertEquals(setOf(Square.C3, Square.E3), (listOf(fact.target) + fact.alsoAttacks).toSet())
    }

    @Test
    fun `advice that loses half a pawn is not given`() = runBlocking {
        val base = queenPosition.substringBefore(" ")
        val engine = ScriptedEngine(
            mapOf(
                // Лучший ход и оценка позиции: от лица чёрных.
                queenPosition to EngineLine(1, Score.Cp(-20), listOf("e5e4", "d3e2"), 10),
                // После d5-d4 движок за белых видит большой перевес.
                "r1b1k1nr/pppq2pp/2nb4/4p1B1/3p4/2NBP3/PPPN1PPP/R2QK2R w" to EngineLine(1, Score.Cp(230), listOf("e3d4"), 10),
            ),
        )
        val explanation = CoachEngine(engine).explain(queenPosition)
        val advice = explanation.section<Section.MyAttack>()!!.advice
        assertFalse(base, advice.any { it.fact is PawnBreakFact && (it.fact as PawnBreakFact).to == Square.D4 })
    }

    @Test
    fun `played move explains score, line and reason`() = runBlocking {
        val after = "r1b1k1nr/pppq2pp/2nb4/4p1B1/3p4/2NBP3/PPPN1PPP/R2QK2R w"
        val engine = ScriptedEngine(
            mapOf(
                queenPosition to EngineLine(1, Score.Cp(-20), listOf("e5e4", "d3e2"), 10),
                after to EngineLine(1, Score.Cp(230), listOf("e3d4", "e5d4", "c3d5"), 10),
            ),
        )
        val result = CoachEngine(engine).tryCandidate(queenPosition, "d4") as CandidateResult.Explained
        assertTrue(result.text, result.text.contains("Оценка после хода: +2.3. После лучшего e4: +0.2."))
        assertTrue(result.text, result.text.contains("Вариант: d4 exd4 exd4 Nd5."))
        assertTrue(result.text, result.text.contains("Почему:"))
        assertFalse(result.text, result.text.contains("Вопрос на будущее"))
        assertNotNull(result.variation)
        assertEquals("d2d4".length, result.variation!!.uci.first().length)
    }

    @Test
    fun `verdict comes first and names the capture`() = runBlocking {
        val engine = ScriptedEngine(mapOf(knightPosition to EngineLine(1, Score.Cp(-120), listOf("e4d4", "d1b3"), 10)))
        val text = TextRenderer.render(CoachEngine(engine).explain(knightPosition))
        assertTrue(text, text.lines()[1].startsWith("Вывод: играть Qxd4 (+1.2): берёт коня d4."))
    }

    @Test
    fun `opening idea names development instead of defending the king`() = runBlocking {
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
        val engine = ScriptedEngine(mapOf(fen to EngineLine(1, Score.Cp(20), listOf("g1f3"), 10)))
        val text = TextRenderer.render(CoachEngine(engine).explain(fen))
        assertTrue(text, text.contains("Идея: развить"))
        assertTrue(text, text.contains("сделать рокировку"))
        assertFalse(text, text.contains("подтянуть фигуры"))
    }

    @Test
    fun `variations replay on real positions`() = runBlocking {
        val engine = ScriptedEngine(mapOf(knightPosition to EngineLine(1, Score.Cp(-120), listOf("e4d4", "d1b3"), 10)))
        val variation = Variations.of(CoachEngine(engine).explain(knightPosition)).single()
        assertEquals(listOf("Qxd4", "Qb3"), variation.san)
        assertEquals(2, variation.fens.size)
    }

    @Test
    fun `error report counts only my moves and names the repeated cause`() = runBlocking {
        // Детский мат: чёрные дважды ходят плохо, у белых ходы в порядке.
        val positions = GameReplay.replay(listOf("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#"), null)
        val game = AnalyzedGame(
            iAmWhite = false,
            positions = positions,
            classifications = mapOf(5 to MoveClassification.BLUNDER, 0 to MoveClassification.BLUNDER),
        )
        val report = CoachEngine(ScriptedEngine(emptyMap())).errorReport(listOf(game))
        assertEquals(1, report.games)
        assertEquals(3, report.myMoves)
        assertEquals(1, report.blunders)
        assertEquals(1, report.byKind.sumOf { it.moves })
        assertTrue(report.render(), report.render().contains("Разобрано партий: 1, ваших ходов: 3."))
        assertTrue(ErrorKind.entries.isNotEmpty())
    }

    private fun sample(section: Section) = org.chesscoach.coach.model.PositionExplanation(
        fen = knightPosition,
        sideToMove = com.github.bhlangonijr.chesslib.Side.BLACK,
        scoreFromWhite = Score.Cp(120),
        bestMoveSan = "Qxd4",
        bestMoveUci = "e4d4",
        sections = listOf(section),
        featuresBefore = org.chesscoach.coach.model.FeatureSet.empty(knightPosition),
    )
}
