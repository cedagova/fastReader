package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderSyncRejection

/**
 * What the account-library engine is doing, as the shelf reads it.
 *
 * These are the definition's states table, minus the ones that belong to a
 * later increment: signed out (explicit or session gone), bootstrapping,
 * offline, capability unavailable, and the ordinary settled state.
 */
enum class AccountSyncPhase {

    /** No account is signed in on this device (D4), or this build carries no stage values. */
    SIGNED_OUT,

    /** Signed in and up to date as far as the last trigger could tell. */
    IDLE,

    /** Reading the account's lists for the first time, or again after a cursor expiry. */
    BOOTSTRAPPING,

    /** Draining the queue and reading the change stream. */
    SYNCING,

    /**
     * Signed in, but nothing was sent: the network was unavailable. The rows
     * on screen are the last known ones and the queue is kept.
     */
    OFFLINE,

    /**
     * Signed in, but sync is deferred with a reason — `reader.sync.v1`
     * unavailable, or the backend asking for a retry later. Never a sign-out.
     */
    DEFERRED,
}

/** Why an account-library trigger ran. Recorded so a state a trigger produced can be read back. */
enum class AccountSyncTrigger {
    /** A session appeared for an account. */
    SIGN_IN,

    /** The app came to the foreground (the existing `ProcessLifecycleOwner` hook). */
    FOREGROUND,

    /** One of this device's own writes was queued. */
    OWN_WRITE,

    /** The reader asked for a refresh from the shelf. */
    MANUAL_REFRESH,
}

/**
 * The last thing that went wrong, carried verbatim from the backend.
 *
 * Every branch here is one branch of `ReaderAuthException` or one rejection
 * the sync contract declares. This app classifies nothing and invents no
 * message: the codes and request ids are the server's own, so the shelf can
 * quote them and a server log can be found from them.
 */
sealed interface AccountSyncError {

    /** The device has no usable network. The queue is kept and the next trigger retries. */
    data object NetworkUnavailable : AccountSyncError

    /** The backend asked for a retry later; [retryAfterSeconds] is its own hint when it gave one. */
    data class TryLater(
        val status: Int,
        val code: String?,
        val retryAfterSeconds: Long?,
        val requestId: String?,
    ) : AccountSyncError

    /** The session is gone; D4's signed-out state, with the backend's reason shown once. */
    data class SessionGone(val code: String?, val requestId: String?) : AccountSyncError

    /** The backend refused this account the operation. */
    data class Forbidden(val code: String?, val requestId: String?) : AccountSyncError

    /** Anything else the backend answered, including a body that does not match the pinned contract. */
    data class ApiError(
        val status: Int,
        val code: String?,
        val requestId: String?,
        val description: String?,
    ) : AccountSyncError

    /**
     * The backend refused one queued mutation outright. [code] is the
     * contract's own rejection code — surfaced, never re-interpreted.
     */
    data class Rejected(
        val resourceId: String,
        val code: String,
        val detail: String,
        val retryable: Boolean,
    ) : AccountSyncError

    /** The stored account document cannot be used, and must not be overwritten. */
    data class StoreBlocked(val message: String) : AccountSyncError

    /**
     * A `reading_progress` record arrived that this app cannot place on a book
     * (REQ-511).
     *
     * This branch exists to falsify an assumption rather than to report a
     * failure. The pinned document types a change's `resource_id` as a bare
     * string and fixes no meaning for it per resource type; that a progress
     * record's identity is the book is *derived* (see
     * [PortableProgress.recordFor]) and has never been observed against
     * stage. If the derivation is wrong, the record cannot be filed — and the
     * one thing that must not happen then is nothing. So it becomes this: the
     * resource id verbatim, the `book_id` the payload carried if it carried one,
     * and the reason, all of them the values needed to correct the mapping in
     * one file.
     *
     * It is not a sign-out, not a rejection and not retryable. The position is
     * simply not adopted, and the rest of the stream is read as normal.
     */
    data class UnrecognizedProgressRecord(
        val resourceId: String,
        val payloadBookId: String?,
        val reason: String,
    ) : AccountSyncError

    /** The build carries no stage values, so there is nothing to call. */
    data object NotConfigured : AccountSyncError
}

/**
 * The account library as the shelf builds from it (LEAF703).
 *
 * [books] excludes tombstoned rows: a removed book leaves the shelf, and the
 * tombstone exists only so an Undo can bring the row back with its metadata.
 */
data class AccountLibraryState(
    val phase: AccountSyncPhase = AccountSyncPhase.SIGNED_OUT,
    val userId: String? = null,
    val books: List<AccountBook> = emptyList(),
    /** Queued mutations not yet admitted; the shelf says so beside an offline action. */
    val queued: Int = 0,
    val lastError: AccountSyncError? = null,
    /** Why sync is deferred, when [phase] is [AccountSyncPhase.DEFERRED] for a capability. */
    val capabilityReason: ReaderCapabilityReason? = null,
    /** The trigger that produced this state, for a shelf that distinguishes a manual refresh. */
    val lastTrigger: AccountSyncTrigger? = null,
) {
    companion object {
        /** Nobody is signed in: no account rows at all (D4). */
        val SIGNED_OUT: AccountLibraryState = AccountLibraryState()
    }
}

/**
 * The branch-to-error map, total over the sealed class so a branch
 * `:reader-auth` adds later fails to compile here rather than falling into a
 * catch-all — the same bargain `ReaderAuthException.toOutcome()` makes for the
 * sign-in surface.
 */
internal fun ReaderAuthException.toSyncError(): AccountSyncError = when (this) {
    is ReaderAuthException.NotConfigured -> AccountSyncError.NotConfigured
    is ReaderAuthException.ConfigurationMismatch ->
        AccountSyncError.ApiError(status = 0, code = null, requestId = null, description = reason)

    is ReaderAuthException.NetworkUnavailable -> AccountSyncError.NetworkUnavailable
    is ReaderAuthException.TryLater ->
        AccountSyncError.TryLater(status, code, retryAfter?.inWholeSeconds, requestId)

    is ReaderAuthException.SignedOut -> AccountSyncError.SessionGone(code, requestId)
    is ReaderAuthException.Forbidden -> AccountSyncError.Forbidden(code, requestId)
    is ReaderAuthException.ProviderRejected ->
        AccountSyncError.ApiError(status = status, code = code, requestId = null, description = description)

    is ReaderAuthException.ApiError -> AccountSyncError.ApiError(status, code, requestId, description)
}

/** The rejection the contract declares, as the error the shelf shows. */
internal fun ReaderSyncRejection.toSyncError(resourceId: String): AccountSyncError.Rejected =
    AccountSyncError.Rejected(
        resourceId = resourceId,
        code = code.wireName(),
        detail = detail,
        retryable = retryable,
    )
