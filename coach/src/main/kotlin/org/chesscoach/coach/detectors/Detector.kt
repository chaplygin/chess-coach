package org.chesscoach.coach.detectors

import com.github.bhlangonijr.chesslib.Board
import org.chesscoach.coach.model.Fact

/**
 * Детектор признаков позиции.
 *
 * Работает без движка и без побочных эффектов: на одной и той же доске обязан
 * давать один и тот же ответ. Это условие делает разбор воспроизводимым, а
 * тесты — детерминированными.
 *
 * Пустой список — нормальный ответ. Детектор, который на всякий случай что-то
 * возвращает всегда, пройдёт тесты и испортит продукт: тренер начнёт видеть
 * вилки там, где их нет.
 */
interface Detector {
    val name: String
    fun detect(board: Board): List<Fact>
}
