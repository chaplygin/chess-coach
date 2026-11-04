package org.chesscoach.coach

import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.runBlocking
import org.chesscoach.coach.analysis.applyUciMove
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.MoveInput
import org.chesscoach.core.MoveInputResult
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Чтение вслух на позициях из замечаний пользователя и проверки на партиях
 * bufreg. Печатает разбор так, как его видит экран партии. Запускается только с
 * CHESSCOACH_STOCKFISH; с CHESSCOACH_POOL_OUT пишет в файл (UTF-8).
 */
class PoolReadingTest {

    private data class Position(val label: String, val fen: String, val san: String)

    private val positions = listOf(
        Position("DelphicKnight 26. Rd7+", "r2N4/pp5k/2p4p/5bp1/2B2p2/7P/P1P2PP1/3R2K1 w - - 0 26", "Rd7+"),
        Position("BunkerMate 14. g3", "r4rk1/ppp2p1p/3pb3/3N2p1/3QPBnq/3B4/PPP2PPP/R4RK1 w - - 0 14", "g3"),
        Position("edeak 21... Be5", "r3r1k1/p1p2ppp/2pb4/2P5/1P6/P2PBP2/4KP1q/R3R3 b - - 0 21", "Be5"),
        Position("Jerzy_SNU 20. Ke1", "4R1r1/2p2Q1p/p1kb4/3p4/8/2N3n1/PPPP1P2/R1B2KNq w - - 0 20", "Ke1"),
        Position("DelphicKnight 13. Rfb1", "r1b1k2r/pp3ppp/2p2n2/8/1b2N3/3B1N2/PqPQ1PPP/R4RK1 w kq - 2 13", "Rfb1"),
        Position("brsbyd6368 23... Qe7", "1r2r1k1/2pN1p1p/5qp1/3pb3/1P6/2P2P1b/2Q4P/R1B1R1K1 b - - 1 23", "Qe7"),
        Position("Baybik 8. O-O", "r1bqkb1r/ppp1n2p/3p1p2/4p1p1/1nBPP3/2NQBN2/PPP2PPP/R3K2R w KQkq - 2 8", "O-O"),
        Position("baycatj 17... Rc8", "r2q1rk1/pp3ppp/4b3/2pnP3/1b2NPP1/1P6/P1QB2BP/RN2K2R b KQ - 1 17", "Rc8"),
        Position("drethem9 13. Rac1", "r2q1rk1/ppp1n1pp/2n2p2/2b5/Q3P1bB/3p1N2/PP1N1PPP/R4RK1 w - - 4 13", "Rac1"),
        Position("Srihari23 22. Qg4", "5rk1/p5p1/2p1r2p/8/1P1P4/8/PP4PP/R1BQ2K1 w - - 0 22", "Qg4"),
        Position("nonamex435 12... Nxa1", "r2q1rk1/ppp1bppp/4b3/7Q/3nB3/8/PPnP1PPP/RNBK2NR b - - 1 12", "Nxa1"),
        Position("mikklev_84 17... Bd7", "r1bk3r/pp4pp/2p5/3P1p2/2BR1Bn1/2N5/PPP2bPP/5R1K b - - 1 17", "Bd7"),
        Position("marwan72 11... exd4", "r3kb1r/ppp3pp/5p2/3Ppb2/2PNq3/8/PP2BPPP/R1BQ1RK1 b kq - 0 11", "exd4"),
        Position("iliaabbasiii 26... Rg5", "Q7/pppk1pNp/3b4/4r3/2P5/1P5P/P4PP1/3qn1K1 b - - 2 26", "Rg5"),
    )

    @Test
    fun `печатает разбор позиций пула`() {
        val binary = LocalStockfishClient.locate()
        assumeTrue("нет CHESSCOACH_STOCKFISH, пропускаю", binary != null)
        val only = System.getenv("CHESSCOACH_POOL_ONLY")
        val out = StringBuilder()
        LocalStockfishClient(binary!!).use { engine ->
            val coach = CoachEngine(engine)
            runBlocking {
                positions.filter { only == null || it.label.contains(only) }.forEach { position ->
                    val uci = (MoveInput.parse(position.fen, position.san) as MoveInputResult.Parsed).uci
                    val viewer = if (position.fen.split(" ")[1] == "w") Side.WHITE else Side.BLACK
                    val explanation = coach.explain(position.fen, uci)
                    out.appendLine("=".repeat(72))
                    out.appendLine(position.label + " :: " + position.fen)
                    out.appendLine(TextRenderer.render(explanation, viewer, scoreLines = false))
                    val after = applyUciMove(position.fen, uci)
                    if (after != null) {
                        out.appendLine("-".repeat(30) + " после хода, читатель тот же")
                        out.appendLine(TextRenderer.render(coach.explain(after, null, null, position.fen, uci), viewer, scoreLines = false))
                    }
                }
            }
        }
        val file = System.getenv("CHESSCOACH_POOL_OUT")
        if (file != null) File(file).writeText(out.toString(), Charsets.UTF_8) else println(out)
    }
}
