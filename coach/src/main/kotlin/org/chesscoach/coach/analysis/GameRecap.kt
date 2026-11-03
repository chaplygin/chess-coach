package org.chesscoach.coach.analysis

import com.github.bhlangonijr.chesslib.Side
import org.chesscoach.coach.model.BackRankFact
import org.chesscoach.coach.model.DiscoveredAttackFact
import org.chesscoach.coach.model.Fact
import org.chesscoach.coach.model.FeatureSource
import org.chesscoach.coach.model.ForkFact
import org.chesscoach.coach.model.HangingPieceFact
import org.chesscoach.coach.model.MateFact
import org.chesscoach.coach.model.PinFact
import org.chesscoach.coach.model.SkewerFact
import org.chesscoach.coach.model.TrappedPieceFact
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.MoveHeadline
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.Score
import org.chesscoach.core.ScoreFormat
import org.chesscoach.core.WinChance
import kotlin.math.exp
import kotlin.math.roundToInt

/** Чем ход стал ключевым. Порядок не важен: моменты сортируются по силе сдвига. */
enum class MomentKind(val label: String) {
    MY_ERROR("ваша ошибка"),
    OPPONENT_ERROR("ошибка соперника"),
    MISSED_CHANCE("упущенная возможность"),
    MY_STRIKE("ваш удар"),
    OPPONENT_STRIKE("удар соперника"),
    LOST_ADVANTAGE("упущенный перевес"),
    TIME_TROUBLE("ошибка в цейтноте"),
}

/** Ключевой момент: полуход, что с ним случилось и одна строка пояснения. */
data class KeyMoment(
    val ply: Int,
    val kind: MomentKind,
    val title: String,
    val detail: String,
    /** Насколько ход сдвинул шанс на победу, в процентах. По нему моменты и отбираются. */
    val swing: Double,
)

data class SideStats(val accuracy: Int, val blunders: Int, val mistakes: Int, val inaccuracies: Int)

/** Итоги одной партии: вердикт, точность сторон и до пяти главных моментов. */
data class GameRecap(
    val verdict: String,
    val me: SideStats,
    val opponent: SideStats,
    val moments: List<KeyMoment>,
)

/** Партия с готовыми оценками. Часы — остаток в сотых секунды после каждого полухода, если Lichess их отдал. */
data class RecapInput(
    val iAmWhite: Boolean,
    val positions: List<PlyPosition>,
    val evaluations: List<PlyEvaluation>,
    /** WIN, LOSS или DRAW глазами владельца аккаунта. */
    val outcome: String,
    /** Как кончилась партия по данным Lichess: mate, resign, outoftime, draw, stalemate. */
    val status: String = "",
    val clocksCs: List<Int> = emptyList(),
    val initialSeconds: Int? = null,
)

/**
 * Собирает итоги партии из уже посчитанных оценок. Движок не нужен: переломы
 * видны по сдвигу шанса на победу, а причину ошибки называют те же детекторы,
 * что и в разборе хода.
 *
 * Шанс на победу, а не сантипешки: падение с -8 до -12 ничего не меняет, а с
 * +0.5 до -2.9 решает партию, хотя в пешках оно меньше.
 */
class GameRecapBuilder(private val featureSource: FeatureSource) {

