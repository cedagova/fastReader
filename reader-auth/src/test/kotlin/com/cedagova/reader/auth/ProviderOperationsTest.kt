package com.cedagova.reader.auth

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One mock-provider test per in-scope operation (CONTRACT.md, "Sign-in
 * methods" and "Sign-out semantics"): the exact request the SDK sends and the
 * effect on the stored session.
 */
class ProviderOperationsTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()
    private val servers = FakeServers().apply { on(PRE_AUTH) { json(preAuthJson()) } }
    private val email = "reader@example.test"

    private suspend fun client(store: InMemorySessionStore = InMemorySessionStore()): ReaderAuthClient =
        ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter).also { it.awaitReady() }

    private fun body(request: Recorded) = Json.parseToJsonElement(request.body).jsonObject
    private fun Recorded.field(name: String) = body(this)[name]?.jsonPrimitive?.content

    @Test
    fun `email code sign-up requests a code with create_user and verifies it into a stored session`() = runTest {
        servers.on(OTP) { json("{}") }
        servers.on(VERIFY) { json(sessionJson("access-new", "refresh-new", userId = "user-new")) }
        val store = InMemorySessionStore()
        val client = client(store)

        client.requestEmailCode(email, createUser = true)
        val state = client.verifyEmailCode(email, "123456", ReaderAuthClient.EmailCodePurpose.SIGN_UP)

        assertEquals(listOf(PRE_AUTH, OTP, VERIFY), servers.routes())
        val otp = servers.requests[1]
        assertEquals(email, otp.field("email"))
        assertEquals("true", otp.field("create_user"))
        assertEquals(PUBLISHABLE_KEY, otp.headers["apikey"])
        val verify = servers.requests[2]
        assertEquals("signup", verify.field("type"))
        assertEquals("123456", verify.field("token"))
        assertEquals(email, verify.field("email"))
        assertEquals("user-new", state.userId)
        assertEquals("access-new", store.session?.accessToken)
        assertEquals("refresh-new", store.session?.refreshToken)
        assertEquals(state, client.currentState())
        client.close()
    }

    @Test
    fun `email code sign-in uses create_user false and the email type`() = runTest {
        servers.on(OTP) { json("{}") }
        servers.on(VERIFY) { json(sessionJson("access-new", "refresh-new")) }
        val client = client()

        client.requestEmailCode(email, createUser = false)
        client.verifyEmailCode(email, "654321")

        assertEquals("false", servers.requests[1].field("create_user"))
        assertEquals("email", servers.requests[2].field("type"))
        client.close()
    }

    @Test
    fun `a provider 429 on verify is try-later and is never retried`() = runTest {
        servers.on(VERIFY) { json(providerError("over_request_rate_limit"), HttpStatusCode.TooManyRequests, "Retry-After" to "30") }
        val store = InMemorySessionStore()
        val client = client(store)

        val failure = runCatching { client.verifyEmailCode(email, "123456") }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.TryLater)
        assertEquals(429, (failure as ReaderAuthException.TryLater).status)
        assertEquals(kotlin.time.Duration.parse("30s"), failure.retryAfter)
        assertEquals(1, servers.requestsTo("/auth/v1/verify").size)
        assertTrue(waiter.waits.isEmpty())
        assertNull(store.session)
        client.close()
    }

    @Test
    fun `a wrong or expired code is a provider rejection, once`() = runTest {
        servers.on(VERIFY) { json(providerError("otp_expired", "Token has expired or is invalid"), HttpStatusCode.Forbidden) }
        val client = client()

        val failure = runCatching { client.verifyEmailCode(email, "000000") }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ProviderRejected)
        assertEquals("otp_expired", (failure as ReaderAuthException.ProviderRejected).code)
        assertEquals(1, servers.requestsTo("/auth/v1/verify").size)
        assertEquals(ReaderSessionState.SignedOut, client.currentState())
        client.close()
    }

    @Test
    fun `password sign-in posts the password grant and stores the session`() = runTest {
        servers.on(PASSWORD_GRANT) { json(sessionJson("access-pw", "refresh-pw")) }
        val store = InMemorySessionStore()
        val client = client(store)

        val state = client.signInWithPassword(email, "correct horse battery staple")

        assertEquals(listOf(PRE_AUTH, PASSWORD_GRANT), servers.routes())
        assertEquals(email, servers.requests[1].field("email"))
        assertEquals("correct horse battery staple", servers.requests[1].field("password"))
        assertEquals("user-1", state.userId)
        assertEquals("access-pw", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `wrong credentials are a provider rejection with the session untouched`() = runTest {
        servers.on(PASSWORD_GRANT) { json(providerError("invalid_credentials", "Invalid login credentials"), HttpStatusCode.BadRequest) }
        val store = InMemorySessionStore()
        val client = client(store)

        val failure = runCatching { client.signInWithPassword(email, "nope") }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ProviderRejected)
        assertEquals("invalid_credentials", (failure as ReaderAuthException.ProviderRejected).code)
        assertNull(store.session)
        client.close()
    }

    @Test
    fun `recovery requests a code, verifies it, then sets the password with the new session`() = runTest {
        servers.on(RECOVER) { json("{}") }
        servers.on(VERIFY) { json(sessionJson("access-rec", "refresh-rec")) }
        servers.on(USER) { json("""{"id":"user-1","aud":"authenticated","email":"$email"}""") }
        val store = InMemorySessionStore()
        val client = client(store)

        client.requestRecoveryCode(email)
        client.verifyRecoveryCode(email, "112233")
        client.setPassword("new correct horse battery staple")

        assertEquals(listOf(PRE_AUTH, RECOVER, VERIFY, USER), servers.routes())
        assertEquals(email, servers.requests[1].field("email"))
        assertEquals("recovery", servers.requests[2].field("type"))
        assertEquals("112233", servers.requests[2].field("token"))
        assertEquals("access-rec", servers.requests[3].bearer)
        assertEquals("new correct horse battery staple", servers.requests[3].field("password"))
        assertEquals("access-rec", store.session?.accessToken)
        client.close()
    }

    @Test
    fun `a weak password is a provider rejection that names the reasons`() = runTest {
        servers.on(USER) { json("""{"error_code":"weak_password","msg":"Password is too weak","weak_password":{"reasons":["length","characters"]}}""", HttpStatusCode.UnprocessableEntity) }
        val client = client(InMemorySessionStore(session(expiresAt = clock.expiring(3600))))

        val failure = runCatching { client.setPassword("short") }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ProviderRejected)
        assertEquals("weak_password", (failure as ReaderAuthException.ProviderRejected).code)
        assertTrue(failure.description, failure.description.contains("length"))
        client.close()
    }

    @Test
    fun `sign out other devices posts the others scope and keeps this session`() = runTest {
        servers.on(LOGOUT_OTHERS) { json("{}", HttpStatusCode.NoContent) }
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
        val client = client(store)

        client.signOutOtherDevices()

        assertEquals(listOf(LOGOUT_OTHERS), servers.routes())
        assertEquals("access-1", servers.requests[0].bearer)
        assertEquals("access-1", store.session?.accessToken)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.close()
    }

    @Test
    fun `local sign-out clears the store before telling the provider`() = runTest {
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
        var storeAtLogout: Any? = "unset"
        servers.on(LOGOUT_LOCAL) { storeAtLogout = store.session; json("{}", HttpStatusCode.NoContent) }
        val client = client(store)

        client.signOut()

        assertEquals(listOf(LOGOUT_LOCAL), servers.routes())
        assertEquals("access-1", servers.requests[0].bearer)
        assertNull("the store still held a session when the provider was called", storeAtLogout)
        assertNull(store.session)
        assertEquals(ReaderSessionState.SignedOut, client.currentState())
        client.close()
    }

    @Test
    fun `a provider failure during local sign-out never leaves the device signed in`() = runTest {
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
        servers.on(LOGOUT_LOCAL) { networkFailure() }
        val client = client(store)

        client.signOut()

        assertEquals(listOf(LOGOUT_LOCAL), servers.routes())
        assertNull(store.session)
        assertEquals(ReaderSessionState.SignedOut, client.currentState())

        val rejected = FakeServers()
        rejected.on(LOGOUT_LOCAL) { json(providerError("session_not_found"), HttpStatusCode.Forbidden) }
        val other = ReaderAuthClient.build(testConfig, InMemorySessionStore(session(expiresAt = clock.expiring(3600))), rejected.engine, clock, waiter)
        other.awaitReady()
        other.signOut()
        assertEquals(ReaderSessionState.SignedOut, other.currentState())
        client.close()
        other.close()
    }

    @Test
    fun `a session-bound provider call refreshes first inside the margin`() = runTest {
        servers.on(REFRESH_GRANT) { json(sessionJson("access-2", "refresh-2")) }
        servers.on(LOGOUT_OTHERS) { json("{}", HttpStatusCode.NoContent) }
        val client = client(InMemorySessionStore(session(expiresAt = clock.expiring(60))))

        client.signOutOtherDevices()

        assertEquals(listOf(REFRESH_GRANT, LOGOUT_OTHERS), servers.routes())
        assertEquals("access-2", servers.requests[1].bearer)
        assertNotNull(client.currentState() as? ReaderSessionState.SignedIn)
        client.close()
    }
}

