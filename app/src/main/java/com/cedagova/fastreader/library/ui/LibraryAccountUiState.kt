package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.account.library.AccountBook
import com.cedagova.fastreader.account.library.AccountDownloadsState
import com.cedagova.fastreader.account.library.AccountImportsState
import com.cedagova.fastreader.account.library.BookDownloadState
import com.cedagova.fastreader.account.library.AccountLibraryState
import com.cedagova.fastreader.account.library.AccountSyncError
import com.cedagova.fastreader.account.library.AccountSyncPhase
import com.cedagova.fastreader.account.library.BookImportState
import com.cedagova.fastreader.account.library.ImportsOff
import com.cedagova.fastreader.account.library.wireName
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus

/**
 * The account half of the shelf (LEAF703): the merge, the states, and the
 * removal offer, all as plain Kotlin values the Compose layer only lays out.
 *
 * Kept beside [buildLibraryUiState] rather than inside it because the two
 * halves answer different questions and fail differently: the device shelf is
 * the catalog's, and everything here is the account store's, adopted from the
 * backend and never computed locally.
 */

/**
 * The account half of one shelf row (REQ-501).
 *
 * [bookId] is the backend's canonical UUID — what every `library_item`
 * mutation for this row quotes, and never the device's content digest.
 * [onThisDevice] is false for a row the account has and this device does not;
 * such a row shows its title and author, says it is not here, and since #119
 * offers **Download and open** — which fetches and verifies the bytes before
 * anything opens (REQ-510).
 */
data class AccountRow(
    val bookId: String,
    val status: ReaderLibraryStatus = ReaderLibraryStatus.QUEUED,
    val onThisDevice: Boolean = true,
)

/** The account removal the reader can still take back (REQ-508). */
data class AccountUndoNotice(val bookId: String, val title: String)

/**
 * The downloaded private copy behind a row (REQ-510, D2, AD-24).
 *
 * [contentSha256] is what freeing the copy is addressed by — the copy store
 * names its file after it — and [sizeBytes] is what freeing it would recover,
 * taken from the catalog source so the confirmation quotes the file that is
 * actually there rather than a number the account reported.
 */
data class AccountCopyRow(val contentSha256: String, val sizeBytes: Long)

/**
 * The download half of [item], or null when the row has none.
 *
 * Keyed by the *account's* book id, so it only ever attaches to an
 * account-only row: a device row has no account bytes to fetch, and a copy
 * that has landed is a device row by then (see [BookDownloadState]).
 */
internal fun downloadFor(item: LibraryBookItem, downloads: AccountDownloadsState): BookDownloadState? {
    if (!item.isAccountOnly) return null
    val bookId = item.account?.bookId ?: return null
    return downloads.byAccountBookId[bookId]
}

/**
 * The **Add to account library** half of one device row (REQ-505, REQ-506,
 * REQ-507).
 *
 * Present only on a row that could actually be added: a readable book this
 * device has and the signed-in account does not. Null everywhere else, which is
 * why every signed-out golden is byte-identical to v1.6.0's and why an account
 * book — whose bytes the account already holds — offers nothing to upload.
 *
 * The three shapes it can take:
 *
 * - both fields null — the action, plain and on offer;
 * - [off] set — the deployment admits no imports, so the reason is shown *in
 *   place of* the action rather than offering a control that cannot work;
 * - [state] set — an add is somewhere between the tap and its verdict.
 */
data class AddToAccount(
    val state: BookImportState? = null,
    val off: ImportsOff? = null,
) {
    /** True when the row simply offers the action and nothing has happened yet. */
    val offered: Boolean get() = state == null && off == null
}

/**
 * The add-to-account half of [item], or null when the row cannot offer one.
 *
 * Four rows get nothing, each for its own reason and none of them cosmetic:
 *
 * 1. a row the account already has — there is nothing to upload, and an
 *    account-only row has no bytes here to upload in the first place;
 * 2. a book this device cannot read — missing, corrupt, DRM-protected or with
 *    its permission gone; there is no file to send;
 * 3. signed out — D4's shelf is exactly v1.6.0's;
 * 4. `reader.sync.v1` unavailable — the backend has said it is not serving this
 *    client's library, and offering an upload against it would be a control
 *    that fails on purpose.
 */
internal fun addToAccountFor(
    item: LibraryBookItem,
    account: AccountLibraryState,
    imports: AccountImportsState,
): AddToAccount? {
    if (item.account != null || !item.isReadable) return null
    if (account.phase == AccountSyncPhase.SIGNED_OUT) return null
    if (account.phase == AccountSyncPhase.DEFERRED && account.capabilityReason != null) return null
    imports.disabled?.let { return AddToAccount(off = it) }
    return AddToAccount(state = imports.byDeviceBookId[item.id])
}

