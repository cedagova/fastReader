package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Schema 3 → 4: the one cue flag becomes two (#32).
 *
 * Increment 003 stored `pivotEnabled`, which switched the letter highlight and the
 * off-centre alignment together. Issue #32 split them, and the owner decided the
 * alignment ships **off**:
 *
 * - `pivotEnabled` becomes `highlightEnabled` with the same value, so a reader who
 *   had the cue off still has no coloured letter and a reader who had it on still
 *   has one; and
 * - `focusAlignmentEnabled` is written as `false`, because inheriting the old
 *   flag's value here would silently keep shipping the presentation the split
 *   exists to stop shipping by default.
 *
 * That `false` is written out rather than left to
 * [com.cedagova.fastreader.settings.ReaderSettings]'s default, so the migrated
 * document records what the owner decided instead of depending on a default a
 * later change could move. Every other key is untouched, including `pivotColor`,
 * which still names the colour of the highlighted letter.
 *
 * Nothing outside the `settings` object is read or rewritten: books, folders,
 * reading positions and the last-read book pass through this step unchanged, and
 * a document with no `settings` block is returned exactly as it arrived. The step
 * is also total — a `settings` value that is not an object, or a `pivotEnabled`
 * that is not a boolean, falls through to the documented defaults rather than
 * throwing, because a throw here escapes [CatalogCodec.decode]'s decode guard.
 */
internal object CueSplitV4Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val settings = document["settings"] as? JsonObject ?: return document

        val carried = (settings["pivotEnabled"] as? JsonPrimitive)?.booleanOrNull
        var migrated = settings - "pivotEnabled"
        if (carried != null && "highlightEnabled" !in settings) {
            migrated = migrated + ("highlightEnabled" to JsonPrimitive(carried))
        }
        migrated = migrated + ("focusAlignmentEnabled" to JsonPrimitive(false))

        return JsonObject(document + ("settings" to JsonObject(migrated)))
    }
}
