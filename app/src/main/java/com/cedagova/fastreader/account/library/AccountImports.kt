package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.account.PublicationImportGateway
import com.cedagova.fastreader.library.Book
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.imports.PublicationImportRefusal
import com.cedagova.reader.library.imports.PublicationImportStep
import com.cedagova.reader.library.imports.PublicationSource
import com.cedagova.reader.library.imports.PublicationTransferException
import com.cedagova.reader.library.imports.UploadConsent
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.PublicationImportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adding a device book to the Reader account: the consent gate, the transfer,
 * and what survives the app being killed in the middle of one (LEAF802 of #104).
 *
 * ## The consent gate is the whole point
 *
 * This class is the only thing in FastReader that can put a book's bytes on the
 * wire, and it is split into exactly two steps so that the split is visible in
 * the code and not only in a dialog:
 *
 * - [requestAdd] re-reads the account's `reader.publication-import.v1`
 *   capability and the deployment's policy, and works out what would happen.
 *   It sends `GET /v1/reader/capabilities` and `GET /reader/v1/imports/policy`
 *   — requests that carry no book, no name, no digest and no library — and
 *   nothing else. It cannot admit an
 *   import: it has no [UploadConsent] to pass and there is no way to make one
 *   except the owner's answer.
 * - [confirmAdd] is the answer. It is the only call here that reaches
 *   [PublicationImportGateway.start], and the consent travels with it as a
 *   value, not as a flag on a request object.
 *
 * Cancelling the question calls [dismiss], which sends nothing, ever. That is
 * REQ-505's "requires an explicit consent before anything is sent" expressed as
 * two methods rather than as an `if`.
 *
 * ## Nothing here is a constant
 *
 * The cap the consent question quotes, the formats it accepts and the chunk
 * size of the transfer all come from this attempt's policy and grant (REQ-506).
 * The refusal shown *before* the question is computed by the engine's own
 * [PublicationImportGateway.refuse] against that same policy, so the answer the
 * owner is shown and the answer the backend would give are one piece of code.
 *
 * ## App death (REQ-507, AD-26)
 *
 * What survives is the [PublicationImportRecord] in the account document, whose
 * `client_import_id` is derived from the file's content and the account. After a
 * relaunch [resumeStoredImports] replays that admission; the backend answers
 * with the same import and a fresh grant, and the transfer continues from the
 * offset the storage provider itself reports. One add, however many processes it
 * took.
 *
 * The record's *offset* is deliberately not written on every chunk. A resume
 * always re-reads the provider's own offset with `HEAD` before it sends a byte,
 * so a persisted offset would buy nothing and cost a file write per chunk; the
 * record is stored when something that cannot be recovered changes — the import
 * id, the upload's location, the grant window, the status.
 *
 * ## What it never does
 *
 * It writes no account row: when an import reaches `ready` the *backend* puts
 * the book in the library and in the change stream, so this asks
 * [onImportReady] for a sync and the row arrives with everything else, binding
 * to the device book by content identity alone (AD-23). It runs no scheduler
 * and registers no receiver: polling happens while the app is in the foreground
 * and stops when it is not.
 */
class AccountImports(
    /** Null on a build with no stage values: then nothing here can be reached. */
    private val gateway: PublicationImportGateway?,
    private val records: AccountImportRecords,
    private val sources: DeviceBookSources,
    /** The catalog's book for an id, or null when this device no longer has it. */
    private val bookForId: (String) -> Book?,
    /** Asks the sync engine for a pass, so the backend's new row reaches the shelf. */
    private val onImportReady: () -> Unit,
    accountState: Flow<AccountLibraryState>,
    private val scope: CoroutineScope,
    /** Backoff between status reads of a non-terminal import; the last value repeats. */
    private val pollDelaysMs: List<Long> = DEFAULT_POLL_DELAYS_MS,
    /** How many status reads one foreground spell makes before leaving it to the next. */
    private val pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(AccountImportsState.NONE)

    /** Every add in flight and the one account-wide verdict, as the shelf reads it. */
    val state: StateFlow<AccountImportsState> = _state.asStateFlow()

    /** One job per device book, so two adds never share a cancellation. */
    private val jobs = mutableMapOf<String, Job>()

    /** The last record written for each device book, to write only real changes. */
    private val persisted = mutableMapOf<String, PublicationImportRecord>()

    /**
     * Whether the app is in front of the reader.
     *
     * Polling a non-terminal import is the one thing here that would otherwise
     * keep running with the screen off, and AD-21's "no background work" is a
     * promise the release gate's permission set rests on. The loop waits on
     * this rather than being cancelled by it, so returning to the app picks the
     * same import up rather than restarting it.
     */
    private val foreground = MutableStateFlow(true)

    /** Whose session the current [AccountImportsState.offer] was read in; null while signed out. */
    private var offerUser: String? = null

    /** The capability read in flight, so a burst of account states asks once. */
    private var offerJob: Job? = null

    init {
        scope.launch {
            accountState.collect { account ->
                if (account.phase == AccountSyncPhase.SIGNED_OUT) {
                    offerUser = null
                    forgetEverything()
                } else if (account.userId != offerUser || _state.value.offer == ImportOffer.Unknown) {
                    // A new session reads the capability afresh; an unanswered
                    // read is retried on the next account state rather than
                    // leaving the action off for the whole session.
                    offerUser = account.userId
                    refreshOffer()
                }
            }
        }
    }

    /** The app came to the foreground: poll again, and pick up anything left in flight. */
    fun onForeground() {
        foreground.value = true
        if (offerUser != null) refreshOffer()
        resumeStoredImports()
    }

    /**
     * Re-reads `reader.publication-import.v1` in the background (#139).
     *
     * Active capacity frees up as imports finish, and a deployment can turn
     * admissions back on, so the answer is read at the start of a session, on
     * every return to the foreground and after an import settles — never kept as
     * a constant. A failed read leaves the offer [ImportOffer.Unknown], which is
     * not an offer.
     */
    private fun refreshOffer() {
        val gateway = gateway ?: return
        if (offerJob?.isActive == true) return
        offerJob = scope.launch {
            runCatching { readOffer(gateway) }
        }
    }

    /** One capability read, published to the state and returned. */
    private suspend fun readOffer(gateway: PublicationImportGateway): ImportOffer {
        val capability = gateway.importCapability()
        val offer = if (capability.isAvailable) ImportOffer.Available else ImportOffer.Unavailable(capability.reason)
        _state.update { it.copy(offer = offer) }
        return offer
    }

    /** The app went away: status reads stop until it is back. */
    fun onBackground() {
        foreground.value = false
    }

    /**
     * The owner tapped **Add to account library** on [deviceBookId].
     *
     * Ends in the consent question, in the action withdrawn with the
     * capability's reason, or in the refusal the policy already implies — shown from the policy's own numbers, with no admission made and
     * no byte sent either way.
     */
    fun requestAdd(deviceBookId: String) {
        val gateway = gateway ?: return
        launchFor(deviceBookId) {
            publish(deviceBookId, BookImportState.Checking)
            val source = sourceFor(deviceBookId) ?: return@launchFor
            // The capability first (#139, core.md §7.6 step 1): the row offered
            // the action on the last answer, and this tap re-asks before the
            // policy read. A "no" takes the action off every row with its
            // reason and sends nothing more.
            val offer = try {
                readOffer(gateway)
            } catch (e: ReaderAuthException) {
                publish(deviceBookId, refusalFor(e))
                return@launchFor
            }
            if (offer != ImportOffer.Available) {
                clear(deviceBookId)
                return@launchFor
            }
            val policy = try {
                gateway.importPolicy()
            } catch (e: ReaderAuthException) {
                publish(deviceBookId, refusalFor(e))
                return@launchFor
            }
            when (val refusal = gateway.refuse(policy, source)) {
                null -> publish(
                    deviceBookId,
                    BookImportState.Consent(
                        sizeBytes = source.sizeBytes,
                        // Non-null by construction: `refuse` returns
                        // FormatNotSupported for a type the policy does not
                        // list, so a null refusal means this entry exists.
                        maxSourceBytes = policy.capFor(source) ?: source.sizeBytes,
                    ),
                )

                is PublicationImportRefusal.ImportsDisabled -> disable(deviceBookId, refusal.requestId)
                else -> publish(deviceBookId, refusal.toState(policy.requestId))
            }
        }
    }

    /**
     * The owner answered yes. This is the only call in FastReader that can send
     * a book's bytes.
     *
     * It refuses to act on anything but a [BookImportState.Consent] on screen:
     * a confirm that does not follow a question is a bug, and the answer to a
     * bug is not to upload a book.
     */
    fun confirmAdd(deviceBookId: String) {
        val gateway = gateway ?: return
        if (_state.value.byDeviceBookId[deviceBookId] !is BookImportState.Consent) return
        launchFor(deviceBookId) {
            publish(deviceBookId, BookImportState.Sending(0f))
            val source = sourceFor(deviceBookId) ?: return@launchFor
            val accountId = records.accountId() ?: return@launchFor clear(deviceBookId)
            runStep(deviceBookId, source) {
                gateway.start(accountId, source, UploadConsent.GRANTED) { record ->
                    onRecord(deviceBookId, record)
                }
            }
        }
    }

    /**
     * The owner called the add off.
     *
     * The backend is told, and the record is forgotten whether or not that call
     * got through: an admission with no bytes behind it is not an account entry
     * — the import contract's own `broken_account_entry_created` is false — and
     * the derived `client_import_id` means a later add of the same file
     * addresses the same admission rather than a second one. Waiting for the
     * network before letting the owner out of a transfer would be the worse
     * failure.
     */
    fun cancelAdd(deviceBookId: String) {
        scope.launch {
            mutex.withLock { jobs.remove(deviceBookId) }?.cancel()
            val record = storedRecordFor(deviceBookId)
            if (record != null) {
                runCatching { gateway?.cancel(record) }
                records.dropImportRecord(record.clientImportId)
                mutex.withLock { persisted.remove(deviceBookId) }
            }
            publish(deviceBookId, BookImportState.Refused(ImportProblem.Category(PublicationFailureCategory.CANCELLED)))
        }
    }

    /** Puts away a finished verdict — the consent question declined, a refusal read. */
    fun dismiss(deviceBookId: String) {
        scope.launch { clear(deviceBookId) }
    }

    /**
     * Carries on every stored import this device left unfinished.
     *
     * Called on every foreground pass (AD-21's trigger, not a scheduler of this
     * class's own). A record whose book has left the catalog is dropped rather
     * than resumed: there are no bytes to send, and keeping it would retry for
     * ever.
     */
    fun resumeStoredImports() {
        val gateway = gateway ?: return
        scope.launch {
            records.importRecords().forEach { record ->
                if (record.isTerminal) {
                    records.dropImportRecord(record.clientImportId)
                    return@forEach
                }
                val deviceBookId = deviceBookIdOf(record)
                if (mutex.withLock { jobs[deviceBookId]?.isActive } == true) return@forEach
                launchFor(deviceBookId) {
                    val source = sourceFor(deviceBookId) ?: run {
                        records.dropImportRecord(record.clientImportId)
                        return@launchFor
                    }
                    publish(deviceBookId, BookImportState.Sending(record.transferredFraction))
                    runStep(deviceBookId, source) {
                        // The stored record *is* the consent: it exists only
                        // because the owner answered the question for this file
                        // in this account, and it outlived the process that
                        // asked. Resuming re-states that answer; it does not
                        // invent one.
                        gateway.resume(record, source, UploadConsent.GRANTED) { updated ->
                            onRecord(deviceBookId, updated)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- the lifecycle

    private suspend fun runStep(
        deviceBookId: String,
        source: PublicationSource,
        attempt: suspend () -> PublicationImportStep,
    ) {
        val step = try {
            attempt()
        } catch (e: ReaderAuthException) {
            publish(deviceBookId, refusalFor(e))
            return
        } catch (e: PublicationTransferException) {
            // The engine turns a transfer failure into a step; anything that
            // escapes it is the creation call, and it is resumable all the same.
            publish(deviceBookId, BookImportState.Refused(ImportProblem.NeedsConnection))
            return
        } catch (e: IllegalArgumentException) {
            // The module refuses a malformed admission locally rather than
            // learning it from a 422 — a mismatch between this file and what it
            // says about itself.
            publish(
                deviceBookId,
                BookImportState.Refused(
                    ImportProblem.Category(PublicationFailureCategory.MALFORMED),
                    retryable = false,
                ),
            )
            return
        }
        settle(deviceBookId, source, step)
    }

    private suspend fun settle(deviceBookId: String, source: PublicationSource, step: PublicationImportStep) {
        step.record?.let { store(deviceBookId, it) }
        when (step) {
            is PublicationImportStep.Refused -> publish(deviceBookId, step.refusal.toState(null))

            is PublicationImportStep.Failed -> {
                // A backend-terminal failure has nothing left to resume, so the
                // record goes; a local `upload` verdict leaves the import alive
                // on the backend at `pending_upload`, and keeping the record is
                // what lets the next attempt continue instead of starting over.
                if (step.record.isTerminal) forget(deviceBookId, step.record)
                publish(
                    deviceBookId,
                    BookImportState.Refused(
                        ImportProblem.Category(step.category),
                        retryable = step.record.failureRetryable ?: false,
                    ),
                )
            }

            is PublicationImportStep.Interrupted ->
                publish(deviceBookId, BookImportState.Refused(ImportProblem.NeedsConnection))

            is PublicationImportStep.Transferred -> awaitReady(deviceBookId, source, step.record)
        }
    }

    /**
     * Polls the backend while it prepares the book.
     *
     * Only while the app is in front of the reader, with the backoff growing to
     * [pollDelaysMs]'s last value, and for a bounded number of reads: what has
     * not settled by then settles on the next foreground pass, which costs
     * nothing while the phone is in a pocket.
     */
    private suspend fun awaitReady(
        deviceBookId: String,
        source: PublicationSource,
        transferred: PublicationImportRecord,
    ) {
        val gateway = gateway ?: return
        var record = transferred
        var attempt = 0
        while (!record.isTerminal && attempt < pollAttempts) {
            publish(deviceBookId, BookImportState.Finishing)
            foreground.first { it }
            delay(pollDelaysMs[minOf(attempt, pollDelaysMs.lastIndex)])
            attempt++
            record = try {
                gateway.refresh(record)
            } catch (e: ReaderAuthException) {
                publish(deviceBookId, refusalFor(e))
                return
            }
            store(deviceBookId, record)
        }
        when (record.status) {
            PublicationImportStatus.READY -> {
                forget(deviceBookId, record)
                clear(deviceBookId)
                // A settled import frees active capacity the offer may have been waiting on.
                refreshOffer()
                // The row is the backend's to create; this only asks for the
                // pass that will carry it here (AD-22, AD-23).
                onImportReady()
            }

            PublicationImportStatus.FAILED -> {
                forget(deviceBookId, record)
                publish(
                    deviceBookId,
                    BookImportState.Refused(
                        ImportProblem.Category(record.failureCategory ?: PublicationFailureCategory.UNKNOWN),
                        retryable = record.failureRetryable ?: false,
                    ),
                )
            }

            PublicationImportStatus.CANCELLED, PublicationImportStatus.DELETED -> {
                forget(deviceBookId, record)
                publish(
                    deviceBookId,
                    BookImportState.Refused(ImportProblem.Category(PublicationFailureCategory.CANCELLED)),
                )
            }

            // Still working. The record is stored, so the next foreground pass
            // picks it up exactly where this one left it.
            else -> publish(deviceBookId, BookImportState.Finishing)
        }
    }

    // --------------------------------------------------------------- plumbing

    /** Publishes a record's progress, and stores it when something durable changed. */
    private fun onRecord(deviceBookId: String, record: PublicationImportRecord) {
        scope.launch {
            store(deviceBookId, record)
            if (!record.isTerminal) publish(deviceBookId, BookImportState.Sending(record.transferredFraction))
        }
    }

    /**
     * Writes [record] when it differs from the last one written in something a
     * relaunch could not work out for itself.
     *
     * The offset is excluded from that comparison on purpose: a resume re-reads
     * the provider's own offset before it sends anything, so storing ours would
     * be a file write per chunk for a number nothing trusts.
     */
    private suspend fun store(deviceBookId: String, record: PublicationImportRecord) {
        val durable = record.copy(uploadedOffset = 0)
        val unchanged = mutex.withLock {
            val previous = persisted[deviceBookId]?.copy(uploadedOffset = 0)
            if (previous == durable) true else { persisted[deviceBookId] = record; false }
        }
        if (!unchanged) records.putImportRecord(record)
    }

    private suspend fun forget(deviceBookId: String, record: PublicationImportRecord) {
        records.dropImportRecord(record.clientImportId)
        mutex.withLock { persisted.remove(deviceBookId) }
    }

    private suspend fun storedRecordFor(deviceBookId: String): PublicationImportRecord? {
        val identity = deviceBookId.removePrefix(SHA256_PREFIX).lowercase()
        return records.importRecords().firstOrNull { it.contentSha256 == identity }
    }

    private fun deviceBookIdOf(record: PublicationImportRecord): String = SHA256_PREFIX + record.contentSha256

    /** The bytes behind [deviceBookId], or null having already published why not. */
    private suspend fun sourceFor(deviceBookId: String): PublicationSource? {
        val book = bookForId(deviceBookId) ?: run {
            publish(
                deviceBookId,
                BookImportState.Refused(
                    ImportProblem.SourceUnavailable(PublicationSourceProblem.UNREACHABLE),
                    retryable = false,
                ),
            )
            return null
        }
        return when (val resolved = sources.of(book)) {
            is PublicationSourceResult.Ready -> resolved.source
            is PublicationSourceResult.Unavailable -> {
                publish(
                    deviceBookId,
                    BookImportState.Refused(ImportProblem.SourceUnavailable(resolved.problem), retryable = false),
                )
                null
            }
        }
    }

    /**
     * Runs [block] as *the* job for [deviceBookId], replacing whatever was
     * running for that book.
     *
     * Created lazily and started only after it is in [jobs], so a cancel that
     * lands in between — the owner tapping Cancel, a sign-out — cannot miss it.
     * That is the same discipline `AccountShelf`'s undo timer learned in #129.
     */
    private fun launchFor(deviceBookId: String, block: suspend () -> Unit) {
        scope.launch {
            val job = scope.launch(start = CoroutineStart.LAZY) { block() }
            mutex.withLock { jobs.put(deviceBookId, job) }?.cancel()
            job.start()
            job.join()
            mutex.withLock { if (jobs[deviceBookId] === job) jobs.remove(deviceBookId) }
        }
    }

    private fun publish(deviceBookId: String, state: BookImportState) {
        _state.update { it.copy(byDeviceBookId = it.byDeviceBookId + (deviceBookId to state)) }
    }

    private fun clear(deviceBookId: String) {
        _state.update { it.copy(byDeviceBookId = it.byDeviceBookId - deviceBookId) }
    }

    /**
     * The deployment admits nothing: the action goes and the reason takes its
     * place, for every device book at once — `enabled: false` is about the
     * deployment, not about a file.
     */
    private fun disable(deviceBookId: String, requestId: String?) {
        _state.update {
            it.copy(disabled = ImportsOff(requestId), byDeviceBookId = it.byDeviceBookId - deviceBookId)
        }
    }

    /**
     * Signing out ends every add with it (D4).
     *
     * A transfer is a credential for one account's storage; there is nothing
     * honest to do with it once that session is gone. The stored records stay
     * in that account's document — signing back in resumes them — and the
     * `enabled: false` verdict goes too, because it belonged to the deployment
     * as that session saw it.
     */
    private suspend fun forgetEverything() {
        mutex.withLock {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            persisted.clear()
        }
        _state.value = AccountImportsState.NONE
    }

    private fun PublicationImportPolicyResponse.capFor(source: PublicationSource): Long? =
        formatForMimeType(source.mimeType)?.maxSourceBytes

    private fun PublicationImportRefusal.toState(requestId: String?): BookImportState.Refused = when (this) {
        is PublicationImportRefusal.TooLarge -> BookImportState.Refused(
            problem = ImportProblem.Category(category),
            requestId = requestId,
            sizeBytes = sizeBytes,
            maxSourceBytes = maxSourceBytes,
            retryable = false,
        )

        is PublicationImportRefusal.ImportsDisabled -> BookImportState.Refused(
            problem = ImportProblem.Api(null),
            requestId = this.requestId,
            retryable = false,
        )

        else -> BookImportState.Refused(
            problem = ImportProblem.Category(category ?: PublicationFailureCategory.UNKNOWN),
            requestId = requestId,
            retryable = false,
        )
    }

    /** One `ReaderAuthException` branch as the state the row shows. Nothing is invented. */
    private fun refusalFor(error: ReaderAuthException): BookImportState.Refused = when (error) {
        is ReaderAuthException.NetworkUnavailable ->
            BookImportState.Refused(ImportProblem.NeedsConnection)

        is ReaderAuthException.SignedOut ->
            BookImportState.Refused(ImportProblem.Api(null), code = error.code, requestId = error.requestId)

        is ReaderAuthException.Forbidden ->
            BookImportState.Refused(ImportProblem.Api(null), code = error.code, requestId = error.requestId)

        is ReaderAuthException.TryLater ->
            BookImportState.Refused(ImportProblem.Api(error.status), code = error.code, requestId = error.requestId)

        is ReaderAuthException.ApiError ->
            BookImportState.Refused(ImportProblem.Api(error.status), code = error.code, requestId = error.requestId)

        is ReaderAuthException.ProviderRejected ->
            BookImportState.Refused(ImportProblem.Api(error.status), code = error.code)

        is ReaderAuthException.NotConfigured, is ReaderAuthException.ConfigurationMismatch ->
            BookImportState.Refused(ImportProblem.Api(null), retryable = false)
    }

    companion object {

        /** The catalog's identity prefix (v1 AD-2); the record stores the bare hex. */
        private const val SHA256_PREFIX: String = "sha256:"

        /**
         * Growing backoff for status reads, ending at half a minute. The
         * backend's own verification of a fifty-megabyte EPUB is seconds, not
         * minutes, so the early reads are close together and the tail is there
         * for a queue that is busy.
         */
        val DEFAULT_POLL_DELAYS_MS: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

        /** Roughly a minute of reads per foreground spell, then it waits for the next. */
        const val DEFAULT_POLL_ATTEMPTS: Int = 8
    }
}
