package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.EMPTY_JSON_OBJECT
import com.cedagova.reader.library.model.ReaderCoverStatus
import com.cedagova.reader.library.model.ReaderLibraryItem
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The persisted account-library schema (AD-20).
 *
 * Deliberately **not** the device catalog: that codec drops unknown keys, its
 * schema is the device library's own contract, and D4 needs one document per
 * account so signing in as somebody else cannot see the previous account's
 * rows or queue. The discipline is the catalog's, though — one JSON document
 * written atomically, carrying its own version, migrated forward step by step
 * and *refused* when it was written by a newer build.
 */
object AccountLibrarySchema {

    /**
     * Version history:
     *
     * - **1** — increment 001 (LEAF702): the account's book rows, the sync
     *   cursor and the outbox of queued mutations.
     * - **2** — increment 002 (LEAF802): `imports`, the durable publication
     *   import records of AD-26, so an add interrupted by app death addresses
     *   the same admission instead of making a second one.
     * - **3** — increment 003 (LEAF811): `copies`, this account's references to
     *   the verified private copies on this device (REQ-510, D2). A reference
     *   is content identity plus when it was placed; the bytes themselves are
     *   `AccountCopyStore`'s, and the device catalog's `ACCOUNT_COPY` source is
     *   what keeps them readable after sign-out (D4, AD-24).
     *
     * LEAF821 takes the next step of its own, for remote positions and the
     * resume record. Each adds a [MIGRATIONS] entry keyed by the version it
     * upgrades *from*, exactly as `CatalogSchema` does.
     */
    const val CURRENT_VERSION: Int = 3

    /**
     * Forward migrations keyed by the version they upgrade *from*.
     *
     * Both steps so far are no-ops on the document's own keys, for the same
     * reason: each adds a new list with an empty default, so an older document
     * decodes with that list empty — which is exactly the truth about a device
     * that has never added a book (1 → 2) or never downloaded one (2 → 3). The
     * steps exist all the same, because the decoder demands one per version and
     * a missing entry is how a forgotten migration is caught rather than a
     * document quietly read as damaged.
     */
    val MIGRATIONS: Map<Int, AccountLibraryMigration> = mapOf(
        1 to AccountLibraryMigration { document -> document },
        2 to AccountLibraryMigration { document -> document },
    )
}

/** One forward step of the account-library schema. */
fun interface AccountLibraryMigration {
    fun migrate(document: JsonObject): JsonObject
}

/**
 * Everything FastReader holds for one signed-in account.
 *
 * [userId] is the provider subject the session reports; it is written into the
 * document as well as deciding which file the document lives in, so a document
 * that somehow reached the wrong file is detected rather than adopted.
 *
 * [cursor] is the account change stream's position. `null` means this device
 * has never bootstrapped the account, which is the one condition that makes
 * the engine read the lists instead of the stream.
 */
