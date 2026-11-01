package org.chesscoach.core

import com.github.bhlangonijr.chesslib.Side

/**
 * Разбор одного полухода партии: насколько сыгранный ход хуже лучшего и что
 * это дало для оценки партии.
 */
data class PlyEvaluation(
    val ply: Int,
    val classification: MoveClassification,
    val centipawnLoss: Int,
    val missedForcedMate: Boolean,
    /** Оценка позиции после этого хода, от лица белых — то, что рисует график. */
    val scoreAfterWhite: Score,
)

/**
 * Считает разбор партии по уже готовым оценкам позиций.
 *
 * Сами оценки сюда не запрашиваются — получать их у движка дело вызывающей
 * стороны, вместе с решением экономить обращения: движок считает каждую
 * позицию партии ровно один раз, а не по разу на «до» и «после» каждого хода.
 * Здесь только арифметика над готовыми числами, поэтому она проверяется без
 * движка и без Android.
 */
object GameAnalyzer {

    /** Оценка позиции с матом для той стороны, которую заматовали, взятая по модулю. */
    const val MATED_CP = 100_000

    /**
     * [positionScores] — оценка каждой позиции партии от лица стороны, чей в
     * ней ход (как её и отдаёт сам движок), в количестве на одну больше, чем
     * полуходов: позиция перед первым ходом и позиция после каждого из них.
     *
     * Оценка позиции i служит дважды: как «до» для хода i и, с обратным
     * знаком, как «после» для хода i-1 — того самого, что привёл в позицию i.
     */
    fun classify(positions: List<PlyPosition>, positionScores: List<Score>): List<PlyEvaluation> {
        require(positionScores.size == positions.size + 1) {
            "Ожидалось ${positions.size + 1} оценок позиций, получено ${positionScores.size}"
        }

        return positions.mapIndexed { index, ply ->
            val before = positionScores[index]
            val afterFromMoverView = ScoreFormat.negate(positionScores[index + 1])

            val loss = ScoreFormat.centipawnLoss(before, afterFromMoverView)

            // Форсированный мат был доступен и после хода пропал — неважно,
            // насколько «дёшево» это выглядит в сантипешках.
            // Сам поставленный мат движок не оценивает: позиция без ходов приходит
            // предельной оценкой в сантипешках, и пропажей мата она не считается.
            val gaveMate = afterFromMoverView is Score.Cp && afterFromMoverView.centipawns >= MATED_CP
            val missedMate = before is Score.Mate && before.movesToMate > 0 && !gaveMate &&
                !(afterFromMoverView is Score.Mate && afterFromMoverView.movesToMate > 0)

            val sideAfterMove = if (ply.whiteMoved) Side.BLACK else Side.WHITE

            PlyEvaluation(
                ply = ply.index,
                classification = MoveClassification.of(loss, missedMate),
                centipawnLoss = loss,
                missedForcedMate = missedMate,
                scoreAfterWhite = ScoreFormat.toWhitePerspective(positionScores[index + 1], sideAfterMove),
            )
        }
    }
}
