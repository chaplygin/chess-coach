package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PkceTest {

    @Test
    fun `matches the reference vector from RFC 7636`() {
        // Приложение B документа RFC 7636. Если эта проверка падает, обмен кода
        // на токен не заработает ни при каких обстоятельствах.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expected, Pkce.challengeS256(verifier))
    }

    @Test
    fun `generated verifier satisfies the specification`() {
        repeat(50) {
            val verifier = Pkce.generateVerifier()
            assertTrue("недопустимый verifier: $verifier", Pkce.isValidVerifier(verifier))
            assertEquals(43, verifier.length)
        }
    }

    @Test
    fun `rejects verifiers outside the allowed shape`() {
        assertTrue(!Pkce.isValidVerifier("слишком-короткий"))
        // Символы + / = недопустимы: это обычный base64, а нужен url-safe без выравнивания.
        assertTrue(!Pkce.isValidVerifier("a".repeat(42) + "+"))
        assertTrue(!Pkce.isValidVerifier("a".repeat(129)))
        assertTrue(Pkce.isValidVerifier("a".repeat(43)))
    }

    @Test
    fun `values are not repeated between runs`() {
        val random = SecureRandom()
        assertNotEquals(Pkce.generateVerifier(random), Pkce.generateVerifier(random))
        assertNotEquals(Pkce.generateState(random), Pkce.generateState(random))
    }

    @Test
    fun `challenge contains no padding`() {
        // Хвостовые знаки = сломали бы параметр в адресе запроса.
        assertTrue(!Pkce.challengeS256(Pkce.generateVerifier()).contains("="))
    }
}
