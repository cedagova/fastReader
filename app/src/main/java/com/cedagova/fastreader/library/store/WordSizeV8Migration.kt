package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Schema 7 → 8: the streamed word gets a size of its own.
 *
 * Until now one `fontSize` scaled the app's text *and* the streamed word, so a
 * reader who wanted a bigger word had to take bigger menus with it. The two are
 * now separate settings, and this step decides what an updating reader's word
 * size is: **the size it already was**. `wordSize` is written as a copy of the
 * document's `fontSize`, so nothing on their screen changes on the first launch
 * after the update — the word is the size it was, the text is the size it was,
 * and only the settings screen has a new row.
 *
 * The value is copied out rather than left to
 * [com.cedagova.fastreader.settings.ReaderSettings]'s default, for the same
 * reason every earlier step writes its decision: a document with `fontSize:
 * "LARGE"` and no `wordSize` would otherwise read back a *medium* word, which is
 * a visible change nobody asked for.
 *
 * A document with no `settings` block, or one with no `fontSize` in it, is
 * returned as it arrived: that reader is at the default size, and the default
 * word size is the same step. A `settings` or `fontSize` value of the wrong
 * shape falls through untouched rather than throwing, because a throw here
 * escapes [CatalogCodec.decode]'s guard and would set aside the reader's whole
 * library.
 */
internal object WordSizeV8Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject {
        val settings = document["settings"] as? JsonObject ?: return document
        val fontSize = settings["fontSize"] as? JsonPrimitive ?: return document
        if (!fontSize.isString) return document
        val migrated = settings + ("wordSize" to fontSize)
        return JsonObject(document + ("settings" to JsonObject(migrated)))
    }
}
