package com.cedagova.fastreader.account.library

import com.cedagova.reader.account.library.AccountCopy
import com.cedagova.reader.account.library.AccountDocumentCopyReferences
import com.cedagova.reader.library.sync.AccountLibraryLoad
import com.cedagova.reader.library.sync.FileAccountLibraryStore
import com.cedagova.reader.library.testing.RecordingHostRecords
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FastReader's own state in the account document, since #147 moved the engine
 * into `:reader-library`: the answered resume offers (schema 5's
 * `resumeOfferSettledFor`), a host record the engine stores and never reads,
 * and — since #200 moved the copy references to `:reader-account`
 * (`AccountCopyReferencesTest` there) — that a document FastReader wrote still
 * reads back both.
 */
class AccountHostRecordsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
     * FastReader's side of `:reader-account`'s shelf guard (#200): the shelf
     * reaches the resume-offer note only through `ResumeOfferRecords.settle`,
     * and FastReader's implementation adds no other public operation.
     */
    @Test
    fun `the resume-offer note offers nothing but settling`() {
        assertEquals(
            sortedSetOf("settle"),
            AccountResumeOffers::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSortedSet(),
        )
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
