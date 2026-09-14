package com.cedagova.fastreader.account

import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderSessionState
import com.cedagova.reader.auth.api.ReaderApiResponse
import kotlinx.coroutines.flow.Flow

/**
 * The one seam the account surface owns: every library operation the surface
 * reaches, and nothing the library does not offer.
 *
 * It exists because `ReaderAuthClient`'s constructor is internal, so nothing
 * in this app could build a fake of it — and the state model, its unit tests
 * and the `account_*` goldens all need to run with no SDK, no network and no
 * Keystore. Production is [LibraryReaderAccountGateway], a pass-through; the
 * tests substitute a scripted fake. Every failure is still one branch of
 * `ReaderAuthException`, thrown through unchanged: the gateway maps nothing,
 * retries nothing and classifies nothing.
 *
 * `onForeground()` is deliberately absent. It is the application object's
 * obligation (host requirement 5), called from its foreground observer on the
 * client directly, not something the surface does.
 */
interface ReaderAccountGateway {

    /** The stored session as the library reports it; emits on every change. */
    val sessionState: Flow<ReaderSessionState>

    /** Suspends until the stored session has been read, so it is never reported as signed out for a frame. */
    suspend fun awaitReady()

    suspend fun requestEmailCode(email: String, createUser: Boolean)

    suspend fun verifyEmailCode(email: String, code: String)

    suspend fun signInWithPassword(email: String, password: String)

    suspend fun requestRecoveryCode(email: String)

    suspend fun verifyRecoveryCode(email: String, code: String)

    suspend fun setPassword(newPassword: String)

    suspend fun capabilities(): ReaderApiResponse

    suspend fun signOut()

    suspend fun signOutOtherDevices()
}

/**
 * The production gateway: a thin pass-through over the one [ReaderAuthClient]
 * the application owns. Each method is exactly one library call.
 *
 * `verifyEmailCode` always verifies with the library's default purpose, the
 * provider's `email` type, which the contract says accepts a sign-in *and* a
 * sign-up code; the "new account" choice governs only whether the code request
 * may create the address. `upsertProfile` is not here: `PUT /v1/reader/profile`
 * is a #100 non-goal, so no path in this app reaches it.
 */
class LibraryReaderAccountGateway(private val client: ReaderAuthClient) : ReaderAccountGateway {

    override val sessionState: Flow<ReaderSessionState> get() = client.sessionState

    override suspend fun awaitReady() = client.awaitReady()

    override suspend fun requestEmailCode(email: String, createUser: Boolean) =
        client.requestEmailCode(email, createUser)

    override suspend fun verifyEmailCode(email: String, code: String) {
        client.verifyEmailCode(email, code)
    }

    override suspend fun signInWithPassword(email: String, password: String) {
        client.signInWithPassword(email, password)
    }

    override suspend fun requestRecoveryCode(email: String) = client.requestRecoveryCode(email)

    override suspend fun verifyRecoveryCode(email: String, code: String) {
        client.verifyRecoveryCode(email, code)
    }

    override suspend fun setPassword(newPassword: String) = client.setPassword(newPassword)

    override suspend fun capabilities(): ReaderApiResponse = client.capabilitiesResponse()

    override suspend fun signOut() = client.signOut()

    override suspend fun signOutOtherDevices() = client.signOutOtherDevices()
}
