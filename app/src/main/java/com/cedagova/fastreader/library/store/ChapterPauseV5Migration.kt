package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schema 4 → 5: the chapter-boundary pause becomes a setting (#51, REQ-201, D4).
 *
 * v1 stopped the stream on the first word of every new chapter and gave the
 * reader no say in it (REQ-015). Decision D4 turns that into a choice, and the
 * owner decided it ships **on**: an existing reader must see no change at all
 * after updating, which is the second half of REQ-201's acceptance and the whole
 * of REQ-208's "the new chapter-pause setting reads its default".
 *
 * So this step writes `chapterPauseEnabled: true` into the stored `settings`
 * object. That `true` is written out rather than left to
 * [com.cedagova.fastreader.settings.ReaderSettings]'s default, for the same
 * reason [CueSplitV4Migration] writes its `false`: the migrated document then
 * records what this step decided instead of inheriting a default a later change
 * could move.
 *
 * A document with no `settings` block at all is returned exactly as it arrived.
 * There is nothing to record there — every field of that document already reads
 * back as its documented default, and `chapterPauseEnabled` defaults to the same
 * `true` this step would have written — so inventing a settings object would add
 * a key without adding a fact.
 *
 * Nothing outside `settings` is read or rewritten: books, folders, reading
 * positions, removals and the last-read book pass through unchanged, and so does
 * the per-book front-matter record `frontMatterOfferedBookIds`, whose documented
 * default is "no book has been offered yet" — which is what an absent key already
 * decodes to. The step is total: a `settings` value that is not an object falls
 * through untouched rather than throwing, because a throw here escapes
 * [CatalogCodec.decode]'s guard and would set aside the reader's whole library.
 */
internal object ChapterPauseV5Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val settings = document["settings"] as? JsonObject ?: return document
        val migrated = settings + ("chapterPauseEnabled" to JsonPrimitive(true))
        return JsonObject(document + ("settings" to JsonObject(migrated)))
    }
}
