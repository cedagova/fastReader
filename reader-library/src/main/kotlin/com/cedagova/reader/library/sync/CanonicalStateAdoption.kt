package com.cedagova.reader.library.sync

import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncMutationResult
import com.cedagova.reader.library.model.ReaderSyncStatus
import kotlinx.serialization.json.JsonObject

/**
 * Adopting the backend's canonical state into the account document (AD-22,
 * client contract §7.3 and §7.4).
 *
 * Every mutation result and every stream change goes through here, and the
 * rules that decide whether it replaces the stored row — freshness by revision,
 * presence of a library item, the mark that a position is this device's own —
 * live nowhere else. The functions are pure transforms of the document they are
 * handed, except that a progress record this app cannot place is remembered
 * until the engine surfaces it once ([takeProgressMismatch]).
 *
 * Used only under the engine's lock, like everything else that touches the
 * document; it holds no lock of its own.
 */
internal class CanonicalStateAdoption {

    /**
     * A `reading_progress` record this app could not place on a book, kept until
     * the next settled state has surfaced it once.
     *
     * The same shape as the engine's `pendingSessionGone` and for the same
     * reason: the record is noticed while the stream is being read, and the
     * state that reports it is published after. Without this the mapping derivation could be wrong for as
     * long as nobody happened to look — see
     * [AccountSyncError.UnrecognizedProgressRecord].
     */
    private var progressMismatch: AccountSyncError.UnrecognizedProgressRecord? = null

    /**
     * Adopts one mutation result. A rejection carries an empty canonical
     * payload by contract and changes no row here — a non-retryable one is
     * undone by the repair read the drain asks for (#151); everything else — `applied`,
     * `replayed`, `superseded` and `conflict` alike — is adopted as it stands,
     * unless its revision is older than the one already stored (§7.3, #147: a
     * late answer never moves a book's state backwards).
     *
     * `applied` and `replayed` are the backend admitting *this device's own*
     * mutation, so for a position they are recorded as this device's own
     * (#140). `superseded` and `conflict` carry the position that beat it —
     * somebody else's — and are adopted without that mark.
     */
    fun adopt(document: AccountLibraryDocument, result: ReaderSyncMutationResult): AccountLibraryDocument {
        if (result.status == ReaderSyncStatus.REJECTED) return document
        val payload = result.canonicalPayload.takeIf { it.isNotEmpty() }
            ?: result.conflict?.canonicalPayload
            ?: result.canonicalPayload
        return applyCanonical(
            document = document,
            resourceType = result.resourceType,
            resourceId = result.resourceId,
            kind = result.mutationKind,
            payload = payload,
            revision = result.revision ?: result.conflict?.remoteRevision,
            serverAdmittedAt = result.serverAdmittedAt,
            origin = when (result.status) {
                ReaderSyncStatus.APPLIED, ReaderSyncStatus.REPLAYED -> CanonicalOrigin.ADMITTED_HERE
                else -> CanonicalOrigin.RESULT
            },
        )
    }

