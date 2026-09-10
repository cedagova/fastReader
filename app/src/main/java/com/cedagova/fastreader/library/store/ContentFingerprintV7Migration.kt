package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Schema 6 → 7: a reading position records the structure of the file it was taken
 * in (#62, AD-16, AD-18).
 *
 * The field is new and no document written before this step can know a value for
 * it, so the step writes the only honest one: `structuralFingerprint: null` into
 * every stored `readingStates` entry. Null is the documented default and it means
 * **no guard** — every position written by v1.1.0 or by any earlier v1.2.0 build
 * resumes exactly where it did. It is deliberately *not* a mismatch: a migration
 * that refused positions it could not verify would throw away the reader's place
 * in every book they own, to protect against a file that in all likelihood never
 * changed.
 *
 * The null is written out rather than left to
 * [com.cedagova.fastreader.library.ReadingState]'s Kotlin default, for the same
 * reason [CueSplitV4Migration] writes its `false`, [ChapterPauseV5Migration] its
 * `true` and [LibraryOrderV6Migration] its `RECENTLY_READ`: the migrated document
 * then records what this step decided — "this position predates the guard" —
 * rather than inheriting a default a later change could move.
 *
 * Protection arrives on the first *directory* open of each book after the update:
 * the reader computes a fingerprint from the central-directory read it already
 * performs and stores it with the next position. Books never opened again keep a
 * null and keep resuming. A book opened through the streaming archive produces no
 * fingerprint, and the write path leaves whatever is stored in place rather than
 * clearing it, so this is only ever a gain (AD-18).
 *
 * Nothing outside `readingStates` is read or rewritten: books, folders, settings,
 * removals, the last-read book and the per-book front-matter record all pass
 * through unchanged. The step is total — a `readingStates` value that is not an
 * object, or an entry inside it that is not an object, falls through untouched
 * rather than throwing, because a throw here escapes [CatalogCodec.decode]'s
 * guard and would set aside the reader's whole library over one malformed entry.
 */
internal object ContentFingerprintV7Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val states = document["readingStates"] as? JsonObject ?: return document
        if (states.isEmpty()) return document
        val migrated = states.mapValues { (_, state) ->
            val entry = state as? JsonObject ?: return@mapValues state
            JsonObject(entry + ("structuralFingerprint" to JsonNull))
        }
        return JsonObject(document + ("readingStates" to JsonObject(migrated)))
    }
}
