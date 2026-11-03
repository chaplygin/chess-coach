package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Rank
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.GamePhase
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveHeadline
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.core.WinChance

/** Партия с уже посчитанными оценками: движок для отчёта заново не запускается. */
data class AnalyzedGame(
    val iAmWhite: Boolean,
    val positions: List<PlyPosition>,
    /** Классификация по индексу полухода. */
    val classifications: Map<Int, MoveClassification>,
    /** Оценка после полухода с индексом, от лица белых: по ней считается цена ошибки. */
    val scoresAfterWhite: Map<Int, Score> = emptyMap(),
    val gameId: String = "",
    val opponent: String = "",
)

enum class ErrorKind(val label: String, val title: String, val advice: String) {
    MATE(
        "пропускаете мат или удар по последней горизонтали", "Мат и последняя горизонталь",
        "перед ходом проверяйте шахи соперника и отход короля",
    ),
    FORK("попадаете под вилку", "Вилки", "перед ходом проверяйте, куда может прыгнуть конь и куда встать ферзь соперника"),
    HANGING("отдаёте материал", "Материал без защиты", "перед ходом проверяйте, что останется без защиты"),
    THREAT(
        "не замечаете угрозу соперника", "Незамеченные угрозы",
        "перед своим ходом спросите: что хотел соперник последним ходом и что он бьёт",
    ),
    DISCOVERED("пропускаете вскрытое нападение", "Вскрытые нападения", "смотрите, какие линии откроются, если фигура соперника уйдёт"),
    LINE_TACTIC("попадаете под связку или рентген", "Связки и рентгены", "проверяйте, что стоит за вашей фигурой на линии"),
    OPENING(
        "нарушаете дебютные принципы", "Дебютные принципы",
        "в дебюте сначала развивайте коней и слонов и рокируйтесь, не ходите дважды одной фигурой",
    ),
    OTHER(
        "ошибаетесь в расчёте варианта", "Просчёт варианта",
        "такие ходы проигрывают через 2-3 хода: разбирайте их по варианту движка",
    ),
}

data class KindCount(val kind: ErrorKind, val moves: Int, val games: Int)

/** Пример ошибки: на него можно нажать и попасть в разбор этой партии на этом ходу. */
data class ErrorExample(
    val kind: ErrorKind,
    val gameId: String,
    val opponent: String,
    val ply: Int,
    /** "7. Bxg5" или "12... Qd7". */
    val move: String,
    val scoreBeforeWhite: Score?,
    val scoreAfterWhite: Score?,
    /** Сколько процентов шанса на победу потеряно этим ходом. */
    val cost: Double,
    val classification: MoveClassification,
)

/** Повторяющиеся ошибки по всем разобранным партиям. Считает только ходы самого игрока. */
data class ErrorReport(
    val games: Int,
    val myMoves: Int,
    val blunders: Int,
    val mistakes: Int,
    val inaccuracies: Int,
    val byPhase: Map<GamePhase, Int>,
    val byKind: List<KindCount>,
    /** Самые дорогие ошибки по видам, дорогие первыми. */
    val examples: Map<ErrorKind, List<ErrorExample>> = emptyMap(),
) {
    /** Главное: самый частый вид ошибки с понятной причиной. "Просчёт" главным не бывает: совета из него не выйдет. */
    val main: KindCount? get() = byKind.firstOrNull { it.kind != ErrorKind.OTHER } ?: byKind.firstOrNull()

    fun render(): String {
        if (games == 0) return "Разобранных партий пока нет. Откройте партию: разбор запустится сам."
        return buildList {
            main?.let {
                add("ГЛАВНОЕ")
                add("Вы чаще всего " + it.kind.label + ": ходов " + it.moves + ", партий " + it.games + ".")
                add("Совет: " + it.kind.advice + ".")
                examples[it.kind]?.firstOrNull()?.let { example ->
                    add("Самый дорогой пример: " + example.move + " против " + example.opponent + ".")
                }
                add("")
            }
            add("Разобрано партий: $games, ваших ходов: $myMoves.")
            add("Зевков: $blunders, ошибок: $mistakes, неточностей: $inaccuracies.")
            if (byPhase.isNotEmpty()) {
                add(
                    "Зевки и ошибки по стадиям: дебют " + (byPhase[GamePhase.OPENING] ?: 0) +
                        ", миттельшпиль " + (byPhase[GamePhase.MIDDLEGAME] ?: 0) +
                        ", эндшпиль " + (byPhase[GamePhase.ENDGAME] ?: 0) + ".",
                )
            }
            if (byKind.isEmpty()) {
                add("Зевков и ошибок не нашлось.")
            } else {
                add("")
                add("ЧТО ПОВТОРЯЕТСЯ")
                byKind.forEach { add(it.kind.title + ": ходов " + it.moves + ", партий " + it.games + ".") }
            }
        }.joinToString(System.lineSeparator())
    }
}

/**
 * Сводка по партиям. Причина ошибки — те же детекторы, что и в разборе хода:
 * что появилось у соперника после этого хода, что висело и до него, что
 * соперник забрал следующим ходом.
 */
class ErrorReportBuilder(private val featureSource: FeatureSource) {

