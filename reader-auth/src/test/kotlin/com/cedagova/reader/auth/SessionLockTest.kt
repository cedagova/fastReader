package com.cedagova.reader.auth

import io.github.jan.supabase.auth.user.UserSession
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153: every path that writes the stored session — the 401 clear and
 * sign-in, beside refresh and sign-out — goes through the refresh mutex, so a
 * refresh in flight can neither undo a clear nor overwrite a sign-in, and a
 * stale rejection cannot wipe a newer session. #179 adds setPassword, whose
 * provider call saves the session again.
 *
 * The races are pinned with gates, not scheduling: a refresh grant parks in
 * the fake provider until the test opens its gate, and the test waits on
 * signals the fake servers raise when a request actually arrives.
 */
class SessionLockTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()
    private val servers = FakeServers().apply { on(PRE_AUTH) { json(preAuthJson()) } }

    private suspend fun clientWith(session: UserSession): Pair<ReaderAuthClient, InMemorySessionStore> {
        val store = InMemorySessionStore(session)
        val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
        client.awaitReady()
        return client to store
    }

    private fun capabilityBearers() = servers.requestsTo("/v1/reader/capabilities").map { it.bearer }

    @Test
    fun `a 401 clear during an in-flight refresh waits for it and ends signed out`() = runTest {
        val refreshEntered = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val rejectionServed = CompletableDeferred<Unit>()
        // Caller A: its token has expired server-side, so it refreshes; the grant parks.
        servers.queue(
            GENERIC_POST,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) },
            { json("{}") },
        )
        servers.on(REFRESH_GRANT) {
            refreshEntered.complete(Unit)
            refreshGate.await()
            json(sessionJson("access-2", "refresh-2"))
        }
        // Caller B: reader-api rejects the session outright — the old token, then the refreshed one.
        servers.queue(
            CAPABILITIES,
            { json(apiError("auth.invalid_token"), HttpStatusCode.Unauthorized).also { rejectionServed.complete(Unit) } },
            { json(apiError("auth.invalid_token"), HttpStatusCode.Unauthorized) },
        )
        val (client, store) = clientWith(session(expiresAt = clock.expiring(3000)))

        val a = async { client.api.post(GENERIC_PATH, JsonObject(emptyMap())) }
        refreshEntered.await()
        val b = async { runCatching { client.capabilities() } }
        rejectionServed.await()
        // B holds its 401 while A's refresh is still in flight; only now does the refresh finish.
        refreshGate.complete(Unit)
        a.await()
        val outcome = b.await().exceptionOrNull()

        assertTrue("B is signed out, got $outcome", outcome is ReaderAuthException.SignedOut)
        // B's clear waited for the refresh, found its token already replaced, and
        // retried with the replacement; that rejection cleared the session for good.
        assertEquals(listOf("access-1", "access-2"), capabilityBearers())
        assertEquals(1, servers.requestsTo("/auth/v1/token").size)
        assertNull(store.session)
        assertEquals(ReaderSessionState.SignedOut, client.currentState())
        client.close()
    }

    @Test
    fun `a stale-token 401 after a refresh keeps the new session`() = runTest {
        val staleEntered = CompletableDeferred<Unit>()
        val staleGate = CompletableDeferred<Unit>()
        // Caller B sends the old token; its 401 is held until after A's refresh has landed.
        servers.queue(
            CAPABILITIES,
            {
                staleEntered.complete(Unit)
                staleGate.await()
                json(apiError("auth.invalid_token"), HttpStatusCode.Unauthorized)
            },
            { json(CAPABILITIES_BODY) },
        )
        servers.queue(
            GENERIC_POST,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) },
            { json("{}") },
        )
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(3000)))

        val b = async { runCatching { client.capabilities() } }
        staleEntered.await()
        client.api.post(GENERIC_PATH, JsonObject(emptyMap()))
        assertEquals("access-2", store.session?.accessToken)
        staleGate.complete(Unit)
        b.await().getOrThrow()

        assertEquals(listOf("access-1", "access-2"), capabilityBearers())
        assertEquals("access-2", store.session?.accessToken)
        assertEquals("refresh-2", store.session?.refreshToken)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.close()
    }

    @Test
    fun `a sign-in during an in-flight refresh keeps the sign-in`() = runTest {
        val refreshEntered = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        var refreshDoneAtSignIn: Boolean? = null
        servers.on(REFRESH_GRANT) {
            refreshEntered.complete(Unit)
            refreshGate.await()
            json(sessionJson("access-2", "refresh-2"))
        }
        servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        servers.on(PASSWORD_GRANT) {
            refreshDoneAtSignIn = refreshGate.isCompleted
            json(sessionJson("access-pw", "refresh-pw", userId = "user-2", email = "other@example.test"))
        }
        val (client, store) = clientWith(session(expiresAt = clock.expiring(120)))
        client.bootstrap()

        val refreshing = async { client.capabilities() }
        refreshEntered.await()
        val signingIn = async { client.signInWithPassword("other@example.test", "correct horse battery staple") }
        // Everything up to the refresh mutex runs on the test thread: sign-in is now
        // parked behind the refresh (on the fixed code) rather than at the provider.
        runCurrent()
        refreshGate.complete(Unit)
        refreshing.await()
        val state = signingIn.await()

        assertEquals("the password grant waited for the refresh", true, refreshDoneAtSignIn)
        assertEquals("user-2", state.userId)
        assertEquals("access-pw", store.session?.accessToken)
        assertEquals("refresh-pw", store.session?.refreshToken)
        assertEquals("user-2", (client.currentState() as ReaderSessionState.SignedIn).userId)
        client.close()
    }

    @Test
    fun `a password change during an in-flight refresh waits for it and keeps the rotated token`() = runTest {
        val refreshEntered = Arrivals(1)
        val refreshGate = CompletableDeferred<Unit>()
        // Caller A: reader-api says its token expired, so it refreshes; the grant parks.
        servers.queue(
            GENERIC_POST,
            { json(apiError("auth.expired_token"), HttpStatusCode.Unauthorized) },
            { json("{}") },
        )
        servers.queue(
            REFRESH_GRANT,
            gated(refreshGate, entered = refreshEntered) { json(sessionJson("access-2", "refresh-2")) },
            { json(sessionJson("access-3", "refresh-3")) },
        )
        servers.on(USER) { json("""{"id":"user-1","aud":"authenticated","email":"reader@example.test"}""") }
        // The session is fresh, so setPassword itself has no reason to refresh.
        val (client, store) = clientWith(session(expiresAt = clock.expiring(3600)))

        val refreshing = async { client.api.post(GENERIC_PATH, JsonObject(emptyMap())) }
        // A holds the refresh mutex once its grant is on the wire.
        refreshEntered.await()
        val changing = async { client.setPassword("new correct horse battery staple") }
        // Everything up to the refresh mutex runs on the test thread: the password
        // change is now parked behind the refresh (fixed code) or has already built
        // its request with the old session (#179).
        runCurrent()
        refreshGate.complete(Unit)
        refreshing.await()
        changing.await()

        assertEquals("the password change waited for the refresh", listOf("access-2"), servers.requestsTo("/auth/v1/user").map { it.bearer })
        assertEquals("refresh-2", store.session?.refreshToken)

        // The next refresh sends the rotated token, not the spent one.
        clock.advance(3600.seconds)
        client.onForeground()
        val grants = servers.requestsTo("/auth/v1/token").map { Json.parseToJsonElement(it.body).jsonObject["refresh_token"]?.jsonPrimitive?.content }
        assertEquals(listOf("refresh-1", "refresh-2"), grants)
        assertEquals("refresh-3", store.session?.refreshToken)
        client.close()
    }
}
