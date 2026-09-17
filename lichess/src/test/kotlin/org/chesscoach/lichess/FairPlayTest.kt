package org.chesscoach.lichess

import kotlinx.serialization.json.Json
import org.chesscoach.core.GameSummary
import org.chesscoach.core.PlayerColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true }

/**
 * Проверки границы честной игры.
 *
 * Это самая ответственная логика в приложении: ошибка здесь означает подсказку
 * движка в партии против живого человека, то есть нарушение правил Lichess и
 * закрытие аккаунта пользователя. Поэтому проверяется и разрешающий случай,
 * и — что важнее — все запрещающие.
 */
class FairPlayTest {

    private fun ongoing(body: String): OngoingGame =
        json.decodeFromString<OngoingGameDto>(body).toDomain()

    @Test
    fun `hints are allowed against the lichess engine`() {
        val game = ongoing(
            """
            {"gameId":"abc123","color":"white","fen":"8/8/8/8/8/8/8/8 w - - 0 1",
             "isMyTurn":true,"variant":{"key":"standard","name":"Standard"},
             "opponent":{"id":"ai","username":"Stockfish level 5","ai":5}}
            """.trimIndent()
        )

        assertTrue(game.opponent.isComputer)
        assertEquals(5, game.opponent.aiLevel)
        assertTrue("против движка подсказки разрешены явно", game.liveCoachingAllowed)
    }

    @Test
    fun `hints are forbidden against a human opponent`() {
        // У живого соперника поля ai нет вовсе — это и есть признак.
        val game = ongoing(
            """
            {"gameId":"def456","color":"black","fen":"8/8/8/8/8/8/8/8 b - - 0 1",
             "isMyTurn":true,"rated":true,"variant":{"key":"standard","name":"Standard"},
             "opponent":{"id":"petrov","username":"petrov","rating":1732}}
            """.trimIndent()
        )

        assertFalse(game.opponent.isComputer)
        assertNull(game.opponent.aiLevel)
        assertFalse("против человека подсказки запрещены", game.liveCoachingAllowed)
    }

    @Test
    fun `hints are forbidden in a casual game against a human too`() {
        // Правила Lichess не делают различия между рейтинговой и лёгкой партией.
        val game = ongoing(
            """
            {"gameId":"ghi789","color":"white","fen":"8/8/8/8/8/8/8/8 w - - 0 1",
             "rated":false,"variant":{"key":"standard","name":"Standard"},
             "opponent":{"username":"guest","rating":1200}}
            """.trimIndent()
        )

        assertFalse(game.liveCoachingAllowed)
    }

    @Test
    fun `hints are forbidden in a non standard variant even against the engine`() {
        // Движок и будущие детекторы рассчитаны на обычные шахматы.
        val game = ongoing(
            """
            {"gameId":"jkl012","color":"white","fen":"8/8/8/8/8/8/8/8 w - - 0 1",
             "variant":{"key":"chess960","name":"Chess960"},"opponent":{"username":"Stockfish","ai":3}}
            """.trimIndent()
        )

        assertTrue(game.opponent.isComputer)
        assertFalse("нестандартный вариант — подсказок нет", game.liveCoachingAllowed)
    }

    @Test
    fun `a game started from a position is analysed like ordinary chess`() {
        // Расстановка своя, правила обычные — тренер разбирает позицию, а не
        // дебютную книгу. Именно на таком ответе экран и падал: Lichess шлёт
        // вариант объектом, а модель ждала строку.
        val game = ongoing(
            """
            {"gameId":"pos001","color":"white","fen":"8/8/8/8/8/8/8/8 w - - 0 1",
             "isMyTurn":true,"variant":{"key":"fromPosition","name":"From Position"},
             "opponent":{"username":"Stockfish level 4","ai":4}}
            """.trimIndent()
        )

        assertTrue(game.isOrdinaryChess)
        assertTrue("против движка от позиции подсказки разрешены", game.liveCoachingAllowed)
    }

    @Test
    fun `an opponent without any recognisable marks is treated as human`() {
        // Умолчание должно быть безопасным: при неизвестной форме ответа
        // считаем соперника живым и подсказки не выдаём.
        val game = ongoing("""{"gameId":"mno345","opponent":{}}""")

        assertFalse(game.opponent.isComputer)
        assertFalse(game.liveCoachingAllowed)
    }
}

class GameMappingTest {

    private val sample = """
        {"id":"xyz789","rated":true,"variant":"standard","speed":"blitz","status":"mate",
         "createdAt":1700000000000,"winner":"black","moves":"e4 e5 Nf3 Nc6 Bb5 a6",
         "opening":{"eco":"C60","name":"Ruy Lopez"},
         "players":{"white":{"user":{"id":"petrov","name":"petrov"},"rating":1700},
                    "black":{"user":{"id":"chaplygin","name":"chaplygin"},"rating":1650}}}
    """.trimIndent()

    private fun parse(body: String, me: String): GameSummary? =
        json.decodeFromString<GameDto>(body).toDomain(me)

    @Test
    fun `figures out which colour I played`() {
        val game = parse(sample, "chaplygin")!!
        assertEquals(PlayerColor.BLACK, game.myColor)
        assertEquals("petrov", game.opponent.name)
        assertEquals(1700, game.opponent.rating)
    }

    @Test
    fun `matches the username regardless of letter case`() {
        // Lichess отдаёт имя в том виде, как его завёл владелец аккаунта.
        val game = parse(sample, "CHAPLYGIN")!!
        assertEquals(PlayerColor.BLACK, game.myColor)
    }

    @Test
    fun `reports the result from my point of view`() {
        assertEquals(GameSummary.Outcome.WIN, parse(sample, "chaplygin")!!.outcome)
        assertEquals(GameSummary.Outcome.LOSS, parse(sample, "petrov")!!.outcome)
    }

    @Test
    fun `reads the moves and the opening`() {
        val game = parse(sample, "chaplygin")!!
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6"), game.movesSan)
        assertEquals(6, game.movesCount)
        assertEquals("Ruy Lopez", game.openingName)
    }

    @Test
    fun `skips non standard variants instead of misreading them`() {
        val body = sample.replace("\"variant\":\"standard\"", "\"variant\":\"crazyhouse\"")
        assertNull(parse(body, "chaplygin"))
    }

    @Test
    fun `survives unknown fields`() {
        // Lichess добавляет поля без предупреждения, и падать из-за этого нельзя.
        val body = sample.dropLast(1) + ""","somethingBrandNew":{"nested":[1,2,3]}}"""
        assertEquals("xyz789", parse(body, "chaplygin")!!.id)
    }

    @Test
    fun `recognises a game against the engine in history`() {
        val body = """
            {"id":"ai001","variant":"standard","speed":"correspondence","status":"resign",
             "moves":"d4 d5","players":{"white":{"user":{"name":"chaplygin"},"rating":1600},
                                        "black":{"aiLevel":6}}}
        """.trimIndent()

        val game = parse(body, "chaplygin")!!
        assertTrue(game.opponent.isComputer)
        assertEquals(6, game.opponent.aiLevel)
    }
}
