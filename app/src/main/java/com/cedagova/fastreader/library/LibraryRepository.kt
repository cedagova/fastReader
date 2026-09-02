package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CatalogLoad
import com.cedagova.fastreader.library.store.CatalogStore
import com.cedagova.fastreader.library.store.CoverStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val minimumRescanIntervalMs: Long = DEFAULT_MINIMUM_RESCAN_INTERVAL_MS,
    positionFlushIntervalMs: Long = ReadingPositionWriter.DEFAULT_INTERVAL_MILLIS,
) {

    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(Catalog())
    private val _ingestion = MutableStateFlow<IngestionState>(IngestionState.Idle)
    private val _persistenceFailure = MutableStateFlow<String?>(null)

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
            _catalog.value = next
        } catch (error: Exception) {
            // Routing still uses the fresher in-memory answer; the write failing
            // is a library problem, not a reason to resume into an unreadable book.
            _catalog.value = next
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
     */
    suspend fun removeBook(bookId: String) = mutateCatalog { ingestor.removeBook(it, bookId) }

    /** Removes an added folder and the entries only it provided. Files are never touched. */
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
    fun openBook(bookId: String): InputStream {
        val book = _catalog.value.book(bookId) ?: throw IOException("unknown book $bookId")
        val source = book.readableSource ?: throw IOException("no reachable source for ${book.title}")
        return gateway.open(source.uri)
    }

    /** Fire-and-forget wrappers for callers without a coroutine scope of their own. */
    fun requestRescan(trigger: ScanTrigger) = scope.launch { rescan(trigger) }

    fun requestAddPickedBooks(uris: List<String>) = scope.launch { addPickedBooks(uris) }

    fun requestAddFolder(treeUri: String, displayName: String? = null) =
        scope.launch { addFolder(treeUri, displayName) }

    fun requestRemoveBook(bookId: String) = scope.launch { removeBook(bookId) }

    fun requestRemoveFolder(folderId: String) = scope.launch { removeFolder(folderId) }

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
            _catalog.value = outcome.catalog
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
            withContext(ioDispatcher) { store.save(next) }
            _catalog.value = next
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
                _catalog.value = load.catalog
                loaded = true
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
    }
}

private fun DocumentLookup.availability(): SourceAvailability = when (this) {
    is DocumentLookup.Found -> SourceAvailability.AVAILABLE
    DocumentLookup.Missing -> SourceAvailability.MISSING
    DocumentLookup.PermissionLost -> SourceAvailability.PERMISSION_LOST
}
