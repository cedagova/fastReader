package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject

/**
 * Schema 2 → 3: the catalog gains the reader's settings (LEAF302).
 *
 * There is nothing to convert. Schema 2 predates the settings surface, so a v2
 * document has no settings to carry forward and no field whose meaning changed —
 * every key this step introduces has a documented default in
 * [com.cedagova.fastreader.settings.ReaderSettings], and an absent key decodes to
 * exactly that default. Materialising those defaults as JSON literals here would
 * duplicate them in a second place that could drift from the first; the next write
 * encodes them from the data class itself, because the codec encodes defaults.
 *
 * The step still has to exist: [CatalogCodec] refuses a document it cannot walk
 * forward one version at a time, which is what stops a gap in the chain from being
 * mistaken for an intact library. So this is a deliberate identity step, and
 * `CatalogStoreTest` proves a real v2 document loads with its books and positions
 * intact and every setting at its default.
 */
internal object SettingsV3Migration : CatalogMigration {
    override fun migrate(document: JsonObject): JsonObject = document
}
