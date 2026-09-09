package com.cedagova.fastreader.reader.ui

import com.cedagova.fastreader.content.BundledSample
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.reader.ReaderPosition
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reading the bundled sample must leave nothing behind (REQ-109).
 *
 * The dangerous half is not the file system — the sample never leaves the APK —
 * but the catalog: recording a position is also what makes a book the *last-read*
 * one, and the sample has no catalog row, so a position stored under it would
 * send the next launch looking for a book the library does not have. These tests
 * pin that at the boundary where positions actually reach the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SamplePositionsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gateway = FakeDocumentGateway()

    private fun repository(scope: CoroutineScope): LibraryRepository {
        val covers = CoverStore(File(temporaryFolder.root, "covers"))
        return LibraryRepository(
            store = FileCatalogStore(File(File(temporaryFolder.root, "catalog"), "catalog.json")),
            ingestor = CatalogIngestor(gateway, covers),
            gateway = gateway,
            covers = covers,
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[TestCoroutineScheduler]),
        )
    }

    private fun position(index: Int, digest: String) = ReaderPosition(
        position = TokenPosition(digest, index),
        progressFraction = 0.5f,
        wpm = 400,
    )

    @Test
    fun `reading the sample writes no reading state and no last-read book`() = runTest {
        val repository = repository(backgroundScope)
        val positions = CatalogPositions(repository)

        BundledSample.entries.forEach { sample ->
            positions.record(sample.identity.value, position(120, sample.identity.value))
        }
        repository.flushReadingState().join()

        val catalog = repository.catalog.value
        assertTrue("readingStates: ${catalog.readingStates}", catalog.readingStates.isEmpty())
        assertNull(catalog.lastReadBookId)
    }

    @Test
    fun `the sample never resolves a stored position`() = runTest {
        val repository = repository(backgroundScope)
        val positions = CatalogPositions(repository)

        BundledSample.entries.forEach { sample ->
            assertNull(sample.name, positions.restore(sample.identity.value))
        }
    }

    /**
     * The guard is a sample check, not "positions are off". A real book recorded
     * through the same object still becomes the last-read one, which is what
     * launch routing resumes into.
     */
    @Test
    fun `a real book recorded through the same store still keeps its position`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        val positions = CatalogPositions(repository)

        positions.record(bookId, position(42, bookId))
        repository.flushReadingState().join()

        val catalog = repository.catalog.value
        assertEquals(bookId, catalog.lastReadBookId)
        val stored = requireNotNull(catalog.readingStates[bookId])
        assertEquals(42, stored.tokenIndex)
        assertEquals(400, stored.wpm)
        assertNotNull(positions.restore(bookId))
    }

    /**
     * A reader who opens the sample after a real book must come back to the book:
     * the sample's write is dropped, so `lastReadBookId` still names the book.
     */
    @Test
    fun `the sample does not displace the last-read book`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        val positions = CatalogPositions(repository)

        positions.record(bookId, position(42, bookId))
        repository.flushReadingState().join()
        positions.record(BundledSample.ENGLISH.identity.value, position(9, BundledSample.ENGLISH.identity.value))
        repository.flushReadingState().join()

        assertEquals(bookId, repository.catalog.value.lastReadBookId)
        assertEquals(setOf(bookId), repository.catalog.value.readingStates.keys)
    }
}