/** Which sentence the shelf says about the account library. */
enum class AccountNoticeKind {
    /** The first load of the account's library, or a reload after a cursor expiry. */
    BOOTSTRAPPING,

    /** No network: rows are the last known ones and account actions are queued. */
    OFFLINE,

    /** `reader.sync.v1` is unavailable, or the backend asked for a retry later. Never a sign-out. */
    UNAVAILABLE,

    /** The backend no longer accepts this session (D4). Said once. */
    SESSION_GONE,

    /** The backend refused one queued change outright, with its own code. */
    REJECTED,

    /** Anything else the backend or the store answered, with whatever it gave. */
    PROBLEM,
}

/**
 * What the shelf says about the account library, when it has something to say.
 *
 * [code] and [requestId] are the backend's own words, carried through
 * untouched: the accessibility and content section asks for the category in
 * plain language *and* the code, because the code is what finds a server log.
 */
data class AccountNotice(
    val kind: AccountNoticeKind,
    val code: String? = null,
    val requestId: String? = null,
    /** Account changes waiting to be sent; only meaningful while offline. */
    val queued: Int = 0,
    /** A live state clears itself; a verdict about the past has to be put away. */
    val dismissible: Boolean = false,
) {
    /**
     * Stable identity of *this* notice, so putting one away cannot silently
     * hide the next, different one.
     */
    val key: String get() = "${kind.name}:${code.orEmpty()}:${requestId.orEmpty()}"
}

/**
 * The shelf's view of the account library state (the definition's states table).
 *
 * Signed out is deliberately silent: D4 says the signed-out shelf is v1.6.0's,
 * and a build with no stage values has nothing to report either. The one
 * exception is a session the backend stopped accepting, which the same table
 * says shows its reason once.
 */
fun accountNoticeFor(account: AccountLibraryState): AccountNotice? = when (account.phase) {
    AccountSyncPhase.SIGNED_OUT -> (account.lastError as? AccountSyncError.SessionGone)?.let {
        AccountNotice(
            kind = AccountNoticeKind.SESSION_GONE,
            code = it.code,
            requestId = it.requestId,
            dismissible = true,
        )
    }

    AccountSyncPhase.BOOTSTRAPPING -> AccountNotice(AccountNoticeKind.BOOTSTRAPPING)

    AccountSyncPhase.OFFLINE -> AccountNotice(AccountNoticeKind.OFFLINE, queued = account.queued)

    AccountSyncPhase.DEFERRED -> account.capabilityReason
        ?.let { AccountNotice(AccountNoticeKind.UNAVAILABLE, code = it.wireName()) }
        ?: account.lastError.toNotice()

    AccountSyncPhase.IDLE, AccountSyncPhase.SYNCING -> account.lastError.toNotice()
}

/**
 * The notice one error is, or null for an error the shelf says nothing about.
 *
 * `NotConfigured` is that one: a build with no stage values has no account
 * surface at all, and the account screen of #100 already says so in the one
 * place a reader would go looking.
 */
private fun AccountSyncError?.toNotice(): AccountNotice? = when (this) {
    null, AccountSyncError.NotConfigured -> null

    AccountSyncError.NetworkUnavailable -> AccountNotice(AccountNoticeKind.OFFLINE)

    is AccountSyncError.TryLater -> AccountNotice(
        kind = AccountNoticeKind.UNAVAILABLE,
        code = code ?: "HTTP $status",
        requestId = requestId,
    )

    is AccountSyncError.SessionGone -> AccountNotice(
        kind = AccountNoticeKind.SESSION_GONE,
        code = code,
        requestId = requestId,
        dismissible = true,
    )

    is AccountSyncError.Rejected -> AccountNotice(
        kind = AccountNoticeKind.REJECTED,
        code = code,
        dismissible = true,
    )

    is AccountSyncError.Forbidden -> AccountNotice(
        kind = AccountNoticeKind.PROBLEM,
        code = code ?: "403",
        requestId = requestId,
        dismissible = true,
    )

    is AccountSyncError.ApiError -> AccountNotice(
        kind = AccountNoticeKind.PROBLEM,
        code = code ?: "HTTP $status",
        requestId = requestId,
        dismissible = true,
    )

    is AccountSyncError.StoreBlocked -> AccountNotice(
        kind = AccountNoticeKind.PROBLEM,
        code = message,
        dismissible = true,
    )
}