    fun build(input: RecapInput): GameRecap {
        val plies = input.positions.zip(input.evaluations)
        val moments = mutableListOf<KeyMoment>()
        val myAccuracy = mutableListOf<Double>()
        val opponentAccuracy = mutableListOf<Double>()
        var lastStrikePly = -10

        for ((index, pair) in plies.withIndex()) {
            val (ply, evaluation) = pair
            val before = if (index == 0) START_SCORE else plies[index - 1].second.scoreAfterWhite
            val after = evaluation.scoreAfterWhite
            val mine = ply.whiteMoved == input.iAmWhite
            val winBefore = WinChance.forSide(before, ply.whiteMoved)
            val winAfter = WinChance.forSide(after, ply.whiteMoved)
            val drop = (winBefore - winAfter).coerceAtLeast(0.0)
            (if (mine) myAccuracy else opponentAccuracy) += moveAccuracy(drop)

            val serious = evaluation.classification == MoveClassification.MISTAKE ||
                evaluation.classification == MoveClassification.BLUNDER
            val scores = ScoreFormat.short(before) + " → " + ScoreFormat.short(after)
            if (serious) {
                val kind = errorKind(input, plies, index, mine, winBefore, winAfter)
                val reason = if (evaluation.missedForcedMate) "был форсированный мат" else reasonOf(ply, mine)
                val clock = clockNote(input, index, mine)
                moments += KeyMoment(
                    ply = ply.index,
                    kind = kind,
                    title = MoveHeadline.moveLabel(ply) + " — " + kind.label,
                    detail = listOfNotNull(scores, reason, clock).joinToString(" · "),
                    swing = drop,
                )
                continue
            }

            val gain = strikeGain(input.positions, index)
            if (gain != null && index - lastStrikePly > 2) {
                lastStrikePly = index
                val kind = if (mine) MomentKind.MY_STRIKE else MomentKind.OPPONENT_STRIKE
                // Удар в уже решённой позиции только доводит дело: вес по тому, насколько
                // исход был открыт до предыдущего хода соперника.
                val earlier = if (index >= 2) plies[index - 2].second.scoreAfterWhite else START_SCORE
                val openness = 1.0 - kotlin.math.abs(WinChance.white(earlier) - 50.0) / 50.0
                moments += KeyMoment(
                    ply = ply.index,
                    kind = kind,
                    title = MoveHeadline.moveLabel(ply) + " — " + kind.label,
                    detail = scores + " · выигрывает " + gainWord(gain),
                    swing = (gain.toDouble() / STRIKE_CP_PER_PERCENT).coerceAtMost(STRIKE_MAX_SWING) * openness,
                )
            }
        }

        val top = moments.sortedByDescending { it.swing }.take(MAX_MOMENTS).sortedBy { it.ply }
        return GameRecap(
            verdict = verdict(input, top),
            me = stats(myAccuracy, plies, input.iAmWhite),
            opponent = stats(opponentAccuracy, plies, !input.iAmWhite),
            moments = top,
        )
    }

    private fun errorKind(
        input: RecapInput,
        plies: List<Pair<PlyPosition, PlyEvaluation>>,
        index: Int,
        mine: Boolean,
        winBefore: Double,
        winAfter: Double,
    ): MomentKind {
        if (!mine) return MomentKind.OPPONENT_ERROR
        if (plies[index].second.missedForcedMate) return MomentKind.MISSED_CHANCE
        val previous = plies.getOrNull(index - 1)?.second?.classification
        if (previous == MoveClassification.MISTAKE || previous == MoveClassification.BLUNDER) return MomentKind.MISSED_CHANCE
        if (winBefore >= WINNING_PERCENT && winAfter < HOLDING_PERCENT) return MomentKind.LOST_ADVANTAGE
        if (lowClock(input, index)) return MomentKind.TIME_TROUBLE
        return MomentKind.MY_ERROR
    }

    /** Что получил соперник ходившего: тот же порядок тяжести, что в сводке ошибок. */
    private fun reasonOf(ply: PlyPosition, mine: Boolean): String? {
        val mover = if (ply.whiteMoved) Side.WHITE else Side.BLACK
        val opponent = mover.flip()
        val known = featureSource.features(ply.fenBefore).favouring(opponent).map(::signature).toSet()
        val gains = featureSource.features(ply.fenAfter).favouring(opponent).filterNot { signature(it) in known }
        val who = if (mine) "соперник получил " else "вы получили "
        return when {
            gains.any { it is MateFact || it is BackRankFact } -> who + "мат или удар по последней горизонтали"
            gains.any { it is ForkFact } -> who + "вилку"
            gains.any { it is HangingPieceFact || it is TrappedPieceFact } -> "материал остался под боем"
            gains.any { it is DiscoveredAttackFact } -> who + "вскрытое нападение"
            gains.any { it is PinFact || it is SkewerFact } -> who + "связку или рентген"
            else -> null
        }
    }

    /**
     * Удар: хороший ход, после которого к концу размена у ходившего стало больше
     * материала, чем было до хода соперника. Так взятие в ответ не считается
     * ударом: оно только возвращает отданное.
     */
    private fun strikeGain(positions: List<PlyPosition>, index: Int): Int? {
        val ply = positions[index]
        val sign = if (ply.whiteMoved) 1 else -1
        val baseline = listOfNotNull(
            positions.getOrNull(index - 1)?.fenBefore?.let(LineMaterial::balance),
            LineMaterial.balance(ply.fenBefore),
        ).maxOfOrNull { it * sign } ?: return null
        val end = positions[minOf(index + 2, positions.lastIndex)].fenAfter
        val gain = (LineMaterial.balance(end) ?: return null) * sign - baseline
        return gain.takeIf { it >= STRIKE_MIN_CP }
    }

