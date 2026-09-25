package com.cedagova.reader.auth

import com.cedagova.reader.auth.api.PreAuthDocument
import com.cedagova.reader.auth.api.ReaderApiResponse
import com.cedagova.reader.auth.api.ReaderProfileUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * What a host calls on the Reader authentication module (#199, A197-F002): the
 * session state, the sign-in methods, the protected calls and sign-out.
 *
 * [ReaderAuthClient] is the one production implementation, and a host holds
 * this interface wherever it wants to substitute the module in its own tests —
 * the test fixtures of this module ship a scripted double
 * (`com.cedagova.reader.auth.testing.FakeReaderAuthOperations`) and a real
 * client over a mock server (`ReaderAuthHarness`), so a host writes neither a
 * wrapper nor a fake of its own. Each member's contract is stated on
 * [ReaderAuthClient]; every failure is one branch of [ReaderAuthException].
 *
 * Wiring and lifecycle stay on the class, not here: [ReaderAuthClient.config],
 * [ReaderAuthClient.api] and [ReaderAuthClient.close] belong to whoever built
 * the client.
 */
public interface ReaderAuthOperations {

    /** The stored session as a host renders it; emits on every change. */
    public val sessionState: Flow<ReaderSessionState>

    /** The current [sessionState] value without collecting. */
    public fun currentState(): ReaderSessionState

    /** Suspends until the stored session has been read (or found absent). */
    public suspend fun awaitReady()

    /** `GET /v1/reader/pre-auth` and the fail-closed checks; see [ReaderAuthClient.bootstrap]. */
    public suspend fun bootstrap(): PreAuthDocument

    /** Email code, step one; see [ReaderAuthClient.requestEmailCode]. */
    public suspend fun requestEmailCode(email: String, createUser: Boolean)

    /** Email code, step two; see [ReaderAuthClient.verifyEmailCode]. */
    public suspend fun verifyEmailCode(
        email: String,
        code: String,
        purpose: ReaderAuthClient.EmailCodePurpose = ReaderAuthClient.EmailCodePurpose.SIGN_IN,
    ): ReaderSessionState.SignedIn

    /** Password sign-in. */
    public suspend fun signInWithPassword(email: String, password: String): ReaderSessionState.SignedIn

    /** Code-based recovery, step one. */
    public suspend fun requestRecoveryCode(email: String)

    /** Code-based recovery, step two; then [setPassword]. */
    public suspend fun verifyRecoveryCode(email: String, code: String): ReaderSessionState.SignedIn

    /** Sets the signed-in user's password; see [ReaderAuthClient.setPassword]. */
    public suspend fun setPassword(newPassword: String)

    /** The first authenticated call after sign-in. */
    public suspend fun capabilities(): JsonObject

    /** [capabilities], with the request id the successful call carried beside the document. */
    public suspend fun capabilitiesResponse(): ReaderApiResponse

    /** The profile upsert that precedes any profile read. */
    public suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject

    /** The foreground refresh; never throws; see [ReaderAuthClient.onForeground]. */
    public suspend fun onForeground(): ReaderSessionState

    /** Local sign-out; see [ReaderAuthClient.signOut]. */
    public suspend fun signOut()

    /** Revokes every other device's session; this device stays signed in. */
    public suspend fun signOutOtherDevices()
}
