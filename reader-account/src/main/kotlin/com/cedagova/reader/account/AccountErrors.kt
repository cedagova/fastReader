package com.cedagova.reader.account

import com.cedagova.reader.account.library.BookDownloadState
import com.cedagova.reader.account.library.BookImportState
import com.cedagova.reader.account.library.DownloadProblem
import com.cedagova.reader.account.library.ImportProblem
import com.cedagova.reader.auth.ReaderAuthException

// The one place this module turns a `ReaderAuthException` into what a reader is
// shown (#200, A197-F003).
//
// [toOutcome] is the only `when` over the exception's branches here: the sign-in
// surface shows its result directly, and the download and import rows are
// projections of that same result ([toDownloadRefusal], [toImportRefusal]) rather
// than three competing readings of the exception. Each projection keeps the row
// states these surfaces showed before the mapping was centralised; the tests of
// each surface hold them.
//
// The sync engine's own state (`AccountSyncError`) is mapped inside
// `:reader-library`, which this module depends on and which cannot depend back
// on it.

/**
 * The branch-to-outcome map, total over the sealed class so a branch the
 * library adds later fails to compile here rather than falling into a
 * catch-all. `NotConfigured` cannot reach a controller that has a gateway —
 * the host builds one only for a configured client — but the map stays total;
 * it is reported as a mismatch naming the library's own message.
 */
internal fun ReaderAuthException.toOutcome(): AccountOutcome.Failure = when (this) {
    is ReaderAuthException.NotConfigured -> AccountOutcome.ConfigurationMismatch(message.orEmpty())
    is ReaderAuthException.ConfigurationMismatch -> AccountOutcome.ConfigurationMismatch(reason)
    is ReaderAuthException.SignInUnavailable -> AccountOutcome.SignInUnavailable(reason)
    is ReaderAuthException.NetworkUnavailable -> AccountOutcome.NetworkUnavailable
    is ReaderAuthException.TryLater -> AccountOutcome.TryLater(status, code, retryAfter?.inWholeSeconds, requestId)
    is ReaderAuthException.SignedOut -> AccountOutcome.SessionGone(code, requestId)
    is ReaderAuthException.Forbidden -> AccountOutcome.Forbidden(code, requestId)
    is ReaderAuthException.ProviderRejected -> AccountOutcome.ProviderRejected(status, code, description)
    is ReaderAuthException.ApiError -> AccountOutcome.ApiError(status, code, requestId, description)
    is ReaderAuthException.StorageUnavailable -> AccountOutcome.StorageUnavailable
    is ReaderAuthException.UnexpectedResponse -> AccountOutcome.UnexpectedResponse
}

/**
 * A download grant that reader-api refused, as the row shows it: the backend's
 * code and request id where there are any, and whether trying again could work.
 */
internal fun AccountOutcome.Failure.toDownloadRefusal(): BookDownloadState.Refused = when (this) {
    AccountOutcome.NetworkUnavailable ->
        BookDownloadState.Refused(DownloadProblem.OFFLINE, retryable = true)

    is AccountOutcome.TryLater ->
        BookDownloadState.Refused(
            problem = DownloadProblem.REFUSED,
            code = code ?: "HTTP $status",
            requestId = requestId,
            retryable = true,
        )

    is AccountOutcome.Forbidden ->
        BookDownloadState.Refused(DownloadProblem.REFUSED, code = code ?: "403", requestId = requestId)

    is AccountOutcome.SessionGone ->
        BookDownloadState.Refused(DownloadProblem.REFUSED, code = code, requestId = requestId)

    is AccountOutcome.ApiError ->
        BookDownloadState.Refused(
            problem = DownloadProblem.REFUSED,
            code = code ?: "HTTP $status",
            requestId = requestId,
        )

    is AccountOutcome.ConfigurationMismatch, is AccountOutcome.SignInUnavailable,
    is AccountOutcome.ProviderRejected, AccountOutcome.StorageUnavailable, AccountOutcome.UnexpectedResponse,
    -> BookDownloadState.Refused(DownloadProblem.REFUSED)
}

/** An import call that failed, as the row shows it. Nothing is invented. */
internal fun AccountOutcome.Failure.toImportRefusal(): BookImportState.Refused = when (this) {
    AccountOutcome.NetworkUnavailable ->
        BookImportState.Refused(ImportProblem.NeedsConnection)

    is AccountOutcome.SessionGone ->
        BookImportState.Refused(ImportProblem.Api(null), code = code, requestId = requestId)

    is AccountOutcome.Forbidden ->
        BookImportState.Refused(ImportProblem.Api(null), code = code, requestId = requestId)

    is AccountOutcome.TryLater ->
        BookImportState.Refused(ImportProblem.Api(status), code = code, requestId = requestId)

    is AccountOutcome.ApiError ->
        BookImportState.Refused(ImportProblem.Api(status), code = code, requestId = requestId)

    is AccountOutcome.ProviderRejected ->
        BookImportState.Refused(ImportProblem.Api(status), code = code)

    // `ReaderAuthException.NotConfigured` arrives here as a ConfigurationMismatch.
    is AccountOutcome.ConfigurationMismatch, is AccountOutcome.SignInUnavailable ->
        BookImportState.Refused(ImportProblem.Api(null), retryable = false)

    // The session is kept in memory, so the same import succeeds when repeated (#154).
    AccountOutcome.StorageUnavailable ->
        BookImportState.Refused(ImportProblem.Api(null), code = "storage_unavailable")

    // The provider's answer could not be read; the session is intact, so repeating the import may succeed (#191).
    AccountOutcome.UnexpectedResponse ->
        BookImportState.Refused(ImportProblem.Api(null), code = "unexpected_response")
}
