package com.cedagova.reader.library.testing

import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.sync.AccountHostRecords
import com.cedagova.reader.library.sync.AccountImportRecords
import com.cedagova.reader.library.sync.AccountLibraryActions
import com.cedagova.reader.library.sync.LocalReadingPosition
import kotlinx.serialization.json.JsonElement

// In-memory stand-ins for the sync engine's host-facing interfaces (#199,
// A197-F002), for a host's tests of the code that sits on the engine: its
// shelf, its resume offers, its import flow. Each follows the engine's rules
// where a host could observe them — nothing while nobody is signed in, and a
// book the account has no row for is skipped.

/**
 * Every [AccountLibraryActions] call, in order, in [calls]
 * (`remove:<id>`, `position:<id>:<href>:<percent>`, …); positions also land in
 * [positions] with their full value.
 */
public class RecordingAccountLibraryActions : AccountLibraryActions {
    public val calls: MutableList<String> = mutableListOf()
    public val positions: MutableList<Pair<String, LocalReadingPosition>> = mutableListOf()

    override fun refresh() {
        calls += "refresh"
    }

    override fun removeFromAccount(bookId: String) {
        calls += "remove:$bookId"
    }

    override fun undoRemove(bookId: String) {
        calls += "undo:$bookId"
    }

    override fun recordOpened(bookId: String) {
        calls += "opened:$bookId"
    }

    override fun recordFinished(bookId: String) {
        calls += "finished:$bookId"
    }

    override fun recordPosition(bookId: String, position: LocalReadingPosition) {
        calls += "position:$bookId:${position.href}:${position.percent}"
        positions += bookId to position
    }

    override fun recordStatus(bookId: String, status: ReaderLibraryStatus) {
        calls += "status:$bookId:$status"
    }
}

/**
 * The engine's host records in memory: the document's records and each known
 * book row's. [userId] null is "nobody signed in"; a book not in [books] is a
 * book the account has no row for.
 */
public class RecordingHostRecords(public var userId: String? = "user-1", knownBooks: Set<String> = emptySet()) :
    AccountHostRecords {

    public val document: MutableMap<String, JsonElement> = mutableMapOf()
    public val books: MutableMap<String, MutableMap<String, JsonElement>> =
        knownBooks.associateWith { mutableMapOf<String, JsonElement>() }.toMutableMap()

    /** Every update that changed something, in order, as `<document|bookId>:<key>`. */
    public val writes: MutableList<String> = mutableListOf()

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

/** The account document's import section in memory; [signedIn] null is "nobody signed in". */
public class InMemoryImportRecords(public var signedIn: String? = "user-1") : AccountImportRecords {
    public val stored: MutableList<PublicationImportRecord> = mutableListOf()

    override fun accountId(): String? = signedIn

    override suspend fun importRecords(): List<PublicationImportRecord> = stored.toList()

    override suspend fun putImportRecord(record: PublicationImportRecord) {
        val index = stored.indexOfFirst { it.clientImportId == record.clientImportId }
        if (index < 0) stored += record else stored[index] = record
    }

    override suspend fun dropImportRecord(clientImportId: String) {
        stored.removeAll { it.clientImportId == clientImportId }
    }
}
