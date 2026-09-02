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
