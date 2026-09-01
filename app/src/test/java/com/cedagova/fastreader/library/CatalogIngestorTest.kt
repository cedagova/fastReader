package com.cedagova.fastreader.library

import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.store.CoverStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogIngestorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gateway = FakeDocumentGateway()
    private var now = 10_000L

    private val covers: CoverStore by lazy { CoverStore(File(temporaryFolder.root, "covers")) }
    private val ingestor: CatalogIngestor by lazy { CatalogIngestor(gateway, covers, clock = { now }) }

    // REQ-001: pick three EPUBs, get three entries with metadata and cover flags.
    @Test
    fun `adding three picked books yields three entries with metadata and cover flags`() {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet-machine.epub")
        gateway.putDocument("doc://b", EpubFixtures.spanishEpub(withCover = true), "maquina.epub")
        gateway.putDocument("doc://c", EpubFixtures.validEpub(withCover = false, title = "No Cover", identifier = "urn:uuid:3"), "no-cover.epub")

        val outcome = ingestor.addPickedBooks(Catalog(), listOf("doc://a", "doc://b", "doc://c"))

        assertEquals(3, outcome.catalog.books.size)
        assertEquals(3, outcome.added)
        val byTitle = outcome.catalog.books.associateBy { it.title }
        assertEquals("Ada Fielding", byTitle.getValue("The Quiet Machine").author)
        assertEquals("José Ramírez Ñuño", byTitle.getValue("¿Quién teme a la máquina?").author)
        assertTrue(byTitle.getValue("The Quiet Machine").hasCover)
        assertTrue(byTitle.getValue("¿Quién teme a la máquina?").hasCover)
        assertEquals(false, byTitle.getValue("No Cover").hasCover)
        assertTrue(outcome.catalog.books.all { it.status == BookStatus.READABLE })
        // The cover was cached, so the library can render it without re-parsing.
        assertNotNull(covers.read(byTitle.getValue("The Quiet Machine").id))
        assertNull(covers.read(byTitle.getValue("No Cover").id))
    }

    @Test
    fun `a picked book with no metadata title falls back to its file name`() {
        gateway.putDocument("doc://a", EpubFixtures.emptySpineEpub(), "some-book.epub")

        val outcome = ingestor.addPickedBooks(Catalog(), listOf("doc://a"))

        assertEquals("some-book", outcome.catalog.books.single().title)
    }

    // REQ-002: folders are discovered recursively and rescanned.
    @Test
    fun `adding a folder discovers its EPUBs recursively`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        gateway.putIntoFolder(
            "tree://books",
            "tree://books/sub/two.epub",
            EpubFixtures.spanishEpub(),
            "two.epub",
        )

        val outcome = ingestor.addFolder(Catalog(), "tree://books", "Books")

        assertEquals(2, outcome.catalog.books.size)
        assertEquals(1, outcome.catalog.folders.size)
        assertEquals(FolderStatus.AVAILABLE, outcome.catalog.folders.single().status)
        assertTrue(gateway.persistedGrants.contains("tree://books"))
        assertTrue(outcome.catalog.books.all { it.sources.single().origin == SourceOrigin.FOLDER })
    }

    @Test
    fun `a book copied into an added folder appears on the next rescan`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val afterAdd = ingestor.addFolder(Catalog(), "tree://books", "Books").catalog
        assertEquals(1, afterAdd.books.size)

        gateway.putIntoFolder("tree://books", "tree://books/new.epub", EpubFixtures.spanishEpub(), "new.epub")
        val afterRescan = ingestor.rescan(afterAdd)

        assertEquals(2, afterRescan.catalog.books.size)
        assertEquals(1, afterRescan.added)
        assertTrue(afterRescan.catalog.books.any { it.title == "¿Quién teme a la máquina?" })
    }

    // AD-2: one entry for a book reachable twice.
    @Test
    fun `a book reachable directly and through a folder produces one entry`() {
        val bytes = EpubFixtures.validEpub()
        gateway.putDocument("doc://picked", bytes, "quiet.epub")
        gateway.putIntoFolder("tree://books", "tree://books/quiet.epub", bytes, "quiet.epub")

        val afterPick = ingestor.addPickedBooks(Catalog(), listOf("doc://picked")).catalog
        val afterFolder = ingestor.addFolder(afterPick, "tree://books", "Books")

        val book = afterFolder.catalog.books.single()
        assertEquals(2, book.sources.size)
        assertEquals(0, afterFolder.added)
        assertEquals(
            setOf(SourceOrigin.DIRECT_PICK, SourceOrigin.FOLDER),
            book.sources.map { it.origin }.toSet(),
        )
    }

    @Test
    fun `identity survives the same book being moved to a different name`() {
        val bytes = EpubFixtures.validEpub()
        gateway.putDocument("doc://before", bytes, "quiet.epub")
        val first = ingestor.addPickedBooks(Catalog(), listOf("doc://before")).catalog.books.single().id

        gateway.documents.remove("doc://before")
        gateway.putDocument("doc://after", bytes, "renamed.epub")
        val second = ingestor.addPickedBooks(Catalog(), listOf("doc://after")).catalog.books.single().id

        assertEquals(first, second)
    }

    // REQ-004: removal keeps the file and the position.
    @Test
    fun `removing a book keeps the file and restores the position when it is re-added`() {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val added = ingestor.addPickedBooks(Catalog(), listOf("doc://a")).catalog
        val bookId = added.books.single().id
        val seeded = added.copy(
            readingStates = mapOf(bookId to ReadingState(spineIndex = 2, wordIndex = 412, progressFraction = 0.37f)),
        )

        val afterRemove = ingestor.removeBook(seeded, bookId)

        assertTrue(afterRemove.books.isEmpty())
        assertTrue("the file must never be deleted", gateway.documents.containsKey("doc://a"))
        assertEquals(412, afterRemove.readingStates.getValue(bookId).wordIndex)

        val afterReAdd = ingestor.addPickedBooks(afterRemove, listOf("doc://a")).catalog

        assertEquals(bookId, afterReAdd.books.single().id)
        assertEquals(412, afterReAdd.readingStates.getValue(bookId).wordIndex)
        assertEquals(0.37f, afterReAdd.readingStates.getValue(bookId).progressFraction, 0.0001f)
    }

    // REQ-005: missing and restored folders.
    @Test
    fun `a renamed folder marks its books missing and restoring it clears the state`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val added = ingestor.addFolder(Catalog(), "tree://books", "Books").catalog
        val bookId = added.books.single().id
        val seeded = added.copy(readingStates = mapOf(bookId to ReadingState(wordIndex = 99)))

        gateway.missingFolders += "tree://books"
        val whileMissing = ingestor.rescan(seeded).catalog

        assertEquals(BookStatus.MISSING, whileMissing.books.single().status)
        assertEquals(FolderStatus.MISSING, whileMissing.folders.single().status)
        assertEquals(99, whileMissing.readingStates.getValue(bookId).wordIndex)

        gateway.missingFolders -= "tree://books"
        val restored = ingestor.rescan(whileMissing).catalog

        assertEquals(BookStatus.READABLE, restored.books.single().status)
        assertEquals(FolderStatus.AVAILABLE, restored.folders.single().status)
        assertEquals(bookId, restored.books.single().id)
        assertEquals(99, restored.readingStates.getValue(bookId).wordIndex)
    }

    @Test
    fun `a single file that disappears from a folder becomes missing rather than vanishing`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val added = ingestor.addFolder(Catalog(), "tree://books", "Books").catalog

        gateway.documents.remove("tree://books/one.epub")
        gateway.folders.getValue("tree://books").remove("tree://books/one.epub")
        val rescanned = ingestor.rescan(added).catalog

        assertEquals(BookStatus.MISSING, rescanned.books.single().status)
    }

    @Test
    fun `revoking a folder grant marks its books permission lost and re-granting clears it`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val added = ingestor.addFolder(Catalog(), "tree://books", "Books").catalog

        gateway.revokedGrants += "tree://books"
        val revoked = ingestor.rescan(added).catalog

        assertEquals(BookStatus.PERMISSION_LOST, revoked.books.single().status)
        assertEquals(FolderStatus.PERMISSION_LOST, revoked.folders.single().status)

        gateway.revokedGrants -= "tree://books"
        val regranted = ingestor.rescan(revoked).catalog

        assertEquals(BookStatus.READABLE, regranted.books.single().status)
    }

    @Test
    fun `a picked file whose grant is revoked becomes permission lost`() {
        gateway.putDocument("doc://a", EpubFixtures.validEpub(), "quiet.epub")
        val added = ingestor.addPickedBooks(Catalog(), listOf("doc://a")).catalog

        gateway.revokedGrants += "doc://a"
        val revoked = ingestor.rescan(added).catalog

        assertEquals(BookStatus.PERMISSION_LOST, revoked.books.single().status)
    }

    // Rejection states, distinct and persisted.
    @Test
    fun `DRM and corrupt books are rejected with distinct persisted reasons and no crash`() {
        gateway.putDocument("doc://drm", EpubFixtures.drmProtectedEpub(), "locked.epub")
        gateway.putDocument("doc://broken", EpubFixtures.notAZip(), "broken.epub")
        gateway.putDocument("doc://good", EpubFixtures.validEpub(), "quiet.epub")

        val outcome = ingestor.addPickedBooks(Catalog(), listOf("doc://drm", "doc://broken", "doc://good"))

        assertEquals(3, outcome.catalog.books.size)
        assertEquals(2, outcome.rejected)
        val byStatus = outcome.catalog.books.associateBy { it.status }
        assertEquals("DRM_PROTECTED", byStatus.getValue(BookStatus.DRM_PROTECTED).rejectReason)
        assertEquals("CORRUPT_ARCHIVE", byStatus.getValue(BookStatus.CORRUPT).rejectReason)
        assertTrue(byStatus.getValue(BookStatus.DRM_PROTECTED).rejectDetail!!.isNotBlank())
        assertTrue(byStatus.getValue(BookStatus.CORRUPT).rejectDetail!!.isNotBlank())
        assertEquals(BookStatus.READABLE, byStatus.getValue(BookStatus.READABLE).status)
    }

    @Test
    fun `a rejected book that goes missing reports missing but keeps why it was rejected`() {
        gateway.putDocument("doc://drm", EpubFixtures.drmProtectedEpub(), "locked.epub")
        val added = ingestor.addPickedBooks(Catalog(), listOf("doc://drm")).catalog

        gateway.documents.remove("doc://drm")
        val rescanned = ingestor.rescan(added).catalog

        val book = rescanned.books.single()
        assertEquals(BookStatus.MISSING, book.status)
        assertEquals(BookContentStatus.DRM_PROTECTED, book.contentStatus)
        assertEquals("DRM_PROTECTED", book.rejectReason)
    }

    @Test
    fun `an unchanged file is not re-parsed on rescan`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        var inspections = 0
        val counting = CatalogIngestor(
            gateway = gateway,
            covers = covers,
            inspect = { source ->
                inspections++
                com.cedagova.fastreader.epub.EpubInspector.inspect(source)
            },
            clock = { now },
        )
        val added = counting.addFolder(Catalog(), "tree://books", "Books").catalog
        assertEquals(1, inspections)

        counting.rescan(added)

        assertEquals("an unchanged file must not be re-parsed", 1, inspections)
    }

    @Test
    fun `a replaced file is re-parsed and takes over its source`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        val added = ingestor.addFolder(Catalog(), "tree://books", "Books").catalog
        val originalId = added.books.single().id

        gateway.documents.getValue("tree://books/one.epub").apply {
            bytes = EpubFixtures.spanishEpub()
            lastModifiedEpochMs = 2_000
        }
        val rescanned = ingestor.rescan(added).catalog

        assertEquals(1, rescanned.books.size)
        assertEquals("¿Quién teme a la máquina?", rescanned.books.single().title)
        assertTrue(rescanned.books.single().id != originalId)
    }

    @Test
    fun `removing a folder drops only the entries it provided and never the files`() {
        val shared = EpubFixtures.validEpub()
        gateway.putDocument("doc://picked", shared, "quiet.epub")
        gateway.putIntoFolder("tree://books", "tree://books/quiet.epub", shared, "quiet.epub")
        gateway.putIntoFolder("tree://books", "tree://books/other.epub", EpubFixtures.spanishEpub(), "other.epub")
        var catalog = ingestor.addPickedBooks(Catalog(), listOf("doc://picked")).catalog
        catalog = ingestor.addFolder(catalog, "tree://books", "Books").catalog
        assertEquals(2, catalog.books.size)

        val afterRemove = ingestor.removeFolder(catalog, "tree://books")

        assertEquals(1, afterRemove.books.size)
        assertEquals(SourceOrigin.DIRECT_PICK, afterRemove.books.single().sources.single().origin)
        assertTrue(afterRemove.folders.isEmpty())
        assertTrue(gateway.documents.containsKey("tree://books/other.epub"))
        assertTrue(gateway.releasedGrants.contains("tree://books"))
    }

    @Test
    fun `scan progress is reported for the library loading state`() {
        gateway.putIntoFolder("tree://books", "tree://books/one.epub", EpubFixtures.validEpub(), "one.epub")
        gateway.putIntoFolder("tree://books", "tree://books/two.epub", EpubFixtures.spanishEpub(), "two.epub")
        val seen = mutableListOf<Pair<Int, Int>>()

        ingestor.addFolder(Catalog(), "tree://books", "Books") { processed, total, _ ->
            seen += processed to total
        }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }
}
