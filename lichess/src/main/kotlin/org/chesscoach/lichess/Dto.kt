package org.chesscoach.lichess

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.chesscoach.core.GameSummary
import org.chesscoach.core.Opponent
import org.chesscoach.core.PlayerColor

/**
 * Ответы Lichess как они есть.
 *
 * Отделены от моделей приложения намеренно: форма чужого JSON неоднородна —
 * у человека игрок описан вложенным объектом user, у компьютера вместо него
 * уровень движка, — и тащить эту неоднородность в экраны не стоит.
 *
 * Все классы терпимы к незнакомым полям: Lichess добавляет их без предупреждения,
 * и падать из-за нового поля приложение не должно.
 */

@Serializable
data class AccountDto(
    val id: String,
    val username: String,
) {
    fun toDomain() = LichessAccount(id = id, username = username)
}

data class LichessAccount(val id: String, val username: String)

@Serializable
data class UserRefDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class PlayerDto(
    val user: UserRefDto? = null,
    val rating: Int? = null,
    /** Уровень движка Lichess. Присутствует только у компьютерного соперника. */
    val aiLevel: Int? = null,
) {
    fun displayName(): String = user?.name ?: aiLevel?.let { "Компьютер, уровень $it" } ?: "Аноним"
}

@Serializable
data class PlayersDto(
    val white: PlayerDto = PlayerDto(),
    val black: PlayerDto = PlayerDto(),
)

@Serializable
data class OpeningDto(
    val eco: String? = null,
    val name: String? = null,
)

@Serializable
data class GameDto(
    val id: String,
    val rated: Boolean = false,
    val variant: String = "standard",
    val speed: String = "unknown",
    val status: String = "unknown",
    val createdAt: Long = 0,
    val winner: String? = null,
    val moves: String = "",
    val opening: OpeningDto? = null,
    val players: PlayersDto = PlayersDto(),
    @SerialName("initialFen") val initialFen: String? = null,
) {

    /**
     * Приводит партию к модели приложения с точки зрения владельца аккаунта.
     *
     * Возвращает null для нестандартных вариантов: движок и будущие детекторы
     * рассчитаны на обычные шахматы, и молча разбирать Chess960 как обычную
     * партию значило бы выдавать неверные объяснения.
     */
    fun toDomain(myUsername: String): GameSummary? {
        if (variant != "standard") return null

        val whiteName = players.white.user?.name
        val myColor = when {
            whiteName != null && whiteName.equals(myUsername, ignoreCase = true) -> PlayerColor.WHITE
            else -> PlayerColor.BLACK
        }

        val opponentPlayer = if (myColor == PlayerColor.WHITE) players.black else players.white

        return GameSummary(
            id = id,
            myColor = myColor,
            opponent = Opponent(
                name = opponentPlayer.displayName(),
                rating = opponentPlayer.rating,
                aiLevel = opponentPlayer.aiLevel,
            ),
            rated = rated,
            speed = speed,
            status = status,
            winner = when (winner) {
                "white" -> PlayerColor.WHITE
                "black" -> PlayerColor.BLACK
                else -> null
            },
            createdAtEpochMs = createdAt,
            openingName = opening?.name,
            movesSan = moves.split(' ').filter { it.isNotBlank() },
            initialFen = initialFen,
        )
    }
}

@Serializable
data class NowPlayingDto(
    val nowPlaying: List<OngoingGameDto> = emptyList(),
)

@Serializable
data class OngoingOpponentDto(
    val id: String? = null,
    val username: String? = null,
    val rating: Int? = null,
    /** Уровень движка Lichess. У живого соперника поля нет — это и есть признак. */
    val ai: Int? = null,
)

/**
 * Вариант игры в ответе /api/account/playing.
 *
 * Здесь Lichess отдаёт объект, а не строку: {"key":"standard","name":"Standard"}.
 * В выгрузке партий (/api/games/user) то же поле — обычная строка, и именно это
 * расхождение обрушивало экран текущей партии. Фикстуры тестов были написаны по
 * нашей модели, а не по настоящему ответу, поэтому ошибку они не ловили.
 */
@Serializable
data class VariantDto(
    val key: String = "standard",
    val name: String? = null,
)

@Serializable
data class OngoingGameDto(
    val gameId: String,
    val fullId: String = "",
    val color: String = "white",
    val fen: String = "",
    val lastMove: String = "",
    val isMyTurn: Boolean = false,
    val rated: Boolean = false,
    val speed: String = "unknown",
    val variant: VariantDto = VariantDto(),
    val secondsLeft: Int? = null,
    val opponent: OngoingOpponentDto = OngoingOpponentDto(),
) {
    fun toDomain() = OngoingGame(
        gameId = gameId,
        myColor = if (color == "white") PlayerColor.WHITE else PlayerColor.BLACK,
        fen = fen,
        lastMoveUci = lastMove.takeIf { it.isNotBlank() },
        isMyTurn = isMyTurn,
        rated = rated,
        speed = speed,
        isOrdinaryChess = variant.key in ORDINARY_CHESS_VARIANTS,
        opponent = Opponent(
            name = opponent.username ?: opponent.ai?.let { "Компьютер, уровень $it" } ?: "Соперник",
            rating = opponent.rating,
            aiLevel = opponent.ai,
        ),
    )
}

/**
 * Идущая партия.
 *
 * Свойство [opponent] несёт признак компьютерного соперника — единственную
 * законную границу для подсказок в реальном времени.
 */
data class OngoingGame(
    val gameId: String,
    val myColor: PlayerColor,
    val fen: String,
    val lastMoveUci: String?,
    val isMyTurn: Boolean,
    val rated: Boolean,
    val speed: String,
    /**
     * Играется ли партия по обычным шахматным правилам.
     *
     * Партия "от позиции" сюда входит: начальная расстановка у неё своя, но
     * правила обычные, а тренер разбирает позицию, а не дебютную книгу. Не
     * входят Chess960 и прочие варианты — там и движок, и детекторы дали бы
     * неверные объяснения.
     */
    val isOrdinaryChess: Boolean,
    val opponent: Opponent,
) {
    /**
     * Разрешены ли подсказки в реальном времени.
     *
     * Только против движка Lichess: правила честной игры прямо запрещают любую
     * внешнюю помощь в партиях с людьми, и за это закрывают аккаунт.
     */
    val liveCoachingAllowed: Boolean get() = opponent.isComputer && isOrdinaryChess
}

/**
 * Варианты, которые играются по обычным правилам и потому разбираются.
 *
 * Список закрытый и разрешающий: неизвестный ключ считается неразбираемым, а
 * не наоборот — молча разобрать Chess960 как обычную партию хуже, чем
 * промолчать.
 */
private val ORDINARY_CHESS_VARIANTS = setOf("standard", "fromPosition")
