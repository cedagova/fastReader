package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.account.FakePublicationImportGateway
import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.BookSource
import com.cedagova.fastreader.library.FakeDocumentGateway
import com.cedagova.fastreader.library.SourceOrigin
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.imports.PublicationImportStep
import com.cedagova.reader.library.imports.UploadConsent
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationImportStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-to-account flow (REQ-505, REQ-506, REQ-507), proved against a
 * scripted seam — no network, no storage provider, no Keystore.
 *
 * The first three tests are the ones this leaf exists for, and they are all
 * about what has *not* happened: tapping the action sends one policy read and
 * nothing else; declining sends nothing at all; and the only call that can put
 * a book's bytes on the wire carries an explicit consent. The stage half of the
 * acceptance — a real EPUB appearing on reader-web — is an owner test, recorded
 * on the PR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountImportsTest {

    private val gateway = FakePublicationImportGateway()
    private val documents = FakeDocumentGateway()
    private val records = FakeImportRecords()
    private val account = MutableStateFlow(signedIn())
    /**
     * The flow is process-scoped in the app, so it gets a scope of its own here
     * too — one that shares the test's scheduler, so `advanceUntilIdle` drives
     * its coroutines as well as the test body's. The test body runs in
     * `runTest(dispatcher)`'s own scope, which is what keeps its completion
     * from waiting on the sign-out collector that never ends.
     */
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var syncs = 0

    private val catalog = mutableMapOf<String, Book>()

    private val imports: AccountImports by lazy {
        AccountImports(
            gateway = gateway,
            records = records,
            sources = DeviceBookSources(documents),
            bookForId = catalog::get,
            onImportReady = { syncs++ },
            accountState = account,
            scope = scope,
            pollDelaysMs = listOf(10),
            pollAttempts = 3,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- nothing leaves the device before the tap ------------------------------------------

    @Test
    fun `tapping Add reads the policy, asks the question, and sends nothing about the book`() =
        runTest(dispatcher) {
            givenBook(sizeBytes = 1_000)

            imports.requestAdd(FICCIONES_ID)
            advanceUntilIdle()

            assertEquals(listOf("importPolicy()"), gateway.calls)
            assertTrue("no consent may have reached a sending call", gateway.consents.isEmpty())
            assertTrue("no import record may exist yet", records.stored.isEmpty())
            val state = imports.state.value.byDeviceBookId[FICCIONES_ID]
            assertEquals(BookImportState.Consent(sizeBytes = 1_000, maxSourceBytes = 52_428_800), state)
        }

    @Test
    fun `declining the question sends nothing, ever`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)

        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()
        imports.dismiss(FICCIONES_ID)
        advanceUntilIdle()

        assertEquals(listOf("importPolicy()"), gateway.calls)
        assertTrue(gateway.consents.isEmpty())
        assertTrue(records.stored.isEmpty())
        assertNull(imports.state.value.byDeviceBookId[FICCIONES_ID])
    }

    @Test
    fun `confirming is the only call that sends, and it carries the consent`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        gateway.steps += PublicationImportStep.Transferred(readyRecord())

        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()
        imports.confirmAdd(FICCIONES_ID)
        advanceUntilIdle()

        assertEquals(listOf("importPolicy()", "start(user-1)"), gateway.calls)
        assertEquals(listOf(UploadConsent.GRANTED), gateway.consents)
    }

    @Test
    fun `a confirm that does not follow a question is ignored`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)

        imports.confirmAdd(FICCIONES_ID)
        advanceUntilIdle()

        assertTrue("nothing may be sent without the question", gateway.calls.isEmpty())
        assertTrue(gateway.consents.isEmpty())
    }

    // ---- the policy decides, never a constant ----------------------------------------------

    @Test
    fun `the cap the question quotes is the deployment's own`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        gateway.policy = FakePublicationImportGateway.accepting(cap = 1_234_567)

        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()

        assertEquals(
            BookImportState.Consent(sizeBytes = 1_000, maxSourceBytes = 1_234_567),
            imports.state.value.byDeviceBookId[FICCIONES_ID],
        )
    }

    @Test
    fun `a file over the deployment's cap is refused with that cap and nothing is admitted`() =
        runTest(dispatcher) {
            givenBook(sizeBytes = 900)
            gateway.policy = FakePublicationImportGateway.accepting(cap = 800)

            imports.requestAdd(FICCIONES_ID)
            advanceUntilIdle()

            val refused = imports.state.value.byDeviceBookId[FICCIONES_ID] as BookImportState.Refused
            assertEquals(ImportProblem.Category(PublicationFailureCategory.TOO_LARGE), refused.problem)
            assertEquals(900L, refused.sizeBytes)
            assertEquals(800L, refused.maxSourceBytes)
            assertEquals(listOf("importPolicy()"), gateway.calls)
            assertTrue(records.stored.isEmpty())
        }

    @Test
    fun `a deployment that admits nothing replaces the action with its reason`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        gateway.policy = FakePublicationImportGateway.accepting(enabled = false)

        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()

        assertEquals(
            ImportsOff(FakePublicationImportGateway.REQUEST_ID),
            imports.state.value.disabled,
        )
        assertNull(imports.state.value.byDeviceBookId[FICCIONES_ID])
    }

    @Test
    fun `offline at the question needs a connection and queues no admission`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        gateway.policyFailure = ReaderAuthException.NetworkUnavailable(java.io.IOException("offline"))

        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()

        val refused = imports.state.value.byDeviceBookId[FICCIONES_ID] as BookImportState.Refused
        assertEquals(ImportProblem.NeedsConnection, refused.problem)
        assertTrue(records.stored.isEmpty())
    }

    // ---- the lifecycle -----------------------------------------------------------------------

    @Test
    fun `a ready import forgets its record and asks for the pass that brings the row`() =
        runTest(dispatcher) {
            givenBook(sizeBytes = 1_000)
            gateway.steps += PublicationImportStep.Transferred(record(PublicationImportStatus.VERIFYING_UPLOAD))
            gateway.refreshes += record(PublicationImportStatus.READY).copy(canonicalBookId = "book-1")

            addAndConfirm()

            assertNull("the shelf shows no verdict for a book that simply arrived",
                imports.state.value.byDeviceBookId[FICCIONES_ID])
            assertTrue("the record is spent", records.stored.isEmpty())
            assertEquals("one sync asked for, so the backend's row can arrive", 1, syncs)
        }

    @Test
    fun `a failed import shows the backend's own category and leaves the device book alone`() =
        runTest(dispatcher) {
            givenBook(sizeBytes = 1_000)
            gateway.steps += PublicationImportStep.Failed(
                record(PublicationImportStatus.FAILED).copy(
                    failureCategory = PublicationFailureCategory.PROTECTED,
                    failureRetryable = false,
                ),
                PublicationFailureCategory.PROTECTED,
            )

            addAndConfirm()

            val refused = imports.state.value.byDeviceBookId[FICCIONES_ID] as BookImportState.Refused
            assertEquals(ImportProblem.Category(PublicationFailureCategory.PROTECTED), refused.problem)
            assertEquals(false, refused.retryable)
            assertTrue("a terminal failure leaves nothing to resume", records.stored.isEmpty())
            assertEquals(0, syncs)
        }

    @Test
    fun `an interrupted transfer keeps its record so the next pass carries on`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        gateway.steps += PublicationImportStep.Interrupted(
            record(PublicationImportStatus.PENDING_UPLOAD).copy(uploadedOffset = 400),
        )

        addAndConfirm()

        val refused = imports.state.value.byDeviceBookId[FICCIONES_ID] as BookImportState.Refused
        assertEquals(ImportProblem.NeedsConnection, refused.problem)
        assertEquals(1, records.stored.size)
    }

    // ---- app death (REQ-507) -----------------------------------------------------------------

    @Test
    fun `a stored import is resumed as the same admission, never as a second one`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        records.stored += record(PublicationImportStatus.PENDING_UPLOAD)
        gateway.steps += PublicationImportStep.Transferred(record(PublicationImportStatus.READY))

        imports.resumeStoredImports()
        advanceUntilIdle()

        assertEquals(listOf("resume($CLIENT_IMPORT_ID)"), gateway.calls)
        assertTrue("a resume never starts a second import", gateway.calls.none { it.startsWith("start(") })
    }

    @Test
    fun `a stored import whose book has left the catalog is dropped rather than retried`() =
        runTest(dispatcher) {
            records.stored += record(PublicationImportStatus.PENDING_UPLOAD)

            imports.resumeStoredImports()
            advanceUntilIdle()

            assertTrue(records.stored.isEmpty())
            assertTrue(gateway.calls.none { it.startsWith("resume(") })
        }

    @Test
    fun `a terminal stored import is forgotten on the next pass`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        records.stored += record(PublicationImportStatus.READY)

        imports.resumeStoredImports()
        advanceUntilIdle()

        assertTrue(records.stored.isEmpty())
        assertTrue(gateway.calls.isEmpty())
    }

    // ---- cancelling and signing out ----------------------------------------------------------

    @Test
    fun `cancelling tells the backend, forgets the record and says so`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        records.stored += record(PublicationImportStatus.PENDING_UPLOAD)

        imports.cancelAdd(FICCIONES_ID)
        advanceUntilIdle()

        assertEquals(listOf(IMPORT_ID), gateway.cancelled)
        assertTrue(records.stored.isEmpty())
        val refused = imports.state.value.byDeviceBookId[FICCIONES_ID] as BookImportState.Refused
        assertEquals(ImportProblem.Category(PublicationFailureCategory.CANCELLED), refused.problem)
    }

    @Test
    fun `signing out ends every add and leaves the stored records for the next sign-in`() =
        runTest(dispatcher) {
            givenBook(sizeBytes = 1_000)
            records.stored += record(PublicationImportStatus.PENDING_UPLOAD)
            imports.requestAdd(FICCIONES_ID)
            advanceUntilIdle()

            records.signedIn = null
            account.value = AccountLibraryState.SIGNED_OUT
            advanceUntilIdle()

            assertEquals(AccountImportsState.NONE, imports.state.value)
            assertEquals("D4: the account's own document keeps them", 1, records.stored.size)
        }

    // ---- a book whose bytes cannot be reached ------------------------------------------------

    @Test
    fun `a book whose folder will not say how large it is cannot be added`() = runTest(dispatcher) {
        givenBook(sizeBytes = 1_000)
        documents.documents.getValue(URI).sizeOverride = -1
        catalog[FICCIONES_ID] = book(sizeBytes = -1)

        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()

        val refused = imports.state.value.byDeviceBookId[FICCIONES_ID] as BookImportState.Refused
        assertEquals(
            ImportProblem.SourceUnavailable(PublicationSourceProblem.SIZE_UNKNOWN),
            refused.problem,
        )
        assertTrue("nothing is asked of the backend for a file that cannot be measured", gateway.calls.isEmpty())
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun TestScope.addAndConfirm() {
        imports.requestAdd(FICCIONES_ID)
        advanceUntilIdle()
        imports.confirmAdd(FICCIONES_ID)
        advanceUntilIdle()
    }

    private fun givenBook(sizeBytes: Long) {
        documents.putDocument(URI, ByteArray(sizeBytes.toInt().coerceAtLeast(0)), "ficciones.epub")
        catalog[FICCIONES_ID] = book(sizeBytes)
    }

    private fun book(sizeBytes: Long) = Book(
        id = FICCIONES_ID,
        title = "Ficciones",
        author = "Jorge Luis Borges",
        sources = listOf(
            BookSource(
                uri = URI,
                origin = SourceOrigin.DIRECT_PICK,
                displayName = "ficciones.epub",
                sizeBytes = sizeBytes,
            ),
        ),
    )

    private fun record(status: PublicationImportStatus) = PublicationImportRecord(
        clientImportId = CLIENT_IMPORT_ID,
        accountId = "user-1",
        contentSha256 = FICCIONES_HEX,
        sizeBytes = 1_000,
        sourceFormat = PublicationFormat.EPUB,
        sourceMimeType = FakePublicationImportGateway.EPUB_MIME,
        importId = IMPORT_ID,
        status = status,
    )

    private fun readyRecord() = record(PublicationImportStatus.READY)

    private fun signedIn() = AccountLibraryState(phase = AccountSyncPhase.IDLE, userId = "user-1")

    /** The account document's import section, in memory. */
    private class FakeImportRecords : AccountImportRecords {
        val stored = mutableListOf<PublicationImportRecord>()
        var signedIn: String? = "user-1"

        override fun accountId(): String? = signedIn

        override suspend fun importRecords(): List<PublicationImportRecord> = stored.toList()

        override suspend fun putImportRecord(record: PublicationImportRecord) {
            val index = stored.indexOfFirst { it.clientImportId == record.clientImportId }
            if (index < 0) stored += record else stored[index] = record
        }

        override suspend fun dropImportRecord(clientImportId: String) {
            stored.removeAll { it.clientImportId == clientImportId }
        }
    }

    private companion object {
        const val FICCIONES_HEX = "f1cc10e500000000000000000000000000000000000000000000000000000000"
        const val FICCIONES_ID = "sha256:$FICCIONES_HEX"
        const val URI = "content://books/ficciones.epub"
        const val CLIENT_IMPORT_ID = "reader-import-v1-${FICCIONES_HEX}"
        const val IMPORT_ID = "9a3b1c2d-4e5f-4061-8172-839405a6b7c8"
    }
}
