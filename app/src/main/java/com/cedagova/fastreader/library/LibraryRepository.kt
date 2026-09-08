package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CatalogLoad
import com.cedagova.fastreader.library.store.CatalogStore
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeMirror
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
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
 * The library's single entry point for the UI (LEAF102) and later for the reader.
 *
 * Owns the catalog in memory, serialises every mutation, and runs all file work
 * off the main thread. When the stored catalog is unreadable in a way that would
 * lose data, the repository refuses to write and reports it instead.
 */
class LibraryRepository(
    private val store: CatalogStore,
    private val ingestor: CatalogIngestor,
    private val gateway: DocumentGateway,
    private val covers: CoverStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Keeps the pre-Compose copy of the theme choice in step with the catalog
     * (AD-10). Defaults to [ThemeMirror.None], which mirrors nothing: only the
     * running app needs a real one.
     */
    private val themeMirror: ThemeMirror = ThemeMirror.None,
    private val minimumRescanIntervalMs: Long = DEFAULT_MINIMUM_RESCAN_INTERVAL_MS,
    private val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
    positionFlushIntervalMs: Long = ReadingPositionWriter.DEFAULT_INTERVAL_MILLIS,
) {

    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(Catalog())
    private val _ingestion = MutableStateFlow<IngestionState>(IngestionState.Idle)
    private val _persistenceFailure = MutableStateFlow<String?>(null)
    private val _settings = MutableStateFlow(ReaderSettings.DEFAULTS)

    /**
     * The book removal that can still be taken back, and the timer that ends the
     * offer. Held under [undoMutex] rather than [mutex] so claiming the pending
     * removal never nests inside a catalog write, which would deadlock.
     */
    private val undoMutex = Mutex()
    private var pendingRemoval: PendingRemoval? = null
    private val _undoableRemoval = MutableStateFlow<RemovedBook?>(null)

    /**
     * Coalesces reading positions so the reader can report one per word without
     * putting a durable write between two frames. See [ReadingPositionWriter].
     */
    private val positions = ReadingPositionWriter(scope, positionFlushIntervalMs) { bookId, state ->
        writeReadingState(bookId, state)
    }

    private var loaded = false
    private var blockedMessage: String? = null
    private var lastScanAtEpochMs = 0L

    /** The current catalog. Empty until the first load completes. */
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    /** Loading/result state for the library's loading and refresh affordances. */
    val ingestion: StateFlow<IngestionState> = _ingestion.asStateFlow()

    /**
     * Non-null while the store is refusing writes, so losing a reading position is
     * never silent (the definition's persistence guardrail). The reader shows it
     * on the reading surface, where the library's own banner is not visible.
     */
    val persistenceFailure: StateFlow<String?> = _persistenceFailure.asStateFlow()

    /**
     * How the reader wants books presented (LEAF302).
     *
     * A projection of [catalog], so there is exactly one source of truth: the
     * settings screen, the app's theme, the library and the reader all read the
     * value the store accepted, and a write that fails leaves every one of them
     * showing what is actually saved while [persistenceFailure] says why.
     *
     * It is published by [publish] alongside the catalog rather than derived with
     * `map(…).stateIn(…)`, because that would put a dispatch between a settings
     * write landing and the theme changing — one frame of the old theme on every
     * change, and a value that lags its own catalog in any caller that reads both.
     *
     * Its value is [ReaderSettings.DEFAULTS] until [load] has run, which is also
     * what a device with nothing stored resolves to.
     */
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    /**
     * The book the reader has just removed, while taking it back is still on
     * offer (REQ-105). Null once [undoRemoveBook] ran or the window elapsed.
     */
    val undoableRemoval: StateFlow<RemovedBook?> = _undoableRemoval.asStateFlow()

    /** Loads the stored catalog without scanning. Safe to call repeatedly. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

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
    suspend fun refreshLastReadBook() = mutex.withLock {
        if (!ensureLoaded()) return@withLock
        val catalog = _catalog.value
        val bookId = catalog.lastReadBookId ?: return@withLock
        val book = catalog.book(bookId) ?: return@withLock
        if (book.sources.isEmpty()) return@withLock
        val refreshed = withContext(ioDispatcher) {
            book.sources.map { source -> source.copy(availability = gateway.lookup(source.uri).availability()) }
        }
        if (refreshed == book.sources) return@withLock
        val next = catalog.copy(
            books = catalog.books.map { if (it.id == bookId) it.copy(sources = refreshed) else it },
        )
        try {
            withContext(ioDispatcher) { store.save(next) }
            publish(next)
        } catch (error: Exception) {
            // Routing still uses the fresher in-memory answer; the write failing
            // is a library problem, not a reason to resume into an unreadable book.
            publish(next)
            val message = error.message ?: "the library could not be updated"
            _ingestion.value = IngestionState.Failed(message)
            _persistenceFailure.value = message
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
     * when a scan just finished, so returning from the document picker does not
     * immediately trigger a second full scan.
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
        mutateCatalog { catalog ->
            removed = catalog.book(bookId)
            if (removed == null) catalog else ingestor.removeBook(catalog, bookId)
        }
        val book = removed ?: return
        // A write that failed left the book on screen; offering to undo a removal
        // that did not happen would be a lie the reader could tap.
        if (_catalog.value.book(bookId) != null) return
        offerUndo(book)
    }

    /**
     * Puts back the book the reader has just removed, with its position and
     * progress (REQ-105). Does nothing once the window has closed.
     */
    suspend fun undoRemoveBook() {
        val pending = claimPendingRemoval(null) ?: return
        pending.timer.cancel()
        mutateCatalog { ingestor.restoreBook(it, pending.book) }
    }

    /**
     * Removes an added folder and the entries only it provided
     * ([Catalog.booksOnlyFrom]). Files are never touched and every position is
     * kept, including those of the books that left (REQ-104, REQ-004).
     */
    suspend fun removeFolder(folderId: String) = mutateCatalog { ingestor.removeFolder(it, folderId) }

    /**
     * Notes where the reader is, without writing yet (REQ-016).
     *
     * Safe to call once per word: the position is held in memory and written at
     * most twice a second. Anything that is not "the next word" should follow it
     * with [flushReadingState].
     */
    fun recordReadingState(bookId: String, state: ReadingState) = positions.record(bookId, state)

    /** Makes the last [recordReadingState] durable now. Returns the job doing it. */
    fun flushReadingState(): Job = positions.flush()

    /** Stores the reading position for a book, waiting for the write. */
    suspend fun updateReadingState(bookId: String, state: ReadingState) = writeReadingState(bookId, state)

    /**
     * Stores a change to the reader's settings (REQ-020 to REQ-023).
     *
     * Takes a transform rather than a whole value so two changes made in quick
     * succession cannot lose one another: each one is applied to whatever the
     * store currently holds, under the same mutex every other catalog write uses.
     *
     * The write is loud on failure like every other one here — [settings] keeps
     * reporting the value that is actually saved and [persistenceFailure] carries
     * the reason — so a setting that appears not to take is a store problem the
     * reader is told about, never a silently discarded preference.
     */
    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) =
        mutateCatalog { it.copy(settings = transform(it.settings)) }

    /** Fire-and-forget [updateSettings], for the settings screen's callbacks. */
    fun requestUpdateSettings(transform: (ReaderSettings) -> ReaderSettings): Job =
        scope.launch { updateSettings(transform) }

    /** The retained position for a book, including one that was removed and re-added. */
    fun readingState(bookId: String): ReadingState? = _catalog.value.readingStates[bookId]

    /**
     * The one place a position reaches the store.
     *
     * Recording a position is also what makes a book the last-read one, which is
     * what launch resumes into (REQ-009). The id is kept even for a book that is
     * currently missing or removed, because the launch routing has to name the
     * book it could not open.
     */
    private suspend fun writeReadingState(bookId: String, state: ReadingState) = mutateCatalog { catalog ->
        catalog.copy(
            readingStates = catalog.readingStates + (bookId to state.copy(updatedAtEpochMs = clock())),
            lastReadBookId = bookId,
        )
    }

    /** The cached cover image for a book, or null when it has none. */
    fun coverFile(bookId: String): File? = covers.read(bookId)

    /**
     * Opens the book's bytes for reading, in place. The reading pipeline
     * (increment 002) consumes this instead of holding URIs of its own.
     */
    @Throws(IOException::class)
    fun openBook(bookId: String): InputStream = gateway.open(readableUri(bookId))

    /**
     * A seekable view of the book's bytes, or null when the provider has none.
     *
     * What lets the reader open a book by seeking to its text instead of reading
     * past its pictures (REQ-110).
     */
    @Throws(IOException::class)
    fun openBookChannel(bookId: String): SeekableByteChannel? = gateway.openSeekable(readableUri(bookId))

    @Throws(IOException::class)
    private fun readableUri(bookId: String): String {
        val book = _catalog.value.book(bookId) ?: throw IOException("unknown book $bookId")
        val source = book.readableSource ?: throw IOException("no reachable source for ${book.title}")
        return source.uri
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
     * Gives back every long-lived grant the catalog no longer references.
     *
     * [releaseGrantsNoLongerNeeded] only runs while the process that removed the
     * book is alive. A reader who removes a book and then swipes the app away
     * inside the undo window leaves the row gone from the stored catalog and the
     * grant still held, with nothing left in memory that knows about it — and
     * Android caps how many persisted grants an app may hold, so that leak
     * eventually stops the reader from adding books at all. The platform's own
     * list is the only record that survives, so reconciling against it at load
     * closes that window and any grant an earlier crash orphaned.
     *
     * It runs exactly once per process, from the first successful load, which is
     * necessarily before this process can have a removal pending.
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
    private fun releaseOrphanedGrants(catalog: Catalog) {
        try {
            val referenced = HashSet<String>()
            catalog.books.forEach { book -> book.sources.forEach { referenced += it.uri } }
            catalog.folders.forEach { referenced += it.treeUri }
            gateway.persistedReadPermissions()
                .filterNot { it in referenced }
                // Neither gateway distinguishes a tree from a document when
                // giving a grant back; a sweep cannot know which an orphan is.
                .forEach { gateway.releaseReadPermission(it, isTree = false) }
        } catch (_: Exception) {
            // Deliberately quiet: see above.
        }
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
        val stillUsed = _catalog.value.book(book.id)?.sources?.mapTo(HashSet()) { it.uri }.orEmpty()
        ingestor.releaseGrants(book.sources.filterNot { it.uri in stillUsed })
    }

    private suspend fun mutate(
        trigger: ScanTrigger,
        skip: () -> Boolean = { false },
        block: (Catalog, ScanProgress) -> IngestOutcome,
    ) = mutex.withLock {
        if (!ensureLoaded()) return@withLock
        if (skip()) return@withLock
        _ingestion.value = IngestionState.Scanning(trigger)
        val progress = ScanProgress { processed, total, name ->
            _ingestion.value = IngestionState.Scanning(trigger, processed, total, name)
        }
        try {
            val outcome = withContext(ioDispatcher) { block(_catalog.value, progress) }
            withContext(ioDispatcher) { store.save(outcome.catalog) }
            publish(outcome.catalog)
            _persistenceFailure.value = null
            lastScanAtEpochMs = clock()
            _ingestion.value = IngestionState.Completed(
                trigger = trigger,
                added = outcome.added,
                updated = outcome.updated,
                rejected = outcome.rejected,
                unavailable = outcome.unavailable,
                finishedAtEpochMs = lastScanAtEpochMs,
            )
        } catch (error: Exception) {
            _ingestion.value = IngestionState.Failed(error.message ?: "the library could not be updated")
        }
    }

    private suspend fun mutateCatalog(block: (Catalog) -> Catalog) = mutex.withLock {
        if (!ensureLoaded()) return@withLock
        try {
            val next = block(_catalog.value)
            withContext(ioDispatcher) {
                store.save(next)
                // Catalog first, mirror second, both before anything is published.
                // A catalog write that throws therefore leaves *both* copies at
                // the old value, so the two can never disagree about a change
                // that did not happen (AD-10).
                themeMirror.write(next.settings.theme)
            }
            publish(next)
            _persistenceFailure.value = null
            // A store that has just accepted a write is no longer failing, so the
            // library's banner has to go with the reader's. Without this, one
            // transient write failure would leave "the library could not be
            // updated" on screen until the next folder scan — and since positions
            // are written continuously now, that is a banner a reader could easily
            // provoke and never be able to clear.
            if (_ingestion.value is IngestionState.Failed) _ingestion.value = IngestionState.Idle
        } catch (error: Exception) {
            // Loud on both surfaces: the library banner and, while reading, the
            // reader's own. A write that fails silently is a lost position.
            val message = error.message ?: "your place could not be saved"
            _ingestion.value = IngestionState.Failed(message)
            _persistenceFailure.value = message
        }
    }

    /**
     * The one place the catalog becomes visible, so [catalog] and [settings] can
     * never disagree about which document they describe.
     */
    private fun publish(next: Catalog) {
        _catalog.value = next
        _settings.value = next.settings
    }

    /** Returns false when the catalog must not be written, leaving the reason in [ingestion]. */
    private suspend fun ensureLoaded(): Boolean {
        blockedMessage?.let {
            _ingestion.value = IngestionState.Failed(it)
            _persistenceFailure.value = it
            return false
        }
        if (loaded) return true
        return when (val load = withContext(ioDispatcher) { store.load() }) {
            is CatalogLoad.Loaded -> {
                publish(load.catalog)
                loaded = true
                // Re-sync on load, not only on write: this is what repairs a
                // mirror that a failed write left stale, and what gives an
                // install whose catalog predates the mirror a correct second
                // launch instead of a permanently default first frame.
                // Unconditional, recovery included: a recovered load really does
                // put the app on the default theme, so the mirror has to say so
                // or the next cold start opens on the pre-corruption colour.
                withContext(ioDispatcher) { themeMirror.write(load.catalog.settings.theme) }
                // The grant sweep is the opposite case and stays guarded: an
                // empty recovered catalog is no evidence the library is empty,
                // and a released grant cannot be taken back. See
                // [releaseOrphanedGrants].
                if (load.recoveredFrom == null) {
                    withContext(ioDispatcher) { releaseOrphanedGrants(load.catalog) }
                }
                true
            }

            is CatalogLoad.Blocked -> {
                blockedMessage = load.message
                _ingestion.value = IngestionState.Failed(load.message)
                _persistenceFailure.value = load.message
                false
            }
        }
    }

    companion object {
        const val DEFAULT_MINIMUM_RESCAN_INTERVAL_MS = 2_000L

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
