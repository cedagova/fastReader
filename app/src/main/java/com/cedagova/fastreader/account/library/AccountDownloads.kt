package com.cedagova.fastreader.account.library

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.downloads.AssetDownloadException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bringing an account book onto the shelf, and taking its bytes back off it
 * (LEAF812 of #104: REQ-510, REQ-516's copy rule, D2, D4).
 *
 * [AccountBookCopies] already knows how to fetch, verify and place a copy.
 * This is the part the reader touches: which book is being downloaded, how far
 * it has got, what to say when it will not come, and — the one rule that
 * matters — *when* the book may be opened.
 *
 * ## Open never starts reading before verification succeeds
 *
 * [opened] is written in exactly one place, the [CopyOutcome.Ready] branch of
 * [download], and [CopyOutcome.Ready] is only ever produced after
 * `AccountCopyStore.place` has compared the digest it computed itself against
 * the account's identity for the book and renamed the file into place. Every
 * other outcome writes a [BookDownloadState.Refused] and nothing else, so
 * there is no path from a failed, short, tampered or refused download to a
 * reader. That is not a comment about the order of two statements: the id a
 * caller would need in order to open the book only exists inside that branch.
 *
 * ## The account never sees a removal
 *
 * [removeCopy] is D2's **Remove downloaded copy**: the file goes and the
 * catalog's `ACCOUNT_COPY` source goes with it, so the row falls back to being
 * an account-only row. No `library_item` mutation is produced, nothing is
 * queued, and the book is still in the account on every other device.
 *
 * ## Sign-out (D4)
 *
 * A download in flight belongs to a grant that account owns, so signing out
 * cancels it; the copies already on the device are catalog rows and are not
 * this class's to touch. Nothing here deletes a byte on sign-out.
 */
class AccountDownloads(
    private val copies: AccountBookCopies,
    /** The account library the shelf renders; the source of the book to download. */
    private val accountState: StateFlow<AccountLibraryState>,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(AccountDownloadsState.NONE)

    /** Every download in flight or refused, as the shelf reads it. */
    val state: StateFlow<AccountDownloadsState> = _state.asStateFlow()

    private val _opened = MutableStateFlow<String?>(null)

    /**
     * The catalog id of a copy that has just been verified and placed, waiting
     * for its host to open it. Null at every other moment, including while a
     * download is running and after every failure.
     */
    val opened: StateFlow<String?> = _opened.asStateFlow()

    /** One job per account book, so two downloads never share a cancellation. */
    private val jobs = mutableMapOf<String, Job>()

    init {
        scope.launch {
            accountState.collect { account ->
                if (account.phase == AccountSyncPhase.SIGNED_OUT) cancelEverything()
            }
        }
    }

    /**
     * Downloads [accountBookId]'s bytes and, when they verify, opens the book.
     *
     * Idempotent while one is running: a second tap on a row that is already
     * downloading is ignored rather than starting a second transfer against a
     * second grant.
     */
    fun open(accountBookId: String) {
        scope.launch {
            val book = accountState.value.books.firstOrNull { it.bookId == accountBookId } ?: return@launch
            val started = mutex.withLock {
                // The guard is the published state, not the job map, and under
                // the same lock as the write that sets it: the job is only
                // recorded once `launch` has returned, so a second tap in that
                // window would find no job and start a second grant.
                if (_state.value.byAccountBookId[accountBookId] is BookDownloadState.Downloading) {
                    return@withLock false
                }
                _state.update { it.with(accountBookId, BookDownloadState.Downloading()) }
                true
            }
            if (!started) return@launch
            val job = scope.launch { download(book) }
            mutex.withLock { jobs[accountBookId] = job }
            job.join()
            mutex.withLock { if (jobs[accountBookId] === job) jobs.remove(accountBookId) }
        }
    }

    /** Stops a download. Nothing was placed, so the row is exactly as it was. */
    fun cancel(accountBookId: String) {
        scope.launch {
            val job = mutex.withLock {
                _state.update { it.without(accountBookId) }
                jobs.remove(accountBookId)
            }
            job?.cancel()
        }
    }

    /** Puts away a refusal that has been read. Sends nothing and downloads nothing. */
    fun dismiss(accountBookId: String) {
        scope.launch { mutex.withLock { _state.update { it.without(accountBookId) } } }
    }

    /**
     * Frees this device's copy of the book with that content identity (D2).
     *
     * The account is untouched: this is the one removal in the app that costs
     * bytes and nothing else.
     */
    fun removeCopy(contentSha256: String) {
        scope.launch { copies.remove(contentSha256) }
    }

    /** The host has opened [deviceBookId]; stop offering to. */
    fun opened(deviceBookId: String) {
        _opened.compareAndSet(deviceBookId, null)
    }

    private suspend fun download(book: AccountBook) {
        val outcome = copies.download(book) { received, total ->
            _state.update { it.with(book.bookId, BookDownloadState.Downloading(received, total)) }
        }
        if (outcome is CopyOutcome.Ready) {
            // The only write of [_opened] in this class, and it is unreachable
            // except through a placement that has already verified the digest.
            _state.update { it.without(book.bookId) }
            _opened.value = outcome.bookId
            return
        }
        _state.update { it.with(book.bookId, outcome.refusal()) }
    }

    private suspend fun cancelEverything() {
        val running = mutex.withLock {
            _state.value = AccountDownloadsState.NONE
            val taken = jobs.values.toList()
            jobs.clear()
            taken
        }
        running.forEach(Job::cancel)
    }
}

/** Every download the shelf is showing, keyed by the account's own book id. */
data class AccountDownloadsState(
    val byAccountBookId: Map<String, BookDownloadState> = emptyMap(),
) {

    internal fun with(bookId: String, state: BookDownloadState): AccountDownloadsState =
        copy(byAccountBookId = byAccountBookId + (bookId to state))

    internal fun without(bookId: String): AccountDownloadsState =
        if (bookId in byAccountBookId) copy(byAccountBookId = byAccountBookId - bookId) else this

    companion object {
        val NONE: AccountDownloadsState = AccountDownloadsState()
    }
}

/**
 * Where one account book's download has got to, as the row shows it.
 *
 * There is deliberately no `Ready` member. A finished download is not a state
 * of the row: the copy has become a device source, so the row is an ordinary
 * book with a cover, a position and a **Remove downloaded copy** action, and
 * anything left here would be a second, stale answer to the same question.
 */
sealed interface BookDownloadState {

    /** The bytes are arriving. [total] is the grant's own declared length, or 0. */
    data class Downloading(val received: Long = 0, val total: Long = 0) : BookDownloadState {

        /** Determinate progress, or null while the grant declared no length. */
        val fraction: Float? get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /**
     * Nothing was downloaded, and the book is still in the account (the
     * definition's "the book stays listed").
     *
     * [code] and [requestId] are the backend's own words when there are any,
     * carried through untouched for the same reason an [AccountSyncError]'s
     * are: the code is what finds a server log.
     */
    data class Refused(
        val problem: DownloadProblem,
        val code: String? = null,
        val requestId: String? = null,
        /** Whether trying the same book again could plausibly work. */
        val retryable: Boolean = false,
    ) : BookDownloadState
}

/**
 * Why a download did not produce a copy.
 *
 * One member per sentence the shelf says, and each is a statement about a
 * different thing: the asset, this device, the network, the backend, or the
 * book itself. "Something went wrong" is not one of them.
 */
enum class DownloadProblem {

    /** The bytes that arrived are not the book the account holds (REQ-510). */
    TAMPERED,

    /** There is no room on this device for the copy. */
    NO_STORAGE,

    /** No network. A later attempt with one will work. */
    OFFLINE,

    /** The backend would not give the book out, with its own code. */
    REFUSED,

    /** The bytes did not arrive. A later attempt may work. */
    FAILED,

    /**
     * The storage provider sent the download to another origin, and FastReader
     * would not send the book's signed grant there (#142). The same grant
     * would meet the same redirect, so this is not offered again.
     */
    REDIRECTED,

    /** The bytes are the account's, exactly, and are not an EPUB this reader opens. */
    UNREADABLE,

    /** There was nothing to ask for: no account build, no asset, or no identity. */
    UNAVAILABLE,
}

/** The sentence one outcome is. [CopyOutcome.Ready] never reaches this. */
private fun CopyOutcome.refusal(): BookDownloadState.Refused = when (this) {
    is CopyOutcome.Ready -> error("a placed copy is not a refusal")

    // Not retryable on purpose: the same asset would produce the same bytes,
    // and offering "Try again" would invite the reader to keep asking for a
    // file the account's own digest says is not the book.
    is CopyOutcome.Tampered -> BookDownloadState.Refused(DownloadProblem.TAMPERED)

    is CopyOutcome.NoStorage -> BookDownloadState.Refused(DownloadProblem.NO_STORAGE, retryable = true)

    is CopyOutcome.DownloadFailed ->
        if (generateSequence(error) { it.cause }.any { it is AssetDownloadException.ForeignRedirect }) {
            BookDownloadState.Refused(DownloadProblem.REDIRECTED)
        } else {
            BookDownloadState.Refused(DownloadProblem.FAILED, retryable = true)
        }

    is CopyOutcome.Unreadable -> BookDownloadState.Refused(DownloadProblem.UNREADABLE)

    is CopyOutcome.Unavailable -> BookDownloadState.Refused(DownloadProblem.UNAVAILABLE)

    is CopyOutcome.GrantFailed -> when (val e = error) {
        is ReaderAuthException.NetworkUnavailable ->
            BookDownloadState.Refused(DownloadProblem.OFFLINE, retryable = true)

        is ReaderAuthException.TryLater ->
            BookDownloadState.Refused(
                problem = DownloadProblem.REFUSED,
                code = e.code ?: "HTTP ${e.status}",
                requestId = e.requestId,
                retryable = true,
            )

        is ReaderAuthException.Forbidden ->
            BookDownloadState.Refused(DownloadProblem.REFUSED, code = e.code ?: "403", requestId = e.requestId)

        is ReaderAuthException.SignedOut ->
            BookDownloadState.Refused(DownloadProblem.REFUSED, code = e.code, requestId = e.requestId)

        is ReaderAuthException.ApiError ->
            BookDownloadState.Refused(
                problem = DownloadProblem.REFUSED,
                code = e.code ?: "HTTP ${e.status}",
                requestId = e.requestId,
            )

        else -> BookDownloadState.Refused(DownloadProblem.REFUSED)
    }
}
