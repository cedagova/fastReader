package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.account.ReaderAccountState
import com.cedagova.fastreader.account.ReaderLibraryGateway
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderMutationKind
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The account-library actions the shelf performs (LEAF703). */
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
 * The account document's copy references, as the download flow uses them
 * (LEAF811, REQ-510, D2).
 *
 * Beside [AccountImportRecords] and for the same reason: the account document
 * has exactly one writer, and a second one racing it would be the first way to
 * lose a queued mutation. These say which account books this device holds
 * bytes for; the bytes are [AccountCopyStore]'s and the readable source is the
 * device catalog's.
 */
interface AccountCopyReferences {

    /** The signed-in account's user id, or null when nobody is signed in. */
    fun accountId(): String?

    /** The content identities the signed-in account has copies of on this device. */
    suspend fun copyReferences(): List<AccountCopy>

    /** Records [copy], replacing any earlier reference to the same content. */
    suspend fun putCopyReference(copy: AccountCopy)

    /** Forgets the reference to [contentSha256]. The bytes are not dropped here. */
    suspend fun dropCopyReference(contentSha256: String)

    /**
     * Keeps only the references [present] names.
     *
     * The start-up reconciliation: the store is the authority on what is
     * actually on disk, and a reference to a copy a dead process never finished
     * placing must not outlive it.
     */
    suspend fun retainCopyReferences(present: Set<String>)
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
 *    expired, invalid or ahead.
 *
 * ## What it never does
 *
 * It resolves no conflict and prompts for none (AD-22): every result and every
 * change is adopted from the backend's canonical payload, a `conflict` result
 * included. It runs no scheduler, holds no wake lock and registers no receiver
 * (AD-21): the only things that make it run are the triggers of
 * [AccountSyncTrigger]. It writes nothing but the account document — the
 * device catalog is not this leaf's to touch. And it sends nothing but
 * `library_item` mutations: no `profile`, `settings`, `note` or `bookmark`
 * envelope exists in this file.
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
    accountState: Flow<ReaderAccountState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Minted once per queued mutation and then persisted; never re-minted for a retry. */
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> String = { kotlin.time.Clock.System.now().toString() },
) : AccountLibraryActions, AccountImportRecords, AccountCopyReferences {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(AccountLibraryState.SIGNED_OUT)

    /** The account library as the shelf builds from it. */
    val state: StateFlow<AccountLibraryState> = _state.asStateFlow()

    private var active: ActiveAccount? = null

    /** Cleared on every session change: the capability is read once per signed-in session. */
    private var capabilityConfirmed: Boolean = false

    /** A session the backend rejected, kept until the signed-out state has shown it once. */
    private var pendingSessionGone: AccountSyncError.SessionGone? = null

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
        scope.launch { sync(trigger) }
    }

    override fun refresh() = requestSync(AccountSyncTrigger.MANUAL_REFRESH)

    override fun removeFromAccount(bookId: String) =
        enqueue(bookId, ReaderMutationKind.DELETE, JsonObject(emptyMap())) { it.copy(removed = true) }

    override fun undoRemove(bookId: String) =
        enqueue(bookId, ReaderMutationKind.RESTORE, JsonObject(emptyMap())) { it.copy(removed = false) }

    override fun recordOpened(bookId: String) {
        val at = now()
        val payload = buildJsonObject {
            put("status", JsonPrimitive(ReaderLibraryStatus.READING.wireName()))
            put("last_opened_at", JsonPrimitive(at))
        }
        enqueue(bookId, ReaderMutationKind.UPSERT, payload) {
            it.copy(status = ReaderLibraryStatus.READING, lastOpenedAt = at)
        }
    }

    override fun recordFinished(bookId: String) = recordStatus(bookId, ReaderLibraryStatus.FINISHED)

    override fun recordStatus(bookId: String, status: ReaderLibraryStatus) {
        val payload = buildJsonObject { put("status", JsonPrimitive(status.wireName())) }
        enqueue(bookId, ReaderMutationKind.UPSERT, payload) { it.copy(status = status) }
    }

    // ---------------------------------------------------------- import records

    override fun accountId(): String? = active?.userId

    override suspend fun importRecords(): List<PublicationImportRecord> =
        mutex.withLock { active?.document?.imports.orEmpty() }

    /**
     * Stores [record] under the one writer of the account document.
     *
     * Silently does nothing when nobody is signed in, which is the right answer
     * rather than a failure: a transfer whose session went away has nowhere to
     * be remembered, and the shelf has already lost the account rows it would
     * have bound to.
     */
    override suspend fun putImportRecord(record: PublicationImportRecord) {
        mutex.withLock {
            val account = active ?: return@withLock
            if (record.accountId != account.userId) return@withLock
            persistQuietly(account, account.document.withImport(record))
        }
    }

    override suspend fun dropImportRecord(clientImportId: String) {
        mutex.withLock {
            val account = active ?: return@withLock
            if (account.document.import(clientImportId) == null) return@withLock
            persistQuietly(account, account.document.withoutImport(clientImportId))
        }
    }

    // ---------------------------------------------------------- copy references

    override suspend fun copyReferences(): List<AccountCopy> =
        mutex.withLock { active?.document?.copies.orEmpty() }

    /**
     * Records a copy under the one writer of the account document.
     *
     * Silently does nothing when nobody is signed in, and that is the right
     * answer rather than a failure: the bytes are already placed and the
     * catalog's `ACCOUNT_COPY` source already makes them readable, so a
     * reference that has no account to belong to costs nothing (D4 — a copy
     * outlives the session that fetched it). Signing back in re-binds it.
     */
    override suspend fun putCopyReference(copy: AccountCopy) {
        mutex.withLock {
            val account = active ?: return@withLock
            persistQuietly(account, account.document.withCopy(copy))
        }
    }

    override suspend fun dropCopyReference(contentSha256: String) {
        mutex.withLock {
            val account = active ?: return@withLock
            if (!account.document.hasCopy(contentSha256)) return@withLock
            persistQuietly(account, account.document.withoutCopy(contentSha256))
        }
    }

    override suspend fun retainCopyReferences(present: Set<String>) {
        mutex.withLock {
            val account = active ?: return@withLock
            val retained = account.document.retainingCopies(present)
            if (retained.copies.size == account.document.copies.size) return@withLock
            persistQuietly(account, retained)
        }
    }

    /**
     * Writes the document and publishes nothing.
     *
     * An import record changes no row the shelf draws — the account row arrives
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
    private fun targetOf(state: ReaderAccountState): SessionTarget? = when (state) {
        ReaderAccountState.Loading -> null
        is ReaderAccountState.NotConfigured -> SessionTarget.None(notConfigured = true)
        is ReaderAccountState.SignedOut -> SessionTarget.None(notConfigured = false)
        is ReaderAccountState.SignedIn -> SessionTarget.User(state.userId)
    }

    /**
     * D4: stop reading the store, keep the file, delete nothing. A session the
     * backend rejected shows its reason here, once.
     */
    private suspend fun signOut(notConfigured: Boolean) {
        mutex.withLock {
            active = null
            capabilityConfirmed = false
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
                val rejection = drainOutbox(account, gateway)
                if (account.document.bootstrapped) {
                    readDeltas(account, gateway, trigger)
                } else {
                    bootstrap(account, gateway, trigger)
                }
                publish(AccountSyncPhase.IDLE, trigger, error = rejection)
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
     * surface its code; a rejection is not a failure of the run.
     */
    private suspend fun drainOutbox(
        account: ActiveAccount,
        gateway: ReaderLibraryGateway,
    ): AccountSyncError.Rejected? {
        val pending = account.document.outbox
        if (pending.isEmpty()) return null

        val kept = mutableListOf<AccountOutboxEntry>()
        var rejection: AccountSyncError.Rejected? = null
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
                    result.rejection?.let {
                        rejection = it.toSyncError(result.resourceId)
                        if (it.retryable) kept += entry
                    }
                }
            }
            index += batch.size
            persist(account, document.copy(outbox = kept + pending.drop(index)))
        }
        return rejection
    }

    /**
     * Reads the account's lists and takes the stream's head as the cursor.
     *
     * The head is read *first*, on purpose: a change admitted between the head
     * read and the lists is then still after the stored cursor and arrives with
     * the next delta read. Reading it afterwards would place the cursor past a
     * change the lists never showed, and that change would be lost. `latest_cursor`
     * is present in every delta answer, an expired one included, so the head read
     * asks for one change and uses nothing but that field.
     */
    private suspend fun bootstrap(
        account: ActiveAccount,
        gateway: ReaderLibraryGateway,
        trigger: AccountSyncTrigger,
    ) {
        publish(AccountSyncPhase.BOOTSTRAPPING, trigger)
        val head = gateway.deltas(ReaderLibraryClient.FIRST_CURSOR, HEAD_LIMIT)
        val library = gateway.library()
        val positions = gateway.progress().progress.associateBy { it.bookId }
        val books = library.items.map { item ->
            val row = AccountBook.of(item)
            positions[row.bookId]?.let {
                row.copy(progressPercent = it.progressPercent, progressUpdatedAt = it.updatedAt)
            } ?: row
        }
        persist(account, account.document.copy(books = books, cursor = head.latestCursor))
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
     * [locally] is the row's queued intent — the removal leaving the shelf, the
     * status the reader just set — applied at once so the shelf reacts while
     * offline. It decides nothing: the moment the backend answers, its
     * canonical payload replaces the row wholesale (AD-22).
     */
    private fun enqueue(
        bookId: String,
        kind: ReaderMutationKind,
        payload: JsonObject,
        locally: (AccountBook) -> AccountBook,
    ) {
        scope.launch {
            val queued = mutex.withLock {
                val account = active ?: return@withLock false
                val existing = account.document.book(bookId)
                val entry = AccountOutboxEntry(
                    idempotencyKey = newIdempotencyKey(),
                    resourceType = ReaderResourceType.LIBRARY_ITEM,
                    resourceId = bookId,
                    mutationKind = kind,
                    baseRevision = existing?.revision ?: 0,
                    payload = payload,
                    clientCreatedAt = now(),
                )
                var document = account.document.copy(outbox = account.document.outbox + entry)
                if (existing != null) document = document.withBook(locally(existing))
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

    // ------------------------------------------------------------- adoption

    /**
     * Adopts one mutation result. A rejection carries an empty canonical
     * payload by contract and changes no row; everything else — `applied`,
     * `replayed`, `superseded` and `conflict` alike — is adopted as it stands.
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
        )
    }

    private fun applyCanonical(
        document: AccountLibraryDocument,
        resourceType: ReaderResourceType,
        resourceId: String,
        kind: ReaderMutationKind,
        payload: JsonObject,
        revision: Long?,
    ): AccountLibraryDocument = when (resourceType) {
        ReaderResourceType.LIBRARY_ITEM, ReaderResourceType.BOOK -> {
            val existing = document.book(resourceId)
            when (kind) {
                ReaderMutationKind.DELETE ->
                    existing
                        ?.let { document.withBook(it.copy(removed = true, revision = revision ?: it.revision)) }
                        ?: document

                else -> {
                    val row = AccountCanonicalPayload.libraryItem(existing, resourceId, payload, revision)
                    document.withBook(row.copy(removed = false))
                }
            }
        }

        ReaderResourceType.READING_PROGRESS ->
            AccountCanonicalPayload.readingProgress(document.book(resourceId), payload)
                ?.let(document::withBook)
                ?: document

        // profile, settings, note and bookmark are never sent and never shown.
        ReaderResourceType.PROFILE,
        ReaderResourceType.SETTINGS,
        ReaderResourceType.NOTE,
        ReaderResourceType.BOOKMARK,
        ReaderResourceType.UNKNOWN,
        -> document
    }

    // -------------------------------------------------------------- plumbing

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
