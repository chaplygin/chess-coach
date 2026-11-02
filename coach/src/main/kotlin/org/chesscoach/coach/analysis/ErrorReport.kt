package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
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
import org.chesscoach.core.PlyPosition

/** Партия с уже посчитанными оценками: движок для отчёта заново не запускается. */
data class AnalyzedGame(
    val iAmWhite: Boolean,
    val positions: List<PlyPosition>,
    /** Классификация по индексу полухода. */
    val classifications: Map<Int, MoveClassification>,
)

enum class ErrorKind(val label: String, val advice: String) {
    FORK("попадаете под вилку", "перед ходом проверяйте, куда может прыгнуть конь и куда встать ферзь соперника"),
    HANGING("отдаёте материал", "перед ходом проверяйте, что останется без защиты"),
    LINE_TACTIC("попадаете под связку или рентген", "проверяйте, что стоит за вашей фигурой на линии"),
    DISCOVERED("пропускаете вскрытое нападение", "смотрите, какие линии откроются, если фигура соперника уйдёт"),
    MATE("пропускаете мат или удар по последней горизонтали", "перед ходом проверяйте шахи соперника и отход короля"),
    OTHER("детекторы причину не назвали", "разбирайте такие ходы по варианту движка"),
}

data class KindCount(val kind: ErrorKind, val moves: Int, val games: Int)

/** Повторяющиеся ошибки по всем разобранным партиям. Считает только ходы самого игрока. */
data class ErrorReport(
    val games: Int,
    val myMoves: Int,
    val blunders: Int,
    val mistakes: Int,
    val inaccuracies: Int,
    val byPhase: Map<GamePhase, Int>,
    val byKind: List<KindCount>,
) {
    fun render(): String {
        if (games == 0) return "Разобранных партий пока нет. Откройте партию: разбор запустится сам."
        return buildList {
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
                byKind.forEach { add(it.kind.label + ": ходов " + it.moves + ", партий " + it.games + ".") }
                val top = byKind.firstOrNull { it.kind != ErrorKind.OTHER }
                if (top != null) {
                    add("")
                    add("Главное: вы чаще всего " + top.kind.label + ". Совет: " + top.kind.advice + ".")
                }
            }
        }.joinToString(System.lineSeparator())
    }
}

/**
 * Сводка по партиям. Причина ошибки — те же детекторы, что и в разборе хода:
 * что появилось у соперника именно после этого хода.
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

        for (game in games) {
            val seen = HashSet<ErrorKind>()
            for (position in game.positions) {
                if (position.whiteMoved != game.iAmWhite) continue
                myMoves++
                when (game.classifications[position.index]) {
                    MoveClassification.INACCURACY -> inaccuracies++
                    MoveClassification.MISTAKE -> mistakes++
                    MoveClassification.BLUNDER -> blunders++
                    else -> Unit
                }
                val serious = game.classifications[position.index]
                if (serious != MoveClassification.MISTAKE && serious != MoveClassification.BLUNDER) continue

                val before = featureSource.features(position.fenBefore)
                byPhase[before.phase] = (byPhase[before.phase] ?: 0) + 1
                val opponent = if (game.iAmWhite) Side.BLACK else Side.WHITE
                val known = before.favouring(opponent).map(::signature).toSet()
                val gains = featureSource.features(position.fenAfter).favouring(opponent)
                    .filterNot { signature(it) in known }
                val kind = kindOf(gains)
                moves[kind] = (moves[kind] ?: 0) + 1
                seen += kind
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
        )
    }

    /** Самая тяжёлая причина: порядок тот же, что у вреда в разборе хода. */
    private fun kindOf(gains: List<Fact>): ErrorKind = when {
        gains.any { it is MateFact || it is BackRankFact } -> ErrorKind.MATE
        gains.any { it is ForkFact } -> ErrorKind.FORK
        gains.any { it is HangingPieceFact || it is TrappedPieceFact } -> ErrorKind.HANGING
        gains.any { it is DiscoveredAttackFact } -> ErrorKind.DISCOVERED
        gains.any { it is PinFact || it is SkewerFact } -> ErrorKind.LINE_TACTIC
        else -> ErrorKind.OTHER
    }
}
