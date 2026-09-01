package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject

/**
 * The persisted catalog schema (AD-3).
 *
 * The store is versioned from its first byte: every document carries
 * `schemaVersion`, and an older document is migrated forward step by step before
 * it is decoded. A newer-than-known document is refused rather than rewritten, so
 * an app downgrade can never silently discard a reader's library.
 */
object CatalogSchema {

    const val CURRENT_VERSION: Int = 1

    /**
     * Forward migrations keyed by the version they upgrade *from*; each step must
     * produce the next version. Version 1 is the first release, so there is
     * nothing to migrate yet — later increments add their steps here.
     */
    val MIGRATIONS: Map<Int, CatalogMigration> = emptyMap()
}

/** One forward step, from version `n` to version `n + 1`, over the raw document. */
fun interface CatalogMigration {
    fun migrate(document: JsonObject): JsonObject
}
