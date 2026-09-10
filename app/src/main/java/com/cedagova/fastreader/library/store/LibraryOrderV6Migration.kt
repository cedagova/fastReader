package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schema 5 → 6: the library's order becomes a setting (#52, REQ-203).
 *
 * v1 listed books alphabetically and gave the reader no say in it. REQ-203 turns
 * that into a choice of three, and the owner's default is **recently read**, not
 * the alphabetical order v1 shipped: the acceptance is that reading two words of
 * a book moves it to the top, and a reader who never opens the control should
 * still get that.
 *
 * So this step writes `libraryOrder: "RECENTLY_READ"` into the stored `settings`
 * object. That value is written out rather than left to
 * [com.cedagova.fastreader.settings.ReaderSettings]'s default, for the same
 * reason [CueSplitV4Migration] writes its `false` and [ChapterPauseV5Migration]
 * its `true`: the migrated document then records what this step decided instead
 * of inheriting a default a later change could move.
 *
 * This is a *change* of visible behaviour for an updating reader — v1.1.0's
 * library was alphabetical and v1.2.0's is not — and that is the requirement,
 * not an accident of the migration. Nothing about a book is rewritten to achieve
 * it: both orders are computed from timestamps the catalog already keeps
 * (`ReadingState.updatedAtEpochMs` and `Book.addedAtEpochMs`), so a reader who
 * picks `TITLE` gets exactly the v1 list back.
 *
 * A document with no `settings` block at all is returned exactly as it arrived.
 * There is nothing to record there — every field of that document already reads
 * back as its documented default, and `libraryOrder` defaults to the same
 * `RECENTLY_READ` this step would have written — so inventing a settings object
 * would add a key without adding a fact.
 *
 * Nothing outside `settings` is read or rewritten: books, folders, reading
 * positions, removals, the last-read book and the per-book front-matter record
 * all pass through unchanged. The step is total: a `settings` value that is not
 * an object falls through untouched rather than throwing, because a throw here
 * escapes [CatalogCodec.decode]'s guard and would set aside the reader's whole
 * library.
 */
internal object LibraryOrderV6Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val settings = document["settings"] as? JsonObject ?: return document
        val migrated = settings + ("libraryOrder" to JsonPrimitive("RECENTLY_READ"))
        return JsonObject(document + ("settings" to JsonObject(migrated)))
    }
}
