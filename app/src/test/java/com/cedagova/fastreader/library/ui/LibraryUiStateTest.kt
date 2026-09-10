package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.library.BookContentStatus
import com.cedagova.fastreader.library.BookFolder
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.FolderStatus
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.RemovedBook
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library screen's derivation rules: what is listed, in what order, with what state. */
class LibraryUiStateTest {

    @Test
    fun `an empty catalog asks for the guidance state`() {
        val state = buildLibraryUiState(Catalog(), IngestionState.Idle, query = "")

        assertEquals(LibraryContent.EMPTY_LIBRARY, state.content)
        assertTrue(state.books.isEmpty())
    }

    @Test
    fun `picked books are listed with title, author and file name (REQ-001)`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("c", "Rayuela", "Julio Cortázar", fileName = "rayuela.epub"),
                LibraryFixtures.readable("a", "Ficciones", "Jorge Luis Borges", fileName = "ficciones.epub"),
                LibraryFixtures.readable("b", "Dubliners", fileName = "dubliners.epub"),
            ),
        )

        val state = buildLibraryUiState(catalog, IngestionState.Idle, query = "")

        assertEquals(LibraryContent.BOOKS, state.content)
        // Alphabetical by title so the list is stable between scans.
        assertEquals(listOf("Dubliners", "Ficciones", "Rayuela"), state.books.map { it.title })
        val borges = state.books.single { it.title == "Ficciones" }
        assertEquals("Jorge Luis Borges", borges.author)
        assertEquals("ficciones.epub", borges.fileName)
        assertNull(state.books.single { it.title == "Dubliners" }.author)
    }

    @Test
    fun `titles sort by the reader's alphabet, not by code unit`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("z", "Zola y el naturalismo"),
                LibraryFixtures.readable("n", "Ñuño de Guzmán"),
                LibraryFixtures.readable("a", "Álvarez en Madrid"),
                LibraryFixtures.readable("b", "Beowulf"),
            ),
        )

        val titles = buildLibraryUiState(catalog, IngestionState.Idle, "").books.map { it.title }

        // Raw UTF-16 ordering would drop both accented initials below Z.
        assertTrue("$titles", titles.indexOf("Álvarez en Madrid") < titles.indexOf("Beowulf"))
        assertTrue("$titles", titles.indexOf("Ñuño de Guzmán") < titles.indexOf("Zola y el naturalismo"))
        assertEquals("Álvarez en Madrid", titles.first())
    }

    @Test
    fun `a title opening with punctuation files under its first letter`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("q", "¿Quién teme a la máquina?"),
                LibraryFixtures.readable("p", "Pedro Páramo"),
                LibraryFixtures.readable("r", "Rayuela"),
            ),
        )

        val titles = buildLibraryUiState(catalog, IngestionState.Idle, "").books.map { it.title }

        // Not first in the list: the leading inverted question mark is not a letter.
        assertEquals(listOf("Pedro Páramo", "¿Quién teme a la máquina?", "Rayuela"), titles)
    }

    @Test
    fun `search filters by author as typed (REQ-003)`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("a", "Ficciones", "Jorge Luis Borges"),
                LibraryFixtures.readable("b", "Rayuela", "Julio Cortázar"),
            ),
        )

        assertEquals(2, buildLibraryUiState(catalog, IngestionState.Idle, "").books.size)
        assertEquals(
            listOf("Ficciones"),
            buildLibraryUiState(catalog, IngestionState.Idle, "borg").books.map { it.title },
        )
        assertEquals(
            listOf("Ficciones"),
            buildLibraryUiState(catalog, IngestionState.Idle, "BORGES").books.map { it.title },
        )
    }

    @Test
    fun `search also matches title and file name`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("a", "Ficciones", "Jorge Luis Borges", fileName = "el-aleph-draft.epub"),
                LibraryFixtures.readable("b", "Rayuela", "Julio Cortázar", fileName = "rayuela.epub"),
            ),
        )

        assertEquals(listOf("Rayuela"), buildLibraryUiState(catalog, IngestionState.Idle, "rayue").books.map { it.title })
        assertEquals(listOf("Ficciones"), buildLibraryUiState(catalog, IngestionState.Idle, "aleph").books.map { it.title })
    }

    @Test
    fun `search ignores accents so a Spanish library is searchable from an English keyboard`() {
        val catalog = Catalog(books = listOf(LibraryFixtures.readable("a", "Rayuela", "Julio Cortázar")))

        assertEquals(1, buildLibraryUiState(catalog, IngestionState.Idle, "cortazar").books.size)
        assertEquals(1, buildLibraryUiState(catalog, IngestionState.Idle, "Cortázar").books.size)
    }

    @Test
    fun `a search matching nothing is distinct from an empty library`() {
        val catalog = Catalog(books = listOf(LibraryFixtures.readable("a", "Ficciones", "Jorge Luis Borges")))

        val state = buildLibraryUiState(catalog, IngestionState.Idle, "tolkien")

        assertEquals(LibraryContent.NO_SEARCH_RESULTS, state.content)
        assertTrue(state.books.isEmpty())
    }

    @Test
    fun `progress comes from the stored reading position (REQ-004)`() {
        val catalog = Catalog(
            books = listOf(LibraryFixtures.readable("a", "Ficciones")),
            readingStates = mapOf("a" to ReadingState(progressFraction = 0.42f)),
        )

        assertEquals(42, buildLibraryUiState(catalog, IngestionState.Idle, "").books.single().progressPercent)
    }

    @Test
    fun `a book with no stored position reads as zero percent`() {
        val catalog = Catalog(books = listOf(LibraryFixtures.readable("a", "Ficciones")))

        assertEquals(0, buildLibraryUiState(catalog, IngestionState.Idle, "").books.single().progressPercent)
    }

    @Test
    fun `each failure state is carried through distinctly (REQ-005)`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.rejected("corrupt", "Broken", BookContentStatus.CORRUPT, "CORRUPT_ARCHIVE"),
                LibraryFixtures.rejected("drm", "Locked", BookContentStatus.DRM_PROTECTED, "DRM_PROTECTED"),
                LibraryFixtures.unavailable("gone", "Moved", SourceAvailability.MISSING),
                LibraryFixtures.unavailable("revoked", "Revoked", SourceAvailability.PERMISSION_LOST),
                LibraryFixtures.readable("ok", "Fine"),
            ),
        )

        val byTitle = buildLibraryUiState(catalog, IngestionState.Idle, "").books.associateBy { it.title }

        assertEquals(BookStatus.CORRUPT, byTitle.getValue("Broken").status)
        assertEquals(BookStatus.DRM_PROTECTED, byTitle.getValue("Locked").status)
        assertEquals(BookStatus.MISSING, byTitle.getValue("Moved").status)
        assertEquals(BookStatus.PERMISSION_LOST, byTitle.getValue("Revoked").status)
        assertEquals(BookStatus.READABLE, byTitle.getValue("Fine").status)
    }

    @Test
    fun `a folder book that lost access offers its folder as the re-grant target`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.unavailable(
                    id = "revoked",
                    title = "Revoked",
                    availability = SourceAvailability.PERMISSION_LOST,
                    origin = SourceOrigin.FOLDER,
                    folderId = "content://tree/novels",
                ),
            ),
        )

        assertEquals(
            "content://tree/novels",
            buildLibraryUiState(catalog, IngestionState.Idle, "").books.single().regrantTreeUri,
        )
    }

    @Test
    fun `a directly picked book that lost access has no folder to re-grant`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.unavailable(
                    id = "revoked",
                    title = "Revoked",
                    availability = SourceAvailability.PERMISSION_LOST,
                    origin = SourceOrigin.DIRECT_PICK,
                    folderId = null,
                ),
            ),
        )

        assertNull(buildLibraryUiState(catalog, IngestionState.Idle, "").books.single().regrantTreeUri)
    }

    @Test
    fun `the cover placeholder skips leading punctuation`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("a", "¿Quién teme a la máquina?"),
                LibraryFixtures.readable("b", "Dubliners"),
                LibraryFixtures.readable("c", "…"),
            ),
        )

        val byId = buildLibraryUiState(catalog, IngestionState.Idle, "").books.associateBy { it.id }

        assertEquals("Q", byId.getValue("a").coverInitial)
        assertEquals("D", byId.getValue("b").coverInitial)
        assertEquals("?", byId.getValue("c").coverInitial)
    }

    @Test
    fun `a running scan becomes the loading state with determinate progress (REQ-002)`() {
        val scanning = IngestionState.Scanning(ScanTrigger.ADD_FOLDER, processed = 3, total = 12, currentName = "x.epub")

        val state = buildLibraryUiState(Catalog(), scanning, query = "")

        val scan = requireNotNull(state.scan)
        assertEquals(ScanTrigger.ADD_FOLDER, scan.trigger)
        assertEquals(0.25f, requireNotNull(scan.fraction), 0.0001f)
        assertEquals("x.epub", scan.currentName)
    }

    @Test
    fun `a scan that does not know its size stays indeterminate`() {
        val state = buildLibraryUiState(Catalog(), IngestionState.Scanning(ScanTrigger.APP_OPEN), query = "")

        assertNull(requireNotNull(state.scan).fraction)
    }

    @Test
    fun `a failed catalog update is surfaced to the reader`() {
        val state = buildLibraryUiState(Catalog(), IngestionState.Failed("the catalog is unreadable"), query = "")

        assertEquals("the catalog is unreadable", state.failureMessage)
    }

    @Test
    fun `a finished scan leaves no loading state behind`() {
        val completed = IngestionState.Completed(ScanTrigger.MANUAL_REFRESH, 1, 0, 0, 0, 1_700_000_000_000)

        val state = buildLibraryUiState(Catalog(), completed, query = "")

        assertNull(state.scan)
        assertNull(state.failureMessage)
    }

    // REQ-004/REQ-018: the number the library shows comes from real reading.
    @Test
    fun `percent read comes from the stored position and only reaches 100 when finished`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.readable("started", "Started"),
                LibraryFixtures.readable("nearly", "Nearly"),
                LibraryFixtures.readable("finished", "Finished"),
                LibraryFixtures.readable("untouched", "Untouched"),
            ),
            readingStates = mapOf(
                "started" to ReadingState(bookDigest = "started", tokenIndex = 370, progressFraction = 0.37f),
                // Would round to 100 and claim to be finished; it is not.
                "nearly" to ReadingState(bookDigest = "nearly", tokenIndex = 9_996, progressFraction = 0.9997f),
                "finished" to ReadingState(bookDigest = "finished", tokenIndex = 9_999, progressFraction = 1f),
            ),
        )

        val percentByTitle = buildLibraryUiState(catalog, IngestionState.Idle, query = "")
            .books.associate { it.title to it.progressPercent }

        assertEquals(37, percentByTitle.getValue("Started"))
        assertEquals(99, percentByTitle.getValue("Nearly"))
        assertEquals(100, percentByTitle.getValue("Finished"))
        assertEquals(0, percentByTitle.getValue("Untouched"))
    }

    // REQ-009: the library has to say why it opened here instead of in the book.
    @Test
    fun `a blocked resume names the book and the reason`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.unavailable("revoked", "Down and Out", SourceAvailability.PERMISSION_LOST),
            ),
            lastReadBookId = "revoked",
        )

        val state = buildLibraryUiState(
            catalog,
            IngestionState.Idle,
            query = "",
            resumeBlocked = ResumeBlocked("revoked", ResumeBlockedReason.PERMISSION_LOST),
        )

        val notice = requireNotNull(state.resumeNotice)
        assertEquals("Down and Out", notice.title)
        assertEquals(ResumeBlockedReason.PERMISSION_LOST, notice.reason)
    }

    @Test
    fun `a blocked resume for a removed book still renders without a title`() {
        val state = buildLibraryUiState(
            Catalog(),
            IngestionState.Idle,
            query = "",
            resumeBlocked = ResumeBlocked("gone", ResumeBlockedReason.REMOVED),
        )

        val notice = requireNotNull(state.resumeNotice)
        assertNull(notice.title)
        assertEquals(ResumeBlockedReason.REMOVED, notice.reason)
    }

    // Found on the emulator: after granting access again the banner still claimed
    // the book was unreachable, in front of a library that had just recovered it.
    @Test
    fun `a resume notice disappears once the book is readable again`() {
        val catalog = Catalog(
            books = listOf(LibraryFixtures.readable("regranted", "Down and Out")),
            lastReadBookId = "regranted",
        )

        val state = buildLibraryUiState(
            catalog,
            IngestionState.Idle,
            query = "",
            resumeBlocked = ResumeBlocked("regranted", ResumeBlockedReason.PERMISSION_LOST),
        )

        assertNull(state.resumeNotice)
    }

    /**
     * The one reason the catalog cannot answer. A file deleted out from under a
     * directly-picked book still queries as available, so launch routes into it
     * and the *open* is what fails. Suppressing the notice on the catalog's word
     * would leave that reader with a library that looks perfectly fine and no
     * explanation of why their book did not open.
     */
    @Test
    fun `an unreadable notice survives a catalog that still calls the book readable`() {
        val catalog = Catalog(
            books = listOf(LibraryFixtures.readable("ghost", "A Deleted Book")),
            lastReadBookId = "ghost",
        )

        val state = buildLibraryUiState(
            catalog,
            IngestionState.Idle,
            query = "",
            resumeBlocked = ResumeBlocked("ghost", ResumeBlockedReason.UNREADABLE),
        )

        val notice = requireNotNull(state.resumeNotice)
        assertEquals("A Deleted Book", notice.title)
        assertEquals(ResumeBlockedReason.UNREADABLE, notice.reason)
    }

    @Test
    fun `an ordinary launch shows no resume notice`() {
        assertNull(buildLibraryUiState(Catalog(), IngestionState.Idle, query = "").resumeNotice)
    }

    /**
     * REQ-104's two counts. The folder holds three books, but only two of them
     * would leave with it: the third was also picked directly and keeps that
     * source. The confirmation names the second number, not the first.
     */
    @Test
    fun `a folder reports what it holds and what removing it would cost (REQ-104)`() {
        val catalog = Catalog(
            folders = listOf(BookFolder(id = "tree://novels", treeUri = "tree://novels", displayName = "Novels")),
            books = listOf(
                LibraryFixtures.inFolder("a", "Ficciones", "tree://novels"),
                LibraryFixtures.inFolder("b", "Rayuela", "tree://novels"),
                LibraryFixtures.inFolder("c", "Dubliners", "tree://novels", alsoPickedDirectly = true),
            ),
        )

        val folder = buildLibraryUiState(catalog, IngestionState.Idle, query = "").folders.single()

        assertEquals("Novels", folder.displayName)
        assertEquals(3, folder.bookCount)
        assertEquals(2, folder.removedBookCount)
        assertEquals(FolderStatus.AVAILABLE, folder.status)
    }

    /** A folder whose books all live somewhere else too costs nothing to remove. */
    @Test
    fun `a folder that provides no book of its own would remove none`() {
        val catalog = Catalog(
            folders = listOf(BookFolder(id = "tree://copy", treeUri = "tree://copy", displayName = "Copy")),
            books = listOf(LibraryFixtures.inFolder("a", "Ficciones", "tree://copy", alsoPickedDirectly = true)),
        )

        val folder = buildLibraryUiState(catalog, IngestionState.Idle, query = "").folders.single()

        assertEquals(1, folder.bookCount)
        assertEquals(0, folder.removedBookCount)
    }

    @Test
    fun `a folder that is no longer reachable keeps its status (REQ-104)`() {
        val catalog = Catalog(
            folders = listOf(
                BookFolder("tree://gone", "tree://gone", "Moved", status = FolderStatus.MISSING),
                BookFolder("tree://revoked", "tree://revoked", "Revoked", status = FolderStatus.PERMISSION_LOST),
            ),
        )

        val statuses = buildLibraryUiState(catalog, IngestionState.Idle, query = "").folders.map { it.status }

        assertEquals(listOf(FolderStatus.MISSING, FolderStatus.PERMISSION_LOST), statuses)
    }

    @Test
    fun `the undo offer names the book it would bring back (REQ-105)`() {
        val state = buildLibraryUiState(
            Catalog(books = listOf(LibraryFixtures.readable("a", "Ficciones"))),
            IngestionState.Idle,
            query = "",
            undoableRemoval = RemovedBook("gone", "Rayuela"),
        )

        assertEquals("Rayuela", state.undoNotice?.title)
        assertEquals("gone", state.undoNotice?.bookId)
    }

    @Test
    fun `no removal on offer means no undo banner`() {
        assertNull(buildLibraryUiState(Catalog(), IngestionState.Idle, query = "").undoNotice)
    }

    // --- REQ-203, the three orders -------------------------------------------

    /**
     * The default an updating reader gets without touching anything, and the
     * requirement's own acceptance: reading two words of a book moves it up.
     */
    @Test
    fun `recently read is the default order and puts the last book read on top (REQ-203)`() {
        val catalog = orderedCatalog()

        val state = buildLibraryUiState(catalog, IngestionState.Idle, query = "")

        assertEquals(LibraryOrder.RECENTLY_READ, state.order)
        assertEquals(listOf("Beowulf", "Anna Karenina", "Candide", "Dubliners"), state.books.map { it.title })
    }

    /** Reading two words of the book at the bottom is what moves it to the top. */
    @Test
    fun `reading a book moves it above one read earlier (REQ-203)`() {
        val before = orderedCatalog()
        assertEquals("Beowulf", buildLibraryUiState(before, IngestionState.Idle, "").books.first().title)

        // What the position writer does on the second word: it stamps `now`.
        val after = before.copy(
            readingStates = before.readingStates + ("anna" to ReadingState(updatedAtEpochMs = 5_000)),
        )

        assertEquals("Anna Karenina", buildLibraryUiState(after, IngestionState.Idle, "").books.first().title)
    }

    /**
     * The issue's failure behaviour. Candide and Dubliners were never opened, so
     * they come after both books that were, and they are alphabetical between
     * themselves rather than in whatever order the catalog happens to hold them.
     */
    @Test
    fun `books never read sort after read ones, alphabetically (REQ-203)`() {
        val books = buildLibraryUiState(orderedCatalog(), IngestionState.Idle, "").books.map { it.title }

        assertEquals(listOf("Candide", "Dubliners"), books.takeLast(2))
    }

    /**
     * A stored position whose timestamp is the `0` the field defaults to is a
     * book this catalog cannot say was ever read — a v1 document, or a write
     * that never happened — and it must not jump the queue over one that was.
     */
    @Test
    fun `a stored position with no timestamp does not count as recently read`() {
        val catalog = orderedCatalog().let {
            it.copy(readingStates = it.readingStates + ("candide" to ReadingState(progressFraction = 0.4f)))
        }

        val books = buildLibraryUiState(catalog, IngestionState.Idle, "").books.map { it.title }

        assertEquals(listOf("Beowulf", "Anna Karenina", "Candide", "Dubliners"), books)
    }

    @Test
    fun `recently added orders by when the book entered the catalog (REQ-203)`() {
        val catalog = orderedCatalog(LibraryOrder.RECENTLY_ADDED)

        val state = buildLibraryUiState(catalog, IngestionState.Idle, query = "")

        assertEquals(LibraryOrder.RECENTLY_ADDED, state.order)
        assertEquals(listOf("Dubliners", "Candide", "Anna Karenina", "Beowulf"), state.books.map { it.title })
    }

    /** A book added before the catalog kept the timestamp carries `0` and sorts last. */
    @Test
    fun `a book with no added timestamp sorts last under recently added`() {
        val catalog = orderedCatalog(LibraryOrder.RECENTLY_ADDED).let {
            it.copy(books = it.books + LibraryFixtures.readable("old", "A Very Old Import"))
        }

        val books = buildLibraryUiState(catalog, IngestionState.Idle, "").books.map { it.title }

        assertEquals("A Very Old Import", books.last())
    }

    /** Choosing title gives back exactly the list v1 shipped. */
    @Test
    fun `title order is the alphabet, whatever the timestamps say (REQ-203)`() {
        val catalog = orderedCatalog(LibraryOrder.TITLE)

        val state = buildLibraryUiState(catalog, IngestionState.Idle, query = "")

        assertEquals(LibraryOrder.TITLE, state.order)
        assertEquals(listOf("Anna Karenina", "Beowulf", "Candide", "Dubliners"), state.books.map { it.title })
    }

    /** Two books read in the same millisecond are alphabetical, not arbitrary. */
    @Test
    fun `books read at the same instant fall back to the alphabet`() {
        val catalog = Catalog(
            settings = ReaderSettings(libraryOrder = LibraryOrder.RECENTLY_READ),
            books = listOf(
                LibraryFixtures.readable("z", "Zeno's Conscience"),
                LibraryFixtures.readable("a", "Amerika"),
            ),
            readingStates = mapOf(
                "z" to ReadingState(updatedAtEpochMs = 9_000),
                "a" to ReadingState(updatedAtEpochMs = 9_000),
            ),
        )

        val books = buildLibraryUiState(catalog, IngestionState.Idle, "").books.map { it.title }

        assertEquals(listOf("Amerika", "Zeno's Conscience"), books)
    }

    /** The order applies to what search left, not only to the whole library. */
    @Test
    fun `a filtered list keeps the chosen order`() {
        val catalog = orderedCatalog().copy(
            settings = ReaderSettings(libraryOrder = LibraryOrder.RECENTLY_ADDED),
        )

        val books = buildLibraryUiState(catalog, IngestionState.Idle, query = "n").books.map { it.title }

        // "Dubliners", "Candide" and "Anna Karenina" all contain an n.
        assertEquals(listOf("Dubliners", "Candide", "Anna Karenina"), books)
    }

    /**
     * Four books that disagree about every order: alphabetically A, B, C, D;
     * by reading, only B then A; by addition, exactly backwards.
     */
    private fun orderedCatalog(order: LibraryOrder = LibraryOrder.RECENTLY_READ) = Catalog(
        settings = ReaderSettings(libraryOrder = order),
        books = listOf(
            LibraryFixtures.readable("anna", "Anna Karenina", addedAtEpochMs = 2_000),
            LibraryFixtures.readable("beowulf", "Beowulf", addedAtEpochMs = 1_000),
            LibraryFixtures.readable("candide", "Candide", addedAtEpochMs = 3_000),
            LibraryFixtures.readable("dubliners", "Dubliners", addedAtEpochMs = 4_000),
        ),
        readingStates = mapOf(
            "anna" to ReadingState(updatedAtEpochMs = 1_500),
            "beowulf" to ReadingState(updatedAtEpochMs = 2_500),
        ),
    )
}
