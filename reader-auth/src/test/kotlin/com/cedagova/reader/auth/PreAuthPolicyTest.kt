package com.cedagova.reader.auth

import com.cedagova.reader.auth.api.PreAuthDocument
import com.cedagova.reader.auth.api.SignInMethod
import com.cedagova.reader.auth.testing.FakeClock
import com.cedagova.reader.auth.testing.FakeServers
import com.cedagova.reader.auth.testing.InMemorySessionStore
import com.cedagova.reader.auth.testing.OTP
import com.cedagova.reader.auth.testing.PASSWORD_GRANT
import com.cedagova.reader.auth.testing.PRE_AUTH
import com.cedagova.reader.auth.testing.RecordingWaiter
import com.cedagova.reader.auth.testing.json
import com.cedagova.reader.auth.testing.networkFailure
import com.cedagova.reader.auth.testing.preAuthJson
import com.cedagova.reader.auth.testing.sessionJson
import com.cedagova.reader.auth.testing.testConfig
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT.md, "Bootstrap and first calls" (#160): the pre-auth document's
 * enabled methods, account availability and freshness bounds decide what a
 * sign-in may do, before any provider call.
 */
class PreAuthPolicyTest {

    private val servers = FakeServers()
    private val clock = FakeClock(Instant.parse("2026-09-24T12:00:00Z"))

    private suspend fun client(): ReaderAuthClient =
        ReaderAuthClient.build(testConfig, InMemorySessionStore(), servers.engine, clock, RecordingWaiter()).also {
            it.awaitReady()
        }

    private fun providerCalls() = servers.requests.filter { it.host == "provider.test" }

    private suspend inline fun <reified T : Throwable> failureOf(crossinline block: suspend () -> Unit): T {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ${T::class.simpleName}, got $failure", failure is T)
        return failure as T
    }

    // ---- Enabled methods ---------------------------------------------------------------------

    @Test
    fun `emailOtp false refuses the email code without a provider call`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson(emailOtp = false)) }
        val client = client()

        for (attempt in listOf<suspend () -> Unit>(
            { client.requestEmailCode("reader@example.test", createUser = true) },
            { client.verifyEmailCode("reader@example.test", "123456") },
        )) {
            val failure = failureOf<ReaderAuthException.SignInUnavailable> { attempt() }
            assertEquals(SignInMethod.EMAIL_CODE, failure.method)
            assertEquals(ReaderAuthClient.METHOD_DISABLED, failure.reason)
            assertFalse(failure.retryable)
        }
        assertTrue("a provider request was made: ${servers.routes()}", providerCalls().isEmpty())
        assertEquals(setOf(SignInMethod.PASSWORD), client.bootstrap().enabledMethods)
        client.close()
    }

    @Test
    fun `no password provider refuses password sign-in and recovery without a provider call`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson(enabledProviders = listOf("google"))) }
        servers.on(OTP) { json("{}") }
        val client = client()

        for (attempt in listOf<suspend () -> Unit>(
            { client.signInWithPassword("reader@example.test", "correct horse battery staple") },
            { client.requestRecoveryCode("reader@example.test") },
            { client.verifyRecoveryCode("reader@example.test", "123456") },
        )) {
            assertEquals(SignInMethod.PASSWORD, failureOf<ReaderAuthException.SignInUnavailable> { attempt() }.method)
        }
        assertTrue("a provider request was made: ${servers.routes()}", providerCalls().isEmpty())

        client.requestEmailCode("reader@example.test", createUser = false)
        assertEquals(listOf(PRE_AUTH, OTP), servers.routes())
        client.close()
    }

    // ---- Account availability ----------------------------------------------------------------

    @Test
    fun `unavailable account entry refuses sign-in with the server's reason and is fetched again`() = runTest {
        servers.queue(
            PRE_AUTH,
            { json(preAuthJson(availability = "unavailable", reason = "configuration_stale", retryable = true)) },
            { json(preAuthJson()) },
        )
        servers.on(PASSWORD_GRANT) { json(sessionJson("access-1", "refresh-1")) }
        val client = client()

        val failure = failureOf<ReaderAuthException.SignInUnavailable> {
            client.signInWithPassword("reader@example.test", "correct horse battery staple")
        }
        assertEquals("configuration_stale", failure.reason)
        assertTrue(failure.retryable)
        assertEquals(null, failure.method)
        assertTrue("a provider request was made: ${servers.routes()}", providerCalls().isEmpty())

        client.signInWithPassword("reader@example.test", "correct horse battery staple")
        assertEquals(listOf(PRE_AUTH, PRE_AUTH, PASSWORD_GRANT), servers.routes())
        client.close()
    }

    // ---- Freshness ---------------------------------------------------------------------------

    @Test
    fun `a clock past freshUntil makes the next sign-in fetch pre-auth again`() = runTest {
        servers.on(PRE_AUTH) { json(preAuthJson()) }
        servers.on(OTP) { json("{}") }
        val client = client()

        client.requestEmailCode("reader@example.test", createUser = true)
        clock.advance(59.minutes)
        client.requestEmailCode("reader@example.test", createUser = true)
        assertEquals("fresh for an hour: not fetched again", listOf(PRE_AUTH, OTP, OTP), servers.routes())

        clock.advance(2.minutes)
        client.requestEmailCode("reader@example.test", createUser = true)
        assertEquals(listOf(PRE_AUTH, OTP, OTP, PRE_AUTH, OTP), servers.routes())
        client.close()
    }

    @Test
    fun `a re-fetch that finds the method turned off refuses it`() = runTest {
        servers.queue(PRE_AUTH, { json(preAuthJson()) }, { json(preAuthJson(emailOtp = false)) })
        servers.on(OTP) { json("{}") }
        val client = client()

        client.requestEmailCode("reader@example.test", createUser = true)
        clock.advance(2.hours)
        failureOf<ReaderAuthException.SignInUnavailable> {
            client.requestEmailCode("reader@example.test", createUser = true)
        }
        assertEquals(listOf(PRE_AUTH, OTP, PRE_AUTH), servers.routes())
        client.close()
    }

    @Test
    fun `a failed re-fetch keeps the old document until staleUntil, then fails`() = runTest {
        servers.queue(PRE_AUTH, { json(preAuthJson()) }, { networkFailure() })
        servers.on(OTP) { json("{}") }
        val client = client()

        client.requestEmailCode("reader@example.test", createUser = true)
        clock.advance(90.minutes) // past freshUntil, before staleUntil
        client.requestEmailCode("reader@example.test", createUser = true)
        assertEquals(listOf(PRE_AUTH, OTP, PRE_AUTH, OTP), servers.routes())

        clock.advance(31.minutes) // past staleUntil
        failureOf<ReaderAuthException.NetworkUnavailable> {
            client.requestEmailCode("reader@example.test", createUser = true)
        }
        assertEquals(listOf(PRE_AUTH, OTP, PRE_AUTH, OTP, PRE_AUTH), servers.routes())
        client.close()
    }

    @Test
    fun `the bounds follow the device clock's offset from generatedAt, capped by validUntil`() {
        fun parse(json: String) = PreAuthDocument.parse(Json.parseToJsonElement(json) as JsonObject)
        val receivedAt = Instant.parse("2026-09-24T12:00:00Z")

        // generatedAt 2026-09-11T00:00Z, fresh +1h, stale +2h: the same spans from receipt.
        val lifetime = parse(preAuthJson()).lifetime(receivedAt)
        assertEquals(receivedAt + 1.hours, lifetime.freshUntil)
        assertEquals(receivedAt + 2.hours, lifetime.staleUntil)

        val capped = parse(preAuthJson(validUntil = "2026-09-11T00:30:00Z")).lifetime(receivedAt)
        assertEquals(receivedAt + 30.minutes, capped.freshUntil)
        assertEquals(receivedAt + 30.minutes, capped.staleUntil)

        // Unreadable bounds: usable for the fetching call only.
        val unreadable = parse(preAuthJson(freshUntil = "soon", staleUntil = "later")).lifetime(receivedAt)
        assertEquals(receivedAt, unreadable.freshUntil)
        assertEquals(receivedAt, unreadable.staleUntil)
    }
}
