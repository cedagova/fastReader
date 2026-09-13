package com.cedagova.reader.auth

import io.github.jan.supabase.auth.exception.AuthErrorCode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The named constants of the client contract (`CONTRACT.md`). Each is pinned
 * by `ReaderAuthPolicyTest`, which also reads the document, so changing one
 * without the other fails the build.
 */
object ReaderAuthPolicy {

    /** The `X-Reader-Client` value reader-api projects the Android contract for. */
    const val CLIENT_ID: String = "reader-android"

    /** The Android contract version line reader-api currently serves (supported major 1). */
    const val CLIENT_VERSION: String = "1.0.0"

    /**
     * A session is refreshed before a protected call, and on return to the
     * foreground, when `exp - now <= REFRESH_MARGIN`. 300 s exceeds reader-api's
     * 120 s verifier leeway and any plausible device skew; with a 3600 s access
     * token the refresh lands at 55 minutes at the latest.
     */
    val REFRESH_MARGIN: Duration = 300.seconds

    /** Every provider and reader-api request times out after this long. */
    val REQUEST_TIMEOUT: Duration = 10.seconds

    /** The wait before the one retry when the server sends no `Retry-After`. */
    val DEFAULT_RETRY_AFTER: Duration = 10.seconds

    /** A retryable failure is retried exactly this many times, never in a loop. */
    const val RETRY_LIMIT: Int = 1

    /**
     * Provider refresh failures that mean the session is gone and the device
     * must sign in again. Every other refresh failure keeps the stored session.
     */
    val SESSION_CLEARING_REFRESH_CODES: Set<AuthErrorCode> = setOf(
        AuthErrorCode.RefreshTokenAlreadyUsed,
        AuthErrorCode.RefreshTokenNotFound,
        AuthErrorCode.SessionNotFound,
        AuthErrorCode.SessionExpired,
        AuthErrorCode.BadJwt,
    )

    /** The one reader-api 401 code that means "refresh and retry once" rather than "signed out". */
    const val EXPIRED_TOKEN_CODE: String = "auth.expired_token"

    /** The reader-api 502 code that is retried once after `Retry-After`. */
    const val JWKS_DEPENDENCY_FAILED_CODE: String = "auth.jwks_dependency_failed"

    /** Whether a session whose access token expires at [expiresAt] must be refreshed at [now]. */
    fun needsRefresh(expiresAt: Instant, now: Instant): Boolean = expiresAt - now <= REFRESH_MARGIN

    /**
     * The wait before the one retry: the server's integer-seconds `Retry-After`
     * when present and positive, [DEFAULT_RETRY_AFTER] otherwise.
     */
    fun retryAfter(header: String?): Duration =
        header?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.seconds ?: DEFAULT_RETRY_AFTER
}
