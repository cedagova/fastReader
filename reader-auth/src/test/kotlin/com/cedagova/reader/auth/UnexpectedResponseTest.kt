package com.cedagova.reader.auth

import com.cedagova.reader.auth.testing.Arrivals
import com.cedagova.reader.auth.testing.CAPABILITIES
import com.cedagova.reader.auth.testing.CAPABILITIES_BODY
import com.cedagova.reader.auth.testing.FakeClock
import com.cedagova.reader.auth.testing.FakeServers
import com.cedagova.reader.auth.testing.InMemorySessionStore
import com.cedagova.reader.auth.testing.PASSWORD_GRANT
import com.cedagova.reader.auth.testing.PRE_AUTH
import com.cedagova.reader.auth.testing.REFRESH_GRANT
import com.cedagova.reader.auth.testing.RecordingWaiter
import com.cedagova.reader.auth.testing.Responder
import com.cedagova.reader.auth.testing.USER
import com.cedagova.reader.auth.testing.gated
import com.cedagova.reader.auth.testing.json
import com.cedagova.reader.auth.testing.preAuthJson
import com.cedagova.reader.auth.testing.session
import com.cedagova.reader.auth.testing.sessionJson
import com.cedagova.reader.auth.testing.testConfig
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #191: an answer nobody can read never escapes the closed set. A provider
 * 2xx whose body does not decode (a captive portal's page) is
 * [ReaderAuthException.UnexpectedResponse] — session kept, never retried —
 * and an unreadable reader-api body stays [ReaderAuthException.ApiError] or
 * follows the status policy (CONTRACT.md, "Refresh policy" and "reader-api
 * call policy").
 */
class UnexpectedResponseTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()
    private val servers = FakeServers()

    private suspend fun client(store: InMemorySessionStore): ReaderAuthClient =
        ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter).also { it.awaitReady() }

    private val portal: Responder = {
        respond(
            "<html><body>Sign in to the hotel Wi-Fi</body></html>",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "text/html"),
        )
    }

    private fun inMargin(): UserSession = session(expiresAt = clock.expiring(120))

    // ---- Refresh -----------------------------------------------------------------------------

    @Test
    fun `return to the foreground behind a captive portal keeps the session, sends one refresh and does not throw`() =
        runTest {
            servers.on(REFRESH_GRANT, portal)
            val stored = inMargin()
            val store = InMemorySessionStore(stored)
            val client = client(store)

            val state = client.onForeground()

            assertTrue("$state", state is ReaderSessionState.SignedIn)
            assertEquals(stored.expiresAt, (state as ReaderSessionState.SignedIn).expiresAt)
            assertEquals("exactly one refresh request", listOf(REFRESH_GRANT), servers.routes())
            assertEquals(emptyList<Any>(), waiter.waits)
            assertEquals("access-1", store.session?.accessToken)
            assertEquals("refresh-1", store.session?.refreshToken)
            client.close()
        }

    @Test
    fun `a protected call behind a captive portal throws unexpected response and sends nothing to reader-api`() =
        runTest {
            servers.on(REFRESH_GRANT, portal)
            servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
            val store = InMemorySessionStore(inMargin())
            val client = client(store)

            val failure = runCatching { client.capabilities() }.exceptionOrNull()

            assertTrue("$failure", failure is ReaderAuthException.UnexpectedResponse)
            assertTrue(
                "the SDK's decode failure is the cause: ${failure?.cause}",
                failure?.cause is SerializationException,
            )
            assertEquals(listOf(REFRESH_GRANT), servers.routes())
            assertEquals(emptyList<Any>(), waiter.waits)
            assertEquals("refresh-1", store.session?.refreshToken)
            assertTrue(client.currentState() is ReaderSessionState.SignedIn)
            client.close()
        }

    @Test
    fun `after an unreadable refresh the next refresh that works replaces the session`() = runTest {
        servers.queue(REFRESH_GRANT, portal, { json(sessionJson("access-2", "refresh-2")) })
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        val store = InMemorySessionStore(inMargin())
        val client = client(store)

        runCatching { client.capabilities() }
        client.capabilities()

        assertEquals(listOf(REFRESH_GRANT, REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertEquals("refresh-1", servers.requests[1].body.substringAfter("\"refresh_token\":\"").substringBefore('"'))
        assertEquals("refresh-2", store.session?.refreshToken)
        client.close()
    }

    @Test
    fun `a cancelled refresh is not wrapped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val refreshing = Arrivals(1)
        servers.on(REFRESH_GRANT, gated(gate, refreshing) { json(sessionJson("access-2", "refresh-2")) })
        val store = InMemorySessionStore(inMargin())
        val client = client(store)
        val seen = CompletableDeferred<Throwable>()

        val call = launch {
            try {
                client.capabilities()
            } catch (t: Throwable) {
                seen.complete(t)
                throw t
            }
        }
        refreshing.await()
        call.cancel()
        call.join()
        gate.complete(Unit)

        val failure = seen.await()
        assertTrue("$failure", failure is CancellationException)
        assertFalse("$failure", failure is ReaderAuthException)
        assertEquals("refresh-1", store.session?.refreshToken)
        client.close()
    }

    // ---- Sign-in -----------------------------------------------------------------------------

    /**
     * A code verification is not in this list: the SDK reads `verify` leniently
     * and reports an unreadable 200 as "verified without a session", which the
     * module already maps (to `ProviderRejected` `no_session`).
     */
    @Test
    fun `a password change answered by a captive portal throws unexpected response and keeps the session`() = runTest {
        servers.on(USER, portal)
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
        val client = client(store)

        val failure = runCatching { client.setPassword("a much better password") }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.UnexpectedResponse)
        assertEquals(listOf(USER), servers.routes())
        assertEquals("refresh-1", store.session?.refreshToken)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.close()
    }

    @Test
    fun `a password sign-in answered by a captive portal throws unexpected response and saves nothing`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson()) }
        servers.on(PASSWORD_GRANT, portal)
        val store = InMemorySessionStore()
        val client = client(store)

        val failure = runCatching {
            client.signInWithPassword("reader@example.test", "correct horse")
        }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.UnexpectedResponse)
        assertEquals(listOf(PRE_AUTH, PASSWORD_GRANT), servers.routes())
        assertNull(store.session)
        assertTrue(store.saves.isEmpty())
        client.close()
    }

    // ---- reader-api bodies -------------------------------------------------------------------

    @Test
    fun `a 503 whose code is an object still follows the status policy`() = runTest {
        val body = """{"code":{"nested":"proxy"},"message":["upstream"],"request_id":null,"retryable":true}"""
        servers.on(CAPABILITIES) { json(body, HttpStatusCode.ServiceUnavailable, "X-Request-ID" to "req-proxy") }
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
        val client = client(store)

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        failure as ReaderAuthException.TryLater
        assertEquals(503, failure.status)
        assertNull(failure.code)
        assertEquals("req-proxy", failure.requestId)
        assertEquals(listOf(CAPABILITIES, CAPABILITIES), servers.routes())
        assertEquals("access-1", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `a 500 whose fields are objects is an api error with the body's text`() = runTest {
        val body = """{"code":["x"],"message":{"text":"boom"}}"""
        servers.on(CAPABILITIES) { json(body, HttpStatusCode.InternalServerError) }
        val client = client(InMemorySessionStore(session(expiresAt = clock.expiring(3600))))

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        failure as ReaderAuthException.ApiError
        assertEquals(500, failure.status)
        assertNull(failure.code)
        assertEquals(body, failure.description)
        client.close()
    }

    @Test
    fun `a pre-auth document with a wrongly typed field is an api error and is not kept`() = runTest {
        val wrong = preAuthJson().replace(
            """"accountEntry":{"availability":"available","reason":"available","retryable":false}""",
            """"accountEntry":["available"]""",
        )
        assertTrue("the fixture changed", wrong != preAuthJson())
        servers.queue(PRE_AUTH, {
            json(wrong, HttpStatusCode.OK, "X-Request-ID" to "req-pre")
        }, { json(preAuthJson()) })
        val client = client(InMemorySessionStore())

        val failure = runCatching { client.bootstrap() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        failure as ReaderAuthException.ApiError
        assertEquals(200, failure.status)
        assertNull(failure.code)
        assertEquals("req-pre", failure.requestId)

        client.bootstrap()
        assertEquals("the bad document was not kept", listOf(PRE_AUTH, PRE_AUTH), servers.routes())
        client.close()
    }
}
