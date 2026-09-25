package com.cedagova.reader.auth.testing

import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderAuthOperations
import com.cedagova.reader.auth.ReaderSessionState
import com.cedagova.reader.auth.api.PreAuthDocument
import com.cedagova.reader.auth.api.ReaderApiResponse
import com.cedagova.reader.auth.api.ReaderProfileUpdate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A scripted [ReaderAuthOperations] for a host's state-model tests and
 * screenshots (#199, A197-F002): no provider SDK, no network, no Keystore.
 *
 * Each operation records its call in [calls] (a password as asterisks), then
 * completes at once, throws the [ReaderAuthException] a test put in
 * [nextFailure], or — when a test wants to watch the in-flight state — parks
 * on [gate] until the test completes it. Sign-in operations flip [session] the
 * way the module's own state flow does; [signOut], and a scripted
 * [ReaderAuthException.SignedOut], flip it back. [awaitReady] waits for
 * [ready], complete unless a test passes its own.
 */
public class FakeReaderAuthOperations(
    initial: ReaderSessionState = ReaderSessionState.SignedOut,
    public val ready: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : ReaderAuthOperations {

    /** The session state the fake reports; a test may set it directly. */
    public val session: MutableStateFlow<ReaderSessionState> = MutableStateFlow(initial)

    /** Every operation called, in order. */
    public val calls: MutableList<String> = mutableListOf()

    /** The failure the next operation throws, consumed once. */
    public var nextFailure: ReaderAuthException? = null

    /** When set, the next operation parks here until it completes. */
    public var gate: CompletableDeferred<Unit>? = null

    /** What [capabilitiesResponse] and [capabilities] answer. */
    public var capabilitiesResponse: ReaderApiResponse = ReaderApiResponse(
        document = Json.parseToJsonElement(CAPABILITIES_DOCUMENT).jsonObject,
        requestId = CAPABILITIES_REQUEST_ID,
    )

    /** What [bootstrap] answers. */
    public var preAuth: PreAuthDocument = PreAuthDocument()

    override val sessionState: Flow<ReaderSessionState> get() = session

    override fun currentState(): ReaderSessionState = session.value

    override suspend fun awaitReady(): Unit = ready.await()

    override suspend fun bootstrap(): PreAuthDocument {
        record("bootstrap()")
        return preAuth
    }

    override suspend fun requestEmailCode(email: String, createUser: Boolean): Unit =
        record("requestEmailCode($email, createUser=$createUser)")

    override suspend fun verifyEmailCode(
        email: String,
        code: String,
        purpose: ReaderAuthClient.EmailCodePurpose,
    ): ReaderSessionState.SignedIn {
        record("verifyEmailCode($email, $code)")
        return signIn(email)
    }

    override suspend fun signInWithPassword(email: String, password: String): ReaderSessionState.SignedIn {
        record("signInWithPassword($email, ${"*".repeat(password.length)})")
        return signIn(email)
    }

    override suspend fun requestRecoveryCode(email: String): Unit = record("requestRecoveryCode($email)")

    override suspend fun verifyRecoveryCode(email: String, code: String): ReaderSessionState.SignedIn {
        record("verifyRecoveryCode($email, $code)")
        return signIn(email)
    }

    override suspend fun setPassword(newPassword: String): Unit =
        record("setPassword(${"*".repeat(newPassword.length)})")

    override suspend fun capabilities(): JsonObject = capabilitiesResponse().document

    override suspend fun capabilitiesResponse(): ReaderApiResponse {
        record("capabilities()")
        return capabilitiesResponse
    }

    override suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject {
        record("upsertProfile()")
        return JsonObject(emptyMap())
    }

    override suspend fun onForeground(): ReaderSessionState {
        calls += "onForeground()"
        return session.value
    }

    override suspend fun signOut() {
        record("signOut()")
        session.value = ReaderSessionState.SignedOut
    }

    override suspend fun signOutOtherDevices(): Unit = record("signOutOtherDevices()")

    private suspend fun record(call: String) {
        calls += call
        gate?.let {
            it.await()
            gate = null
        }
        nextFailure?.let {
            nextFailure = null
            if (it is ReaderAuthException.SignedOut) session.value = ReaderSessionState.SignedOut
            throw it
        }
    }

    private fun signIn(email: String): ReaderSessionState.SignedIn = ReaderSessionState.SignedIn(
        userId = "user-1",
        email = email,
        expiresAt = Instant.fromEpochSeconds(0) + 1.hours,
    ).also { session.value = it }

    public companion object {
        /** The capabilities document [capabilitiesResponse] answers by default. */
        public const val CAPABILITIES_DOCUMENT: String =
            """{"schemaVersion":"reader.capabilities.v1","generatedAt":"2026-09-13T10:00:00Z","compatibility":{"status":"compatible"},"capabilities":{"library":"available","sync":"unavailable"}}"""

        /** The request id [capabilitiesResponse] answers with by default. */
        public const val CAPABILITIES_REQUEST_ID: String = "0f1e2d3c-4b5a-4697-8877-665544332211"
    }
}
