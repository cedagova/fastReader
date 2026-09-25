package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.settings.LibraryOrder
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The split types share one writer (#204): writes from all three, racing on real
 * threads, each land in `catalog.json` and none undoes another.
 */
class CatalogDocumentTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `settings, positions and front-matter writes racing on real threads all land`() = runBlocking {
        val file = File(File(temporaryFolder.root, "catalog"), "catalog.json")
        val gateway = FakeDocumentGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = DeviceLibrary(
                store = FileCatalogStore(file),
                ingestor = CatalogIngestor(gateway, CoverStore(File(temporaryFolder.root, "covers"))),
                gateway = gateway,
                scope = scope,
                ioDispatcher = Dispatchers.IO,
            )
            val writes = (0 until WRITERS).flatMap { i ->
                listOf(
                    scope.async {
                        library.positions.update("book-$i", ReadingState(bookDigest = "book-$i", tokenIndex = i))
                    },
                    scope.async { library.positions.markFrontMatterOffered("offered-$i") },
                    scope.async {
                        val change = if (i % 2 == 0) DARK else BY_TITLE
                        library.settingsStore.update(change)
                    },
                )
            }
            writes.awaitAll()

            val stored = (FileCatalogStore(file).load() as CatalogLoad.Loaded).catalog
            val expected = (0 until WRITERS).associateBy({ "book-$it" }, { it })
            assertEquals(expected, stored.readingStates.mapValues { it.value.tokenIndex })
            assertEquals((0 until WRITERS).map { "offered-$it" }.toSet(), stored.frontMatterOfferedBookIds)
            assertEquals(ThemeChoice.DARK, stored.settings.theme)
            assertEquals(LibraryOrder.TITLE, stored.settings.libraryOrder)
            assertEquals("what is published is what is stored", stored, library.repository.catalog.value)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val WRITERS = 40
        val DARK: (ReaderSettings) -> ReaderSettings = { it.copy(theme = ThemeChoice.DARK) }
        val BY_TITLE: (ReaderSettings) -> ReaderSettings = { it.copy(libraryOrder = LibraryOrder.TITLE) }
    }
}
