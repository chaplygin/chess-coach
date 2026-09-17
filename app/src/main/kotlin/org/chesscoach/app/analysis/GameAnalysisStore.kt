package org.chesscoach.app.analysis

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.chesscoach.core.MoveClassification
import org.chesscoach.core.PlyEvaluation
import org.chesscoach.core.Score

/**
 * Кэш разбора партии на диске: filesDir/analysis/{gameId}.json.
 *
 * Свои DTO вместо сериализации доменных классов напрямую — тот же приём, что
 * уже применён в :lichess для ответов сервера. [PlyEvaluation] и [Score]
 * живут в чистом JVM-модуле :core без плагина kotlinx.serialization, и тащить
 * туда эту зависимость ради одного экрана незачем.
 */
@Serializable
private data class StoredScore(val cp: Int? = null, val mate: Int? = null) {
    fun toDomain(): Score = if (mate != null) Score.Mate(mate) else Score.Cp(cp ?: 0)

    companion object {
        fun of(score: Score): StoredScore = when (score) {
            is Score.Cp -> StoredScore(cp = score.centipawns)
            is Score.Mate -> StoredScore(mate = score.movesToMate)
        }
    }
}

@Serializable
private data class StoredPlyEvaluation(
    val ply: Int,
    val classification: String,
    val centipawnLoss: Int,
    val missedForcedMate: Boolean,
    val scoreAfterWhite: StoredScore,
) {
    fun toDomain(): PlyEvaluation = PlyEvaluation(
        ply = ply,
        classification = MoveClassification.valueOf(classification),
        centipawnLoss = centipawnLoss,
        missedForcedMate = missedForcedMate,
        scoreAfterWhite = scoreAfterWhite.toDomain(),
    )

    companion object {
        fun of(evaluation: PlyEvaluation): StoredPlyEvaluation = StoredPlyEvaluation(
            ply = evaluation.ply,
            classification = evaluation.classification.name,
            centipawnLoss = evaluation.centipawnLoss,
            missedForcedMate = evaluation.missedForcedMate,
            scoreAfterWhite = StoredScore.of(evaluation.scoreAfterWhite),
        )
    }
}

@Serializable
private data class StoredGameAnalysis(val gameId: String, val evaluations: List<StoredPlyEvaluation>)

object GameAnalysisStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context, gameId: String): File =
        File(File(context.filesDir, "analysis"), "$gameId.json")

    /** Читает кэш партии, либо null, если она ещё не разобрана или файл повреждён. */
    fun read(context: Context, gameId: String): List<PlyEvaluation>? {
        val target = file(context, gameId)
        if (!target.exists()) return null

        return runCatching {
            json.decodeFromString(StoredGameAnalysis.serializer(), target.readText())
                .evaluations.map { it.toDomain() }
        }.getOrNull()
    }

    fun write(context: Context, gameId: String, evaluations: List<PlyEvaluation>) {
        val target = file(context, gameId)
        target.parentFile?.mkdirs()

        val payload = StoredGameAnalysis(gameId, evaluations.map { StoredPlyEvaluation.of(it) })
        target.writeText(json.encodeToString(StoredGameAnalysis.serializer(), payload))
    }
}
