package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.model.ReaderBook
import com.cedagova.reader.library.model.ReaderLibraryItem
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How an own write reaches the outbox (#161, #162, #163, umbrella #169): in call
 * order, in the account it was asked for in, and never marked as published
 * before it is on disk.
 *
 * The order and the lost-position proofs run the engine on
 * [Dispatchers.Default], the multi-threaded scope FastReader itself passes —
 * the ordering bug only exists there. The session-switch proofs that need the
 * switch to land at one exact point use the test scheduler instead, with the
 * lock held by a parked sync, so the interleaving is the one the issue names
 * rather than one a thread pool happens to produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSyncEngineQueueTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fake = FakeReaderLibraryGateway().apply {
        libraryResponses = ArrayDeque(listOf(libraryOf("book-1")))
    }

    /** Offline for every mutation, so each queued entry stays in the outbox to be inspected. */
    private val offline = AtomicBoolean(true)

    private val gateway = object : ReaderLibraryGateway by fake {
        override suspend fun applyMutations(
            mutations: List<ReaderSyncMutationEnvelope>,
        ): ReaderSyncMutationBatchResponse {
            if (offline.get()) throw ReaderAuthException.NetworkUnavailable(IOException("offline"))
            return fake.applyMutations(mutations)
        }
    }

    private val session = MutableStateFlow<AccountSession>(AccountSession.Loading)
    private val directory: File by lazy { File(temporaryFolder.root, "account-library") }
    private val files: AccountLibraryStores by lazy { FileAccountLibraryStores(directory) }

    /** When set, every save throws, as a full disk would. */
    private val storeFails = AtomicBoolean(false)
    private val stores: AccountLibraryStores by lazy {
        object : AccountLibraryStores {
            override fun forUser(userId: String) = failing(files.forUser(userId))
            override fun exceptUser(userId: String) = files.exceptUser(userId).map(::failing)
        }
    }

    /** When set, the next save throws this, consumed once: a failure that is not an [IOException]. */
    private val storeThrows = AtomicReference<Throwable?>(null)

    private fun failing(store: AccountLibraryStore) = object : AccountLibraryStore {
        override fun load() = store.load()
        override fun save(document: AccountLibraryDocument) {
            storeThrows.getAndSet(null)?.let { throw it }
            if (storeFails.get()) throw IOException("disk full")
            store.save(document)
        }
    }

    private val keys = AtomicInteger()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun engine(scope: CoroutineScope, io: kotlinx.coroutines.CoroutineDispatcher) = AccountSyncEngine(
        gateway = gateway,
        stores = stores,
        accountState = session,
        scope = scope.also { scopes += it },
        ioDispatcher = io,
        newIdempotencyKey = { "key-${keys.incrementAndGet()}" },
        now = { "2026-09-24T09:00:00Z" },
    )

    /** On the host's own kind of scope: `CoroutineScope(SupervisorJob())`, i.e. [Dispatchers.Default]. */
    private fun threadedEngine() = engine(CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO)

    // -------------------------------------------------------------- #161

    /** #161's proof: a burst of Remove / Undo lands in the outbox exactly in call order. */
    @Test
    fun `a thousand alternating removes and undos reach the outbox in call order`() = runBlocking {
        val engine = threadedEngine()
        signInAndSettle(engine, "user-a")

        val calls = List(1_000) { if (it % 2 == 0) ReaderMutationKind.DELETE else ReaderMutationKind.RESTORE }
        calls.forEach { kind ->
            if (kind == ReaderMutationKind.DELETE) engine.removeFromAccount("book-1") else engine.undoRemove("book-1")
        }
        val settled = awaitState(engine) { it.queued == calls.size }

        val outbox = document("user-a").outbox
        assertEquals(calls, outbox.map { it.mutationKind })
        val minted = outbox.map { it.idempotencyKey.removePrefix("key-").toInt() }
        assertEquals("keys are minted in queue order", minted.sorted().distinct(), minted)
        assertEquals("a final Undo leaves the book on the shelf", listOf("book-1"), settled.books.map { it.bookId })
        assertEquals(false, document("user-a").book("book-1")!!.removed)
    }

    /** The mirror case: a final Remove leaves it off, however the threads ran. */
    @Test
    fun `a burst ending in a remove leaves the book off the shelf`() = runBlocking {
        val engine = threadedEngine()
        signInAndSettle(engine, "user-a")

        repeat(501) { if (it % 2 == 0) engine.removeFromAccount("book-1") else engine.undoRemove("book-1") }
        val settled = awaitState(engine) { it.queued == 501 }

        assertEquals(ReaderMutationKind.DELETE, document("user-a").outbox.last().mutationKind)
        assertTrue(settled.books.isEmpty())
    }

    // -------------------------------------------------------------- #162

    /**
     * #162's proof: a removal asked for as A, then a switch straight to B that
     * takes the lock first. B's outbox is untouched and A's stored document
     * holds the removal.
     */
    @Test
    fun `a removal asked for as A before a switch to B lands in A's document, not B's`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = engine(TestScope(dispatcher), dispatcher)
            session.value = AccountSession.SignedIn("user-a")
            advanceUntilIdle()

            val gate = holdTheLock(engine)
            session.value = AccountSession.SignedIn("user-b") // queues on the lock first
            runCurrent()
            engine.removeFromAccount("book-1") // asked for as A: B has not signed in yet
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("user-b", engine.state.value.userId)
            assertEquals("B's outbox is untouched", emptyList<AccountOutboxEntry>(), document("user-b").outbox)
            assertEquals("B's copy of the book stays", false, document("user-b").book("book-1")!!.removed)
            val held = document("user-a").outbox.single()
            assertEquals(ReaderMutationKind.DELETE, held.mutationKind)
            assertEquals("book-1", held.resourceId)
        }
    }

    /** The other half of #162: through signed-out, A's change is kept for A, not dropped. */
    @Test
    fun `a removal asked for as A before a sign-out is kept in A's document`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val engine = engine(TestScope(dispatcher), dispatcher)
            session.value = AccountSession.SignedIn("user-a")
            advanceUntilIdle()

            val gate = holdTheLock(engine)
            session.value = AccountSession.SignedOut
            runCurrent()
            engine.removeFromAccount("book-1")
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(AccountSyncPhase.SIGNED_OUT, engine.state.value.phase)
            assertEquals(ReaderMutationKind.DELETE, document("user-a").outbox.single().mutationKind)

            // And it is sent when A comes back.
            offline.set(false)
            fake.calls.clear()
            session.value = AccountSession.SignedIn("user-a")
            advanceUntilIdle()
            assertEquals(listOf("book-1"), fake.submitted.flatten().map { it.resourceId })
        }
    }

    /**
     * #162 under real threads: removals asked for while the session switches
     * from A to B on another thread. Every change lands in the account it was
     * asked for in, so what is left of A's queue and B's whole queue are, end to
     * end, one unbroken tail of the calls — A's before B's, none in both, none
     * missing (the part of A's queue that is gone is D4's discard, all of it
     * earlier than anything kept).
     *
     * A stress test, not an interleaving proof: the pinned switch interleavings
     * are the test-scheduler tests above.
     */
    @Test
    fun `under real threads a switch from A to B never moves a change across accounts`() = runBlocking {
        val engine = threadedEngine()
        signInAndSettle(engine, "user-a")

        // Calls keep coming from this thread while the collector switches on
        // another one, until B is signed in and then for 500 more.
        val ids = mutableListOf<String>()
        fun call() = "x-${ids.size}".also {
            ids += it
            engine.removeFromAccount(it)
        }
        repeat(500) { call() }
        session.value = AccountSession.SignedIn("user-b")
        withTimeout(20_000) {
            while (engine.accountId() != "user-b") {
                call()
                if (ids.size % 50 == 0) delay(1)
            }
        }
        repeat(500) { call() }
        awaitState(engine) { it.userId == "user-b" }
        // Wait until every call is somewhere: the last call, asked for in B, is
        // in B's outbox. One consumer takes the calls off in call order, so every
        // earlier call has been placed by then (#181: a quiet-period guess here
        // read the outbox while a slow CI disk still had 49 calls to write).
        // The timeout only guards a hang: each change rewrites the whole
        // document, so a loaded runner can take tens of seconds to catch up.
        val lastCall = ids.last()
        val placed = withTimeoutOrNull(120_000) {
            while (document("user-b").outbox.lastOrNull()?.resourceId != lastCall) delay(10)
        }
        if (placed == null) {
            fail(
                "the last call never reached B's outbox; " +
                    queueDetail(
                        ids,
                        document("user-a").outbox.map {
                            it.resourceId
                        },
                        document("user-b").outbox.map { it.resourceId },
                    ),
            )
        }

        val a = document("user-a").outbox.map { it.resourceId }
        val b = document("user-b").outbox.map { it.resourceId }
        val detail = queueDetail(ids, a, b)
        assertTrue("B got changes after the switch; $detail", b.isNotEmpty())
        assertTrue(
            "A's remainder then B's queue are one unbroken tail of the calls; $detail",
            ids.takeLast(a.size + b.size) == a + b,
        )
        assertTrue("nothing asked for before the switch reached B; $detail", ids.indexOf(b.first()) >= 500)
    }

    /** What a failure of the switch proof needs, without printing a thousand ids. */
    private fun queueDetail(ids: List<String>, a: List<String>, b: List<String>): String {
        fun span(list: List<String>) = if (list.isEmpty()) "empty" else "${list.first()}..${list.last()} (${list.size})"
        val placed = a + b
        val missing = ids.drop(ids.indexOf(placed.firstOrNull()).coerceAtLeast(0)).filterNot(placed.toSet()::contains)
        val duplicated = placed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return "calls=${span(ids)} a=${span(a)} b=${span(b)} " +
            "missing=${missing.take(10)}${if (missing.size > 10) "+${missing.size - 10}" else ""} " +
            "duplicated=${duplicated.take(10)}"
    }

    // -------------------------------------------------------------- #163

    /** #163: a position asked for while the session is still loading is queued once it signs in. */
    @Test
    fun `a position recorded while loading is queued exactly once after sign-in`() = runBlocking {
        val engine = threadedEngine()
        engine.recordPosition("book-1", position(40))
        engine.recordPosition("book-1", position(40))
        delay(100)

        session.value = AccountSession.SignedIn("user-a")
        awaitState(engine) { it.userId == "user-a" && it.queued == 1 }
        delay(200)

        val outbox = document("user-a").outbox
        assertEquals(1, outbox.size)
        assertEquals(ReaderResourceType.READING_PROGRESS, outbox.single().resourceType)
    }

    /** #163: a store that refused the position does not mark it published; the same flush retries. */
    @Test
    fun `a position the store refused is queued by the next identical call`() = runBlocking {
        val engine = threadedEngine()
        signInAndSettle(engine, "user-a")

        storeFails.set(true)
        engine.recordPosition("book-1", position(40))
        awaitState(engine) { it.lastError is AccountSyncError.StoreBlocked }
        assertEquals(emptyList<AccountOutboxEntry>(), document("user-a").outbox)

        storeFails.set(false)
        engine.recordPosition("book-1", position(40))
        awaitState(engine) { it.queued == 1 }
        engine.recordPosition("book-1", position(40))
        delay(200)

        assertEquals("queued once, and the guard holds after", 1, document("user-a").outbox.size)
    }

    // -------------------------------------------------------------- #177

    /** #177: a failure that is not an IOException is reported and does not stop the queue. */
    @Test
    fun `a non-IO failure on one change leaves the queue running`() = runBlocking {
        val engine = threadedEngine()
        signInAndSettle(engine, "user-a")

        storeThrows.set(IllegalStateException("injected"))
        engine.recordPosition("book-1", position(40))
        awaitState(engine) { it.lastError is AccountSyncError.StoreBlocked }
        assertEquals(emptyList<AccountOutboxEntry>(), document("user-a").outbox)

        engine.recordPosition("book-2", position(10))
        awaitState(engine) { it.queued == 1 }
        assertEquals(listOf("book-2"), document("user-a").outbox.map { it.resourceId })
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun signInAndSettle(engine: AccountSyncEngine, userId: String) {
        session.value = AccountSession.SignedIn(userId)
        awaitState(engine) { it.userId == userId && it.phase == AccountSyncPhase.IDLE }
    }

    private suspend fun awaitState(
        engine: AccountSyncEngine,
        predicate: (AccountLibraryState) -> Boolean,
    ): AccountLibraryState = withTimeout(20_000) { engine.state.first(predicate) }

    /** Parks a sync on the gateway, which holds the engine's lock until the gate opens. */
    private fun TestScope.holdTheLock(engine: AccountSyncEngine): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        engine.refresh()
        runCurrent()
        return gate
    }

    private fun document(userId: String): AccountLibraryDocument =
        (files.forUser(userId).load() as AccountLibraryLoad.Loaded).document

    private fun position(percent: Int) = LocalReadingPosition(
        href = "OEBPS/ch1.xhtml",
        chapterTitle = "Chapter",
        progression = percent / 100.0,
        percent = percent,
    )

    private companion object {
        const val TIME = "2026-09-14T09:00:00Z"

        fun libraryOf(vararg ids: String) = ReaderLibraryResponse(
            requestId = FakeReaderLibraryGateway.REQUEST_ID,
            items = ids.map { id ->
                ReaderLibraryItem(
                    book = ReaderBook(id = id, title = id, createdAt = TIME, updatedAt = TIME),
                    status = ReaderLibraryStatus.QUEUED,
                    createdAt = TIME,
                    updatedAt = TIME,
                )
            },
        )
    }
}
