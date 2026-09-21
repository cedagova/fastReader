package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationImportStatus
import com.cedagova.reader.library.model.ReaderCoverStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The account store's three promises (AD-20): what goes in comes back out, a
 * document from a newer build is refused *without being rewritten*, and one
 * account's document is never another's.
 */
class AccountLibraryStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val directory: File by lazy { File(temporaryFolder.root, "account-library") }

    private fun sample() = AccountLibraryDocument(
        userId = "user-1",
        cursor = "412",
        books = listOf(
            AccountBook(
                bookId = "book-1",
                title = "Dune",
                author = "Frank Herbert",
                language = "en",
                contentSha256 = "a".repeat(64),
                assetId = "asset-1",
                status = ReaderLibraryStatus.READING,
                coverStatus = ReaderCoverStatus.COVERED,
                lastOpenedAt = "2026-09-14T10:00:00Z",
                revision = 7,
                progressPercent = 41.5,
                progressUpdatedAt = "2026-09-14T10:01:00Z",
            ),
            AccountBook(bookId = "book-2", title = "Gone", removed = true, revision = 3),
        ),
        outbox = listOf(
            AccountOutboxEntry(
                idempotencyKey = "key-1",
                resourceId = "book-2",
                mutationKind = ReaderMutationKind.DELETE,
                baseRevision = 2,
                clientCreatedAt = "2026-09-14T10:02:00Z",
            ),
            AccountOutboxEntry(
                idempotencyKey = "key-2",
                resourceId = "book-1",
                mutationKind = ReaderMutationKind.UPSERT,
                baseRevision = 7,
                payload = buildJsonObject { put("status", JsonPrimitive("finished")) },
            ),
        ),
    )

    @Test
    fun `a saved document comes back exactly as it went in`() {
        val store = FileAccountLibraryStores(directory).forUser("user-1")
        store.save(sample())

        val loaded = store.load()
        assertTrue(loaded is AccountLibraryLoad.Loaded)
        assertEquals(sample(), (loaded as AccountLibraryLoad.Loaded).document)
    }

    @Test
    fun `an account this device has never seen loads as an empty document`() {
        val loaded = FileAccountLibraryStores(directory).forUser("nobody").load()

        assertEquals(AccountLibraryDocument(), (loaded as AccountLibraryLoad.Loaded).document)
        assertNull(loaded.recoveredFrom)
    }

    @Test
    fun `a document from a newer build is refused and left byte-for-byte alone`() {
        val stores = FileAccountLibraryStores(directory)
        stores.forUser("user-1").save(sample())
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val newer = file.readText().replace(
            "\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION}",
            "\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION + 1}",
        )
        file.writeText(newer)

        val load = stores.forUser("user-1").load()

        assertTrue("a newer document must be refused, not adopted", load is AccountLibraryLoad.Blocked)
        assertTrue((load as AccountLibraryLoad.Blocked).message.contains("newer version"))
        assertEquals("the refused document must not be rewritten", newer, file.readText())
    }

    @Test
    fun `a damaged document is set aside rather than deleted`() {
        val stores = FileAccountLibraryStores(directory)
        stores.forUser("user-1").save(sample())
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        file.writeText("{ this is not a document")

        val load = stores.forUser("user-1").load() as AccountLibraryLoad.Loaded

        assertEquals(AccountLibraryDocument(), load.document)
        assertNotNull("the damaged bytes must be kept", load.recoveredFrom)
        assertTrue(File(directory, load.recoveredFrom!!).isFile)
    }

    @Test
    fun `each account gets its own document, and the id is not in the file name`() {
        val stores = FileAccountLibraryStores(directory)
        stores.forUser("user-1").save(sample())
        stores.forUser("user-2").save(AccountLibraryDocument(userId = "user-2", cursor = "9"))

        val one = stores.forUser("user-1").load() as AccountLibraryLoad.Loaded
        val two = stores.forUser("user-2").load() as AccountLibraryLoad.Loaded

        assertEquals("412", one.document.cursor)
        assertEquals("9", two.document.cursor)
        assertEquals(2, directory.listFiles()!!.count { it.name.endsWith(".json") })
        directory.listFiles()!!.forEach { file ->
            assertTrue("the user id must not appear in ${file.name}", !file.name.contains("user-"))
        }
    }

    @Test
    fun `exceptUser names every other account's document and never this one's`() {
        val stores = FileAccountLibraryStores(directory)
        stores.forUser("user-1").save(sample())
        stores.forUser("user-2").save(AccountLibraryDocument(userId = "user-2", cursor = "9"))

        val others = stores.exceptUser("user-1")

        assertEquals(1, others.size)
        val document = (others.single().load() as AccountLibraryLoad.Loaded).document
        assertEquals("user-2", document.userId)
    }

    /**
     * Schema 2 (LEAF802): the import records survive a write and a read, because
     * REQ-507's "app death mid-transfer resumes without a duplicate" is entirely
     * a claim about this file.
     */
    @Test
    fun `an import record survives the round trip with everything a resume needs`() {
        val store = FileAccountLibraryStores(directory).forUser("user-1")
        val record = PublicationImportRecord(
            clientImportId = "reader-import-v1-${"c".repeat(64)}",
            accountId = "user-1",
            contentSha256 = "b".repeat(64),
            sizeBytes = 8_388_608,
            sourceFormat = PublicationFormat.EPUB,
            sourceMimeType = "application/epub+zip",
            originalFileName = "dune.epub",
            importId = "9a3b1c2d-4e5f-4061-8172-839405a6b7c8",
            status = PublicationImportStatus.PENDING_UPLOAD,
            transferLocation = "https://storage.example/upload/resumable/abc",
            grantExpiresAt = "2026-09-21T10:00:00Z",
        )
        store.save(sample().withImport(record))

        val loaded = (store.load() as AccountLibraryLoad.Loaded).document

        assertEquals(listOf(record), loaded.imports)
        assertEquals(record, loaded.import(record.clientImportId))
        assertNull(loaded.withoutImport(record.clientImportId).import(record.clientImportId))
    }

    /**
     * A version 1 document — every device that ran increment 001 — reads back as
     * a version 2 one with no imports, which is the truth about it: it never
     * added a book. Nothing else in the document moves.
     */
    @Test
    fun `a version one document migrates forward with its rows and queue intact`() {
        val codec = AccountLibraryCodec()
        val version1 = codec.encode(sample())
            .replace("\"schemaVersion\":2", "\"schemaVersion\":1")
            // A real version 1 document has no `imports` key at all; leaving the
            // encoder's empty one in would test a document no device ever wrote.
            .replace(",\"imports\":[]", "")
        assertTrue("the fixture must really be a version 1 document", version1.contains("\"schemaVersion\":1"))
        assertTrue("a version 1 document has no imports key", !version1.contains("imports"))

        val decoded = codec.decode(version1) as AccountLibraryDecoding.Decoded

        assertEquals(1, decoded.migratedFrom)
        assertEquals(AccountLibrarySchema.CURRENT_VERSION, decoded.document.schemaVersion)
        assertEquals(sample().books, decoded.document.books)
        assertEquals(sample().outbox, decoded.document.outbox)
        assertEquals(emptyList<PublicationImportRecord>(), decoded.document.imports)
    }
}
