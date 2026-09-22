package com.cedagova.reader.library.imports

import com.cedagova.reader.auth.ReaderClock
import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.model.CancelPublicationImportRequest
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.PublicationImportStatus
import com.cedagova.reader.library.model.PublicationTransferGrant
import kotlin.time.Instant

/**
 * One add, from the policy to the last byte: the lifecycle AD-26 describes,
 * with the resume rules that make an interrupted add cost nothing.
 *
 * The engine owns the *order* — read the policy, refuse locally if it already
 * says no, admit with consent, transfer under the grant, complete — and the
 * decisions that order implies. It owns no state: every call takes and returns
 * a [PublicationImportRecord], and LEAF802 (#117) persists it. That is what
 * makes "killing the app mid-transfer resumes or restarts without a duplicate"
 * (REQ-507) a property of the *record* rather than of a process that happened
 * to stay alive.
 *
 * Three rules are worth stating outright, because they are the ones a later
 * change could quietly break:
 *
 * 1. **Nothing is a constant.** The cap, the accepted MIME types and the chunk
 *    size are read from this attempt's policy and grant. A deployment that
 *    lowers its cap lowers this client's cap with it, and nobody ships a build.
 * 2. **Consent is a parameter, not a flag.** Every entry point that can put
 *    bytes on the wire takes an [UploadConsent] with no default (REQ-505).
 * 3. **The bearer never reaches Storage.** The engine calls
 *    [ReaderLibraryOperations] for the lifecycle and
 *    [PublicationTransferClient] for the bytes, and the two share nothing.
 *
 * Polling a non-terminal import is deliberately *not* here: the frequency,
 * backoff and foreground-only rule are the host's policy, and
 * [refresh] is the one call it needs.
 */