    suspend fun build(games: List<AnalyzedGame>): ErrorReport {
        var myMoves = 0
        var blunders = 0
        var mistakes = 0
        var inaccuracies = 0
        val byPhase = HashMap<GamePhase, Int>()
        val moves = HashMap<ErrorKind, Int>()
        val inGames = HashMap<ErrorKind, Int>()
        val examples = HashMap<ErrorKind, MutableList<ErrorExample>>()

        for (game in games) {
            val seen = HashSet<ErrorKind>()
            for (position in game.positions) {
                if (position.whiteMoved != game.iAmWhite) continue
                myMoves++
                val classification = game.classifications[position.index]
                when (classification) {
                    MoveClassification.INACCURACY -> inaccuracies++
                    MoveClassification.MISTAKE -> mistakes++
                    MoveClassification.BLUNDER -> blunders++
                    else -> Unit
                }
                if (classification != MoveClassification.MISTAKE && classification != MoveClassification.BLUNDER) continue

                val before = featureSource.features(position.fenBefore)
                byPhase[before.phase] = (byPhase[before.phase] ?: 0) + 1
                val next = game.positions.getOrNull(position.index + 1)
                val kind = kindOf(game, position, before.phase, next)
                moves[kind] = (moves[kind] ?: 0) + 1
                seen += kind

                val scoreBefore = if (position.index == 0) Score.Cp(0) else game.scoresAfterWhite[position.index - 1]
                val scoreAfter = game.scoresAfterWhite[position.index]
                val cost = if (scoreBefore != null && scoreAfter != null) {
                    WinChance.forSide(scoreBefore, game.iAmWhite) - WinChance.forSide(scoreAfter, game.iAmWhite)
                } else {
                    0.0
                }
                examples.getOrPut(kind) { ArrayList() } += ErrorExample(
                    kind = kind,
                    gameId = game.gameId,
                    opponent = game.opponent,
                    ply = position.index,
                    move = MoveHeadline.moveLabel(position),
                    scoreBeforeWhite = scoreBefore,
                    scoreAfterWhite = scoreAfter,
                    cost = cost,
                    classification = classification,
                )
            }
            seen.forEach { inGames[it] = (inGames[it] ?: 0) + 1 }
        }

        return ErrorReport(
            games = games.size,
            myMoves = myMoves,
            blunders = blunders,
            mistakes = mistakes,
            inaccuracies = inaccuracies,
            byPhase = byPhase,
            byKind = moves.map { (kind, count) -> KindCount(kind, count, inGames[kind] ?: 0) }
                .sortedWith(compareByDescending<KindCount> { it.moves }.thenBy { it.kind.ordinal }),
            examples = examples.mapValues { (_, list) -> list.sortedByDescending { it.cost }.take(MAX_EXAMPLES) },
        )
    }

    /**
     * Самая тяжёлая причина. Сначала то, что ход создал сам (новые удары
     * соперника), потом то, что висело и до хода и осталось, потом что соперник
     * действительно забрал, и только потом дебют и просчёт.
     */
    private fun kindOf(game: AnalyzedGame, position: PlyPosition, phase: GamePhase, next: PlyPosition?): ErrorKind {
        val opponent = if (game.iAmWhite) Side.BLACK else Side.WHITE
        val dangerBefore = featureSource.features(position.fenBefore).favouring(opponent).filter(::isDanger)
        val known = dangerBefore.map(::signature).toSet()
        val after = featureSource.features(position.fenAfter).favouring(opponent)
        val gains = after.filterNot { signature(it) in known }
        tacticKind(gains)?.let { return it }

        // Опасность была и до хода, и ход её не снял: фигура висела и висит.
        val stillThere = after.filter { isDanger(it) && signature(it) in known }
        if (stillThere.isNotEmpty()) return ErrorKind.THREAT

        // Детекторы молчат, а соперник следующим же ходом что-то забрал.
        if (next != null && next.san.contains('x')) return ErrorKind.HANGING

        if (phase == GamePhase.OPENING && !isOpeningMove(position)) return ErrorKind.OPENING
        return ErrorKind.OTHER
    }

    private fun tacticKind(gains: List<Fact>): ErrorKind? = when {
        gains.any { it is MateFact || it is BackRankFact } -> ErrorKind.MATE
        gains.any { it is ForkFact } -> ErrorKind.FORK
        gains.any { it is HangingPieceFact || it is TrappedPieceFact } -> ErrorKind.HANGING
        gains.any { it is DiscoveredAttackFact } -> ErrorKind.DISCOVERED
        gains.any { it is PinFact || it is SkewerFact } -> ErrorKind.LINE_TACTIC
        else -> null
    }

    private fun isDanger(fact: Fact): Boolean =
        fact is HangingPieceFact || fact is ForkFact || fact is MateFact || fact is BackRankFact || fact is TrappedPieceFact

    /** Ход по дебютным правилам: развитие лёгкой фигуры, рокировка, взятие или центральная пешка. */
    private fun isOpeningMove(position: PlyPosition): Boolean {
        if (position.san.startsWith("O-O") || position.san.contains('x')) return true
        val board = Board()
        if (!runCatching { board.loadFromFen(position.fenBefore) }.isSuccess) return true
        val from = runCatching { Square.valueOf(position.fromSquare.uppercase()) }.getOrNull() ?: return true
        val type = board.getPiece(from).pieceType ?: return true
        val home = if (position.whiteMoved) Rank.RANK_1 else Rank.RANK_8
        return when (type) {
            PieceType.KNIGHT, PieceType.BISHOP -> from.rank == home
            PieceType.PAWN -> position.toSquare.first() in "de"
            else -> false
        }
    }

    private companion object {
        /** Сколько примеров храним на вид: три видны сразу, остальные по кнопке "ещё". */
        const val MAX_EXAMPLES = 10
    }
}

/** Оценка до и после хода ошибки строкой "+0.2 → -4.5". */
fun ErrorExample.scoreChange(): String =
    listOfNotNull(scoreBeforeWhite, scoreAfterWhite).joinToString(" → ") { ScoreFormat.short(it) }
