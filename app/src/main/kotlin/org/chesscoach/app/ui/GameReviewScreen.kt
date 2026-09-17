package org.chesscoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.chesscoach.app.AppContainer
import org.chesscoach.app.analysis.GameAnalysisProgress
import org.chesscoach.app.analysis.GameAnalysisService
import org.chesscoach.app.analysis.GameAnalysisStore
import org.chesscoach.app.analysis.ScanState
import org.chesscoach.coach.CandidateResult
import org.chesscoach.coach.model.ArrowStyle
import org.chesscoach.coach.model.BoardArrow
import org.chesscoach.coach.model.BoardMarks
import org.chesscoach.coach.model.PositionExplanation
import org.chesscoach.coach.text.TextRenderer
import org.chesscoach.core.EvalGraph
import org.chesscoach.core.GameReplay
import org.chesscoach.core.GameSummary
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveNotation
import org.chesscoach.core.catchingNonCancellation
import org.chesscoach.core.mapCatchingNonCancellation
import org.chesscoach.core.PlayerColor
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.START_FEN
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.engine.SearchLimits

private data class PositionAdvice(
    val scoreFromWhite: Score,
    val bestMoveSan: String,
    val depth: Int,
)

/**
 * Пауза перед обращением к движку.
 *
 * Пока пользователь щёлкает по ходам, считать каждую промежуточную позицию
 * бессмысленно: разбор всё равно не успеет показаться, а движок будет занят.
 */
private const val SETTLE_MS = 350L

