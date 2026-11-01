package org.chesscoach.web

import com.github.bhlangonijr.chesslib.Board
import org.chesscoach.coach.CandidateResult
import org.chesscoach.coach.CoachEngine
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.model.BoardMarks
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.EngineLine
import org.chesscoach.core.GameAnalyzer
import org.chesscoach.core.GameReplay
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.Score
import org.chesscoach.core.UciParser
import org.teavm.jso.JSExport
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@JSFunctor
fun interface LinesCallback : JSObject {
    fun accept(infoLines: String)
}

@JSFunctor
fun interface EngineBridge : JSObject {
    fun analyse(fen: String, movetimeMs: Int, depth: Int, multiPv: Int, done: LinesCallback)
}

@JSFunctor
fun interface DoneCallback : JSObject {
    fun done(text: String?, arrows: String?, error: String?)
}

/**
 * Граница между страницей и кодом тренера.
 *
 * Наружу уходят только строки: FEN и UCI не содержат разделителей ROW и FIELD,
 * а тащить в браузер библиотеку JSON ради таблицы из пяти колонок незачем.
 */
object WebCoach {
    private const val ROW = ";"
    private const val FIELD = "|"
    private const val NO_DEPTH = 0
    private const val MATED_CP = -GameAnalyzer.MATED_CP

    private class BridgeClient(private val bridge: EngineBridge) : EngineClient {
        override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> =
            suspendCoroutine { continuation ->
                bridge.analyse(fen, limits.movetimeMs.toInt(), limits.depth ?: NO_DEPTH, limits.multiPv) { info ->
                    continuation.resume(info.split(FIELD).mapNotNull(UciParser::parseInfo))
                }
            }
    }

    @JvmStatic
    fun main(args: Array<String>) = Unit

    /** Партия в позиции: index|fenBefore|fenAfter|san|uci|moveNumber|whiteMoved. */
    @JvmStatic
    @JSExport
    fun replay(moves: String, initialFen: String?): String =
        GameReplay.replay(moves.split(" ").filter { it.isNotEmpty() }, initialFen?.takeIf { it.isNotEmpty() })
            .joinToString(ROW) {
                listOf(it.index, it.fenBefore, it.fenAfter, it.san, it.uci, it.moveNumber, it.whiteMoved)
                    .joinToString(FIELD)
            }

    /** [scores] вида "cp:15;mate:-3", на одну больше, чем полуходов. Ответ: class|loss|whiteCp|whiteMate. */
    @JvmStatic
    @JSExport
    fun classify(positions: String, scores: String): String {
        val plies = positions.split(ROW).filter { it.isNotEmpty() }.map { row ->
            val f = row.split(FIELD)
            PlyPosition(f[0].toInt(), f[1], f[2], f[3], f[4], f[5].toInt(), f[6] == "true")
        }
        val parsed = scores.split(ROW).map { item ->
            val value = item.substringAfter(":").toInt()
            if (item.startsWith("mate")) Score.Mate(value) else Score.Cp(value)
        }
        return GameAnalyzer.classify(plies, parsed).joinToString(ROW) { ply ->
            val score = ply.scoreAfterWhite
            val cp = (score as? Score.Cp)?.centipawns?.toString().orEmpty()
            val mate = (score as? Score.Mate)?.movesToMate?.toString().orEmpty()
            listOf(ply.classification.name, ply.centipawnLoss, cp, mate).joinToString(FIELD)
        }
    }

    /**
     * Оценка позиции без ходов, в которой движку считать нечего: мат или пат.
     * Пустая строка значит, что ходы есть, и молчание движка было сбоем.
     */
    @JvmStatic
    @JSExport
    fun terminalScore(fen: String): String {
        val board = Board().apply { loadFromFen(fen) }
        return when {
            board.legalMoves().isNotEmpty() -> ""
            board.isMated -> "cp:$MATED_CP"
            else -> "cp:0"
        }
    }

    /** Разбор тренера и стрелки вида "e2e4:BEST_MOVE;...". Пустой [playedUci]: хода из позиции не было. */
    @JvmStatic
    @JSExport
    fun explain(fen: String, playedUci: String?, opening: String?, bridge: EngineBridge, done: DoneCallback) {
        launch(done) {
            val explanation = CoachEngine(BridgeClient(bridge))
                .explain(fen, playedUci?.takeIf { it.isNotEmpty() }, opening?.takeIf { it.isNotEmpty() })
            val arrows = BoardMarks.of(explanation)
                .joinToString(ROW) { it.from + it.to + ":" + it.style.name }
            TextRenderer.render(explanation) to arrows
        }
    }

    /** Разбор хода, который человек предложил сам. */
    @JvmStatic
    @JSExport
    fun tryCandidate(fen: String, input: String, bridge: EngineBridge, done: DoneCallback) {
        launch(done) {
            val text = when (val result = CoachEngine(BridgeClient(bridge)).tryCandidate(fen, input)) {
                is CandidateResult.Explained -> result.text
                CandidateResult.NotLegal -> "Такого хода в этой позиции нет."
                CandidateResult.Ambiguous -> "Подходит несколько ходов, уточните запись."
                CandidateResult.NoVerdict -> "Движку нечего сказать об этом ходе."
            }
            text to ""
        }
    }

    private fun launch(done: DoneCallback, block: suspend () -> Pair<String, String>) {
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                result.fold(
                    onSuccess = { done.done(it.first, it.second, null) },
                    onFailure = { done.done(null, null, it.message ?: it.toString()) },
                )
            },
        )
    }
}
