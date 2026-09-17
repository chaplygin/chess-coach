package org.chesscoach.coach.text

import kotlin.random.Random

/**
 * Пул формулировок на структурную ситуацию.
 *
 * Без него разбор нескольких позиций подряд читался бы как один и тот же
 * текст с подставленными именами — а это и есть канцелярит, которого просили
 * избежать. Каждая формулировка — типизированная функция Kotlin от факта
 * ([choose] принимает список таких функций), а не строка с плейсхолдером:
 * согласование и вообще компилируемость текста тем самым проверяет
 * компилятор, а не рендер.
 *
 * Генератор случайных чисел засеян хешем позиции (FEN), поэтому вариативность
 * — между позициями, а не внутри одного и того же вызова: на одном и том же
 * FEN результат всегда один и тот же, как и требуют тесты. Последние три
 * использованные формулировки исключаются из выбора (кроме случая, когда
 * вариантов меньше трёх), чтобы соседние разделы одного разбора не звучали
 * одним и тем же оборотом.
 */
class TemplatePool(fen: String) {

    private val random = Random(seedOf(fen))
    private val recentlyUsed = ArrayDeque<String>()

    /**
     * Выбирает одну из готовых формулировок для [fact].
     *
     * Все варианты просчитываются заранее (это дёшево — обычная подстановка
     * в строку), из них исключаются недавно использованные, и среди
     * оставшихся выбор идёт по сидированному генератору.
     */
    fun <T> choose(fact: T, templates: List<(T) -> String>): String {
        require(templates.isNotEmpty()) { "Пул формулировок не может быть пустым" }

        val rendered = templates.map { it(fact) }
        val freshIndices = rendered.indices.filter { rendered[it] !in recentlyUsed }
        val candidates = freshIndices.ifEmpty { rendered.indices.toList() }

        val chosen = rendered[candidates[random.nextInt(candidates.size)]]
        remember(chosen)
        return chosen
    }

    private fun remember(text: String) {
        recentlyUsed.addLast(text)
        if (recentlyUsed.size > HISTORY_SIZE) recentlyUsed.removeFirst()
    }

    private companion object {
        const val HISTORY_SIZE = 3

        /** Простой стабильный хеш строки: одинаковый FEN — одинаковый сид на любой JVM. */
        fun seedOf(fen: String): Long = fen.fold(0L) { acc, ch -> acc * 31 + ch.code }
    }
}
