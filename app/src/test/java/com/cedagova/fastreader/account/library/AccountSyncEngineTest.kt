package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.account.FakeReaderLibraryGateway
import com.cedagova.fastreader.account.ReaderAccountState
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
    private val session = MutableStateFlow<ReaderAccountState>(ReaderAccountState.Loading)
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
        session.value = ReaderAccountState.Loading
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
    fun `a rejection is surfaced with the backend's own code and does not move the row`() = runTest(dispatcher) {
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
     * Settling the resume offer is a note to this device and never a mutation
     * (#121).
     *
     * The record exists so the offer is made once per remote change; nothing
     * about it belongs to the account's backend state, so answering it must add
     * no envelope to the outbox and send nothing at all. Asserted beside the
     * envelope invariant above because this is the operation most likely to grow
     * a wire call by accident — it is the only thing on
     * [AccountLibraryActions] that writes the account document without queueing.
     */
    @Test
    fun `settling a resume offer is stored and sends nothing`() = runTest(dispatcher) {
        gateway.libraryResponses = queueOf(libraryOf(item("book-1", "Dune")))
        gateway.deltaResponses = queueOf(deltas(latestCursor = "1"))
        val engine = engine()
        signIn("user-1")
        gateway.calls.clear()

        engine.settleResumeOffer("book-1", "7:2026-09-20T10:00:00Z")
        advanceUntilIdle()

        assertTrue("settling must send nothing at all, got ${gateway.calls}", gateway.calls.isEmpty())
        assertEquals(0, engine.state.value.queued)
        val stored = stores.forUser("user-1").load() as AccountLibraryLoad.Loaded
        assertEquals("7:2026-09-20T10:00:00Z", stored.document.book("book-1")?.resumeOfferSettledFor)
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

        session.value = ReaderAccountState.SignedOut()
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

        session.value = ReaderAccountState.SignedOut()
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
        session.value = ReaderAccountState.SignedOut()
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

    // ------------------------------------------------------------- helpers

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
        canonicalPayload = buildJsonObject {
            put("book_id", JsonPrimitive(bookId))
            put("progress_percent", JsonPrimitive(percent))
            put("updated_at", JsonPrimitive("2026-09-20T09:00:00Z"))
            put("location", location(bookId, locator(href, percent / 100.0)))
        },
    )

    /** Answer the next batch's single envelope as admitted, with an empty canonical payload. */
    private fun admit(key: String, kind: ReaderMutationKind) {
        gateway.answerMutations(
            result("book-1", ReaderSyncStatus.APPLIED, revision = 2, payload = JsonObject(emptyMap()), key = key, kind = kind),
        )
    }

    private fun TestScope.signIn(userId: String) {
        session.value = ReaderAccountState.SignedIn(userId = userId, email = null)
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
    ) = ReaderSyncChange(
        cursor = cursor,
        resourceType = ReaderResourceType.LIBRARY_ITEM,
        resourceId = resourceId,
        revision = cursor.toLong(),
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
    }
}
