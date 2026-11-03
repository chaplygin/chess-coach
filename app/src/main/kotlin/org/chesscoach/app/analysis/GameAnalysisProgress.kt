package org.chesscoach.app.analysis

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.chesscoach.core.PlyEvaluation

/** Состояние скана партии движком, как его видит экран. */
sealed interface ScanState {
    /** [partial] — оценки уже посчитанных ходов: по ним рисуется предварительный график. */
    data class Running(val done: Int, val total: Int, val partial: List<PlyEvaluation> = emptyList()) : ScanState
    data class Done(val evaluations: List<PlyEvaluation>) : ScanState
    data class Failed(val message: String) : ScanState
}

/**
 * Мост между фоновым сервисом сканирования и экраном разбора.
 *
 * Сервис и экран Compose всегда живут в одном процессе (отдельный
 * android:process для сервиса не объявлен), а singleTask не даёт системе
 * поднять вторую копию активности. Поэтому обмен состоянием — общий
 * StateFlow в памяти, а не Binder или широковещательные intent'ы: здесь это
 * была бы сложность без выигрыша.
 */
object GameAnalysisProgress {

    private val state = MutableStateFlow<Map<String, ScanState>>(emptyMap())

    fun flowFor(gameId: String): Flow<ScanState?> =
        state.map { it[gameId] }.distinctUntilChanged()

    fun update(gameId: String, value: ScanState) {
        state.update { it + (gameId to value) }
    }
}
