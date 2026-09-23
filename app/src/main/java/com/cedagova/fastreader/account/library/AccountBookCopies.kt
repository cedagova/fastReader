package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.sync.AccountBook
import com.cedagova.fastreader.account.AssetDownloadGateway
import com.cedagova.fastreader.library.BookSource
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.downloads.AssetDownloadException
import java.io.File

/**
 * Bringing an account book's bytes onto this device, and freeing them again
 * (#118, REQ-510, D2, AD-24).
 *
 * This is the whole of the download direction, and it is deliberately one
 * short method per outcome rather than a state machine: LEAF812 owns *when*
 * the owner is offered a download and what the shelf shows while one runs.
 *
 * ## The order is the requirement
 *
 * [download] does four things, and their order is what REQ-510 asks for:
 *
 * 1. ask reader-api for a grant — the only address a book may be fetched from;
 * 2. stream the bytes under that grant into [AccountCopyStore.place], which
 *    digests them as they land and refuses to place anything whose SHA-256 is
 *    not the account's identity for that book;
 * 3. only once a file has been placed, write the device-catalog row with its
 *    `ACCOUNT_COPY` source, which is what makes it readable;
 * 4. and then record the account's copy reference.
 *
 * Nothing between steps 1 and 3 can produce a readable book, which is the
 * property the whole leaf exists to hold. Failures stop at the step they
 * happen in, and every one of them leaves this device exactly as it was.
 *
 * ## A spent grant is not a failure
 *
 * The grant's TTL is the backend's, and a long download on a phone network can
 * outlive one. A rejected grant is therefore retried exactly once, with a
 * *fresh* grant from reader-api and a fresh attempt — never with the session's
 * bearer, which the provider would not accept and which the transport could not
 * produce anyway. A second rejection is reported rather than retried: at that
 * point the asset, not the signature, is the problem.
 *
 * ## Sign-out (D4)
 *
 * Nothing here has to know about it. The bytes are a file and the row is a
 * device book, so a copy keeps opening when the account rows leave; only the
 * copy *reference* is account-scoped, and it comes back when the same account
 * signs in again.
 */
