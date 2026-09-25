package com.cedagova.reader.auth

import com.cedagova.reader.auth.session.StoreSessionManager
import com.cedagova.reader.auth.testing.FakeClock
import com.cedagova.reader.auth.testing.FakeServers
import com.cedagova.reader.auth.testing.InMemorySessionStore
import com.cedagova.reader.auth.testing.session
import com.cedagova.reader.auth.testing.testConfig
import com.cedagova.reader.auth.testing.user
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider SDK defaults the contract overrides stay overridden
 * (CONTRACT.md, "Provider SDK defaults this module overrides"): restoring any
 * of them — plaintext `SharedPreferences` storage, the background auto-refresh
 * loop, the implicit flow — fails here before it can ship.
 */
class SdkDefaultsTest {

    @Test
    fun `the three overridden defaults are pinned`() = runTest {
        val store = InMemorySessionStore()
        val client = ReaderAuthClient.build(testConfig, store, FakeServers().engine)
        val config = client.supabase.auth.config

        val manager = config.sessionManager
        assertTrue("session manager is ${manager?.javaClass}", manager is StoreSessionManager)
        assertSame(store, (manager as StoreSessionManager).store)
        assertTrue(
            "code verifier cache is ${config.codeVerifierCache?.javaClass}",
            config.codeVerifierCache is MemoryCodeVerifierCache,
        )
        assertFalse("the SDK's background auto-refresh must stay off", config.alwaysAutoRefresh)
        assertFalse("the SDK's lifecycle-driven refresh must stay off", config.enableLifecycleCallbacks)
        assertTrue(config.autoLoadFromStorage)
        assertTrue(config.autoSaveToStorage)
        assertEquals(FlowType.PKCE, config.flowType)
        assertEquals(10.seconds, client.supabase.config.networkConfig.requestTimeout)
        client.close()
    }

    @Test
    fun `a blank configuration is refused before any client exists`() {
        val servers = FakeServers()
        try {
            ReaderAuthClient.build(testConfig.copy(publishableKey = ""), InMemorySessionStore(), servers.engine)
            error("expected NotConfigured")
        } catch (e: ReaderAuthException.NotConfigured) {
            assertTrue(servers.requests.isEmpty())
        }
    }

    @Test
    fun `a stored session is loaded at start and an empty store means signed out`() = runTest {
        val clock = FakeClock()
        val stored = session(expiresAt = clock.expiring(3600))
        val signedIn = ReaderAuthClient.build(testConfig, InMemorySessionStore(stored), FakeServers().engine, clock)
        signedIn.awaitReady()
        assertEquals(
            ReaderSessionState.SignedIn("user-1", "reader@example.test", stored.expiresAt),
            signedIn.currentState(),
        )
        signedIn.close()

        val signedOut = ReaderAuthClient.build(testConfig, InMemorySessionStore(), FakeServers().engine, clock)
        signedOut.awaitReady()
        assertEquals(ReaderSessionState.SignedOut, signedOut.currentState())
        signedOut.close()
    }
}
