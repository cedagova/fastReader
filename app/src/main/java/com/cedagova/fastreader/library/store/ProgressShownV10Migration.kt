package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schema 9 → 10: the progress readouts become a choice.
 *
 * The percent read and the time left (REQ-017) have always sat above the
 * progress bar. They can now be turned off, and this step decides what an
 * updating reader gets: **what they had**. `progressShown` is written as `true`,
 * so nothing under their stream changes on the first launch after the update.
 *
 * The value is written out rather than left to
 * [com.cedagova.fastreader.settings.ReaderSettings]'s default, for the same
 * reason every earlier step writes its decision: the migrated document then
 * records what this step decided instead of inheriting a default a later change
 * could move.
 *
 * A document with no `settings` block at all is returned exactly as it arrived:
 * every field of that document already reads back as its documented default, and
 * `progressShown` defaults to the same `true` this step would have written. A
 * `settings` value that is not an object falls through untouched rather than
 * throwing, because a throw here escapes [CatalogCodec.decode]'s guard and would
 * set aside the reader's whole library.
 */
internal object ProgressShownV10Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val settings = document["settings"] as? JsonObject ?: return document
        val migrated = settings + ("progressShown" to JsonPrimitive(true))
        return JsonObject(document + ("settings" to JsonObject(migrated)))
    }
}
