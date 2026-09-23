package com.cedagova.reader.library.sync

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
 * One document per account, so signing in as somebody else cannot see the
 * previous account's rows or queue (D4): one JSON document written atomically,
 * carrying its own version, migrated forward step by step and *refused* when it
 * was written by a newer build.
 *
 * ## Host records
 *
 * A host app keeps a little state of its own beside the account's — FastReader
 * keeps its verified-copy references and its answered resume offers — and it has
 * to live in this document, because the document has exactly one writer and a
 * second file racing it would be the first way to lose a queued mutation. The
 * schema therefore reserves nothing for any host: every key the document or a
 * book row does not declare is a **host record**, kept verbatim through every
 * load, adoption and save ([AccountLibraryDocument.host], [AccountBook.host]),
 * and written back at the same level it was read from. That is also what keeps
 * a document written before #147 byte-compatible: FastReader's `copies` and
 * `resumeOfferSettledFor` keys are where they always were.
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
     * - **3** — increment 003 (LEAF811): `copies`, FastReader's references to
     *   the verified private copies on its device (REQ-510, D2). A host record
     *   since #147: the schema number stays, the key is the host's.
     * - **4** — increment 004 (LEAF821): `remotePosition` on a book row, the
     *   portable position another client left for it (REQ-511, AD-25). It is
     *   the account's position and deliberately *not* the device's: the local
     *   `ReadingState` keeps its own shape and semantics, and the two are never
     *   merged here.
     * - **5** — increment 004 (LEAF822): `resumeOfferSettledFor` on a book row,
     *   FastReader's note of the resume offer the reader already answered
     *   (REQ-511). A book host record since #147; it never reaches the backend.
     * - **6** — #140: `ownPositionChangeKey` on a book row, the position change
     *   the backend admitted from *this* device's own publish. Like schema 5 it
     *   is this device's note about itself and never reaches the backend; it is
     *   what keeps this device's own position, echoed back by the change stream
     *   or read back by a re-bootstrap, from being offered as another device's.
     *
     * #147 moved this schema into `:reader-library` without a version change:
     * the bytes a schema 6 document holds are exactly the bytes it held before.
     *
     * Each adds a [MIGRATIONS] entry keyed by the version it upgrades *from*,
     * exactly as `CatalogSchema` does.
     */
    const val CURRENT_VERSION: Int = 6

    /**
     * Forward migrations keyed by the version they upgrade *from*.
     *
     * Every step so far is a no-op on the document's own keys, for the same
     * reason: each adds something with an empty or absent default, so an older
     * document decodes with it empty — which is exactly the truth about a device
     * that has never added a book (1 → 2), never downloaded one (2 → 3), or
     * never heard a position from another client (3 → 4), never answered a
     * resume offer (4 → 5), or never had a position of its own admitted (5 → 6). The steps exist all the same, because the decoder
     * demands one per version and a missing entry is how a forgotten migration is
     * caught rather than a document quietly read as damaged.
     */
    val MIGRATIONS: Map<Int, AccountLibraryMigration> = mapOf(
        1 to AccountLibraryMigration { document -> document },
        2 to AccountLibraryMigration { document -> document },
        3 to AccountLibraryMigration { document -> document },
        4 to AccountLibraryMigration { document -> document },
        5 to AccountLibraryMigration { document -> document },
    )
}

/** One forward step of the account-library schema. */
fun interface AccountLibraryMigration {
    fun migrate(document: JsonObject): JsonObject
}

