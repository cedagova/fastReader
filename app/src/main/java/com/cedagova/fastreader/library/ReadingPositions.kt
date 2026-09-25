package com.cedagova.fastreader.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Where the reader is in each book, and whether a book has had its one
 * front-matter offer (#204).
 *
 * The reader's durable per-book state. It is stored in `catalog.json` beside
 * the books it describes, and reaches the file through the [CatalogDocument],
 * the same single writer as every other catalog change.
 */
class ReadingPositions internal constructor(
    private val document: CatalogDocument,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    flushIntervalMs: Long,
) {

    /**
     * Coalesces reading positions so the reader can report one per word without
     * putting a durable write between two frames. See [ReadingPositionWriter].
     */
    private val writer = ReadingPositionWriter(scope, flushIntervalMs) { bookId, state -> write(bookId, state) }

    /** Non-null while the store is refusing writes, so a lost position is never silent. */
    val persistenceFailure: StateFlow<String?> get() = document.persistenceFailure

    /** The retained position for a book, including one that was removed and re-added. */
    fun readingState(bookId: String): ReadingState? = document.catalog.value.readingStates[bookId]

    /**
     * Notes where the reader is, without writing yet (REQ-016).
     *
     * Safe to call once per word: the position is held in memory and written at
     * most twice a second. Anything that is not "the next word" should follow it
     * with [flush].
     */
    fun record(bookId: String, state: ReadingState) = writer.record(bookId, state)

    /** Makes the last [record] durable now. Returns the job doing it. */
    fun flush(): Job = writer.flush()

    /** Stores the reading position for a book, waiting for the write. */
    suspend fun update(bookId: String, state: ReadingState) = write(bookId, state)

    /**
     * Records that this book has been offered the front-matter skip (REQ-202).
     *
     * Written whichever way the reader answered, because the requirement is that
     * the offer is made *once*: someone who chose to start at the cover has
     * answered the question and must not be asked it again.
     *
     * A book that is already in the set is not rewritten, so answering the offer
     * on a book that somehow reached it twice costs no catalog write.
     */
    suspend fun markFrontMatterOffered(bookId: String) = document.write { catalog ->
        if (bookId in catalog.frontMatterOfferedBookIds) {
            catalog
        } else {
            catalog.copy(frontMatterOfferedBookIds = catalog.frontMatterOfferedBookIds + bookId)
        }
    }

    /** Fire-and-forget [markFrontMatterOffered], for the reader's callbacks. */
    fun requestMarkFrontMatterOffered(bookId: String): Job = scope.launch { markFrontMatterOffered(bookId) }

    /**
     * The one place a position reaches the store.
     *
     * Recording a position is also what makes a book the last-read one, which is
     * what launch resumes into (REQ-009) — but only for a book the catalog has.
     * The id is still kept for a book that is currently missing or removed,
     * because the launch routing has to name the book it could not open; what it
     * is *not* kept for is a book that was never a library row at all.
     *
     * That case is new in v1.1.0: a session-only "Open with" stores a position
     * under a digest the catalog does not list (REQ-103, AD-9). Making that the
     * last-read book would send the next launch looking for a row that does not
     * exist and land the reader on "it is no longer in your library" — a sentence
     * about a book they never added. Leaving the id alone means the last book
     * they actually own stays the one launch comes back to, and the external
     * book's position is kept exactly as the definition says, waiting for the
     * file to be added.
     *
     * ## The one thing a write must not do (AD-18)
     *
     * A position taken from an open that produced no structural fingerprint —
     * the streaming fallback, or a book whose layout the directory reader refuses
     * — carries a null. Storing that null over a fingerprint already recorded
     * would disarm the content-change guard for that book silently and for good:
     * nothing would report it, and the next swapped file would resume at an
     * arbitrary word again. So a null keeps what is stored, and only a real
     * fingerprint replaces one. A book only ever gains this protection.
     *
     * Done here rather than at the reader's boundary because this is the single
     * place a position reaches the store, and it runs under the catalog lock
     * with the current document in hand — so the read of the previous value and
     * the write of the new one cannot interleave with another write.
     */
    private suspend fun write(bookId: String, state: ReadingState) = document.write { catalog ->
        val storedFingerprint = catalog.readingStates[bookId]?.structuralFingerprint
        val next = state.copy(
            structuralFingerprint = state.structuralFingerprint ?: storedFingerprint,
            updatedAtEpochMs = clock(),
        )
        catalog.copy(
            readingStates = catalog.readingStates + (bookId to next),
            lastReadBookId = if (catalog.book(bookId) != null) bookId else catalog.lastReadBookId,
        )
    }
}