class PublicationImportEngine(
    private val operations: ReaderLibraryOperations,
    private val transfer: PublicationTransferClient,
    private val clock: ReaderClock = ReaderClock.System,
) {

    /**
     * Start an add for [source] in [accountId].
     *
     * Reads the policy first, so a refusal the policy already implies costs one
     * `GET` and never an admission: nothing about this file is announced to the
     * account until the policy says it could be admitted.
     */
    suspend fun start(
        accountId: String,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit = {},
    ): PublicationImportStep {
        val policy = operations.importPolicy()
        val refusal = refuse(policy, source)
        if (refusal != null) return PublicationImportStep.Refused(refusal)
        val format = policy.formatForMimeType(source.mimeType)?.format ?: PublicationFormat.UNKNOWN
        val record = PublicationImportRecord.forSource(accountId, source, format)
        return run(record, source, consent, onProgress)
    }

    /**
     * Carry on a stored [record] — after app death, a lost network or a spent
     * grant.
     *
     * It always replays the admission. That is not a wasted call: the admission
     * is idempotent on `client_import_id`, so it returns the same import with
     * `created = false` *and* a fresh signed grant, which is the only way to get
     * a credential for a location whose signature this device deliberately did
     * not keep. What happens next is decided by the record: a location still
     * inside its grant window is `HEAD`ed and resumed from the provider's own
     * offset, and anything else starts a fresh transfer.
     */
    suspend fun resume(
        record: PublicationImportRecord,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit = {},
    ): PublicationImportStep {
        if (record.isTerminal) return step(record)
        return run(record, source, consent, onProgress)
    }

    /** One status read. The host decides when to make it; this makes it. */
    suspend fun refresh(record: PublicationImportRecord): PublicationImportRecord {
        val importId = record.importId ?: return record
        return record.withImport(operations.importRecord(importId).importRecord)
    }

    /**
     * Cancel an import the owner changed their mind about.
     *
     * A record that was never admitted has nothing to cancel and is simply
     * reported cancelled; the backend never heard of it.
     */
    suspend fun cancel(
        record: PublicationImportRecord,
        reason: String = CancelPublicationImportRequest.DEFAULT_REASON,
    ): PublicationImportRecord {
        val importId = record.importId
            ?: return record.copy(
                status = PublicationImportStatus.CANCELLED,
                failureCategory = PublicationFailureCategory.CANCELLED,
            ).withoutTransfer()
        return record.withImport(operations.cancelImport(importId, reason).importRecord)
    }

    /**
     * What the policy alone already refuses, or null when this file could be
     * admitted.
     *
     * Pure and public: LEAF802 shows the same answer on the shelf row before the
     * owner is ever asked for consent, from the same code that enforces it.
     */
    fun refuse(policy: PublicationImportPolicyResponse, source: PublicationSource): PublicationImportRefusal? {
        if (!policy.enabled) return PublicationImportRefusal.ImportsDisabled(policy.requestId)
        if (source.sizeBytes <= 0) return PublicationImportRefusal.SourceEmpty
        val format = policy.formatForMimeType(source.mimeType)
            ?: return PublicationImportRefusal.FormatNotSupported(
                mimeType = source.mimeType,
                supportedMimeTypes = policy.formats.flatMap { it.mimeTypes },
            )
        if (format.format == PublicationFormat.UNKNOWN) {
            // The policy lists a format this build of the client does not know.
            // Admitting it would send a `source_format` the contract test never
            // pinned; saying "not supported" is the truth from here.
            return PublicationImportRefusal.FormatNotSupported(
                mimeType = source.mimeType,
                supportedMimeTypes = policy.formats.flatMap { it.mimeTypes },
            )
        }
        if (source.sizeBytes > format.maxSourceBytes) {
            return PublicationImportRefusal.TooLarge(
                format = format.format,
                sizeBytes = source.sizeBytes,
                maxSourceBytes = format.maxSourceBytes,
            )
        }
        return null
    }

    // ---- the lifecycle ------------------------------------------------------------------------

    private suspend fun run(
        initial: PublicationImportRecord,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit,
    ): PublicationImportStep {
        // The expiry the *stored* location was issued under, read before the
        // admission answer below overwrites it with the fresh grant's. It is the
        // only thing that says whether that location's signature still works, and
        // a fresh grant's expiry is by definition in the future: judging the old
        // location by the new expiry would HEAD every spent grant forever.
        val locationGrantExpiresAt = initial.grantExpiresAt
        val admission = operations.admitImport(initial.admissionRequest(consent))
        var record = initial.withAdmission(admission)
        onProgress(record)
        if (record.isTerminal) return step(record)

        val grant = admission.transferGrant
            // No grant and not terminal means the bytes are already at Storage
            // and the backend is working on them: there is nothing to send.
            ?: return PublicationImportStep.Transferred(record)

        val prepared = try {
            prepare(record, grant, locationGrantExpiresAt)
        } catch (e: PublicationTransferException) {
            // Same rule as the transfer catch below: a stored location is thrown
            // away only when the failure *proves* it is gone. `prepare` already
            // swallows the two answers that prove it — GrantRejected and
            // Protocol — and falls through to a fresh creation, so what reaches
            // here from its HEAD is a dropped connection, a socket timeout or a
            // provider 5xx. None of those says anything about the object: the
            // provider is still holding those bytes, and discarding the location
            // would re-send the whole book on every blip.
            //
            // The expiry has to be restored with it. `record` has been through
            // `withAdmission`, so its grantExpiresAt is the FRESH grant's;
            // pairing that open window with the OLD location would reintroduce
            // exactly the "judged by a window that is open by construction" bug
            // — the location must keep the expiry it was issued under.
            return interrupted(keepingLocation(record, locationGrantExpiresAt, e), e)
        }
        record = prepared
        onProgress(record)

        record = try {
            val end = transfer.transfer(grant, record.transferLocation!!, source, record.uploadedOffset) { offset ->
                onProgress(record.confirmedOffset(offset).also { record = it })
            }
            record.confirmedOffset(end)
        } catch (e: PublicationTransferException) {
            val reached = record.confirmedOffset(e.offset)
            return interrupted(if (e.provesLocationGone()) reached.withoutTransfer() else reached, e)
        }

        val completed = record.withImport(operations.completeImport(record.importId!!).importRecord)
        onProgress(completed)
        return step(completed)
    }

    /**
     * The location to transfer to, and the offset to start from.
     *
     * A stored location inside its grant window is `HEAD`ed: the provider's
     * answer is the durable offset, and the transfer begins at exactly the byte
     * it does not have. A spent window skips that round trip, and a rejected
     * `HEAD` falls through to a fresh creation — which is the expired-grant path
     * the plan asks for, arrived at from either direction.
     *
     * [locationGrantExpiresAt] is the expiry [PublicationImportRecord.transferLocation]
     * was created under, *not* [grant]'s: [grant] is the fresh one this attempt
     * just admitted, and its window is always open.
     *
     * This `HEAD` is also the only thing that knows how far an interrupted
     * transfer actually got. A connection that dies mid-`PATCH` may leave the
     * provider holding part of that chunk, and the client is not told; the
     * stored record therefore under-counts until this call replaces it with the
     * provider's own number.
     */
    private suspend fun prepare(
        record: PublicationImportRecord,
        grant: PublicationTransferGrant,
        locationGrantExpiresAt: String?,
    ): PublicationImportRecord {
        val location = record.transferLocation
        if (location != null && !expired(locationGrantExpiresAt)) {
            try {
                val offset = transfer.offset(grant, location)
                return record.copy(grantExpiresAt = grant.expiresAt, uploadedOffset = 0).confirmedOffset(offset)
            } catch (e: PublicationTransferException.GrantRejected) {
                // The resumable upload is gone. Nothing to recover; make a new one.
            } catch (e: PublicationTransferException.Protocol) {
                // An answer TUS does not allow is not an offset we may trust.
            } catch (e: PublicationTransferException.ForeignLocation) {
                // A stored location off the grant's origin is never HEADed
                // with the grant's headers (#142); make a new one instead.
            }
        }
        return record.transferringAt(transfer.create(grant), grant)
    }

    /** True when [expiresAt] is a moment this device's clock has already passed. */
    private fun expired(expiresAt: String?): Boolean {
        val instant = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return instant <= clock.now()
    }

    /**
     * [record] with its stored transfer location kept or dropped, by the one
     * rule this engine applies everywhere: drop it only when [cause] proves the
     * resumable upload is gone.
     *
     * Only [PublicationTransferException.GrantRejected] proves that (401/403/404
     * /410 — the signature is spent or the object was discarded), plus
     * [PublicationTransferException.ForeignLocation], a location this client
     * will never send the grant's headers to (#142). Everything else leaves the provider holding whatever it already had, so the location
     * is worth more than the round trip it costs to find out.
     *
     * The asymmetry matters: keeping a dead location costs one futile `HEAD` on
     * the next attempt, which then falls through to a fresh creation. Dropping a
     * live one costs the owner the entire upload again — up to the hosted cap,
     * over mobile data, on exactly the flaky link that caused the failure.
     *
     * [locationGrantExpiresAt] is the window the kept location was issued under.
     * It must travel with it: [record] has already adopted the fresh grant's
     * expiry, and a stored location wearing a fresh window is never judged
     * spent.
     */
    private fun keepingLocation(
        record: PublicationImportRecord,
        locationGrantExpiresAt: String?,
        cause: PublicationTransferException,
    ): PublicationImportRecord = when {
        cause.provesLocationGone() -> record.withoutTransfer()
        // A first attempt has no location, so this preserves nothing and the
        // next attempt creates one, exactly as it would have.
        else -> record.copy(grantExpiresAt = locationGrantExpiresAt)
    }

    /**
     * True when [this] says the stored location is not worth keeping: the
     * provider discarded it, or it is on another origin than the grant and
     * so will never be sent the grant's headers (#142).
     */
    private fun PublicationTransferException.provesLocationGone(): Boolean =
        this is PublicationTransferException.GrantRejected || this is PublicationTransferException.ForeignLocation

    private fun interrupted(
        record: PublicationImportRecord,
        cause: PublicationTransferException,
    ): PublicationImportStep = when (cause) {
        // A provider refusal is not one of the backend's categories, and this
        // module does not invent one: `upload` is the contract's own name for a
        // transfer that did not succeed, which is exactly what happened.
        // A Location off the grant's origin (#142) is the provider refusing
        // this client's terms rather than a dropped connection: retrying would
        // be handed the same Location, so it settles like a refusal.
        is PublicationTransferException.Refused,
        is PublicationTransferException.ForeignLocation,
        ->
            PublicationImportStep.Failed(
                record.copy(failureCategory = PublicationFailureCategory.UPLOAD, failureRetryable = false),
                PublicationFailureCategory.UPLOAD,
            )
        else -> PublicationImportStep.Interrupted(record, cause)
    }

    /** The step a record's own status implies, once the backend has spoken. */
    private fun step(record: PublicationImportRecord): PublicationImportStep = when {
        record.failureCategory != null && record.isTerminal ->
            PublicationImportStep.Failed(record, record.failureCategory)
        else -> PublicationImportStep.Transferred(record)
    }
}