    fun applyCanonical(
        document: AccountLibraryDocument,
        resourceType: ReaderResourceType,
        resourceId: String,
        kind: ReaderMutationKind,
        payload: JsonObject,
        revision: Long?,
        /** The server's admission time for this change, when it came from the stream. */
        serverAdmittedAt: String? = null,
        origin: CanonicalOrigin,
    ): AccountLibraryDocument = when (resourceType) {
        ReaderResourceType.LIBRARY_ITEM, ReaderResourceType.BOOK -> {
            val existing = document.book(resourceId)
            if (existing != null && revision != null && !replaces(revision, existing.revision, origin)) {
                // Client contract §7.3 (#147, #149): a stream change is applied only
                // when its revision is strictly newer than the stored one, and a
                // mutation result only when it is not older. An older answer — a late
                // `replayed` result, an out-of-order delivery — must not move the row
                // back or take it off the shelf; an equal-revision stream change is
                // the state this device already holds, and applying it would revert
                // a local intent still queued in the outbox (a pending removal would
                // reappear). A *result* at an equal revision is different (§7.4): it
                // is the backend's verdict on this device's own queued mutation, and
                // it is what discards an optimistic row the backend did not keep
                // (`superseded`, `conflict`).
                document
            } else if (present(kind, payload, origin)) {
                val row = AccountCanonicalPayload.libraryItem(existing, resourceId, payload, revision)
                document.withBook(row.copy(removed = false))
            } else {
                existing
                    ?.let { document.withBook(it.copy(removed = true, revision = revision ?: it.revision)) }
                    ?: document
            }
        }

        // Which book a position record is about is a *derivation*, not a fact the
        // document states, so it goes through the one seam that owns it and a
        // record this app cannot place becomes a visible outcome rather than a
        // dropped one (REQ-511).
        ReaderResourceType.READING_PROGRESS ->
            when (
                val record = PortableProgress.recordFor(
                    resourceId = resourceId,
                    payload = payload,
                    knownBook = { document.book(it) != null },
                )
            ) {
                is ProgressRecord.Recognized -> {
                    val existing = document.book(record.bookId)
                    val stored = existing?.remotePosition
                    if (existing != null && stored != null && revision != null && revision <= stored.revision) {
                        // Client contract §7.3: a result or stream change is applied
                        // only when its revision is newer than the one this device
                        // already holds for the resource — whatever its source
                        // (#147: a late `replayed` result used to overwrite a newer
                        // position the stream had delivered). The stream names no
                        // originating client, so this is also what recognises this
                        // device's own admitted position coming back to it (#140):
                        // it carries the very revision §7.4 stored when the result
                        // was adopted. An admitted result at the *same* revision is
                        // that same record, so it is still marked as this device's.
                        if (origin == CanonicalOrigin.ADMITTED_HERE && revision == stored.revision &&
                            existing.ownPositionChangeKey != stored.changeKey
                        ) {
                            document.withBook(existing.copy(ownPositionChangeKey = stored.changeKey))
                        } else {
                            document
                        }
                    } else {
                        AccountCanonicalPayload.readingProgress(
                            existing = existing,
                            payload = payload,
                            position = record.position,
                            revision = revision,
                            serverAdmittedAt = serverAdmittedAt,
                        )
                            ?.let { row ->
                                if (origin == CanonicalOrigin.ADMITTED_HERE) {
                                    row.copy(ownPositionChangeKey = row.remotePosition?.changeKey)
                                } else {
                                    row
                                }
                            }
                            ?.let(document::withBook)
                            ?: document
                    }
                }

                is ProgressRecord.Unrecognized -> {
                    progressMismatch = AccountSyncError.UnrecognizedProgressRecord(
                        resourceId = record.resourceId,
                        payloadBookId = record.payloadBookId,
                        reason = record.reason,
                    )
                    document
                }
            }

        // profile, settings, note and bookmark are never sent and never shown.
        ReaderResourceType.PROFILE,
        ReaderResourceType.SETTINGS,
        ReaderResourceType.NOTE,
        ReaderResourceType.BOOKMARK,
        ReaderResourceType.UNKNOWN,
        -> document
    }

    /**
     * Whether the canonical state of a library item is a live membership (true)
     * or the tombstone (false).
     *
     * A stream change and this device's own admitted mutation say it with their
     * kind: the stream's `kind` is the canonical change kind, and an `applied` or
     * `replayed` result is the backend doing exactly what was asked. A
     * `superseded` or `conflict` result is the backend's verdict *against* the
     * mutation, so its kind is this device's request, not the outcome (§7.4,
     * #149): presence comes from the canonical payload the backend returned. The
     * backend's canonical state for a removed membership is the empty object
     * (reader-api `publication_membership.py`, `_structural_conflict` at
     * `909174af`), and a live one is the item's body — so a queued removal the
     * backend answered with the live book ends on the shelf, and a queued upsert
     * answered with a tombstone ends off it.
     */
    private fun present(kind: ReaderMutationKind, payload: JsonObject, origin: CanonicalOrigin): Boolean =
        when (origin) {
            CanonicalOrigin.RESULT -> payload.isNotEmpty()
            CanonicalOrigin.ADMITTED_HERE, CanonicalOrigin.STREAM -> kind != ReaderMutationKind.DELETE
        }

    /**
     * Whether a library item's canonical state at [incoming] replaces the one
     * stored at [stored] (§7.3, §7.4): a stream change must be strictly newer, a
     * mutation result must be equal or newer.
     */
    private fun replaces(incoming: Long, stored: Long, origin: CanonicalOrigin): Boolean = when (origin) {
        CanonicalOrigin.STREAM -> incoming > stored
        CanonicalOrigin.ADMITTED_HERE, CanonicalOrigin.RESULT -> incoming >= stored
    }

    /**
     * The unplaceable progress record, if one arrived, and clears it.
     *
     * Taken rather than read so it is reported once per occurrence: the next
     * settled state after a clean run says nothing, which is what makes the
     * report mean "this happened just now" rather than "this happened once".
     */
    fun takeProgressMismatch(): AccountSyncError.UnrecognizedProgressRecord? {
        val mismatch = progressMismatch
        progressMismatch = null
        return mismatch
    }

    /**
     * Forgets a record not yet surfaced: it belonged to the session that just ended
     * (sign-out), so the next session's first settled state does not report it.
     */
    fun forgetProgressMismatch() {
        progressMismatch = null
    }
}

/**
 * Where a canonical payload came from, which decides the two rules of
 * [CanonicalStateAdoption] that differ by source.
 */
internal enum class CanonicalOrigin {
    /** This device's own mutation, `applied` or `replayed`: the canonical result is this device's write. */
    ADMITTED_HERE,

    /**
     * Any other mutation result — `superseded` or `conflict` — adopted as it
     * stands. Its mutation kind is this device's request, not the outcome, so
     * a library item's presence is read from the canonical payload.
     */
    RESULT,

    /** A change read from the account's change stream, which names no originating client. */
    STREAM,
}
