package com.cedagova.reader.auth

import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract's named constants, pinned in code and in the document at once:
 * change `300 s` in `ReaderAuthPolicy` and `CONTRACT.md` still says 300 s,
 * so this fails until both agree.
 */
class ReaderAuthPolicyTest {

    private val contract: String =
        File(repositoryRoot(), "reader-auth/CONTRACT.md").also { check(it.isFile) { "$it is missing" } }.readText()

    @Test
    fun `the constants are the ones the contract names`() {
        assertEquals(300.seconds, ReaderAuthPolicy.REFRESH_MARGIN)
        assertEquals(10.seconds, ReaderAuthPolicy.REQUEST_TIMEOUT)
        assertEquals(10.seconds, ReaderAuthPolicy.DEFAULT_RETRY_AFTER)
        assertEquals(1, ReaderAuthPolicy.RETRY_LIMIT)
        assertEquals("reader-android", ReaderAuthPolicy.CLIENT_ID)
        assertEquals("1.0.0", ReaderAuthPolicy.CLIENT_VERSION)
    }

    @Test
    fun `the contract document states the same constants`() {
        listOf(
            "| Refresh margin | 300 s |",
            "| Request timeout | 10 s |",
            "| Default `Retry-After` | 10 s |",
            "| Retry limit | 1 |",
            "`X-Reader-Client: reader-android`",
            "clientVersion=1.0.0",
        ).forEach { needle ->
            assertTrue("CONTRACT.md no longer says $needle", contract.contains(needle))
        }
    }

    @Test
    fun `the contract names every session-clearing provider code`() {
        ReaderAuthPolicy.SESSION_CLEARING_REFRESH_CODES.forEach { code ->
            assertTrue("CONTRACT.md does not list `${code.value}`", contract.contains("`${code.value}`"))
        }
        assertEquals(5, ReaderAuthPolicy.SESSION_CLEARING_REFRESH_CODES.size)
    }

    @Test
    fun `a token inside the margin needs a refresh and one outside does not`() {
        val now = Clock.System.now()
        assertTrue(ReaderAuthPolicy.needsRefresh(expiresAt = now + 300.seconds, now = now))
        assertTrue(ReaderAuthPolicy.needsRefresh(expiresAt = now + 1.seconds, now = now))
        assertTrue(ReaderAuthPolicy.needsRefresh(expiresAt = now - 10.seconds, now = now))
        assertFalse(ReaderAuthPolicy.needsRefresh(expiresAt = now + 301.seconds, now = now))
        assertFalse(ReaderAuthPolicy.needsRefresh(expiresAt = now + 3600.seconds, now = now))
    }

    @Test
    fun `Retry-After is honoured when positive and defaults otherwise`() {
        assertEquals(7.seconds, ReaderAuthPolicy.retryAfter("7"))
        assertEquals(7.seconds, ReaderAuthPolicy.retryAfter(" 7 "))
        assertEquals(10.seconds, ReaderAuthPolicy.retryAfter(null))
        assertEquals(10.seconds, ReaderAuthPolicy.retryAfter("0"))
        assertEquals(10.seconds, ReaderAuthPolicy.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `a blank service value means not configured`() {
        assertTrue(testConfig.isConfigured)
        assertFalse(testConfig.copy(supabaseUrl = "").isConfigured)
        assertFalse(testConfig.copy(publishableKey = " ").isConfigured)
        assertFalse(testConfig.copy(readerApiBaseUrl = "").isConfigured)
        assertEquals("https://api.test", testConfig.copy(readerApiBaseUrl = "https://api.test/").readerApiOrigin)
    }
}
