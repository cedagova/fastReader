package com.cedagova.reader.library.sync

import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderResourceType
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The outbox: how an own write becomes a queued entry, and the optimistic
// intent a queued entry puts on its row until the backend answers. Pure
// document transforms; [AccountSyncEngine] alone decides when they run, under
// its lock.

/** One own write as it was asked for, before it reaches the outbox. */
internal class QueuedChange(
    val owner: ChangeOwner,
    val bookId: String,
    val kind: ReaderMutationKind,
    val payload: JsonObject,
    val resourceType: ReaderResourceType,
    /** AD-25's key, for a position only: recorded once the entry is persisted (#163). */
    val publishKey: Pair<String?, Int>?,
)

/** This document with [change] appended to its outbox and its intent on the row. */
internal fun AccountLibraryDocument.queuing(
    change: QueuedChange,
    newIdempotencyKey: () -> String,
    now: () -> String,
): AccountLibraryDocument {
    val existing = book(change.bookId)
    val entry = AccountOutboxEntry(
        idempotencyKey = newIdempotencyKey(),
        resourceType = change.resourceType,
        resourceId = change.bookId,
        mutationKind = change.kind,
        // The revision of the resource this envelope is about, which
        // for a position is the *progress* resource's own and not the
        // library item's — two resources whose revisions have nothing
        // to do with each other. 0 for one this device has never seen,
        // which is what the contract asks for.
        baseRevision = when (change.resourceType) {
            ReaderResourceType.READING_PROGRESS -> existing?.remotePosition?.revision ?: 0
            else -> existing?.revision ?: 0
        },
        payload = change.payload,
        clientCreatedAt = now(),
    )
    val queued = copy(outbox = outbox + entry)
    return if (existing != null) queued.withBook(intentOf(entry, existing)) else queued
}

/**
 * The row as [entry]'s queued intent leaves it: what the shelf shows while
 * the change waits to be admitted.
 *
 * A pure function of the stored entry, so the intent applied when the change
 * is queued and the intent re-applied on top of a re-bootstrap's snapshot
 * (#151) are one and the same. A removal takes the row off the shelf, an Undo
 * puts it back, a status upsert sets the status and, when the payload names
 * one, the last-opened time. A position changes no library row — its
 * account-side record arrives with the backend's answer.
 */
internal fun intentOf(entry: AccountOutboxEntry, book: AccountBook): AccountBook {
    if (!entry.resourceType.isLibraryItem()) return book
    return when (entry.mutationKind) {
        ReaderMutationKind.DELETE -> book.copy(removed = true)

        ReaderMutationKind.RESTORE -> book.copy(removed = false)

        ReaderMutationKind.UPSERT -> {
            val status = (entry.payload["status"] as? JsonPrimitive)?.content
                ?.let { wire -> ReaderLibraryStatus.entries.firstOrNull { it.wireName() == wire } }
            book.copy(
                status = status ?: book.status,
                lastOpenedAt = if (entry.payload.containsKey("last_opened_at")) {
                    (entry.payload["last_opened_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                } else {
                    book.lastOpenedAt
                },
            )
        }

        ReaderMutationKind.UNKNOWN -> book
    }
}

/**
 * Every queued change's intent, re-applied in queue order on top of this
 * document's rows (client contract §7.2 step 5, #151). A change for a book
 * the account holds no row for invents none, exactly as queueing it did not.
 */
internal fun AccountLibraryDocument.withQueuedIntents(): AccountLibraryDocument = outbox.fold(this) { document, entry ->
    document.book(entry.resourceId)?.let { document.withBook(intentOf(entry, it)) } ?: document
}

internal fun ReaderResourceType.isLibraryItem(): Boolean =
    this == ReaderResourceType.LIBRARY_ITEM || this == ReaderResourceType.BOOK

/**
 * D4's one destructive clause: the held queue of every *other* account on
 * this device is discarded when a different user id signs in. Their rows
 * are left alone — the backend holds those, and a re-bootstrap would fetch
 * them again anyway; the queue is the only thing nothing else can replace.
 */
internal fun AccountLibraryStores.discardOtherAccountQueues(userId: String) {
    exceptUser(userId).forEach { store ->
        val load = store.load()
        if (load is AccountLibraryLoad.Loaded && load.document.outbox.isNotEmpty()) {
            try {
                store.save(load.document.copy(outbox = emptyList()))
            } catch (error: IOException) {
                // Nothing is lost by leaving it: this account never reads that
                // document, and the next sign-in to it tries again.
            }
        }
    }
}
