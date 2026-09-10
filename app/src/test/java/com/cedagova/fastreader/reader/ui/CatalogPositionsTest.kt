package com.cedagova.fastreader.reader.ui

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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The boundary where the reader's positions reach the catalog store: a recorded
 * position becomes the book's reading state and makes it the last-read book.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPositionsTest {

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
}