@Composable
fun GameReviewScreen(
    container: AppContainer,
    game: GameSummary,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val positions = remember(game.id) {
        GameReplay.replay(game.movesSan, game.initialFen)
    }

    // -1 — начальная позиция, до первого хода.
    var currentPly by remember(game.id) { mutableStateOf(-1) }
    var advice by remember(game.id) { mutableStateOf<PositionAdvice?>(null) }
    var explanation by remember(game.id) { mutableStateOf<PositionExplanation?>(null) }
    var thinking by remember(game.id) { mutableStateOf(false) }
    var engineError by remember(game.id) { mutableStateOf<String?>(null) }

    // Полный разбор партии на зевки/ошибки/неточности (M6) — отдельный от
    // разбора текущей позиции выше поток данных: один живёт весь сеанс показа
    // экрана, другой переживает и сворачивание приложения в фоновом сервисе.
    var evaluations by remember(game.id) { mutableStateOf<List<PlyEvaluation>?>(null) }
    val analysisProgress = remember(game.id) { GameAnalysisProgress.flowFor(game.id) }
    val scanState by analysisProgress.collectAsState(initial = null)

    LaunchedEffect(game.id) {
        // Кэш — первым делом: партия могла быть разобрана раньше, и гонять
        // движок по ней заново незачем.
        evaluations = GameAnalysisStore.read(context, game.id)
    }

    LaunchedEffect(scanState) {
        val done = scanState as? ScanState.Done ?: return@LaunchedEffect
        evaluations = done.evaluations
    }

    val evaluationsByPly = remember(evaluations) {
        evaluations?.associateBy { it.ply } ?: emptyMap()
    }

    // Свой вариант хода: человек набирает ход и спрашивает, чем он хуже (или
    // лучше) рекомендованного. Сбрасывается при смене позиции — вердикт про
    // ход из другой позиции хуже, чем пустое место.
    var candidateInput by remember(game.id) { mutableStateOf("") }
    var candidateVerdict by remember(game.id) { mutableStateOf<String?>(null) }
    var candidateThinking by remember(game.id) { mutableStateOf(false) }
    val candidateScope = rememberCoroutineScope()

    val fen = currentFen(positions, currentPly)
    val movesState = rememberLazyListState()

    // Ключ включает и номер полухода: одна и та же позиция может встретиться
    // дважды при повторении ходов, а сыгранный из неё ход будет разным.
    LaunchedEffect(fen, currentPly) {
        advice = null
        explanation = null
        engineError = null
        candidateVerdict = null
        candidateInput = ""
        if (positions.isEmpty()) return@LaunchedEffect

        delay(SETTLE_MS)
        thinking = true

        // Сначала короткий поиск: оценка и стрелка появляются сразу, а не
        // через две с половиной секунды полного разбора.
        container.engine.analyzer()
            .mapCatchingNonCancellation { it.analyze(fen, SearchLimits(movetimeMs = 600, multiPv = 1)) }
            .onSuccess { result ->
                val line = result.lines.firstOrNull()
                val uci = line?.pv?.firstOrNull()
                if (line != null && uci != null) {
                    advice = PositionAdvice(
                        scoreFromWhite = ScoreFormat.toWhitePerspective(
                            line.score,
                            MoveNotation.sideToMove(fen),
                        ),
                        bestMoveSan = MoveNotation.uciToSanOrRaw(fen, listOf(uci)).first(),
                        depth = line.depth,
                    )
                }
            }
            .onFailure { engineError = it.message ?: "Движок недоступен" }

        if (engineError == null) {
            // Ход, который был сыгран в партии из этой позиции: тренеру он
            // нужен, чтобы сказать, чем он хуже лучшего. Структура разбора
            // берётся один раз — стрелки и текст строятся из неё же, вместо
            // того чтобы гонять движок повторно ради каждого представления.
            val playedUci = positions.getOrNull(currentPly + 1)?.uci
            explanation = catchingNonCancellation { container.coach.explain(fen, playedUci, game.openingName) }
                .getOrNull()
        }

        thinking = false
    }

    val arrows = remember(explanation) { explanation?.let(BoardMarks::of) ?: emptyList() }
    val coachText = remember(explanation) { explanation?.let(TextRenderer::render) }

    LaunchedEffect(currentPly) {
        if (currentPly >= 0) movesState.animateScrollToItem(currentPly)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Фиксированная часть: доска не должна уезжать вместе с текстом
        // разбора, который бывает длинным, — иначе её не видно, пока читаешь.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("К списку") }
            Text(
                "против " + game.opponent.name,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ChessBoard(
            fen = fen,
            // Доска разворачивается так, как её видел игрок за своими фигурами.
            flipped = game.myColor == PlayerColor.BLACK,
            lastMoveFrom = positions.getOrNull(currentPly)?.fromSquare,
            lastMoveTo = positions.getOrNull(currentPly)?.toSquare,
            arrows = arrows,
        )

        // Прокручиваемая часть: всё, что ниже доски.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArrowLegend(arrows)

            AnalysisControls(
                scanState = scanState,
                analyzed = evaluations != null,
                onStart = { GameAnalysisService.start(context, game) },
            )

            MoveStrip(
                positions = positions,
                currentPly = currentPly,
                state = movesState,
                evaluationsByPly = evaluationsByPly,
                myColor = game.myColor,
                onSelect = { currentPly = it },
            )

            evaluations?.let { list ->
                EvalGraphBar(
                    evaluations = list,
                    currentPly = currentPly,
                    onSelect = { currentPly = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { if (currentPly >= 0) currentPly-- },
                    enabled = currentPly >= 0,
                    modifier = Modifier.weight(1f),
                ) { Text("Назад") }

                Button(
                    onClick = { if (currentPly < positions.lastIndex) currentPly++ },
                    enabled = currentPly < positions.lastIndex,
                    modifier = Modifier.weight(1f),
                ) { Text("Вперёд") }
            }

            AdviceCard(
                fen = fen,
                advice = advice,
                coachText = coachText,
                thinking = thinking,
                error = engineError,
            )

            CandidateMoveCard(
                input = candidateInput,
                verdict = candidateVerdict,
                thinking = candidateThinking,
                enabled = !thinking && engineError == null,
                onInputChange = { candidateInput = it },
                onSubmit = {
                    val typed = candidateInput
                    candidateScope.launch {
                        candidateThinking = true
                        candidateVerdict = catchingNonCancellation {
                            verdictText(container.coach.tryCandidate(fen, typed))
                        }.getOrElse { "Не удалось проверить ход: движок недоступен." }
                        candidateThinking = false
                    }
                },
            )
        }
    }
}

/** Вердикт тренера в текст. Каждый случай отвечает по делу, а не общим "не получилось". */
private fun verdictText(result: CandidateResult): String = when (result) {
    is CandidateResult.Explained -> result.text
    CandidateResult.NotLegal -> "Такого хода в этой позиции нет. Запись годится любая: Nf3, exd5, O-O или g1f3."
    CandidateResult.Ambiguous -> "Подходит больше одного хода. Уточните регистр: bxc4 — пешкой, Bxc4 — слоном."
    CandidateResult.NoVerdict -> "Ход законный, но движку нечего о нём сказать в этой позиции."
}

/**
 * Свой вариант хода.
 *
 * Вопрос "а что, если бы я сыграл так" — самый частый у человека, который
 * читает разбор и не согласен с рекомендацией. Отвечает на него тот же
 * конвейер, что разбирает сыгранный ход: сравнение с лучшим — ровно та же
 * задача.
 */
@Composable
private fun CandidateMoveCard(
    input: String,
    verdict: String?,
    thinking: Boolean,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Свой вариант", style = MaterialTheme.typography.labelLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Ход, например Nf3") },
                )
                Button(
                    onClick = onSubmit,
                    enabled = enabled && !thinking && input.isNotBlank(),
                ) { Text("Проверить") }
            }

            when {
                thinking -> Text(
                    "Тренер считает ваш вариант…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5F6368),
                )

                verdict != null -> Text(verdict, style = MaterialTheme.typography.bodyMedium)
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
)

