package com.cedagova.fastreader.library

import com.cedagova.fastreader.settings.ThemeMirror
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * The device library's one assembly call (#204): the narrow types that share
 * `catalog.json`, built over one [CatalogDocument] so the file keeps exactly
 * one writer.
 *
 * Each caller takes only the type it uses — the library screen the
 * [repository], the settings screen [settingsStore], the reader [positions] and
 * [bookBytes] — and none of them can reach the others' writes.
 */
class DeviceLibrary(
    store: CatalogStore,
    ingestor: CatalogIngestor,
    gateway: DocumentGateway,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    clock: () -> Long = System::currentTimeMillis,
    /** The pre-Compose copy of the theme choice (AD-10); only the running app needs a real one. */
    themeMirror: ThemeMirror = ThemeMirror.None,
    minimumRescanIntervalMs: Long = LibraryRepository.DEFAULT_MINIMUM_RESCAN_INTERVAL_MS,
    undoWindowMs: Long = LibraryRepository.DEFAULT_UNDO_WINDOW_MS,
    positionFlushIntervalMs: Long = ReadingPositionWriter.DEFAULT_INTERVAL_MILLIS,
) {

    private val document = CatalogDocument(
        store = store,
        ioDispatcher = ioDispatcher,
        themeMirror = themeMirror,
        onCleanLoad = gateway::releaseOrphanedGrants,
    )

    /** Books, folders, removal with undo, and account copies. */
    val repository = LibraryRepository(
        document = document,
        ingestor = ingestor,
        gateway = gateway,
        scope = scope,
        ioDispatcher = ioDispatcher,
        clock = clock,
        minimumRescanIntervalMs = minimumRescanIntervalMs,
        undoWindowMs = undoWindowMs,
    )

    /** The reader's settings, still stored in `catalog.json` (AD-8). */
    val settingsStore = ReaderSettingsStore(document, scope)

    /** Reading positions and the front-matter record. */
    val positions = ReadingPositions(document, scope, clock, positionFlushIntervalMs)

    /** A library book's bytes. Read-only. */
    val bookBytes = BookBytes(document.catalog, gateway)
}
