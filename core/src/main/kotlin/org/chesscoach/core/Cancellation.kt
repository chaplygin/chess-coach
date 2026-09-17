package org.chesscoach.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * runCatching, который не считает отмену корутины ошибкой.
 *
 * Обычный runCatching ловит Throwable, а отмена — это тоже Throwable. В
 * корутинах это ломает сразу две вещи. Первая видна пользователю: пока он
 * щёлкает по ходам, каждый предыдущий разбор отменяется, и на экране вместо
 * позиции появляется "The coroutine scope left the composition" — текст,
 * который к шахматам отношения не имеет и выглядит как поломка. Вторая хуже и
 * не видна: обработчик ошибки на отмене успевает закрыть прогретый процесс
 * движка, и следующий ход считается с нуля.
 *
 * Правило простое: отмена летит дальше, всё остальное становится Result.
 */
inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

/** То же самое для уже готового [Result]: отмену внутри block пропускаем наружу. */
inline fun <T, R> Result<T>.mapCatchingNonCancellation(block: (T) -> R): Result<R> =
    fold(
        onSuccess = { value -> catchingNonCancellation { block(value) } },
        onFailure = { error -> Result.failure(error) },
    )
