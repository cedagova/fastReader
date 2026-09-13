package com.cedagova.reader.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import io.ktor.http.HttpHeaders
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The module's refresh policy (CONTRACT.md, "Refresh policy"): proactive with
 * a margin, one reactive refresh after a server-side expiry, and single-flight
 * everywhere. All refreshes go through one mutex; a caller that arrives while
 * a refresh is in flight waits for it and then finds the session already
 * fresh, so N concurrent callers produce exactly one provider request
 * (`RefreshPolicyTest`). That matters because the provider rotates refresh
 * tokens with a 10 s reuse window: a second concurrent refresh with the old
 * token revokes the whole family.
 *
 * No timer runs anywhere: refresh happens only on the code paths that call
 * into this class — before a protected call and on return to the foreground.
 */
internal class SessionRefresher(
    private val auth: Auth,
    private val clock: ReaderClock,
    private val waiter: RetryWaiter,
) {
    private val mutex = Mutex()

    /**
     * The session to send with a protected call, refreshed first when
     * `exp - now <= REFRESH_MARGIN`; `null` when the device is signed out.
     */
    suspend fun sessionForRequest(): UserSession? {
        val current = auth.currentSessionOrNull() ?: return null
        if (!ReaderAuthPolicy.needsRefresh(current.expiresAt, clock.now())) return current
        return mutex.withLock {
            val latest = auth.currentSessionOrNull() ?: return null
            if (ReaderAuthPolicy.needsRefresh(latest.expiresAt, clock.now())) refresh(latest) else latest
        }
    }

    /**
     * After reader-api answered 401 `auth.expired_token` for [rejectedAccessToken]:
     * refresh once, unless another caller already replaced that token, in
     * which case the replacement is returned untouched. `null` when signed out.
     */
    suspend fun refreshAfterRejection(rejectedAccessToken: String): UserSession? = mutex.withLock {
        val latest = auth.currentSessionOrNull() ?: return null
        if (latest.accessToken == rejectedAccessToken) refresh(latest) else latest
    }

    /**
     * Runs [block] while no refresh is in flight and holds off any refresh
     * until it returns. Sign-out uses it so a refresh that started a moment
     * earlier cannot re-save a session after the store was cleared.
     */
    suspend fun <T> withoutRefresh(block: suspend () -> T): T = mutex.withLock { block() }

    /** Runs under [mutex]. */
    private suspend fun refresh(session: UserSession): UserSession {
        var retried = 0
        while (true) {
            try {
                val refreshed = auth.refreshSession(session.refreshToken)
                auth.importSession(refreshed, autoRefresh = false, source = SessionSource.Refresh(session))
                return refreshed
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRestException) {
                if (e.errorCode in ReaderAuthPolicy.SESSION_CLEARING_REFRESH_CODES) {
                    auth.clearSession()
                    throw ReaderAuthException.SignedOut(e.errorCode?.value ?: e.error)
                }
                if (e.isTransient()) {
                    val retryAfter = ReaderAuthPolicy.retryAfter(e.response.headers[HttpHeaders.RetryAfter])
                    if (retried < ReaderAuthPolicy.RETRY_LIMIT) {
                        retried += 1
                        waiter.wait(retryAfter)
                        continue
                    }
                    throw ReaderAuthException.TryLater(e.statusCode, e.errorCode?.value ?: e.error, retryAfter)
                }
                throw ReaderAuthException.ProviderRejected(e.statusCode, e.errorCode?.value ?: e.error, e.errorDescription)
            } catch (e: RestException) {
                if (e.isTransient()) {
                    val retryAfter = ReaderAuthPolicy.retryAfter(e.response.headers[HttpHeaders.RetryAfter])
                    if (retried < ReaderAuthPolicy.RETRY_LIMIT) {
                        retried += 1
                        waiter.wait(retryAfter)
                        continue
                    }
                    throw ReaderAuthException.TryLater(e.statusCode, e.error, retryAfter)
                }
                throw ReaderAuthException.ProviderRejected(e.statusCode, e.error, e.description ?: e.error)
            } catch (e: IOException) {
                if (retried < ReaderAuthPolicy.RETRY_LIMIT) {
                    retried += 1
                    waiter.wait(ReaderAuthPolicy.DEFAULT_RETRY_AFTER)
                    continue
                }
                throw ReaderAuthException.NetworkUnavailable(e)
            }
        }
    }
}

/** 429 and every 5xx keep the session and earn one retry; everything else is final. */
internal fun RestException.isTransient(): Boolean = statusCode == 429 || statusCode >= 500
