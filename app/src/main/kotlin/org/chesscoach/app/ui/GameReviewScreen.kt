package org.chesscoach.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.chesscoach.coach.model.Section
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.delay
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.ScanState
import org.chesscoach.coach.analysis.KeyMoment
import org.chesscoach.coach.model.ArrowStyle
import org.chesscoach.coach.model.BoardArrow
import org.chesscoach.coach.model.BoardMarks
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.model.Variation
import org.chesscoach.coach.model.Variations
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.BoardMoves
import org.chesscoach.core.GameEnd
import org.chesscoach.core.GameSummary
import org.chesscoach.core.LegalMove
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveHeadline
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.PlayerColor
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.START_FEN
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.core.catchingNonCancellation
import org.chesscoach.core.mapCatchingNonCancellation
import org.chesscoach.engine.SearchLimits

/** Строка движка над доской: оценка от лица белых, начало варианта и позиция после каждого хода. */
private data class EngineView(
    val scoreFromWhite: Score,
    val sanLine: List<String>,
    val firstUci: String,
    val startFen: String,
    val fens: List<String>,
)

/** Короткий расчёт каждой позиции варианта: оценка под ходом, как в ленте партии. */
private val STEP_LIMITS = SearchLimits(movetimeMs = 120, multiPv = 1)

/** Ход своего варианта: человек сыграл его на доске, а движок оценил позицию после. */
private data class LineMove(
    val uci: String,
    val san: String,
    val fenBefore: String,
    val fenAfter: String,
    val scoreFromWhite: Score? = null,
)

/**
 * Пауза перед обращением к движку.
 *
 * Пока пользователь щёлкает по ходам, считать каждую промежуточную позицию
 * бессмысленно: разбор всё равно не успеет показаться, а движок будет занят.
 */
private const val SETTLE_MS = 350L

/**
 * Разбор партии по ходам.
 *
 * Выбранный ход и есть разбираемый: доска показывает позицию после него, под
 * ходом и на графике стоит оценка после него, а карточка объясняет именно его.
 * На доске можно сыграть свой вариант за обе стороны, как в анализе на Lichess.
 */
