package org.chesscoach.coach.engine

import org.chesscoach.core.EngineLine

/**
 * Реализация EngineClient на замороженных ответах: FEN в ту же строку, что
 * отдал бы движок.
 *
 * Это тот самый шов, ради которого весь аналитический слой тестируется без
 * настоящего Stockfish: без бинарника, без телефона и без недетерминизма
 * поиска, который иначе превратил бы тесты в лотерею.
 *
 * Ключ карты — это ровно тот FEN, с которым к клиенту обратятся. Так тест
 * сам решает, что ответить на каждый конкретный вызов конвейера (основной
 * поиск, нулевой ход, подтверждение хода, опровержение ловушки, быстрый
 * поиск), не притворяясь настоящим движком.
 */
class FakeEngineClient(
    private val responses: Map<String, List<EngineLine>>,
) : EngineClient {

    override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> {
        val key = normalize(fen)
        // Явная ошибка вместо тихой пустоты: тест, забывший заморозить ответ
        // для какого-то FEN, должен упасть с понятной причиной, а не молча
        // получить пустой разбор и решить, что код сломан.
        return responses[key] ?: throw IllegalStateException(
            "FakeEngineClient: нет замороженного ответа для FEN \"$fen\". " +
                "Заморожено ${responses.size} позиций.",
        )
    }

    private fun normalize(fen: String): String = fen.trim()

    companion object {
        /** Тот же клиент, но неизвестный FEN тихо даёт пустой список — когда в тесте это осознанно нужно. */
        fun ofOrEmpty(responses: Map<String, List<EngineLine>>): EngineClient =
            object : EngineClient {
                override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> =
                    responses[fen.trim()] ?: emptyList()
            }
    }
}
