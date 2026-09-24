package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.model.ReaderBook
import com.cedagova.reader.library.model.ReaderBookAsset
import com.cedagova.reader.library.model.ReaderBookAssetKind
import com.cedagova.reader.library.model.ReaderCapabilityAvailability
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderCoverStatus
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderLibraryItem
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderPortableLocationV1
import com.cedagova.reader.library.model.ReaderPortablePublicationV1
import com.cedagova.reader.library.model.ReaderProgress
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderServerAdmission
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncChange
import com.cedagova.reader.library.model.ReaderSyncConflict
import com.cedagova.reader.library.model.ReaderSyncConflictCode
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationResult
import com.cedagova.reader.library.model.ReaderSyncRejection
import com.cedagova.reader.library.model.ReaderSyncRejectionCode
import com.cedagova.reader.library.model.ReaderSyncStatus
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The engine's contract (REQ-502, REQ-503, REQ-504, REQ-516's queue rule),
 * proved against LEAF701's scripted gateway — no SDK, no network, no Keystore.
 *
 * The stage half of this leaf's acceptance (a real round trip against the
 * backend) belongs to LEAF703's device run: there is no surface to drive yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSyncEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gateway = FakeReaderLibraryGateway()
    private val session = MutableStateFlow<AccountSession>(AccountSession.Loading)
    private val directory: File by lazy { File(temporaryFolder.root, "account-library") }
    private val stores: AccountLibraryStores by lazy { FileAccountLibraryStores(directory) }
    private var keys = 0

    /**
     * The engine is process-scoped in the app, so it gets a scope of its own
     * here too — one that shares the test's scheduler, so `advanceUntilIdle`
     * drives the engine's own coroutines as well as the test body's.
     */
    private val dispatcher = StandardTestDispatcher()
    private val engineScope = TestScope(dispatcher)

    @After
    fun tearDown() = engineScope.cancel()

    private fun engine(): AccountSyncEngine = AccountSyncEngine(
        gateway = gateway,
        stores = stores,
        accountState = session,
        scope = engineScope,
        ioDispatcher = dispatcher,
        newIdempotencyKey = { "key-${++keys}" },
        now = { CLIENT_TIME },
    )

    // ------------------------------------------------------------- bootstrap

    @Test
    fun `bootstrap populates the rows and the cursor from the account's lists`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune"), item("book-2", "Emma")))
        gateway.progressResponses = queueOf(
            ReaderProgressListResponse(
                requestId = REQUEST_ID,
                progress = listOf(
                    ReaderProgress(
                        bookId = "book-1",
                        progressPercent = 41.5,
                        updatedAt = "2026-09-14T10:01:00Z",
                        location = ReaderPortableLocationV1(
                            publication = ReaderPortablePublicationV1.accountEpub("book-1"),
                            locator = locator("OEBPS/ch1.xhtml", 0.415),
                        ),
                    ),
                ),
            ),
        )
        gateway.deltaResponses = queueOf(deltas(latestCursor = "512"))

        val engine = engine()
        signIn("user-1")

        val state = engine.state.value
        assertEquals(listOf("Dune", "Emma"), state.books.map { it.title })
        assertEquals(41.5, state.books.first().progressPercent!!, 0.001)
        assertEquals(AccountSyncPhase.IDLE, state.phase)
        assertEquals("512", document("user-1").cursor)
        // The head is read before the lists, so a change admitted in between is
        // still after the cursor and arrives with the next delta read.
        assertEquals(
            listOf("syncCapability()", "deltas(0, 1)", "library()", "progress()"),
            gateway.calls,
        )
    }

    @Test
    fun `a delta upsert, delete and restore each move the row`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "2",
                nextCursor = "2",
                changes = listOf(
                    change("2", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", status = "finished")),
                ),
            ),
        )
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()
        assertEquals(ReaderLibraryStatus.FINISHED, engine.state.value.books.single().status)
        assertEquals("2", document("user-1").cursor)

        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "3",
                nextCursor = "3",
                changes = listOf(change("3", "book-1", ReaderMutationKind.DELETE, JsonObject(emptyMap()))),
            ),
        )
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()
        assertTrue("a removed book leaves the shelf", engine.state.value.books.isEmpty())

        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "4",
                nextCursor = "4",
                changes = listOf(
                    change("4", "book-1", ReaderMutationKind.RESTORE, itemPayload("book-1", "Dune", status = "reading")),
                ),
            ),
        )
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()
        assertEquals(listOf("Dune"), engine.state.value.books.map { it.title })
        assertEquals(ReaderLibraryStatus.READING, engine.state.value.books.single().status)
    }

    @Test
    fun `the stream is read until has_more is false`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.calls.clear()
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "3",
                nextCursor = "2",
                hasMore = true,
                changes = listOf(change("2", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", status = "finished"))),
            ),
            deltas(latestCursor = "3", nextCursor = "3"),
        )
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(listOf("deltas(1, 100)", "deltas(2, 100)"), gateway.calls)
        assertEquals("3", document("user-1").cursor)
    }

    // ---------------------------------------------------------------- outbox

    @Test
    fun `the same entry sent twice is applied once and replayed once, and the row does not move`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        engine.recordStatus("book-1", ReaderLibraryStatus.FINISHED)
        advanceUntilIdle()
        val queuedKey = gateway.submitted.single().single().idempotencyKey

        // The store as it stood with the entry still queued: this is what a
        // process death between the send and the save leaves behind.
        val beforeDrain = storeFile("user-1").readText()

        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.APPLIED, revision = 8, payload = itemPayload("book-1", "Dune", status = "finished"), key = queuedKey),
        )
        engine.requestSync(AccountSyncTrigger.MANUAL_REFRESH)
        advanceUntilIdle()
        val applied = engine.state.value.books.single()
        assertEquals(ReaderLibraryStatus.FINISHED, applied.status)
        assertEquals(0, engine.state.value.queued)

        // Rewind the store to before the save and start a second engine on it:
        // the key must come back out of the document, not out of a new UUID.
        storeFile("user-1").writeText(beforeDrain)
        session.value = AccountSession.Loading
        advanceUntilIdle()
        gateway.submitted.clear()
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.REPLAYED, revision = 8, payload = itemPayload("book-1", "Dune", status = "finished"), key = queuedKey, admission = ReaderServerAdmission.REPLAYED),
        )
        val second = engine()
        signIn("user-1")

        assertEquals(
            "the retry must carry the original key, so the backend replays it",
            listOf(queuedKey),
            gateway.submitted.single().map { it.idempotencyKey },
        )
        assertEquals(applied, second.state.value.books.single())
        assertEquals(0, second.state.value.queued)
    }

    @Test
    fun `the outbox drains before the stream is read`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.calls.clear()
        admit("key-1", ReaderMutationKind.DELETE)
        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        assertEquals(listOf("applyMutations(1)", "deltas(1, 100)"), gateway.calls)
    }

    @Test
    fun `a rejection is surfaced with the backend's own code and is not retried`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.answerMutations(
            ReaderSyncMutationResult(
                idempotencyKey = "key-1",
                resourceType = ReaderResourceType.LIBRARY_ITEM,
                resourceId = "book-1",
                mutationKind = ReaderMutationKind.UPSERT,
                status = ReaderSyncStatus.REJECTED,
                rejection = ReaderSyncRejection(
                    code = ReaderSyncRejectionCode.UNSUPPORTED_MUTATION,
                    detail = "this resource does not accept that",
                    retryable = false,
                ),
            ),
        )
        engine.recordStatus("book-1", ReaderLibraryStatus.ARCHIVED)
        advanceUntilIdle()

        val error = engine.state.value.lastError
        assertTrue(error is AccountSyncError.Rejected)
        assertEquals("unsupported_mutation", (error as AccountSyncError.Rejected).code)
        assertEquals("book-1", error.resourceId)
        assertEquals("a refused entry is not retried", 0, engine.state.value.queued)
    }

    @Test
    fun `a conflict result is adopted, never prompted`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.answerMutations(
            ReaderSyncMutationResult(
                idempotencyKey = "key-1",
                resourceType = ReaderResourceType.LIBRARY_ITEM,
                resourceId = "book-1",
                mutationKind = ReaderMutationKind.UPSERT,
                status = ReaderSyncStatus.CONFLICT,
                canonicalPayload = itemPayload("book-1", "Dune", status = "archived"),
                conflict = ReaderSyncConflict(
                    conflictId = "c-1",
                    code = ReaderSyncConflictCode.REVISION_CONFLICT,
                    remoteRevision = 12,
                    canonicalPayload = itemPayload("book-1", "Dune", status = "archived"),
                ),
            ),
        )
        engine.recordStatus("book-1", ReaderLibraryStatus.FINISHED)
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals("the backend's value wins, with no decision asked of anybody", ReaderLibraryStatus.ARCHIVED, row.status)
        assertEquals(12L, row.revision)
        assertNull(engine.state.value.lastError)
        assertEquals(0, engine.state.value.queued)
    }

    /**
     * The four *library* actions the shelf offers produce `library_item`
     * envelopes and nothing else.
     *
     * Deliberately named for what it drives. It used to be called "nothing but
     * library_item mutations is ever produced", which read as a global invariant
     * over the engine — and stopped being one in #120, when a published position
     * became a second kind of envelope this class builds. The test itself never
     * covered that: it drives remove, undo, open and finish, none of which is a
     * position flush, so it kept passing while the claim in its name went false.
     * A privacy promise was resting on that name (row 7 of
     * `docs/privacy-statement.md`), which is the whole reason the rename matters.
     *
     * The invariant over the *engine* is the test below this one.
     */
    @Test
    fun `a library mutation is only ever a library_item envelope`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        // Each action is answered, so each drains: an envelope the backend does
        // not answer is deliberately kept and retried under the same key, which
        // would otherwise pile the earlier ones onto every later batch.
        admit("key-1", ReaderMutationKind.DELETE)
        engine.removeFromAccount("book-1")
        advanceUntilIdle()
        admit("key-2", ReaderMutationKind.RESTORE)
        engine.undoRemove("book-1")
        advanceUntilIdle()
        admit("key-3", ReaderMutationKind.UPSERT)
        engine.recordOpened("book-1")
        advanceUntilIdle()
        admit("key-4", ReaderMutationKind.UPSERT)
        engine.recordFinished("book-1")
        advanceUntilIdle()

        val envelopes = gateway.submitted.flatten()
        assertTrue(envelopes.isNotEmpty())
        envelopes.forEach { assertEquals(ReaderResourceType.LIBRARY_ITEM, it.resourceType) }
        assertEquals(
            listOf(
                ReaderMutationKind.DELETE,
                ReaderMutationKind.RESTORE,
                ReaderMutationKind.UPSERT,
                ReaderMutationKind.UPSERT,
            ),
            envelopes.map { it.mutationKind },
        )
    }

    /**
     * The invariant row 7 of `docs/privacy-statement.md` actually rests on: over
     * every operation this engine offers, the set of resource types it puts on
     * the wire is exactly `library_item` and `reading_progress`.
     *
     * An equality over the whole set, not a per-envelope check against a list of
     * allowed members: a `profile`, `settings`, `note` or `bookmark` envelope
     * added later fails this, and so does silently dropping the position
     * envelope. It drives all five operations in one run — remove, undo, open,
     * finish *and* a position — so the assertion is about a batch that really
     * contains both kinds rather than about one kind in isolation.
     */
    @Test
    fun `only library_item and reading_progress envelopes are ever produced`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        admit("key-1", ReaderMutationKind.DELETE)
        engine.removeFromAccount("book-1")
        advanceUntilIdle()
        admit("key-2", ReaderMutationKind.RESTORE)
        engine.undoRemove("book-1")
        advanceUntilIdle()
        admit("key-3", ReaderMutationKind.UPSERT)
        engine.recordOpened("book-1")
        advanceUntilIdle()
        admit("key-4", ReaderMutationKind.UPSERT)
        engine.recordFinished("book-1")
        advanceUntilIdle()
        admit("key-5", ReaderMutationKind.UPSERT)
        engine.recordPosition("book-1", position(href = "OEBPS/ch3.xhtml", percent = 40))
        advanceUntilIdle()

        val envelopes = gateway.submitted.flatten()
        assertEquals(5, envelopes.size)
        assertEquals(
            "the engine can put no settings, note, bookmark or profile envelope on the wire",
            setOf(ReaderResourceType.LIBRARY_ITEM, ReaderResourceType.READING_PROGRESS),
            envelopes.map { it.resourceType }.toSet(),
        )
        assertEquals(
            "exactly one of the five is a position",
            1,
            envelopes.count { it.resourceType == ReaderResourceType.READING_PROGRESS },
        )
    }

    /**
     * A host record — FastReader's answered resume offer, its copy references —
     * is stored under the one writer and adds no envelope to the outbox and sends
     * nothing at all. Asserted beside the envelope invariant above because this
     * is the write most likely to grow a wire call by accident: it is the only
     * thing the engine writes to the account document without queueing (#147).
     */
    @Test
    fun `a host record is stored verbatim and sends nothing`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")
        gateway.calls.clear()

        engine.updateBookHostRecord("book-1", "resumeOfferSettledFor") { JsonPrimitive("7:2026-09-20T10:00:00Z") }
        engine.updateHostRecord("copies") { buildJsonArray { add(JsonPrimitive("a")) } }
        engine.updateBookHostRecord("book-unknown", "note") { JsonPrimitive("x") }
        advanceUntilIdle()

        assertTrue("a host record must send nothing at all, got ${gateway.calls}", gateway.calls.isEmpty())
        assertEquals(0, engine.state.value.queued)
        val stored = (stores.forUser("user-1").load() as AccountLibraryLoad.Loaded).document
        assertEquals(JsonPrimitive("7:2026-09-20T10:00:00Z"), stored.book("book-1")?.host?.get("resumeOfferSettledFor"))
        assertEquals(buildJsonArray { add(JsonPrimitive("a")) }, stored.host["copies"])
        assertNull("a row the account does not hold is not invented", stored.book("book-unknown"))
        assertEquals(buildJsonArray { add(JsonPrimitive("a")) }, engine.hostRecord("copies"))
        // Written back at the level it was read from, never under a `host` key.
        val raw = storeFile("user-1").readText()
        assertTrue(raw.contains("\"resumeOfferSettledFor\":\"7:2026-09-20T10:00:00Z\""))
        assertFalse(raw.contains("\"host\""))
    }

    /** A host record survives an adoption of the row it sits on: the payload does not carry it. */
    @Test
    fun `adopting a canonical payload keeps the row's host records`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")
        engine.updateBookHostRecord("book-1", "resumeOfferSettledFor") { JsonPrimitive("7:x") }

        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "2",
                changes = listOf(change("2", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune II", "reading"))),
            ),
        )
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals("Dune II", row.title)
        assertEquals(JsonPrimitive("7:x"), row.host["resumeOfferSettledFor"])
    }

    /** Signed out, a host record is neither read nor written. */
    @Test
    fun `host records are unavailable while signed out`() = runTest(dispatcher) {
        val engine = engine()
        session.value = AccountSession.SignedOut
        advanceUntilIdle()

        engine.updateHostRecord("copies") { JsonPrimitive("x") }

        assertNull(engine.hostRecord("copies"))
        assertNull(engine.accountId())
    }

    // ------------------------------------------------------- cursor expiry

    @Test
    fun `an expired cursor re-bootstraps and sends nothing new`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.APPLIED, revision = 2, payload = itemPayload("book-1", "Dune", status = "finished"), key = "key-1"),
        )
        engine.recordStatus("book-1", ReaderLibraryStatus.FINISHED)
        advanceUntilIdle()
        assertEquals(1, gateway.submitted.size)

        gateway.calls.clear()
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune", status = ReaderLibraryStatus.FINISHED)))
        gateway.deltaResponses = queueOf(
            deltas(status = ReaderDeltaStatus.CURSOR_EXPIRED, latestCursor = "77", rebootstrapRequired = true),
            deltas(latestCursor = "77"),
        )
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals("nothing may be re-sent by a re-bootstrap", 1, gateway.submitted.size)
        assertEquals(listOf("Dune"), engine.state.value.books.map { it.title })
        assertEquals("77", document("user-1").cursor)
        assertFalse(gateway.calls.any { it.startsWith("applyMutations") })
    }

    // -------------------------------------------- merge, not rebuild (#151)

    /**
     * §7.2: a re-bootstrap merges the lists into the stored rows. A book still in
     * the account keeps the host's records; a book gone from the account goes,
     * its host records with it; the document's own host records stay.
     */
    @Test
    fun `a re-bootstrap keeps the host records of books still present and drops rows the snapshot lacks`() =
        runTest(dispatcher) {
            gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune"), item("book-2", "Emma")))
            gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
            val engine = engine()
            signIn("user-1")
            engine.updateBookHostRecord("book-1", "resumeOfferSettledFor") { JsonPrimitive("7:x") }
            engine.updateBookHostRecord("book-2", "resumeOfferSettledFor") { JsonPrimitive("8:y") }
            engine.updateHostRecord("copies") { JsonPrimitive("kept") }

            gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune (revised)")))
            expireCursorOnNextRead()
            engine.refresh()
            advanceUntilIdle()

            val stored = document("user-1")
            assertEquals(listOf("book-1"), stored.books.map { it.bookId })
            assertEquals("the snapshot's fields are the backend's", "Dune (revised)", stored.book("book-1")!!.title)
            assertEquals(JsonPrimitive("7:x"), stored.book("book-1")!!.host["resumeOfferSettledFor"])
            assertNull("a book gone from the account goes, host records with it", stored.book("book-2"))
            assertEquals(JsonPrimitive("kept"), stored.host["copies"])
            assertEquals("77", stored.cursor)
        }

    /** §7.2 step 5: a removal still queued when the cursor expires stays off the shelf. */
    @Test
    fun `a re-bootstrap re-applies a still-queued removal on top of the snapshot`() = runTest(dispatcher) {
        val engine = signedInEngine()
        // No answer for the removal: it stays queued under its key.
        gateway.mutationResponses = queueOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID))
        expireCursorOnNextRead()

        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        val stored = document("user-1")
        assertEquals(listOf("key-1"), stored.outbox.map { it.idempotencyKey })
        assertTrue("the queued removal is re-applied on the snapshot's row", stored.book("book-1")!!.removed)
        assertTrue(engine.state.value.books.isEmpty())
        assertEquals("77", stored.cursor)
    }

    /** §7.2 step 5: a queued status is re-applied over the snapshot's, and sent once with its original key. */
    @Test
    fun `a re-bootstrap re-applies a still-queued status and sends it once under its key`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.mutationResponses = queueOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID))
        expireCursorOnNextRead()

        engine.recordOpened("book-1")
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(ReaderLibraryStatus.READING, row.status)
        assertEquals(CLIENT_TIME, row.lastOpenedAt)
        assertEquals(1, engine.state.value.queued)
        assertEquals(listOf("key-1"), gateway.submitted.flatten().map { it.idempotencyKey })
    }

    /**
     * §7.2/§7.3 (#147, #149): a list read carries no revision, so the revision
     * the backend already stated for a book is kept — and a stream change no
     * newer than it still cannot revert the row after a re-bootstrap.
     */
    @Test
    fun `a re-bootstrap keeps the known revision so a stale stream change cannot revert the row`() =
        runTest(dispatcher) {
            val engine = engineAtRevision8()
            gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune", status = ReaderLibraryStatus.FINISHED)))
            expireCursorOnNextRead()
            engine.refresh()
            advanceUntilIdle()
            assertEquals(8L, document("user-1").book("book-1")!!.revision)

            gateway.deltaResponses = queueOf(
                deltas(
                    latestCursor = "80",
                    changes = listOf(
                        change("78", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"), revision = 8),
                    ),
                ),
            )
            engine.refresh()
            advanceUntilIdle()

            assertEquals(ReaderLibraryStatus.FINISHED, document("user-1").book("book-1")!!.status)
            assertEquals("80", document("user-1").cursor)
        }

    // --------------------------------------------- refused changes (#151)

    /** A refused status leaves the shelf: the repair read restores the backend's row, host records kept. */
    @Test
    fun `a non-retryable rejection restores the row to the backend's state`() = runTest(dispatcher) {
        val engine = signedInEngine()
        engine.updateBookHostRecord("book-1", "resumeOfferSettledFor") { JsonPrimitive("7:x") }
        gateway.calls.clear()
        gateway.answerMutations(rejected("key-1", ReaderMutationKind.UPSERT))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "5"))

        engine.recordStatus("book-1", ReaderLibraryStatus.ARCHIVED)
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals("the refused status is undone", ReaderLibraryStatus.QUEUED, row.status)
        assertEquals(JsonPrimitive("7:x"), row.host["resumeOfferSettledFor"])
        assertEquals(0, engine.state.value.queued)
        assertTrue(engine.state.value.lastError is AccountSyncError.Rejected)
        assertEquals(
            listOf("applyMutations(1)", "deltas(0, 1)", "library()", "progress()"),
            gateway.calls,
        )
    }

    /** A refused removal puts the book back on the shelf. */
    @Test
    fun `a rejected removal puts the book back on the shelf`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(rejected("key-1", ReaderMutationKind.DELETE))

        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        assertFalse(document("user-1").book("book-1")!!.removed)
        assertEquals(listOf("Dune"), engine.state.value.books.map { it.title })
    }

    /** The repair read undoes only the refused change: another one still queued is re-applied. */
    @Test
    fun `the repair after a rejection keeps every other queued change`() = runTest(dispatcher) {
        val engine = signedInEngine()
        // First run: neither change is answered, both stay queued.
        gateway.mutationResponses = queueOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID))
        engine.recordStatus("book-1", ReaderLibraryStatus.ARCHIVED)
        engine.removeFromAccount("book-1")
        advanceUntilIdle()
        assertEquals(2, engine.state.value.queued)

        // Next run: the status is refused for good; the removal is still unanswered.
        gateway.answerMutations(rejected("key-1", ReaderMutationKind.UPSERT))
        engine.refresh()
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(listOf("key-2"), document("user-1").outbox.map { it.idempotencyKey })
        assertEquals("the refused status is undone", ReaderLibraryStatus.QUEUED, row.status)
        assertTrue("the queued removal still holds", row.removed)
    }

    // ------------------------------------------------------ capability gate

    @Test
    fun `an unavailable capability defers with the reason and sends no library request`() = runTest(dispatcher) {
        gateway.capability = ReaderSyncCapability(
            availability = ReaderCapabilityAvailability.UNAVAILABLE,
            reason = ReaderCapabilityReason.SERVICE_NOT_ENABLED,
        )
        val engine = engine()
        signIn("user-1")

        assertEquals(listOf("syncCapability()"), gateway.calls)
        assertEquals(AccountSyncPhase.DEFERRED, engine.state.value.phase)
        assertEquals(ReaderCapabilityReason.SERVICE_NOT_ENABLED, engine.state.value.capabilityReason)
        assertNull("an unavailable capability is not an error", engine.state.value.lastError)
        assertNull("and never a sign-out", document("user-1").cursor)
    }

    @Test
    fun `an undeclared capability is unavailable, not an error`() = runTest(dispatcher) {
        gateway.capability = ReaderSyncCapability.UNDECLARED
        val engine = engine()
        signIn("user-1")

        assertEquals(listOf("syncCapability()"), gateway.calls)
        assertEquals(AccountSyncPhase.DEFERRED, engine.state.value.phase)
        assertEquals(ReaderCapabilityReason.UNKNOWN, engine.state.value.capabilityReason)
    }

    // -------------------------------------------------------------- offline

    @Test
    fun `offline keeps the queue and the last known rows`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.nextFailure = ReaderAuthException.NetworkUnavailable(java.io.IOException("no network"))
        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        assertEquals(AccountSyncPhase.OFFLINE, engine.state.value.phase)
        assertEquals(AccountSyncError.NetworkUnavailable, engine.state.value.lastError)
        assertEquals("the queued removal is kept", 1, engine.state.value.queued)
        assertEquals("key-1", document("user-1").outbox.single().idempotencyKey)
    }

    // -------------------------------------------------------------- D4

    @Test
    fun `signing out keeps the store and takes the account rows off the shelf`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")
        assertTrue(engine.state.value.books.isNotEmpty())

        session.value = AccountSession.SignedOut
        advanceUntilIdle()

        assertEquals(AccountSyncPhase.SIGNED_OUT, engine.state.value.phase)
        assertTrue(engine.state.value.books.isEmpty())
        assertTrue("sign-out deletes nothing", storeFile("user-1").isFile)
        assertEquals(listOf("Dune"), document("user-1").books.map { it.title })
    }

    @Test
    fun `signing in again to the same account restores the rows and sends the held queue once`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.nextFailure = ReaderAuthException.NetworkUnavailable(java.io.IOException("no network"))
        engine.removeFromAccount("book-1")
        advanceUntilIdle()
        assertEquals(1, document("user-1").outbox.size)

        session.value = AccountSession.SignedOut
        advanceUntilIdle()
        gateway.submitted.clear()
        admit("key-1", ReaderMutationKind.DELETE)
        gateway.libraryResponses = queueOf(libraryOf())
        signIn("user-1")

        assertEquals(listOf("key-1"), gateway.submitted.single().map { it.idempotencyKey })
        assertEquals("the queue is admitted once", 0, document("user-1").outbox.size)
        assertEquals(AccountSyncPhase.IDLE, engine.state.value.phase)
    }

    @Test
    fun `a different account starts empty and discards the previous account's held queue`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.nextFailure = ReaderAuthException.NetworkUnavailable(java.io.IOException("no network"))
        engine.removeFromAccount("book-1")
        advanceUntilIdle()
        assertEquals(1, document("user-1").outbox.size)

        gateway.submitted.clear()
        gateway.libraryResponses = queueOf(libraryOf(item("book-9", "Persuasion")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "40"))
        signIn("user-2")

        assertEquals(listOf("Persuasion"), engine.state.value.books.map { it.title })
        assertEquals("the other account's queue must never be sent", 0, gateway.submitted.size)
        assertEquals("user-1's queue is discarded", 0, document("user-1").outbox.size)
        assertEquals("but user-1's rows are left alone", listOf("Dune"), document("user-1").books.map { it.title })
    }

    @Test
    fun `a session the backend rejects ends in the signed-out state with its reason`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")

        gateway.nextFailure = ReaderAuthException.SignedOut(code = "session_revoked", requestId = "req-9")
        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(AccountSyncPhase.SIGNED_OUT, engine.state.value.phase)
        assertEquals(
            AccountSyncError.SessionGone("session_revoked", "req-9"),
            engine.state.value.lastError,
        )
        assertTrue("D4 deletes nothing", storeFile("user-1").isFile)

        // `:reader-auth` drops the session behind this; the reason survives that
        // transition, and is shown once.
        session.value = AccountSession.SignedOut
        advanceUntilIdle()
        assertEquals(
            AccountSyncError.SessionGone("session_revoked", "req-9"),
            engine.state.value.lastError,
        )
    }

    // --------------------------------------------- portable position (#120)

    /**
     * A position is published as a `reading_progress` upsert carrying exactly the
     * portable location — and both the envelope and the location's publication
     * are the account's book id.
     */
    @Test
    fun `a published position is a reading_progress upsert with the portable locator`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            result(
                "book-1",
                ReaderSyncStatus.APPLIED,
                revision = 3,
                payload = JsonObject(emptyMap()),
                key = "key-1",
                kind = ReaderMutationKind.UPSERT,
            ),
        )

        engine.recordPosition("book-1", position(href = "OEBPS/ch3.xhtml", percent = 40))
        advanceUntilIdle()

        val envelope = gateway.submitted.flatten().single()
        assertEquals(ReaderResourceType.READING_PROGRESS, envelope.resourceType)
        assertEquals(ReaderMutationKind.UPSERT, envelope.mutationKind)
        assertEquals("book-1", envelope.resourceId)
        assertEquals(
            setOf("chapter_title", "location", "progress_percent"),
            envelope.payload.keys,
        )
        val location = envelope.payload["location"] as JsonObject
        assertEquals(
            "OEBPS/ch3.xhtml",
            (location["locator"] as JsonObject)["href"]!!.toString().trim('"'),
        )
        assertEquals(
            "reader-api rejects a publication_id that is not the envelope's resource_id",
            envelope.resourceId,
            (location["publication"] as JsonObject)["publication_id"]!!.toString().trim('"'),
        )
    }

    /**
     * REQ-512, at the level where an envelope is actually built: over a long run
     * of publishes, no key of any `reading_progress` payload is one of
     * FastReader's own values.
     */
    @Test
    fun `no published position payload ever carries a token index or a speed`() = runTest(dispatcher) {
        val engine = signedInEngine()
        repeat(12) { step ->
            admitPosition("key-${step + 1}", revision = (step + 2).toLong())
            engine.recordPosition("book-1", position(href = "OEBPS/ch$step.xhtml", percent = step * 8))
            advanceUntilIdle()
        }

        val payloads = gateway.submitted.flatten().map { it.payload }
        assertEquals(12, payloads.size)
        val locations = payloads.map { it["location"] as JsonObject }
        assertEquals(setOf("chapter_title", "location", "progress_percent"), payloads.flatMap { it.keys }.toSet())
        assertEquals(setOf("contract_version", "publication", "locator"), locations.flatMap { it.keys }.toSet())
        assertEquals(
            setOf("publication_id", "format", "media_type", "source"),
            locations.flatMap { (it["publication"] as JsonObject).keys }.toSet(),
        )
        assertEquals(
            setOf("contract_version", "format", "href", "progression"),
            locations.flatMap { (it["locator"] as JsonObject).keys }.toSet(),
        )
    }

    /** AD-25's guard: an unchanged section and percent says nothing new, so nothing is sent. */
    @Test
    fun `a flush that changed neither section nor whole percent publishes nothing`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.APPLIED, revision = 3, payload = JsonObject(emptyMap()), key = "key-1", kind = ReaderMutationKind.UPSERT),
        )

        engine.recordPosition("book-1", position("OEBPS/ch3.xhtml", 40))
        advanceUntilIdle()
        // The reader moved on a few words: a new fraction, the same whole percent
        // and the same section. This is the case that would otherwise make a long
        // chapter a stream of mutations.
        engine.recordPosition("book-1", position("OEBPS/ch3.xhtml", 40))
        engine.recordPosition("book-1", position("OEBPS/ch3.xhtml", 40))
        advanceUntilIdle()

        assertEquals("one publish, not three", 1, gateway.submitted.flatten().size)
    }

    /** A new section publishes even at the same percent; a new percent publishes too. */
    @Test
    fun `a new section or a new whole percent each publish`() = runTest(dispatcher) {
        val engine = signedInEngine()

        // Each publish is answered before the next, so an unanswered envelope is
        // never kept and retried onto a later batch (see `admit`'s note).
        admitPosition("key-1", revision = 2)
        engine.recordPosition("book-1", position("OEBPS/ch3.xhtml", 40))
        advanceUntilIdle()
        admitPosition("key-2", revision = 3)
        engine.recordPosition("book-1", position("OEBPS/ch4.xhtml", 40))
        advanceUntilIdle()
        admitPosition("key-3", revision = 4)
        engine.recordPosition("book-1", position("OEBPS/ch4.xhtml", 41))
        advanceUntilIdle()

        assertEquals(3, gateway.submitted.flatten().size)
    }

    /**
     * `causal-progress-can-move-backward`: reading backwards is published exactly
     * like reading forwards, and no client-side rule suppresses it.
     *
     * This is the test that would fail if anybody ever added a `max` or a
     * furthest-wins comparison to the publish path.
     */
    @Test
    fun `a position that moves backwards is published like any other`() = runTest(dispatcher) {
        val engine = signedInEngine()

        admitPosition("key-1", revision = 2)
        engine.recordPosition("book-1", position("OEBPS/ch8.xhtml", 80))
        advanceUntilIdle()
        admitPosition("key-2", revision = 3)
        engine.recordPosition("book-1", position("OEBPS/ch2.xhtml", 12))
        advanceUntilIdle()

        val percents = gateway.submitted.flatten().map {
            (it.payload["progress_percent"])!!.toString().toDouble()
        }
        assertEquals("the backward move is sent, second and unaltered", listOf(80.0, 12.0), percents)
    }

    /** Signed out there is no account to publish to, and nothing is queued. */
    @Test
    fun `nothing is published while signed out`() = runTest(dispatcher) {
        val engine = engine()
        advanceUntilIdle()

        engine.recordPosition("book-1", position("OEBPS/ch3.xhtml", 40))
        advanceUntilIdle()

        assertTrue(gateway.submitted.isEmpty())
        assertEquals(AccountSyncPhase.SIGNED_OUT, engine.state.value.phase)
    }

    /** A position envelope quotes the *progress* resource's revision, not the library item's. */
    @Test
    fun `a published position quotes the progress revision and not the library item's`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(
            deltas(latestCursor = "1"),
            deltas(
                latestCursor = "7",
                changes = listOf(progressChange("7", "book-1", revision = 5, percent = 30.0, href = "OEBPS/ch2.xhtml")),
            ),
        )
        val engine = engine()
        signIn("user-1")
        engine.refresh()
        advanceUntilIdle()
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.APPLIED, revision = 6, payload = JsonObject(emptyMap()), key = "key-1", kind = ReaderMutationKind.UPSERT),
        )

        engine.recordPosition("book-1", position("OEBPS/ch4.xhtml", 44))
        advanceUntilIdle()

        assertEquals(5L, gateway.submitted.flatten().single().baseRevision)
    }

    // ---- consuming

    /**
     * The clause carried into this increment from 001 (REQ-502): a position
     * changed on another device is reflected on the next **foreground**.
     *
     * Driven through `AccountSyncTrigger.FOREGROUND` on purpose, rather than a
     * manual refresh, because that is the trigger the requirement names.
     */
    @Test
    fun `a position changed on another device is reflected on the next foreground`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(
            deltas(latestCursor = "1"),
            deltas(
                latestCursor = "9",
                changes = listOf(progressChange("9", "book-1", revision = 4, percent = 62.5, href = "OEBPS/ch8.xhtml")),
            ),
        )
        val engine = engine()
        signIn("user-1")

        engine.requestSync(AccountSyncTrigger.FOREGROUND)
        advanceUntilIdle()

        val remote = engine.state.value.books.single().remotePosition!!
        assertEquals("OEBPS/ch8.xhtml", remote.href)
        assertEquals(0.625, remote.progression!!, 1e-9)
        assertEquals(62.5, remote.percent!!, 1e-9)
        assertEquals(4L, remote.revision)
        assertEquals(SERVER_TIME, remote.serverAdmittedAt)
        assertNull("a record this app could place is not an error", engine.state.value.lastError)
    }

    /** The bootstrap's progress list carries the location's locator too, not only the percentage. */
    @Test
    fun `the bootstrap stores the portable locator from the progress list`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.progressResponses = queueOf(
            ReaderProgressListResponse(
                requestId = REQUEST_ID,
                progress = listOf(
                    ReaderProgress(
                        bookId = "book-1",
                        progressPercent = 33.0,
                        updatedAt = "2026-09-20T08:00:00Z",
                        location = ReaderPortableLocationV1(
                            publication = ReaderPortablePublicationV1.accountEpub("book-1"),
                            locator = locator("OEBPS/ch3.xhtml", 0.33),
                        ),
                        chapterTitle = "Chapter Three",
                    ),
                ),
            ),
        )
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))

        val engine = engine()
        signIn("user-1")

        val remote = engine.state.value.books.single().remotePosition!!
        assertEquals("OEBPS/ch3.xhtml", remote.href)
        assertEquals("Chapter Three", remote.chapterTitle)
        assertEquals(0.33, remote.progression!!, 1e-9)
        assertEquals("2026-09-20T08:00:00Z", remote.updatedAt)
    }

    /**
     * A remote position that moves the row backwards is adopted as it stands.
     *
     * The backend admitted it, so it is the answer; nothing here compares it with
     * what was stored.
     */
    @Test
    fun `a remote position that moves backwards is adopted, not filtered`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(
            deltas(latestCursor = "1"),
            deltas(
                latestCursor = "4",
                changes = listOf(
                    progressChange("3", "book-1", revision = 3, percent = 80.0, href = "OEBPS/ch8.xhtml"),
                    progressChange("4", "book-1", revision = 4, percent = 12.0, href = "OEBPS/ch2.xhtml"),
                ),
            ),
        )
        val engine = engine()
        signIn("user-1")

        engine.refresh()
        advanceUntilIdle()

        val remote = engine.state.value.books.single().remotePosition!!
        assertEquals("the later admission stands, though it is further back", 12.0, remote.percent!!, 1e-9)
        assertEquals("OEBPS/ch2.xhtml", remote.href)
        assertEquals(4L, remote.revision)
    }

    // ---- this device's own position (#140)

    /**
     * #140's acceptance: publish at 40 %, the backend admits it at revision 5,
     * and the stream later delivers that same change back. The stream names no
     * originating client, so what recognises the echo is the contract's revision
     * rule — and the row records that the position the account holds is this
     * device's own, which is what keeps the resume offer from presenting it as
     * another device's after a rewind.
     */
    @Test
    fun `this device's own admitted position echoed by the stream stays marked as its own`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            positionResult("key-1", ReaderSyncStatus.APPLIED, revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml"),
        )
        engine.recordPosition("book-1", position(href = "OEBPS/ch4.xhtml", percent = 40))
        advanceUntilIdle()

        val admitted = engine.state.value.books.single()
        assertEquals(5L, admitted.remotePosition!!.revision)
        assertEquals(
            "the admitted result is this device's own position (§7.4)",
            admitted.remotePosition!!.changeKey,
            admitted.ownPositionChangeKey,
        )

        // The reader rewinds to 30 % without a new publish; the echo arrives.
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "5",
                changes = listOf(progressChange("5", "book-1", revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml")),
            ),
        )
        engine.refresh()
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals(admitted.remotePosition, row.remotePosition)
        assertEquals(
            "the echo is this device's own change, never another device's",
            row.remotePosition!!.changeKey,
            row.ownPositionChangeKey,
        )
    }

    /**
     * Client contract §7.3: a stream change is applied only when its revision is
     * newer than the one held for the resource. Old code applied every change,
     * so a stale revision moved the held position back.
     */
    @Test
    fun `a stream position no newer than the held revision is not applied`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(
            deltas(latestCursor = "1"),
            deltas(
                latestCursor = "7",
                changes = listOf(
                    progressChange("6", "book-1", revision = 6, percent = 62.0, href = "OEBPS/ch6.xhtml"),
                    progressChange("7", "book-1", revision = 5, percent = 30.0, href = "OEBPS/ch3.xhtml"),
                    progressChange("8", "book-1", revision = 6, percent = 31.0, href = "OEBPS/ch3.xhtml"),
                ),
            ),
        )
        val engine = engine()
        signIn("user-1")

        engine.refresh()
        advanceUntilIdle()

        val remote = engine.state.value.books.single().remotePosition!!
        assertEquals("an older and an equal revision both leave the held one", 6L, remote.revision)
        assertEquals(62.0, remote.percent!!, 1e-9)
        assertEquals("OEBPS/ch6.xhtml", remote.href)
    }

    /** A genuinely newer revision from elsewhere is applied, and it is not this device's own. */
    @Test
    fun `a newer position after this device's own is applied as another device's`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            positionResult("key-1", ReaderSyncStatus.APPLIED, revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml"),
        )
        engine.recordPosition("book-1", position(href = "OEBPS/ch4.xhtml", percent = 40))
        advanceUntilIdle()
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "6",
                changes = listOf(
                    progressChange("5", "book-1", revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml"),
                    progressChange("6", "book-1", revision = 6, percent = 70.0, href = "OEBPS/ch7.xhtml"),
                ),
            ),
        )

        engine.refresh()
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals(6L, row.remotePosition!!.revision)
        assertEquals(70.0, row.remotePosition!!.percent!!, 1e-9)
        assertTrue(
            "a newer revision is somebody else's change",
            row.ownPositionChangeKey != row.remotePosition!!.changeKey,
        )
    }

    /** A superseded publish carries the position that beat it — another device's — and is not marked as own. */
    @Test
    fun `a superseded publish adopts the winner without marking it as this device's`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            positionResult("key-1", ReaderSyncStatus.SUPERSEDED, revision = 7, percent = 81.0, href = "OEBPS/ch8.xhtml"),
        )

        engine.recordPosition("book-1", position(href = "OEBPS/ch4.xhtml", percent = 40))
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals(81.0, row.remotePosition!!.percent!!, 1e-9)
        assertNull(row.ownPositionChangeKey)
    }

    /**
     * A re-bootstrap reads the progress list, which carries no revision. The same
     * record this device admitted keeps its revision and its own mark; without
     * that, a cursor expiry would turn this device's position into another's.
     */
    @Test
    fun `a re-bootstrap that reads back this device's own position keeps it as its own`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            positionResult("key-1", ReaderSyncStatus.APPLIED, revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml"),
        )
        engine.recordPosition("book-1", position(href = "OEBPS/ch4.xhtml", percent = 40))
        advanceUntilIdle()
        val admitted = engine.state.value.books.single()
        gateway.deltaResponses = queueOf(
            deltas(status = ReaderDeltaStatus.CURSOR_EXPIRED, latestCursor = "9", rebootstrapRequired = true),
            deltas(latestCursor = "9"),
        )
        gateway.progressResponses = queueOf(
            ReaderProgressListResponse(
                requestId = REQUEST_ID,
                progress = listOf(
                    ReaderProgress(
                        bookId = "book-1",
                        progressPercent = 40.0,
                        updatedAt = PROGRESS_TIME,
                        location = ReaderPortableLocationV1(
                            publication = ReaderPortablePublicationV1.accountEpub("book-1"),
                            locator = locator("OEBPS/ch4.xhtml", 0.40),
                        ),
                    ),
                ),
            ),
        )

        engine.refresh()
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals(admitted.remotePosition!!.changeKey, row.remotePosition!!.changeKey)
        assertEquals(row.remotePosition!!.changeKey, row.ownPositionChangeKey)
    }

    /**
     * The unverified derivation failing, as a visible outcome.
     *
     * A `reading_progress` change whose resource id is not a book id and whose
     * payload names no book must not vanish: it becomes a typed error carrying
     * both values, which is what makes the assumption falsifiable on stage.
     */
    @Test
    fun `a progress record naming no known book is surfaced, not dropped`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(
            deltas(latestCursor = "1"),
            deltas(
                latestCursor = "5",
                changes = listOf(
                    ReaderSyncChange(
                        cursor = "5",
                        resourceType = ReaderResourceType.READING_PROGRESS,
                        resourceId = "progress:book-1:v7",
                        revision = 2,
                        kind = ReaderMutationKind.UPSERT,
                        serverAdmittedAt = SERVER_TIME,
                        canonicalPayload = buildJsonObject {
                            put("progress_percent", JsonPrimitive(50.0))
                            put("location", location("book-1", locator("OEBPS/ch5.xhtml", 0.5)))
                        },
                    ),
                ),
            ),
        )
        val engine = engine()
        signIn("user-1")

        engine.refresh()
        advanceUntilIdle()

        val error = engine.state.value.lastError as AccountSyncError.UnrecognizedProgressRecord
        assertEquals("progress:book-1:v7", error.resourceId)
        assertNull(error.payloadBookId)
        assertTrue(error.reason.isNotBlank())
        // The row is untouched and the run is not a failure: the cursor advanced.
        assertNull(engine.state.value.books.single().remotePosition)
        assertEquals(AccountSyncPhase.IDLE, engine.state.value.phase)
        assertEquals("5", document("user-1").cursor)
    }

    /** A record whose payload names the book is placed by that, whatever the resource id is. */
    @Test
    fun `a progress record is placed by its payload's book_id`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(
            deltas(latestCursor = "1"),
            deltas(
                latestCursor = "6",
                changes = listOf(
                    ReaderSyncChange(
                        cursor = "6",
                        resourceType = ReaderResourceType.READING_PROGRESS,
                        resourceId = "some-opaque-progress-row",
                        revision = 2,
                        kind = ReaderMutationKind.UPSERT,
                        serverAdmittedAt = SERVER_TIME,
                        canonicalPayload = buildJsonObject {
                            put("book_id", JsonPrimitive("book-1"))
                            put("progress_percent", JsonPrimitive(50.0))
                            put("location", location("book-1", locator("OEBPS/ch5.xhtml", 0.5)))
                        },
                    ),
                ),
            ),
        )
        val engine = engine()
        signIn("user-1")

        engine.refresh()
        advanceUntilIdle()

        assertNull(engine.state.value.lastError)
        assertEquals("OEBPS/ch5.xhtml", engine.state.value.books.single().remotePosition!!.href)
    }

    // ---------------------------------------------- never backwards (§7.3, #147)

    /**
     * Client contract §7.3 at the pinned reader-api `909174af`: a mutation result
     * whose revision is not newer than the stored one never replaces it.
     *
     * The case #147 names: this device's publish was admitted at revision 5 but
     * the answer for its envelope never arrived, so the entry stays queued under
     * the same key. The stream then delivers that revision 5 and another device's
     * revision 8. The retry is answered `replayed` at revision 5 — and before
     * #147 that late answer was adopted as it stood, moving the account's
     * position back from 81 % to 40 %.
     */
    @Test
    fun `a late replayed position result never replaces a newer position from the stream`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.mutationResponses = queueOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID))
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "8",
                changes = listOf(
                    progressChange("5", "book-1", revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml"),
                    progressChange("8", "book-1", revision = 8, percent = 81.0, href = "OEBPS/ch8.xhtml"),
                ),
            ),
        )
        engine.recordPosition("book-1", position(href = "OEBPS/ch4.xhtml", percent = 40))
        advanceUntilIdle()
        assertEquals("the unanswered envelope stays queued", 1, engine.state.value.queued)
        assertEquals(8L, engine.state.value.books.single().remotePosition!!.revision)

        gateway.answerMutations(
            positionResult("key-1", ReaderSyncStatus.REPLAYED, revision = 5, percent = 40.0, href = "OEBPS/ch4.xhtml"),
        )
        gateway.deltaResponses = queueOf(deltas(latestCursor = "8"))
        engine.refresh()
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals("the replay is admitted and leaves the queue", 0, engine.state.value.queued)
        assertEquals("a stale result never moves the position back", 8L, row.remotePosition!!.revision)
        assertEquals(81.0, row.remotePosition!!.percent!!, 1e-9)
        assertEquals("OEBPS/ch8.xhtml", row.remotePosition!!.href)
        assertEquals(81.0, row.progressPercent!!, 1e-9)
        assertTrue(
            "the newer position is another device's, not this device's own",
            row.ownPositionChangeKey != row.remotePosition!!.changeKey,
        )
    }

    /**
     * §7.3 for a stream change on a position: an older revision than the held one
     * is dropped even when the held one was this device's own admitted result.
     * (This half already held before #147; it is here beside its three siblings.)
     */
    @Test
    fun `a stale stream position never replaces a newer admitted one`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.answerMutations(
            positionResult("key-1", ReaderSyncStatus.APPLIED, revision = 8, percent = 81.0, href = "OEBPS/ch8.xhtml"),
        )
        engine.recordPosition("book-1", position(href = "OEBPS/ch8.xhtml", percent = 81))
        advanceUntilIdle()

        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "9",
                changes = listOf(progressChange("9", "book-1", revision = 6, percent = 30.0, href = "OEBPS/ch3.xhtml")),
            ),
        )
        engine.refresh()
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals(8L, row.remotePosition!!.revision)
        assertEquals(81.0, row.remotePosition!!.percent!!, 1e-9)
        assertEquals("still this device's own", row.remotePosition!!.changeKey, row.ownPositionChangeKey)
    }

    /**
     * §7.3 for a library item's mutation result: the same late replay as the
     * position case, on the row. Before #147 the `replayed` answer at revision 5
     * put the book back to `reading` after another device had finished it at 8.
     */
    @Test
    fun `a late replayed library result never replaces a newer row from the stream`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.mutationResponses = queueOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID))
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "8",
                changes = listOf(
                    change("5", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading")),
                    change("8", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "finished")),
                ),
            ),
        )
        engine.recordOpened("book-1")
        advanceUntilIdle()
        assertEquals(1, engine.state.value.queued)
        assertEquals(ReaderLibraryStatus.FINISHED, engine.state.value.books.single().status)

        gateway.answerMutations(
            result(
                "book-1",
                ReaderSyncStatus.REPLAYED,
                revision = 5,
                payload = itemPayload("book-1", "Dune", "reading"),
                key = "key-1",
            ),
        )
        gateway.deltaResponses = queueOf(deltas(latestCursor = "8"))
        engine.refresh()
        advanceUntilIdle()

        val row = engine.state.value.books.single()
        assertEquals(0, engine.state.value.queued)
        assertEquals("a stale result never moves the row back", ReaderLibraryStatus.FINISHED, row.status)
        assertEquals(8L, row.revision)
        assertEquals(8L, document("user-1").book("book-1")!!.revision)
    }

    /**
     * §7.3 for a library item's stream change. Before #147 the revision rule was
     * applied to positions only (#140), so an older upsert or delete arriving after
     * a newer one — a replayed page, an out-of-order delivery — moved the row back
     * or took it off the shelf.
     */
    @Test
    fun `a stale library stream change never replaces a newer row`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "8",
                changes = listOf(change("8", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "finished"))),
            ),
        )
        engine.refresh()
        advanceUntilIdle()
        assertEquals(ReaderLibraryStatus.FINISHED, engine.state.value.books.single().status)

        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "10",
                changes = listOf(
                    change("9", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"), revision = 6),
                    change("10", "book-1", ReaderMutationKind.DELETE, JsonObject(emptyMap()), revision = 7),
                ),
            ),
        )
        engine.refresh()
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals("an older upsert leaves the row", ReaderLibraryStatus.FINISHED, row.status)
        assertEquals(8L, row.revision)
        assertFalse("an older delete leaves the book on the shelf", row.removed)
        assertEquals(listOf("book-1"), engine.state.value.books.map { it.bookId })
        assertEquals("but the cursor still moves past both", "10", document("user-1").cursor)
    }

    /** A newer change still applies after the rule: §7.3 only ever drops the stale. */
    @Test
    fun `a newer library change still applies after a stale one was dropped`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "12",
                changes = listOf(
                    change("8", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "finished")),
                    change("9", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"), revision = 6),
                    change("12", "book-1", ReaderMutationKind.DELETE, JsonObject(emptyMap())),
                ),
            ),
        )
        engine.refresh()
        advanceUntilIdle()

        assertTrue(document("user-1").book("book-1")!!.removed)
        assertEquals(12L, document("user-1").book("book-1")!!.revision)
    }

    /**
     * #149: §7.3's strictly-newer rule for a library item's *stream* change. An
     * equal-revision change is the state this device already holds; applying it
     * used to revert the optimistic row while its mutation still sat in the
     * outbox, so a pending removal came back onto the shelf.
     */
    @Test
    fun `an equal-revision library stream change never replaces the optimistic row`() = runTest(dispatcher) {
        val engine = signedInEngine()
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "8",
                changes = listOf(change("8", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"))),
            ),
        )
        engine.refresh()
        advanceUntilIdle()
        assertEquals(8L, document("user-1").book("book-1")!!.revision)

        // The removal stays queued (no answer for it), and the stream echoes the
        // row at the revision this device already holds — an upsert and a status.
        gateway.mutationResponses = queueOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID))
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "10",
                changes = listOf(
                    change("9", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"), revision = 8),
                    change("10", "book-1", ReaderMutationKind.RESTORE, itemPayload("book-1", "Dune", "finished"), revision = 8),
                ),
            ),
        )
        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(1, engine.state.value.queued)
        assertTrue("the pending removal stays off the shelf", row.removed)
        assertEquals("an equal revision replaces nothing", ReaderLibraryStatus.READING, row.status)
        assertTrue(engine.state.value.books.isEmpty())
        assertEquals("but the cursor still moves past both", "10", document("user-1").cursor)
    }

    /** §7.4: an equal-revision `superseded` result is the backend's verdict and still replaces the optimistic row. */
    @Test
    fun `an equal-revision superseded result still replaces the optimistic row`() = runTest(dispatcher) {
        val engine = engineAtRevision8()
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.SUPERSEDED, revision = 8, payload = itemPayload("book-1", "Dune", "reading"), key = "key-1"),
        )
        gateway.deltaResponses = queueOf(deltas(latestCursor = "8"))
        engine.recordStatus("book-1", ReaderLibraryStatus.FINISHED)
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(0, engine.state.value.queued)
        assertEquals("the optimistic status is discarded", ReaderLibraryStatus.READING, row.status)
        assertEquals(8L, row.revision)
    }

    /** §7.4: the same for a `conflict` whose remote revision equals the stored one. */
    @Test
    fun `an equal-revision conflict result still replaces the optimistic row`() = runTest(dispatcher) {
        val engine = engineAtRevision8()
        gateway.answerMutations(
            ReaderSyncMutationResult(
                idempotencyKey = "key-1",
                resourceType = ReaderResourceType.LIBRARY_ITEM,
                resourceId = "book-1",
                mutationKind = ReaderMutationKind.UPSERT,
                status = ReaderSyncStatus.CONFLICT,
                canonicalPayload = itemPayload("book-1", "Dune", "reading"),
                conflict = ReaderSyncConflict(
                    conflictId = "c-1",
                    code = ReaderSyncConflictCode.REVISION_CONFLICT,
                    remoteRevision = 8,
                    canonicalPayload = itemPayload("book-1", "Dune", "reading"),
                ),
            ),
        )
        gateway.deltaResponses = queueOf(deltas(latestCursor = "8"))
        engine.recordStatus("book-1", ReaderLibraryStatus.FINISHED)
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(0, engine.state.value.queued)
        assertEquals("the backend's value wins over the optimistic status", ReaderLibraryStatus.READING, row.status)
        assertEquals(8L, row.revision)
    }

    /**
     * #149 review B1: a queued removal the backend answers `conflict` with the
     * book still live must end on the shelf. Presence is the canonical payload's,
     * not the mutation kind's — and since the stream's equal-revision echo of the
     * winning change is no longer re-applied, nothing else would repair it.
     */
    @Test
    fun `a removal answered conflict with the book present ends present, and the equal-revision echo keeps it`() = runTest(dispatcher) {
        val engine = engineAtRevision8()
        gateway.answerMutations(
            ReaderSyncMutationResult(
                idempotencyKey = "key-1",
                resourceType = ReaderResourceType.LIBRARY_ITEM,
                resourceId = "book-1",
                mutationKind = ReaderMutationKind.DELETE,
                status = ReaderSyncStatus.CONFLICT,
                canonicalPayload = itemPayload("book-1", "Dune", "reading"),
                conflict = ReaderSyncConflict(
                    conflictId = "c-1",
                    code = ReaderSyncConflictCode.REVISION_CONFLICT,
                    remoteRevision = 9,
                    canonicalPayload = itemPayload("book-1", "Dune", "reading"),
                ),
            ),
        )
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "9",
                changes = listOf(change("9", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"))),
            ),
        )
        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(0, engine.state.value.queued)
        assertFalse("the backend kept the book, so it is back on the shelf", row.removed)
        assertEquals(9L, row.revision)
        assertEquals(ReaderLibraryStatus.READING, row.status)
        assertEquals(listOf("book-1"), engine.state.value.books.map { it.bookId })
        assertEquals("9", document("user-1").cursor)
    }

    /** #149 review B1: the same for a removal answered `superseded` by a live book. */
    @Test
    fun `a removal answered superseded with the book present ends present`() = runTest(dispatcher) {
        val engine = engineAtRevision8()
        gateway.answerMutations(
            result(
                "book-1",
                ReaderSyncStatus.SUPERSEDED,
                revision = 9,
                payload = itemPayload("book-1", "Dune", "finished"),
                key = "key-1",
                kind = ReaderMutationKind.DELETE,
            ),
        )
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "9",
                changes = listOf(change("9", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "finished"))),
            ),
        )
        engine.removeFromAccount("book-1")
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(0, engine.state.value.queued)
        assertFalse("a superseded removal leaves the winner's live book", row.removed)
        assertEquals(9L, row.revision)
        assertEquals(ReaderLibraryStatus.FINISHED, row.status)
        assertEquals(listOf("book-1"), engine.state.value.books.map { it.bookId })
    }

    /**
     * #149 review B1, the mirror: a queued upsert the backend answers with the
     * tombstone (the empty canonical payload of a removed membership) ends off
     * the shelf, and the equal-revision stream tombstone does not undo that.
     */
    @Test
    fun `an upsert answered conflict with the canonical tombstone ends removed`() = runTest(dispatcher) {
        val engine = engineAtRevision8()
        gateway.answerMutations(
            ReaderSyncMutationResult(
                idempotencyKey = "key-1",
                resourceType = ReaderResourceType.LIBRARY_ITEM,
                resourceId = "book-1",
                mutationKind = ReaderMutationKind.UPSERT,
                status = ReaderSyncStatus.CONFLICT,
                conflict = ReaderSyncConflict(
                    conflictId = "c-1",
                    code = ReaderSyncConflictCode.TOMBSTONE_REQUIRES_RESTORE,
                    remoteRevision = 9,
                ),
            ),
        )
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "9",
                changes = listOf(change("9", "book-1", ReaderMutationKind.DELETE, JsonObject(emptyMap()))),
            ),
        )
        engine.recordStatus("book-1", ReaderLibraryStatus.FINISHED)
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(0, engine.state.value.queued)
        assertTrue("the backend removed the book, so the status change does not keep it", row.removed)
        assertEquals(9L, row.revision)
        assertTrue(engine.state.value.books.isEmpty())
    }

    /** #149 review B1, the mirror for `superseded`: an upsert beaten by a later removal ends removed. */
    @Test
    fun `an upsert answered superseded by a removal ends removed`() = runTest(dispatcher) {
        val engine = engineAtRevision8()
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.SUPERSEDED, revision = 9, payload = JsonObject(emptyMap()), key = "key-1"),
        )
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "9",
                changes = listOf(change("9", "book-1", ReaderMutationKind.DELETE, JsonObject(emptyMap()))),
            ),
        )
        engine.recordOpened("book-1")
        advanceUntilIdle()

        val row = document("user-1").book("book-1")!!
        assertEquals(0, engine.state.value.queued)
        assertTrue(row.removed)
        assertEquals(9L, row.revision)
        assertTrue(engine.state.value.books.isEmpty())
    }

    /**
     * #149: a host record under a key the document reserves would be written and
     * then lost — a declared key wins over it on the way out, and `host` is dropped
     * on the way in. The write is refused with a typed error, and nothing is written.
     */
    @Test
    fun `a reserved host record key is refused with a typed error`() = runTest(dispatcher) {
        val engine = signedInEngine()
        val before = storeFile("user-1").readText()

        for (key in listOf("host", "cursor", "books", "outbox")) {
            val error = assertThrowsSuspending<ReservedHostRecordKeyException> {
                engine.updateHostRecord(key) { JsonPrimitive("x") }
            }
            assertEquals(key, error.key)
            assertFalse(error.bookLevel)
        }
        for (key in listOf("host", "revision", "bookId", "removed")) {
            val error = assertThrowsSuspending<ReservedHostRecordKeyException> {
                engine.updateBookHostRecord("book-1", key) { JsonPrimitive("x") }
            }
            assertEquals(key, error.key)
            assertTrue(error.bookLevel)
        }

        assertEquals("nothing was written", before, storeFile("user-1").readText())
        // An unreserved key is still a host record.
        engine.updateHostRecord("copies") { JsonPrimitive("kept") }
        assertEquals(JsonPrimitive("kept"), engine.hostRecord("copies"))
    }

    /**
     * #149: a transform runs under the engine's non-reentrant lock. A call back
     * into the engine from inside it fails loudly instead of deadlocking, and the
     * engine is usable again afterwards. The timeout is the regression guard: a
     * reentrant call on the old code hangs for ever.
     */
    @Test(timeout = 30_000)
    fun `a host record transform that calls back into the engine fails instead of hanging`() = runTest(dispatcher) {
        val engine = signedInEngine()

        val suspendCallback = assertThrowsSuspending<IllegalStateException> {
            engine.updateHostRecord("copies") { current ->
                kotlinx.coroutines.runBlocking { engine.hostRecord("copies") } ?: current
            }
        }
        assertTrue(suspendCallback.message!!.contains("must not call back"))
        assertThrowsSuspending<IllegalStateException> {
            engine.updateBookHostRecord("book-1", "note") {
                engine.refresh()
                JsonPrimitive("x")
            }
        }
        assertThrowsSuspending<IllegalStateException> {
            engine.updateHostRecord("copies") {
                engine.removeFromAccount("book-1")
                JsonPrimitive("x")
            }
        }
        assertEquals("a refused callback queued nothing", 0, engine.state.value.queued)

        engine.updateHostRecord("copies") { JsonPrimitive("after") }
        assertEquals(JsonPrimitive("after"), engine.hostRecord("copies"))
        assertEquals("user-1", engine.accountId())
    }

    // ------------------------------------------------------------- helpers

    /** Runs [block] and returns the [T] it throws; fails when it throws nothing. */
    private suspend inline fun <reified T : Throwable> assertThrowsSuspending(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
    }

    /** The next delta read answers `cursor_expired`; the re-bootstrap's head read then answers cursor 77. */
    private fun expireCursorOnNextRead() {
        gateway.deltaResponses = queueOf(
            deltas(status = ReaderDeltaStatus.CURSOR_EXPIRED, latestCursor = "77", rebootstrapRequired = true),
            deltas(latestCursor = "77"),
        )
    }

    /** A non-retryable rejection of book-1's library change [key], with the contract's empty payload. */
    private fun rejected(key: String, kind: ReaderMutationKind) = ReaderSyncMutationResult(
        idempotencyKey = key,
        resourceType = ReaderResourceType.LIBRARY_ITEM,
        resourceId = "book-1",
        mutationKind = kind,
        status = ReaderSyncStatus.REJECTED,
        rejection = ReaderSyncRejection(
            code = ReaderSyncRejectionCode.UNSUPPORTED_MUTATION,
            detail = "this resource does not accept that",
            retryable = false,
        ),
    )

    /** [signedInEngine], with book-1 moved to revision 8 by the stream. */
    private fun TestScope.engineAtRevision8(): AccountSyncEngine {
        val engine = signedInEngine()
        gateway.deltaResponses = queueOf(
            deltas(
                latestCursor = "8",
                changes = listOf(change("8", "book-1", ReaderMutationKind.UPSERT, itemPayload("book-1", "Dune", "reading"))),
            ),
        )
        engine.refresh()
        advanceUntilIdle()
        assertEquals(8L, document("user-1").book("book-1")!!.revision)
        return engine
    }

    /** An engine signed in with one book and a settled bootstrap. */
    private fun TestScope.signedInEngine(): AccountSyncEngine {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")
        return engine
    }

    /** Answer the next batch's single position envelope as admitted. */
    private fun admitPosition(key: String, revision: Long) {
        gateway.answerMutations(
            result(
                "book-1",
                ReaderSyncStatus.APPLIED,
                revision = revision,
                payload = JsonObject(emptyMap()),
                key = key,
                kind = ReaderMutationKind.UPSERT,
            ),
        )
    }

    private fun position(href: String, percent: Int) = LocalReadingPosition(
        href = href,
        chapterTitle = "Chapter",
        progression = percent / 100.0,
        percent = percent,
    )

    /** A canonical `location` for an account EPUB, as reader-api returns it (#139). */
    private fun location(bookId: String, locator: JsonObject) = buildJsonObject {
        put("contract_version", JsonPrimitive("reader.portable-semantics.v1"))
        put(
            "publication",
            buildJsonObject {
                put("publication_id", JsonPrimitive(bookId))
                put("format", JsonPrimitive("epub"))
                put("media_type", JsonPrimitive("application/epub+zip"))
                put("source", JsonPrimitive("account"))
            },
        )
        put("locator", locator)
    }

    private fun locator(href: String, progression: Double) = buildJsonObject {
        put("format", JsonPrimitive("epub"))
        put("href", JsonPrimitive(href))
        put("progression", JsonPrimitive(progression))
        put("contract_version", JsonPrimitive("reader.portable-semantics.v1"))
    }

    private fun progressChange(
        cursor: String,
        bookId: String,
        revision: Long,
        percent: Double,
        href: String,
    ) = ReaderSyncChange(
        cursor = cursor,
        resourceType = ReaderResourceType.READING_PROGRESS,
        resourceId = bookId,
        revision = revision,
        kind = ReaderMutationKind.UPSERT,
        serverAdmittedAt = SERVER_TIME,
        canonicalPayload = progressPayload(bookId, percent, href),
    )

    /** A canonical `reading_progress` record as reader-api returns it. */
    private fun progressPayload(bookId: String, percent: Double, href: String) = buildJsonObject {
        put("book_id", JsonPrimitive(bookId))
        put("progress_percent", JsonPrimitive(percent))
        put("updated_at", JsonPrimitive(PROGRESS_TIME))
        put("location", location(bookId, locator(href, percent / 100.0)))
    }

    /** The backend's answer to this device's own `reading_progress` upsert for book-1. */
    private fun positionResult(
        key: String,
        status: ReaderSyncStatus,
        revision: Long,
        percent: Double,
        href: String,
    ) = ReaderSyncMutationResult(
        idempotencyKey = key,
        resourceType = ReaderResourceType.READING_PROGRESS,
        resourceId = "book-1",
        mutationKind = ReaderMutationKind.UPSERT,
        status = status,
        canonicalPayload = progressPayload("book-1", percent, href),
        serverAdmission = if (status == ReaderSyncStatus.APPLIED) ReaderServerAdmission.ACCEPTED else null,
        revision = revision,
        cursor = revision.toString(),
        serverAdmittedAt = SERVER_TIME,
    )

    /** Answer the next batch's single envelope as admitted, with an empty canonical payload. */
    private fun admit(key: String, kind: ReaderMutationKind) {
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.APPLIED, revision = 2, payload = JsonObject(emptyMap()), key = key, kind = kind),
        )
    }

    private fun TestScope.signIn(userId: String) {
        session.value = AccountSession.SignedIn(userId)
        advanceUntilIdle()
    }

    private fun storeFile(userId: String): File {
        stores.forUser(userId)
        return directory.listFiles()!!
            .filter { it.name.endsWith(".json") }
            .single { file ->
                val store = FileAccountLibraryStore(file)
                (store.load() as? AccountLibraryLoad.Loaded)?.document?.userId == userId
            }
    }

    private fun document(userId: String): AccountLibraryDocument =
        (stores.forUser(userId).load() as AccountLibraryLoad.Loaded).document

    private fun <T> queueOf(vararg values: T): ArrayDeque<T> = ArrayDeque(values.toList())

    private fun libraryOf(vararg items: ReaderLibraryItem) =
        ReaderLibraryResponse(requestId = REQUEST_ID, items = items.toList())

    private fun item(
        id: String,
        title: String,
        status: ReaderLibraryStatus = ReaderLibraryStatus.QUEUED,
    ) = ReaderLibraryItem(
        book = ReaderBook(
            id = id,
            title = title,
            createdAt = SERVER_TIME,
            updatedAt = SERVER_TIME,
            author = "Frank Herbert",
            language = "en",
        ),
        status = status,
        createdAt = SERVER_TIME,
        updatedAt = SERVER_TIME,
        assets = listOf(
            ReaderBookAsset(
                assetId = "asset-$id",
                bookId = id,
                kind = ReaderBookAssetKind.EPUB,
                uploadStatus = "ready",
                createdAt = SERVER_TIME,
                updatedAt = SERVER_TIME,
                checksum = "sha-$id",
            ),
        ),
        coverStatus = ReaderCoverStatus.COVERED,
    )

    private fun itemPayload(id: String, title: String, status: String) = buildJsonObject {
        put(
            "book",
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("title", JsonPrimitive(title))
            },
        )
        put("status", JsonPrimitive(status))
        put(
            "assets",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("asset_id", JsonPrimitive("asset-$id"))
                        put("checksum", JsonPrimitive("sha-$id"))
                    },
                )
            },
        )
    }

    private fun deltas(
        status: ReaderDeltaStatus = ReaderDeltaStatus.OK,
        latestCursor: String,
        nextCursor: String? = null,
        changes: List<ReaderSyncChange> = emptyList(),
        hasMore: Boolean = false,
        rebootstrapRequired: Boolean = false,
    ) = ReaderSyncDeltaResponse(
        requestId = REQUEST_ID,
        status = status,
        minimumValidCursor = "0",
        latestCursor = latestCursor,
        changes = changes,
        nextCursor = nextCursor,
        hasMore = hasMore,
        rebootstrapRequired = rebootstrapRequired,
    )

    private fun change(
        cursor: String,
        resourceId: String,
        kind: ReaderMutationKind,
        payload: JsonObject,
        revision: Long = cursor.toLong(),
    ) = ReaderSyncChange(
        cursor = cursor,
        resourceType = ReaderResourceType.LIBRARY_ITEM,
        resourceId = resourceId,
        revision = revision,
        kind = kind,
        serverAdmittedAt = SERVER_TIME,
        canonicalPayload = payload,
    )

    private fun result(
        resourceId: String,
        status: ReaderSyncStatus,
        revision: Long,
        payload: JsonObject,
        key: String,
        kind: ReaderMutationKind = ReaderMutationKind.UPSERT,
        admission: ReaderServerAdmission = ReaderServerAdmission.ACCEPTED,
    ) = ReaderSyncMutationResult(
        idempotencyKey = key,
        resourceType = ReaderResourceType.LIBRARY_ITEM,
        resourceId = resourceId,
        mutationKind = kind,
        status = status,
        canonicalPayload = payload,
        serverAdmission = admission,
        revision = revision,
        cursor = revision.toString(),
        serverAdmittedAt = SERVER_TIME,
    )

    private companion object {
        const val REQUEST_ID: String = FakeReaderLibraryGateway.REQUEST_ID
        const val SERVER_TIME: String = "2026-09-14T09:00:00Z"
        const val CLIENT_TIME: String = "2026-09-14T09:30:00Z"
        const val PROGRESS_TIME: String = "2026-09-20T09:00:00Z"
    }
}
