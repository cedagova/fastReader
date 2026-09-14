package com.cedagova.fastreader.account

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderSessionState
import com.cedagova.reader.auth.api.ReaderApiResponse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The scripted gateway the state-model tests and the goldens run against: no
 * SDK, no network, no Keystore. Each operation records its call, then either
 * completes at once, throws the [ReaderAuthException] a test scripted, or —
 * when a test wants to watch the in-flight state — parks on a gate until the
 * test releases it. Sign-in operations flip [session] the way the library's
 * own state flow would.
 */
class FakeReaderAccountGateway(
    initial: ReaderSessionState = ReaderSessionState.SignedOut,
) : ReaderAccountGateway {

    val session = MutableStateFlow(initial)
    val calls = mutableListOf<String>()

    /** The failure the next operation throws, consumed once. */
    var nextFailure: ReaderAuthException? = null

    /** When set, the next operation parks here until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    /** What [capabilities] answers. */
    var capabilitiesResponse: ReaderApiResponse = ReaderApiResponse(
        document = Json.parseToJsonElement(CAPABILITIES_DOCUMENT).jsonObject,
        requestId = "0f1e2d3c-4b5a-4697-8877-665544332211",
    )

    /** Whether the stored session has been "read"; the controller waits for this. */
    val ready = CompletableDeferred<Unit>().apply { complete(Unit) }

    override val sessionState: Flow<ReaderSessionState> get() = session

    override suspend fun awaitReady() = ready.await()

    override suspend fun requestEmailCode(email: String, createUser: Boolean) =
        record("requestEmailCode($email, createUser=$createUser)")

    override suspend fun verifyEmailCode(email: String, code: String) {
        record("verifyEmailCode($email, $code)")
        signIn(email)
    }

    override suspend fun signInWithPassword(email: String, password: String) {
        record("signInWithPassword($email, ${"*".repeat(password.length)})")
        signIn(email)
    }

    override suspend fun requestRecoveryCode(email: String) = record("requestRecoveryCode($email)")

    override suspend fun verifyRecoveryCode(email: String, code: String) {
        record("verifyRecoveryCode($email, $code)")
        signIn(email)
    }

    override suspend fun setPassword(newPassword: String) = record("setPassword(${"*".repeat(newPassword.length)})")

    override suspend fun capabilities(): ReaderApiResponse {
        record("capabilities()")
        return capabilitiesResponse
    }

    override suspend fun signOut() {
        record("signOut()")
        session.value = ReaderSessionState.SignedOut
    }

    override suspend fun signOutOtherDevices() = record("signOutOtherDevices()")

    private suspend fun record(call: String) {
        calls += call
        gate?.let { it.await(); gate = null }
        nextFailure?.let {
            nextFailure = null
            if (it is ReaderAuthException.SignedOut) session.value = ReaderSessionState.SignedOut
            throw it
        }
    }

    private fun signIn(email: String) {
        session.value = ReaderSessionState.SignedIn(userId = "user-1", email = email, expiresAt = Instant.fromEpochSeconds(0) + 1.hours)
    }

    companion object {
        const val CAPABILITIES_DOCUMENT: String =
            """{"schemaVersion":"reader.capabilities.v1","generatedAt":"2026-09-13T10:00:00Z","compatibility":{"status":"compatible"},"capabilities":{"library":"available","sync":"unavailable"}}"""
    }
}