@Serializable
data class AccountLibraryDocument(
    @SerialName("schemaVersion") val schemaVersion: Int = AccountLibrarySchema.CURRENT_VERSION,
    @SerialName("userId") val userId: String = "",
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("books") val books: List<AccountBook> = emptyList(),
    @SerialName("outbox") val outbox: List<AccountOutboxEntry> = emptyList(),
    /**
     * The publication imports this device has started for this account and the
     * backend has not finished (AD-26, schema 2).
     *
     * Durable on purpose: the whole of "killing the app mid-transfer resumes or
     * restarts without a duplicate" (REQ-507) is that
     * [com.cedagova.reader.library.imports.PublicationImportRecord.clientImportId]
     * is derived from the file's content and the account, survives the process
     * that minted it, and makes the next admission a replay.
     *
     * What is deliberately *not* in here is the grant's signed headers: those
     * are a credential for a storage provider, they live for one transfer, and
     * a resume gets fresh ones by replaying the idempotent admission.
     */
    @SerialName("imports") val imports: List<PublicationImportRecord> = emptyList(),
    /**
     * The verified private copies this account has on this device (schema 3,
     * REQ-510, D2).
     *
     * It is a *reference*, not the bytes and not their location: the bytes are
     * `AccountCopyStore`'s, keyed by the same content identity, and the file
     * they live in is named by the device catalog's `ACCOUNT_COPY` source. This
     * list is how the shelf answers "is this account book on this device?"
     * without reading the filesystem for every row it draws.
     *
     * It is deliberately account-scoped and deliberately not authoritative. A
     * copy the store no longer holds is reconciled away on the next start, and
     * two accounts that hold the same book each carry their own reference to
     * the one shared file — which is why signing out drops the reference and
     * never the copy (D4).
     */
    @SerialName("copies") val copies: List<AccountCopy> = emptyList(),
) {

    /** True once the account's library has been read at least once on this device. */
    val bootstrapped: Boolean get() = cursor != null

    fun book(bookId: String): AccountBook? = books.firstOrNull { it.bookId == bookId }

    /** The stored import for [clientImportId], or null when there is none. */
    fun import(clientImportId: String): PublicationImportRecord? =
        imports.firstOrNull { it.clientImportId == clientImportId }

    /** Replaces [record]'s entry, or appends it when this account has none for that file. */
    fun withImport(record: PublicationImportRecord): AccountLibraryDocument {
        val index = imports.indexOfFirst { it.clientImportId == record.clientImportId }
        return if (index < 0) {
            copy(imports = imports + record)
        } else {
            copy(imports = imports.toMutableList().apply { this[index] = record })
        }
    }

    /** Drops the import [clientImportId] names; nothing happens when there is none. */
    fun withoutImport(clientImportId: String): AccountLibraryDocument =
        copy(imports = imports.filterNot { it.clientImportId == clientImportId })

    /** True when this account has a copy reference for that content identity. */
    fun hasCopy(contentSha256: String): Boolean = copies.any { it.contentSha256 == contentSha256 }

    /** Records [copy], replacing any earlier reference to the same content. */
    fun withCopy(copy: AccountCopy): AccountLibraryDocument {
        val index = copies.indexOfFirst { it.contentSha256 == copy.contentSha256 }
        return if (index < 0) {
            copy(copies = copies + copy)
        } else {
            copy(copies = copies.toMutableList().apply { this[index] = copy })
        }
    }

    /** Drops the copy reference for [contentSha256]; the bytes are not this document's to delete. */
    fun withoutCopy(contentSha256: String): AccountLibraryDocument =
        copy(copies = copies.filterNot { it.contentSha256 == contentSha256 })

    /** Keeps only the copy references [present] still names — the start-up reconciliation. */
    fun retainingCopies(present: Set<String>): AccountLibraryDocument =
        copy(copies = copies.filter { it.contentSha256 in present })

    /** Replaces [book]'s row, or appends it when the account has no row for that book yet. */
    fun withBook(book: AccountBook): AccountLibraryDocument {
        val index = books.indexOfFirst { it.bookId == book.bookId }
        return if (index < 0) {
            copy(books = books + book)
        } else {
            copy(books = books.toMutableList().apply { this[index] = book })
        }
    }
}

/**
 * One account book, as the backend last described it.
 *
 * Every field here is the server's, adopted from a library row, a mutation
 * result's canonical payload or a delta's canonical payload (AD-22). Nothing
 * in this app computes one of them.
 *
 * [contentSha256] is the identity the shelf merges on (AD-23): a device book
 * and an account book are the same row when these agree. [removed] is a
 * tombstone rather than a deletion, because a `restore` — the immediate Undo
 * the contract offers — has to bring the row back, and the restore's canonical
 * payload is not guaranteed to carry the metadata again.
 */
