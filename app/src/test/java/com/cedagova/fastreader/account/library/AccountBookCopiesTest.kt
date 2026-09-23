package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.sync.AccountBook
import com.cedagova.fastreader.account.AssetDownloadGateway
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.BookSource
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.fastreader.library.SourceOrigin
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.downloads.AssetDownloadException
import com.cedagova.reader.library.model.ReaderAssetDirection
import com.cedagova.reader.library.model.ReaderAssetGrant
import com.cedagova.reader.library.model.ReaderAssetMethod
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download direction end to end, with everything real but the network
 * (#118, REQ-510, D2, AD-24).
 *
 * The store, the ingestor, the catalog codec and the repository are the
 * production classes; only [ScriptedDownloads] stands in for reader-api and the
 * storage provider, and it stands in for them the way they behave — a grant
 * with a TTL, and a signature that can be spent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountBookCopiesTest {

    @get:Rule val temporary = TemporaryFolder()

    private val bytes = EpubFixtures.validEpub(title = "The Quiet Machine")
    private val digest = sha256(bytes)
    private val gateway = FakeDocumentGateway()
    private val references = RecordingCopyReferences()

    private val book = AccountBook(
        bookId = "1f0f1c9e-6a3c-4f8a-9c2b-2f1c7d3e4a5b",
        title = "The Quiet Machine",
        contentSha256 = digest,
        assetId = "11111111-1111-1111-1111-111111111111",
    )

    // ------------------------------------------------------------- the happy path

    @Test
    fun `a downloaded book is verified, placed and readable as a device book`() = runTest {
        val downloads = ScriptedDownloads(bytes)
        val library = repository(backgroundScope)
        val copies = copies(downloads, library)
        val progress = mutableListOf<Pair<Long, Long>>()

        val outcome = copies.download(book) { received, total -> progress += received to total }

        val ready = outcome as CopyOutcome.Ready
        assertEquals("the row's id is the book's own content digest", "sha256:$digest", ready.bookId)
        assertEquals(bytes.size.toLong(), ready.sizeBytes)
        assertEquals("exactly one grant for one download", 1, downloads.grants.size)
        assertEquals(book.assetId, downloads.grants.single())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())

        val row = library.catalog.value.book(ready.bookId)!!
        assertEquals("The Quiet Machine", row.title)
        val source = row.sources.single()
        assertEquals(SourceOrigin.ACCOUNT_COPY, source.origin)
        assertTrue(source.isAccountCopy)
        assertEquals(BookSource.accountCopyUri(digest), source.uri)
        assertEquals(ready.file.absolutePath, source.filePath)
        assertEquals(SourceAvailability.AVAILABLE, source.availability)

        // And it opens: the repository resolves the private copy itself, so the
        // reader asks for a book and gets one (REQ-510).
        assertEquals(
            bytes.toList(),
            library.byteSource(ready.bookId).open().use { it.readBytes() }.toList(),
        )
        assertNotNull("a copy is seekable, so the reader opens it the cheap way", library.openBookChannel(ready.bookId))

        assertEquals(
            "the account remembers that it has this book here",
            listOf(digest),
            references.stored.map { it.contentSha256 },
        )
        assertEquals(bytes.size.toLong(), references.stored.single().sizeBytes)
    }

    /**
     * A book this device already has as a picked file becomes a *second source
     * of one row*, not a second row (AD-23).
     */
    @Test
    fun `a copy of a book already on the device joins its row`() = runTest {
        gateway.putDocument("doc://a", bytes, "quiet.epub")
        val library = repository(backgroundScope)
        library.addPickedBooks(listOf("doc://a"))
        assertEquals(1, library.catalog.value.books.size)

        val ready = copies(ScriptedDownloads(bytes), library).download(book) as CopyOutcome.Ready

        assertEquals("one book, not two", 1, library.catalog.value.books.size)
        val sources = library.catalog.value.book(ready.bookId)!!.sources
        assertEquals(2, sources.size)
        assertEquals(setOf(SourceOrigin.DIRECT_PICK, SourceOrigin.ACCOUNT_COPY), sources.map { it.origin }.toSet())
    }

    // ------------------------------------------------------------- the refusals

    @Test
    fun `tampered bytes are refused with the reason and leave no row and no file`() = runTest {
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val library = repository(backgroundScope)
        val store = store()
        val copies = copies(ScriptedDownloads(tampered), library, store)

        val outcome = copies.download(book)

        val refused = outcome as CopyOutcome.Tampered
        assertEquals(digest, refused.expected)
        assertEquals(sha256(tampered), refused.actual)
        assertFalse("nothing may be placed", store.has(digest))
        assertEquals("no row may be written for bytes that were refused", 0, library.catalog.value.books.size)
        assertEquals("and the account must not think it has the book", 0, references.stored.size)
    }

    @Test
    fun `a grant reader-api will not issue is reported unchanged, and nothing is placed`() = runTest {
        val downloads = ScriptedDownloads(bytes).apply {
            grantFailure = ReaderAuthException.NetworkUnavailable(java.io.IOException("offline"))
        }
        val library = repository(backgroundScope)
        val store = store()

        val outcome = copies(downloads, library, store).download(book)

        assertTrue(outcome is CopyOutcome.GrantFailed)
        assertTrue((outcome as CopyOutcome.GrantFailed).error is ReaderAuthException.NetworkUnavailable)
        assertFalse(store.has(digest))
        assertEquals(0, library.catalog.value.books.size)
    }

    @Test
    fun `a full disk is reported as no storage, with nothing placed`() = runTest {
        val downloads = ScriptedDownloads(bytes).apply {
            transportFailure = java.io.IOException("No space left on device")
        }
        val library = repository(backgroundScope)
        val store = store()

        val outcome = copies(downloads, library, store).download(book)

        assertTrue(outcome is CopyOutcome.NoStorage)
        assertFalse(store.has(digest))
        assertEquals(0, library.catalog.value.books.size)
    }

    @Test
    fun `an account book with no asset or no identity is never fetched`() = runTest {
        val downloads = ScriptedDownloads(bytes)
        val copies = copies(downloads, repository(backgroundScope))

        assertTrue(copies.download(book.copy(assetId = null)) is CopyOutcome.Unavailable)
        assertTrue(copies.download(book.copy(contentSha256 = null)) is CopyOutcome.Unavailable)
        assertEquals("no grant may be asked for without something to verify against", 0, downloads.grants.size)
    }

    // ------------------------------------------------------------- the spent grant

    /**
     * The acceptance criterion the plan's TTL assumption names: a grant that
     * expires mid-download is re-fetched and the download succeeds.
     *
     * The proof is in two numbers — two grants asked for, one book placed — and
     * in the bytes, which are the book. A client that had retried the *same*
     * grant would have asked for one.
     */
    @Test
    fun `a grant spent mid-download is re-fetched once and the copy still lands`() = runTest {
        val downloads = ScriptedDownloads(bytes).apply { rejectAttempts = 1 }
        val library = repository(backgroundScope)
        val store = store()

        val ready = copies(downloads, library, store).download(book) as CopyOutcome.Ready

        assertEquals("the expiry cost exactly one more grant", 2, downloads.grants.size)
        assertEquals("and exactly one more fetch", 2, downloads.fetches)
        assertTrue(store.has(digest))
        assertEquals(bytes.toList(), ready.file.readBytes().toList())
        assertEquals(1, library.catalog.value.books.size)
    }

    /** Two rejections is the asset, not the signature: reported, not retried forever. */
    @Test
    fun `a grant rejected twice is reported rather than retried again`() = runTest {
        val downloads = ScriptedDownloads(bytes).apply { rejectAttempts = Int.MAX_VALUE }
        val library = repository(backgroundScope)
        val store = store()

        val outcome = copies(downloads, library, store).download(book)

        assertTrue(outcome is CopyOutcome.DownloadFailed)
        assertTrue((outcome as CopyOutcome.DownloadFailed).error is AssetDownloadException.GrantRejected)
        assertEquals("two attempts, and then the answer", 2, downloads.grants.size)
        assertFalse(store.has(digest))
        assertEquals(0, library.catalog.value.books.size)
    }

    // ------------------------------------------------------------- freeing a copy

    @Test
    fun `removing a copy deletes the file and the source, and the row with it`() = runTest {
        val library = repository(backgroundScope)
        val store = store()
        val copies = copies(ScriptedDownloads(bytes), library, store)
        val ready = copies.download(book) as CopyOutcome.Ready

        assertTrue(copies.remove(digest))

        assertFalse("the bytes are gone", ready.file.exists())
        assertFalse(store.has(digest))
        assertEquals("the row goes with its only source", 0, library.catalog.value.books.size)
        assertEquals("and the account no longer claims it", 0, references.stored.size)
        assertFalse("removing a copy that is gone is not a failure", copies.remove(digest))
    }

    /** A book that is also a picked file keeps that source: freeing the copy frees the copy. */
    @Test
    fun `removing a copy of a book that is also on the device keeps the book`() = runTest {
        gateway.putDocument("doc://a", bytes, "quiet.epub")
        val library = repository(backgroundScope)
        library.addPickedBooks(listOf("doc://a"))
        val store = store()
        val copies = copies(ScriptedDownloads(bytes), library, store)
        val ready = copies.download(book) as CopyOutcome.Ready

        copies.remove(digest)

        assertFalse(ready.file.exists())
        val row = library.catalog.value.book(ready.bookId)
        assertNotNull("the book stays: it is still a device book", row)
        assertEquals(SourceOrigin.DIRECT_PICK, row!!.sources.single().origin)
        assertEquals(bytes.toList(), library.byteSource(row.id).open().use { it.readBytes() }.toList())
    }

    /**
     * The position outlives the copy (REQ-004), so downloading the book again
     * resumes where the reader left off.
     */
    @Test
    fun `freeing a copy keeps the reading position`() = runTest {
        val library = repository(backgroundScope)
        val copies = copies(ScriptedDownloads(bytes), library)
        val ready = copies.download(book) as CopyOutcome.Ready
        library.updateReadingState(
            ready.bookId,
            com.cedagova.fastreader.library.ReadingState(bookDigest = ready.bookId, tokenIndex = 42),
        )

        copies.remove(digest)

        assertEquals(42, library.readingState(ready.bookId)?.tokenIndex)
    }

    // ------------------------------------------------------------- the start-up sweep

    @Test
    fun `the start-up sweep discards partials and reconciles rows and references`() = runTest {
        val library = repository(backgroundScope)
        val store = store()
        val copies = copies(ScriptedDownloads(bytes), library, store)
        val ready = copies.download(book) as CopyOutcome.Ready

        // A dead process's partial, and a copy the system reclaimed.
        val directory = File(temporary.root, AccountCopyStore.DIRECTORY_NAME)
        File(directory, AccountCopyStore.PREFIX + "d".repeat(64) + AccountCopyStore.PARTIAL_SUFFIX).writeBytes(bytes)
        assertTrue(ready.file.delete())

        assertEquals("the partial goes", 1, copies.reconcile())

        assertEquals(
            "a row whose bytes are gone says so rather than claiming to be readable",
            SourceAvailability.MISSING,
            library.catalog.value.book(ready.bookId)!!.sources.single().availability,
        )
        assertNull("and it no longer opens", library.catalog.value.book(ready.bookId)!!.readableSource)
        assertEquals("the account's reference goes with the bytes", 0, references.stored.size)
    }

    @Test
    fun `a device with no copies sweeps nothing and changes nothing`() = runTest {
        gateway.putDocument("doc://a", bytes, "quiet.epub")
        val library = repository(backgroundScope)
        library.addPickedBooks(listOf("doc://a"))
        val before = library.catalog.value

        assertEquals(0, copies(ScriptedDownloads(bytes), library).reconcile())

        assertEquals(before, library.catalog.value)
    }

    // ------------------------------------------------------------- fixtures

    private fun store() = AccountCopyStore(File(temporary.root, AccountCopyStore.DIRECTORY_NAME))

    private fun repository(scope: CoroutineScope): LibraryRepository {
        val covers = CoverStore(File(temporary.root, "covers"))
        return LibraryRepository(
            store = FileCatalogStore(File(File(temporary.root, "catalog"), "catalog.json")),
            ingestor = CatalogIngestor(gateway, covers),
            gateway = gateway,
            covers = covers,
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[TestCoroutineScheduler]),
        )
    }

    private fun copies(
        downloads: AssetDownloadGateway,
        library: LibraryRepository,
        store: AccountCopyStore = store(),
    ) = AccountBookCopies(
        gateway = downloads,
        store = store,
        references = references,
        library = library,
        clock = { 1_700_000_000_000 },
    )

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    /**
     * reader-api and the storage provider, as the download path sees them.
     *
     * The grant is re-minted on every ask, which is what makes "the expiry cost
     * one more grant" countable; [rejectAttempts] spends the signature the way a
     * TTL does.
     */
    private class ScriptedDownloads(private val body: ByteArray) : AssetDownloadGateway {

        val grants = mutableListOf<String>()
        var fetches: Int = 0

        /** reader-api refuses to issue a grant at all. */
        var grantFailure: ReaderAuthException? = null

        /** The provider rejects the signature on this many attempts, then serves. */
        var rejectAttempts: Int = 0

        /** The sink refuses the bytes — a full disk, for instance. */
        var transportFailure: Throwable? = null

        override suspend fun downloadGrant(assetId: String): ReaderAssetGrant {
            grantFailure?.let { throw it }
            grants += assetId
            return ReaderAssetGrant(
                assetId = assetId,
                bookId = "22222222-2222-2222-2222-222222222222",
                direction = ReaderAssetDirection.DOWNLOAD,
                method = ReaderAssetMethod.GET,
                url = "https://storage.test/object/$assetId?token=signed-${grants.size}",
                expiresAt = "2026-09-21T12:00:00Z",
                checksum = "sha256:" + MessageDigest.getInstance("SHA-256")
                    .digest(body).joinToString("") { "%02x".format(it) },
                sizeBytes = body.size.toLong(),
                uploadStatus = "ready",
                headers = mapOf("x-signature" to "test-signature-not-a-credential"),
            )
        }

        override suspend fun download(
            grant: ReaderAssetGrant,
            sink: OutputStream,
            onProgress: (written: Long, total: Long) -> Unit,
        ): Long {
            fetches++
            if (fetches <= rejectAttempts) throw AssetDownloadException.GrantRejected(403)
            transportFailure?.let { throw it }
            sink.write(body)
            onProgress(body.size.toLong(), grant.sizeBytes)
            return body.size.toLong()
        }
    }

    /** The account document's copy half, recorded rather than persisted. */
    private class RecordingCopyReferences : AccountCopyReferences {
        val stored = mutableListOf<AccountCopy>()

        override fun accountId(): String = "user-1"

        override suspend fun copyReferences(): List<AccountCopy> = stored.toList()

        override suspend fun putCopyReference(copy: AccountCopy) {
            stored.removeAll { it.contentSha256 == copy.contentSha256 }
            stored += copy
        }

        override suspend fun dropCopyReference(contentSha256: String) {
            stored.removeAll { it.contentSha256 == contentSha256 }
        }

        override suspend fun retainCopyReferences(present: Set<String>) {
            stored.retainAll { it.contentSha256 in present }
        }
    }
}
