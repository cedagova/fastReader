package com.cedagova.fastreader.account

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderSessionState
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account surface's state model (#100, REQ-401 to REQ-407) against the
 * scripted gateway: each form calls exactly its library operation once, each
 * `ReaderAuthException` branch lands as its own outcome with the codes and
 * request id the library reported, a session is never shown as signed out
 * before it has been read, and nothing is ever invoked twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAccountControllerTest {

    private val gateway = FakeReaderAccountGateway()
    private val email = "reader@example.test"

    private fun TestScope.controller(gateway: ReaderAccountGateway? = this@ReaderAccountControllerTest.gateway) =
        ReaderAccountController(gateway, ReaderAccountConfiguration.PROPERTY_KEYS, backgroundScope)

    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    // --- The three states -----------------------------------------------------

    @Test
    fun `a controller without a gateway is not configured and names every missing value`() = runUnconfined {
        val controller = controller(gateway = null)

        assertEquals(ReaderAccountState.NotConfigured(ReaderAccountConfiguration.PROPERTY_KEYS), controller.state.value)
        controller.requestEmailCode(email, newAccount = true)
        controller.loadCapabilities()
        assertEquals("nothing is called when not configured", emptyList<String>(), gateway.calls)
        assertEquals(ReaderAccountState.NotConfigured(ReaderAccountConfiguration.PROPERTY_KEYS), controller.state.value)
    }

    @Test
    fun `the session is loading until it has been read, never signed out for a frame`() = runUnconfined {
        val stored = FakeReaderAccountGateway(
            initial = ReaderSessionState.SignedIn("user-9", "kept@example.test", Instant.fromEpochSeconds(0) + 1.hours),
        )
        val wait = CompletableDeferred<Unit>()
        val slowToRead = object : ReaderAccountGateway by stored {
            override suspend fun awaitReady() = wait.await()
        }

        val controller = controller(slowToRead)

        assertEquals(ReaderAccountState.Loading, controller.state.value)
        wait.complete(Unit)
        assertEquals(ReaderAccountState.SignedIn(userId = "user-9", email = "kept@example.test"), controller.state.value)
    }

    @Test
    fun `an initializing library state is still loading`() = runUnconfined {
        val controller = controller(FakeReaderAccountGateway(initial = ReaderSessionState.Initializing))

        assertEquals(ReaderAccountState.Loading, controller.state.value)
    }

    @Test
    fun `a signed-out device shows the sign-in forms`() = runUnconfined {
        assertEquals(ReaderAccountState.SignedOut(), controller().state.value)
    }

    // --- REQ-402: every contract method reachable, each exactly one call -------

    @Test
    fun `send code with the new-account choice requests a sign-up code`() = runUnconfined {
        val controller = controller()

        controller.requestEmailCode(email, newAccount = true)

        assertEquals(listOf("requestEmailCode($email, createUser=true)"), gateway.calls)
        assertEquals(ReaderAccountState.SignedOut(outcome = AccountOutcome.CodeSent), controller.state.value)
    }

    @Test
    fun `send code without it requests a sign-in code`() = runUnconfined {
        controller().requestEmailCode(email, newAccount = false)

        assertEquals(listOf("requestEmailCode($email, createUser=false)"), gateway.calls)
    }

    @Test
    fun `verify code exchanges the code and the surface reads signed in as the address`() = runUnconfined {
        val controller = controller()

        controller.verifyEmailCode(email, "123456")

        assertEquals(listOf("verifyEmailCode($email, 123456)"), gateway.calls)
        assertEquals(ReaderAccountState.SignedIn(userId = "user-1", email = email), controller.state.value)
    }

    @Test
    fun `password sign-in calls exactly the password grant`() = runUnconfined {
        val controller = controller()

        controller.signInWithPassword(email, "hunter2!")

        assertEquals(listOf("signInWithPassword($email, ********)"), gateway.calls)
        assertTrue(controller.state.value is ReaderAccountState.SignedIn)
    }

    @Test
    fun `recovery is a code request, a code verification, then a new password`() = runUnconfined {
        val controller = controller()

        controller.requestRecoveryCode(email)
        assertEquals(ReaderAccountState.SignedOut(outcome = AccountOutcome.RecoveryCodeSent), controller.state.value)
        controller.verifyRecoveryCode(email, "654321")
        assertTrue(controller.state.value is ReaderAccountState.SignedIn)
        controller.setPassword("new-secret")

        assertEquals(
            listOf("requestRecoveryCode($email)", "verifyRecoveryCode($email, 654321)", "setPassword(**********)"),
            gateway.calls,
        )
        assertEquals(AccountOutcome.PasswordSet, controller.state.value.outcome)
    }

    // --- REQ-407: capabilities with the request id; Refresh re-fetches --------

    @Test
    fun `show capabilities carries the whole document and the request id of the call`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")

        controller.loadCapabilities()

        val state = controller.state.value as ReaderAccountState.SignedIn
        val loaded = requireNotNull(state.capabilities)
        assertEquals("0f1e2d3c-4b5a-4697-8877-665544332211", loaded.requestId)
        assertTrue(loaded.document.contains("\"schemaVersion\": \"reader.capabilities.v1\""))
        assertTrue(loaded.document.contains("\"sync\": \"unavailable\""))
        assertTrue("pretty-printed, one field per line", loaded.document.lines().size > 5)
        assertNull(state.outcome)
    }

    @Test
    fun `refresh fetches again and shows the new request id`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")
        controller.loadCapabilities()
        gateway.capabilitiesResponse = gateway.capabilitiesResponse.copy(requestId = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")

        controller.loadCapabilities()

        assertEquals(2, gateway.calls.count { it == "capabilities()" })
        assertEquals("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", (controller.state.value as ReaderAccountState.SignedIn).capabilities?.requestId)
    }

    @Test
    fun `a failed capabilities call drops the document and shows the backend's code and request id`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")
        controller.loadCapabilities()
        gateway.nextFailure = ReaderAuthException.ApiError(500, "internal", "req-500", "boom")

        controller.loadCapabilities()

        val state = controller.state.value as ReaderAccountState.SignedIn
        assertNull(state.capabilities)
        assertEquals(AccountOutcome.ApiError(500, "internal", "req-500", "boom"), state.outcome)
    }

    // --- REQ-404: distinct outcomes, one per library branch, no retry ---------

    @Test
    fun `a provider rejection shows the provider's code and is not retried`() = runUnconfined {
        val controller = controller()
        gateway.nextFailure = ReaderAuthException.ProviderRejected(400, "invalid_credentials", "Invalid login credentials")

        controller.signInWithPassword(email, "wrong")

        assertEquals(1, gateway.calls.size)
        assertEquals(
            ReaderAccountState.SignedOut(outcome = AccountOutcome.ProviderRejected(400, "invalid_credentials", "Invalid login credentials")),
            controller.state.value,
        )
    }

    @Test
    fun `every library branch lands as its own outcome`() {
        assertEquals(AccountOutcome.NetworkUnavailable, ReaderAuthException.NetworkUnavailable(java.io.IOException("x")).toOutcome())
        assertEquals(AccountOutcome.ConfigurationMismatch("client.applicationId is x"), ReaderAuthException.ConfigurationMismatch("client.applicationId is x").toOutcome())
        assertEquals(AccountOutcome.TryLater(429, "over_request_rate_limit", 30, "r1"), ReaderAuthException.TryLater(429, "over_request_rate_limit", 30.seconds, "r1").toOutcome())
        assertEquals(AccountOutcome.TryLater(503, null, null, null), ReaderAuthException.TryLater(503, null, null).toOutcome())
        assertEquals(AccountOutcome.SessionGone("auth.invalid_token", "r2"), ReaderAuthException.SignedOut("auth.invalid_token", "r2").toOutcome())
        assertEquals(AccountOutcome.Forbidden("forbidden", "r3"), ReaderAuthException.Forbidden("forbidden", "r3").toOutcome())
        assertEquals(AccountOutcome.ProviderRejected(422, "weak_password", "too short"), ReaderAuthException.ProviderRejected(422, "weak_password", "too short").toOutcome())
        assertEquals(AccountOutcome.ApiError(404, "not_found", "r4", "gone"), ReaderAuthException.ApiError(404, "not_found", "r4", "gone").toOutcome())
        assertEquals(AccountOutcome.ConfigurationMismatch("reader-auth is not configured"), ReaderAuthException.NotConfigured().toOutcome())
    }

    @Test
    fun `network unavailable keeps the session and the document`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")
        gateway.nextFailure = ReaderAuthException.NetworkUnavailable(java.io.IOException("offline"))

        controller.signOutOtherDevices()

        val state = controller.state.value as ReaderAccountState.SignedIn
        assertEquals(AccountOutcome.NetworkUnavailable, state.outcome)
        assertEquals(email, state.email)
    }

    @Test
    fun `a session the server rejected reads signed out with the reason, and the document goes with it`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")
        controller.loadCapabilities()
        gateway.nextFailure = ReaderAuthException.SignedOut("auth.invalid_token", "req-401")

        controller.loadCapabilities()

        assertEquals(
            ReaderAccountState.SignedOut(outcome = AccountOutcome.SessionGone("auth.invalid_token", "req-401")),
            controller.state.value,
        )
        controller.dismissOutcome()
        assertEquals("shown once, then dismissed", ReaderAccountState.SignedOut(), controller.state.value)
    }

    // --- REQ-406: the two sign-outs ------------------------------------------

    @Test
    fun `sign out clears the session on this device and drops the document`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")
        controller.loadCapabilities()

        controller.signOut()

        assertEquals(ReaderAccountState.SignedOut(outcome = AccountOutcome.SignedOutLocally), controller.state.value)
        assertTrue(gateway.calls.last() == "signOut()")
    }

    @Test
    fun `sign out other devices keeps this device signed in`() = runUnconfined {
        val controller = controller()
        controller.verifyEmailCode(email, "123456")

        controller.signOutOtherDevices()

        val state = controller.state.value as ReaderAccountState.SignedIn
        assertEquals(AccountOutcome.OtherDevicesSignedOut, state.outcome)
        assertEquals(email, state.email)
    }

    // --- One operation at a time; nothing invoked twice ------------------------

    @Test
    fun `while an operation is in flight the surface says so and a second tap is refused`() = runUnconfined {
        val controller = controller()
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate

        controller.requestEmailCode(email, newAccount = false)
        assertEquals(ReaderAccountState.SignedOut(activity = AccountActivity.REQUESTING_CODE), controller.state.value)
        controller.requestEmailCode(email, newAccount = false)
        controller.signInWithPassword(email, "x")
        gate.complete(Unit)

        assertEquals("exactly one call reached the library", listOf("requestEmailCode($email, createUser=false)"), gateway.calls)
        assertEquals(ReaderAccountState.SignedOut(outcome = AccountOutcome.CodeSent), controller.state.value)
    }

    @Test
    fun `a new operation clears the previous outcome`() = runUnconfined {
        val controller = controller()
        gateway.nextFailure = ReaderAuthException.ProviderRejected(400, "otp_expired", "expired")
        controller.verifyEmailCode(email, "000000")
        assertTrue(controller.state.value.outcome is AccountOutcome.ProviderRejected)

        controller.requestEmailCode(email, newAccount = false)

        assertEquals(AccountOutcome.CodeSent, controller.state.value.outcome)
    }
}
