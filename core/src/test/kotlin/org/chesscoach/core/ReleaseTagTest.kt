package org.chesscoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTagTest {
    @Test
    fun `tag round trips through build number`() {
        assertEquals(57, ReleaseTag.buildNumber(ReleaseTag.of(57)))
    }

    @Test
    fun `numbers compare as numbers, not as strings`() {
        assertTrue(ReleaseTag.isNewer("build-10", 9))
    }

    @Test
    fun `same or older build is not an update`() {
        assertFalse(ReleaseTag.isNewer("build-9", 9))
        assertFalse(ReleaseTag.isNewer("build-8", 9))
    }

    @Test
    fun `foreign tag is never an update`() {
        assertNull(ReleaseTag.buildNumber("v1.2"))
        assertFalse(ReleaseTag.isNewer("v99", 1))
        assertFalse(ReleaseTag.isNewer("build-", 1))
    }
}
