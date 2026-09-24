package com.cedagova.reader.auth.session

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SDK's persistence seam, pointed at the module's [SessionStore] so the
 * provider SDK never touches its plaintext `SharedPreferences` default
 * (CONTRACT.md, "Provider SDK defaults this module overrides").
 *
 * A failed save does not throw into the SDK (#154). The SDK makes a session
 * current only after [saveSession] returns, so a throwing save would leave
 * the *previous* session in memory — after a refresh, one whose refresh token
 * the provider has already consumed, and the next refresh with it signs the
 * device out. Instead the failure is recorded, the SDK keeps the new session
 * for this process, and the caller that caused the save reports the failure
 * with [takeSaveFailure]. The SDK also re-saves the session it loads at
 * start-up; a failure there is recorded too and discarded by the next caller,
 * which is why the stored copy is left alone here.
 *
 * One slot is enough because every caller that saves — refresh, sign-in and
 * setPassword — does so under the refresh mutex, so no two saves
 * overlap between a caller's reset and its read. Callers that save nothing
 * never reset or read it (#183), so none can take a refresh's failure.
 */
class StoreSessionManager(val store: SessionStore) : SessionManager {

    private val saveFailure = AtomicReference<Exception?>(null)

    override suspend fun saveSession(session: UserSession) {
        try {
            store.save(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveFailure.set(e)
        }
    }

    override suspend fun loadSession(): UserSession = store.load() ?: throw NoSessionFoundException()
    override suspend fun deleteSession() = store.clear()

    /** The failure of the last save since the previous call, if any; reading it resets it. */
    fun takeSaveFailure(): Exception? = saveFailure.getAndSet(null)
}