@Serializable
data class AccountBook(
    @SerialName("bookId") val bookId: String,
    @SerialName("title") val title: String = "",
    @SerialName("author") val author: String? = null,
    @SerialName("language") val language: String? = null,
    /** The EPUB asset's content SHA-256, when the server has computed one. */
    @SerialName("contentSha256") val contentSha256: String? = null,
    /** The asset the checksum came from; the reference LEAF811 downloads by. */
    @SerialName("assetId") val assetId: String? = null,
    @SerialName("status") val status: ReaderLibraryStatus = ReaderLibraryStatus.QUEUED,
    @SerialName("coverStatus") val coverStatus: ReaderCoverStatus = ReaderCoverStatus.PENDING,
    @SerialName("lastOpenedAt") val lastOpenedAt: String? = null,
    /** The server's revision for the library item; the base revision a mutation quotes. */
    @SerialName("revision") val revision: Long = 0,
    /** Set by a `delete` change or result; cleared by a `restore`. Tombstoned rows leave the shelf. */
    @SerialName("removed") val removed: Boolean = false,
    /**
     * The account's reading position for this book as a percentage, carried
     * through from `GET /v1/reader/progress` and the `reading_progress` stream
     * untouched. The portable locator and FastReader's own position are
     * LEAF821's work, not this increment's; this is the one field of that read
     * the store keeps so the bootstrap's second list is not thrown away.
     */
    @SerialName("progressPercent") val progressPercent: Double? = null,
    @SerialName("progressUpdatedAt") val progressUpdatedAt: String? = null,
) {
    companion object {

        /** The row a `GET /v1/reader/library` item describes. */
        fun of(item: ReaderLibraryItem): AccountBook {
            val asset = item.assets.firstOrNull { it.checksum != null }
            return AccountBook(
                bookId = item.book.id,
                title = item.book.title,
                author = item.book.author,
                language = item.book.language,
                contentSha256 = asset?.checksum,
                assetId = asset?.assetId,
                status = item.status,
                coverStatus = item.coverStatus,
                lastOpenedAt = item.lastOpenedAt,
            )
        }
    }
}

/**
 * One account book whose bytes are on this device (REQ-510, D2).
 *
 * [contentSha256] is the whole of the identity — the same digest the shelf
 * merges rows on (AD-23) and the same one `AccountCopyStore` names its file
 * after. There is deliberately no path here: a reference that recorded where
 * the bytes were would be a second answer to a question the catalog's
 * `ACCOUNT_COPY` source already answers, and two answers drift.
 *
 * [sizeBytes] and [placedAtEpochMs] are what a host shows about a copy — how
 * much freeing it would recover, and when it arrived. Neither is load-bearing:
 * a reference with both at zero still means "this account's copy is here".
 */
@Serializable
data class AccountCopy(
    @SerialName("contentSha256") val contentSha256: String,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("placedAtEpochMs") val placedAtEpochMs: Long = 0,
)

/**
 * One mutation waiting to be admitted.
 *
 * [idempotencyKey] is minted once, here, and then *persisted*: it is what
 * makes a retry a replay rather than a second admission, so it has to survive
 * a failed send, a process death mid-drain and a cursor re-bootstrap. The
 * engine never mints a new key for an entry that already has one.
 */
@Serializable
data class AccountOutboxEntry(
    @SerialName("idempotencyKey") val idempotencyKey: String,
    @SerialName("resourceType") val resourceType: ReaderResourceType = ReaderResourceType.LIBRARY_ITEM,
    @SerialName("resourceId") val resourceId: String,
    @SerialName("mutationKind") val mutationKind: ReaderMutationKind,
    @SerialName("baseRevision") val baseRevision: Long = 0,
    @SerialName("payload") val payload: JsonObject = EMPTY_JSON_OBJECT,
    @SerialName("clientCreatedAt") val clientCreatedAt: String? = null,
) {
    fun toEnvelope(): ReaderSyncMutationEnvelope = ReaderSyncMutationEnvelope(
        idempotencyKey = idempotencyKey,
        resourceType = resourceType,
        resourceId = resourceId,
        mutationKind = mutationKind,
        baseRevision = baseRevision,
        payload = payload,
        clientCreatedAt = clientCreatedAt,
    )
}
