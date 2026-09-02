package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.library.BookContentStatus
import com.cedagova.fastreader.library.BookStatus
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.ScanTrigger
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin
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

    @Test
    fun `an ordinary launch shows no resume notice`() {
        assertNull(buildLibraryUiState(Catalog(), IngestionState.Idle, query = "").resumeNotice)
    }
}
