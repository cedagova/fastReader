package com.cedagova.reader.auth

import com.cedagova.reader.auth.api.ReaderApiClient
import com.cedagova.reader.auth.api.ReaderProfileUpdate
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT.md, "reader-api call policy": the headers on every request and
 * the exact request sequence for each 401/403/429/502 branch.
 */
class ReaderApiPolicyTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()
    private val servers = FakeServers()
    private val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
    private var requestIdCounter = 0
    private val requestIds = listOf("0f1e2d3c-4b5a-4697-8877-665544332211", "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e")

    private suspend fun client(): ReaderAuthClient {
        val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter) { requestIds[requestIdCounter++ % requestIds.size] }
        client.awaitReady()
        return client
    }

    @Test
    fun `every request carries the contract's headers and clientVersion where required`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson()) }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        servers.on(PROFILE) { json("""{"contract_version":"reader.v1","request_id":"r","profile":{"created_at":"2026-09-13T00:00:00Z","updated_at":"2026-09-13T00:00:00Z"}}""") }
        val client = client()

        client.bootstrap()
        client.capabilities()
        client.upsertProfile(ReaderProfileUpdate(displayName = "Reader"))

        assertEquals(listOf(PRE_AUTH, CAPABILITIES, PROFILE), servers.routes())
        servers.requests.forEachIndexed { index, request ->
            assertEquals("api.test", request.host)
            assertEquals("reader-android", request.headers["X-Reader-Client"])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(requestIds[index], request.headers["X-Request-ID"])
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(request.headers["X-Request-ID"]!!))
        }
        assertNull("pre-auth is public", servers.requests[0].bearer)
        assertEquals("access-1", servers.requests[1].bearer)
        assertEquals("access-1", servers.requests[2].bearer)
        assertEquals("", servers.requests[2].query)
        assertEquals("""{"display_name":"Reader"}""", servers.requests[2].body)
        assertTrue(servers.requests[2].headers["Content-Type"]!!.startsWith("application/json"))
        client.close()
    }

    /**
     * The one additive accessor #100 asked for: a successful call reports the
     * `X-Request-ID` it sent — the same id reader-api echoes — beside the
     * document, and it is the id of the *successful* attempt, not of an earlier
     * one that was refreshed or waited on. Nothing about the request changes.
     */
    @Test
    fun `a successful capabilities call reports the request id it carried`() = runTest {
        servers.queue(
            CAPABILITIES,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) },
            { request -> json(CAPABILITIES_BODY, HttpStatusCode.OK, "X-Request-ID" to request.headers["X-Request-ID"]!!) },
        )
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        val client = client()

        val response = client.capabilitiesResponse()

        assertEquals("reader.capabilities.v1", response.document["schemaVersion"]!!.toString().trim('"'))
        assertEquals(listOf(CAPABILITIES, REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertEquals(requestIds[0], servers.requests[0].headers["X-Request-ID"])
        assertEquals(requestIds[1], servers.requests[2].headers["X-Request-ID"])
        assertEquals("the id of the attempt that succeeded", requestIds[1], response.requestId)
        client.close()
    }

    @Test
    fun `401 expired_token triggers one refresh and one retry`() = runTest {
        servers.queue(
            CAPABILITIES,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized, "WWW-Authenticate" to "Bearer") },
            { json(CAPABILITIES_BODY) },
        )
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        val client = client()

        val document = client.capabilities()

        assertEquals("reader.capabilities.v1", document["schemaVersion"]!!.toString().trim('"'))
        assertEquals(listOf(CAPABILITIES, REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertEquals("access-1", servers.requests[0].bearer)
        assertEquals("access-2", servers.requests[2].bearer)
        assertEquals("access-2", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `a second expired_token after the refresh is surfaced, not retried, and keeps the session`() = runTest {
        servers.on(CAPABILITIES) { json(apiError("auth.expired_token", "req-9"), HttpStatusCode.Unauthorized) }
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        val client = client()

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        assertEquals("auth.expired_token", (failure as ReaderAuthException.ApiError).code)
        assertEquals("req-9", failure.requestId)
        assertEquals(listOf(CAPABILITIES, REFRESH_GRANT, CAPABILITIES), servers.routes())
        assertNotNull(store.session)
        client.close()
    }

    @Test
    fun `concurrent expired_token rejections share one refresh`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var capabilitiesCalls = 0
        servers.on(CAPABILITIES) { request ->
            capabilitiesCalls += 1
            if (request.bearer == "access-1") json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) else json(CAPABILITIES_BODY)
        }
        servers.on(REFRESH_GRANT, gated(gate) { json(sessionJson("access-2", "refresh-2")) })
        val client = client()

        val callers = (1..4).map { async { client.capabilities() } }
        repeat(50) { yield() }
        gate.complete(Unit)
        callers.awaitAll()

        assertEquals(1, servers.requestsTo("/auth/v1/token").size)
        assertEquals(8, capabilitiesCalls)
        client.close()
    }

    @Test
    fun `any other 401 auth code clears the session with no retry`() = runTest {
        for (code in listOf("auth.invalid_token", "auth.anonymous_identity_rejected", "auth.malformed_token", "auth.missing_sub_claim", "auth.unauthorized")) {
            val servers = FakeServers()
            servers.on(CAPABILITIES) { json(apiError(code, "req-$code"), HttpStatusCode.Unauthorized) }
            val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
            val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
            client.awaitReady()

            val failure = runCatching { client.capabilities() }.exceptionOrNull()

            assertTrue("$code -> $failure", failure is ReaderAuthException.SignedOut)
            assertEquals(code, (failure as ReaderAuthException.SignedOut).code)
            assertEquals("req-$code", failure.requestId)
            assertEquals(listOf(CAPABILITIES), servers.routes())
            assertNull("$code left a session", store.session)
            assertEquals(ReaderSessionState.SignedOut, client.currentState())
            client.close()
        }
    }

    @Test
    fun `403 is surfaced with the session intact`() = runTest {
        servers.on(CAPABILITIES) { json(apiError("auth.forbidden", "req-403"), HttpStatusCode.Forbidden) }
        val client = client()

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.Forbidden)
        assertEquals("auth.forbidden", (failure as ReaderAuthException.Forbidden).code)
        assertEquals("req-403", failure.requestId)
        assertEquals(listOf(CAPABILITIES), servers.routes())
        assertNotNull(store.session)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.close()
    }

    @Test
    fun `429 is retried once after Retry-After and then surfaced`() = runTest {
        servers.queue(
            CAPABILITIES,
            { json(apiError("rate_limit.exceeded", retryable = true), HttpStatusCode.TooManyRequests, "Retry-After" to "3") },
            { json(CAPABILITIES_BODY) },
        )
        val client = client()

        client.capabilities()
        assertEquals(listOf(CAPABILITIES, CAPABILITIES), servers.routes())
        assertEquals(listOf(3.seconds), waiter.waits)

        val exhausted = FakeServers()
        exhausted.on(CAPABILITIES) { json(apiError("rate_limit.exceeded", "req-429", retryable = true), HttpStatusCode.TooManyRequests) }
        val other = ReaderAuthClient.build(testConfig, store, exhausted.engine, clock, waiter)
        other.awaitReady()
        val failure = runCatching { other.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(429, (failure as ReaderAuthException.TryLater).status)
        assertEquals(10.seconds, failure.retryAfter)
        assertEquals("req-429", failure.requestId)
        assertEquals(2, exhausted.requests.size)
        assertEquals(listOf(3.seconds, 10.seconds), waiter.waits)
        assertNotNull(store.session)
        client.close()
        other.close()
    }

    @Test
    fun `502 jwks_dependency_failed is retried once and other 502s are not`() = runTest {
        servers.queue(
            CAPABILITIES,
            { json(apiError("auth.jwks_dependency_failed", retryable = true), HttpStatusCode.BadGateway) },
            { json(CAPABILITIES_BODY) },
        )
        val client = client()

        client.capabilities()
        assertEquals(listOf(CAPABILITIES, CAPABILITIES), servers.routes())
        assertEquals(listOf(10.seconds), waiter.waits)

        val plain = FakeServers()
        plain.on(CAPABILITIES) { json(apiError("db.unavailable", "req-502", retryable = true), HttpStatusCode.BadGateway) }
        val other = ReaderAuthClient.build(testConfig, store, plain.engine, clock, waiter)
        other.awaitReady()
        val failure = runCatching { other.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        assertEquals("db.unavailable", (failure as ReaderAuthException.ApiError).code)
        assertEquals("req-502", failure.requestId)
        assertEquals(1, plain.requests.size)
        assertNotNull(store.session)
        client.close()
        other.close()
    }

    @Test
    fun `a network failure on a protected call clears nothing`() = runTest {
        servers.on(CAPABILITIES) { networkFailure() }
        val client = client()

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.NetworkUnavailable)
        assertEquals(listOf(CAPABILITIES), servers.routes())
        assertNotNull(store.session)
        client.close()
    }

    /**
     * The one verb #112 added. It is [ReaderApiClient.put] with a different
     * method, so the proof is that a `POST` carries the same headers, the same
     * bearer, the same JSON content type and the caller's body verbatim — and
     * that it walks the same refresh-and-retry branch on a 401 expiry, which is
     * the only way to show the policy is shared rather than re-implemented.
     */
    @Test
    fun `a POST carries the same headers, body and refresh policy as every other verb`() = runTest {
        val body = buildJsonObject { put("mutations", JsonArray(emptyList())) }
        servers.queue(
            GENERIC_POST,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) },
            { json("""{"ok":true}""") },
        )
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        val client = client()

        val document = client.api.post(GENERIC_PATH, body)

        assertEquals(JsonPrimitive(true), document["ok"])
        assertEquals(listOf(GENERIC_POST, REFRESH_GRANT, GENERIC_POST), servers.routes())
        val attempts = servers.requestsTo(GENERIC_PATH)
        assertEquals(2, attempts.size)
        attempts.forEach { attempt ->
            assertEquals("POST", attempt.method)
            assertEquals("api.test", attempt.host)
            assertEquals("reader-android", attempt.headers["X-Reader-Client"])
            assertEquals("application/json", attempt.headers["Accept"])
            assertTrue(attempt.headers["Content-Type"]!!.startsWith("application/json"))
            assertEquals(body.toString(), attempt.body)
            assertEquals("", attempt.query)
        }
        assertEquals("access-1", attempts[0].bearer)
        assertEquals("access-2", attempts[1].bearer)
        client.close()
    }

    /** The same verb's failures are the same branches: nothing about `POST` is special. */
    @Test
    fun `a POST maps 403 and a network failure to the existing branches`() = runTest {
        servers.on(GENERIC_POST) { json(apiError("reader.forbidden", "req-403"), HttpStatusCode.Forbidden) }
        val client = client()

        val forbidden = runCatching { client.api.post(GENERIC_PATH, JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue("$forbidden", forbidden is ReaderAuthException.Forbidden)
        assertEquals("req-403", (forbidden as ReaderAuthException.Forbidden).requestId)
        assertNotNull(store.session)

        servers.on(GENERIC_POST) { networkFailure() }
        val offline = runCatching { client.api.post(GENERIC_PATH, JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue("$offline", offline is ReaderAuthException.NetworkUnavailable)
        assertNotNull(store.session)
        client.close()
    }

    @Test
    fun `a protected call while signed out is refused without a request`() = runTest {
        val empty = InMemorySessionStore()
        val client = ReaderAuthClient.build(testConfig, empty, servers.engine, clock, waiter)
        client.awaitReady()

        val failure = runCatching { client.capabilities() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.SignedOut)
        assertTrue(servers.requests.isEmpty())
        client.close()
    }
}
