package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderPortableLocationV1
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncMutationBatchRequest
import com.cedagova.reader.library.model.ReaderSyncMutationResult
import com.cedagova.reader.library.model.ReaderSyncStatus
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Who is signed in, as the engine needs to know it (D4).
 *
 * The host maps its own session state onto this; the engine reads nothing else
 * about the session. [Loading] means the stored session has not been read yet,
 * so nothing changes.
 */
sealed interface AccountSession {

    /** The stored session has not been read yet. */
    data object Loading : AccountSession

    /** The host carries no service values, so there is no account to sync. */
    data object NotConfigured : AccountSession

    /** Nobody is signed in. */
    data object SignedOut : AccountSession

    /** [userId] is the provider subject the session reports. */
    data class SignedIn(val userId: String) : AccountSession
}

/** The account-library actions a host's shelf performs (LEAF703). */
interface AccountLibraryActions {

    /** The reader asked for a refresh. */
    fun refresh()

    /** Remove [bookId] from the account library. */
    fun removeFromAccount(bookId: String)

    /** The contract's immediate Undo of a removal: a `restore` of the same book. */
    fun undoRemove(bookId: String)

    /** The book was opened: `reading`, and this device's clock as the last-opened time. */
    fun recordOpened(bookId: String)

    /** The book was finished. */
    fun recordFinished(bookId: String)

    /** Any other library status the shelf sets. */
    fun recordStatus(bookId: String, status: ReaderLibraryStatus)

    /**
     * Publishes the portable position of an account book (REQ-511, AD-25).
     *
     * [bookId] is the **account's** book id. A device book the account does not
     * hold has no account id to resolve, so nothing is published for it — the
     * caller's `accountBookIdForDevice` returns null and this is never reached.
     *
     * Called only when the position writer flushes for a non-word event and the
     * section or the whole percent changed since the last publish; never on the
     * per-word throttle. A position that moves *backwards* is published exactly
     * like one that moves forwards: `causal-progress-can-move-backward`, and the
     * backend's admission order decides who wins.
     */
    fun recordPosition(bookId: String, position: LocalReadingPosition)

}

/**
 * The account document's import records, as the add-to-account flow uses them
 * (LEAF802, AD-26).
 *
 * A separate interface rather than more methods on [AccountLibraryActions]
 * because the two answer different questions: those are *mutations of the
 * account's library* that queue and are admitted; these are this device's own
 * memory of an upload in flight, which the backend never sees. It is served by
 * [AccountSyncEngine] all the same, and for one reason — the account document
 * has exactly one writer, and a second one racing it would be the first way to
 * lose a queued mutation.
 */
interface AccountImportRecords {

    /** The signed-in account's user id, or null when nobody is signed in. */
    fun accountId(): String?

    /** Every import this device has started for the signed-in account and not finished. */
    suspend fun importRecords(): List<PublicationImportRecord>

    /** Stores [record], replacing any earlier state of the same import. */
    suspend fun putImportRecord(record: PublicationImportRecord)

    /** Forgets the import [clientImportId] names. */
    suspend fun dropImportRecord(clientImportId: String)
}

/**
 * A host record was given a key the account document reserves (#149).
 *
 * [key] is either one the schema declares at that level — which would win over
 * the record when the document is written — or `host`, which is dropped when it
 * is read back. Either way the record would be stored and then silently lost, so
 * the write is refused instead. [bookLevel] says which level the key was for.
 */
class ReservedHostRecordKeyException(
    val key: String,
    val bookLevel: Boolean,
) : IllegalArgumentException(
    "\"$key\" is reserved by the account document ${if (bookLevel) "for a book row" else "at the top level"}; " +
        "a host record under it would be lost",
)

/**
 * The host's own records in the account document (#147).
 *
 * A host keeps a little state of its own beside the account's — FastReader
 * keeps its verified-copy references and the resume offers a reader answered —
 * and it has to be written by the same single writer as everything else in the
 * document, because a second writer racing it would be the first way to lose a
 * queued mutation. These are that writer's host-facing half: values the engine
 * stores verbatim and never reads, never queues and never sends.
 *
 * Every update runs under the engine's lock, so a read-modify-write here is
 * atomic with every other write to the document. An update while nobody is
 * signed in does nothing; an update that changes nothing writes nothing. Neither
 * publishes a new state: a host record changes no row the account describes.
 *
 * **A `transform` must not call back into the engine** (#149) — not this
 * interface, not [AccountImportRecords], not [AccountLibraryActions], not
 * [AccountSyncEngine.requestSync]. It runs while the engine holds its lock, and
 * that lock is not re-entrant: a callback that waits on the engine from inside
 * a transform would wait for ever. [AccountSyncEngine] detects the call made
 * from the transform's own thread and throws [IllegalStateException] instead of
 * hanging; one handed to another thread and awaited is not detectable, and is a
 * deadlock. Compute what the transform needs *before* calling the update, and
 * act on its outcome *after* it returns.
 *
 * **A `transform` must be pure** (#149): a function of its argument, with no
 * side effects. The guard is a flag on the transform's thread, so a side effect
 * that synchronously resumes another coroutine on that thread — completing a
 * deferred, emitting to a flow collected on `Dispatchers.Unconfined`, a nested
 * `runBlocking` that drains the thread's event loop — would run that coroutine
 * inside the flag, and an engine call it makes would throw although it is not
 * the transform's own.
 *
 * A key the document reserves — one its schema declares at that level, or
 * `host` — is refused with [ReservedHostRecordKeyException] before anything is
 * written (#149), because a record under it would be stored and then lost.
 */
interface AccountHostRecords {

    /** The signed-in account's user id, or null when nobody is signed in. */
    fun accountId(): String?

    /** The document-level host record [key], or null when there is none or nobody is signed in. */
    suspend fun hostRecord(key: String): JsonElement?

