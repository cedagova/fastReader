package com.cedagova.reader.account.library

import com.cedagova.reader.library.sync.AccountLibraryLoad
import com.cedagova.reader.library.sync.FileAccountLibraryStore
import com.cedagova.reader.library.testing.RecordingHostRecords
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FastReader's own state in the account document, since #147 moved the engine
 * into `:reader-library`: the copy references (schema 3's `copies`) and the
 * answered resume offers (schema 5's `resumeOfferSettledFor`), both host
 * records the engine stores and never reads.
 */
class AccountHostRecordsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sha = "b".repeat(64)

    /** A copy reference replaces rather than duplicates, and drop / retain do what they say. */
    @Test
    fun `copy references are keyed by content`() = runTest {
        val records = RecordingHostRecords()
        val references = AccountDocumentCopyReferences(records)
        val copy = AccountCopy(contentSha256 = sha, sizeBytes = 4_096, placedAtEpochMs = 1_700_000_000_000)

        references.putCopyReference(copy)
        assertEquals(listOf(copy), references.copyReferences())

        references.putCopyReference(copy.copy(sizeBytes = 8_192))
        assertEquals("the same content is one reference, not two", 1, references.copyReferences().size)
        assertEquals(8_192L, references.copyReferences().single().sizeBytes)

        references.retainCopyReferences(setOf(sha))
        assertEquals(1, references.copyReferences().size)
        references.retainCopyReferences(emptySet())
        assertEquals(emptyList<AccountCopy>(), references.copyReferences())

        references.putCopyReference(copy)
        references.dropCopyReference(sha)
        assertEquals(emptyList<AccountCopy>(), references.copyReferences())
    }

    /** Asking to change nothing writes nothing — an absent key is not turned into an empty one. */
    @Test
    fun `an unchanged copy list is not written`() = runTest {
        val records = RecordingHostRecords()
        val references = AccountDocumentCopyReferences(records)

        references.retainCopyReferences(emptySet())
        references.dropCopyReference(sha)

        assertTrue(records.writes.isEmpty())
        assertNull(records.document[AccountDocumentCopyReferences.KEY])
    }

    /**
     * #149: one damaged element costs that element and nothing else. Before, the
     * whole list read as empty, and the next put rewrote it with only the new
     * reference — dropping every valid one beside the damaged element.
     */
    @Test
    fun `a malformed copies element keeps the valid references across read and put`() = runTest {
        val records = RecordingHostRecords()
        val first = AccountCopy(contentSha256 = "a".repeat(64), sizeBytes = 4_096, placedAtEpochMs = 1_700_000_000_000)
        val second = AccountCopy(contentSha256 = "c".repeat(64), sizeBytes = 2_048, placedAtEpochMs = 1_700_000_000_001)
        records.document[AccountDocumentCopyReferences.KEY] = buildJsonArray {
            add(copyJson(first))
            add(buildJsonObject { put("sizeBytes", JsonPrimitive(12)) }) // no contentSha256
            add(JsonPrimitive("not a reference"))
            add(copyJson(second))
        }
        val references = AccountDocumentCopyReferences(records)

        assertEquals(listOf(first, second), references.copyReferences())

        val added = AccountCopy(contentSha256 = sha, sizeBytes = 1_024, placedAtEpochMs = 1_700_000_000_002)
        references.putCopyReference(added)

        assertEquals(listOf(first, second, added), references.copyReferences())
        assertEquals(
            "only the malformed elements are gone from what is stored",
            JsonArray(listOf(copyJson(first), copyJson(second), copyJson(added))),
            records.document[AccountDocumentCopyReferences.KEY],
        )
    }

    private fun copyJson(copy: AccountCopy) = buildJsonObject {
        put("contentSha256", JsonPrimitive(copy.contentSha256))
        put("sizeBytes", JsonPrimitive(copy.sizeBytes))
        put("placedAtEpochMs", JsonPrimitive(copy.placedAtEpochMs))
    }

    /** D4: signed out, a reference has no account to belong to, and nothing is stored. */
    @Test
    fun `nothing is referenced while signed out`() = runTest {
        val records = RecordingHostRecords(userId = null)
        val references = AccountDocumentCopyReferences(records)

        references.putCopyReference(AccountCopy(contentSha256 = sha))

        assertNull(references.accountId())
        assertEquals(emptyList<AccountCopy>(), references.copyReferences())
        assertTrue(records.writes.isEmpty())
    }

    /** Settling writes the book row's note and nothing else; an unknown book is skipped. */
    @Test
    fun `settling a resume offer notes the change on the row`() = runTest {
        val records = RecordingHostRecords(knownBooks = setOf("book-1"))
        val offers = AccountResumeOffers(records)

        offers.settle("book-1", "7:2026-09-20T10:00:00Z")
        offers.settle("book-1", "7:2026-09-20T10:00:00Z")
        offers.settle("book-unknown", "7:2026-09-20T10:00:00Z")

        assertEquals(JsonPrimitive("7:2026-09-20T10:00:00Z"), records.books["book-1"]!![AccountResumeOffers.KEY])
        assertEquals("settling twice writes once", listOf("book-1:${AccountResumeOffers.KEY}"), records.writes)
        assertNull(records.books["book-unknown"])
    }

    /**
     * #147's compatibility promise from FastReader's side: a schema 6 document
     * FastReader wrote at main `ab175bc`, before the move, loads through
     * `:reader-library`'s store, and FastReader reads its own copy reference and
     * its answered resume offer back exactly as it wrote them.
     */
    @Test
    fun `a schema 6 document keeps FastReader's copies and answered offers`() = runTest {
        val text = javaClass.getResource("/account-library-v6.json")!!.readText()
        val file = File(temporaryFolder.root, "account-legacy.json").apply { writeText(text) }
        val document = (FileAccountLibraryStore(file).load() as AccountLibraryLoad.Loaded).document

        val records = RecordingHostRecords(knownBooks = document.books.map { it.bookId }.toSet())
        records.document.putAll(document.host)
        document.books.forEach { records.books[it.bookId]!!.putAll(it.host) }

        assertEquals(
            listOf(AccountCopy(contentSha256 = "a".repeat(64), sizeBytes = 4_096, placedAtEpochMs = 1_700_000_000_000)),
            AccountDocumentCopyReferences(records).copyReferences(),
        )
        assertEquals("9:2026-09-13T08:00:00Z", document.book("book-1")!!.resumeOfferSettledFor)
        assertNull("an explicit null is still no answer", document.book("book-2")!!.resumeOfferSettledFor)
    }
}
