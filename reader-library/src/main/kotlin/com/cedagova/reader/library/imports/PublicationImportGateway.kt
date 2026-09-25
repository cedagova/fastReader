package com.cedagova.reader.library.imports

import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.ReaderPublicationImportCapability

/**
 * The publication-import seam, beside `ReaderLibraryGateway` and for the same
 * reason (#117; library-owned since #199, A197-F002).
 *
 * This module owns the lifecycle and the transfer; a host owns *when* it runs,
 * and the unit tests that prove "when" have to run with no network, no SDK and
 * no Keystore. [ReaderApiPublicationImportGateway] is the one production
 * implementation; a host's tests substitute the scripted
 * `com.cedagova.reader.library.testing.FakePublicationImportGateway` from this
 * module's test fixtures.
 *
 * ## Consent survives the seam
 *
 * [start] and [resume] take an [UploadConsent] with no default, exactly as the
 * engine's own entry points do. That is deliberate and must stay: the seam is
 * where a convenience default would be tempting, and a default here would undo
 * the one thing the type exists for — that a call putting a book's bytes on the
 * wire cannot be written without naming the owner's consent at the call site
 * (REQ-505). There is no `DENIED`; refusing is not calling.
 *
 * Every bound — what is accepted, how large, how big a chunk — comes from
 * [importPolicy] and the grant inside an admission. Nothing here is a constant.
 */
public interface PublicationImportGateway {

    /** `GET /reader/v1/imports/policy`: what this deployment will admit today. */
    public suspend fun importPolicy(): PublicationImportPolicyResponse

    /**
     * The account's `reader.publication-import.v1` capability under the
     * contract's exactly-one rule: whether **Add to account library** may be
     * offered at all (#139). A read of the capabilities document; it names no
     * book and reserves nothing.
     */
    public suspend fun importCapability(): ReaderPublicationImportCapability

    /**
     * What [policy] alone already refuses about [source], or null when it could
     * be admitted. Pure, local, and the same code that enforces it — so the
     * shelf shows the refusal before the owner is ever asked for consent, and
     * before one byte or one admission exists.
     */
    public fun refuse(policy: PublicationImportPolicyResponse, source: PublicationSource): PublicationImportRefusal?

    /** Start an add: admit with [consent], then transfer and complete. */
    public suspend fun start(
        accountId: String,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit = {},
    ): PublicationImportStep

    /** Carry on a stored [record] after app death, a lost network or a spent grant. */
    public suspend fun resume(
        record: PublicationImportRecord,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit = {},
    ): PublicationImportStep

    /** One status read of a non-terminal import. */
    public suspend fun refresh(record: PublicationImportRecord): PublicationImportRecord

    /** Stop an import the owner changed their mind about; idempotent. */
    public suspend fun cancel(record: PublicationImportRecord): PublicationImportRecord
}

/**
 * The production gateway: a pass-through over the one engine the host owns.
 *
 * It holds [operations] as well, for the policy read alone. The engine reads
 * the policy itself on every [start] — that is the enforcement — and does not
 * re-publish it, so the *display* half (the cap the consent question quotes,
 * the refusal shown before any admission) comes from the same route through
 * the same client. Two readers, one source; no cached constant anywhere.
 */
public class ReaderApiPublicationImportGateway(
    private val operations: ReaderLibraryOperations,
    private val engine: PublicationImportEngine,
) : PublicationImportGateway {

    override suspend fun importPolicy(): PublicationImportPolicyResponse = operations.importPolicy()

    override suspend fun importCapability(): ReaderPublicationImportCapability =
        operations.publicationImportCapability()

    override fun refuse(
        policy: PublicationImportPolicyResponse,
        source: PublicationSource,
    ): PublicationImportRefusal? = engine.refuse(policy, source)

    override suspend fun start(
        accountId: String,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit,
    ): PublicationImportStep = engine.start(accountId, source, consent, onProgress)

    override suspend fun resume(
        record: PublicationImportRecord,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit,
    ): PublicationImportStep = engine.resume(record, source, consent, onProgress)

    override suspend fun refresh(record: PublicationImportRecord): PublicationImportRecord = engine.refresh(record)

    override suspend fun cancel(record: PublicationImportRecord): PublicationImportRecord = engine.cancel(record)
}