/**
 * Where one attempt got to. Four answers, and a host does something different
 * with each of them.
 */
sealed interface PublicationImportStep {

    /** The record as it now stands; always safe to persist, always safe to resume from. */
    val record: PublicationImportRecord?

    /**
     * The policy already said no, and nothing was admitted or sent. The device
     * book is untouched because it was never involved (REQ-507).
     */
    data class Refused(val refusal: PublicationImportRefusal) : PublicationImportStep {
        override val record: PublicationImportRecord? get() = null
    }

    /**
     * The bytes are at Storage and the backend has them. The import is not
     * finished — it is `verifying_upload`, `queued` or `processing` — and the
     * host polls [PublicationImportEngine.refresh] until it is terminal.
     */
    data class Transferred(override val record: PublicationImportRecord) : PublicationImportStep

    /**
     * The transfer stopped part-way. [record] carries the last offset the
     * provider *acknowledged*, which is an honest lower bound and never a claim
     * on bytes that may not be there: a connection lost mid-`PATCH` can leave
     * the provider holding part of that chunk without the client ever hearing
     * so. Resuming re-reads the provider's own offset with `HEAD` before it
     * sends anything, so nothing already stored is re-sent and nothing is
     * skipped — that guarantee comes from the `HEAD`, not from this number.
     */
    data class Interrupted(
        override val record: PublicationImportRecord,
        val cause: Throwable? = null,
    ) : PublicationImportStep

    /** The import ended badly. [category] is the backend's own classification. */
    data class Failed(
        override val record: PublicationImportRecord,
        val category: PublicationFailureCategory,
    ) : PublicationImportStep
}
