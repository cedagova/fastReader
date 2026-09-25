package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncMutationBatchRequest
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
public class AccountSyncEngine(
    private val gateway: ReaderLibraryGateway?,
    private val stores: AccountLibraryStores,
    accountState: Flow<AccountSession>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Minted once per queued mutation and then persisted; never re-minted for a retry. */
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> String = { kotlin.time.Clock.System.now().toString() },
) : AccountLibraryActions,
    AccountImportRecords,
    AccountHostRecords {

    private val mutex = Mutex()

    /** Refuses a call back into the engine from a host's `transform` (#149). */
    private val hostCallbacks = HostCallbackGuard()

    private val _state = MutableStateFlow(AccountLibraryState.SIGNED_OUT)

    /** The account library as the shelf builds from it. */
    public val state: StateFlow<AccountLibraryState> = _state.asStateFlow()

    /** Written only under [mutex]; volatile because [accountId] reads it without the lock. */
    @Volatile
    private var active: ActiveAccount? = null

    /**
     * Whose session the engine is in, as a caller sees it at the moment it acts
     * (#162). Read without the lock by [enqueue], written under [mutex] by
     * [signIn] and [signOut] only.
     */
    @Volatile
    private var owner: ChangeOwner = ChangeOwner.Unresolved

    /**
     * The first session the engine settles on: its user id, or null for nobody
     * (#163). A change asked for before then — during `Loading` — waits for it
     * rather than being dropped.
     */
    private val firstSession = CompletableDeferred<String?>()

    /**
     * Every own write, in the order it was asked for (#161). One consumer —
     * [runQueue] — takes them off in that order, so the outbox order is the call
     * order whatever dispatcher the host's scope runs on.
     */
    private val changes = Channel<QueuedChange>(Channel.UNLIMITED)

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
     *
     * Read and written only under [mutex], and a key is recorded only once its
     * entry is in the outbox on disk (#163): a position that was never queued is
     * never taken for one that was.
     */
    private val published = mutableMapOf<String, Pair<String?, Int>>()

    /** Adopts the backend's canonical state; holds an unplaced progress record until it is surfaced. */
    private val adoption = CanonicalStateAdoption()

    /** One run's exchange with the backend: drain, then stream read or bootstrap. */
    private val exchange = AccountLibraryExchange(adoption) { trigger ->
        publish(AccountSyncPhase.BOOTSTRAPPING, trigger)
    }

    init {
        scope.launch { runQueue() }
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
    public fun requestSync(trigger: AccountSyncTrigger) {
        hostCallbacks.checkNotInHostCallback()
        scope.launch { sync(trigger) }
    }

    override fun refresh(): Unit = requestSync(AccountSyncTrigger.MANUAL_REFRESH)

    override fun removeFromAccount(bookId: String): Unit =
        enqueue(bookId, ReaderMutationKind.DELETE, JsonObject(emptyMap()))

    override fun undoRemove(bookId: String): Unit = enqueue(bookId, ReaderMutationKind.RESTORE, JsonObject(emptyMap()))

    override fun recordOpened(bookId: String) {
        val at = now()
        val payload = buildJsonObject {
            put("status", JsonPrimitive(ReaderLibraryStatus.READING.wireName()))
            put("last_opened_at", JsonPrimitive(at))
        }
        enqueue(bookId, ReaderMutationKind.UPSERT, payload)
    }

    override fun recordFinished(bookId: String): Unit = recordStatus(bookId, ReaderLibraryStatus.FINISHED)

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
     * The guard is applied where the change reaches the outbox, under the
     * engine's lock, and the key is recorded only once the entry is persisted
     * (#163): a position that could not be queued — the store refused it, or the
     * session was still loading — is not remembered as sent, so the next flush
     * with the same key queues it.
     *
     * Nothing about the local position is queued: [PortableProgress]
     * builds the payload from the contract's own body type, so the token index
     * and the reading speed have no field to travel in (REQ-512).
     */
    override fun recordPosition(bookId: String, position: LocalReadingPosition) {
        enqueue(
            bookId = bookId,
            kind = ReaderMutationKind.UPSERT,
            payload = PortableProgress.payloadFor(bookId, position),
            resourceType = ReaderResourceType.READING_PROGRESS,
            publishKey = position.publishKey,
        )
    }

    // ---------------------------------------------------------- import records

    override fun accountId(): String? {
        hostCallbacks.checkNotInHostCallback()
        return active?.userId
    }

    override suspend fun importRecords(): List<PublicationImportRecord> = locked { active?.document?.imports.orEmpty() }

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

    override suspend fun hostRecord(key: String): JsonElement? = locked { active?.document?.host?.get(key) }

    override suspend fun updateHostRecord(key: String, transform: (JsonElement?) -> JsonElement?) {
        if (key in
            AccountLibraryCodec.RESERVED_DOCUMENT_KEYS
        ) {
            throw ReservedHostRecordKeyException(key, bookLevel = false)
        }
        locked {
            val account = active ?: return@locked
            val host = account.document.host
            val updated = host.withRecord(key, hostCallbacks.hostCallback { transform(host[key]) })
            if (updated == host) return@locked
            persistQuietly(account, account.document.copy(host = updated))
        }
    }

    override suspend fun updateBookHostRecord(bookId: String, key: String, transform: (JsonElement?) -> JsonElement?) {
        if (key in AccountLibraryCodec.RESERVED_BOOK_KEYS) throw ReservedHostRecordKeyException(key, bookLevel = true)
        locked {
            val account = active ?: return@locked
            val existing = account.document.book(bookId) ?: return@locked
            val updated = existing.host.withRecord(key, hostCallbacks.hostCallback { transform(existing.host[key]) })
            if (updated == existing.host) return@locked
            persistQuietly(account, account.document.withBook(existing.copy(host = updated)))
        }
    }

    private fun JsonObject.withRecord(key: String, value: JsonElement?): JsonObject =
        if (value == null) JsonObject(this - key) else JsonObject(this + (key to value))

    /** [block] under the engine's lock, from a public entry point: refused inside a host callback. */
    private suspend inline fun <T> locked(crossinline block: suspend () -> T): T {
        hostCallbacks.checkNotInHostCallback()
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
            account.persist(document)
        } catch (e: IOException) {
            publish(
                AccountSyncPhase.DEFERRED,
                AccountSyncTrigger.OWN_WRITE,
                error = AccountSyncError.StoreBlocked(e.message ?: "the account library could not be written"),
            )
        }
    }

    // ---------------------------------------------------------------- sessions

    /**
     * D4: stop reading the store, keep the file, delete nothing. A session the
     * backend rejected shows its reason here, once.
     */
    private suspend fun signOut(notConfigured: Boolean) {
        mutex.withLock {
            active = null
            owner = ChangeOwner.Nobody
            firstSession.complete(null)
            capabilityConfirmed = false
            // This device's memory of what it last published goes with the
            // session: the next sign-in publishes its first position afresh
            // rather than suppressing it because some earlier account was at the
            // same percent of some other book.
            published.clear()
            adoption.forgetProgressMismatch()
            val reason = pendingSessionGone ?: if (notConfigured) AccountSyncError.NotConfigured else null
            pendingSessionGone = null
            publish(AccountSyncPhase.SIGNED_OUT, trigger = null, error = reason)
        }
    }

    private suspend fun signIn(userId: String) {
        val opened = mutex.withLock {
            owner = ChangeOwner.User(userId)
            firstSession.complete(userId)
            capabilityConfirmed = false
            pendingSessionGone = null
            // What an earlier session published says nothing about this one.
            published.clear()
            withContext(ioDispatcher) { stores.discardOtherAccountQueues(userId) }
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
                    active = ActiveAccount(userId, store, document, ioDispatcher)
                    publish(
                        phase = if (!document.loadsShelf) {
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
                val drained = exchange.drainOutbox(account, gateway)
                if (account.document.bootstrapped) {
                    exchange.readDeltas(account, gateway, trigger)
                } else {
                    // Either the first read of the account, or the repair a refused
                    // change is owed (#151): the drain cleared the cursor in the same
                    // save that dropped the refused entry, so the owed repair outlives
                    // a failed read or a process death and runs on the next trigger.
                    // The merge-style read restores the backend's truth for the
                    // refused row and re-applies every change still queued. A repair
                    // over a populated shelf corrects it rather than loading it, so it
                    // does not announce a bootstrap.
                    exchange.bootstrap(account, gateway, trigger, announce = account.document.loadsShelf)
                }
                // A rejection the backend stated outranks a record this app could
                // not place; both are surfaced, a rejection first.
                publish(AccountSyncPhase.IDLE, trigger, error = drained.rejection ?: adoption.takeProgressMismatch())
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

    // ------------------------------------------------------------- own writes

    /**
     * Queues one mutation and runs a sync for it.
     *
     * The change is only *taken* here: who it belongs to is read now, at the
     * call, and its place in [changes] is the call order (#161, #162). [runQueue]
     * then puts it in the outbox under the lock.
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
        publishKey: Pair<String?, Int>? = null,
    ) {
        hostCallbacks.checkNotInHostCallback()
        val sent = changes.trySend(QueuedChange(owner, bookId, kind, payload, resourceType, publishKey))
        // An unlimited channel that is never closed takes every element.
        check(sent.isSuccess) { "the engine's change queue refused a change" }
    }

    /**
     * The one consumer of [changes]: every own write reaches the outbox here, one
     * at a time and in call order, whatever the host's dispatcher (#161).
     *
     * Everything already waiting is queued before one sync is started for all of
     * it, so a burst of taps is one drain rather than one per tap.
     */
    private suspend fun runQueue() {
        while (true) {
            var queued = queueSurviving(changes.receive())
            while (true) {
                val next = changes.tryReceive().getOrNull() ?: break
                queued = queueSurviving(next) || queued
            }
            if (queued) scope.launch { sync(AccountSyncTrigger.OWN_WRITE) }
        }
    }

    /**
     * [queue], except that no failure of one change ends [runQueue] (#177).
     *
     * [runQueue] is the only consumer, so an escaping exception would leave every
     * later change accepted by [enqueue] and never queued — silently, in a host
     * whose scope handles the exception. The change that failed is not kept; the
     * failure is reported on the current state and the next change goes on.
     */
    private suspend fun queueSurviving(change: QueuedChange): Boolean = try {
        queue(change)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.value = _state.value.copy(
            lastError = AccountSyncError.StoreBlocked(
                "a change could not be queued: ${e.message ?: e::class.simpleName}",
            ),
        )
        false
    }

    /**
     * Puts [change] in the outbox of the account it was asked for in (#162);
     * true when that is the signed-in account, which then needs a sync.
     *
     * - The account it was asked for is signed in: its outbox, as always.
     * - That account is not the signed-in one any more — the session changed
     *   between the call and now: that account's own stored document, whose file
     *   outlives its session (D4), and never the account signed in now.
     * - Nobody was signed in when it was asked for: there is no account to hold
     *   it, and nothing is queued.
     * - The session was still loading: the first session the engine settles on
     *   is the one the reader was acting in (#163).
     */
    private suspend fun queue(change: QueuedChange): Boolean {
        val userId = when (val owner = change.owner) {
            ChangeOwner.Unresolved -> firstSession.await()
            ChangeOwner.Nobody -> null
            is ChangeOwner.User -> owner.userId
        } ?: return false
        return mutex.withLock {
            val account = active
            if (account != null && account.userId == userId) {
                queueActive(account, change)
            } else {
                queueHeld(userId, change)
                false
            }
        }
    }

    /** [change] into the signed-in account's outbox. Runs under [mutex]. */
    private suspend fun queueActive(account: ActiveAccount, change: QueuedChange): Boolean {
        // AD-25's guard, under the lock that also clears it (#163).
        if (change.publishKey != null && published[change.bookId] == change.publishKey) return false
        val document = account.document.queuing(change, newIdempotencyKey, now)
        try {
            account.persist(document)
        } catch (e: IOException) {
            publish(
                AccountSyncPhase.DEFERRED,
                AccountSyncTrigger.OWN_WRITE,
                error = AccountSyncError.StoreBlocked(
                    e.message ?: "the account library could not be written",
                ),
            )
            return false
        }
        // Only now is the position this device's to remember as sent (#163).
        change.publishKey?.let { published[change.bookId] = it }
        publish(AccountSyncPhase.SYNCING, AccountSyncTrigger.OWN_WRITE)
        return true
    }

    /**
     * [change] into the stored document of [userId], an account that is not the
     * signed-in one (#162). Runs under [mutex], which is also the only writer of
     * that file. It is sent the next time that account signs in.
     *
     * A document that cannot be read or written, or that names another account,
     * is not written; the refusal is reported on the current state rather than
     * lost without a word. Nothing about the signed-in account changes.
     */
    private suspend fun queueHeld(userId: String, change: QueuedChange) {
        val store = stores.forUser(userId)
        val refusal = try {
            withContext(ioDispatcher) {
                when (val load = store.load()) {
                    is AccountLibraryLoad.Blocked -> load.message

                    is AccountLibraryLoad.Loaded -> {
                        val stored = load.document
                        if (stored.userId.isNotEmpty() && stored.userId != userId) {
                            "the stored account library belongs to another account"
                        } else {
                            store.save(stored.copy(userId = userId).queuing(change, newIdempotencyKey, now))
                            null
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.message ?: "the account library could not be written"
        }
        if (refusal != null) {
            _state.value = _state.value.copy(
                lastError = AccountSyncError.StoreBlocked(
                    "a change made before the session changed was not kept: $refusal",
                ),
            )
        }
    }

    // -------------------------------------------------------------- plumbing

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
}