private fun legendLabel(style: ArrowStyle): String = when (style) {
    ArrowStyle.BEST_MOVE -> "Лучший ход"
    ArrowStyle.PLAYED_MOVE -> "Сыгранный ход"
    ArrowStyle.THREAT -> "Угроза соперника"
    ArrowStyle.CONSEQUENCE -> "Следствие (пунктир)"
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

/**
 * Управление полным разбором партии: кнопка запуска, прогресс сервиса,
 * ошибка. Само чтение и запись кэша делает экран — этот блок только рисует
 * состояние, которое ему дали.
 */
@Composable
private fun AnalysisControls(
    scanState: ScanState?,
    analyzed: Boolean,
    onStart: () -> Unit,
) {
    when {
        scanState is ScanState.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Разбор партии: ${scanState.done} из ${scanState.total}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = {
                    if (scanState.total > 0) scanState.done.toFloat() / scanState.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        analyzed -> Text(
            "Партия разобрана",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF5F6368),
        )

        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (scanState as? ScanState.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC5221F))
            }
            TextButton(onClick = onStart) { Text("Разобрать партию") }
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
        modifier = Modifier.fillMaxWidth().height(44.dp),
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

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            selected -> Color(0xFFD7E3FC)
                            tint != null -> tint
                            else -> Color.Transparent
                        }
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    buildString {
                        if (ply.whiteMoved) append(ply.moveNumber.toString() + ". ")
                        append(ply.san)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val EVAL_GRAPH_HEIGHT = 28.dp

/**
 * График оценки партии от лица белых — узкая полоса под лентой ходов.
 *
 * Один Canvas и один столбик на полуход: выше середины — лучше белым, ниже —
 * чёрным. Тап переводит на соответствующий полуход, поэтому график служит
 * ещё и второй, более быстрой лентой ходов.
 */
@Composable
private fun EvalGraphBar(
    evaluations: List<PlyEvaluation>,
    currentPly: Int,
    onSelect: (Int) -> Unit,
) {
    if (evaluations.isEmpty()) return

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(EVAL_GRAPH_HEIGHT)
            .pointerInput(evaluations.size) {
                detectTapGestures { offset ->
                    val ply = (offset.x / size.width * evaluations.size)
                        .toInt()
                        .coerceIn(0, evaluations.lastIndex)
                    onSelect(ply)
                }
            },
    ) {
        val barWidth = size.width / evaluations.size
        val half = size.height / 2f

        evaluations.forEachIndexed { index, evaluation ->
            val fraction = EvalGraph.fraction(evaluation.scoreAfterWhite).toFloat()
            val barHeight = half * fraction
            val top = if (barHeight >= 0f) half - barHeight else half

            drawRect(
                color = if (index == currentPly) Color(0xFF1A73E8) else Color(0xFFBDC1C6),
                topLeft = Offset(index * barWidth, top),
                size = Size(barWidth, abs(barHeight).coerceAtLeast(1f)),
            )
        }

        drawLine(
            color = Color(0xFF9AA0A6),
            start = Offset(0f, half),
            end = Offset(size.width, half),
            strokeWidth = 1f,
        )
    }
}

@Composable
private fun AdviceCard(
    fen: String,
    advice: PositionAdvice?,
    coachText: String?,
    thinking: Boolean,
    error: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) Color(0xFFFCE8E6) else Color(0xFFF1F3F4),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val whiteToMove = MoveNotation.isWhiteToMove(fen)
            Text(
                "Ход " + MoveNotation.fullMoveNumber(fen) + ", " +
                    if (whiteToMove) "белые" else "чёрные",
                style = MaterialTheme.typography.labelLarge,
            )

            when {
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall)

                advice != null -> {
                    Text(
                        "Оценка " + ScoreFormat.display(advice.scoreFromWhite),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Лучший ход: " + advice.bestMoveSan,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    when {
                        coachText != null -> Text(
                            coachText,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        thinking -> Text(
                            "Тренер разбирает позицию…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                        )

                        else -> Text(
                            "Для этой позиции тренеру нечего добавить.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                        )
                    }
                }

                thinking -> Text("Движок считает…", style = MaterialTheme.typography.bodyMedium)

                else -> Text("Нет данных для этой позиции.")
            }
        }
    }
}
