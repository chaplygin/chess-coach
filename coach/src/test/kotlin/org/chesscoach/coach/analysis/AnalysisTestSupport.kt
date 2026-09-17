package org.chesscoach.coach.analysis

import org.chesscoach.coach.model.FeatureSet
import org.chesscoach.coach.model.FeatureSource

/**
 * FeatureSource на заранее заготовленных наборах признаков: для FEN, которого
 * нет в карте, отдаёт пустой набор, а не падает — большинству тестов этого
 * модуля признаки для промежуточных позиций (после ответа соперника и т.п.)
 * не важны вовсе.
 */
class MapFeatureSource(private val byFen: Map<String, FeatureSet>) : FeatureSource {
    override fun features(fen: String): FeatureSet = byFen[fen.trim()] ?: FeatureSet.empty(fen)
}

object CommonFens {
    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    const val AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    const val AFTER_E4_E5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
    const val FLIP_START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
    const val FLIP_AFTER_E4_E5 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"
    const val CHECK_FEN = "4k3/8/8/8/8/8/4r3/4K3 w - - 0 1"
    const val KINGS_ONLY = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    const val FORK_SETUP = "4k3/8/8/8/3n4/8/8/2R1K2R w K - 0 1"
    const val FLIP_FORK_SETUP = "4k3/8/8/8/3n4/8/8/2R1K2R b K - 0 1"
    const val AFTER_FORK_MOVE = "4k3/8/8/8/8/8/2n5/2R1K2R w K - 1 2"
    const val AFTER_NC3 = "rnbqkbnr/pppppppp/8/8/8/2N5/PPPPPPPP/R1BQKBNR b KQkq - 1 1"
    const val AFTER_NF3 = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"
}
