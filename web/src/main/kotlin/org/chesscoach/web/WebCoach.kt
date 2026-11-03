package org.chesscoach.web

import com.github.bhlangonijr.chesslib.Board
import org.chesscoach.coach.CandidateResult
import org.chesscoach.coach.CoachEngine
import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.analysis.AnalyzedGame
import org.chesscoach.coach.analysis.scoreChange
import org.chesscoach.coach.analysis.RecapInput
import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.coach.model.BoardMarks
import org.chesscoach.coach.model.Variation
import org.chesscoach.coach.model.Variations
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.BoardMoves
import org.chesscoach.core.EngineLine
import org.chesscoach.core.GameEnd
import org.chesscoach.core.GameAnalyzer
import org.chesscoach.core.GameReplay
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.PlyEvaluation
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
    private const val PART = "~"
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

    /** [scores] вида "cp:15;mate:-3", на одну больше, чем полуходов. Ответ: class|loss|whiteCp|whiteMate|missedMate. */
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
            listOf(ply.classification.name, ply.centipawnLoss, cp, mate, ply.missedForcedMate).joinToString(FIELD)
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

    /**
     * Разбор тренера и стрелки вида "e2e4:BEST_MOVE;...". Пустой [playedUci]: хода из позиции не было.
     * [viewer] — "white" или "black", цвет читателя: от него зависят заголовки планов. Пусто — планы по цвету.
     * [scoreLines] — "false", когда оценки до и после хода страница показывает сама в заголовке хода.
     * [previousFen] и [previousUci] — прошлый ход соперника, пусто — неизвестен.
     * Стрелки идут вместе с продолжением лучшей линии, вида "e2e4:LINE_MINE:3":
     * прятать пунктир или нет, решает страница.
     */
    @JvmStatic
    @JSExport
    fun explain(
        fen: String,
        playedUci: String?,
        opening: String?,
        viewer: String?,
        scoreLines: String?,
        previousFen: String?,
        previousUci: String?,
        bridge: EngineBridge,
        done: DoneCallback,
    ) {
        launch(done) {
            val explanation = CoachEngine(BridgeClient(bridge)).explain(
                fen,
                playedUci?.takeIf { it.isNotEmpty() },
                opening?.takeIf { it.isNotEmpty() },
                previousFen?.takeIf { it.isNotEmpty() },
                previousUci?.takeIf { it.isNotEmpty() },
            )
            val arrows = BoardMarks.withContinuation(BoardMarks.of(explanation), explanation)
                .joinToString(ROW) { it.from + it.to + ":" + it.style.name + (it.label?.let { label -> ":$label" } ?: "") }
            val side = when (viewer) {
                "white" -> Side.WHITE
                "black" -> Side.BLACK
                else -> null
            }
            TextRenderer.render(explanation, side, scoreLines != "false") to
                (arrows + PART + encodeVariations(Variations.of(explanation)))
        }
    }

    /** Законные ходы для доски с нажатиями: uci|san|fenAfter через ";". */
    @JvmStatic
    @JSExport
    fun legalMoves(fen: String): String =
        BoardMoves.legal(fen).joinToString(ROW) { it.uci + FIELD + it.san + FIELD + it.fenAfter }

    /** Вариант движка записью SAN через пробел. */
    @JvmStatic
    @JSExport
    fun sanLine(fen: String, ucis: String): String =
        MoveNotation.uciToSanOrRaw(fen, ucis.split(" ").filter { it.isNotEmpty() }).joinToString(" ")

    /** "Мат. Победили белые." или "Пат. Ничья." Пусто — партия на доске не кончилась. */
    @JvmStatic
    @JSExport
    fun gameEnd(fen: String): String = GameEnd.of(fen).orEmpty()

    /**
     * Итоги партии. Поля через [PART]: цвет, ходы, начальная позиция, оценки в
     * формате [classify], итог (WIN, LOSS, DRAW), как кончилась партия, часы через
     * запятую, начальное время. Ответ через [PART]: вердикт, свои числа, числа
     * соперника (точность,зевки,ошибки,неточности) и моменты ply|kind|title|detail|swing через [ROW].
     */
    @JvmStatic
    @JSExport
    fun recap(payload: String): String {
        val f = payload.split(PART)
        val positions = GameReplay.replay(f[1].split(" ").filter { it.isNotEmpty() }, f[2].takeIf { it.isNotEmpty() })
        val evaluations = f[3].split(ROW).filter { it.isNotEmpty() }.mapIndexed { index, row ->
            val e = row.split(FIELD)
            val score = if (e[3].isNotEmpty()) Score.Mate(e[3].toInt()) else Score.Cp(e[2].toIntOrNull() ?: 0)
            PlyEvaluation(index, MoveClassification.valueOf(e[0]), e[1].toIntOrNull() ?: 0, e.getOrNull(4) == "true", score)
        }
        val recap = CoachEngine(NoEngine).recap(
            RecapInput(
                iAmWhite = f[0] == "white",
                positions = positions.take(evaluations.size),
                evaluations = evaluations.take(positions.size),
                outcome = f[4],
                status = f[5],
                clocksCs = f[6].split(",").mapNotNull { it.toIntOrNull() },
                initialSeconds = f[7].toIntOrNull(),
            ),
        )
        val stats = { s: org.chesscoach.coach.analysis.SideStats ->
            listOf(s.accuracy, s.blunders, s.mistakes, s.inaccuracies).joinToString(",")
        }
        return listOf(
            recap.verdict,
            stats(recap.me),
            stats(recap.opponent),
            recap.moments.joinToString(ROW) { listOf(it.ply, it.kind.name, it.title, it.detail, it.swing.toInt()).joinToString(FIELD) },
        ).joinToString(PART)
    }

    /** Разбор хода, который человек предложил сам. Вторая строка ответа: его вариант. */
    @JvmStatic
    @JSExport
    fun tryCandidate(fen: String, input: String, bridge: EngineBridge, done: DoneCallback) {
        launch(done) {
            when (val result = CoachEngine(BridgeClient(bridge)).tryCandidate(fen, input)) {
                is CandidateResult.Explained -> result.text to encodeVariations(listOfNotNull(result.variation))
                CandidateResult.NotLegal -> "Такого хода в этой позиции нет." to ""
                CandidateResult.Ambiguous -> "Подходит несколько ходов, уточните запись." to ""
                CandidateResult.NoVerdict -> "Движку нечего сказать об этом ходе." to ""
            }
        }
    }

    /**
     * Сводка ошибок по партиям. [games] разделены [PART], в каждой через [FIELD]:
     * цвет, ходы, начальная позиция, классификации через запятую, оценки после
     * каждого хода через запятую ("35" или "m-3"), id партии, соперник.
     * Партия без разбора не передаётся.
     *
     * Ответ через [PART]: числа (партии, ходы, зевки, ошибки, неточности, дебют,
     * миттельшпиль, эндшпиль через запятую), главный вид ошибки, виды
     * kind|title|label|advice|moves|games через [ROW] и примеры
     * kind|gameId|opponent|ply|move|оценки через [ROW].
     */
    @JvmStatic
    @JSExport
    fun errorReport(games: String, done: DoneCallback) {
        launch(done) {
            val parsed = games.split(PART).filter { it.isNotEmpty() }.map { row ->
                val f = row.split(FIELD)
                val classes = f[3].split(",").withIndex()
                    .associate { (index, name) -> index to MoveClassification.valueOf(name) }
                val scores = f.getOrNull(4).orEmpty().split(",").withIndex().mapNotNull { (index, value) ->
                    val score = when {
                        value.startsWith("m") -> value.drop(1).toIntOrNull()?.let { Score.Mate(it) }
                        else -> value.toIntOrNull()?.let { Score.Cp(it) }
                    }
                    score?.let { index to it }
                }.toMap()
                AnalyzedGame(
                    iAmWhite = f[0] == "white",
                    positions = GameReplay.replay(f[1].split(" ").filter { it.isNotEmpty() }, f[2].takeIf { it.isNotEmpty() }),
                    classifications = classes,
                    scoresAfterWhite = scores,
                    gameId = f.getOrNull(5).orEmpty(),
                    opponent = f.getOrNull(6).orEmpty(),
                )
            }
            val report = CoachEngine(NoEngine).errorReport(parsed)
            val phases = listOf(
                org.chesscoach.coach.model.GamePhase.OPENING,
                org.chesscoach.coach.model.GamePhase.MIDDLEGAME,
                org.chesscoach.coach.model.GamePhase.ENDGAME,
            ).map { report.byPhase[it] ?: 0 }
            listOf(
                (listOf(report.games, report.myMoves, report.blunders, report.mistakes, report.inaccuracies) + phases).joinToString(","),
                report.main?.kind?.name.orEmpty(),
                report.byKind.joinToString(ROW) {
                    listOf(it.kind.name, it.kind.title, it.kind.label, it.kind.advice, it.moves, it.games).joinToString(FIELD)
                },
                report.examples.values.flatten().joinToString(ROW) {
                    listOf(it.kind.name, it.gameId, it.opponent, it.ply, it.move, it.scoreChange()).joinToString(FIELD)
                },
            ).joinToString(PART) to ""
        }
    }

    /** Сводке движок не нужен: всё уже посчитано при разборе партий. */
    private object NoEngine : EngineClient {
        override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> = emptyList()
    }

    /** Варианты строкой: title|fen,fen|san san;... */
    private fun encodeVariations(variations: List<Variation>): String = variations.joinToString(ROW) {
        it.title + FIELD + it.fens.joinToString(",") + FIELD + it.san.joinToString(" ")
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
