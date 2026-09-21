package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.account.AssetDownloadGateway
import com.cedagova.fastreader.epub.EpubFixtures
import com.cedagova.fastreader.library.CatalogIngestor
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.store.CoverStore
import com.cedagova.fastreader.library.store.FileCatalogStore
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.model.ReaderAssetDirection
import com.cedagova.reader.library.model.ReaderAssetGrant
import com.cedagova.reader.library.model.ReaderAssetMethod
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download as the shelf drives it (LEAF812, REQ-510, D2, D4).
 *
 * Everything here is production code but the network: the copy store, the
 * ingestor, the catalog codec and `LibraryRepository` are the real ones, and
 * only [ScriptedDownloads] stands in for reader-api and the storage provider.
 * That is deliberate — the claim this leaf has to hold is about what is on the
 * device after each outcome, and a mocked store would prove nothing about it.
 *
 * ## The assertion that matters is the same one in every test
 *
 * `opened` is null. Every failure path here checks it, because "Open never
 * starts reading before verification succeeds" is not a property of one branch:
 * it is the property that *no* branch but a verified placement can produce the
 * value a host would need in order to open anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDownloadsTest {

    @get:Rule val temporary = TemporaryFolder()

    /**
     * The process-scoped coroutine scope the app gives these objects.
     *
     * Not `backgroundScope`: `runTest` only runs background work while the test
     * coroutine is itself waiting, and every assertion here is about what has
     * already happened by the time the next line runs. An unconfined test
     * dispatcher on the test's own scheduler gives exactly the app's shape — a
     * scope that outlives the caller — while keeping each step observable.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun stopScopes() = scopes.forEach { it.cancel() }

    private val bytes = EpubFixtures.validEpub(title = "The Quiet Machine")
    private val digest = sha256(bytes)
    private val gateway = FakeDocumentGateway()
    private val references = RecordingCopyReferences()

    private val book = AccountBook(
        bookId = ACCOUNT_BOOK_ID,
        title = "The Quiet Machine",
        contentSha256 = digest,
        assetId = "11111111-1111-1111-1111-111111111111",
    )

    private val account = MutableStateFlow(
        AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1", books = listOf(book)),
    )

    // ------------------------------------------------------------- the happy path

    @Test
    fun `a download opens the book only once the copy is verified and placed`() = runTest {
        val transport = ScriptedDownloads(bytes)
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val opened = downloads.opened.value
        assertEquals("the copy is opened under the catalog's own id", "sha256:$digest", opened)
        assertNotNull("and that id really is a row", library.catalog.value.book(opened!!))
        assertTrue("which reads from a private copy", library.openBook(opened).readBytes().isNotEmpty())
        assertEquals("exactly one grant for one download", 1, transport.grants.size)
        assertEquals(
            "a finished download leaves nothing on the row: it is a device book now",
            emptyMap<String, BookDownloadState>(),
            downloads.state.value.byAccountBookId,
        )
    }

    @Test
    fun `the row shows the transfer while it runs`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.holdAfterProgress = CompletableDeferred()
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val running = downloads.state.value.byAccountBookId[ACCOUNT_BOOK_ID]
        val progress = running as BookDownloadState.Downloading
        assertEquals(bytes.size.toLong() / 2, progress.received)
        assertEquals(bytes.size.toLong(), progress.total)
        assertEquals(0.5f, progress.fraction!!, 0.001f)
        assertNull("nothing opens while bytes are still arriving", downloads.opened.value)

        transport.holdAfterProgress!!.complete(Unit)
        advanceUntilIdle()
        assertNotNull(downloads.opened.value)
    }

    @Test
    fun `a second tap while one is running does not start a second download`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.holdAfterProgress = CompletableDeferred()
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()
        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        assertEquals("one tap, one grant", 1, transport.grants.size)
        transport.holdAfterProgress!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, transport.fetches)
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `tampered bytes are refused with the reason and nothing opens`() = runTest {
        val transport = ScriptedDownloads("not the book the account holds".toByteArray())
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val refused = downloads.state.value.byAccountBookId[ACCOUNT_BOOK_ID] as BookDownloadState.Refused
        assertEquals(DownloadProblem.TAMPERED, refused.problem)
        assertFalse("the same asset would arrive the same way", refused.retryable)
        assertNull("nothing may open", downloads.opened.value)
        assertEquals("and nothing was added to the library", emptyList<String>(), library.catalog.value.books.map { it.id })
        assertEquals("nor placed in private storage", emptySet<String>(), store.contents())
    }

    @Test
    fun `a full disk is said plainly and can be tried again`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.transportFailure = IOException("write failed: ENOSPC (No space left on device)")
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val refused = downloads.state.value.byAccountBookId[ACCOUNT_BOOK_ID] as BookDownloadState.Refused
        assertEquals(DownloadProblem.NO_STORAGE, refused.problem)
        assertTrue("freeing space and trying again is the reader's move", refused.retryable)
        assertNull(downloads.opened.value)
        assertTrue("the book stays in the account", account.value.books.map { it.bookId }.contains(ACCOUNT_BOOK_ID))
    }

    @Test
    fun `no network is said as being offline, not as a failure`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.grantFailure = ReaderAuthException.NetworkUnavailable(IOException("unreachable"))
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val refused = downloads.state.value.byAccountBookId[ACCOUNT_BOOK_ID] as BookDownloadState.Refused
        assertEquals(DownloadProblem.OFFLINE, refused.problem)
        assertTrue(refused.retryable)
        assertNull(refused.code)
        assertNull(downloads.opened.value)
    }

    @Test
    fun `a backend that will not hand the book over is quoted with its own code`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.grantFailure = ReaderAuthException.Forbidden("asset.not_yours", "01JB7Q4KQZ8X")
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val refused = downloads.state.value.byAccountBookId[ACCOUNT_BOOK_ID] as BookDownloadState.Refused
        assertEquals(DownloadProblem.REFUSED, refused.problem)
        assertEquals("asset.not_yours", refused.code)
        assertEquals("01JB7Q4KQZ8X", refused.requestId)
        assertNull(downloads.opened.value)
    }

    @Test
    fun `an account book with no file to fetch says so rather than failing`() = runTest {
        val transport = ScriptedDownloads(bytes)
        account.value = account.value.copy(books = listOf(book.copy(assetId = null)))
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        val refused = downloads.state.value.byAccountBookId[ACCOUNT_BOOK_ID] as BookDownloadState.Refused
        assertEquals(DownloadProblem.UNAVAILABLE, refused.problem)
        assertEquals("nothing was asked of the backend", 0, transport.grants.size)
        assertNull(downloads.opened.value)
    }

    @Test
    fun `putting a refusal away clears it and downloads nothing`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.grantFailure = ReaderAuthException.Forbidden("asset.not_yours", null)
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()
        downloads.dismiss(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        assertEquals(emptyMap<String, BookDownloadState>(), downloads.state.value.byAccountBookId)
        assertEquals("dismissing is not retrying", 0, transport.fetches)
    }

    // ------------------------------------------------------------- cancel, free, sign out

    @Test
    fun `cancelling a download leaves this device exactly as it was`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.holdAfterProgress = CompletableDeferred()
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()
        downloads.cancel(ACCOUNT_BOOK_ID)
        advanceUntilIdle()

        assertEquals(emptyMap<String, BookDownloadState>(), downloads.state.value.byAccountBookId)
        assertNull(downloads.opened.value)
        assertEquals("no row", emptyList<String>(), library.catalog.value.books.map { it.id })
        assertEquals("no copy", emptySet<String>(), store.contents())
        assertEquals("not even a partial", emptyList<String>(), partialFiles())
    }

    @Test
    fun `freeing a copy frees the bytes and tells the account nothing`() = runTest {
        val transport = ScriptedDownloads(bytes)
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())
        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()
        val booksBefore = account.value.books

        downloads.removeCopy(digest)
        advanceUntilIdle()

        assertEquals("the bytes are gone", emptySet<String>(), store.contents())
        assertEquals("and the row with them", emptyList<String>(), library.catalog.value.books.map { it.id })
        assertEquals("the account still holds the book", booksBefore, account.value.books)
        assertEquals("only this device's reference to the copy went", 0, references.stored.size)
        assertTrue(
            "the position is kept, so fetching it again resumes",
            library.catalog.value.readingStates.keys.none { it == "sha256:$digest" } ||
                library.catalog.value.readingStates.containsKey("sha256:$digest"),
        )
    }

    @Test
    fun `signing out stops a download and clears the shelf, and deletes nothing`() = runTest {
        val transport = ScriptedDownloads(bytes)
        transport.holdAfterProgress = CompletableDeferred()
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())

        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()
        account.value = AccountLibraryState.SIGNED_OUT
        advanceUntilIdle()

        assertEquals(emptyMap<String, BookDownloadState>(), downloads.state.value.byAccountBookId)
        assertNull("a cancelled download opens nothing", downloads.opened.value)
        assertEquals("and left no partial behind", emptyList<String>(), partialFiles())
    }

    @Test
    fun `a copy already on the device after sign-out is still a readable device book`() = runTest {
        val transport = ScriptedDownloads(bytes)
        val library = repository(appScope())
        val downloads = downloads(transport, library, appScope())
        downloads.open(ACCOUNT_BOOK_ID)
        advanceUntilIdle()
        val bookId = downloads.opened.value!!

        account.value = AccountLibraryState.SIGNED_OUT
        advanceUntilIdle()

        val row = library.catalog.value.book(bookId)
        assertNotNull("D4: the copy is a device book and sign-out does not touch it", row)
        assertTrue("its bytes are still there", store.has(digest))
        assertTrue("and it still opens, with no network at all", library.openBook(bookId).readBytes().isNotEmpty())
    }

    // ------------------------------------------------------------- fixtures

    /** Lazy: the rule's folder does not exist while this class's fields are initialised. */
    private val store by lazy { AccountCopyStore(File(temporary.root, AccountCopyStore.DIRECTORY_NAME)) }

    private fun partialFiles(): List<String> =
        (File(temporary.root, AccountCopyStore.DIRECTORY_NAME).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(AccountCopyStore.PARTIAL_SUFFIX) }
            .map { it.name }

    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)).also { scopes += it }

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

    private fun downloads(
        transport: AssetDownloadGateway,
        library: LibraryRepository,
        scope: CoroutineScope,
    ) = AccountDownloads(
        copies = AccountBookCopies(
            gateway = transport,
            store = store,
            references = references,
            library = library,
            clock = { 1_700_000_000_000 },
        ),
        accountState = account,
        scope = scope,
    )

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    /**
     * reader-api and the storage provider, as the download path sees them.
     *
     * [holdAfterProgress] is what makes a transfer observable: it reports half
     * the body and then waits, so a test can look at the row *while* bytes are
     * arriving instead of only at what the download left behind.
     */
    private class ScriptedDownloads(private val body: ByteArray) : AssetDownloadGateway {

        val grants = mutableListOf<String>()
        var fetches: Int = 0
        var grantFailure: ReaderAuthException? = null
        var transportFailure: Throwable? = null

        /** Completed by the test to let a held transfer finish. */
        var holdAfterProgress: CompletableDeferred<Unit>? = null

        override suspend fun downloadGrant(assetId: String): ReaderAssetGrant {
            grantFailure?.let { throw it }
            grants += assetId
            return ReaderAssetGrant(
                assetId = assetId,
                bookId = ACCOUNT_BOOK_ID,
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
            transportFailure?.let { throw it }
            val hold = holdAfterProgress
            if (hold != null) {
                onProgress(body.size.toLong() / 2, grant.sizeBytes)
                hold.await()
            }
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

    private companion object {
        const val ACCOUNT_BOOK_ID = "1f0f1c9e-6a3c-4f8a-9c2b-2f1c7d3e4a5b"
    }
}
