package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.sync.AccountHostRecords
import kotlinx.serialization.json.JsonElement

/**
 * An in-memory stand-in for the engine's host records (#147): the document's
 * records and each known book row's, with the engine's rules — nothing while
 * nobody is signed in, and a book the account has no row for is skipped.
 */
class RecordingHostRecords(var userId: String? = "user-1", knownBooks: Set<String> = emptySet()) : AccountHostRecords {

    val document = mutableMapOf<String, JsonElement>()
    val books: MutableMap<String, MutableMap<String, JsonElement>> =
        knownBooks.associateWith { mutableMapOf<String, JsonElement>() }.toMutableMap()

    /** Every update that changed something, in order. */
    val writes = mutableListOf<String>()

    override fun accountId(): String? = userId

    override suspend fun hostRecord(key: String): JsonElement? = if (userId == null) null else document[key]

    override suspend fun updateHostRecord(key: String, transform: (JsonElement?) -> JsonElement?) {
        if (userId == null) return
        apply(document, key, transform(document[key]), "document")
    }

    override suspend fun updateBookHostRecord(bookId: String, key: String, transform: (JsonElement?) -> JsonElement?) {
        if (userId == null) return
        val row = books[bookId] ?: return
        apply(row, key, transform(row[key]), bookId)
    }

    private fun apply(level: MutableMap<String, JsonElement>, key: String, value: JsonElement?, where: String) {
        if (level[key] == value) return
        if (value == null) level.remove(key) else level[key] = value
        writes += "$where:$key"
    }
}
