package com.cedagova.fastreader.library

import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeMirror
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one writer of `catalog.json` (#204).
 *
 * The catalog file is the app's single persisted store: books, folders,
 * positions, settings and the front-matter record all live in one document
 * (AD-3, AD-8). Several narrow types write to it — [LibraryRepository],
 * [ReaderSettingsStore], [ReadingPositions] — and every one of them writes
 * through this object, so there is still exactly one in-memory copy, one lock
 * and one path to the [CatalogStore]:
 *
 * - every write is a transform applied to the *current* document under
 *   [mutex], then saved (the store's own temp-file-and-rename), then
 *   published. A settings change and a position write therefore cannot clobber
 *   each other: whichever takes the lock second starts from the document the
 *   first one saved, and changes only its own fields.
 * - [catalog] and [settings] are published together in [publish], so no reader
 *   of both ever sees them describe different documents.
 *
 * Built once per catalog file, by [DeviceLibrary]. A second instance over the
 * same file would be a second writer, which is exactly what this type exists to
 * rule out.
 *
 * When the stored catalog is unreadable in a way that would lose data, it
 * refuses to write and reports it instead.
 */
class CatalogDocument internal constructor(
    private val store: CatalogStore,
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * Keeps the pre-Compose copy of the theme choice in step with the catalog
     * (AD-10). Defaults to [ThemeMirror.None], which mirrors nothing: only the
     * running app needs a real one.
     */
    private val themeMirror: ThemeMirror = ThemeMirror.None,
    /**
     * Runs once, on the IO dispatcher, after the first load that produced a
     * genuine catalog — never after a blocked or a recovered one. The library
     * uses it to give back orphaned read grants ([releaseOrphanedGrants]).
     */
    private val onCleanLoad: (Catalog) -> Unit = {},
) {

    private val mutex = Mutex()
    private val _catalog = MutableStateFlow(Catalog())
    private val _ingestion = MutableStateFlow<IngestionState>(IngestionState.Idle)
    private val _persistenceFailure = MutableStateFlow<String?>(null)
    private val _settings = MutableStateFlow(ReaderSettings.DEFAULTS)

    private var loaded = false
    private var blockedMessage: String? = null

    /** The current catalog. Empty until the first load completes. */
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    /**
     * Loading/result state for the library's loading and refresh affordances.
     * Held here rather than on [LibraryRepository] because a failed write of
     * *any* kind has to reach the library's banner, whichever type made it.
     */
    val ingestion: StateFlow<IngestionState> = _ingestion.asStateFlow()

    /**
     * Non-null while the store is refusing writes, so losing a reading position is
     * never silent (the definition's persistence guardrail). The reader shows it
     * on the reading surface, where the library's own banner is not visible.
     */
    val persistenceFailure: StateFlow<String?> = _persistenceFailure.asStateFlow()

    /**
     * The stored settings, a projection of [catalog] published with it. See
     * [ReaderSettingsStore.settings] for why it is not derived with `map`.
     */
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    /** Loads the stored catalog without scanning. Safe to call repeatedly. */
    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    /**
     * One catalog write under the one lock. [block] is a suspending lambda so a
     * write whose work is more than a field change — inspecting a downloaded
     * copy, for instance — can put that work on the IO dispatcher itself rather
     * than leaving it on whatever thread happened to call.
     *
     * Loud on failure: the library banner and, while reading, the reader's own
     * both carry the reason, and nothing is published — so every observer keeps
     * showing what is actually saved.
     */
    suspend fun write(block: suspend (Catalog) -> Catalog) {
        mutex.withLock {
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
    }

    /**
     * Holds the one lock over a loaded catalog for a write whose bookkeeping is
     * not [write]'s: a scan, which reports progress and its own result, and the
     * reachability checks, which publish their answer even when saving it fails.
     *
     * Does nothing when the store is refusing writes. [Transaction] is only
     * usable inside [block], so nothing outside the lock can save or publish.
     */
    internal suspend fun transaction(block: suspend Transaction.() -> Unit) {
        mutex.withLock {
            if (!ensureLoaded()) return@withLock
            Transaction().block()
        }
    }

    /** What a [transaction] may do with the document it holds the lock over. */
    internal inner class Transaction {

        /** The document as last published. */
        val current: Catalog get() = _catalog.value

        /** Saves [next] through the store on the IO dispatcher; throws on failure. */
        suspend fun save(next: Catalog) = withContext(ioDispatcher) { store.save(next) }

        /** Makes [next] the visible document. */
        fun publish(next: Catalog) = this@CatalogDocument.publish(next)

        /** Reports scan progress or a scan result on [ingestion]. */
        fun report(state: IngestionState) {
            _ingestion.value = state
        }

        /** The store accepted a write, so the reader's failure notice goes. */
        fun clearPersistenceFailure() {
            _persistenceFailure.value = null
        }

        /** A write that could not be saved: loud on both surfaces, as in [write]. */
        fun reportWriteFailure(message: String) {
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
                // The clean-load hook is the opposite case and stays guarded: an
                // empty recovered catalog is no evidence the library is empty,
                // and a released grant cannot be taken back. See
                // [releaseOrphanedGrants].
                if (load.recoveredFrom == null) {
                    withContext(ioDispatcher) { onCleanLoad(load.catalog) }
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
}
