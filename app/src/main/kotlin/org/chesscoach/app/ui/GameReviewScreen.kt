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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Switch
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

/** Строка движка над доской: оценка от лица белых и начало варианта. */
private data class EngineView(val scoreFromWhite: Score, val sanLine: List<String>, val firstUci: String)

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
                    )
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

    val context = LocalContext.current
    var showContinuation by remember { mutableStateOf(continuationEnabled(context)) }
    val arrows = remember(explanation, engineLines, lineActive, showContinuation) {
        when {
            lineActive -> engineLines.firstOrNull()?.let { listOf(BoardArrow(it.firstUci.take(2), it.firstUci.substring(2, 4), ArrowStyle.BEST_MOVE)) }.orEmpty()
            else -> explanation?.let { e ->
                BoardMarks.of(e).let { if (showContinuation) BoardMarks.withContinuation(it, e) else it }
            }.orEmpty()
        }
    }
    // Продолжение лучшей линии по шагам: номер на стрелке — номер шага здесь.
    val continuation = remember(explanation) { explanation?.let(::continuationSteps).orEmpty() }
    val coachText = remember(explanation) { explanation?.let { TextRenderer.render(it, viewer, scoreLines = false) } }
    val coachVariations = remember(explanation) { explanation?.let(Variations::of).orEmpty() }

    Column(
        modifier = Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Итоги") }
            Text("против " + game.opponent.name, style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
        }

        EngineLinesHeader(engineLines, GameEnd.of(displayedFen))

        // Доска разворачивается так, как её видел игрок за своими фигурами.
        val flipped = game.myColor == PlayerColor.BLACK
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val boardSize = maxWidth - EVAL_BAR_WIDTH - EVAL_BAR_GAP
            Row(
                modifier = Modifier.fillMaxWidth().height(boardSize),
                horizontalArrangement = Arrangement.spacedBy(EVAL_BAR_GAP),
            ) {
                val lastMove = when {
                    coachVariationFen != null -> null
                    lineActive && lineCursor >= 0 -> line[lineCursor].uci
                    lineActive -> null
                    else -> positions.getOrNull(currentPly)?.uci
                }
                ChessBoard(
                    fen = displayedFen,
                    modifier = Modifier.width(boardSize),
                    flipped = flipped,
                    lastMoveFrom = lastMove?.take(2),
                    lastMoveTo = lastMove?.substring(2, 4),
                    arrows = if (coachVariationFen == null) arrows else emptyList(),
                    selectedSquare = selectedSquare,
                    targetSquares = legalMoves.filter { it.from == selectedSquare }.map { it.to }.toSet(),
                    onSquareTap = if (coachVariationFen == null) ::onSquareTap else null,
                )
                EvalBar(
                    score = when {
                        lineActive -> engineLines.firstOrNull()?.scoreFromWhite
                        else -> evaluationsByPly[currentPly]?.scoreAfterWhite ?: engineLines.firstOrNull()?.scoreFromWhite
                    },
                    flipped = flipped,
                    modifier = Modifier.width(EVAL_BAR_WIDTH).fillMaxHeight(),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Свой вариант сразу под доской: внизу длинной страницы его не находили.
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
                lineExplanation?.let { GroupCard { CoachText(it) } }
            } else {
                Text(
                    "Свой вариант: нажмите на фигуру, потом на поле. Ходить можно за обе стороны.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.secondaryText,
                )
                ContinuationRow(
                    steps = continuation,
                    enabled = showContinuation,
                    shownFen = coachVariationFen,
                    onToggle = {
                        showContinuation = it
                        saveContinuation(context, it)
                    },
                    onShow = { coachVariationFen = it },
                )
                ArrowLegend(arrows)
            }

            ScanProgress(scan)

            MoveStrip(
                positions = positions,
                currentPly = currentPly,
                state = movesState,
                evaluationsByPly = evaluationsByPly,
                myColor = game.myColor,
                onSelect = { currentPly = it },
            )

            EvalGraphBar(
                evaluations = scan.evaluations,
                totalPlies = positions.size,
                currentPly = currentPly,
                moments = moments,
                onSelect = { currentPly = it },
            )

            MoveNavigation(
                currentPly = currentPly,
                lastPly = positions.lastIndex,
                moments = moments,
                onGo = { currentPly = it },
            )

            AdviceCard(
                ply = positions.getOrNull(currentPly),
                before = evaluationsByPly[currentPly - 1]?.scoreAfterWhite,
                evaluation = evaluationsByPly[currentPly],
                moment = moments.firstOrNull { it.ply == currentPly },
                coachText = coachText,
                thinking = thinking,
                error = engineError,
            )

            VariationStrips(
                variations = coachVariations,
                shownFen = coachVariationFen,
                onShow = { coachVariationFen = it },
            )
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

private const val PREFS_REVIEW = "review"
private const val KEY_CONTINUATION = "continuation"

private fun continuationEnabled(context: android.content.Context): Boolean =
    context.getSharedPreferences(PREFS_REVIEW, android.content.Context.MODE_PRIVATE).getBoolean(KEY_CONTINUATION, true)

private fun saveContinuation(context: android.content.Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_REVIEW, android.content.Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_CONTINUATION, enabled).apply()
}

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
 * нажатие на номер ставит на доску позицию этого шага.
 */
@Composable
private fun ContinuationRow(
    steps: List<ContinuationStep>,
    enabled: Boolean,
    shownFen: String?,
    onToggle: (Boolean) -> Unit,
    onShow: (String?) -> Unit,
) {
    if (steps.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Продолжение", style = MaterialTheme.typography.labelMedium)
        Switch(checked = enabled, onCheckedChange = onToggle)
        if (enabled) {
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

/** Две лучшие линии движка для позиции на доске, как над доской в анализе Lichess. */
@Composable
private fun EngineLinesHeader(lines: List<EngineView>, gameEnd: String?) {
    Column(Modifier.fillMaxWidth().height(44.dp), verticalArrangement = Arrangement.Center) {
        if (gameEnd != null) {
            Text(gameEnd, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        lines.forEach { view ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ScoreFormat.short(view.scoreFromWhite),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3A3C)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Text(
                    view.sanLine.joinToString(" ") { MoveNotation.figurine(it) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(
            onClick = { previousMoment?.let { onGo(it.ply) } },
            enabled = previousMoment != null,
            modifier = Modifier.weight(1f),
        ) { Text("« Момент", maxLines = 1) }
        Button(onClick = { onGo(currentPly - 1) }, enabled = currentPly >= 0, modifier = Modifier.weight(1f)) { Text("‹") }
        Button(onClick = { onGo(currentPly + 1) }, enabled = currentPly < lastPly, modifier = Modifier.weight(1f)) { Text("›") }
        FilledTonalButton(
            onClick = { nextMoment?.let { onGo(it.ply) } },
            enabled = nextMoment != null,
            modifier = Modifier.weight(1f),
        ) { Text("Момент »", maxLines = 1) }
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
                variation.san.forEachIndexed { index, san ->
                    val fen = variation.fens.getOrNull(index)
                    Text(
                        MoveNotation.figurine(san),
                        fontWeight = if (fen == shownFen) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (fen == shownFen) Palette.selected else Palette.card)
                            .clickable { onShow(fen) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (shownFen != null) TextButton(onClick = { onShow(null) }) { Text("Вернуться к партии") }
    }
}

/** Текст тренера: можно выделить и скопировать, справочный блок свёрнут. */
@Composable
private fun CoachText(text: String) {
    val marker = System.lineSeparator() + TextRenderer.REFERENCE_TITLE + System.lineSeparator()
    val main = text.substringBefore(marker).trimEnd()
    val reference = text.substringAfter(marker, "")
    var referenceOpen by remember(text) { mutableStateOf(false) }

    // Заголовки групп "━━ ДО ХОДА ━━" выделены: по ним глаз находит, где что.
    val styled = remember(main) {
        buildAnnotatedString {
            main.lines().forEachIndexed { index, line ->
                if (index > 0) append(System.lineSeparator())
                if (line.startsWith(TextRenderer.GROUP_MARK)) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Palette.accent)) {
                        append(line.removePrefix(TextRenderer.GROUP_MARK).removeSuffix(TextRenderer.GROUP_MARK).trim())
                    }
                } else {
                    append(line)
                }
            }
        }
    }
    SelectionContainer {
        Text(styled, style = MaterialTheme.typography.bodyMedium)
    }
    if (reference.isNotEmpty()) {
        TextButton(onClick = { referenceOpen = !referenceOpen }) {
            Text(if (referenceOpen) "Скрыть термины" else "Показать термины")
        }
        if (referenceOpen) {
            SelectionContainer {
                Text(reference, style = MaterialTheme.typography.bodySmall)
            }
        }
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
@Composable
private fun ArrowLegend(arrows: List<BoardArrow>) {
    val stylesPresent = remember(arrows) {
        LEGEND_ORDER.filter { style -> arrows.any { it.style == style } }
    }
    if (stylesPresent.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
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
 * Карточка выбранного хода. Заголовок — тот же ход с оценками до и после из
 * разбора партии, поэтому числа совпадают с лентой и графиком; ниже текст тренера.
 */
@Composable
private fun AdviceCard(
    ply: PlyPosition?,
    before: Score?,
    evaluation: PlyEvaluation?,
    moment: KeyMoment?,
    coachText: String?,
    thinking: Boolean,
    error: String?,
) {
    GroupCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ply == null) {
                Text("Начальная позиция", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text("Выберите ход в ленте или нажмите ›, чтобы тренер его разобрал.", style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
                return@Column
            }
            Text(
                MoveHeadline.of(ply, before, evaluation?.scoreAfterWhite, evaluation?.classification),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            moment?.let { Text(it.kind.label.replaceFirstChar(Char::uppercase) + ": " + it.detail, color = momentColor(it.kind), style = MaterialTheme.typography.bodySmall) }
            GameEnd.of(ply.fenAfter)?.let { Text(it, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }

            when {
                error != null -> Text("Тренер не смог разобрать ход: $error", style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                coachText != null -> CoachText(coachText)
                thinking -> Text("Тренер разбирает ход…", style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
                else -> Text("Тренер разбирает ход…", style = MaterialTheme.typography.bodySmall, color = Palette.secondaryText)
            }
        }
    }
}
