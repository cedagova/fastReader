package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderPortableLocationV1
import com.cedagova.reader.library.model.ReaderSyncMutationBatchRequest
import com.cedagova.reader.library.model.ReaderSyncStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One sync run's exchange with the backend, after the capability read: drain
 * the outbox, then read the change stream from the stored cursor or bootstrap
 * from the lists.
 *
 * [AccountSyncEngine] decides *when* this runs and what state a run ends in;
 * this says *what is sent and read, in which order*, and writes each answer to
 * the account document through [ActiveAccount.persist] as it arrives. Every
 * call runs under the engine's lock, so the document it is handed has no other
 * writer while it runs. What a result or a change does to a row is
 * [CanonicalStateAdoption]'s.
 */
internal class AccountLibraryExchange(
    private val adoption: CanonicalStateAdoption,
    /** Publishes the bootstrapping phase, when a bootstrap announces itself. */
    private val announceBootstrap: (AccountSyncTrigger) -> Unit,
) {

    /**
     * Sends every queued mutation, oldest first, and adopts each result.
     *
     * The store is rewritten after every batch, so what it holds is always
     * exactly what has not been admitted: a process death mid-drain loses no
     * entry, and the entries it does still hold carry their original
     * idempotency keys, so the next attempt is a replay rather than a second
     * admission.
     *
     * Returns the last rejection the backend reported, so the caller can
     * surface its code — a rejection is not a failure of the run.
     *
     * A non-retryable refusal of a library change clears the cursor in the very
     * save that drops the entry (#151). The refusal carries `{}`, so only a read
     * of the account can undo its optimistic row; recording that owed read in
     * the document rather than in memory is what keeps it owed when the read
     * fails or the process dies before it — the next run finds no cursor and
     * bootstraps, whatever happened in between. No schema change: a missing
     * cursor already means "read the lists".
     */
    suspend fun drainOutbox(account: ActiveAccount, gateway: ReaderLibraryGateway): DrainOutcome {
        val pending = account.document.outbox
        if (pending.isEmpty()) return DrainOutcome(rejection = null)

        val kept = mutableListOf<AccountOutboxEntry>()
        var rejection: AccountSyncError.Rejected? = null
        var index = 0
        while (index < pending.size) {
            val batch = pending.subList(index, minOf(index + MAX_BATCH, pending.size))
            val response = try {
                gateway.applyMutations(batch.map { it.toEnvelope() })
            } catch (e: ReaderAuthException) {
                // Nothing in this batch was admitted: keep it, and everything
                // after it, under the same keys.
                account.persist(account.document.copy(outbox = kept + pending.drop(index)))
                throw e
            }
            val byKey = response.results.associateBy { it.idempotencyKey }
            var document = account.document
            for (entry in batch) {
                val result = byKey[entry.idempotencyKey]
                if (result == null) {
                    // No answer for this envelope: keep it and retry under the
                    // same key, which the backend will replay if it did admit it.
                    kept += entry
                    continue
                }
                document = adoption.adopt(document, result)
                if (result.status == ReaderSyncStatus.REJECTED) {
                    val retryable = result.rejection?.retryable == true
                    result.rejection?.let { rejection = it.toSyncError(result.resourceId) }
                    if (retryable) {
                        kept += entry
                    } else if (entry.resourceType.isLibraryItem()) {
                        // Dropped for good, and its optimistic row with it: a
                        // refusal carries `{}`, so nothing here can say what the
                        // row was before. The repair read does (#151), and the
                        // cleared cursor owes it durably, saved with the drop
                        // below. A refused position changed no row, so it needs none.
                        document = document.copy(cursor = null)
                    }
                }
            }
            index += batch.size
            account.persist(document.copy(outbox = kept + pending.drop(index)))
        }
        return DrainOutcome(rejection = rejection)
    }

    /** What one drain of the outbox reports to the run that made it. */
    class DrainOutcome(
        /** The last rejection the backend stated, surfaced with its own code. */
        val rejection: AccountSyncError.Rejected?,
    )

    /**
     * Reads the account's lists and takes the stream's head as the cursor.
     *
     * The head is read *first*, on purpose: a change admitted between the head
     * read and the lists is then still after the stored cursor and arrives with
     * the next delta read. Reading it afterwards would place the cursor past a
     * change the lists never showed, and that change would be lost. `latest_cursor`
     * is present in every delta answer, an expired one included, so the head read
     * asks for one change and uses nothing but that field.
     *
     * ## A merge, not a rebuild (#151)
     *
     * The snapshot is merged into the stored rows, because the stored rows carry
     * what no list read can state:
     *
     * - a book the lists still return keeps its host records and its known
     *   revision ([AccountBook.of]); every field the lists state is theirs;
     * - a book the lists no longer return is gone from the account, so its row
     *   goes — host records with it;
     * - every change still queued in the outbox is re-applied on top, in queue
     *   order ([withQueuedIntents]), which is client contract §7.2 step 5:
     *   "reconcile the saved outbox against this canonical state". The queue
     *   itself is untouched — same entries, same idempotency keys — so nothing is
     *   re-sent or re-minted.
     *
     * The document's own host records, import records and outbox are carried
     * through as they stand. [announce] is false for the repair read after a
     * refusal: the shelf is not being loaded, only corrected, so it stays in the
     * syncing phase.
     */
    suspend fun bootstrap(
        account: ActiveAccount,
        gateway: ReaderLibraryGateway,
        trigger: AccountSyncTrigger,
        announce: Boolean = true,
    ) {
        if (announce) announceBootstrap(trigger)
        val head = gateway.deltas(ReaderLibraryClient.FIRST_CURSOR, HEAD_LIMIT)
        val library = gateway.library()
        val positions = gateway.progress().progress.associateBy { it.bookId }
        val stored = account.document
        val books = library.items.map { item ->
            val row = AccountBook.of(item, held = stored.book(item.book.id))
            positions[row.bookId]?.let { progress ->
                // The bootstrap's second list is keyed by `book_id`, which the
                // document marks required on `ReaderProgress` — so unlike the
                // stream there is nothing to derive here, and the portable
                // location is read through the same seam the stream uses (#120, #139).
                //
                // A list read carries no revision. When it returns the very record
                // this device already held — same server `updated_at` — the stored
                // revision stands, and so does the mark that the record is this
                // device's own (#140); a re-bootstrap must not turn this device's
                // own position into "another device's". A different record keeps
                // neither.
                val held = stored.book(row.bookId)
                    ?.takeIf { it.remotePosition?.updatedAt == progress.updatedAt }
                val remote = AccountCanonicalPayload.remotePosition(
                    position = PortableProgress.positionOf(
                        buildJsonObject {
                            put(
                                "location",
                                Json.encodeToJsonElement(ReaderPortableLocationV1.serializer(), progress.location),
                            )
                            put("progress_percent", JsonPrimitive(progress.progressPercent))
                            put("updated_at", JsonPrimitive(progress.updatedAt))
                            progress.chapterTitle?.let { put("chapter_title", JsonPrimitive(it)) }
                        },
                    ),
                    existing = held?.remotePosition,
                    revision = null,
                    serverAdmittedAt = null,
                )
                row.copy(
                    progressPercent = progress.progressPercent,
                    progressUpdatedAt = progress.updatedAt,
                    remotePosition = remote,
                    ownPositionChangeKey = held?.ownPositionChangeKey?.takeIf { it == remote.changeKey },
                )
            } ?: row
        }
        account.persist(stored.copy(books = books, cursor = head.latestCursor).withQueuedIntents())
    }

    /** Reads the stream from the stored cursor until `has_more` is false. */
    suspend fun readDeltas(account: ActiveAccount, gateway: ReaderLibraryGateway, trigger: AccountSyncTrigger) {
        val stored = account.document.cursor
        if (stored == null || !CURSOR.matches(stored)) {
            // A cursor this client cannot send is no cursor: start over rather
            // than hand `:reader-library` a value it refuses.
            bootstrap(account, gateway, trigger)
            return
        }
        var cursor: String = stored
        while (true) {
            val response = gateway.deltas(cursor, ReaderLibraryClient.DEFAULT_DELTA_LIMIT)
            if (response.rebootstrapRequired ||
                response.status == ReaderDeltaStatus.CURSOR_EXPIRED ||
                response.status == ReaderDeltaStatus.CURSOR_INVALID
            ) {
                bootstrap(account, gateway, trigger)
                return
            }
            var document = account.document
            for (change in response.changes) {
                document = adoption.applyCanonical(
                    document = document,
                    resourceType = change.resourceType,
                    resourceId = change.resourceId,
                    kind = change.kind,
                    payload = change.canonicalPayload,
                    revision = change.revision,
                    serverAdmittedAt = change.serverAdmittedAt,
                    origin = CanonicalOrigin.STREAM,
                )
            }
            val next = response.nextCursor ?: response.latestCursor
            account.persist(document.copy(cursor = next))
            // A page that says there is more but does not advance would loop for
            // ever; stop and let the next trigger try again.
            if (!response.hasMore || next == cursor) break
            cursor = next
        }
    }

    private companion object {
        val MAX_BATCH: Int = ReaderSyncMutationBatchRequest.MAX_MUTATIONS

        /** The head read wants `latest_cursor` and nothing else, so it asks for one change. */
        const val HEAD_LIMIT: Int = ReaderLibraryClient.MIN_DELTA_LIMIT

        /** The shape `:reader-library` accepts as a cursor. */
        val CURSOR = Regex("^[0-9]+$")
    }
}

/**
 * True when a bootstrap would load the shelf rather than correct it: no row
 * is held yet. A document without a cursor but with rows is owed the repair
 * read after a refusal (#151), which keeps the shelf on screen.
 */
internal val AccountLibraryDocument.loadsShelf: Boolean
    get() = !bootstrapped && books.isEmpty()
