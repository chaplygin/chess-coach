package org.chesscoach.core

/** Цвет, которым играл владелец аккаунта. */
enum class PlayerColor { WHITE, BLACK }

/** Соперник в партии. Компьютерный соперник отличается наличием уровня. */
data class Opponent(
    val name: String,
    val rating: Int?,
    /** Уровень движка Lichess, если соперник — компьютер. У человека null. */
    val aiLevel: Int?,
) {
    val isComputer: Boolean get() = aiLevel != null
}

/**
 * Партия из аккаунта в том виде, в каком её показывает список.
 *
 * Отдельная от ответа сервера модель нужна, чтобы экраны не зависели от формы
 * чужого JSON: у Lichess поля игроков разной формы для человека и компьютера.
 */
data class GameSummary(
    val id: String,
    val myColor: PlayerColor,
    val opponent: Opponent,
    val rated: Boolean,
    val speed: String,
    val status: String,
    val winner: PlayerColor?,
    val createdAtEpochMs: Long,
    val openingName: String?,
    val movesSan: List<String>,
    val initialFen: String?,
) {
    val movesCount: Int get() = movesSan.size

    /** Итог партии глазами владельца аккаунта. */
    val outcome: Outcome
        get() = when (winner) {
            null -> Outcome.DRAW
            myColor -> Outcome.WIN
            else -> Outcome.LOSS
        }

    enum class Outcome { WIN, LOSS, DRAW }
}
