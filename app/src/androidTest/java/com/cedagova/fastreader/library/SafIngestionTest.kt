package com.cedagova.fastreader.library

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.saf.SafDocumentGateway
import com.cedagova.fastreader.library.store.CoverStore
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Storage Access Framework: a real folder pick through the
 * system document picker, a real recursive listing through the external-storage
 * documents provider, and a real permission revocation.
 *
 * This is the boundary the JVM tests deliberately fake, so it is the one thing
 * that genuinely needs a device.
 */
@RunWith(AndroidJUnit4::class)
class SafIngestionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private lateinit var gateway: SafDocumentGateway
    private lateinit var covers: CoverStore

    @Before
    fun setUp() {
        gateway = SafDocumentGateway(context)
        covers = CoverStore(File(context.cacheDir, "saf-test-covers"))
        releaseEveryGrant()
        deleteFixtures()
        writeFixtures()
        PickerHostActivity.reset()
    }

    @After
    fun tearDown() {
        releaseEveryGrant()
        deleteFixtures()
        covers.let { File(context.cacheDir, "saf-test-covers").deleteRecursively() }
    }

    @Test
    fun picksAFolderAndIngestsItsBooksThroughRealStorageAccess() {
        val treeUri = pickFixtureFolder()

        assertTrue("the grant must be persistable", gateway.persistReadPermission(treeUri, isTree = true))

        val listing = gateway.listEpubs(treeUri)
        assertTrue("expected a listing, got $listing", listing is FolderListing.Listed)
        val documents = (listing as FolderListing.Listed).documents
        assertEquals(
            "recursive discovery must find every .epub, including nested ones, and nothing else",
            setOf(
                "english.epub",
                "spanish.epub",
                "no-cover.epub",
                "locked.epub",
                "broken.epub",
                "nested.epub",
                "english-copy.epub",
            ),
            documents.map { it.displayName }.toSet(),
        )
        assertTrue("the provider must report real sizes", documents.all { it.sizeBytes > 0 })

        val ingestor = CatalogIngestor(gateway, covers)
        val catalog = ingestor.addFolder(Catalog(), treeUri, "FastReaderSmoke").catalog

        // Seven files, six books: the copy in the subfolder is the same book (AD-2).
        assertEquals(6, catalog.books.size)
        val english = catalog.bookFor("english.epub")
        assertEquals("The Quiet Machine", english.title)
        assertEquals("Ada Fielding", english.author)
        assertTrue(english.hasCover)
        assertEquals(BookStatus.READABLE, english.status)
        assertNotNull("the cover must be cached for the library screen", covers.read(english.id))
        assertEquals(
            "a byte-identical copy must be the same entry, reachable from both files",
            setOf("english.epub", "english-copy.epub"),
            english.sources.map { it.displayName }.toSet(),
        )

        assertEquals("¿Quién teme a la máquina?", catalog.bookFor("spanish.epub").title)
        assertEquals(false, catalog.bookFor("no-cover.epub").hasCover)
        assertEquals(BookStatus.DRM_PROTECTED, catalog.bookFor("locked.epub").status)
        assertEquals("DRM_PROTECTED", catalog.bookFor("locked.epub").rejectReason)
        assertEquals(BookStatus.CORRUPT, catalog.bookFor("broken.epub").status)
        assertEquals("CORRUPT_ARCHIVE", catalog.bookFor("broken.epub").rejectReason)
        assertEquals("Nested Book", catalog.bookFor("nested.epub").title)

        // Reading a book must go through the real content resolver.
        val bytes = gateway.open(english.readableSource!!.uri).use { it.readBytes() }
        assertTrue(bytes.size > 100)

        // Revoking the grant is what the library reports as permission-lost.
        gateway.releaseReadPermission(treeUri, isTree = true)
        assertTrue(gateway.listEpubs(treeUri) is FolderListing.PermissionLost)
        val afterRevoke = ingestor.rescan(catalog).catalog
        assertTrue(afterRevoke.books.all { it.status == BookStatus.PERMISSION_LOST })
        assertEquals(FolderStatus.PERMISSION_LOST, afterRevoke.folders.single().status)
    }

    private fun Catalog.bookFor(displayName: String): Book =
        books.first { book -> book.sources.any { it.displayName == displayName } }

    /** Drives the real system folder picker and returns the granted tree URI. */
    private fun pickFixtureFolder(): String {
        PickerHostActivity.initialUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:$FIXTURE_RELATIVE_PATH",
        )
        context.startActivity(
            Intent(context, PickerHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        val selected = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), UI_TIMEOUT_MS)
        assertNotNull("the folder picker did not offer a select action:\n${device.currentPackageName}", selected)
        selected.click()

        val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), UI_TIMEOUT_MS)
        allow?.click()

        val result = PickerHostActivity.awaitResult(30)
        assertNotNull("the picker returned no result", result)
        assertEquals(Activity.RESULT_OK, result!!.resultCode)
        return requireNotNull(result.treeUri) { "the picker returned no tree" }.toString()
    }

    private fun writeFixtures() {
        write("english.epub", EpubFixtures.validEpub(), FIXTURE_RELATIVE_PATH)
        write("spanish.epub", EpubFixtures.spanishEpub(), FIXTURE_RELATIVE_PATH)
        write("no-cover.epub", EpubFixtures.validEpub(withCover = false, identifier = "urn:uuid:nc"), FIXTURE_RELATIVE_PATH)
        write("locked.epub", EpubFixtures.drmProtectedEpub(), FIXTURE_RELATIVE_PATH)
        write("broken.epub", EpubFixtures.notAZip(), FIXTURE_RELATIVE_PATH)
        write("notabook.txt", "ignore me".toByteArray(), FIXTURE_RELATIVE_PATH)
        write(
            "nested.epub",
            EpubFixtures.validEpub(title = "Nested Book", identifier = "urn:uuid:nested"),
            "$FIXTURE_RELATIVE_PATH/inner",
        )
        // Byte-identical to english.epub: the catalog must fold the two into one entry.
        write("english-copy.epub", EpubFixtures.validEpub(), "$FIXTURE_RELATIVE_PATH/inner")
    }

    private fun write(name: String, bytes: ByteArray, relativePath: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".epub")) EPUB_MIME else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = requireNotNull(context.contentResolver.insert(FILES_COLLECTION, values)) {
            "could not create fixture $relativePath/$name"
        }
        context.contentResolver.openOutputStream(uri).use { output ->
            requireNotNull(output).write(bytes)
        }
    }

    private fun deleteFixtures() {
        try {
            context.contentResolver.delete(
                FILES_COLLECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$FIXTURE_RELATIVE_PATH%"),
            )
        } catch (_: Exception) {
            // Best effort: a leftover fixture folder does not invalidate the run.
        }
    }

    private fun releaseEveryGrant() {
        context.contentResolver.persistedUriPermissions.forEach { permission ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    permission.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Already gone.
            }
        }
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val FIXTURE_RELATIVE_PATH = "Documents/FastReaderSmoke"
        const val EPUB_MIME = "application/epub+zip"
        const val UI_TIMEOUT_MS = 15_000L
        val FILES_COLLECTION = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
