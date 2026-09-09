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
     * - **4** — issue #32: the single `pivotEnabled` cue splits into
     *   `highlightEnabled` (the coloured letter, carried forward from the old
     *   flag) and `focusAlignmentEnabled` (the off-centre alignment, now opt-in
     *   and written as `false`).
     * - **5** — issue #51: the chapter-boundary pause becomes a setting
     *   (`chapterPauseEnabled`, written as `true` so an updating reader keeps v1
     *   behaviour), and the catalog remembers which books have already been
     *   offered the one-time front-matter skip (`frontMatterOfferedBookIds`,
     *   absent meaning none). The first of increment 002's three steps; issue #52
     *   takes 6 and issue #62 takes 7 (AD-16).
     * - **6** — issue #52: the library's order becomes a setting
     *   (`libraryOrder`, written as `RECENTLY_READ` so an updating reader gets
     *   the new default rather than v1's alphabetical list). The second of
     *   increment 002's three steps; issue #62 takes 7 (AD-16).
     * - **7** — issue #62: a reading position records the structure of the file it
     *   was taken in (`ReadingState.structuralFingerprint`, written as `null`
     *   because no earlier document can know one — and null means no guard, so
     *   every stored position still resumes). The last of increment 002's three
     *   steps; the chain 5 → 6 → 7 is complete.
     */
    const val CURRENT_VERSION: Int = 7

    /**
     * Forward migrations keyed by the version they upgrade *from*; each step must
     * produce the next version. Nothing is ever dropped: a step that cannot carry
     * a value forward exactly must carry the closest honest equivalent.
     */
    val MIGRATIONS: Map<Int, CatalogMigration> = mapOf(
        1 to ReadingStateV2Migration,
        2 to SettingsV3Migration,
        3 to CueSplitV4Migration,
        4 to ChapterPauseV5Migration,
        5 to LibraryOrderV6Migration,
        6 to ContentFingerprintV7Migration,
    )
}

/** One forward step, from version `n` to version `n + 1`, over the raw document. */
fun interface CatalogMigration {
    fun migrate(document: JsonObject): JsonObject
}
