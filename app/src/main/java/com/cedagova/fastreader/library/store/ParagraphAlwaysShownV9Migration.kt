package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schema 8 → 9: the paragraph can stay on screen while the stream runs.
 *
 * Until now the paragraph around the current word appeared only when the stream
 * was paused (REQ-010). It is now a choice whether it stays there while the
 * stream runs too, and this step decides what an updating reader gets: **what
 * they had**. `paragraphAlwaysShown` is written as `false`, so nothing on their
 * reading surface changes on the first launch after the update — one word on a
 * static page while playing, the paragraph when paused.
 *
 * The value is written out rather than left to
 * [com.cedagova.fastreader.settings.ReaderSettings]'s default, for the same
 * reason every earlier step writes its decision: the migrated document then
 * records what this step decided instead of inheriting a default a later change
 * could move.
 *
 * A document with no `settings` block at all is returned exactly as it arrived:
 * every field of that document already reads back as its documented default, and
 * `paragraphAlwaysShown` defaults to the same `false` this step would have
 * written. A `settings` value that is not an object falls through untouched
 * rather than throwing, because a throw here escapes [CatalogCodec.decode]'s
 * guard and would set aside the reader's whole library.
 */
internal object ParagraphAlwaysShownV9Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val settings = document["settings"] as? JsonObject ?: return document
        val migrated = settings + ("paragraphAlwaysShown" to JsonPrimitive(false))
        return JsonObject(document + ("settings" to JsonObject(migrated)))
    }
}