    private fun gainWord(cp: Int): String = when {
        cp >= 850 -> "ферзя"
        cp >= 450 -> "ладью"
        cp >= 280 -> "фигуру"
        else -> "качество или две пешки"
    }

    private fun lowClock(input: RecapInput, index: Int): Boolean {
        val left = input.clocksCs.getOrNull(index) ?: return false
        val initial = input.initialSeconds ?: return false
        return left / 100 < maxOf(LOW_CLOCK_SECONDS, initial / 10)
    }

    private fun clockNote(input: RecapInput, index: Int, mine: Boolean): String? {
        if (!mine || !lowClock(input, index)) return null
        val seconds = (input.clocksCs[index] / 100)
        return "на часах оставалось " + seconds / 60 + ":" + (seconds % 60).toString().padStart(2, '0')
    }

    private fun stats(
        accuracy: List<Double>,
        plies: List<Pair<PlyPosition, PlyEvaluation>>,
        white: Boolean,
    ): SideStats {
        val own = plies.filter { it.first.whiteMoved == white }.map { it.second.classification }
        // Среднее с гармоническим, как у Lichess: один зевок на тридцать хороших ходов должен быть виден в числе.
        val harmonic = if (accuracy.isEmpty()) 0.0 else accuracy.size / accuracy.sumOf { 1.0 / it.coerceAtLeast(1.0) }
        return SideStats(
            accuracy = if (accuracy.isEmpty()) 0 else ((accuracy.average() + harmonic) / 2).roundToInt(),
            blunders = own.count { it == MoveClassification.BLUNDER },
            mistakes = own.count { it == MoveClassification.MISTAKE },
            inaccuracies = own.count { it == MoveClassification.INACCURACY },
        )
    }

    private fun verdict(input: RecapInput, moments: List<KeyMoment>): String {
        val result = when (input.outcome) {
            "WIN" -> "Победа"
            "LOSS" -> "Поражение"
            else -> "Ничья"
        } + statusNote(input)
        // Перелом — это ошибка, а удар обычно её следствие: удар главный, только если ошибок не было.
        val errors = moments.filter { it.kind != MomentKind.MY_STRIKE && it.kind != MomentKind.OPPONENT_STRIKE }
        val decisive = (errors.ifEmpty { moments }).maxByOrNull { it.swing }
            ?: return "$result. Крупных переломов не было: партия шла ровно."
        val turn = when (decisive.kind) {
            MomentKind.MY_ERROR, MomentKind.TIME_TROUBLE, MomentKind.LOST_ADVANTAGE, MomentKind.MISSED_CHANCE ->
                "Главный перелом — ваш ход "
            MomentKind.OPPONENT_ERROR -> "Главный перелом — ошибка соперника "
            MomentKind.MY_STRIKE -> "Главный перелом — ваш удар "
            MomentKind.OPPONENT_STRIKE -> "Главный перелом — удар соперника "
        }
        return "$result. " + turn + decisive.title.substringBefore(" — ") + ": " + decisive.detail + "."
    }

    private fun statusNote(input: RecapInput): String = when (input.status) {
        "mate" -> ", мат"
        "resign" -> if (input.outcome == "WIN") ", соперник сдался" else if (input.outcome == "LOSS") ", вы сдались" else ""
        "outoftime" -> ", по времени"
        "stalemate" -> ", пат"
        else -> ""
    }

    /** Точность хода по формуле Lichess: потеря шанса на победу в 5% даёт около 80%. */
    private fun moveAccuracy(drop: Double): Double =
        (103.1668 * exp(-0.04354 * drop) - 3.1669).coerceIn(0.0, 100.0)

    private companion object {
        /** Начальная позиция: у белых обычные две десятых пешки. */
        val START_SCORE: Score = Score.Cp(20)
        const val MAX_MOMENTS = 5
        const val WINNING_PERCENT = 75.0
        const val HOLDING_PERCENT = 60.0
        const val LOW_CLOCK_SECONDS = 30
        const val STRIKE_MIN_CP = 250
        const val STRIKE_CP_PER_PERCENT = 10
        const val STRIKE_MAX_SWING = 40.0
    }
}