    /**
     * Replaces the document-level host record [key] with what [transform]
     * returns for its current value; null removes it.
     *
     * [transform] runs under the engine's lock and must not call back into the
     * engine. Throws [ReservedHostRecordKeyException] when [key] is in
     * [AccountLibraryCodec.RESERVED_DOCUMENT_KEYS].
     */
    suspend fun updateHostRecord(key: String, transform: (JsonElement?) -> JsonElement?)

    /**
     * Replaces the host record [key] on [bookId]'s row with what [transform]
     * returns for its current value; null removes it. A book the account has no
     * row for is skipped rather than invented.
     *
     * [transform] runs under the engine's lock and must not call back into the
     * engine. Throws [ReservedHostRecordKeyException] when [key] is in
     * [AccountLibraryCodec.RESERVED_BOOK_KEYS].
     */
    suspend fun updateBookHostRecord(bookId: String, key: String, transform: (JsonElement?) -> JsonElement?)
}

/**
 * The account library: one store per account, and the foreground-driven engine
 * that keeps it in step with the backend (AD-20, AD-21, AD-22).
 *
 * ## What one trigger does
 *
 * 1. Reads `reader.sync.v1` once per signed-in session, *before* anything else.
 *    Unavailable means a deferred state carrying the backend's own reason and
 *    **no library request at all** — not a sign-out, and not an error.
 * 2. Drains the outbox, in the contract's batches of at most
 *    [ReaderSyncMutationBatchRequest.MAX_MUTATIONS]. Always before the stream
 *    is read, so this device's own change is never applied to it twice.
 * 3. Reads the stream from the stored cursor until `has_more` is false — or
 *    bootstraps, when there is no cursor yet or the stream says the cursor is
 *    expired, invalid or ahead, or when the backend refused a library change for
 *    good (the repair read, #151). A bootstrap merges the lists into the stored
 *    rows and re-applies the queued changes; it never rebuilds them.
 *
 * ## What it never does
 *
 * It resolves no conflict and prompts for none (AD-22): every result and every
 * change is adopted from the backend's canonical payload, a `conflict` result
 * included. It runs no scheduler, holds no wake lock and registers no receiver
 * (AD-21): the only things that make it run are the triggers of
 * [AccountSyncTrigger]. It writes nothing but the account document — a host's
 * own storage is the host's. And it sends nothing but
 * `library_item` and `reading_progress` mutations: no `profile`, `settings`,
 * `note` or `bookmark` envelope exists in this file. A `reading_progress`
 * envelope carries the portable position and only ever that — the section, the
 * fraction and the percent — because [PortableProgress] builds its
 * payload from the contract's own body type, which has no field a host's reading
 * unit or a reading speed could travel in (REQ-512, #120).
 *
 * It also never decides which of two positions is further along. The backend
 * does, by admission order (`reader.activity-convergence.v1`), so a position
 * that moves backwards is published exactly like one that moves forwards and
 * there is no `max`, latest-wins or percentage comparison on a position
 * anywhere in this file.
 *
 * ## Sign-out (D4)
 *
 * Signing out stops reading the store and keeps the file: the queue is held
 * for the next sign-in to the *same* account. Signing in as a different user
 * id starts from that id's own document and discards the previous account's
 * held queue, which is the one thing D4 says is not kept. A session the
 * backend no longer accepts arrives as `ReaderAuthException.SignedOut` and
 * ends in the same state, with the backend's reason shown once.
 */
