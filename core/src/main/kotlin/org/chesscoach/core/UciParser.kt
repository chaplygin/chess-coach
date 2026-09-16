package org.chesscoach.core

/**
 * Разбор строк протокола UCI.
 *
 * Живёт в чистом JVM-модуле намеренно: это самая ошибкоопасная часть работы с
 * движком, и здесь она покрывается юнит-тестами в CI без Android и без телефона.
 */
object UciParser {

    /**
     * Разбирает строку `info ...`.
     *
     * Возвращает null, если строка не несёт законченной оценки: это могут быть
     * служебные `info currmove ...`, строки без главного варианта или оценки с
     * пометками lowerbound/upperbound. Последние особенно важно отбрасывать —
     * это промежуточные границы окна поиска, а не настоящая оценка позиции,
     * и показывать их пользователю нельзя.
     */
    fun parseInfo(line: String): EngineLine? {
        if (!line.startsWith("info ")) return null

        val tokens = line.split(' ').filter { it.isNotEmpty() }
        var depth: Int? = null
        var multiPv = 1
        var score: Score? = null
        var pv: List<String> = emptyList()
        var bounded = false

        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> {
                    depth = tokens.getOrNull(i + 1)?.toIntOrNull()
                    i += 2
                }

                "multipv" -> {
                    multiPv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 1
                    i += 2
                }

                "score" -> when (tokens.getOrNull(i + 1)) {
                    "cp" -> {
                        score = tokens.getOrNull(i + 2)?.toIntOrNull()?.let(Score::Cp)
                        i += 3
                    }

                    "mate" -> {
                        score = tokens.getOrNull(i + 2)?.toIntOrNull()?.let(Score::Mate)
                        i += 3
                    }

                    else -> i += 2
                }

                "lowerbound", "upperbound" -> {
                    bounded = true
                    i += 1
                }

                // Главный вариант всегда идёт последним в строке.
                "pv" -> {
                    pv = tokens.drop(i + 1)
                    i = tokens.size
                }

                else -> i += 1
            }
        }

        if (bounded) return null
        val finalDepth = depth ?: return null
        val finalScore = score ?: return null
        if (pv.isEmpty()) return null

        return EngineLine(multiPvIndex = multiPv, score = finalScore, pv = pv, depth = finalDepth)
    }

    /** Числовые поля строки `info ...`: nodes, nps, time, hashfull. */
    fun parseNumericField(line: String, field: String): Long? {
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        val index = tokens.indexOf(field)
        if (index < 0) return null
        return tokens.getOrNull(index + 1)?.toLongOrNull()
    }

    /** Возвращает ход из строки `bestmove e2e4 ponder e7e5`, либо null. */
    fun parseBestMove(line: String): String? {
        if (!line.startsWith("bestmove")) return null
        val move = line.split(' ').filter { it.isNotEmpty() }.getOrNull(1) ?: return null
        // Движок отвечает bestmove даже когда ходов нет — мат или пат.
        return move.takeIf { it != "(none)" && it != "0000" }
    }
}

/**
 * Копит строки одного поиска и оставляет по каждому варианту MultiPV только
 * самый глубокий результат. Движок присылает один и тот же вариант много раз
 * по мере углубления, и брать надо последний, а не первый.
 */
class MultiPvCollector {

    private val deepestByIndex = LinkedHashMap<Int, EngineLine>()

    fun accept(line: String) {
        val parsed = UciParser.parseInfo(line) ?: return
        val existing = deepestByIndex[parsed.multiPvIndex]
        if (existing == null || parsed.depth >= existing.depth) {
            deepestByIndex[parsed.multiPvIndex] = parsed
        }
    }

    fun acceptAll(lines: Iterable<String>): MultiPvCollector = apply { lines.forEach(::accept) }

    fun result(): List<EngineLine> = deepestByIndex.values.sortedBy { it.multiPvIndex }
}
