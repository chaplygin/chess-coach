package org.chesscoach.app

import org.chesscoach.coach.engine.CoachSearchLimits
import org.chesscoach.coach.engine.EngineClient
import org.chesscoach.core.EngineLine
import org.chesscoach.engine.EngineController
import org.chesscoach.engine.SearchLimits

/**
 * Переходник между тренером и настоящим Stockfish.
 *
 * Живёт в :app, а не в одном из модулей: иначе :engine пришлось бы знать про
 * :coach или наоборот, и аккуратный граф зависимостей превратился бы в кольцо.
 * Склейка — работа приложения, а не библиотек.
 */
class StockfishEngineClient(private val controller: EngineController) : EngineClient {

    override suspend fun analyse(fen: String, limits: CoachSearchLimits): List<EngineLine> {
        // На слабом телефоне три линии MultiPV отъедают глубину поиска —
        // калибровка (M8) ограничивает запрос тренера тем, что телефон
        // реально тянет.
        val multiPv = limits.multiPv.coerceAtMost(controller.multiPvCap())

        // controller.analyze уже перезапускает упавший процесс движка и один
        // раз повторяет запрос. Отмена — не "движок молчит": пустой список в
        // этом случае заставил бы тренера собрать разбор без единой строки
        // движка вместо того, чтобы честно свернуться вместе с экраном, —
        // поэтому CancellationException из analyze долетает наружу как есть.
        return controller.analyze(
            fen,
            SearchLimits(movetimeMs = limits.movetimeMs.toInt(), depth = limits.depth, multiPv = multiPv),
        ).getOrNull()?.lines ?: emptyList()
    }
}