@Composable
fun GameReviewScreen(
    container: AppContainer,
    game: GameSummary,
    initialPly: Int,
    onBack: () -> Unit,
) {
    val scan = rememberGameScan(container, game)
    val positions = scan.positions
    val evaluationsByPly = remember(scan.evaluations) { scan.evaluations.associateBy { it.ply } }
    val moments = scan.recap?.moments.orEmpty()
    val viewer = if (game.myColor == PlayerColor.WHITE) Side.WHITE else Side.BLACK

    // -1 — начальная позиция, до первого хода.
    var currentPly by remember(game.id) { mutableStateOf(initialPly.coerceIn(-1, positions.lastIndex)) }
    var explanation by remember(game.id) { mutableStateOf<PositionExplanation?>(null) }
    var thinking by remember(game.id) { mutableStateOf(false) }
    var engineError by remember(game.id) { mutableStateOf<String?>(null) }
    var engineLines by remember(game.id) { mutableStateOf<List<EngineView>>(emptyList()) }
    // Оценка позиции по её FEN: у позиции одно число, где бы она ни показывалась.
    val stepScores = remember(game.id) { mutableStateMapOf<String, Score>() }

    // Свой вариант: ходы с позиции партии, сыгранные на доске. Курсор — показанный ход, -1 — исходная позиция.
    var line by remember(game.id) { mutableStateOf<List<LineMove>>(emptyList()) }
    var lineCursor by remember(game.id) { mutableStateOf(-1) }
    var lineExplanation by remember(game.id) { mutableStateOf<String?>(null) }
    var explainRequest by remember(game.id) { mutableStateOf<LineMove?>(null) }
    var selectedSquare by remember(game.id) { mutableStateOf<String?>(null) }
    var promotionChoices by remember(game.id) { mutableStateOf<List<LegalMove>>(emptyList()) }

    // Позиция варианта тренера, показанная на доске вместо партии. Null — доска показывает партию.
    var coachVariationFen by remember(game.id) { mutableStateOf<String?>(null) }

    val gameFen = currentFen(positions, currentPly)
    val lineActive = line.isNotEmpty()
    val displayedFen = coachVariationFen ?: if (lineActive && lineCursor >= 0) line[lineCursor].fenAfter else gameFen
    val legalMoves = remember(displayedFen) { BoardMoves.legal(displayedFen) }
    val movesState = rememberLazyListState()

    // Смена хода партии сбрасывает свой вариант: он отвечал на другую позицию.
    LaunchedEffect(currentPly) {
        line = emptyList()
        lineCursor = -1
        lineExplanation = null
        selectedSquare = null
        coachVariationFen = null
        explanation = null
        engineError = null
        if (currentPly >= 0) movesState.animateScrollToItem(currentPly)
        val ply = positions.getOrNull(currentPly) ?: return@LaunchedEffect

        delay(SETTLE_MS)
        thinking = true
        // Карточка разбирает сам выбранный ход: из позиции до него, со сравнением с лучшим.
        // Прошлый ход соперника: разбор начинается с того, зачем он так сыграл.
        val previous = positions.getOrNull(currentPly - 1)
        explanation = catchingNonCancellation {
            container.coach.explain(ply.fenBefore, ply.uci, game.openingName, previous?.fenBefore, previous?.uci)
        }.onFailure { engineError = it.message ?: "Движок недоступен" }.getOrNull()
        // Оценки тренера — главные: позиция после хода и после лучшего хода
        // показываются с теми же числами, что в тексте разбора.
        explanation?.let { e ->
            e.section<Section.AfterMove>()?.scoreFromWhite?.let { stepScores[ply.fenAfter] = it }
            Variations.of(e).forEach { variation ->
                val first = variation.fens.firstOrNull() ?: return@forEach
                variation.firstScoreFromWhite?.let { stepScores[first] = it }
            }
        }
        thinking = false
    }

    // Две строки движка над доской — для той позиции, что сейчас на доске.
    LaunchedEffect(displayedFen) {
        engineLines = emptyList()
        if (GameEnd.of(displayedFen) != null) return@LaunchedEffect
        delay(SETTLE_MS)
        container.engine.analyzer()
            .mapCatchingNonCancellation { it.analyze(displayedFen, SearchLimits(movetimeMs = 600, multiPv = 2)) }
            .onSuccess { result ->
                val side = MoveNotation.sideToMove(displayedFen)
                engineLines = result.lines.sortedBy { it.multiPvIndex }.take(2).mapNotNull { engineLine ->
                    val first = engineLine.pv.firstOrNull() ?: return@mapNotNull null
                    EngineView(
                        ScoreFormat.toWhitePerspective(engineLine.score, side),
                        MoveNotation.uciToSanOrRaw(displayedFen, engineLine.pv.take(6)),
                        first,
                        displayedFen,
                        MoveNotation.fensAfter(displayedFen, engineLine.pv.take(6)),
                    )
                }
                // Оценка позиции у всех одна: если тренер её уже назвал, не перебиваем.
                engineLines.firstOrNull()?.let { if (displayedFen !in stepScores) stepScores[displayedFen] = it.scoreFromWhite }
                // Лучший ход и сама позиция — одно число.
                engineLines.firstOrNull()?.fens?.firstOrNull()?.let { first ->
                    if (first !in stepScores) stepScores[displayedFen]?.let { stepScores[first] = it }
                }
                // Оценка хода варианта — это оценка позиции после него.
                val score = engineLines.firstOrNull()?.scoreFromWhite
                if (score != null && lineActive && lineCursor >= 0 && line.getOrNull(lineCursor)?.fenAfter == displayedFen) {
                    line = line.toMutableList().also { it[lineCursor] = it[lineCursor].copy(scoreFromWhite = score) }
                }
            }
    }

    LaunchedEffect(explainRequest) {
        val move = explainRequest ?: return@LaunchedEffect
        lineExplanation = "Тренер разбирает ход " + move.san + "…"
        lineExplanation = catchingNonCancellation {
            TextRenderer.render(container.coach.explain(move.fenBefore, move.uci, game.openingName), viewer, scoreLines = true)
        }.getOrElse { "Не удалось разобрать ход: " + (it.message ?: "движок недоступен") }
        explainRequest = null
    }

    fun playMove(move: LegalMove) {
        selectedSquare = null
        val nextGameMove = positions.getOrNull(currentPly + 1)
        if (!lineActive && nextGameMove?.uci == move.uci) {
            // Сыгран ход партии: просто идём по партии дальше.
            currentPly++
            return
        }
        val kept = if (lineActive) line.take(lineCursor + 1) else emptyList()
        line = kept + LineMove(move.uci, move.san, displayedFen, move.fenAfter)
        lineCursor = line.lastIndex
        lineExplanation = null
    }

    fun onSquareTap(square: String) {
        val selected = selectedSquare
        if (selected != null) {
            val candidates = legalMoves.filter { it.from == selected && it.to == square }
            when {
                candidates.size == 1 -> return playMove(candidates.first())
                candidates.size > 1 -> {
                    promotionChoices = candidates
                    return
                }
            }
        }
        selectedSquare = square.takeIf { tapped -> legalMoves.any { it.from == tapped } && tapped != selected }
    }

    // Продолжение лучшей линии по шагам: номер на стрелке — номер шага здесь.
    // Пунктир рисуется всегда, когда у линии есть ходы после первого.
    val continuation = remember(explanation) { explanation?.let(::continuationSteps).orEmpty() }
    val arrows = remember(explanation, engineLines, lineActive, continuation) {
        when {
            lineActive -> engineLines.firstOrNull()?.let { listOf(BoardArrow(it.firstUci.take(2), it.firstUci.substring(2, 4), ArrowStyle.BEST_MOVE)) }.orEmpty()
            else -> explanation?.let { e ->
                BoardMarks.of(e).let { if (continuation.size > 1) BoardMarks.withContinuation(it, e) else it }
            }.orEmpty()
        }
    }
    val coachText = remember(explanation) { explanation?.let { TextRenderer.render(it, viewer, scoreLines = false) } }
    val coachVariations = remember(explanation) { explanation?.let(Variations::of).orEmpty() }
    val playedBest = explanation?.section<Section.PlayedMove>()?.matchesBest == true

    // Одна линия на позицию: на позиции после сыгранного хода первая строка —
    // вариант тренера, вторая — другой ход движка.
    val coachLine = remember(explanation, displayedFen) {
        val played = explanation?.section<Section.PlayedMove>()
        val ply = positions.getOrNull(currentPly)
        if (played == null || ply == null || ply.fenAfter != displayedFen || played.lineUci.size < 2) {
            null
        } else {
            val rest = played.lineUci.drop(1)
            val fens = MoveNotation.fensAfter(displayedFen, rest)
            val score = explanation?.section<Section.AfterMove>()?.scoreFromWhite ?: played.scoreAfterFromWhite
            if (fens.isEmpty() || score == null) {
                null
            } else {
                EngineView(score, MoveNotation.uciToSanOrRaw(displayedFen, rest.take(fens.size)), rest.first(), displayedFen, fens)
            }
        }
    }
    // Лучший ответ и сама позиция — одно число: оценка тренера главнее короткого расчёта.
    LaunchedEffect(coachLine) { coachLine?.let { stepScores[it.fens.first()] = it.scoreFromWhite } }
    val headerLines = remember(coachLine, engineLines) {
        if (coachLine == null) engineLines else listOf(coachLine) + engineLines.filter { it.firstUci != coachLine.firstUci }.take(1)
    }

    // Оценки под ходами вариантов: каждую позицию коротко считает движок,
    // уже посчитанные не трогаем. Пока думает тренер, движок его.
    val stepFens = remember(headerLines, coachVariations) {
        (headerLines.flatMap { it.fens } + coachVariations.flatMap { it.fens }).distinct()
    }
    LaunchedEffect(stepFens, thinking) {
        if (thinking || stepFens.isEmpty()) return@LaunchedEffect
        delay(SETTLE_MS)
        val analyzer = container.engine.analyzer().getOrNull() ?: return@LaunchedEffect
        for (fen in stepFens) {
            if (fen in stepScores || GameEnd.of(fen) != null) continue
            val result = catchingNonCancellation { analyzer.analyze(fen, STEP_LIMITS) }.getOrNull() ?: continue
            val top = result.lines.minByOrNull { it.multiPvIndex } ?: continue
            stepScores[fen] = ScoreFormat.toWhitePerspective(top.score, MoveNotation.sideToMove(fen))
        }
    }

    // Доска разворачивается так, как её видел игрок за своими фигурами.
    val flipped = game.myColor == PlayerColor.BLACK

    val header: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Итоги") }
            Text("против " + game.opponent.name, style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
        }
    }

    val board: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { boardSize ->
        Row(
            modifier = Modifier.height(boardSize).width(boardSize + EVAL_BAR_WIDTH + EVAL_BAR_GAP),
            horizontalArrangement = Arrangement.spacedBy(EVAL_BAR_GAP),
        ) {
            // Подсветка всегда: ход, после которого позиция на доске, и ход перед ним —
            // в партии, в своём варианте и на шагах вариантов тренера.
            val gameMove = positions.getOrNull(currentPly)?.uci
            val gamePrevious = positions.getOrNull(currentPly - 1)?.uci
            val variationShown = coachVariationFen?.let { shown ->
                coachVariations.firstNotNullOfOrNull { v -> v.fens.indexOf(shown).takeIf { it >= 0 }?.let { v to it } }
            }
            val (lastMove, previousMove) = when {
                coachVariationFen != null -> variationShown?.let { (v, i) ->
                    v.uci.getOrNull(i) to (if (i > 0) v.uci.getOrNull(i - 1) else gamePrevious)
                } ?: (null to null)
                lineActive && lineCursor >= 0 -> line[lineCursor].uci to (if (lineCursor > 0) line[lineCursor - 1].uci else gameMove)
                else -> gameMove to gamePrevious
            }
            ChessBoard(
                fen = displayedFen,
                modifier = Modifier.width(boardSize),
                flipped = flipped,
                lastMoveFrom = lastMove?.take(2),
                lastMoveTo = lastMove?.substring(2, 4),
                previousMoveFrom = previousMove?.take(2),
                previousMoveTo = previousMove?.substring(2, 4),
                arrows = if (coachVariationFen == null) arrows else emptyList(),
                selectedSquare = selectedSquare,
                targetSquares = legalMoves.filter { it.from == selectedSquare }.map { it.to }.toSet(),
                onSquareTap = if (coachVariationFen == null) ::onSquareTap else null,
            )
            EvalBar(
                score = when {
                    lineActive -> engineLines.firstOrNull()?.scoreFromWhite
                    else -> stepScores[displayedFen] ?: evaluationsByPly[currentPly]?.scoreAfterWhite ?: engineLines.firstOrNull()?.scoreFromWhite
                },
                flipped = flipped,
                modifier = Modifier.width(EVAL_BAR_WIDTH).fillMaxHeight(),
            )
        }
    }

    // Свой вариант сразу под доской: внизу длинной страницы его не находили.
    val underBoard: @Composable () -> Unit = {
        if (lineActive) {
            UserLineStrip(
                line = line,
                cursor = lineCursor,
                onSelect = { lineCursor = it; lineExplanation = null },
                onExplain = { explainRequest = line.getOrNull(lineCursor) },
                onBackToGame = {
                    line = emptyList()
                    lineCursor = -1
                    lineExplanation = null
                },
            )
            lineExplanation?.let { CoachText(it) }
        } else {
            Text(
                "Свой вариант: нажмите на фигуру, потом на поле. Ходить можно за обе стороны.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.secondaryText,
            )
            val betterExisted = !playedBest && explanation?.playedMoveSan != null
            ContinuationRow(
                steps = continuation,
                label = if (betterExisted) "Лучше было:" else "Продолжение:",
                // Рядом с лучшим — сыгранный: нажатие возвращает доску к нему.
                played = positions.getOrNull(currentPly)?.takeIf { betterExisted }?.let { ply ->
                    "Сыграно: " + MoveNotation.figurine(ply.san) + (stepScores[ply.fenAfter]?.let { " (" + ScoreFormat.short(it) + ")" } ?: "")
                },
                shownFen = coachVariationFen,
                onShow = { coachVariationFen = it },
            )
            ArrowLegend(arrows)
        }
    }

    val strip: @Composable () -> Unit = {
        MoveStrip(
            positions = positions,
            currentPly = currentPly,
            state = movesState,
            evaluationsByPly = evaluationsByPly,
            myColor = game.myColor,
            onSelect = { currentPly = it },
        )
    }
    val graph: @Composable () -> Unit = {
        EvalGraphBar(
            evaluations = scan.evaluations,
            totalPlies = positions.size,
            currentPly = currentPly,
            moments = moments,
            onSelect = { currentPly = it },
        )
    }
    val navigation: @Composable () -> Unit = {
        MoveNavigation(
            currentPly = currentPly,
            lastPly = positions.lastIndex,
            moments = moments,
            onGo = { currentPly = it },
        )
    }
    val coach: @Composable () -> Unit = {
        AdviceCard(
            ply = positions.getOrNull(currentPly),
            evaluation = evaluationsByPly[currentPly],
            moment = moments.firstOrNull { it.ply == currentPly },
            explanation = explanation,
            coachText = coachText,
            thinking = thinking,
            error = engineError,
        )
        VariationStrips(
            variations = coachVariations,
            scores = stepScores,
            shownFen = coachVariationFen,
            onShow = { coachVariationFen = it },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background)) {
        if (maxWidth > maxHeight) {
            // Горизонтально: доска слева во всю высоту, справа навигация закреплена,
            // а всё остальное прокручивается под ней.
            val boardSize = maxHeight - 12.dp
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                board(boardSize)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    header()
                    strip()
                    navigation()
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EngineLinesHeader(headerLines, stepScores, GameEnd.of(displayedFen))
                        underBoard()
                        ScanProgress(scan)
                        graph()
                        coach()
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                header()
                EngineLinesHeader(headerLines, stepScores, GameEnd.of(displayedFen))
                board(this@BoxWithConstraints.maxWidth - 24.dp - EVAL_BAR_WIDTH - EVAL_BAR_GAP)
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    underBoard()
                    ScanProgress(scan)
                    strip()
                    graph()
                    navigation()
                    coach()
                }
            }
        }
    }

    if (promotionChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { promotionChoices = emptyList() },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { promotionChoices = emptyList() }) { Text("Отмена") } },
            title = { Text("Во что превратить пешку?") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    promotionChoices.sortedBy { "qrbn".indexOf(it.promotion ?: 'q') }.forEach { move ->
                        OutlinedButton(onClick = {
                            promotionChoices = emptyList()
                            playMove(move)
                        }) { Text(promotionName(move.promotion)) }
                    }
                }
            },
        )
    }
}

