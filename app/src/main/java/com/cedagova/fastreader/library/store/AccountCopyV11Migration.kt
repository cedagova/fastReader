package com.cedagova.fastreader.library.store

import kotlinx.serialization.json.JsonObject

/**
 * Schema 10 → 11: a source may be a private copy of an account book.
 *
 * Issue #118 adds [com.cedagova.fastreader.library.SourceOrigin.ACCOUNT_COPY]
 * and [com.cedagova.fastreader.library.BookSource.filePath] — the bytes of an
 * account book, downloaded through the backend's grant, verified against their
 * content SHA-256 and placed in this app's private storage (D2, AD-24).
 *
 * This step writes nothing, and that is the whole of it. Both additions are
 * new *values*, not new meanings for old ones: no document written by an
 * earlier build can hold an `ACCOUNT_COPY` source, because no earlier build
 * could make one, and every source such a document holds is read through the
 * document provider and so has no file path to record. A version 10 document
 * therefore decodes into a version 11 one unchanged — every field reads back
 * exactly as it was written, plus the new capability it does not yet use.
 *
 * The step exists all the same, for the reason every no-op migration in this
 * chain exists: [CatalogSchema.MIGRATIONS] demands one entry per version, and
 * a missing entry is how a *forgotten* migration is caught rather than a
 * document quietly read as damaged. Writing the identity here is a decision
 * ("nothing to carry forward"), not an omission.
 */
internal object AccountCopyV11Migration : CatalogMigration {

    override fun migrate(document: JsonObject): JsonObject = document
}