class SignOutOrderingTest {

    private val clock = FakeClock()
    private val waiter = RecordingWaiter()

    @Test
    fun `sign-out waits for a refresh in flight and the refreshed session is not re-saved`() = kotlinx.coroutines.test.runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val servers = FakeServers()
        val refreshing = Arrivals(1)
        servers.on(REFRESH_GRANT, gated(gate, refreshing) { json(sessionJson("access-2", "refresh-2")) })
        val bearerAtLogout = java.util.concurrent.atomic.AtomicReference<String?>()
        servers.on(LOGOUT_LOCAL) { bearerAtLogout.set(it.bearer); json("{}", HttpStatusCode.NoContent) }
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(60)))
        val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
        client.awaitReady()

        val foreground = async { client.onForeground() }
        // The foreground refresh is at the provider, so it holds the refresh lock.
        refreshing.await()
        val signOut = async { client.signOut() }
        testScheduler.runCurrent()
        assertTrue("sign-out must not run while the refresh is in flight", servers.requestsTo("/auth/v1/logout").isEmpty())
        gate.complete(Unit)
        foreground.await()
        signOut.await()

        assertEquals(listOf(REFRESH_GRANT, LOGOUT_LOCAL), servers.routes())
        assertEquals("access-2", bearerAtLogout.get())
        assertNull("the refreshed session was re-saved after sign-out", store.session)
        assertEquals(ReaderSessionState.SignedOut, client.currentState())
        client.close()
    }

    @Test
    fun `a 401 on the public pre-auth route never signs the device out`() = kotlinx.coroutines.test.runTest {
        val servers = FakeServers()
        servers.on(PRE_AUTH) { json(apiError("auth.unauthorized", "req-pre"), HttpStatusCode.Unauthorized) }
        val store = InMemorySessionStore(session(expiresAt = clock.expiring(3600)))
        val client = ReaderAuthClient.build(testConfig, store, servers.engine, clock, waiter)
        client.awaitReady()

        val failure = runCatching { client.bootstrap() }.exceptionOrNull()

        assertTrue("$failure", failure is ReaderAuthException.ApiError)
        assertEquals("auth.unauthorized", (failure as ReaderAuthException.ApiError).code)
        assertEquals(listOf(PRE_AUTH), servers.routes())
        assertNotNull(store.session)
        assertTrue(client.currentState() is ReaderSessionState.SignedIn)
        client.close()
    }
}