class AccountSyncEngine(
    private val gateway: ReaderLibraryGateway?,
    private val stores: AccountLibraryStores,
    accountState: Flow<AccountSession>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Minted once per queued mutation and then persisted; never re-minted for a retry. */
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> String = { kotlin.time.Clock.System.now().toString() },
) : AccountLibraryActions, AccountImportRecords, AccountHostRecords {

    private val mutex = Mutex()

    /**
     * True on the thread that is running a host's `transform`, for as long as it
     * runs (#149).
     *
     * A transform is a plain function called under [mutex], which is not
     * re-entrant, so anything it does that waits on the engine — a `runBlocking`
     * around one of the suspend calls here — would wait for ever. The transform
     * cannot suspend, so it runs start to finish on the thread that set this,
     * and every public entry point checks it and fails instead of hanging.
     */
    private val inHostCallback = ThreadLocal.withInitial { false }

    private val _state = MutableStateFlow(AccountLibraryState.SIGNED_OUT)

    /** The account library as the shelf builds from it. */
    val state: StateFlow<AccountLibraryState> = _state.asStateFlow()

    private var active: ActiveAccount? = null

    /** Cleared on every session change: the capability is read once per signed-in session. */
    private var capabilityConfirmed: Boolean = false

    /** A session the backend rejected, kept until the signed-out state has shown it once. */
    private var pendingSessionGone: AccountSyncError.SessionGone? = null

    /**
     * What this device last published for each account book, as AD-25's
     * section-or-whole-percent key.
     *
     * This device's memory of its own sends and nothing else: it is compared
     * only with the next local position, never with a remote one, and it selects
     * no winner. In memory on purpose — see [recordPosition].
     */
    private val published = mutableMapOf<String, Pair<String?, Int>>()

    /**
     * A `reading_progress` record this app could not place on a book, kept until
     * the next settled state has surfaced it once.
     *
     * The same shape as [pendingSessionGone] and for the same reason: the record
     * is noticed while the stream is being read, and the state that reports it is
     * published after. Without this the mapping derivation could be wrong for as
     * long as nobody happened to look — see
     * [AccountSyncError.UnrecognizedProgressRecord].
     */
    private var progressMismatch: AccountSyncError.UnrecognizedProgressRecord? = null

    private class ActiveAccount(
        val userId: String,
        val store: AccountLibraryStore,
        var document: AccountLibraryDocument,
    )

    init {
        scope.launch {
            accountState
                .map(::targetOf)
                .mapNotNull { it }
                .distinctUntilChanged()
                .collect { target ->
                    when (target) {
                        is SessionTarget.None -> signOut(target.notConfigured)
                        is SessionTarget.User -> signIn(target.userId)
                    }
                }
        }
    }

    /** Run one sync. Safe to call from any trigger; concurrent calls queue on the same lock. */
    fun requestSync(trigger: AccountSyncTrigger) {
        checkNotInHostCallback()
        scope.launch { sync(trigger) }
    }

    override fun refresh() = requestSync(AccountSyncTrigger.MANUAL_REFRESH)

    override fun removeFromAccount(bookId: String) =
        enqueue(bookId, ReaderMutationKind.DELETE, JsonObject(emptyMap()))

    override fun undoRemove(bookId: String) =
        enqueue(bookId, ReaderMutationKind.RESTORE, JsonObject(emptyMap()))

    override fun recordOpened(bookId: String) {
        val at = now()
        val payload = buildJsonObject {
            put("status", JsonPrimitive(ReaderLibraryStatus.READING.wireName()))
            put("last_opened_at", JsonPrimitive(at))
        }
        enqueue(bookId, ReaderMutationKind.UPSERT, payload)
    }

    override fun recordFinished(bookId: String) = recordStatus(bookId, ReaderLibraryStatus.FINISHED)

    override fun recordStatus(bookId: String, status: ReaderLibraryStatus) {
        val payload = buildJsonObject { put("status", JsonPrimitive(status.wireName())) }
        enqueue(bookId, ReaderMutationKind.UPSERT, payload)
    }

    /**
     * Publishes a portable position, when it says something new.
     *
     * The guard is AD-25's, and it is the whole reason reading a long chapter is
     * not a stream of mutations: a flush publishes only when the section or the
     * *whole percent* differs from what this device last published for that book.
     * [published] is this device's own memory of its own sends — it is never
     * compared with a remote position and never decides a winner.
     *
     * It is deliberately in memory rather than in the document. The cost of
     * forgetting it is one redundant upsert after a cold start, which the
     * backend admits like any other; the cost of persisting it would be a
     * durable write on the path whose entire purpose is to avoid durable writes.
     *
     * Nothing about the local position is queued: [PortableProgress]
     * builds the payload from the contract's own body type, so the token index
     * and the reading speed have no field to travel in (REQ-512).
     */
    override fun recordPosition(bookId: String, position: LocalReadingPosition) {
        checkNotInHostCallback()
        if (published[bookId] == position.publishKey) return
        published[bookId] = position.publishKey
        enqueue(
            bookId = bookId,
            kind = ReaderMutationKind.UPSERT,
            payload = PortableProgress.payloadFor(bookId, position),
            resourceType = ReaderResourceType.READING_PROGRESS,
        )
    }

    // ---------------------------------------------------------- import records

    override fun accountId(): String? {
        checkNotInHostCallback()
        return active?.userId
    }

    override suspend fun importRecords(): List<PublicationImportRecord> =
        locked { active?.document?.imports.orEmpty() }

    /**
     * Stores [record] under the one writer of the account document.
     *
     * Silently does nothing when nobody is signed in, which is the right answer
     * rather than a failure: a transfer whose session went away has nowhere to
     * be remembered, and the shelf has already lost the account rows it would
     * have bound to.
     */
    override suspend fun putImportRecord(record: PublicationImportRecord) {
        locked {
            val account = active ?: return@locked
            if (record.accountId != account.userId) return@locked
            persistQuietly(account, account.document.withImport(record))
        }
    }

    override suspend fun dropImportRecord(clientImportId: String) {
        locked {
            val account = active ?: return@locked
            if (account.document.import(clientImportId) == null) return@locked
            persistQuietly(account, account.document.withoutImport(clientImportId))
        }
    }

    // ------------------------------------------------------------ host records

    override suspend fun hostRecord(key: String): JsonElement? =
        locked { active?.document?.host?.get(key) }

    override suspend fun updateHostRecord(key: String, transform: (JsonElement?) -> JsonElement?) {
        if (key in AccountLibraryCodec.RESERVED_DOCUMENT_KEYS) throw ReservedHostRecordKeyException(key, bookLevel = false)
        locked {
            val account = active ?: return@locked
            val host = account.document.host
            val updated = host.withRecord(key, hostCallback { transform(host[key]) })
            if (updated == host) return@locked
            persistQuietly(account, account.document.copy(host = updated))
        }
    }

    override suspend fun updateBookHostRecord(
        bookId: String,
        key: String,
        transform: (JsonElement?) -> JsonElement?,
    ) {
        if (key in AccountLibraryCodec.RESERVED_BOOK_KEYS) throw ReservedHostRecordKeyException(key, bookLevel = true)
        locked {
            val account = active ?: return@locked
            val existing = account.document.book(bookId) ?: return@locked
            val updated = existing.host.withRecord(key, hostCallback { transform(existing.host[key]) })
            if (updated == existing.host) return@locked
            persistQuietly(account, account.document.withBook(existing.copy(host = updated)))
        }
    }

    private fun JsonObject.withRecord(key: String, value: JsonElement?): JsonObject =
        if (value == null) JsonObject(this - key) else JsonObject(this + (key to value))

    private inline fun <T> hostCallback(block: () -> T): T {
        inHostCallback.set(true)
        try {
            return block()
        } finally {
            inHostCallback.set(false)
        }
    }

    private fun checkNotInHostCallback() {
        check(!inHostCallback.get()) {
            "an AccountHostRecords transform must not call back into AccountSyncEngine: it runs under the " +
                "engine's lock, which is not re-entrant"
        }
    }

    /** [block] under the engine's lock, from a public entry point: refused inside a host callback. */
    private suspend inline fun <T> locked(crossinline block: suspend () -> T): T {
        checkNotInHostCallback()
        return mutex.withLock { block() }
    }

    /**
     * Writes the document and publishes nothing.
     *
     * An import or host record changes no row the shelf draws — the account row arrives
     * from the change stream when the backend has made one (AD-23) — so a write
     * here must not re-publish a state and re-trigger the shelf's own effects.
     * A storage failure is surfaced as the deferred state every other write
     * failure is, because a record that could not be stored is exactly the case
     * where a relaunch would start a second transfer.
     */
    private suspend fun persistQuietly(account: ActiveAccount, document: AccountLibraryDocument) {
        try {
            persist(account, document)
        } catch (e: IOException) {
            publish(
                AccountSyncPhase.DEFERRED,
                AccountSyncTrigger.OWN_WRITE,
                error = AccountSyncError.StoreBlocked(e.message ?: "the account library could not be written"),
            )
        }
    }

    // ---------------------------------------------------------------- sessions

    private sealed interface SessionTarget {
        /** Nobody is signed in. [notConfigured] separates "no stage values" from "signed out". */
        data class None(val notConfigured: Boolean) : SessionTarget
        data class User(val userId: String) : SessionTarget
    }

    /** `Loading` is not a target: the stored session has not been read, so nothing changes yet. */
    private fun targetOf(state: AccountSession): SessionTarget? = when (state) {
        AccountSession.Loading -> null
        AccountSession.NotConfigured -> SessionTarget.None(notConfigured = true)
        AccountSession.SignedOut -> SessionTarget.None(notConfigured = false)
        is AccountSession.SignedIn -> SessionTarget.User(state.userId)
    }

    /**
     * D4: stop reading the store, keep the file, delete nothing. A session the
     * backend rejected shows its reason here, once.
     */
    private suspend fun signOut(notConfigured: Boolean) {
        mutex.withLock {
            active = null
            capabilityConfirmed = false
            // This device's memory of what it last published goes with the
            // session: the next sign-in publishes its first position afresh
            // rather than suppressing it because some earlier account was at the
            // same percent of some other book.
            published.clear()
            progressMismatch = null
            val reason = pendingSessionGone ?: if (notConfigured) AccountSyncError.NotConfigured else null
            pendingSessionGone = null
            publish(AccountSyncPhase.SIGNED_OUT, trigger = null, error = reason)
        }
    }

    private suspend fun signIn(userId: String) {
        val opened = mutex.withLock {
            capabilityConfirmed = false
            pendingSessionGone = null
            withContext(ioDispatcher) { discardOtherAccountQueues(userId) }
            val store = stores.forUser(userId)
            when (val load = withContext(ioDispatcher) { store.load() }) {
                is AccountLibraryLoad.Blocked -> {
                    active = null
                    publish(
                        AccountSyncPhase.DEFERRED,
                        AccountSyncTrigger.SIGN_IN,
                        error = AccountSyncError.StoreBlocked(load.message),
                    )
                    false
                }

                is AccountLibraryLoad.Loaded -> {
                    // A document naming a different account is not this account's,
                    // whatever file it was found in; start from an empty one rather
                    // than adopting somebody else's rows.
                    val stored = load.document
                    val document = if (stored.userId.isEmpty() || stored.userId == userId) {
                        stored.copy(userId = userId)
                    } else {
                        AccountLibraryDocument(userId = userId)
                    }
                    active = ActiveAccount(userId, store, document)
                    publish(
                        phase = if (document.bootstrapped) {
                            AccountSyncPhase.IDLE
                        } else {
                            AccountSyncPhase.BOOTSTRAPPING
                        },
                        trigger = AccountSyncTrigger.SIGN_IN,
                    )
                    true
                }
            }
        }
        if (opened) sync(AccountSyncTrigger.SIGN_IN)
    }

    /**
     * D4's one destructive clause: the held queue of every *other* account on
     * this device is discarded when a different user id signs in. Their rows
     * are left alone — the backend holds those, and a re-bootstrap would fetch
     * them again anyway; the queue is the only thing nothing else can replace.
     */
    private fun discardOtherAccountQueues(userId: String) {
        stores.exceptUser(userId).forEach { store ->
            val load = store.load()
            if (load is AccountLibraryLoad.Loaded && load.document.outbox.isNotEmpty()) {
                try {
                    store.save(load.document.copy(outbox = emptyList()))
                } catch (error: IOException) {
                    // Nothing is lost by leaving it: this account never reads that
                    // document, and the next sign-in to it tries again.
                }
            }
        }
    }

    // -------------------------------------------------------------------- sync

    private suspend fun sync(trigger: AccountSyncTrigger) {
        mutex.withLock {
            val account = active ?: return
            val gateway = gateway ?: run {
                publish(AccountSyncPhase.SIGNED_OUT, trigger, error = AccountSyncError.NotConfigured)
                return
            }
            try {
                if (!capabilityConfirmed) {
                    val capability = gateway.syncCapability()
                    if (!capability.isAvailable) {
                        // Deferred, with the backend's reason, and not one library
                        // request sent. Never a sign-out.
                        publish(
                            AccountSyncPhase.DEFERRED,
                            trigger,
                            capabilityReason = capability.reason,
                        )
                        return
                    }
                    capabilityConfirmed = true
                }

                publish(AccountSyncPhase.SYNCING, trigger)
                val drained = drainOutbox(account, gateway)
                when {
                    !account.document.bootstrapped -> bootstrap(account, gateway, trigger)
                    // A refused change left its optimistic row on the shelf, and the
                    // refusal carries no canonical state to put back (#151). The
                    // merge-style repair read restores the backend's truth for it
                    // and re-applies every change still queued; the next trigger
                    // reads the stream from the new barrier.
                    drained.repair -> bootstrap(account, gateway, trigger, announce = false)
                    else -> readDeltas(account, gateway, trigger)
                }
                // A rejection the backend stated outranks a record this app could
                // not place; both are surfaced, a rejection first.
                publish(AccountSyncPhase.IDLE, trigger, error = drained.rejection ?: takeProgressMismatch())
            } catch (e: ReaderAuthException) {
                onFailure(e, trigger)
            } catch (e: IOException) {
                publish(
                    AccountSyncPhase.DEFERRED,
                    trigger,
                    error = AccountSyncError.StoreBlocked(e.message ?: "the account library could not be written"),
                )
            }
        }
    }

    private fun onFailure(error: ReaderAuthException, trigger: AccountSyncTrigger) {
        val syncError = error.toSyncError()
        if (syncError is AccountSyncError.SessionGone) {
            // D4: the same signed-out state as an explicit sign-out. The file
            // stays; `:reader-auth` drops the session, and the session flow
            // brings the signed-out state along behind this.
            active = null
            capabilityConfirmed = false
            pendingSessionGone = syncError
            publish(AccountSyncPhase.SIGNED_OUT, trigger, error = syncError)
            return
        }
        val phase = when (syncError) {
            is AccountSyncError.NetworkUnavailable -> AccountSyncPhase.OFFLINE
            is AccountSyncError.TryLater -> AccountSyncPhase.DEFERRED
            else -> AccountSyncPhase.IDLE
        }
        publish(phase, trigger, error = syncError)
    }

    /**
     * Sends every queued mutation, oldest first, and adopts each result.
     *
     * The store is rewritten after every batch, so what it holds is always
     * exactly what has not been admitted: a process death mid-drain loses no
     * entry, and the entries it does still hold carry their original
     * idempotency keys, so the next attempt is a replay rather than a second
     * admission.
     *
     * Returns the last rejection the backend reported, so the caller can
     * surface its code — a rejection is not a failure of the run — and whether a
     * refused library change needs the repair read (#151).
     */
    private suspend fun drainOutbox(
        account: ActiveAccount,
        gateway: ReaderLibraryGateway,
    ): DrainOutcome {
        val pending = account.document.outbox
        if (pending.isEmpty()) return DrainOutcome(rejection = null, repair = false)

        val kept = mutableListOf<AccountOutboxEntry>()
        var rejection: AccountSyncError.Rejected? = null
        var repair = false
        var index = 0
        while (index < pending.size) {
            val batch = pending.subList(index, minOf(index + MAX_BATCH, pending.size))
            val response = try {
                gateway.applyMutations(batch.map { it.toEnvelope() })
            } catch (e: ReaderAuthException) {
                // Nothing in this batch was admitted: keep it, and everything
                // after it, under the same keys.
                persist(account, account.document.copy(outbox = kept + pending.drop(index)))
                throw e
            }
            val byKey = response.results.associateBy { it.idempotencyKey }
            var document = account.document
            for (entry in batch) {
                val result = byKey[entry.idempotencyKey]
                if (result == null) {
                    // No answer for this envelope: keep it and retry under the
                    // same key, which the backend will replay if it did admit it.
                    kept += entry
                    continue
                }
                document = adopt(document, result)
                if (result.status == ReaderSyncStatus.REJECTED) {
                    val retryable = result.rejection?.retryable == true
                    result.rejection?.let { rejection = it.toSyncError(result.resourceId) }
                    if (retryable) {
                        kept += entry
                    } else if (entry.resourceType.isLibraryItem()) {
                        // Dropped for good, and its optimistic row with it: a
                        // refusal carries `{}`, so nothing here can say what the
                        // row was before. The repair read does (#151). A refused
                        // position changed no row, so it needs none.
                        repair = true
                    }
                }
            }
            index += batch.size
            persist(account, document.copy(outbox = kept + pending.drop(index)))
        }
        return DrainOutcome(rejection = rejection, repair = repair)
    }

    /** What one drain of the outbox reports to the run that made it. */
    private class DrainOutcome(
        /** The last rejection the backend stated, surfaced with its own code. */
        val rejection: AccountSyncError.Rejected?,
        /** A non-retryable rejection dropped a library change whose optimistic row must be undone. */
        val repair: Boolean,
    )

    /**
     * Reads the account's lists and takes the stream's head as the cursor.
     *
     * The head is read *first*, on purpose: a change admitted between the head
     * read and the lists is then still after the stored cursor and arrives with
     * the next delta read. Reading it afterwards would place the cursor past a
     * change the lists never showed, and that change would be lost. `latest_cursor`
     * is present in every delta answer, an expired one included, so the head read
     * asks for one change and uses nothing but that field.
     *
     * ## A merge, not a rebuild (#151)
     *
     * The snapshot is merged into the stored rows, because the stored rows carry
     * what no list read can state:
     *
     * - a book the lists still return keeps its host records and its known
     *   revision ([AccountBook.of]); every field the lists state is theirs;
     * - a book the lists no longer return is gone from the account, so its row
     *   goes — host records with it;
     * - every change still queued in the outbox is re-applied on top, in queue
     *   order ([withQueuedIntents]), which is client contract §7.2 step 5:
     *   "reconcile the saved outbox against this canonical state". The queue
     *   itself is untouched — same entries, same idempotency keys — so nothing is
     *   re-sent or re-minted.
     *
     * The document's own host records, import records and outbox are carried
     * through as they stand. [announce] is false for the repair read after a
     * refusal: the shelf is not being loaded, only corrected, so it stays in the
     * syncing phase.
     */
    private suspend fun bootstrap(
        account: ActiveAccount,
        gateway: ReaderLibraryGateway,
        trigger: AccountSyncTrigger,
        announce: Boolean = true,
    ) {
        if (announce) publish(AccountSyncPhase.BOOTSTRAPPING, trigger)
        val head = gateway.deltas(ReaderLibraryClient.FIRST_CURSOR, HEAD_LIMIT)
        val library = gateway.library()
        val positions = gateway.progress().progress.associateBy { it.bookId }
        val stored = account.document
        val books = library.items.map { item ->
            val row = AccountBook.of(item, held = stored.book(item.book.id))
            positions[row.bookId]?.let { progress ->
                // The bootstrap's second list is keyed by `book_id`, which the
                // document marks required on `ReaderProgress` — so unlike the
                // stream there is nothing to derive here, and the portable
                // location is read through the same seam the stream uses (#120, #139).
                //
                // A list read carries no revision. When it returns the very record
                // this device already held — same server `updated_at` — the stored
                // revision stands, and so does the mark that the record is this
                // device's own (#140); a re-bootstrap must not turn this device's
                // own position into "another device's". A different record keeps
                // neither.
                val held = stored.book(row.bookId)
                    ?.takeIf { it.remotePosition?.updatedAt == progress.updatedAt }
                val remote = AccountCanonicalPayload.remotePosition(
                    position = PortableProgress.positionOf(
                        buildJsonObject {
                            put("location", Json.encodeToJsonElement(ReaderPortableLocationV1.serializer(), progress.location))
                            put("progress_percent", JsonPrimitive(progress.progressPercent))
                            put("updated_at", JsonPrimitive(progress.updatedAt))
                            progress.chapterTitle?.let { put("chapter_title", JsonPrimitive(it)) }
                        },
                    ),
                    existing = held?.remotePosition,
                    revision = null,
                    serverAdmittedAt = null,
                )
                row.copy(
                    progressPercent = progress.progressPercent,
                    progressUpdatedAt = progress.updatedAt,
                    remotePosition = remote,
                    ownPositionChangeKey = held?.ownPositionChangeKey?.takeIf { it == remote.changeKey },
                )
            } ?: row
        }
        persist(account, stored.copy(books = books, cursor = head.latestCursor).withQueuedIntents())
    }

    /** Reads the stream from the stored cursor until `has_more` is false. */
    private suspend fun readDeltas(
        account: ActiveAccount,
        gateway: ReaderLibraryGateway,
        trigger: AccountSyncTrigger,
    ) {
        val stored = account.document.cursor
        if (stored == null || !CURSOR.matches(stored)) {
            // A cursor this client cannot send is no cursor: start over rather
            // than hand `:reader-library` a value it refuses.
            bootstrap(account, gateway, trigger)
            return
        }
        var cursor: String = stored
        while (true) {
            val response = gateway.deltas(cursor, ReaderLibraryClient.DEFAULT_DELTA_LIMIT)
            if (response.rebootstrapRequired ||
                response.status == ReaderDeltaStatus.CURSOR_EXPIRED ||
                response.status == ReaderDeltaStatus.CURSOR_INVALID
            ) {
                bootstrap(account, gateway, trigger)
                return
            }
            var document = account.document
            for (change in response.changes) {
                document = applyCanonical(
                    document = document,
                    resourceType = change.resourceType,
                    resourceId = change.resourceId,
                    kind = change.kind,
                    payload = change.canonicalPayload,
                    revision = change.revision,
                    serverAdmittedAt = change.serverAdmittedAt,
                    origin = CanonicalOrigin.STREAM,
                )
            }
            val next = response.nextCursor ?: response.latestCursor
            persist(account, document.copy(cursor = next))
            // A page that says there is more but does not advance would loop for
            // ever; stop and let the next trigger try again.
            if (!response.hasMore || next == cursor) break
            cursor = next
        }
    }

    // ------------------------------------------------------------- own writes

    /**
     * Queues one mutation and runs a sync for it.
     *
     * The row takes the entry's queued intent at once ([intentOf]) — the removal
     * leaving the shelf, the status the reader just set — so the shelf reacts
     * while offline. It decides nothing: the moment the backend answers, its
     * canonical payload replaces the row wholesale (AD-22). The intent is derived
     * from the stored entry alone, so a re-bootstrap re-applies exactly the same
     * change on top of the snapshot (#151, §7.2 step 5).
     */
    private fun enqueue(
        bookId: String,
        kind: ReaderMutationKind,
        payload: JsonObject,
        /**
         * Which resource the envelope is about. `library_item` for everything the
         * shelf does; `reading_progress` only for a published position (AD-25).
         * No other member of the enum is ever passed here — there is no
         * `profile`, `settings`, `note` or `bookmark` caller in this file.
         */
        resourceType: ReaderResourceType = ReaderResourceType.LIBRARY_ITEM,
    ) {
        checkNotInHostCallback()
        scope.launch {
            val queued = mutex.withLock {
                val account = active ?: return@withLock false
                val existing = account.document.book(bookId)
                val entry = AccountOutboxEntry(
                    idempotencyKey = newIdempotencyKey(),
                    resourceType = resourceType,
                    resourceId = bookId,
                    mutationKind = kind,
                    // The revision of the resource this envelope is about, which
                    // for a position is the *progress* resource's own and not the
                    // library item's — two resources whose revisions have nothing
                    // to do with each other. 0 for one this device has never seen,
                    // which is what the contract asks for.
                    baseRevision = when (resourceType) {
                        ReaderResourceType.READING_PROGRESS -> existing?.remotePosition?.revision ?: 0
                        else -> existing?.revision ?: 0
                    },
                    payload = payload,
                    clientCreatedAt = now(),
                )
                var document = account.document.copy(outbox = account.document.outbox + entry)
                if (existing != null) document = document.withBook(intentOf(entry, existing))
                try {
                    persist(account, document)
                } catch (e: IOException) {
                    publish(
                        AccountSyncPhase.DEFERRED,
                        AccountSyncTrigger.OWN_WRITE,
                        error = AccountSyncError.StoreBlocked(
                            e.message ?: "the account library could not be written",
                        ),
                    )
                    return@withLock false
                }
                publish(AccountSyncPhase.SYNCING, AccountSyncTrigger.OWN_WRITE)
                true
            }
            if (queued) sync(AccountSyncTrigger.OWN_WRITE)
        }
    }

    /**
     * The row as [entry]'s queued intent leaves it: what the shelf shows while
     * the change waits to be admitted.
     *
     * A pure function of the stored entry, so the intent applied when the change
     * is queued and the intent re-applied on top of a re-bootstrap's snapshot
     * (#151) are one and the same. A removal takes the row off the shelf, an Undo
     * puts it back, a status upsert sets the status and, when the payload names
     * one, the last-opened time. A position changes no library row — its
     * account-side record arrives with the backend's answer.
     */
    private fun intentOf(entry: AccountOutboxEntry, book: AccountBook): AccountBook {
        if (!entry.resourceType.isLibraryItem()) return book
        return when (entry.mutationKind) {
            ReaderMutationKind.DELETE -> book.copy(removed = true)
            ReaderMutationKind.RESTORE -> book.copy(removed = false)
            ReaderMutationKind.UPSERT -> {
                val status = (entry.payload["status"] as? JsonPrimitive)?.content
                    ?.let { wire -> ReaderLibraryStatus.entries.firstOrNull { it.wireName() == wire } }
                book.copy(
                    status = status ?: book.status,
                    lastOpenedAt = if (entry.payload.containsKey("last_opened_at")) {
                        (entry.payload["last_opened_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    } else {
                        book.lastOpenedAt
                    },
                )
            }
            ReaderMutationKind.UNKNOWN -> book
        }
    }

    /**
     * Every queued change's intent, re-applied in queue order on top of this
     * document's rows (client contract §7.2 step 5, #151). A change for a book
     * the account holds no row for invents none, exactly as queueing it did not.
     */
    private fun AccountLibraryDocument.withQueuedIntents(): AccountLibraryDocument =
        outbox.fold(this) { document, entry ->
            document.book(entry.resourceId)?.let { document.withBook(intentOf(entry, it)) } ?: document
        }

    private fun ReaderResourceType.isLibraryItem(): Boolean =
        this == ReaderResourceType.LIBRARY_ITEM || this == ReaderResourceType.BOOK

    // ------------------------------------------------------------- adoption

    /**
     * Adopts one mutation result. A rejection carries an empty canonical
     * payload by contract and changes no row here — a non-retryable one is
     * undone by the repair read the drain asks for (#151); everything else — `applied`,
     * `replayed`, `superseded` and `conflict` alike — is adopted as it stands,
     * unless its revision is older than the one already stored (§7.3, #147: a
     * late answer never moves a book's state backwards).
     *
     * `applied` and `replayed` are the backend admitting *this device's own*
     * mutation, so for a position they are recorded as this device's own
     * (#140). `superseded` and `conflict` carry the position that beat it —
     * somebody else's — and are adopted without that mark.
     */
    private fun adopt(
        document: AccountLibraryDocument,
        result: ReaderSyncMutationResult,
    ): AccountLibraryDocument {
        if (result.status == ReaderSyncStatus.REJECTED) return document
        val payload = result.canonicalPayload.takeIf { it.isNotEmpty() }
            ?: result.conflict?.canonicalPayload
            ?: result.canonicalPayload
        return applyCanonical(
            document = document,
            resourceType = result.resourceType,
            resourceId = result.resourceId,
            kind = result.mutationKind,
            payload = payload,
            revision = result.revision ?: result.conflict?.remoteRevision,
            serverAdmittedAt = result.serverAdmittedAt,
            origin = when (result.status) {
                ReaderSyncStatus.APPLIED, ReaderSyncStatus.REPLAYED -> CanonicalOrigin.ADMITTED_HERE
                else -> CanonicalOrigin.RESULT
            },
        )
    }

    /** Where a canonical payload came from, which decides the two rules below that differ by source. */
    private enum class CanonicalOrigin {
        /** This device's own mutation, `applied` or `replayed`: the canonical result is this device's write. */
        ADMITTED_HERE,

        /**
         * Any other mutation result — `superseded` or `conflict` — adopted as it
         * stands. Its mutation kind is this device's request, not the outcome, so
         * a library item's presence is read from the canonical payload ([present]).
         */
        RESULT,

        /** A change read from the account's change stream, which names no originating client. */
        STREAM,
    }

    private fun applyCanonical(
        document: AccountLibraryDocument,
        resourceType: ReaderResourceType,
        resourceId: String,
        kind: ReaderMutationKind,
        payload: JsonObject,
        revision: Long?,
        /** The server's admission time for this change, when it came from the stream. */
        serverAdmittedAt: String? = null,
        origin: CanonicalOrigin,
    ): AccountLibraryDocument = when (resourceType) {
        ReaderResourceType.LIBRARY_ITEM, ReaderResourceType.BOOK -> {
            val existing = document.book(resourceId)
            if (existing != null && revision != null && !replaces(revision, existing.revision, origin)) {
                // Client contract §7.3 (#147, #149): a stream change is applied only
                // when its revision is strictly newer than the stored one, and a
                // mutation result only when it is not older. An older answer — a late
                // `replayed` result, an out-of-order delivery — must not move the row
                // back or take it off the shelf; an equal-revision stream change is
                // the state this device already holds, and applying it would revert
                // a local intent still queued in the outbox (a pending removal would
                // reappear). A *result* at an equal revision is different (§7.4): it
                // is the backend's verdict on this device's own queued mutation, and
                // it is what discards an optimistic row the backend did not keep
                // (`superseded`, `conflict`).
                document
            } else if (present(kind, payload, origin)) {
                val row = AccountCanonicalPayload.libraryItem(existing, resourceId, payload, revision)
                document.withBook(row.copy(removed = false))
            } else {
                existing
                    ?.let { document.withBook(it.copy(removed = true, revision = revision ?: it.revision)) }
                    ?: document
            }
        }

        // Which book a position record is about is a *derivation*, not a fact the
        // document states, so it goes through the one seam that owns it and a
        // record this app cannot place becomes a visible outcome rather than a
        // dropped one (REQ-511).
        ReaderResourceType.READING_PROGRESS ->
            when (
                val record = PortableProgress.recordFor(
                    resourceId = resourceId,
                    payload = payload,
                    knownBook = { document.book(it) != null },
                )
            ) {
                is ProgressRecord.Recognized -> {
                    val existing = document.book(record.bookId)
                    val stored = existing?.remotePosition
                    if (existing != null && stored != null && revision != null && revision <= stored.revision) {
                        // Client contract §7.3: a result or stream change is applied
                        // only when its revision is newer than the one this device
                        // already holds for the resource — whatever its source
                        // (#147: a late `replayed` result used to overwrite a newer
                        // position the stream had delivered). The stream names no
                        // originating client, so this is also what recognises this
                        // device's own admitted position coming back to it (#140):
                        // it carries the very revision §7.4 stored when the result
                        // was adopted. An admitted result at the *same* revision is
                        // that same record, so it is still marked as this device's.
                        if (origin == CanonicalOrigin.ADMITTED_HERE && revision == stored.revision &&
                            existing.ownPositionChangeKey != stored.changeKey
                        ) {
                            document.withBook(existing.copy(ownPositionChangeKey = stored.changeKey))
                        } else {
                            document
                        }
                    } else {
                        AccountCanonicalPayload.readingProgress(
                            existing = existing,
                            payload = payload,
                            position = record.position,
                            revision = revision,
                            serverAdmittedAt = serverAdmittedAt,
                        )
                            ?.let { row ->
                                if (origin == CanonicalOrigin.ADMITTED_HERE) {
                                    row.copy(ownPositionChangeKey = row.remotePosition?.changeKey)
                                } else {
                                    row
                                }
                            }
                            ?.let(document::withBook)
                            ?: document
                    }
                }

                is ProgressRecord.Unrecognized -> {
                    progressMismatch = AccountSyncError.UnrecognizedProgressRecord(
                        resourceId = record.resourceId,
                        payloadBookId = record.payloadBookId,
                        reason = record.reason,
                    )
                    document
                }
            }

        // profile, settings, note and bookmark are never sent and never shown.
        ReaderResourceType.PROFILE,
        ReaderResourceType.SETTINGS,
        ReaderResourceType.NOTE,
        ReaderResourceType.BOOKMARK,
        ReaderResourceType.UNKNOWN,
        -> document
    }

    /**
     * Whether the canonical state of a library item is a live membership (true)
     * or the tombstone (false).
     *
     * A stream change and this device's own admitted mutation say it with their
     * kind: the stream's `kind` is the canonical change kind, and an `applied` or
     * `replayed` result is the backend doing exactly what was asked. A
     * `superseded` or `conflict` result is the backend's verdict *against* the
     * mutation, so its kind is this device's request, not the outcome (§7.4,
     * #149): presence comes from the canonical payload the backend returned. The
     * backend's canonical state for a removed membership is the empty object
     * (reader-api `publication_membership.py`, `_structural_conflict` at
     * `909174af`), and a live one is the item's body — so a queued removal the
     * backend answered with the live book ends on the shelf, and a queued upsert
     * answered with a tombstone ends off it.
     */
    private fun present(kind: ReaderMutationKind, payload: JsonObject, origin: CanonicalOrigin): Boolean =
        when (origin) {
            CanonicalOrigin.RESULT -> payload.isNotEmpty()
            CanonicalOrigin.ADMITTED_HERE, CanonicalOrigin.STREAM -> kind != ReaderMutationKind.DELETE
        }

    /**
     * Whether a library item's canonical state at [incoming] replaces the one
     * stored at [stored] (§7.3, §7.4): a stream change must be strictly newer, a
     * mutation result must be equal or newer.
     */
    private fun replaces(incoming: Long, stored: Long, origin: CanonicalOrigin): Boolean = when (origin) {
        CanonicalOrigin.STREAM -> incoming > stored
        CanonicalOrigin.ADMITTED_HERE, CanonicalOrigin.RESULT -> incoming >= stored
    }

    // -------------------------------------------------------------- plumbing

    /**
     * The unplaceable progress record, if one arrived, and clears it.
     *
     * Taken rather than read so it is reported once per occurrence: the next
     * settled state after a clean run says nothing, which is what makes the
     * report mean "this happened just now" rather than "this happened once".
     */
    private fun takeProgressMismatch(): AccountSyncError.UnrecognizedProgressRecord? {
        val mismatch = progressMismatch
        progressMismatch = null
        return mismatch
    }

    private suspend fun persist(account: ActiveAccount, document: AccountLibraryDocument) {
        val stamped = document.copy(userId = account.userId)
        withContext(ioDispatcher) { account.store.save(stamped) }
        account.document = stamped
    }

    private fun publish(
        phase: AccountSyncPhase,
        trigger: AccountSyncTrigger?,
        error: AccountSyncError? = null,
        capabilityReason: ReaderCapabilityReason? = null,
    ) {
        val account = active
        val document = account?.document
        _state.value = AccountLibraryState(
            phase = phase,
            userId = account?.userId,
            books = document?.books?.filterNot { it.removed }.orEmpty(),
            queued = document?.outbox?.size ?: 0,
            lastError = error,
            capabilityReason = capabilityReason,
            lastTrigger = trigger,
        )
    }

    private companion object {
        val MAX_BATCH: Int = ReaderSyncMutationBatchRequest.MAX_MUTATIONS

        /** The head read wants `latest_cursor` and nothing else, so it asks for one change. */
        const val HEAD_LIMIT: Int = ReaderLibraryClient.MIN_DELTA_LIMIT

        /** The shape `:reader-library` accepts as a cursor. */
        val CURSOR = Regex("^[0-9]+$")
    }
}
