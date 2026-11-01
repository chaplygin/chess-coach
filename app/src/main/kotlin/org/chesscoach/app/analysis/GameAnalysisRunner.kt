package org.chesscoach.app.analysis

import com.github.bhlangonijr.chesslib.Board
import org.chesscoach.core.GameAnalyzer
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.PlyPosition
import org.chesscoach.core.Score
import org.chesscoach.engine.EngineController
import org.chesscoach.engine.SearchLimits

/**
 * Прогоняет движок по всей партии один раз и отдаёт разбор по каждому полуходу.
 *
 * Один и тот же тёплый процесс движка (им владеет [EngineController]) считает
 * оценку каждой позиции партии ровно один раз: она служит и «до» для хода из
 * неё, и, с обратным знаком, «после» для предыдущего хода — арифметику берёт
 * на себя [GameAnalyzer]. На партию из n полуходов уходит n+1 обращений к
 * движку, а не 2n — иначе восемьдесят полуходов считались бы не минуту, а две.
 */
class GameAnalysisRunner(private val engine: EngineController) {

    suspend fun analyze(
        positions: List<PlyPosition>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<List<PlyEvaluation>> {
        if (positions.isEmpty()) return Result.success(emptyList())

        // n+1 различных позиций на n полуходов: fenBefore первого хода и
        // fenAfter каждого хода. Одна и та же позиция никогда не считается
        // дважды, даже если это fenAfter одного хода и fenBefore следующего.
        val fens = ArrayList<String>(positions.size + 1)
        fens += positions.first().fenBefore
        positions.forEach { fens += it.fenAfter }

        val scores = ArrayList<Score>(fens.size)

        for ((index, fen) in fens.withIndex()) {
            // engine.analyze сам перезапускает упавший процесс движка и один
            // раз повторяет запрос — скан партии на 80 полуходов не должен
            // падать целиком из-за одного сбойного обращения к движку.
            val result = engine.analyze(fen, SearchLimits(movetimeMs = MOVETIME_MS, multiPv = 1))
                .getOrElse { return Result.failure(it) }
            val score = result.lines.firstOrNull()?.score
                ?: terminalScoreOrNull(fen)
                ?: return Result.failure(
                    IllegalStateException("Движок не вернул оценку для позиции $index из ${fens.size}")
                )
            scores += score
            onProgress(index.coerceAtMost(positions.size), positions.size)
        }

        return Result.success(GameAnalyzer.classify(positions, scores))
    }

    /**
     * Оценка для позиции без ходов — мат или пат.
     *
     * Движок не присылает `info score` для такой позиции: искать в ней нечего,
     * он сразу отвечает `bestmove (none)`. В партии это может быть только
     * самая последняя позиция (та, что закончилась матом), но реальные партии
     * заканчиваются так сплошь и рядом — без этой ветки скан падал бы именно
     * на них. Для всех остальных позиций легальный ход есть по построению
     * (иначе партия бы на них не продолжилась), и null означает настоящий
     * сбой движка, а не мат.
     */
    private fun terminalScoreOrNull(fen: String): Score? {
        val board = Board().apply { loadFromFen(fen) }
        if (board.legalMoves().isNotEmpty()) return null

        // Мат — предельно плохая оценка для того, чей сейчас ход (его
        // заматовали). Пат — ничья, честный ноль.
        return if (board.isMated) Score.Cp(MATED_CP) else Score.Cp(0)
    }

    private companion object {
        const val MOVETIME_MS = 700
        const val MATED_CP = -GameAnalyzer.MATED_CP
    }
}
