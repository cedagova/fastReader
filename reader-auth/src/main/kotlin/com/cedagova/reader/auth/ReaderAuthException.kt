package com.cedagova.reader.auth

import kotlin.time.Duration

/**
 * Every failure the module surfaces to a host, as one closed set so a host can
 * render each without parsing messages. Which branch a server or provider
 * answer lands in is fixed by `CONTRACT.md`; `ReaderApiPolicyTest` and
 * `ProviderOperationsTest` pin the mapping.
 */
sealed class ReaderAuthException(message: String) : Exception(message) {

    /** The host passed a [ReaderAuthConfig] with a blank service value; nothing was called. */
    class NotConfigured : ReaderAuthException("reader-auth is not configured")

    /**
     * The reader-api pre-auth document does not describe this client's
     * configuration (wrong application id, publishable key, or authority), so
     * sign-in was refused before any provider call.
     */
    class ConfigurationMismatch(val reason: String) :
        ReaderAuthException("pre-auth does not match this client: $reason")

    /** The network was unreachable or the request timed out; nothing was cleared. */
    class NetworkUnavailable(cause: Throwable) :
        ReaderAuthException("network unavailable: ${cause.javaClass.simpleName}") {
        init { initCause(cause) }
    }

    /**
     * The server asked for a later retry: the provider answered 429 or 5xx, or
     * reader-api answered 429, 502 `auth.jwks_dependency_failed`, or a 5xx
     * whose body says `retryable: true` (a 5xx that says `retryable: false` is
     * an [ApiError]). Thrown after the one permitted retry, or at once when
     * [retryAfter] exceeds `ReaderAuthPolicy.MAX_INLINE_RETRY_AFTER`, in which
     * case it carries the server's value. The session is intact; the user
     * should try later. Never a credential error.
     */
    class TryLater(val status: Int, val code: String?, val retryAfter: Duration?, val requestId: String? = null) :
        ReaderAuthException("try later: HTTP $status${code?.let { " $it" } ?: ""}")

    /**
     * There is no usable session. It has two meanings, and [code] tells them
     * apart:
     *
     * - **The server rejected the session** ([code] is the server's code): any
     *   reader-api 401 `auth.*` other than an expiry, or a provider refresh
     *   failure that names a revoked or missing session — the module cleared
     *   the session — or a 401 expiry after the session was already gone, so
     *   there was nothing left to refresh.
     * - **No session existed to send** ([code] `null`): the call was made while
     *   signed out, so no request reached the server.
     *
     * Either way the host must show the sign-in screen.
     */
    class SignedOut(val code: String?, val requestId: String? = null) :
        ReaderAuthException("signed out by the server${code?.let { ": $it" } ?: ""}")

    /**
     * The provider issued a session (a sign-in or a refresh succeeded) but this
     * device could not save it: the Keystore or the session file failed. The
     * session is kept in memory for this process, so the device stays signed
     * in and repeating the operation that needed it works without a new
     * provider call. After a refresh the stored copy, which holds the consumed
     * refresh token, is removed, so the next process start is signed out.
     * Never retried: the provider has already rotated the refresh token.
     */
    class StorageUnavailable(cause: Throwable) :
        ReaderAuthException("the session could not be saved on this device: ${cause.javaClass.simpleName}") {
        init { initCause(cause) }
    }

    /** reader-api 403: the caller is authenticated but not allowed; the session is intact. */
    class Forbidden(val code: String?, val requestId: String?) :
        ReaderAuthException("forbidden${code?.let { ": $it" } ?: ""}")

    /** The identity provider rejected the operation itself (wrong code, bad password, weak password, ...). */
    class ProviderRejected(val status: Int, val code: String?, val description: String) :
        ReaderAuthException("provider rejected (HTTP $status${code?.let { " $it" } ?: ""}): $description")

    /** Any other reader-api error, surfaced with the server's `code` and `request_id`. */
    class ApiError(val status: Int, val code: String?, val requestId: String?, val description: String) :
        ReaderAuthException("reader-api HTTP $status${code?.let { " $it" } ?: ""}: $description")
}
