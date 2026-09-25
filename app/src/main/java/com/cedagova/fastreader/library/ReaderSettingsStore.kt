package com.cedagova.fastreader.library

import com.cedagova.fastreader.settings.ReaderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * How the reader wants books presented (LEAF302), and the one way to change it
 * (#204).
 *
 * The settings are still stored in `catalog.json` (AD-8): this type owns them,
 * the [CatalogDocument] owns the file. Their JSON is exactly what it was, and
 * they reach the file through the same single writer as every other catalog
 * change.
 */
class ReaderSettingsStore internal constructor(
    private val document: CatalogDocument,
    private val scope: CoroutineScope,
) {

    /**
     * The stored settings.
     *
     * A projection of the catalog, so there is exactly one source of truth: the
     * settings screen, the app's theme, the library and the reader all read the
     * value the store accepted, and a write that fails leaves every one of them
     * showing what is actually saved while [persistenceFailure] says why.
     *
     * It is published alongside the catalog rather than derived with
     * `map(…).stateIn(…)`, because that would put a dispatch between a settings
     * write landing and the theme changing — one frame of the old theme on every
     * change, and a value that lags its own catalog in any caller that reads both.
     *
     * Its value is [ReaderSettings.DEFAULTS] until the catalog has loaded, which
     * is also what a device with nothing stored resolves to.
     */
    val settings: StateFlow<ReaderSettings> get() = document.settings

    /** Non-null while the store is refusing writes; see [CatalogDocument.persistenceFailure]. */
    val persistenceFailure: StateFlow<String?> get() = document.persistenceFailure

    /**
     * Stores a change to the reader's settings (REQ-020 to REQ-023).
     *
     * Takes a transform rather than a whole value so two changes made in quick
     * succession cannot lose one another: each one is applied to whatever the
     * store currently holds, under the same lock every other catalog write uses.
     *
     * The write is loud on failure like every other one — [settings] keeps
     * reporting the value that is actually saved and [persistenceFailure] carries
     * the reason — so a setting that appears not to take is a store problem the
     * reader is told about, never a silently discarded preference.
     */
    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) =
        document.write { it.copy(settings = transform(it.settings)) }

    /** Fire-and-forget [update], for the screens' callbacks. */
    fun requestUpdate(transform: (ReaderSettings) -> ReaderSettings): Job = scope.launch { update(transform) }
}
