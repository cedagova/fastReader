package com.cedagova.reader.library.sync

import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.ReaderLibraryStatus
import kotlinx.serialization.json.JsonElement

/**
 * Who is signed in, as the engine needs to know it (D4).
 *
 * The host maps its own session state onto this; the engine reads nothing else
 * about the session. [Loading] means the stored session has not been read yet,
 * so nothing changes.
 */
public sealed interface AccountSession {

    /** The stored session has not been read yet. */
    public data object Loading : AccountSession

    /** The host carries no service values, so there is no account to sync. */
    public data object NotConfigured : AccountSession

    /** Nobody is signed in. */
    public data object SignedOut : AccountSession

    /** [userId] is the provider subject the session reports. */
    public data class SignedIn(val userId: String) : AccountSession
}

/** The account-library actions a host's shelf performs (LEAF703). */
public interface AccountLibraryActions {

    /** The reader asked for a refresh. */
    public fun refresh()

    /** Remove [bookId] from the account library. */
    public fun removeFromAccount(bookId: String)

    /** The contract's immediate Undo of a removal: a `restore` of the same book. */
    public fun undoRemove(bookId: String)

    /** The book was opened: `reading`, and this device's clock as the last-opened time. */
    public fun recordOpened(bookId: String)

    /** The book was finished. */
    public fun recordFinished(bookId: String)

    /** Any other library status the shelf sets. */
    public fun recordStatus(bookId: String, status: ReaderLibraryStatus)

    /**
     * Publishes the portable position of an account book (REQ-511, AD-25).
     *
     * [bookId] is the **account's** book id. A device book the account does not
     * hold has no account id to resolve, so nothing is published for it — the
     * caller's `accountBookIdForDevice` returns null and this is never reached.
     *
     * Called only when the position writer flushes for a non-word event and the
     * section or the whole percent changed since the last publish; never on the
     * per-word throttle. A position that moves *backwards* is published exactly
     * like one that moves forwards: `causal-progress-can-move-backward`, and the
     * backend's admission order decides who wins.
     */
    public fun recordPosition(bookId: String, position: LocalReadingPosition)
}

/**
 * The account document's import records, as the add-to-account flow uses them
 * (LEAF802, AD-26).
 *
 * A separate interface rather than more methods on [AccountLibraryActions]
 * because the two answer different questions: those are *mutations of the
 * account's library* that queue and are admitted; these are this device's own
 * memory of an upload in flight, which the backend never sees. It is served by
 * [AccountSyncEngine] all the same, and for one reason — the account document
 * has exactly one writer, and a second one racing it would be the first way to
 * lose a queued mutation.
 */
public interface AccountImportRecords {

    /** The signed-in account's user id, or null when nobody is signed in. */
    public fun accountId(): String?

    /** Every import this device has started for the signed-in account and not finished. */
    public suspend fun importRecords(): List<PublicationImportRecord>

    /** Stores [record], replacing any earlier state of the same import. */
    public suspend fun putImportRecord(record: PublicationImportRecord)

    /** Forgets the import [clientImportId] names. */
    public suspend fun dropImportRecord(clientImportId: String)
}

/**
 * A host record was given a key the account document reserves (#149).
 *
 * [key] is either one the schema declares at that level — which would win over
 * the record when the document is written — or `host`, which is dropped when it
 * is read back. Either way the record would be stored and then silently lost, so
 * the write is refused instead. [bookLevel] says which level the key was for.
 */
public class ReservedHostRecordKeyException(public val key: String, public val bookLevel: Boolean) :
    IllegalArgumentException(
        "\"$key\" is reserved by the account document ${if (bookLevel) "for a book row" else "at the top level"}; " +
            "a host record under it would be lost",
    )

/**
 * The host's own records in the account document (#147).
 *
 * A host keeps a little state of its own beside the account's — for example
 * its verified-copy references and the resume offers a reader answered —
 * and it has to be written by the same single writer as everything else in the
 * document, because a second writer racing it would be the first way to lose a
 * queued mutation. These are that writer's host-facing half: values the engine
 * stores verbatim and never reads, never queues and never sends.
 *
 * Every update runs under the engine's lock, so a read-modify-write here is
 * atomic with every other write to the document. An update while nobody is
 * signed in does nothing; an update that changes nothing writes nothing. Neither
 * publishes a new state: a host record changes no row the account describes.
 *
 * **A `transform` must not call back into the engine** (#149) — not this
 * interface, not [AccountImportRecords], not [AccountLibraryActions], not
 * [AccountSyncEngine.requestSync]. It runs while the engine holds its lock, and
 * that lock is not re-entrant: a callback that waits on the engine from inside
 * a transform would wait for ever. [AccountSyncEngine] detects the call made
 * from the transform's own thread and throws [IllegalStateException] instead of
 * hanging; one handed to another thread and awaited is not detectable, and is a
 * deadlock. Compute what the transform needs *before* calling the update, and
 * act on its outcome *after* it returns.
 *
 * **A `transform` must be pure** (#149): a function of its argument, with no
 * side effects. The guard is a flag on the transform's thread, so a side effect
 * that synchronously resumes another coroutine on that thread — completing a
 * deferred, emitting to a flow collected on `Dispatchers.Unconfined`, a nested
 * `runBlocking` that drains the thread's event loop — would run that coroutine
 * inside the flag, and an engine call it makes would throw although it is not
 * the transform's own.
 *
 * A key the document reserves — one its schema declares at that level, or
 * `host` — is refused with [ReservedHostRecordKeyException] before anything is
 * written (#149), because a record under it would be stored and then lost.
 */
public interface AccountHostRecords {

    /** The signed-in account's user id, or null when nobody is signed in. */
    public fun accountId(): String?

    /** The document-level host record [key], or null when there is none or nobody is signed in. */
    public suspend fun hostRecord(key: String): JsonElement?

    /**
     * Replaces the document-level host record [key] with what [transform]
     * returns for its current value; null removes it.
     *
     * [transform] runs under the engine's lock and must not call back into the
     * engine. Throws [ReservedHostRecordKeyException] when [key] is in
     * [AccountLibraryCodec.RESERVED_DOCUMENT_KEYS].
     */
    public suspend fun updateHostRecord(key: String, transform: (JsonElement?) -> JsonElement?)

    /**
     * Replaces the host record [key] on [bookId]'s row with what [transform]
     * returns for its current value; null removes it. A book the account has no
     * row for is skipped rather than invented.
     *
     * [transform] runs under the engine's lock and must not call back into the
     * engine. Throws [ReservedHostRecordKeyException] when [key] is in
     * [AccountLibraryCodec.RESERVED_BOOK_KEYS].
     */
    public suspend fun updateBookHostRecord(bookId: String, key: String, transform: (JsonElement?) -> JsonElement?)
}
