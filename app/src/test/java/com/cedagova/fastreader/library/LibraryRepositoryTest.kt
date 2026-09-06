package com.cedagova.fastreader.library

import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.store.CatalogLoad
import com.cedagova.fastreader.library.store.CatalogStore
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.PivotColor
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.fastreader.timing.PauseStrength
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gateway = FakeDocumentGateway()
    private var now = 100_000L

    /** A distinct book, so the content digests that identify them differ (AD-2). */
    private fun epub(title: String) = EpubFixtures.validEpub(
        title = title,
        identifier = "urn:uuid:$title",
        bodyText = "A book called $title.",
    )

    private fun repository(
        store: CatalogStore = FileCatalogStore(File(File(temporaryFolder.root, "catalog"), "catalog.json")),
        scope: kotlinx.coroutines.CoroutineScope,
    ): LibraryRepository {
        val covers = CoverStore(File(temporaryFolder.root, "covers"))
        return LibraryRepository(
            store = store,
            ingestor = CatalogIngestor(gateway, covers, clock = { now }),
            gateway = gateway,
            covers = covers,
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[kotlinx.coroutines.test.TestCoroutineScheduler]),
            clock = { now },
        )
    }

    @Test
    fun `adding books publishes the catalog and a completed ingestion state`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(scope = backgroundScope)

        repository.addPickedBooks(listOf("doc://a"))

        assertEquals(1, repository.catalog.value.books.size)
        val state = repository.ingestion.value as IngestionState.Completed
        assertEquals(ScanTrigger.ADD_BOOKS, state.trigger)
        assertEquals(1, state.added)
    }

    @Test
    fun `the catalog survives a restart of the repository`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val file = File(File(temporaryFolder.root, "catalog"), "catalog.json")
        val first = repository(FileCatalogStore(file), backgroundScope)
        first.addPickedBooks(listOf("doc://a"))
        val bookId = first.catalog.value.books.single().id
        first.updateReadingState(bookId, ReadingState(bookDigest = bookId, tokenIndex = 512))

        val second = repository(FileCatalogStore(file), backgroundScope)
        second.load()

        assertEquals(1, second.catalog.value.books.size)
        assertEquals(512, second.readingState(bookId)?.tokenIndex)
        assertEquals(bookId, second.catalog.value.lastReadBookId)
        assertNotNull(second.coverFile(bookId))
    }

    // REQ-002: both the open-rescan and the manual refresh find a newly copied book.
    @Test
    fun `manual refresh finds a new book that the debounced app-open scan skipped`() = runTest {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val repository = repository(scope = backgroundScope)
        repository.addFolder("tree://books", "Books")
        assertEquals(1, repository.catalog.value.books.size)

        gateway.putIntoFolder("tree://books", "tree://books/two.epub", EpubFixtures.spanishEpub(), "two.epub")

        // Same instant as the add: the app-open scan is suppressed so returning from
        // the picker does not immediately rescan the whole tree.
        repository.rescan(ScanTrigger.APP_OPEN)
        assertEquals(1, repository.catalog.value.books.size)

        // A manual refresh always runs.
        repository.rescan(ScanTrigger.MANUAL_REFRESH)
        assertEquals(2, repository.catalog.value.books.size)
    }

    @Test
    fun `the app-open scan runs once the debounce window has passed`() = runTest {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val repository = repository(scope = backgroundScope)
        repository.addFolder("tree://books", "Books")

        gateway.putIntoFolder("tree://books", "tree://books/two.epub", EpubFixtures.spanishEpub(), "two.epub")
        now += LibraryRepository.DEFAULT_MINIMUM_RESCAN_INTERVAL_MS + 1
        repository.rescan(ScanTrigger.APP_OPEN)

        assertEquals(2, repository.catalog.value.books.size)
    }

    @Test
    fun `a catalog written by a newer app version blocks writes instead of losing books`() = runTest {
        val blocking = object : CatalogStore {
            var saves = 0
            override fun load() = CatalogLoad.Blocked("catalog was written by a newer version of the app")
            override fun save(catalog: Catalog) {
                saves++
            }
        }
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(blocking, backgroundScope)

        repository.addPickedBooks(listOf("doc://a"))

        assertEquals(0, blocking.saves)
        assertTrue(repository.catalog.value.books.isEmpty())
        val failure = repository.ingestion.value as IngestionState.Failed
        assertTrue(failure.message.contains("newer version"))
    }

    @Test
    fun `a failed write is reported rather than swallowed`() = runTest {
        val failing = object : CatalogStore {
            override fun load() = CatalogLoad.Loaded(Catalog())
            override fun save(catalog: Catalog): Unit = throw IOException("disk is full")
        }
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(failing, backgroundScope)

        repository.addPickedBooks(listOf("doc://a"))

        assertEquals("disk is full", (repository.ingestion.value as IngestionState.Failed).message)
        assertTrue(repository.catalog.value.books.isEmpty())
    }

    @Test
    fun `the reading position survives removing and re-adding a book`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(scope = backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        repository.updateReadingState(
            bookId,
            ReadingState(bookDigest = bookId, tokenIndex = 900, progressFraction = 0.6f, wpm = 400),
        )

        repository.removeBook(bookId)
        assertTrue(repository.catalog.value.books.isEmpty())
        assertEquals(900, repository.readingState(bookId)?.tokenIndex)

        repository.addPickedBooks(listOf("doc://a"))

        assertEquals(bookId, repository.catalog.value.books.single().id)
        assertEquals(900, repository.readingState(bookId)?.tokenIndex)
        assertEquals(400, repository.readingState(bookId)?.wpm)
    }

    @Test
    fun `a removed folder book stays removed across an app-open rescan and a restart`() = runTest {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val file = File(File(temporaryFolder.root, "catalog"), "catalog.json")
        val repository = repository(FileCatalogStore(file), backgroundScope)
        repository.addFolder("tree://books", "Books")
        val bookId = repository.catalog.value.books.single().id
        repository.updateReadingState(bookId, ReadingState(bookDigest = bookId, tokenIndex = 250))

        repository.removeBook(bookId)
        now += LibraryRepository.DEFAULT_MINIMUM_RESCAN_INTERVAL_MS + 1
        repository.rescan(ScanTrigger.APP_OPEN)

        assertTrue("the app-open rescan must not resurrect a removed book", repository.catalog.value.books.isEmpty())

        val restarted = repository(FileCatalogStore(file), backgroundScope)
        now += LibraryRepository.DEFAULT_MINIMUM_RESCAN_INTERVAL_MS + 1
        restarted.rescan(ScanTrigger.APP_OPEN)

        assertTrue("the removal must survive a restart", restarted.catalog.value.books.isEmpty())
        assertEquals(250, restarted.readingState(bookId)?.tokenIndex)
    }

    /**
     * REQ-104's acceptance case, exactly: a folder holding three books, one of
     * which was also picked directly. Two books belong to the folder alone, so
     * that is the number the confirmation names and the number of rows that go.
     * The third stays, no file is touched, and all three positions survive.
     */
    @Test
    fun `removing a folder drops only the books it alone provided and keeps every file and position`() = runTest {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", epub("One"), "one.epub")
        gateway.putIntoFolder("tree://books", "tree://books/two.epub", epub("Two"), "two.epub")
        val alsoPicked = epub("Three")
        gateway.putIntoFolder("tree://books", "tree://books/three.epub", alsoPicked, "three.epub")
        // The same book reached from outside the tree, as the document picker
        // hands it over: same content, so the same book (AD-2), second source.
        gateway.putDocument("doc://three", alsoPicked, "three.epub")
        val repository = repository(scope = backgroundScope)
        repository.addFolder("tree://books", "Books")
        repository.addPickedBooks(listOf("doc://three"))
        val byTitle = repository.catalog.value.books.associateBy { it.title }
        assertEquals(3, byTitle.size)
        byTitle.values.forEachIndexed { index, book ->
            repository.updateReadingState(book.id, ReadingState(bookDigest = book.id, tokenIndex = 100 + index))
        }

        // The number the confirmation names, taken from the catalog it acts on.
        assertEquals(2, repository.catalog.value.booksOnlyFrom("tree://books").size)
        assertEquals(3, repository.catalog.value.booksIn("tree://books").size)

        repository.removeFolder("tree://books")

        val remaining = repository.catalog.value.books
        assertEquals("only the directly picked book stays", listOf("Three"), remaining.map { it.title })
        assertTrue("the folder itself is gone", repository.catalog.value.folders.isEmpty())
        assertEquals("no file is touched", 4, gateway.documents.size)
        byTitle.values.forEach { book ->
            assertNotNull("the position of ${book.title} must survive", repository.readingState(book.id))
        }
    }

    /**
     * REQ-105: the removal can be taken back while the offer stands, and what
     * comes back is the row with the reader's place in it.
     */
    @Test
    fun `undo puts a removed book back with its position and progress`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(scope = backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id
        repository.updateReadingState(
            bookId,
            ReadingState(bookDigest = bookId, tokenIndex = 900, progressFraction = 0.6f, wpm = 400),
        )

        repository.removeBook(bookId)
        assertTrue(repository.catalog.value.books.isEmpty())
        assertEquals(bookId, repository.undoableRemoval.value?.bookId)

        repository.undoRemoveBook()

        assertEquals(bookId, repository.catalog.value.books.single().id)
        assertNull("the offer is spent once it is taken", repository.undoableRemoval.value)
        assertEquals(900, repository.readingState(bookId)?.tokenIndex)
        assertEquals(0.6f, repository.readingState(bookId)?.progressFraction)
        assertEquals(400, repository.readingState(bookId)?.wpm)
        assertTrue("the book must be readable again", repository.catalog.value.books.single().status == BookStatus.READABLE)
        assertTrue(
            "a grant given back cannot be taken again without the picker, so undo must not release it",
            gateway.releasedGrants.isEmpty(),
        )
        assertEquals(EpubFixtures.validEpub().size, repository.openBook(bookId).use { it.readBytes() }.size)
    }

    /** REQ-105's other half: let the window elapse and the removal stands. */
    @Test
    fun `the undo window elapsing makes the removal final and gives the grant back`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(scope = backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id

        repository.removeBook(bookId)
        assertNotNull(repository.undoableRemoval.value)

        advanceTimeBy(LibraryRepository.DEFAULT_UNDO_WINDOW_MS + 1)
        runCurrent()

        assertNull("the offer must expire", repository.undoableRemoval.value)
        assertEquals(listOf("doc://a"), gateway.releasedGrants)

        repository.undoRemoveBook()

        assertTrue("undo after the window must do nothing", repository.catalog.value.books.isEmpty())
    }

    /**
     * The undo window is longer than the app-open rescan debounce, so a rescan
     * lands inside it every time the reader removes a book and switches away.
     * Undo has to survive that without duplicating or losing the row.
     */
    @Test
    fun `undo restores exactly one row even when a rescan runs inside the window`() = runTest {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val repository = repository(scope = backgroundScope)
        repository.addFolder("tree://books", "Books")
        val bookId = repository.catalog.value.books.single().id

        repository.removeBook(bookId)
        now += LibraryRepository.DEFAULT_MINIMUM_RESCAN_INTERVAL_MS + 1
        repository.rescan(ScanTrigger.APP_OPEN)
        assertTrue("the rescan must honour the removal while it stands", repository.catalog.value.books.isEmpty())

        repository.undoRemoveBook()

        assertEquals(listOf(bookId), repository.catalog.value.books.map { it.id })
        assertEquals(1, repository.catalog.value.books.single().sources.size)

        // And the row stays exactly one row once scanning resumes.
        now += LibraryRepository.DEFAULT_MINIMUM_RESCAN_INTERVAL_MS + 1
        repository.rescan(ScanTrigger.APP_OPEN)

        assertEquals(listOf(bookId), repository.catalog.value.books.map { it.id })
        assertEquals(1, repository.catalog.value.books.single().sources.size)
    }

    /**
     * Removing the folder a pending removal came from takes its last source with
     * it. Nothing rescans a folder that is no longer added, so bringing that row
     * back would leave a book that looks readable and never opens.
     */
    @Test
    fun `undo brings back nothing when the folder the book came from is gone too`() = runTest {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val repository = repository(scope = backgroundScope)
        repository.addFolder("tree://books", "Books")
        val bookId = repository.catalog.value.books.single().id
        repository.updateReadingState(bookId, ReadingState(bookDigest = bookId, tokenIndex = 640))

        repository.removeBook(bookId)
        repository.removeFolder("tree://books")
        repository.undoRemoveBook()

        assertTrue("no orphaned row may come back", repository.catalog.value.books.isEmpty())
        assertEquals("the position still outlives it (REQ-004)", 640, repository.readingState(bookId)?.tokenIndex)
    }

    /**
     * The undo window only exists in memory. A reader who removes a book and
     * then leaves the app takes the timer with them, and the baseline released
     * that grant synchronously — so without reconciling at load, deferring the
     * release would leak a persisted grant permanently, and Android caps how
     * many an app may hold.
     */
    @Test
    fun `a grant orphaned by a process that died inside the undo window is released at the next load`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val file = File(File(temporaryFolder.root, "catalog"), "catalog.json")
        val first = repository(FileCatalogStore(file), backgroundScope)
        first.addPickedBooks(listOf("doc://a"))
        val bookId = first.catalog.value.books.single().id

        first.removeBook(bookId)
        // The window is still open: nothing has given the grant back yet.
        assertTrue(gateway.releasedGrants.isEmpty())
        assertTrue("doc://a" in gateway.persistedGrants)

        // A new process over the same store, as after the app was swiped away.
        repository(FileCatalogStore(file), backgroundScope).load()

        assertEquals(listOf("doc://a"), gateway.releasedGrants)
        assertTrue("doc://a" !in gateway.persistedGrants)
    }

    /** The sweep must not touch a grant the library is actually using. */
    @Test
    fun `the load sweep keeps every grant the catalog still references`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.spanishEpub(), "one.epub")
        val file = File(File(temporaryFolder.root, "catalog"), "catalog.json")
        val first = repository(FileCatalogStore(file), backgroundScope)
        first.addPickedBooks(listOf("doc://a"))
        first.addFolder("tree://books", "Books")

        repository(FileCatalogStore(file), backgroundScope).load()

        assertTrue("nothing in use may be released, got ${gateway.releasedGrants}", gateway.releasedGrants.isEmpty())
    }

    /**
     * A store that refuses to load reports an empty catalog. Sweeping against it
     * would release the grant for every book the reader owns.
     */
    @Test
    fun `a blocked store never triggers the grant sweep`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        gateway.persistReadPermission("doc://a", isTree = false)
        val blocking = object : CatalogStore {
            override fun load() = CatalogLoad.Blocked("catalog was written by a newer version of the app")
            override fun save(catalog: Catalog) = Unit
        }

        repository(blocking, backgroundScope).load()

        assertTrue("a blocked load must not release anything", gateway.releasedGrants.isEmpty())
        assertTrue("doc://a" in gateway.persistedGrants)
    }

    /**
     * Re-picking the file inside the window puts the book back by another route.
     * The expiring timer must not then release a grant the library is using.
     */
    @Test
    fun `a book re-added inside the undo window keeps its grant when the window closes`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val repository = repository(scope = backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id

        repository.removeBook(bookId)
        repository.addPickedBooks(listOf("doc://a"))
        advanceTimeBy(LibraryRepository.DEFAULT_UNDO_WINDOW_MS + 1)
        runCurrent()

        assertEquals(bookId, repository.catalog.value.books.single().id)
        assertTrue("the grant is still in use", gateway.releasedGrants.isEmpty())
        assertTrue("doc://a" in gateway.persistedGrants)
    }

    @Test
    fun `a book can be opened for reading in place`() = runTest {
        val bytes = EpubFixtures.validEpub()
        gateway.putDocument("doc://a", bytes, "quiet.epub")
        val repository = repository(scope = backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id

        val read = repository.openBook(bookId).use { it.readBytes() }

        assertTrue(bytes.contentEquals(read))
    }

    @Test
    fun `the folder name comes from the platform when the caller does not supply one`() = runTest {
        gateway.putDocument("tree://books", ByteArray(0), "My Books")
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val repository = repository(scope = backgroundScope)

        repository.addFolder("tree://books")

        assertEquals("My Books", repository.catalog.value.folders.single().displayName)
    }

    /**
     * The definition's guardrail is that a write failure is loud, not that it is
     * permanent. Positions are written continuously now, so a transient failure is
     * something a reader can easily hit; leaving both banners up after the store
     * started working again would make the app look broken.
     */
    @Test
    fun `a write failure is loud on both surfaces and clears when writing works again`() = runTest {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val store = FailableStore(FileCatalogStore(File(File(temporaryFolder.root, "catalog"), "catalog.json")))
        val repository = repository(store, backgroundScope)
        repository.addPickedBooks(listOf("doc://a"))
        val bookId = repository.catalog.value.books.single().id

        store.failing = true
        repository.updateReadingState(bookId, ReadingState(bookDigest = bookId, tokenIndex = 120))

        assertEquals("the disk is full", repository.persistenceFailure.value)
        assertEquals("the disk is full", (repository.ingestion.value as IngestionState.Failed).message)

        store.failing = false
        repository.updateReadingState(bookId, ReadingState(bookDigest = bookId, tokenIndex = 121))

        assertNull(repository.persistenceFailure.value)
        assertTrue(repository.ingestion.value !is IngestionState.Failed)
        assertEquals(121, repository.readingState(bookId)?.tokenIndex)
    }

    // --- Settings (LEAF302) -------------------------------------------------

    @Test
    fun `a settings change is stored and survives a restart of the repository`() = runTest {
        val file = File(File(temporaryFolder.root, "catalog"), "catalog.json")
        val first = repository(FileCatalogStore(file), backgroundScope)
        first.load()

        first.updateSettings { it.copy(theme = ThemeChoice.DARK, pauseStrength = PauseStrength.OFF) }

        val second = repository(FileCatalogStore(file), backgroundScope)
        second.load()

        assertEquals(ThemeChoice.DARK, second.settings.value.theme)
        assertEquals(PauseStrength.OFF, second.settings.value.pauseStrength)
        assertEquals(FontSize.MEDIUM, second.settings.value.fontSize)
    }

    /** REQ-023: reset restores exactly the documented defaults, not "most of" them. */
    @Test
    fun `reset to defaults restores every documented default`() = runTest {
        val repository = repository(scope = backgroundScope)
        repository.load()
        repository.updateSettings {
            it.copy(
                theme = ThemeChoice.LIGHT,
                fontSize = FontSize.EXTRA_LARGE,
                highlightEnabled = false,
                focusAlignmentEnabled = true,
                pivotColor = PivotColor.TEAL,
                guideMarksEnabled = false,
                pauseStrength = PauseStrength.STRONG,
            )
        }

        repository.updateSettings { ReaderSettings.DEFAULTS }

        assertEquals(ReaderSettings.DEFAULTS, repository.settings.value)
        assertTrue(repository.settings.value.isDefault)
    }

    /**
     * The definition's persistence guardrail applied to settings: a change that
     * cannot be written leaves the reader looking at the value that *is* stored,
     * with the reason on screen — never a control that appears to have taken.
     */
    @Test
    fun `a settings write that fails is loud and does not pretend to have taken`() = runTest {
        val store = FailableStore(FileCatalogStore(File(File(temporaryFolder.root, "catalog"), "catalog.json")))
        val repository = repository(store, backgroundScope)
        repository.load()

        store.failing = true
        repository.updateSettings { it.copy(theme = ThemeChoice.DARK) }

        assertEquals(ThemeChoice.SYSTEM, repository.settings.value.theme)
        assertEquals("the disk is full", repository.persistenceFailure.value)

        store.failing = false
        repository.updateSettings { it.copy(theme = ThemeChoice.DARK) }

        assertEquals(ThemeChoice.DARK, repository.settings.value.theme)
        assertNull(repository.persistenceFailure.value)
    }

    /** Wraps a real store so a write can be made to fail and then recover. */
    private class FailableStore(private val delegate: CatalogStore) : CatalogStore {
        var failing = false

        override fun load(): CatalogLoad = delegate.load()

        override fun save(catalog: Catalog) {
            if (failing) throw IOException("the disk is full")
            delegate.save(catalog)
        }
    }
}
