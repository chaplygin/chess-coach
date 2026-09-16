package org.chesscoach.coach.engine

import org.chesscoach.core.EngineLine

data class CoachSearchLimits(
    val movetimeMs: Long,
    val depth: Int? = null,
    val multiPv: Int = 1,
)

/**
 * Шов между разбором и настоящим Stockfish.
 *
 * Благодаря ему почти вся логика тренера проверяется на замороженных ответах
 * движка: без бинарника, без телефона и без недетерминизма, который иначе
 * превратил бы тесты в лотерею.
 */
interface EngineClient {
    suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine>
}