class AccountBookCopies(
    /** Null on a build with no stage values: then nothing here can be reached. */
    private val gateway: AssetDownloadGateway?,
    private val store: AccountCopyStore,
    private val references: AccountCopyReferences,
    private val library: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True when this device already holds the verified bytes of that book. */
    fun has(contentSha256: String): Boolean = store.has(contentSha256)

    /** The verified copy of that book, or null when this device has none. */
    fun copy(contentSha256: String): File? = store.copy(contentSha256)

    /**
     * Downloads [book]'s bytes, verifies them and makes them readable.
     *
     * [book] must carry both the asset to fetch and the content identity to
     * verify against: without the identity there is nothing to compare, and a
     * copy that cannot be verified must not be fetched at all. [onProgress] is
     * called with the bytes received and the grant's own declared total.
     */
    suspend fun download(
        book: AccountBook,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): CopyOutcome {
        val gateway = gateway ?: return CopyOutcome.Unavailable("this build has no Reader account")
        val assetId = book.assetId
            ?: return CopyOutcome.Unavailable("the account has no downloadable file for ${book.title}")
        val contentSha256 = book.contentSha256
            ?: return CopyOutcome.Unavailable("the account has no content identity for ${book.title}")

        store.copy(contentSha256)?.let { existing -> return adopt(book, contentSha256, existing) }

        var attempt = 0
        while (true) {
            attempt++
            val grant = try {
                gateway.downloadGrant(assetId)
            } catch (e: ReaderAuthException) {
                return CopyOutcome.GrantFailed(e)
            }
            val placement = store.place(contentSha256, grant.sizeBytes) { sink ->
                gateway.download(grant, sink, onProgress)
            }
            val spent = placement is CopyPlacement.Failed &&
                generateSequence(placement.error) { it.cause }
                    .any { it is AssetDownloadException.GrantRejected }
            if (spent && attempt < MAX_ATTEMPTS) continue
            return when (placement) {
                is CopyPlacement.Placed -> adopt(book, contentSha256, placement.file)
                is CopyPlacement.Mismatched -> CopyOutcome.Tampered(placement.expected, placement.actual)
                is CopyPlacement.NoStorage -> CopyOutcome.NoStorage(placement.reason)
                is CopyPlacement.Refused -> CopyOutcome.Unavailable(placement.reason)
                is CopyPlacement.Failed -> CopyOutcome.DownloadFailed(placement.error)
            }
        }
    }

    /**
     * Frees this device's copy of [contentSha256] without touching the account
     * (D2's **Remove downloaded copy**).
     *
     * The catalog row goes first and the bytes second, so a process killed
     * between the two leaves a file with nothing pointing at it — which
     * [reconcile] sweeps — rather than a row pointing at a file that is gone.
     * Returns true when there was a copy to free.
     */
    suspend fun remove(contentSha256: String): Boolean {
        val bookId = library.catalog.value.books
            .firstOrNull { book -> book.sources.any { it.isAccountCopy && it.uri == copyUri(contentSha256) } }
            ?.id
        if (bookId != null) library.removeAccountCopy(bookId)
        references.dropCopyReference(contentSha256)
        return store.delete(contentSha256)
    }

    /**
     * The start-up sweep (#118's "app death mid-download").
     *
     * Deletes every partial download a dead process left behind, marks every
     * catalog source by whether its file is really there, and drops the account
     * references the store no longer backs. Returns how many partials went, so
     * a caller that wants to say so can.
     */
    suspend fun reconcile(): Int {
        val discarded = store.discardPartials()
        library.reconcileAccountCopies { path -> File(path).isFile }
        references.retainCopyReferences(store.contents())
        return discarded
    }

    /**
     * Makes a placed file readable and records that the account has it.
     *
     * The catalog row is written first: it is what makes the copy openable, and
     * until it exists the file is bytes nothing refers to. The account reference
     * follows, and a sign-out between the two costs only the reference.
     */
    private suspend fun adopt(book: AccountBook, contentSha256: String, file: File): CopyOutcome {
        val displayName = book.title.ifBlank { contentSha256 } + EPUB_SUFFIX
        val bookId = library.addAccountCopy(contentSha256, file, displayName)
            ?: return CopyOutcome.Unreadable(contentSha256)
        references.putCopyReference(
            AccountCopy(
                contentSha256 = contentSha256.removePrefix(SHA256_PREFIX).lowercase(),
                sizeBytes = file.length(),
                placedAtEpochMs = clock(),
            ),
        )
        return CopyOutcome.Ready(bookId = bookId, file = file, sizeBytes = file.length())
    }

    private fun copyUri(contentSha256: String): String = BookSource.accountCopyUri(contentSha256)

    private companion object {
        /** One spent grant is a TTL; two is the asset. */
        const val MAX_ATTEMPTS = 2
        const val EPUB_SUFFIX = ".epub"
        const val SHA256_PREFIX = "sha256:"
    }
}

/**
 * What came of asking for an account book's bytes.
 *
 * Every branch but [Ready] means this device is exactly as it was: no file, no
 * catalog row, no reference. That is the point of having five of them rather
 * than a boolean — the shelf shows a different thing for each, and none of them
 * is "something went wrong".
 */
sealed interface CopyOutcome {

    /** The copy is verified, placed and openable. [bookId] is the catalog row. */
    data class Ready(val bookId: String, val file: File, val sizeBytes: Long) : CopyOutcome

    /**
     * The bytes that arrived are not the book the account holds, so nothing was
     * placed (REQ-510's "a tampered download is refused with the reason").
     */
    data class Tampered(val expected: String, val actual: String) : CopyOutcome

    /** There is no room on this device. Nothing was placed. */
    data class NoStorage(val reason: String) : CopyOutcome

    /** reader-api would not issue a grant. The failure is `:reader-auth`'s own, unchanged. */
    data class GrantFailed(val error: ReaderAuthException) : CopyOutcome

    /** The bytes did not arrive. A later attempt may work. */
    data class DownloadFailed(val error: Throwable) : CopyOutcome

    /**
     * The file is verified but is not an EPUB this reader can open.
     *
     * The bytes are exactly what the account holds — the digest said so — so
     * this is a statement about the book, not about the download.
     */
    data class Unreadable(val contentSha256: String) : CopyOutcome

    /** There was nothing to ask for: no account build, no asset, or no identity. */
    data class Unavailable(val reason: String) : CopyOutcome
}
