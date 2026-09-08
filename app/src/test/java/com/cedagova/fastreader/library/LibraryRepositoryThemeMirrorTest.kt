package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.store.CatalogLoad
import com.cedagova.fastreader.library.store.CatalogStore
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.fastreader.settings.ThemeMirror
import java.io.File
import java.io.IOException
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
 * The half of AD-10 the repository owns: the pre-Compose copy of the theme
 * choice never disagrees with the catalog document that is its source of truth.
 *
 * The invariant is stated as an ordering — catalog first, mirror second — so
 * these tests are about *when* the mirror is written rather than about what it
 * stores. A mirror that is merely eventually consistent would pass a round-trip
 * test and still hand the next cold start a stale first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryThemeMirrorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Records the sequence of mirror writes, which is what the ordering rule is about. */
    private class RecordingThemeMirror(
        private var stored: ThemeChoice = ReaderSettings.DEFAULTS.theme,
    ) : ThemeMirror {
        val writes = mutableListOf<ThemeChoice>()

        override fun read(): ThemeChoice = stored

        override fun write(theme: ThemeChoice) {
            writes += theme
            stored = theme
        }
    }

    private val gateway = FakeDocumentGateway()

    private fun repository(
        store: CatalogStore,
        mirror: ThemeMirror,
        scope: CoroutineScope,
    ): LibraryRepository {
        val covers = CoverStore(File(temporaryFolder.root, "covers"))
        return LibraryRepository(
            store = store,
            ingestor = CatalogIngestor(gateway, covers),
            gateway = gateway,
            covers = covers,
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[TestCoroutineScheduler]),
            themeMirror = mirror,
        )
    }

    private fun fileStore() =
        FileCatalogStore(File(File(temporaryFolder.root, "catalog"), "catalog.json"))

    @Test
    fun `choosing a theme mirrors it for the next cold start`() = runTest {
        val mirror = RecordingThemeMirror()
        val repository = repository(fileStore(), mirror, backgroundScope)

        repository.updateSettings { it.copy(theme = ThemeChoice.DARK) }

        assertEquals(ThemeChoice.DARK, mirror.read())
        assertEquals(ThemeChoice.DARK, repository.settings.value.theme)
    }

    @Test
    fun `a settings change that is not the theme still leaves the two agreeing`() = runTest {
        val mirror = RecordingThemeMirror()
        val repository = repository(fileStore(), mirror, backgroundScope)
        repository.updateSettings { it.copy(theme = ThemeChoice.LIGHT) }

        repository.updateSettings { it.copy(highlightEnabled = false) }

        assertEquals(ThemeChoice.LIGHT, mirror.read())
        // The first entry is the load-time re-sync; then every catalog write
        // re-states the theme, so a mirror that lost a write is repaired by the
        // next unrelated change rather than staying stale.
        assertEquals(
            listOf(ThemeChoice.SYSTEM, ThemeChoice.LIGHT, ThemeChoice.LIGHT),
            mirror.writes,
        )
    }

    @Test
    fun `a catalog write that fails leaves the mirror at the value that is still stored`() = runTest {
        val failing = object : CatalogStore {
            override fun load() = CatalogLoad.Loaded(Catalog())
            override fun save(catalog: Catalog): Unit = throw IOException("the disk is full")
        }
        val mirror = RecordingThemeMirror()
        val repository = repository(failing, mirror, backgroundScope)

        repository.updateSettings { it.copy(theme = ThemeChoice.DARK) }

        // The catalog still says SYSTEM, so the first frame must too: a mirror
        // written before the catalog would have promised a dark launch for a
        // preference that was never saved.
        assertEquals(ThemeChoice.SYSTEM, mirror.read())
        // Only the load-time re-sync; the failed write contributed nothing.
        assertEquals(listOf(ThemeChoice.SYSTEM), mirror.writes)
        assertEquals(ThemeChoice.SYSTEM, repository.settings.value.theme)
    }

    @Test
    fun `loading a stored catalog re-syncs a mirror that was left stale`() = runTest {
        val store = fileStore()
        run {
            val seeded = repository(store, RecordingThemeMirror(), backgroundScope)
            seeded.updateSettings { it.copy(theme = ThemeChoice.DARK) }
        }
        // A fresh process whose mirror never received that write: the install
        // predates the mirror, or its write failed.
        val stale = RecordingThemeMirror(stored = ThemeChoice.SYSTEM)
        val repository = repository(store, stale, backgroundScope)

        repository.load()

        assertEquals(ThemeChoice.DARK, stale.read())
    }
}
