package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CatalogLoad
import com.cedagova.fastreader.library.store.CatalogStore
import com.cedagova.fastreader.library.store.CoverStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
) {

    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(Catalog())
    private val _ingestion = MutableStateFlow<IngestionState>(IngestionState.Idle)

    private var loaded = false
    private var blockedMessage: String? = null
    private var lastScanAtEpochMs = 0L

    /** The current catalog. Empty until the first load completes. */
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    /** Loading/result state for the library's loading and refresh affordances. */
    val ingestion: StateFlow<IngestionState> = _ingestion.asStateFlow()

    /** Loads the stored catalog without scanning. Safe to call repeatedly. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

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
    suspend fun rescan(trigger: ScanTrigger) {
        if (trigger == ScanTrigger.APP_OPEN &&
            lastScanAtEpochMs != 0L &&
            clock() - lastScanAtEpochMs < minimumRescanIntervalMs
        ) {
            return
        }
        mutate(trigger) { catalog, progress -> ingestor.rescan(catalog, progress) }
    }

    /** Removes a catalog entry. The file stays on the device and the position is kept (REQ-004). */
    suspend fun removeBook(bookId: String) = mutateCatalog { ingestor.removeBook(it, bookId) }

    /** Removes an added folder and the entries only it provided. Files are never touched. */
    suspend fun removeFolder(folderId: String) = mutateCatalog { ingestor.removeFolder(it, folderId) }

    /** Stores the reading position for a book (written by the reader in a later increment). */
    suspend fun updateReadingState(bookId: String, state: ReadingState) = mutateCatalog { catalog ->
        catalog.copy(readingStates = catalog.readingStates + (bookId to state.copy(updatedAtEpochMs = clock())))
    }

    /** The retained position for a book, including one that was removed and re-added. */
    fun readingState(bookId: String): ReadingState? = _catalog.value.readingStates[bookId]

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
        block: (Catalog, ScanProgress) -> IngestOutcome,
    ) = mutex.withLock {
        if (!ensureLoaded()) return@withLock
        _ingestion.value = IngestionState.Scanning(trigger)
        val progress = ScanProgress { processed, total, name ->
            _ingestion.value = IngestionState.Scanning(trigger, processed, total, name)
        }
        try {
            val outcome = withContext(ioDispatcher) { block(_catalog.value, progress) }
            withContext(ioDispatcher) { store.save(outcome.catalog) }
            _catalog.value = outcome.catalog
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
        } catch (error: Exception) {
            _ingestion.value = IngestionState.Failed(error.message ?: "the library could not be updated")
        }
    }

    /** Returns false when the catalog must not be written, leaving the reason in [ingestion]. */
    private suspend fun ensureLoaded(): Boolean {
        blockedMessage?.let {
            _ingestion.value = IngestionState.Failed(it)
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
                false
            }
        }
    }

    companion object {
        const val DEFAULT_MINIMUM_RESCAN_INTERVAL_MS = 2_000L
    }
}
