package com.cedagova.reader.auth

import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT.md, "Refresh policy": the margin, single flight, and what each
 * refresh outcome does to the stored session — through the reader-api call
 * path and the foreground hook, against the fake provider.
 */
class RefreshPolicyTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()
    private val servers = FakeServers()

    private suspend fun clientWith(session: io.github.jan.supabase.auth.user.UserSession?, store: InMemorySessionStore = InMemorySessionStore(session)): Pair<ReaderAuthClient, InMemorySessionStore> {
        val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
        client.awaitReady()
        return client to store
    }

    @Test
    fun `a token inside the margin is refreshed before the call`() = runTest {
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(200)))

        client.capabilities()

        assertEquals(listOf(REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertEquals("""{"refresh_token":"refresh-1"}""", servers.requests[0].body)
        assertEquals("access-2", servers.requests[1].bearer)
        assertEquals("access-2", store.session?.accessToken)
        assertEquals("refresh-2", store.session?.refreshToken)
        client.close()
    }

    @Test
    fun `a token outside the margin is sent as it is`() = runTest {
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(301)))

        client.capabilities()

        assertEquals(listOf(CAPABILITIES), servers.routes())
        assertEquals("access-1", servers.requests[0].bearer)
        assertEquals("access-1", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `concurrent callers collapse to one refresh request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val refreshing = Arrivals(1)
        servers.on(REFRESH_GRANT, gated(gate, refreshing) { json(sessionJson("access-2", "refresh-2")) })
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val (client, _) = clientWith(session(expiresAt = clock.expiring(120)))

        val callers = (1..8).map { async { client.capabilities() } }
        // One caller's refresh is at the provider; run the other seven up to the refresh lock.
        refreshing.await()
        testScheduler.runCurrent()
        gate.complete(Unit)
        callers.awaitAll()

        assertEquals(1, servers.requestsTo("/auth/v1/token").size)
        assertEquals(8, servers.requestsTo("/v1/reader/capabilities").size)
        assertTrue(servers.requestsTo("/v1/reader/capabilities").all { it.bearer == "access-2" })
        client.close()
    }

    @Test
    fun `return to the foreground refreshes inside the margin and not outside`() = runTest {
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(3600)))

        assertTrue(client.onForeground() is ReaderSessionState.SignedIn)
        assertEquals(emptyList<String>(), servers.routes())

        clock.advance(3400.seconds)
        val state = client.onForeground()

        assertEquals(listOf(REFRESH_GRANT), servers.routes())
        assertEquals("access-2", store.session?.accessToken)
        assertTrue(state is ReaderSessionState.SignedIn)
        client.close()
    }

    @Test
    fun `a revoked refresh token clears the session`() = runTest {
        servers.on(REFRESH_GRANT) { json(providerError("refresh_token_already_used"), HttpStatusCode.BadRequest) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(10)))

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.SignedOut)
        assertEquals("refresh_token_already_used", (failure as ReaderAuthException.SignedOut).code)
        assertNull(store.session)
        assertEquals(ReaderSessionState.SignedOut, client.currentState())
        assertEquals(listOf(REFRESH_GRANT), servers.routes())
        assertEquals(emptyList<kotlin.time.Duration>(), waiter.waits)
        client.close()
    }

    @Test
    fun `every named provider code clears the session and no other does`() = runTest {
        for (code in listOf("refresh_token_not_found", "session_not_found", "session_expired", "bad_jwt")) {
            val servers = FakeServers()
            servers.on(REFRESH_GRANT) { json(providerError(code), HttpStatusCode.BadRequest) }
            val store = InMemorySessionStore(session(expiresAt = clock.expiring(10)))
            val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
            client.awaitReady()

            val failure = runCatching { client.capabilities() }.exceptionOrNull()

            assertTrue("$code -> $failure", failure is ReaderAuthException.SignedOut)
            assertNull("$code left a session", store.session)
            client.close()
        }

        val servers = FakeServers()
        servers.on(REFRESH_GRANT) { json(providerError("validation_failed"), HttpStatusCode.BadRequest) }
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(10)))
        val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
        client.awaitReady()

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ProviderRejected)
        assertNotNull("an unknown provider error must keep the session", store.session)
        client.close()
    }

    @Test
    fun `a provider 429 keeps the session and is retried once after Retry-After`() = runTest {
        servers.queue(
            REFRESH_GRANT,
            { json(providerError("over_request_rate_limit"), HttpStatusCode.TooManyRequests, "Retry-After" to "7") },
            { json(sessionJson("access-2", "refresh-2")) },
        )
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(10)))

        client.capabilities()

        assertEquals(listOf(REFRESH_GRANT, REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertEquals(listOf(7.seconds), waiter.waits)
        assertEquals("access-2", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `a second provider 429 surfaces try-later with the session intact`() = runTest {
        servers.on(REFRESH_GRANT) { json(providerError("over_request_rate_limit"), HttpStatusCode.TooManyRequests) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(10)))

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(429, (failure as ReaderAuthException.TryLater).status)
        assertEquals(listOf(REFRESH_GRANT, REFRESH_GRANT), servers.routes())
        assertEquals(listOf(10.seconds), waiter.waits)
        assertEquals("access-1", store.session?.accessToken)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.close()
    }

    @Test
    fun `a provider Retry-After above the ceiling surfaces try-later at once with the server's value`() = runTest {
        servers.on(REFRESH_GRANT) {
            json(providerError("over_request_rate_limit"), HttpStatusCode.TooManyRequests, "Retry-After" to "3600")
        }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(10)))

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(3600.seconds, (failure as ReaderAuthException.TryLater).retryAfter)
        assertEquals("no second grant is sent", listOf(REFRESH_GRANT), servers.routes())
        assertEquals("nothing is waited out", emptyList<Any>(), waiter.waits)
        assertEquals("access-1", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `a provider 5xx is treated like a 429`() = runTest {
        servers.on(REFRESH_GRANT) { json("""{"message":"upstream"}""", HttpStatusCode.BadGateway) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(10)))

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(2, servers.requests.size)
        assertNotNull(store.session)
        client.close()
    }

    @Test
    fun `a network failure during refresh keeps the session after one retry`() = runTest {
        servers.on(REFRESH_GRANT) { networkFailure() }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(10)))

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.NetworkUnavailable)
        assertEquals(listOf(REFRESH_GRANT, REFRESH_GRANT), servers.routes())
        assertEquals(listOf(10.seconds), waiter.waits)
        assertEquals("access-1", store.session?.accessToken)
        client.close()
    }
}
