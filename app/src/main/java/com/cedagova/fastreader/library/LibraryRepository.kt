package com.cedagova.fastreader.library

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The device library: what is in it, and the changes a reader makes to it —
 * adding and rescanning books and folders, removing them with an undo window,
 * and the private copies of account books (LEAF102, #118).
 *
 * Settings, reading positions and book bytes are not its business (#204): they
 * are [ReaderSettingsStore], [ReadingPositions] and [BookBytes]. All of them
 * share one [CatalogDocument], the single writer of `catalog.json`, and are
 * built together by [DeviceLibrary].
 *
 * Runs all file work off the main thread. When the stored catalog is unreadable
 * in a way that would lose data, the document refuses to write and reports it
 * instead.
 */
class LibraryRepository internal constructor(
    private val document: CatalogDocument,
    private val ingestor: CatalogIngestor,
    private val gateway: DocumentGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val minimumRescanIntervalMs: Long,
    private val undoWindowMs: Long,
) {

    /**
     * The book removal that can still be taken back, and the timer that ends the
     * offer. Held under [undoMutex] rather than the document's lock so claiming
     * the pending removal never nests inside a catalog write, which would
     * deadlock.
     */
    private val undoMutex = Mutex()
    private var pendingRemoval: PendingRemoval? = null
    private val _undoableRemoval = MutableStateFlow<RemovedBook?>(null)

    /** Read and written only inside a [CatalogDocument.transaction], under its lock. */
    private var lastScanAtEpochMs = 0L

    /** The current catalog. Empty until the first load completes. */
    val catalog: StateFlow<Catalog> get() = document.catalog

    /** Loading/result state for the library's loading and refresh affordances. */
    val ingestion: StateFlow<IngestionState> get() = document.ingestion

    /**
     * The book the reader has just removed, while taking it back is still on
     * offer (REQ-105). Null once [undoRemoveBook] ran or the window elapsed.
     */
    val undoableRemoval: StateFlow<RemovedBook?> = _undoableRemoval.asStateFlow()

    /** Loads the stored catalog without scanning. Safe to call repeatedly. */
    suspend fun load() = document.load()

    /**
     * Re-checks whether the last-read book is still reachable, and nothing else.
     *
     * Launch routing (REQ-009) has to know the *current* answer: a folder whose
     * permission was revoked yesterday still reads AVAILABLE in the stored catalog
     * until something looks. The app-open rescan does look, but it walks every
     * added folder, and the launch budget is under three seconds — so this asks
     * the provider about one book's sources, which is one query in the normal case
     * of a book reachable from one place.
     *
     * It writes only when the answer changed, so the ordinary launch does no I/O
     * beyond the read it already did.
     */
    suspend fun refreshLastReadBook() = document.transaction {
        val catalog = current
        val bookId = catalog.lastReadBookId ?: return@transaction
        val book = catalog.book(bookId) ?: return@transaction
        if (book.sources.isEmpty()) return@transaction
        val refreshed = withContext(ioDispatcher) {
            book.sources.map { source ->
                // A private copy has no provider to ask and no permission to
                // lose: the file either is there or is not (#118).
                val availability = if (source.isAccountCopy) {
                    if (source.filePath?.let { File(it).isFile } == true) {
                        SourceAvailability.AVAILABLE
                    } else {
                        SourceAvailability.MISSING
                    }
                } else {
                    gateway.lookup(source.uri).availability()
                }
                source.copy(availability = availability)
            }
        }
        if (refreshed == book.sources) return@transaction
        val next = catalog.copy(
            books = catalog.books.map { if (it.id == bookId) it.copy(sources = refreshed) else it },
        )
        try {
            save(next)
            publish(next)
        } catch (error: Exception) {
            // Routing still uses the fresher in-memory answer; the write failing
            // is a library problem, not a reason to resume into an unreadable book.
            publish(next)
            reportWriteFailure(error.message ?: "the library could not be updated")
        }
    }

    /** Adds individually picked EPUB files (REQ-001). */
    suspend fun addPickedBooks(uris: List<String>) {
        if (uris.isEmpty()) return
        mutate(ScanTrigger.ADD_BOOKS) { catalog, progress ->
            ingestor.addPickedBooks(catalog, uris, progress)
        }
    }

    /**
     * Adds a folder whose EPUBs are discovered recursively (REQ-002). The folder's
     * own name is used when [displayName] is not supplied.
     */
    suspend fun addFolder(treeUri: String, displayName: String? = null) {
        mutate(ScanTrigger.ADD_FOLDER) { catalog, progress ->
            val name = displayName
                ?: gateway.displayName(treeUri)
                ?: treeUri.substringAfterLast('/').ifBlank { treeUri }
            ingestor.addFolder(catalog, treeUri, name, progress)
        }
    }

    /**
     * Rescans added folders and picked files.
     *
     * [ScanTrigger.MANUAL_REFRESH] always runs; [ScanTrigger.APP_OPEN] is skipped
     * when a scan finished less than [DEFAULT_MINIMUM_RESCAN_INTERVAL_MS] ago, so
     * returning from the document picker — or from any app the reader stepped out
     * to — does not re-list every added folder (REQ-204).
     *
     * A skipped rescan returns before [IngestionState.Scanning] is ever
     * published, which is the whole of "no scanning banner": the library keeps
     * showing the stored catalog and says nothing about a scan that did not run.
     */
    suspend fun rescan(trigger: ScanTrigger) = mutate(
        trigger = trigger,
        // Read under the same lock that owns every other mutation.
        skip = {
            trigger == ScanTrigger.APP_OPEN &&
                lastScanAtEpochMs != 0L &&
                clock() - lastScanAtEpochMs < minimumRescanIntervalMs
        },
    ) { catalog, progress -> ingestor.rescan(catalog, progress) }

    /**
     * Removes a catalog entry. The file stays on the device and the position is
     * kept (REQ-004). The removal survives folder rescans; picking the file
     * again, or re-adding its folder, brings the book back.
     *
     * For a short window afterwards the removal can be taken back with
     * [undoRemoveBook] (REQ-105). Until that window closes the book's read
     * grants are still held, so undo restores a row that is genuinely readable
     * rather than one whose file the app can no longer open.
     */
    suspend fun removeBook(bookId: String) {
        var removed: Book? = null
        document.write { catalog ->
            removed = catalog.book(bookId)
            if (removed == null) catalog else ingestor.removeBook(catalog, bookId)
        }
        val book = removed ?: return
        // A write that failed left the book on screen; offering to undo a removal
        // that did not happen would be a lie the reader could tap.
        if (document.catalog.value.book(bookId) != null) return
        offerUndo(book)
    }

    /**
     * Puts back the book the reader has just removed, with its position and
     * progress (REQ-105). Does nothing once the window has closed.
     */
    suspend fun undoRemoveBook() {
        val pending = claimPendingRemoval(null) ?: return
        pending.timer.cancel()
        document.write { ingestor.restoreBook(it, pending.book) }
    }

    /**
     * Removes an added folder and the entries only it provided
     * ([Catalog.booksOnlyFrom]). Files are never touched and every position is
     * kept, including those of the books that left (REQ-104, REQ-004).
     */
    suspend fun removeFolder(folderId: String) = document.write { ingestor.removeFolder(it, folderId) }

    /**
     * Adds the verified private copy of an account book to the catalog (#118).
     *
     * [file] must already have been verified by
     * `com.cedagova.reader.account.library.AccountCopyStore`: this writes a
     * catalog row, and a row is a promise that the bytes are the book. Returns
     * the id of the row the copy belongs to — the same id a device book of the
     * same content already has, when there is one — or null when the file could
     * not be read as an EPUB at all.
     */
    suspend fun addAccountCopy(contentSha256: String, file: File, displayName: String): String? {
        document.write { catalog ->
            withContext(ioDispatcher) { ingestor.addAccountCopy(catalog, contentSha256, file, displayName).catalog }
        }
        val uri = BookSource.accountCopyUri(contentSha256)
        return document.catalog.value.books.firstOrNull { book -> book.sources.any { it.uri == uri } }?.id
    }

    /**
     * Drops a private copy's source, and the row with it when no other source
     * remains (D2's **Remove downloaded copy**).
     *
     * The file is the copy store's to delete; the position is kept, as it is for
     * every other removal (REQ-004), so downloading the book again resumes where
     * the reader left off.
     */
    suspend fun removeAccountCopy(bookId: String) =
        document.write { catalog -> ingestor.removeAccountCopy(catalog, bookId) }

    /**
     * Re-checks whether each private copy's file is still there (#118).
     *
     * Called on start. A copy can leave without the catalog hearing about it,
     * and a row that claims to be readable when its bytes are gone is the one
     * state the library has no way to explain.
     */
    suspend fun reconcileAccountCopies(exists: (String) -> Boolean = { File(it).isFile }) = document.transaction {
        val catalog = current
        val next = withContext(ioDispatcher) { ingestor.reconcileAccountCopies(catalog, exists) }
        // Deliberately not [CatalogDocument.write]: this runs on every process that has
        // ever downloaded a book, and the answer is almost always "everything is
        // where it was". A write that changes nothing is still a write — one
        // that would re-save the catalog and re-push the theme mirror on every
        // start — so the unchanged case does nothing at all.
        if (next == catalog) return@transaction
        try {
            save(next)
            publish(next)
        } catch (error: Exception) {
            // A row that claims to be readable when its bytes are gone is worse
            // than a stale document, so the in-memory answer is published either
            // way and the failure is reported the way every other write failure is.
            publish(next)
            reportWriteFailure(error.message ?: "the library could not be updated")
        }
    }

    /** Fire-and-forget wrappers for callers without a coroutine scope of their own. */
    fun requestRescan(trigger: ScanTrigger) = scope.launch { rescan(trigger) }

    fun requestAddPickedBooks(uris: List<String>) = scope.launch { addPickedBooks(uris) }

    fun requestAddFolder(treeUri: String, displayName: String? = null) =
        scope.launch { addFolder(treeUri, displayName) }

    fun requestRemoveBook(bookId: String) = scope.launch { removeBook(bookId) }

    fun requestUndoRemoveBook() = scope.launch { undoRemoveBook() }

    fun requestRemoveFolder(folderId: String) = scope.launch { removeFolder(folderId) }

    /**
     * Starts the undo window for [book] and closes any window still open, which
     * makes that earlier removal final.
     */
    private suspend fun offerUndo(book: Book) {
        val superseded = undoMutex.withLock {
            val previous = pendingRemoval
            val timer = scope.launch {
                delay(undoWindowMs)
                // This coroutine *is* the timer, so it claims but never cancels.
                claimPendingRemoval(book.id)?.let { releaseGrantsNoLongerNeeded(it.book) }
            }
            pendingRemoval = PendingRemoval(book, timer)
            _undoableRemoval.value = RemovedBook(book.id, book.title)
            previous?.timer?.cancel()
            previous
        }
        superseded?.let { releaseGrantsNoLongerNeeded(it.book) }
    }

    /**
     * Takes the pending removal, if it is still there and is the expected one.
     * Whoever claims it owns finishing it, so the timer and undo cannot both
     * act, and the claim carries its own timer so no caller has to guess which
     * job it is cancelling.
     */
    private suspend fun claimPendingRemoval(expectedBookId: String?): PendingRemoval? = undoMutex.withLock {
        val pending = pendingRemoval
        if (pending == null || (expectedBookId != null && pending.book.id != expectedBookId)) {
            return@withLock null
        }
        pendingRemoval = null
        _undoableRemoval.value = null
        pending
    }

    /**
     * Gives back only the grants nothing in the catalog still uses.
     *
     * Re-picking the same file inside the undo window brings the book back with
     * its grant; the timer must not then release a permission the library is
     * relying on. There is no suspension point after the claim, so a cancelled
     * timer cannot stop half-way through this.
     */
    private fun releaseGrantsNoLongerNeeded(book: Book) {
        val stillUsed = document.catalog.value.book(book.id)?.sources?.mapTo(HashSet()) { it.uri }.orEmpty()
        ingestor.releaseGrants(book.sources.filterNot { it.uri in stillUsed })
    }

    private suspend fun mutate(
        trigger: ScanTrigger,
        skip: () -> Boolean = { false },
        block: (Catalog, ScanProgress) -> IngestOutcome,
    ) = document.transaction {
        if (skip()) return@transaction
        report(IngestionState.Scanning(trigger))
        val progress = ScanProgress { processed, total, name ->
            report(IngestionState.Scanning(trigger, processed, total, name))
        }
        try {
            val outcome = withContext(ioDispatcher) { block(current, progress) }
            save(outcome.catalog)
            publish(outcome.catalog)
            clearPersistenceFailure()
            lastScanAtEpochMs = clock()
            report(
                IngestionState.Completed(
                    trigger = trigger,
                    added = outcome.added,
                    updated = outcome.updated,
                    rejected = outcome.rejected,
                    unavailable = outcome.unavailable,
                    finishedAtEpochMs = lastScanAtEpochMs,
                ),
            )
        } catch (error: Exception) {
            report(IngestionState.Failed(error.message ?: "the library could not be updated"))
        }
    }

    companion object {
        /**
         * How recently a scan must have finished for the app-open rescan to be
         * skipped (REQ-204's "short interval").
         *
         * One minute, chosen against the two things the number has to hold apart.
         *
         * The behaviour REQ-204 asks for is the app switch: the reader leaves to
         * answer a message or look something up and comes back, and re-listing
         * every added folder for that is work nothing asked for and a scanning
         * banner over a library that has not changed. A minute covers that trip
         * comfortably; the 2 s this replaces covered almost none of it, and only
         * ever stopped the picker's own return from scanning twice.
         *
         * The other side is the promise the empty-library copy makes in as many
         * words — books added to a folder later "show up the next time you open
         * the app". A reader who genuinely goes to a file manager, finds a book,
         * copies it and comes back has spent more than a minute doing it, so that
         * still holds automatically; and REQ-204's own second half means the
         * refresh control finds it either way, immediately, at any point inside
         * the interval.
         *
         * This is deliberately not persisted. [lastScanAtEpochMs] lives in the
         * process, so a cold start always scans however recently the last one
         * ran: the interval can only ever suppress a rescan inside one run of
         * the app, never across a real relaunch.
         */
        const val DEFAULT_MINIMUM_RESCAN_INTERVAL_MS = 60_000L

        /**
         * How long taking a removal back stays on offer (REQ-105's "short time").
         * Long enough to read the sentence and reach the control at a large font
         * size, short enough that the library is not left in two minds about
         * whether a book is in it.
         */
        const val DEFAULT_UNDO_WINDOW_MS = 8_000L
    }
}

/** A book whose removal can still be taken back, named so the library can say which. */
data class RemovedBook(val bookId: String, val title: String)

/** The removed entry and the job that will make its removal final. */
private class PendingRemoval(val book: Book, val timer: Job)

private fun DocumentLookup.availability(): SourceAvailability = when (this) {
    is DocumentLookup.Found -> SourceAvailability.AVAILABLE
    DocumentLookup.Missing -> SourceAvailability.MISSING
    DocumentLookup.PermissionLost -> SourceAvailability.PERMISSION_LOST
}

/**
 * Gives back every long-lived grant the catalog no longer references.
 *
 * `LibraryRepository.releaseGrantsNoLongerNeeded` only runs while the process that removed the
 * book is alive. A reader who removes a book and then swipes the app away
 * inside the undo window leaves the row gone from the stored catalog and the
 * grant still held, with nothing left in memory that knows about it — and
 * Android caps how many persisted grants an app may hold, so that leak
 * eventually stops the reader from adding books at all. The platform's own
 * list is the only record that survives, so reconciling against it at load
 * closes that window and any grant an earlier crash orphaned.
 *
 * It runs exactly once per process, as the [CatalogDocument]'s clean-load hook
 * from the first successful load, which is necessarily before this process can
 * have a removal pending.
 *
 * It runs only on a load that produced a *genuine* catalog. Two loads report
 * an empty one without meaning the library is empty, and sweeping against
 * either would release the grant for every book the reader has:
 *
 * - a **blocked** store, which refuses to be read at all; and
 * - a **recovered** one, where a damaged document was set aside under
 *   `recoveredFrom` and the app carried on with an empty catalog. That path
 *   exists to make corruption survivable — the document is kept, not
 *   deleted. A grant cannot be taken again except by sending the reader back
 *   through the document picker, so releasing them here would destroy the
 *   access the set-aside document describes and make it unrecoverable even
 *   if repaired. Grants orphaned before the corruption simply wait for the
 *   next clean load.
 *
 * A migrated catalog is a real one and needs no such guard.
 *
 * A grant that cannot be enumerated or given back is a housekeeping miss,
 * not a reason to refuse to show the library, so it does not fail the load.
 */
internal fun DocumentGateway.releaseOrphanedGrants(catalog: Catalog) {
    try {
        val referenced = HashSet<String>()
        catalog.books.forEach { book -> book.sources.forEach { referenced += it.uri } }
        catalog.folders.forEach { referenced += it.treeUri }
        persistedReadPermissions()
            .filterNot { it in referenced }
            // Neither gateway distinguishes a tree from a document when
            // giving a grant back; a sweep cannot know which an orphan is.
            .forEach { releaseReadPermission(it, isTree = false) }
    } catch (_: Exception) {
        // Deliberately quiet: see above.
    }
}
