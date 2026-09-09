package com.cedagova.fastreader.external

import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.epub.EpubInspector
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.reader.BookOrigin
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The two halves of REQ-103, and the line between them.
 *
 * What decides which one a reader gets is not the file and not the sender: it is
 * whether the grant can be kept. These tests fix that, because the difference is
 * a privacy promise — a session-only open leaves no grant and no row behind, and
 * a keepable one produces an ordinary library book — and because the emulator can
 * only show one sender's behaviour at a time while this shows both.
 *
 * They also pin the timing that REQ-110 depends on: [ExternalOpenController.accept]
 * reads no bytes at all. That is checked by watching the gateway, not by trusting
 * the shape of the code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalOpenControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gateway = FakeDocumentGateway()
    private val bytes = EpubFixtures.validEpub()
    private val digest = requireNotNull(EpubInspector.inspect(ContentFixtures.source(bytes)).contentDigest)

    /**
     * The ordinary Files-app path: a provider that offers a grant the app may keep.
     * The book becomes a library row, exactly as if it had been picked.
     */
    @Test
    fun `a keepable hand-over is added to the library like a pick`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        val controller = controller()

        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()
        controller.resolveIdentity(SHARED)
        advanceUntilIdle()

        val open = requireNotNull(controller.open.value)
        assertEquals(BookIdentity(digest), open.identity)
        assertTrue(open.resolved)
        assertTrue("the grant must be kept", SHARED in gateway.persistedGrants)
        assertEquals(listOf(digest), repository.catalog.value.books.map { it.id })
    }

    /**
     * The share-sheet path from a sender that grants access for this hand-over
     * only. Nothing about the file may survive it — no grant, no row — and the
     * reading position has to be keyed by the same digest the catalog would use,
     * so adding the book later finds it.
     */
    @Test
    fun `a session-only hand-over keeps neither a grant nor a row`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        gateway.nonPersistableGrants += SHARED
        val controller = controller()

        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()
        controller.resolveIdentity(SHARED)
        advanceUntilIdle()

        val open = requireNotNull(controller.open.value)
        assertEquals(BookOrigin.EXTERNAL_SESSION_ONLY, open.origin)
        assertEquals(BookIdentity(digest), open.identity)
        assertTrue("no grant may be persisted", gateway.persistedGrants.isEmpty())
        assertTrue("no library row may appear", repository.catalog.value.books.isEmpty())
    }

    /**
     * AD-8 and REQ-110 on this path: opening must cost nothing but the metadata
     * the title needs. Everything that reads the file is deferred to
     * [ExternalOpenController.resolveIdentity], which the reader only calls once
     * there is text on screen.
     */
    @Test
    fun `accepting a hand-over reads none of the book`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        val controller = controller()

        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), gateway.opened)
        val open = requireNotNull(controller.open.value)
        assertEquals("quiet", open.title)
        assertNull("identity cannot be known without reading the file", open.identity)
        assertFalse(open.resolved)
    }

    /**
     * The same file, arriving by "Open with" instead of from the library list. The
     * catalog already knows it, so its identity is its catalog id and there is
     * nothing to defer: the position works from the first word and no notice is
     * ever shown.
     */
    @Test
    fun `a book the catalog already has opens as an ordinary known book`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        val controller = controller()
        repository.addPickedBooks(listOf(SHARED))
        gateway.opened.clear()

        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()

        val open = requireNotNull(controller.open.value)
        assertEquals(BookOrigin.EXTERNAL_KEEPABLE, open.origin)
        assertEquals(BookIdentity(digest), open.identity)
        assertTrue("nothing is deferred for a book already in the catalog", open.resolved)
        assertEquals(emptyList<String>(), gateway.opened)
    }

    /**
     * The invariant runs both ways: no row without a grant, and no grant without a
     * row. A document that vanished between the open and the ingest would
     * otherwise leave this app holding a long-lived permission for a file the
     * library does not list.
     */
    @Test
    fun `a grant that produced no library row is given back`() = runTest {
        val controller = controller()

        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()
        controller.resolveIdentity(SHARED)
        advanceUntilIdle()

        assertTrue("the grant must not be kept", gateway.persistedGrants.isEmpty())
        assertEquals(listOf(SHARED), gateway.releasedGrants)
        assertTrue(repository.catalog.value.books.isEmpty())
    }

    @Test
    fun `a book that cannot be hashed simply has no identity`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        gateway.nonPersistableGrants += SHARED
        gateway.revokedGrants += SHARED
        val controller = controller()

        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()
        controller.resolveIdentity(SHARED)
        advanceUntilIdle()

        val open = requireNotNull(controller.open.value)
        assertTrue("the reader must not be left waiting", open.resolved)
        assertNull(open.identity)
    }

    @Test
    fun `dismissing the notice and closing the book are remembered and forgotten`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        val controller = controller()
        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()

        controller.dismissNotice()
        assertTrue(requireNotNull(controller.open.value).noticeDismissed)

        controller.close()
        assertNull("closing an external book leaves nothing behind", controller.open.value)
    }

    /** A second "Open with" while the first book is open replaces it. */
    @Test
    fun `a second hand-over replaces the first`() = runTest {
        gateway.putDocument(SHARED, bytes, "quiet.epub")
        gateway.putDocument(OTHER, EpubFixtures.spanishEpub(), "otra.epub")
        val controller = controller()
        controller.accept(IncomingBook(SHARED))
        advanceUntilIdle()

        controller.accept(IncomingBook(OTHER))
        advanceUntilIdle()

        assertEquals(OTHER, requireNotNull(controller.open.value).uri)
        assertEquals("otra", requireNotNull(controller.open.value).title)
    }

    private lateinit var repository: LibraryRepository

    /**
     * The repository's own scope is the background one, so its position writer's
     * standing coroutine does not keep the test alive; the controller's is the
     * test scope, so `advanceUntilIdle` runs the deferred work this test is about.
     */
    private fun TestScope.controller(): ExternalOpenController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val covers = CoverStore(File(temporaryFolder.root, "covers"))
        repository = LibraryRepository(
            store = FileCatalogStore(File(File(temporaryFolder.root, "catalog"), "catalog.json")),
            ingestor = CatalogIngestor(gateway, covers),
            gateway = gateway,
            covers = covers,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
        )
        return ExternalOpenController(repository, gateway, this, dispatcher)
    }

    private companion object {
        const val SHARED = "content://share/document/quiet.epub"
        const val OTHER = "content://share/document/otra.epub"
    }
}
