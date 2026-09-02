package com.cedagova.fastreader.library.store

import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.BookContentStatus
import com.cedagova.fastreader.library.BookFolder
import com.cedagova.fastreader.library.BookSource
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.SourceOrigin
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.PivotColor
import com.cedagova.fastreader.settings.ReaderSettings
import com.cedagova.fastreader.settings.ThemeChoice
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTiming
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val file: File by lazy { File(File(temporaryFolder.root, "catalog"), "catalog.json") }

    private fun sampleCatalog() = Catalog(
        books = listOf(
            Book(
                id = "sha256:abc",
                title = "¿Quién teme a la máquina?",
                author = "José Ramírez Ñuño",
                language = "es",
                hasCover = true,
                contentStatus = BookContentStatus.READABLE,
                sources = listOf(
                    BookSource(
                        uri = "content://tree/books/one.epub",
                        origin = SourceOrigin.FOLDER,
                        displayName = "one.epub",
                        folderId = "content://tree/books",
                        sizeBytes = 1234,
                        lastModifiedEpochMs = 555,
                    ),
                ),
                addedAtEpochMs = 1,
                lastSeenEpochMs = 2,
            ),
        ),
        folders = listOf(BookFolder(id = "content://tree/books", treeUri = "content://tree/books", displayName = "Books")),
        readingStates = mapOf(
            "sha256:abc" to ReadingState(
                bookDigest = "sha256:abc",
                tokenIndex = 77,
                progressFraction = 0.5f,
                wpm = 400,
                updatedAtEpochMs = 9,
            ),
        ),
        removedBookIds = setOf("sha256:removed"),
        lastReadBookId = "sha256:abc",
    )

    @Test
    fun `round trips the catalog including accented text`() {
        val store = FileCatalogStore(file)
        val catalog = sampleCatalog()

        store.save(catalog)
        val loaded = store.load() as CatalogLoad.Loaded

        assertEquals(catalog, loaded.catalog)
        assertNull(loaded.migratedFrom)
        assertNull(loaded.recoveredFrom)
    }

    @Test
    fun `stamps the current schema version and leaves no temporary file behind`() {
        val store = FileCatalogStore(file)

        store.save(Catalog(schemaVersion = 0))

        assertTrue(file.readText().contains("\"schemaVersion\":${CatalogSchema.CURRENT_VERSION}"))
        assertEquals(listOf("catalog.json"), file.parentFile!!.list()!!.sorted())
    }

    @Test
    fun `an empty or absent catalog loads as an empty library`() {
        val store = FileCatalogStore(file)

        assertEquals(Catalog(), (store.load() as CatalogLoad.Loaded).catalog)

        file.parentFile!!.mkdirs()
        file.writeText("")
        assertEquals(Catalog(), (store.load() as CatalogLoad.Loaded).catalog)
    }

    @Test
    fun `a damaged catalog is set aside rather than deleted`() {
        val store = FileCatalogStore(file, clock = { 4242 })
        file.parentFile!!.mkdirs()
        file.writeText("{ this is not json")

        val loaded = store.load() as CatalogLoad.Loaded

        assertEquals(Catalog(), loaded.catalog)
        assertEquals("catalog.json.damaged-4242", loaded.recoveredFrom)
        val backup = File(file.parentFile, "catalog.json.damaged-4242")
        assertTrue("the damaged document must be kept", backup.isFile)
        assertEquals("{ this is not json", backup.readText())
    }

    @Test
    fun `a catalog from a newer schema is refused instead of overwritten`() {
        val store = FileCatalogStore(file)
        file.parentFile!!.mkdirs()
        file.writeText("""{"schemaVersion":99,"books":[],"folders":[],"readingStates":{}}""")

        val load = store.load()

        assertTrue(load is CatalogLoad.Blocked)
        assertTrue((load as CatalogLoad.Blocked).message.contains("newer version"))
        assertTrue(file.readText().contains("\"schemaVersion\":99"))
    }

    @Test
    fun `unknown fields from a compatible build do not discard the library`() {
        val store = FileCatalogStore(file)
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"schemaVersion":1,"books":[],"folders":[],"readingStates":{},"somethingNew":true}""",
        )

        val loaded = store.load() as CatalogLoad.Loaded

        assertEquals(Catalog(), loaded.catalog)
    }

    // AD-3: the forward-migration chain itself, exercised beyond the current version.
    @Test
    fun `an older document is migrated forward step by step`() {
        val currentText = CatalogCodec().encode(sampleCatalog())
        val addTitlePrefix = CatalogMigration { document ->
            val books = document.getValue("books").jsonArray.map { element ->
                val book = element.jsonObject
                JsonObject(book + ("title" to JsonPrimitive("v3: " + book.getValue("title").jsonPrimitive.content)))
            }
            JsonObject(document + ("books" to kotlinx.serialization.json.JsonArray(books)))
        }
        val next = CatalogSchema.CURRENT_VERSION + 1
        val codec = CatalogCodec(
            currentVersion = next,
            migrations = CatalogSchema.MIGRATIONS + (CatalogSchema.CURRENT_VERSION to addTitlePrefix),
        )

        val decoded = codec.decode(currentText) as CatalogDecoding.Decoded

        assertEquals(CatalogSchema.CURRENT_VERSION, decoded.migratedFrom)
        assertEquals(next, decoded.catalog.schemaVersion)
        assertEquals("v3: ¿Quién teme a la máquina?", decoded.catalog.books.single().title)
        assertEquals(77, decoded.catalog.readingStates.getValue("sha256:abc").tokenIndex)
    }

    /**
     * The real increment 001 → 002 step (AD-3). A version 1 document addressed a
     * position by spine item and word; nothing that could write one ever shipped,
     * so the migration must carry identity, progress, and the timestamp forward
     * and start the position at the beginning of the book rather than inventing a
     * token index for numbers that never described one.
     */
    @Test
    fun `a version 1 document keeps its library and its progress`() {
        val v1 = """
            {"schemaVersion":1,
             "books":[{"id":"sha256:abc","title":"¿Quién teme a la máquina?","sources":[]}],
             "folders":[],
             "readingStates":{"sha256:abc":{"spineIndex":3,"wordIndex":77,
                                            "progressFraction":0.5,"updatedAtEpochMs":9}},
             "removedBookIds":["sha256:removed"]}
        """.trimIndent()

        val decoded = CatalogCodec().decode(v1) as CatalogDecoding.Decoded
        val state = decoded.catalog.readingStates.getValue("sha256:abc")

        assertEquals(1, decoded.migratedFrom)
        assertEquals(CatalogSchema.CURRENT_VERSION, decoded.catalog.schemaVersion)
        assertEquals("¿Quién teme a la máquina?", decoded.catalog.books.single().title)
        assertEquals(setOf("sha256:removed"), decoded.catalog.removedBookIds)
        // Identity comes from the key, which is the content digest (AD-2).
        assertEquals("sha256:abc", state.bookDigest)
        assertEquals(0, state.tokenIndex)
        assertEquals(1, state.pipelineVersion)
        assertEquals(0.5f, state.progressFraction, 0f)
        assertEquals(9L, state.updatedAtEpochMs)
        assertEquals(RsvpTiming.DEFAULT_WPM, state.wpm)
        assertNull(decoded.catalog.lastReadBookId)
    }

    /** A migrated document must be written back at the current version, not the old one. */
    @Test
    fun `a migrated document is stored at the current version`() {
        file.parentFile!!.mkdirs()
        file.writeText("""{"schemaVersion":1,"books":[],"folders":[],"readingStates":{}}""")
        val store = FileCatalogStore(file)

        val loaded = store.load() as CatalogLoad.Loaded
        store.save(loaded.catalog)

        assertEquals(1, loaded.migratedFrom)
        assertTrue(file.readText().contains("\"schemaVersion\":${CatalogSchema.CURRENT_VERSION}"))
        assertNull((store.load() as CatalogLoad.Loaded).migratedFrom)
    }

    @Test
    fun `a document with no migration path is reported instead of being guessed at`() {
        val currentText = CatalogCodec().encode(sampleCatalog())
        // One version past this build, with no way to get there.
        val codec = CatalogCodec(
            currentVersion = CatalogSchema.CURRENT_VERSION + 1,
            migrations = emptyMap(),
        )

        val decoded = codec.decode(currentText)

        assertTrue(decoded is CatalogDecoding.Damaged)
        assertTrue((decoded as CatalogDecoding.Damaged).message.contains("no migration from"))
    }

    @Test
    fun `a document without a schema version is treated as damaged`() {
        assertTrue(CatalogCodec().decode("""{"books":[]}""") is CatalogDecoding.Damaged)
    }

    // --- Settings (LEAF302) -------------------------------------------------
    //
    // AD-3 for the settings schema, and the promise REQ-040's update-in-place
    // acceptance rests on: an absent, unknown or unusable key reads back as its
    // documented default, and none of those cases costs a reader their library.

    /**
     * The real increment 002 → 003 step. A v2 document predates the settings
     * surface, so it must keep every book and position it has and read back with
     * exactly [ReaderSettings.DEFAULTS] — not a blank or a partially filled value.
     */
    @Test
    fun `a version 2 document keeps its library and gains the default settings`() {
        val v2 = """
            {"schemaVersion":2,
             "books":[{"id":"sha256:abc","title":"The Long Signal","sources":[]}],
             "folders":[],
             "readingStates":{"sha256:abc":{"bookDigest":"sha256:abc","tokenIndex":77,
                                            "pipelineVersion":1,"progressFraction":0.5,
                                            "wpm":400,"updatedAtEpochMs":9}},
             "lastReadBookId":"sha256:abc"}
        """.trimIndent()

        val decoded = CatalogCodec().decode(v2) as CatalogDecoding.Decoded

        assertEquals(2, decoded.migratedFrom)
        assertEquals(CatalogSchema.CURRENT_VERSION, decoded.catalog.schemaVersion)
        assertEquals("The Long Signal", decoded.catalog.books.single().title)
        assertEquals(77, decoded.catalog.readingStates.getValue("sha256:abc").tokenIndex)
        assertEquals(400, decoded.catalog.readingStates.getValue("sha256:abc").wpm)
        assertEquals("sha256:abc", decoded.catalog.lastReadBookId)
        assertEquals(ReaderSettings.DEFAULTS, decoded.catalog.settings)
    }

    /** The documented defaults themselves, so a change to one is a change to this test. */
    @Test
    fun `the documented defaults are the ones an absent settings block reads back as`() {
        val withoutSettings = """
            {"schemaVersion":${CatalogSchema.CURRENT_VERSION},"books":[],"folders":[],"readingStates":{}}
        """.trimIndent()

        val settings = (CatalogCodec().decode(withoutSettings) as CatalogDecoding.Decoded).catalog.settings

        assertEquals(ThemeChoice.SYSTEM, settings.theme)
        assertEquals(FontSize.MEDIUM, settings.fontSize)
        assertTrue(settings.highlightEnabled)
        assertFalse(settings.focusAlignmentEnabled)
        assertEquals(PivotColor.ACCENT, settings.pivotColor)
        assertTrue(settings.guideMarksEnabled)
        assertEquals(PauseStrength.NORMAL, settings.pauseStrength)
        assertTrue(settings.isDefault)
    }

    /** A partially written settings block fills the missing keys, it does not reject the rest. */
    @Test
    fun `a settings block with only some keys keeps them and defaults the others`() {
        val partial = """
            {"schemaVersion":${CatalogSchema.CURRENT_VERSION},"books":[],"folders":[],"readingStates":{},
             "settings":{"theme":"DARK","pauseStrength":"OFF"}}
        """.trimIndent()

        val settings = (CatalogCodec().decode(partial) as CatalogDecoding.Decoded).catalog.settings

        assertEquals(ThemeChoice.DARK, settings.theme)
        assertEquals(PauseStrength.OFF, settings.pauseStrength)
        assertEquals(FontSize.MEDIUM, settings.fontSize)
        assertEquals(PivotColor.ACCENT, settings.pivotColor)
        assertTrue(settings.highlightEnabled)
        assertFalse(settings.focusAlignmentEnabled)
    }

    /**
     * The case that would otherwise be expensive: a value this build does not
     * recognise — a colour or theme from a build the reader downgraded from — must
     * cost that one preference and nothing else. Before
     * [CatalogCodec.defaultJson] coerced input values it threw, and this codec's
     * only answer to a throw is `Damaged`, which sets the whole library aside.
     */
    @Test
    fun `an unrecognised setting falls back to its default without losing the library`() {
        val fromTheFuture = """
            {"schemaVersion":${CatalogSchema.CURRENT_VERSION},
             "books":[{"id":"sha256:abc","title":"The Long Signal","sources":[]}],
             "folders":[],"readingStates":{},
             "settings":{"theme":"SEPIA","pivotColor":"CHARTREUSE","fontSize":"LARGE"}}
        """.trimIndent()

        val decoded = CatalogCodec().decode(fromTheFuture) as CatalogDecoding.Decoded

        assertEquals("The Long Signal", decoded.catalog.books.single().title)
        assertEquals(ThemeChoice.SYSTEM, decoded.catalog.settings.theme)
        assertEquals(PivotColor.ACCENT, decoded.catalog.settings.pivotColor)
        // The keys it *could* read are still honoured.
        assertEquals(FontSize.LARGE, decoded.catalog.settings.fontSize)
    }

    /**
     * The real increment 003 → #32 step, over a document a released build wrote.
     *
     * `pivotEnabled` did two things; it becomes the highlight, and the alignment
     * it also carried is turned off — the owner decision this migration exists to
     * apply. Everything a reader would notice losing has to survive it untouched:
     * both books, both reading positions with their pipeline versions and speeds,
     * the last-read book, and every setting the split does not concern.
     */
    @Test
    fun `a version 3 document keeps its library and splits the pivot cue`() {
        val v3 = """
            {"schemaVersion":3,
             "books":[{"id":"sha256:abc","title":"The Long Signal","sources":[]},
                      {"id":"sha256:def","title":"El hilo de plata","sources":[]}],
             "folders":[],
             "readingStates":{"sha256:abc":{"bookDigest":"sha256:abc","tokenIndex":77,
                                            "pipelineVersion":1,"progressFraction":0.5,
                                            "wpm":400,"updatedAtEpochMs":9},
                              "sha256:def":{"bookDigest":"sha256:def","tokenIndex":1201,
                                            "pipelineVersion":1,"progressFraction":0.25,
                                            "wpm":320,"updatedAtEpochMs":11}},
             "lastReadBookId":"sha256:def",
             "settings":{"theme":"DARK","fontSize":"LARGE","pivotEnabled":true,
                         "pivotColor":"CRIMSON","guideMarksEnabled":true,
                         "pauseStrength":"STRONG"}}
        """.trimIndent()

        val decoded = CatalogCodec().decode(v3) as CatalogDecoding.Decoded

        assertEquals(3, decoded.migratedFrom)
        assertEquals(CatalogSchema.CURRENT_VERSION, decoded.catalog.schemaVersion)

        // The library, byte for byte what it was.
        assertEquals(
            listOf("The Long Signal", "El hilo de plata"),
            decoded.catalog.books.map { it.title },
        )
        assertEquals("sha256:def", decoded.catalog.lastReadBookId)
        val first = decoded.catalog.readingStates.getValue("sha256:abc")
        val second = decoded.catalog.readingStates.getValue("sha256:def")
        assertEquals(77, first.tokenIndex)
        assertEquals(400, first.wpm)
        assertEquals(0.5f, first.progressFraction, 0f)
        assertEquals(1201, second.tokenIndex)
        assertEquals(320, second.wpm)
        assertEquals(0.25f, second.progressFraction, 0f)

        // The cue split, and nothing else about the settings.
        val settings = decoded.catalog.settings
        assertTrue(settings.highlightEnabled)
        assertFalse(settings.focusAlignmentEnabled)
        assertEquals(ThemeChoice.DARK, settings.theme)
        assertEquals(FontSize.LARGE, settings.fontSize)
        assertEquals(PivotColor.CRIMSON, settings.pivotColor)
        assertTrue(settings.guideMarksEnabled)
        assertEquals(PauseStrength.STRONG, settings.pauseStrength)
    }

    /** A reader who had the cue off keeps a plain word: the value is carried, not forced on. */
    @Test
    fun `a version 3 document with the pivot cue off migrates to the highlight off`() {
        val v3 = """
            {"schemaVersion":3,"books":[],"folders":[],"readingStates":{},
             "settings":{"pivotEnabled":false,"guideMarksEnabled":false}}
        """.trimIndent()

        val settings = (CatalogCodec().decode(v3) as CatalogDecoding.Decoded).catalog.settings

        assertFalse(settings.highlightEnabled)
        assertFalse(settings.focusAlignmentEnabled)
        assertFalse(settings.guideMarksEnabled)
    }

    /**
     * The downgrade guard is unchanged by the bump: a document from a newer schema
     * is refused rather than rewritten, so an older build cannot cost a reader
     * their library.
     */
    @Test
    fun `a document from a newer schema than this build is still refused`() {
        val fromTheFuture = """
            {"schemaVersion":${CatalogSchema.CURRENT_VERSION + 1},
             "books":[{"id":"sha256:abc","title":"The Long Signal","sources":[]}],
             "folders":[],"readingStates":{}}
        """.trimIndent()

        val decoding = CatalogCodec().decode(fromTheFuture)

        assertTrue(decoding is CatalogDecoding.Newer)
        assertEquals(CatalogSchema.CURRENT_VERSION + 1, (decoding as CatalogDecoding.Newer).documentVersion)
        assertEquals(CatalogSchema.CURRENT_VERSION, decoding.supportedVersion)
    }

    @Test
    fun `changed settings round trip through the store`() {
        val changed = ReaderSettings(
            theme = ThemeChoice.DARK,
            fontSize = FontSize.EXTRA_LARGE,
            highlightEnabled = false,
            focusAlignmentEnabled = true,
            pivotColor = PivotColor.VIOLET,
            guideMarksEnabled = false,
            pauseStrength = PauseStrength.STRONG,
        )
        val store = FileCatalogStore(file)

        store.save(sampleCatalog().copy(settings = changed))

        assertEquals(changed, (store.load() as CatalogLoad.Loaded).catalog.settings)
    }

    @Test
    fun `covers are cached per book and removable`() {
        val covers = CoverStore(File(temporaryFolder.root, "covers"))

        assertNull(covers.read("sha256:abc"))
        assertTrue(covers.write("sha256:abc", byteArrayOf(1, 2, 3)))
        assertNotNull(covers.read("sha256:abc"))
        assertEquals(3, covers.read("sha256:abc")!!.length())

        covers.delete("sha256:abc")
        assertNull(covers.read("sha256:abc"))
    }
}
