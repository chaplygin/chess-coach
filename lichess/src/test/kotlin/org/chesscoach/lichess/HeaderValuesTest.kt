package org.chesscoach.lichess

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Значения заголовков HTTP обязаны быть в латинице.
 *
 * Заголовки передаются однобайтовой кодировкой, и кириллица в них не проходит:
 * OkHttp отвергает такой запрос ещё до отправки. Проверка стоит здесь, потому
 * что цена ошибки несоразмерна её размеру — русское пояснение в User-Agent
 * ломало вход в аккаунт целиком, а выглядело как безобидный текст.
 */
class HeaderValuesTest {

    @Test
    fun `user agent contains no characters a header cannot carry`() {
        val offending = LichessApi.USER_AGENT.filter { it.code !in 0x20..0x7E }

        assertTrue(
            "в User-Agent есть символы вне ASCII: " +
                offending.map { "0x" + it.code.toString(16) } +
                " в значении " + LichessApi.USER_AGENT,
            offending.isEmpty(),
        )
    }

    @Test
    fun `user agent still says who is calling`() {
        // Lichess просит называться: безымянный клиент выглядит как робот,
        // которого нельзя ни о чём предупредить.
        assertTrue(LichessApi.USER_AGENT, LichessApi.USER_AGENT.startsWith("ChessCoach/"))
    }
}