/**
 * Merges the account's books into the device shelf (AD-23).
 *
 * One row per content identity: a device book and an account book are the same
 * book when their SHA-256 agree, and nothing else — not the path, not the file
 * name, not the title — ever makes them one row. An account book no device row
 * matches becomes a row of its own, marked as not being here.
 *
 * The device rows keep their order and their content exactly; the account rows
 * are appended and then sorted by the same comparator the device shelf used, so
 * the shelf stays one list under one rule. Account-only rows carry no
 * timestamps of their own, which puts them where a book this device has never
 * read belongs under both by-time orders: after the read ones, alphabetically.
 */
internal fun List<LibraryBookItem>.withAccountBooks(account: AccountLibraryState): List<LibraryBookItem> {
    if (account.books.isEmpty()) return this
    // Last writer wins on a duplicate identity, which the backend's own dedup
    // makes vanishingly unlikely; taking one is what keeps this a *merge* and
    // not a second row for the same bytes.
    val byIdentity = account.books.mapNotNull { book ->
        contentIdentity(book.contentSha256)?.let { it to book }
    }.toMap()
    val matched = mutableSetOf<String>()
    val merged = map { item ->
        val identity = contentIdentity(item.id) ?: return@map item
        val book = byIdentity[identity] ?: return@map item
        matched += identity
        item.copy(account = AccountRow(bookId = book.bookId, status = book.status, onThisDevice = true))
    }
    val accountOnly = account.books
        .filter { contentIdentity(it.contentSha256)?.let { id -> id !in matched } ?: true }
        .map { it.toAccountOnlyItem() }
    return merged + accountOnly
}

/**
 * A book the account has and this device does not.
 *
 * Its id is the canonical UUID under an `account:` prefix, so it can never
 * collide with a catalog id — every device row's id is a `sha256:` digest —
 * and so a list key, a test tag and a golden can all name it.
 */
private fun AccountBook.toAccountOnlyItem(): LibraryBookItem = LibraryBookItem(
    id = ACCOUNT_ONLY_ID_PREFIX + bookId,
    title = title,
    author = author?.takeIf { it.isNotBlank() },
    fileNames = emptyList(),
    progressPercent = 0,
    status = BookStatus.READABLE,
    // A cover is bytes, and the bytes are not here: the row is title and author
    // until the download lands, at which point it is a device row with the
    // cover the ingestion took from the copy.
    hasCover = false,
    account = AccountRow(bookId = bookId, status = status, onThisDevice = false),
)

/** The prefix an account-only row's id carries; see [toAccountOnlyItem]. */
internal const val ACCOUNT_ONLY_ID_PREFIX: String = "account:"

/**
 * The content identity two rows are merged on, or null when there is none.
 *
 * Both sides are normalised the same way and *only* the 64 hex characters of a
 * SHA-256 are accepted: the catalog stores `sha256:<hex>` (v1 AD-2) and the
 * backend types `checksum` as a free-form string, so a value that is not a
 * SHA-256 — or is absent — merges with nothing rather than merging by
 * accident. That is AD-23 enforced rather than assumed.
 */
internal fun contentIdentity(value: String?): String? {
    val trimmed = value?.trim()?.lowercase() ?: return null
    val hex = trimmed.removePrefix("sha256:").removePrefix("sha-256:")
    if (hex.length != SHA256_HEX_LENGTH) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return hex
}

private const val SHA256_HEX_LENGTH = 64

/**
 * The account books whose library status the shelf can see is out of date
 * because the reader finished them here.
 *
 * Only ever *forward*, and only from a fact the device already holds: a book
 * read to its last word is finished, which is the same rule the row's 100%
 * comes from (REQ-018). Nothing here decides a conflict — the moment the
 * backend answers, its canonical payload replaces the row (AD-22) — and a row
 * the backend already calls finished produces nothing, so the shelf settles
 * instead of re-sending.
 */
fun finishedAccountBooks(books: List<LibraryBookItem>): List<String> = books.mapNotNull { item ->
    val account = item.account ?: return@mapNotNull null
    if (!account.onThisDevice) return@mapNotNull null
    if (item.progressPercent < FINISHED_PERCENT) return@mapNotNull null
    if (account.status == ReaderLibraryStatus.FINISHED) return@mapNotNull null
    account.bookId
}

private const val FINISHED_PERCENT = 100

/**
 * The account book a device book is, or null when the account does not have it.
 *
 * The one mapping outside the shelf: opening a book records last-opened for the
 * *account's* book id, and the launch can go straight into a book without the
 * shelf ever being drawn.
 */
fun accountBookIdForDevice(deviceBookId: String, account: AccountLibraryState): String? {
    val identity = contentIdentity(deviceBookId) ?: return null
    return account.books.firstOrNull { contentIdentity(it.contentSha256) == identity }?.bookId
}