/**
 * Everything a Reader client holds for one signed-in account.
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
     * The host's own records for this account: every top-level key this schema
     * does not declare, kept verbatim (see [AccountLibrarySchema]).
     *
     * Never serialized under this name. [AccountLibraryCodec] reads undeclared
     * keys into it and writes its entries back as top-level keys, so the stored
     * document carries no `host` key at all. The engine never reads a value in
     * here; a host changes it only through the engine's
     * [AccountHostRecords.updateHostRecord], under the one writer.
     */
    @SerialName(HOST_RECORDS_KEY) val host: JsonObject = EMPTY_JSON_OBJECT,
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
     * untouched. The portable locator and a host's own position are
     * LEAF821's work, not this increment's; this is the one field of that read
     * the store keeps so the bootstrap's second list is not thrown away.
     */
    @SerialName("progressPercent") val progressPercent: Double? = null,
    @SerialName("progressUpdatedAt") val progressUpdatedAt: String? = null,
    /**
     * The portable position the account holds for this book (schema 4, REQ-511,
     * AD-25): the section another client named and how far through the book it
     * was, with the server's own revision and admission time.
     *
     * This is **not** the device's position. `ReadingState` is unchanged in shape
     * and semantics and stays in the catalog; this row never merges with it, and
     * nothing here compares the two to decide which is further along — the
     * backend's admission order decides that (`reader.activity-convergence.v1`).
     * LEAF822 is what offers it to a reader.
     */
    @SerialName("remotePosition") val remotePosition: AccountRemotePosition? = null,
    /**
     * The position change this device itself published and the backend admitted
     * (schema 6, #140), as [AccountRemotePosition.changeKey] names it — or null
     * while none has been.
     *
     * Set only when adopting this device's own `reading_progress` result that
     * the backend `applied` or `replayed`: the canonical position it returns is
     * the one this device sent, stored with the revision it was admitted at
     * (client contract §7.4). The change stream later delivers that same
     * revision back — it carries no originating-client field — and §7.3 drops it
     * because it is not newer than the stored one, so [remotePosition] keeps this
     * key. A `superseded` or `conflict` answer carries somebody else's position
     * and sets nothing.
     *
     * While [remotePosition]'s key equals this one, the account's position *is*
     * this device's own, and the resume offer does not present it as another
     * device's. A genuinely newer change from elsewhere has a newer revision and
     * so a different key. Like every host record it is never put in a
     * mutation payload and `AccountCanonicalPayload` never writes it.
     */
    @SerialName("ownPositionChangeKey") val ownPositionChangeKey: String? = null,
    /**
     * The host's own records for this book: every key of a stored row this
     * schema does not declare, kept verbatim (see [AccountLibrarySchema]).
     *
     * Adopting a canonical payload keeps it, exactly as it keeps any field the
     * payload does not carry; it is never put in a mutation payload. Like
     * [AccountLibraryDocument.host] it is never serialized under this name.
     */
    @SerialName(HOST_RECORDS_KEY) val host: JsonObject = EMPTY_JSON_OBJECT,
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
 * The account's portable position for one book (schema 4, REQ-511).
 *
 * Every field is the backend's own, adopted from a `reading_progress` payload
 * (AD-22). Nothing here is computed, and nothing here is a host's: there is
 * no token index, no pipeline version, no structural fingerprint and no reading
 * speed, because the account never held any of them.
 *
 * [revision] and [serverAdmittedAt] are the server's ordering, kept so a host
 * can tell one remote change from the next — which is what LEAF822's "offered
 * once per remote change" needs — without inventing an ordering of its own.
 * [href] may be absent: a locator states only its format as a minimum, and a
 * record from a client that named no section is still a usable percentage.
 */
@Serializable
data class AccountRemotePosition(
    /** The section the other client named — a spine path, when it named one. */
    @SerialName("href") val href: String? = null,
    @SerialName("chapterTitle") val chapterTitle: String? = null,
    /** `0.0..1.0`, the book-level fraction the record carried. */
    @SerialName("progression") val progression: Double? = null,
    /** `0..100` as the record stated it. */
    @SerialName("percent") val percent: Double? = null,
    /** The server's own time for the record; never compared with a device clock. */
    @SerialName("updatedAt") val updatedAt: String? = null,
    /** The server's revision of the progress resource this position came from. */
    @SerialName("revision") val revision: Long = 0,
    /** The server's admission time for the change that delivered it, when one came with it. */
    @SerialName("serverAdmittedAt") val serverAdmittedAt: String? = null,
) {

    /**
     * The identity of the remote change this position came from, as the resume
     * offer's settled record keys itself by (REQ-511).
     *
     * Both of the server's own ordering values, and neither of this device's: the
     * revision of the progress resource and the server's `updated_at` for the
     * record. Nothing here is compared with anything — this is a *name* for one
     * change, not a place in an order — and that is why it is safe for a client
     * to build. `reader.activity-convergence.v1` still decides which position
     * wins.
     *
     * Stable across a re-bootstrap, which is what makes "offered once" hold
     * across a cursor expiry: a list read carries no revision and no admission
     * time, and [AccountCanonicalPayload.remotePosition] keeps the stored ones in
     * that case, so the same record keeps the same key.
     */
    val changeKey: String get() = "$revision:${updatedAt.orEmpty()}"
}

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

/**
 * The in-memory name of a document's or a row's host records. Reserved: a host
 * record cannot itself be called this, and [AccountLibraryCodec] refuses to
 * let one shadow a declared key.
 */
internal const val HOST_RECORDS_KEY: String = "host"