/** Шаг продолжения лучшей линии: номер как на стрелке, ход и позиция после него. */
private data class ContinuationStep(val number: Int, val san: String, val fenAfter: String)

/** Лучшая линия из позиции до хода, по шагам: 1 — лучший ход, 2 — ответ, 3 — свой следующий, 4 — ответ. */
private fun continuationSteps(explanation: PositionExplanation): List<ContinuationStep> {
    val best = explanation.section<Section.BestMoves>() ?: return emptyList()
    var fen = explanation.fen
    return best.computerLineUci.take(4).mapIndexedNotNull { index, uci ->
        val move = BoardMoves.legal(fen).firstOrNull { it.uci == uci } ?: return@mapIndexedNotNull null
        fen = move.fenAfter
        ContinuationStep(index + 1, move.san, move.fenAfter)
    }
}

/**
 * Продолжение лучшей линии: пунктир на доске и номера здесь. Доска показывает
 * позицию после сыгранного хода, а линия идёт из позиции до него, поэтому
 * нажатие на номер ставит на доску позицию этого шага. Один ход — не
 * продолжение: его и так показывает зелёная стрелка.
 */
@Composable
private fun ContinuationRow(
    steps: List<ContinuationStep>,
    label: String,
    played: String?,
    shownFen: String?,
    onShow: (String?) -> Unit,
) {
    if (steps.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (played != null) {
            // Подсвечена, пока на доске сыгранная позиция.
            val selected = shownFen == null
            Text(
                played,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Palette.selected else Palette.card)
                    .clickable { onShow(null) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
        run {
            steps.forEach { step ->
                val selected = step.fenAfter == shownFen
                Text(
                    step.number.toString() + " " + MoveNotation.figurine(step.san),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Palette.selected else Palette.card)
                        .clickable { onShow(if (selected) null else step.fenAfter) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private fun promotionName(letter: Char?): String = when (letter) {
    'r' -> "Ладья"
    'b' -> "Слон"
    'n' -> "Конь"
    else -> "Ферзь"
}

/**
 * Две лучшие линии движка для позиции на доске, как над доской в анализе Lichess:
 * чей ответ, ходы с номерами и оценка после каждого хода. Оценка первой линии —
 * оценка самой позиции, та же, что на полосе.
 */
@Composable
private fun EngineLinesHeader(lines: List<EngineView>, scores: Map<String, Score>, gameEnd: String?) {
    Column(Modifier.fillMaxWidth().height(58.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        if (gameEnd != null) {
            Text(gameEnd, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        lines.firstOrNull()?.let { first ->
            Text(
                "Ответ " + (if (MoveNotation.isWhiteToMove(first.startFen)) "белых" else "чёрных") + ", две лучшие линии:",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.secondaryText,
            )
        }
        lines.forEachIndexed { index, view ->
            // Оценка линии — оценка после её первого хода, она же стоит и под ним.
            val lineScore = (if (index == 0) scores[view.startFen] else null)
                ?: view.fens.firstOrNull()?.let(scores::get) ?: view.scoreFromWhite
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ScoreFormat.short(lineScore),
                    color = Color.White,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3A3C)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Text(
                    MoveNotation.numbered(view.startFen, view.sanLine).mapIndexed { step, move ->
                        MoveNotation.figurine(move) + (view.fens.getOrNull(step)?.let(scores::get)?.let { " (" + ScoreFormat.short(it) + ")" } ?: "")
                    }.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun ScanProgress(scan: GameScan) {
    val running = scan.state as? ScanState.Running
    when {
        running != null && !scan.final -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Разбор партии: ${running.done} из ${running.total}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { if (running.total > 0) running.done.toFloat() / running.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        scan.state is ScanState.Failed && !scan.final -> Column {
            Text((scan.state as ScanState.Failed).message, style = MaterialTheme.typography.bodySmall, color = Palette.danger)
            TextButton(onClick = scan.start) { Text("Разобрать ещё раз") }
        }

        else -> Unit
    }
}

/** Назад и вперёд по ходам и прыжки к ключевым моментам. */
@Composable
private fun MoveNavigation(currentPly: Int, lastPly: Int, moments: List<KeyMoment>, onGo: (Int) -> Unit) {
    val previousMoment = moments.lastOrNull { it.ply < currentPly }
    val nextMoment = moments.firstOrNull { it.ply > currentPly }
    // Узкие поля кнопок: со стандартными "« Момент" на телефоне обрезался до "« Мо".
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            onClick = { previousMoment?.let { onGo(it.ply) } },
            enabled = previousMoment != null,
            modifier = Modifier.weight(1.3f),
            contentPadding = padding,
        ) { Text("« Момент", maxLines = 1, softWrap = false) }
        Button(onClick = { onGo(currentPly - 1) }, enabled = currentPly >= 0, modifier = Modifier.weight(1f), contentPadding = padding) { Text("‹") }
        Button(onClick = { onGo(currentPly + 1) }, enabled = currentPly < lastPly, modifier = Modifier.weight(1f), contentPadding = padding) { Text("›") }
        FilledTonalButton(
            onClick = { nextMoment?.let { onGo(it.ply) } },
            enabled = nextMoment != null,
            modifier = Modifier.weight(1.3f),
            contentPadding = padding,
        ) { Text("Момент »", maxLines = 1, softWrap = false) }
    }
}

/** Свой вариант второй строкой под ходами партии, как ответвление на Lichess. */
@Composable
private fun UserLineStrip(
    line: List<LineMove>,
    cursor: Int,
    onSelect: (Int) -> Unit,
    onExplain: () -> Unit,
    onBackToGame: () -> Unit,
) {
    GroupCard {
        Text("Свой вариант", style = MaterialTheme.typography.labelLarge, color = Palette.secondaryText)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            line.forEachIndexed { index, move ->
                val selected = index == cursor
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Palette.selected else Palette.background)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        MoveNotation.figurine(BoardMoves.moveLabel(move.fenBefore, move.san).let { label ->
                            if (index == 0 || MoveNotation.isWhiteToMove(move.fenBefore)) label else move.san
                        }),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(move.scoreFromWhite?.let(ScoreFormat::short) ?: "…", style = MaterialTheme.typography.labelSmall, color = Palette.secondaryText)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExplain, enabled = cursor >= 0) { Text("Объяснить ход") }
            TextButton(onClick = onBackToGame) { Text("К партии") }
        }
    }
}

/**
 * Варианты разбора: нажатие на ход показывает позицию после него на доске.
 * Прокручивается вбок, а не переносится: ход должен оставаться на своём месте.
 */
@Composable
private fun VariationStrips(
    variations: List<Variation>,
    scores: Map<String, Score>,
    shownFen: String?,
    onShow: (String?) -> Unit,
) {
    if (variations.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        variations.forEach { variation ->
            Text(variation.title, style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MoveNotation.numbered(variation.startFen, variation.san).forEachIndexed { index, san ->
                    val fen = variation.fens.getOrNull(index)
                    // Оценка после хода, как в ленте партии; пока движок считает — многоточие.
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (fen == shownFen) Palette.selected else Palette.card)
                            .clickable { onShow(fen) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(MoveNotation.figurine(san), fontWeight = if (fen == shownFen) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            fen?.let(scores::get)?.let(ScoreFormat::short) ?: if (fen != null && GameEnd.of(fen) != null) "#" else "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.secondaryText,
                        )
                    }
                }
            }
        }
        if (shownFen != null) TextButton(onClick = { onShow(null) }) { Text("Вернуться к партии") }
    }
}

private fun currentFen(positions: List<PlyPosition>, currentPly: Int): String = when {
    positions.isEmpty() -> START_FEN
    currentPly < 0 -> positions.first().fenBefore
    else -> positions[currentPly].fenAfter
}

private val LEGEND_SWATCH_WIDTH = 22.dp
private val LEGEND_SWATCH_HEIGHT = 4.dp

/**
 * Порядок легенды фиксирован (не порядок появления в списке стрелок), иначе
 * пункты будут прыгать местами от хода к ходу без всякой причины.
 */
private val LEGEND_ORDER = listOf(
    ArrowStyle.BEST_MOVE,
    ArrowStyle.PLAYED_MOVE,
    ArrowStyle.THREAT,
    ArrowStyle.CONSEQUENCE,
    ArrowStyle.LINE_MINE,
    ArrowStyle.LINE_OPPONENT,
)

private fun legendLabel(style: ArrowStyle): String = when (style) {
    ArrowStyle.BEST_MOVE -> "Лучший ход"
    ArrowStyle.PLAYED_MOVE -> "Сыгранный ход"
    ArrowStyle.THREAT -> "Угроза соперника"
    ArrowStyle.CONSEQUENCE -> "Следствие (пунктир)"
    ArrowStyle.LINE_MINE -> "Дальше: свой ход"
    ArrowStyle.LINE_OPPONENT, ArrowStyle.LINE_OPPONENT_FAR -> "Дальше: ответ соперника"
}

/**
 * Легенда цветов стрелок — без неё серая и зелёная стрелки ничего не говорят.
 * Показывает только те виды, что реально есть на доске сейчас.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ArrowLegend(arrows: List<BoardArrow>) {
    val stylesPresent = remember(arrows) {
        LEGEND_ORDER.filter { style -> arrows.any { it.style == style } }
    }
    if (stylesPresent.isEmpty()) return

    // Переносится на следующую строку, а не уезжает вбок: на телефоне конец обрезался.
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stylesPresent.forEach { style ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.width(LEGEND_SWATCH_WIDTH).height(LEGEND_SWATCH_HEIGHT)) {
                    val dashed = arrowLegendDashed(style)
                    drawLine(
                        color = arrowLegendColor(style),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                        pathEffect = if (dashed) {
                            PathEffect.dashPathEffect(floatArrayOf(size.width * 0.22f, size.width * 0.14f))
                        } else {
                            null
                        },
                    )
                }
                Text(legendLabel(style), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Оттенок фона хода в ленте по классификации. Лучший и хороший — без пометки. */
private fun classificationTint(classification: MoveClassification): Color? = when (classification) {
    MoveClassification.BLUNDER -> Color(0xFFFAD2CF)
    MoveClassification.MISTAKE -> Color(0xFFFDE3B0)
    MoveClassification.INACCURACY -> Color(0xFFFFF3B0)
    MoveClassification.GOOD, MoveClassification.BEST -> null
}

@Composable
private fun MoveStrip(
    positions: List<PlyPosition>,
    currentPly: Int,
    state: LazyListState,
    evaluationsByPly: Map<Int, PlyEvaluation>,
    myColor: PlayerColor,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(positions) { index, ply ->
            val selected = index == currentPly

            // Чужие зевки разбирать бессмысленно, и лента от них только рябит —
            // пометки только для ходов той стороны, за которую играл пользователь.
            val playedByUser = (ply.whiteMoved && myColor == PlayerColor.WHITE) ||
                (!ply.whiteMoved && myColor == PlayerColor.BLACK)
            val tint = if (playedByUser) {
                evaluationsByPly[ply.index]?.classification?.let(::classificationTint)
            } else {
                null
            }

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            selected -> Palette.selected
                            tint != null -> tint
                            else -> Color.Transparent
                        }
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    buildString {
                        if (ply.whiteMoved) append(ply.moveNumber.toString() + ". ")
                        append(MoveNotation.figurine(ply.san))
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Оценка после хода: плюс за белых, минус за чёрных.
                evaluationsByPly[ply.index]?.let { evaluation ->
                    Text(
                        ScoreFormat.short(evaluation.scoreAfterWhite),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.secondaryText,
                    )
                }
            }
        }
    }
}

private val EVAL_BAR_WIDTH = 22.dp
private val EVAL_BAR_GAP = 2.dp

/** Полоска оценки как на Lichess: белая часть со стороны белых, число на стороне ведущего. */
@Composable
private fun EvalBar(score: Score?, flipped: Boolean, modifier: Modifier = Modifier) {
    val share by animateFloatAsState(score?.let(ScoreFormat::whiteShare) ?: 0.5f, label = "evalBar")
    val whiteLeads = share >= 0.5f
    // Без разворота белые внизу.
    val labelOnTop = whiteLeads == flipped

    Box(modifier = modifier.background(Color(0xFF2A2A2A))) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(share)
                .align(if (flipped) Alignment.TopCenter else Alignment.BottomCenter)
                .background(Color.White),
        )
        if (score != null) {
            Text(
                ScoreFormat.short(score),
                color = if (whiteLeads) Color(0xFF2A2A2A) else Color.White,
                fontSize = 8.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(if (labelOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * Карточка выбранного хода. Оценки в заголовке — те же, что в тексте тренера:
 * у одной позиции одно число. Пока тренер считает, чисел нет, только ход и
 * ярлык из разбора партии. Каждое число подписано: до хода, после, потеря.
 */
@Composable
private fun AdviceCard(
    ply: PlyPosition?,
    evaluation: PlyEvaluation?,
    moment: KeyMoment?,
    explanation: PositionExplanation?,
    coachText: String?,
    thinking: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ply == null) {
                    Text("Начальная позиция", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text("Выберите ход в ленте или нажмите ›, чтобы тренер его разобрал.", style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Ключевой момент партии виден сразу: цветной восклицательный знак.
                    moment?.let {
                        Text(
                            "!",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(momentColor(it.kind)).padding(horizontal = 9.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        MoveHeadline.of(ply, null, null, explanation?.classification ?: evaluation?.classification, withSide = true),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                explanation?.let { ScoreTags(it) }
                // Числа момента — из быстрого разбора партии: здесь их заменяют оценки тренера выше.
                moment?.let {
                    val detail = it.detail.split(" · ").filterNot { part -> part.contains("→") }.joinToString(" · ")
                    Text(
                        it.kind.label.replaceFirstChar(Char::uppercase) + detail.takeIf(String::isNotEmpty)?.let { d -> ": $d" }.orEmpty(),
                        color = momentColor(it.kind),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                GameEnd.of(ply.fenAfter)?.let { Text(it, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }

                when {
                    error != null -> Text("Тренер не смог разобрать ход: $error", style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                    coachText == null || thinking -> Text("Тренер разбирает ход…", style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
                    else -> Unit
                }
            }
        }
        if (ply != null && error == null && coachText != null) CoachText(coachText)
    }
}

/** "до хода -3.9", "после хода +3.7", "потеря 7.6" — подписанные метки вместо голых чисел. */
@Composable
private fun ScoreTags(explanation: PositionExplanation) {
    val before = explanation.scoreFromWhite
    val after = explanation.section<Section.AfterMove>()?.scoreFromWhite
    val played = explanation.section<Section.PlayedMove>()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        before?.let { ScoreTag("до хода " + ScoreFormat.short(it), Palette.background, Palette.secondaryText) }
        after?.let { ScoreTag("после хода " + ScoreFormat.short(it), Palette.background, Palette.secondaryText) }
        if (played != null && !played.matchesBest && played.centipawnLoss >= 5) {
            // Упущенный мат пешками не меряют: так же, как в тексте тренера.
            ScoreTag(
                if (played.centipawnLoss >= 5000) "оценка рушится"
                else "потеря " + String.format(java.util.Locale.ROOT, "%.1f", played.centipawnLoss / 100.0),
                Color(0xFFFDE4E2),
                Color(0xFFB3261E),
            )
        }
    }
}

@Composable
private fun ScoreTag(text: String, background: Color, foreground: Color) {
    Text(
        text,
        fontSize = 13.sp,
        color = foreground,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
