package com.cedagova.reader.library.sync

import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationImportStatus
import com.cedagova.reader.library.model.ReaderCoverStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `a second save replaces the first and leaves no temporary file`() {
        val store = FileAccountLibraryStores(directory).forUser("user-1")
        store.save(AccountLibraryDocument(userId = "user-1", cursor = "1"))
        store.save(sample())

        assertEquals(sample(), (store.load() as AccountLibraryLoad.Loaded).document)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun `a failed replace throws and the previous document survives`() {
        val file = File(directory, "account-test.json")
        FileAccountLibraryStore(file).save(sample())
        val before = file.readBytes()

        val failing = FileAccountLibraryStore(
            file = file,
            codec = AccountLibraryCodec(),
            clock = { 0L },
            replace = { _, _ -> throw IOException("injected rename failure") },
        )
        val thrown = assertThrows(IOException::class.java) {
            failing.save(AccountLibraryDocument(userId = "user-1"))
        }

        assertEquals("injected rename failure", thrown.cause?.message)
        assertArrayEquals(before, file.readBytes())
        assertEquals(sample(), (FileAccountLibraryStore(file).load() as AccountLibraryLoad.Loaded).document)
        assertEquals(listOf(file.name), directory.list()!!.toList())
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
            .replace("\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION}", "\"schemaVersion\":1")
            // A real version 1 document has neither key at all; leaving the
            // encoder's empty ones in would test a document no device ever wrote.
            .replace(",\"imports\":[]", "")
            .replace(",\"copies\":[]", "")
        assertTrue("the fixture must really be a version 1 document", version1.contains("\"schemaVersion\":1"))
        assertTrue("a version 1 document has no imports key", !version1.contains("imports"))
        assertTrue("a version 1 document has no copies key", !version1.contains("copies"))

        val decoded = codec.decode(version1) as AccountLibraryDecoding.Decoded

        assertEquals(1, decoded.migratedFrom)
        assertEquals(AccountLibrarySchema.CURRENT_VERSION, decoded.document.schemaVersion)
        assertEquals(sample().books, decoded.document.books)
        assertEquals(sample().outbox, decoded.document.outbox)
        assertEquals(emptyList<PublicationImportRecord>(), decoded.document.imports)
        assertEquals(sample().host, decoded.document.host)
    }

    /**
     * Schema 3's own step (#118): a device that has added a book but never
     * downloaded one.
     *
     * The 2 → 3 migration writes nothing, and this is what "nothing" has to
     * mean: every row, every queued mutation and every import record read back
     * exactly as version 2 held them, plus an empty copy list — which is the
     * truth about a device with no copies rather than an absent answer.
     */
    @Test
    fun `a version two document migrates forward with no copies and nothing else changed`() {
        val codec = AccountLibraryCodec()
        val version2 = codec.encode(sample())
            .replace("\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION}", "\"schemaVersion\":2")
            .replace(",\"copies\":[]", "")
        assertTrue("the fixture must really be a version 2 document", version2.contains("\"schemaVersion\":2"))
        assertTrue("a version 2 document has no copies key", !version2.contains("copies"))

        val decoded = codec.decode(version2) as AccountLibraryDecoding.Decoded

        assertEquals(2, decoded.migratedFrom)
        assertEquals(AccountLibrarySchema.CURRENT_VERSION, decoded.document.schemaVersion)
        assertEquals(sample().books, decoded.document.books)
        assertEquals(sample().outbox, decoded.document.outbox)
        assertEquals(sample().imports, decoded.document.imports)
        assertEquals(sample().host, decoded.document.host)
    }

    /**
     * 3 → 4: a document written before this increment decodes with no remote
     * position, which is the truth about a device that never heard one.
     */
    @Test
    fun `a version three document migrates forward with no remote position and nothing else changed`() {
        val codec = AccountLibraryCodec()
        val version3 = codec.encode(sample())
            .replace("\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION}", "\"schemaVersion\":3")
            .replace(",\"remotePosition\":null", "")
            .replace(",\"resumeOfferSettledFor\":null", "")
        assertTrue("the fixture must really be a version 3 document", version3.contains("\"schemaVersion\":3"))
        assertTrue("a version 3 document names no remote position", !version3.contains("remotePosition"))
        assertTrue("nor a settled resume offer", !version3.contains("resumeOfferSettledFor"))

        val decoded = codec.decode(version3) as AccountLibraryDecoding.Decoded

        assertEquals(3, decoded.migratedFrom)
        assertEquals(AccountLibrarySchema.CURRENT_VERSION, decoded.document.schemaVersion)
        assertEquals(sample().books, decoded.document.books)
        assertEquals(sample().outbox, decoded.document.outbox)
        assertEquals(sample().imports, decoded.document.imports)
        assertEquals(sample().host, decoded.document.host)
        assertTrue(
            "every row comes back without a remote position",
            decoded.document.books.all { it.remotePosition == null },
        )
    }

    /** A remote position survives a write and a read, with the server's ordering intact. */
    @Test
    fun `a remote position round trips with the server's revision and admission time`() {
        val codec = AccountLibraryCodec()
        val remote = AccountRemotePosition(
            href = "OEBPS/ch8.xhtml",
            chapterTitle = "Chapter Eight",
            progression = 0.625,
            percent = 62.5,
            updatedAt = "2026-09-20T09:00:00Z",
            revision = 11,
            serverAdmittedAt = "2026-09-20T09:00:01Z",
        )
        val book = sample().books.first().copy(remotePosition = remote)
        val document = sample().withBook(book)

        val decoded = codec.decode(codec.encode(document)) as AccountLibraryDecoding.Decoded

        assertEquals(remote, decoded.document.book(book.bookId)!!.remotePosition)
    }

    /**
     * Schema 5 (#121): a document written before the resume offer existed reads
     * back with nothing answered, which is the truth about a device that was never
     * asked. Everything else comes back untouched.
     */
    @Test
    fun `a version four document migrates forward with no settled resume offer`() {
        val codec = AccountLibraryCodec()
        val version4 = codec.encode(sample())
            .replace("\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION}", "\"schemaVersion\":4")
            .replace(",\"resumeOfferSettledFor\":null", "")
        assertTrue("the fixture must really be a version 4 document", version4.contains("\"schemaVersion\":4"))
        assertTrue("a version 4 document names no settled offer", !version4.contains("resumeOfferSettledFor"))

        val decoded = codec.decode(version4) as AccountLibraryDecoding.Decoded

        assertEquals(4, decoded.migratedFrom)
        assertEquals(AccountLibrarySchema.CURRENT_VERSION, decoded.document.schemaVersion)
        assertEquals(sample().books, decoded.document.books)
        assertEquals(sample().outbox, decoded.document.outbox)
        assertEquals(sample().imports, decoded.document.imports)
        assertEquals(sample().host, decoded.document.host)
        assertTrue(
            "every row comes back never having been asked",
            decoded.document.books.all { it.host.isEmpty() },
        )
    }

    /**
     * Schema 6 (#140): a document written before this device marked its own
     * admitted position reads back with none marked. Everything else is untouched.
     */
    @Test
    fun `a version five document migrates forward with no own position marked`() {
        val codec = AccountLibraryCodec()
        val version5 = codec.encode(sample())
            .replace("\"schemaVersion\":${AccountLibrarySchema.CURRENT_VERSION}", "\"schemaVersion\":5")
            .replace(",\"ownPositionChangeKey\":null", "")
        assertTrue("the fixture must really be a version 5 document", version5.contains("\"schemaVersion\":5"))
        assertTrue("a version 5 document names no own position", !version5.contains("ownPositionChangeKey"))

        val decoded = codec.decode(version5) as AccountLibraryDecoding.Decoded

        assertEquals(5, decoded.migratedFrom)
        assertEquals(AccountLibrarySchema.CURRENT_VERSION, decoded.document.schemaVersion)
        assertEquals(sample().books, decoded.document.books)
        assertTrue(decoded.document.books.all { it.ownPositionChangeKey == null })
    }

    /** The own-position mark survives a write and a read. */
    @Test
    fun `an own position mark round trips`() {
        val codec = AccountLibraryCodec()
        val book = sample().books.first().copy(ownPositionChangeKey = "5:2026-09-20T09:00:00Z")
        val document = sample().withBook(book)

        val decoded = codec.decode(codec.encode(document)) as AccountLibraryDecoding.Decoded

        assertEquals("5:2026-09-20T09:00:00Z", decoded.document.book(book.bookId)!!.ownPositionChangeKey)
    }

    /**
     * Host records (#147) survive a write and a read at the level they were
     * written, and are stored as plain keys of that level — never under a `host`
     * key, which is what keeps a document from before the move byte-compatible.
     */
    @Test
    fun `host records round trip at their own level and never under a host key`() {
        val codec = AccountLibraryCodec()
        val copies = buildJsonArray { add(buildJsonObject { put("contentSha256", JsonPrimitive("b".repeat(64))) }) }
        val book = sample().books.first().copy(
            host = buildJsonObject { put("resumeOfferSettledFor", JsonPrimitive("11:2026-09-20T09:00:00Z")) },
        )
        val document = sample().withBook(book).copy(host = buildJsonObject { put("copies", copies) })

        val text = codec.encode(document)
        val decoded = codec.decode(text) as AccountLibraryDecoding.Decoded

        assertEquals(document.copy(schemaVersion = AccountLibrarySchema.CURRENT_VERSION), decoded.document)
        val raw = Json.parseToJsonElement(text).jsonObject
        assertEquals(copies, raw["copies"])
        assertEquals(
            JsonPrimitive("11:2026-09-20T09:00:00Z"),
            raw["books"]!!.jsonArray.first().jsonObject["resumeOfferSettledFor"],
        )
        assertTrue("no level is ever written with a host key", !text.contains("\"host\""))
    }

    /** A host record can add to a level but never shadow a key the schema declares there. */
    @Test
    fun `a host record never shadows a declared key`() {
        val codec = AccountLibraryCodec()
        val document = sample().copy(host = buildJsonObject { put("cursor", JsonPrimitive("999")) })

        val decoded = codec.decode(codec.encode(document)) as AccountLibraryDecoding.Decoded

        assertEquals("412", decoded.document.cursor)
        assertTrue(decoded.document.host.isEmpty())
    }

    /**
     * The key is the server's own ordering and nothing of this device's, and a
     * later change carries a different one — which is the whole of "a newer remote
     * change is offered again".
     */
    @Test
    fun `a remote change key names the revision and the server's own time`() {
        val at = "2026-09-20T09:00:00Z"
        assertEquals("11:$at", AccountRemotePosition(updatedAt = at, revision = 11).changeKey)
        assertEquals(
            "a later revision is a different question",
            "12:$at",
            AccountRemotePosition(updatedAt = at, revision = 12).changeKey,
        )
        assertEquals(
            "and so is the same revision restated at a different time",
            "11:2026-09-20T10:00:00Z",
            AccountRemotePosition(updatedAt = "2026-09-20T10:00:00Z", revision = 11).changeKey,
        )
    }

    /**
     * #147's compatibility promise, on a real document: `account-library-v6.json`
     * is what FastReader's own codec wrote at main `ab175bc`, before the engine
     * moved here — every schema 6 field set, including FastReader's `copies` and
     * a row's `resumeOfferSettledFor`, which are host records now.
     *
     * It loads through the file store with no migration and no recovery, every
     * value reads back, and writing it again produces the same JSON key for key:
     * nothing the old build stored is dropped by the new one.
     */
    @Test
    fun `a schema 6 document written before the move loads and saves without loss`() {
        val text = javaClass.getResource("/account-library-v6.json")!!.readText().trim()
        directory.mkdirs()
        val file = File(directory, "account-legacy.json").apply { writeText(text) }

        val load = FileAccountLibraryStore(file).load() as AccountLibraryLoad.Loaded
        assertNull("schema 6 is current: nothing to migrate", load.migratedFrom)
        assertNull("and nothing was set aside as damaged", load.recoveredFrom)

        val document = load.document
        assertEquals("user-1", document.userId)
        assertEquals("412", document.cursor)
        assertEquals(listOf("book-1", "book-2"), document.books.map { it.bookId })
        val dune = document.book("book-1")!!
        assertEquals("Dune", dune.title)
        assertEquals(ReaderLibraryStatus.READING, dune.status)
        assertEquals(ReaderCoverStatus.COVERED, dune.coverStatus)
        assertEquals(7L, dune.revision)
        assertEquals(41.5, dune.progressPercent!!, 0.0)
        assertEquals(11L, dune.remotePosition!!.revision)
        assertEquals("OEBPS/ch3.xhtml", dune.remotePosition!!.href)
        assertEquals("11:2026-09-14T10:01:00Z", dune.ownPositionChangeKey)
        assertEquals(JsonPrimitive("9:2026-09-13T08:00:00Z"), dune.host["resumeOfferSettledFor"])
        assertTrue(document.book("book-2")!!.removed)
        assertEquals(listOf("key-1", "key-2"), document.outbox.map { it.idempotencyKey })
        assertEquals(ReaderMutationKind.DELETE, document.outbox.first().mutationKind)
        assertEquals(11L, document.outbox.last().baseRevision)
        assertEquals(PublicationImportStatus.PENDING_UPLOAD, document.imports.single().status)
        assertEquals(
            "4096",
            document.host["copies"]!!.jsonArray.single().jsonObject["sizeBytes"]!!.toString(),
        )

        assertEquals(
            "re-encoding a schema 6 document must reproduce it key for key",
            Json.parseToJsonElement(text),
            Json.parseToJsonElement(AccountLibraryCodec().encode(document)),
        )
        FileAccountLibraryStore(file).save(document)
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(file.readText()))
    }
}
