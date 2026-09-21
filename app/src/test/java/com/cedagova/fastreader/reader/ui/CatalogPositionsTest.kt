package com.cedagova.fastreader.reader.ui

import com.cedagova.fastreader.account.library.AccountBook
import com.cedagova.fastreader.account.library.AccountLibraryActions
import com.cedagova.fastreader.account.library.AccountLibraryState
import com.cedagova.fastreader.account.library.AccountShelf
import com.cedagova.fastreader.account.library.AccountSyncPhase
import com.cedagova.fastreader.account.library.LocalReadingPosition
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderPosition
import com.cedagova.reader.library.model.ReaderLibraryStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * REQ-512 at the gate that enforces it: a book the account does not hold
     * never names itself to the Reader API, whatever the reader does in it.
     *
     * The shelf here is signed in with an account that holds a *different* book,
     * so the failure this catches is the one that matters — not "nothing is wired
     * up", but "the wire is there and this book still does not go down it".
     */
    @Test
    fun `a device-only book publishes no position, though the account is signed in`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        val actions = RecordingActions()
        val shelf = AccountShelf(
            actions = actions,
            state = MutableStateFlow(
                AccountLibraryState(
                    phase = AccountSyncPhase.IDLE,
                    userId = "user-1",
                    books = listOf(AccountBook(bookId = "acct-other", contentSha256 = "f".repeat(64))),
                ),
            ),
            scope = backgroundScope,
        )

        CatalogPositions(repository, shelf)
            .publishPortable(bookId, ReaderFixtures.englishNovel, tokenIndex = 40)

        assertEquals("a device-only book tells the account nothing", emptyList<String>(), actions.positions)
    }

    /** The same call for a book the account does hold publishes it, under the account's id. */
    @Test
    fun `an account book publishes its portable position under the account's book id`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        val actions = RecordingActions()
        val shelf = AccountShelf(
            actions = actions,
            state = MutableStateFlow(
                AccountLibraryState(
                    phase = AccountSyncPhase.IDLE,
                    userId = "user-1",
                    // The same content, which is the whole of the identity the
                    // shelf merges a device row and an account row on (AD-23).
                    books = listOf(AccountBook(bookId = "acct-1", contentSha256 = bookId)),
                ),
            ),
            scope = backgroundScope,
        )
        val book = ReaderFixtures.englishNovel
        val chapter = book.chapters.first { !it.isEmpty }

        CatalogPositions(repository, shelf)
            .publishPortable(bookId, book, tokenIndex = chapter.startTokenIndex)

        assertEquals(1, actions.positions.size)
        val (published, position) = actions.positions.single()
        assertEquals("the account's id, not the device's digest", "acct-1", published)
        assertEquals(chapter.spinePath, position.href)
    }

    /** Signed out there is no account row to resolve against, so nothing is published. */
    @Test
    fun `nothing is published while signed out`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        val actions = RecordingActions()
        val shelf = AccountShelf(
            actions = actions,
            state = MutableStateFlow(AccountLibraryState.SIGNED_OUT),
            scope = backgroundScope,
        )

        CatalogPositions(repository, shelf)
            .publishPortable(bookId, ReaderFixtures.englishNovel, tokenIndex = 40)

        assertEquals(emptyList<String>(), actions.positions)
    }

    /** Only the position action is recorded; the rest are the shelf's and not this test's. */
    private class RecordingActions : AccountLibraryActions {
        val positions = mutableListOf<Pair<String, LocalReadingPosition>>()

        override fun refresh() = Unit

        override fun removeFromAccount(bookId: String) = Unit

        override fun undoRemove(bookId: String) = Unit

        override fun recordOpened(bookId: String) = Unit

        override fun recordFinished(bookId: String) = Unit

        override fun recordStatus(bookId: String, status: ReaderLibraryStatus) = Unit

        override fun recordPosition(bookId: String, position: LocalReadingPosition) {
            positions += bookId to position
        }
    }
}
