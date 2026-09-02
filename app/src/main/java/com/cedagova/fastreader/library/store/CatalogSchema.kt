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

    /**
     * Version history:
     *
     * - **1** — increment 001: books, folders, removals, and a reading-position
     *   slot addressed by spine item and word (never written by a released
     *   reader; nothing streamed words yet).
     * - **2** — increment 002 (LEAF204): a reading position is a token-stream
     *   index carrying the identity and pipeline version that make it meaningful,
     *   plus the reading speed, and the catalog remembers the last-read book so
     *   launch can resume into it.
     * - **3** — increment 003 (LEAF302): the reader's settings — theme, font size,
     *   pivot cue and its colour, guide marks, pause strength — each with a
     *   documented default that an absent key reads back as.
     */
    const val CURRENT_VERSION: Int = 3

    /**
     * Forward migrations keyed by the version they upgrade *from*; each step must
     * produce the next version. Nothing is ever dropped: a step that cannot carry
     * a value forward exactly must carry the closest honest equivalent.
     */
    val MIGRATIONS: Map<Int, CatalogMigration> = mapOf(
        1 to ReadingStateV2Migration,
        2 to SettingsV3Migration,
    )
}

/** One forward step, from version `n` to version `n + 1`, over the raw document. */
fun interface CatalogMigration {
    fun migrate(document: JsonObject): JsonObject
}
