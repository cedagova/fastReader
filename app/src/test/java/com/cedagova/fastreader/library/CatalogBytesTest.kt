package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.reader.engine.epub.EpubFixtures
import com.cedagova.reader.engine.timing.PauseStrength
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `catalog.json` is byte-for-byte what it was before the store's concerns were
 * split (#204): no format change, no migration.
 *
 * [GOLDEN] was written by the pre-split `LibraryRepository` running exactly
 * [writeEverything] — every kind of write the catalog takes: a folder and a
 * picked file, a settings change, a coalesced and a direct position, the
 * front-matter record, a removal. The split types must produce the same bytes
 * from the same writes, and must write a stored document back unchanged.
 *
 * The migration tests in `store/CatalogStoreTest` are the other half of the
 * proof and are deliberately not touched by the split.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogBytesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gateway = FakeDocumentGateway()
    private val file: File by lazy { File(File(temporaryFolder.root, "catalog"), "catalog.json") }

    private fun epub(title: String) = EpubFixtures.validEpub(
        title = title,
        identifier = "urn:uuid:$title",
        bodyText = "A book called $title.",
    )

    private fun library(scope: CoroutineScope): DeviceLibrary {
        val covers = CoverStore(File(temporaryFolder.root, "covers"))
        return DeviceLibrary(
            store = FileCatalogStore(file),
            ingestor = CatalogIngestor(gateway, covers, clock = { NOW }),
            gateway = gateway,
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[TestCoroutineScheduler]),
            clock = { NOW },
        )
    }

    private suspend fun writeEverything(library: DeviceLibrary) {
        gateway.putIntoFolder("tree://shelf", "doc://shelf/one", epub("One"), "one.epub")
        gateway.putIntoFolder("tree://shelf", "doc://shelf/two", epub("Two"), "two.epub")
        gateway.putDocument("doc://three", epub("Three"), "three.epub")
        library.repository.addFolder("tree://shelf", "Shelf")
        library.repository.addPickedBooks(listOf("doc://three"))
        val ids = library.repository.catalog.value.books.associate { it.title to it.id }
        val (one, two, three) = listOf("One", "Two", "Three").map(ids::getValue)

        library.settingsStore.update {
            it.copy(
                theme = ThemeChoice.DARK,
                fontSize = FontSize.LARGE,
                pauseStrength = PauseStrength.STRONG,
                libraryOrder = LibraryOrder.TITLE,
            )
        }
        library.positions.update(one, ReadingState(bookDigest = one, tokenIndex = 42, wpm = 420))
        library.positions.record(three, ReadingState(bookDigest = three, tokenIndex = 7, progressFraction = 0.25f))
        library.positions.flush().join()
        library.positions.markFrontMatterOffered(two)
        library.repository.removeBook(two)
    }

    @Test
    fun `the split writes the same catalog bytes the single repository wrote`() = runTest {
        writeEverything(library(backgroundScope))

        assertEquals(golden(), file.readText())
    }

    @Test
    fun `a stored catalog is written back byte for byte`() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText(golden())
        val library = library(backgroundScope)

        // A write that changes nothing still rewrites the whole document.
        library.settingsStore.update { it }

        assertEquals(golden(), file.readText())
    }

    private fun golden(): String = javaClass.getResource(GOLDEN)!!.readText()

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val GOLDEN = "/catalog-golden-204.json"
    }
}
