package com.cedagova.fastreader.reader.ui

import com.cedagova.fastreader.account.library.AccountResumeOffers
import com.cedagova.fastreader.account.library.AccountShelf
import com.cedagova.fastreader.account.library.RecordingHostRecords
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.reader.ReaderFixtures
import com.cedagova.fastreader.reader.ReaderPosition
import com.cedagova.reader.engine.content.TokenPosition
import com.cedagova.reader.engine.epub.EpubFixtures
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.sync.AccountBook
import com.cedagova.reader.library.sync.AccountLibraryActions
import com.cedagova.reader.library.sync.AccountLibraryState
import com.cedagova.reader.library.sync.AccountRemotePosition
import com.cedagova.reader.library.sync.AccountSyncPhase
import com.cedagova.reader.library.sync.LocalReadingPosition
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            resumeOffers = AccountResumeOffers(RecordingHostRecords()),
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
            resumeOffers = AccountResumeOffers(RecordingHostRecords()),
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
            resumeOffers = AccountResumeOffers(RecordingHostRecords()),
            actions = actions,
            state = MutableStateFlow(AccountLibraryState.SIGNED_OUT),
            scope = backgroundScope,
        )

        CatalogPositions(repository, shelf)
            .publishPortable(bookId, ReaderFixtures.englishNovel, tokenIndex = 40)

        assertEquals(emptyList<String>(), actions.positions)
    }

    // --- REQ-511, the resume offer's gates -----------------------------------
    //
    // The consume half of the same seam, with the same two gates as
    // `publishPortable` plus the "is there anything to ask?" one. Driven through
    // `CatalogPositions` rather than through Compose, because every decision in
    // it is arithmetic over a parsed book and two stored values.

    /**
     * The ordinary case: the account holds a place further on, and the offer names
     * the chapter *this* parse has for it.
     */
    @Test
    fun `a remote position ahead of the reader is offered, with this parse's chapter`() = runTest {
        val book = ReaderFixtures.englishNovel
        val chapter = book.chapters.last { !it.isEmpty }
        val target = chapter.startTokenIndex
        val positions = accountPositions(
            remote = AccountRemotePosition(
                href = chapter.spinePath,
                chapterTitle = "whatever the other client called it",
                progression = book.progressFraction(target).toDouble(),
                percent = 77.0,
                updatedAt = "2026-09-20T10:00:00Z",
                revision = 4,
            ),
        )

        val offer = requireNotNull(positions.remoteOffer(deviceBookId, book, tokenIndex = 0))

        assertEquals("the account's id, not the device's digest", "acct-1", offer.accountBookId)
        assertEquals("4:2026-09-20T10:00:00Z", offer.changeKey)
        assertEquals(
            "the token the mapping lands on, inside the named chapter",
            chapter.startTokenIndex,
            offer.targetTokenIndex,
        )
        assertEquals(
            "this parse's own title for the section, not the record's",
            chapter.title,
            offer.chapterTitle,
        )
        assertEquals("the percent the other client published", 77, offer.percent)
    }

    /**
     * A section this edition does not have cannot be honoured, so the offer names
     * the percentage alone — deliberately not the chapter title the record carried,
     * which describes a book this device does not hold (#121's edge case).
     */
    @Test
    fun `an unknown section offers the percent and names no chapter`() = runTest {
        val book = ReaderFixtures.englishNovel
        val positions = accountPositions(
            remote = AccountRemotePosition(
                href = "OEBPS/a-spine-path-this-edition-never-had.xhtml",
                chapterTitle = "Chapter Eight",
                progression = 0.6,
                percent = 60.0,
                updatedAt = "2026-09-20T10:00:00Z",
                revision = 2,
            ),
        )

        val offer = requireNotNull(positions.remoteOffer(deviceBookId, book, tokenIndex = 0))

        assertNull("no chapter can be promised for a section this parse lacks", offer.chapterTitle)
        assertEquals(60, offer.percent)
        assertEquals(
            "the fraction alone decides where it lands",
            (0.6 * book.totalTokens).toInt(),
            offer.targetTokenIndex,
        )
    }

    /**
     * The gate that makes the round trip liveable: a position level with the
     * reader — which is what this device's *own* published position looks like
     * when it comes back through the change stream — asks nothing.
     *
     * A display and question gate, never an adoption: declining or never being
     * asked both leave the local position exactly as it was, and this device keeps
     * publishing backward moves like any other.
     */
    @Test
    fun `a remote position level with the reader offers nothing`() = runTest {
        val book = ReaderFixtures.englishNovel
        val chapter = book.chapters.last { !it.isEmpty }
        val positions = accountPositions(
            remote = AccountRemotePosition(
                href = chapter.spinePath,
                progression = book.progressFraction(chapter.startTokenIndex).toDouble(),
                percent = 77.0,
                updatedAt = "2026-09-20T10:00:00Z",
                revision = 4,
            ),
        )

        assertNull(positions.remoteOffer(deviceBookId, book, tokenIndex = chapter.startTokenIndex))
    }

    /** And #121's other edge: a position behind the reader is not offered either. */
    @Test
    fun `a remote position behind the reader offers nothing`() = runTest {
        val book = ReaderFixtures.englishNovel
        val early = book.chapters.first { !it.isEmpty }
        val late = book.chapters.last { !it.isEmpty }
        val positions = accountPositions(
            remote = AccountRemotePosition(
                href = early.spinePath,
                progression = book.progressFraction(early.startTokenIndex).toDouble(),
                percent = 4.0,
                updatedAt = "2026-09-20T10:00:00Z",
                revision = 4,
            ),
        )

        assertNull(positions.remoteOffer(deviceBookId, book, tokenIndex = late.startTokenIndex))
    }

    /**
     * #140: this device published 40 %, the backend admitted it, and the reader
     * has since rewound. The account's position is ahead of the reader — gate 2
     * alone would offer it — but it is this device's own, not another device's.
     */
    @Test
    fun `this device's own admitted position is not offered, though it is ahead of the reader`() = runTest {
        val book = ReaderFixtures.englishNovel
        val chapter = book.chapters.last { !it.isEmpty }
        val remote = AccountRemotePosition(
            href = chapter.spinePath,
            progression = book.progressFraction(chapter.startTokenIndex).toDouble(),
            percent = 40.0,
            updatedAt = "2026-09-20T10:00:00Z",
            revision = 5,
        )
        val positions = accountPositions(remote = remote, ownPositionChangeKey = remote.changeKey)

        assertNull(positions.remoteOffer(deviceBookId, book, tokenIndex = 0))
    }

    /** A newer change after this device's own is another device's, and is offered. */
    @Test
    fun `a newer position after this device's own is offered`() = runTest {
        val book = ReaderFixtures.englishNovel
        val chapter = book.chapters.last { !it.isEmpty }
        val positions = accountPositions(
            remote = AccountRemotePosition(
                href = chapter.spinePath,
                progression = book.progressFraction(chapter.startTokenIndex).toDouble(),
                percent = 70.0,
                updatedAt = "2026-09-20T11:00:00Z",
                revision = 6,
            ),
            ownPositionChangeKey = "5:2026-09-20T10:00:00Z",
        )

        val offer = requireNotNull(positions.remoteOffer(deviceBookId, book, tokenIndex = 0))

        assertEquals("6:2026-09-20T11:00:00Z", offer.changeKey)
    }

    /** An account row with no position at all: nobody has said anything to offer. */
    @Test
    fun `an account book with no remote position offers nothing`() = runTest {
        val positions = accountPositions(remote = null)

        assertNull(positions.remoteOffer(deviceBookId, ReaderFixtures.englishNovel, tokenIndex = 0))
    }

    /** The same two gates the publish path has, on the consume side. */
    @Test
    fun `a device-only book and a signed-out app are both offered nothing`() = runTest {
        val book = ReaderFixtures.englishNovel
        val chapter = book.chapters.last { !it.isEmpty }
        val remote = AccountRemotePosition(
            href = chapter.spinePath,
            progression = book.progressFraction(chapter.startTokenIndex).toDouble(),
            percent = 77.0,
            updatedAt = "2026-09-20T10:00:00Z",
            revision = 4,
        )

        assertNull(
            "the account holds a position, but for another book",
            accountPositions(remote = remote, contentSha256 = "f".repeat(64))
                .remoteOffer(deviceBookId, book, tokenIndex = 0),
        )
        assertNull(
            "signed out there are no rows to resolve against",
            accountPositions(remote = remote, signedOut = true)
                .remoteOffer(deviceBookId, book, tokenIndex = 0),
        )
    }

    /**
     * A build with no account surface at all reaches none of this: the same null
     * the v1.6.0 reader produced, from the same `account == null`.
     */
    @Test
    fun `a build with no account surface offers nothing`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id

        assertNull(
            CatalogPositions(repository).remoteOffer(bookId, ReaderFixtures.englishNovel, 0),
        )
    }

    /**
     * The device book id the account rows below are matched to, set by
     * [accountPositions] — it is the catalog id, which is the content digest
     * (AD-2), and therefore the identity the shelf merges on (AD-23).
     */
    private lateinit var deviceBookId: String

    private suspend fun TestScope.accountPositions(
        remote: AccountRemotePosition?,
        ownPositionChangeKey: String? = null,
        contentSha256: String? = null,
        signedOut: Boolean = false,
    ): CatalogPositions {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        deviceBookId = repository.catalog.value.books.single().id
        val state = if (signedOut) {
            AccountLibraryState.SIGNED_OUT
        } else {
            AccountLibraryState(
                phase = AccountSyncPhase.IDLE,
                userId = "user-1",
                books = listOf(
                    AccountBook(
                        bookId = "acct-1",
                        contentSha256 = contentSha256 ?: deviceBookId,
                        remotePosition = remote,
                        ownPositionChangeKey = ownPositionChangeKey,
                    ),
                ),
            )
        }
        return CatalogPositions(
            repository,
            AccountShelf(
                resumeOffers = AccountResumeOffers(RecordingHostRecords()),
                actions = RecordingActions(),
                state = MutableStateFlow(state),
                scope = backgroundScope,
            ),
        )
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
