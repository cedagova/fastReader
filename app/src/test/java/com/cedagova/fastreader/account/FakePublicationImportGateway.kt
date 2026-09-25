package com.cedagova.fastreader.account

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.imports.PublicationImportEngine
import com.cedagova.reader.library.imports.PublicationImportRecord
import com.cedagova.reader.library.imports.PublicationImportRefusal
import com.cedagova.reader.library.imports.PublicationImportStep
import com.cedagova.reader.library.imports.PublicationSource
import com.cedagova.reader.library.imports.PublicationTransferClient
import com.cedagova.reader.library.imports.UploadConsent
import com.cedagova.reader.library.model.CancelPublicationImportRequest
import com.cedagova.reader.library.model.CreatePublicationImportRequest
import com.cedagova.reader.library.model.PublicationArchivePolicy
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationFormatPolicy
import com.cedagova.reader.library.model.PublicationImportAdmissionResponse
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.PublicationImportResponse
import com.cedagova.reader.library.model.ReaderAssetGrantResponse
import com.cedagova.reader.library.model.ReaderCapabilityAvailability
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderPublicationImportCapability
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import kotlinx.coroutines.CompletableDeferred

/**
 * A scripted stand-in for the publication-import seam, the same shape as
 * `FakeReaderLibraryGateway` (in `:reader-library`'s tests) and for the same reason: the add-to-account flow
 * has to be provable with no network, no storage provider and no Keystore.
 *
 * Two things are **not** scripted, on purpose.
 *
 * [refuse] delegates to a real [PublicationImportEngine]. The policy check is
 * the thing REQ-506 is about — the cap and the accepted formats come from the
 * deployment and never from a constant — and a hand-written imitation of it
 * here would let the app and the module drift apart while every test stayed
 * green. The engine's transfer client is never reached by `refuse`; it is
 * constructed and never used.
 *
 * [consents] records every [UploadConsent] that arrived. A test asserting that
 * nothing was sent before the tap asserts this list is empty, which is a claim
 * about the *only* way bytes can leave.
 */
class FakePublicationImportGateway : PublicationImportGateway {

    val calls = mutableListOf<String>()

    /** Every consent that reached a call that could send bytes. */
    val consents = mutableListOf<UploadConsent>()

    /** Import ids the flow asked to cancel. */
    val cancelled = mutableListOf<String>()

    var policy: PublicationImportPolicyResponse = accepting()
    var policyFailure: ReaderAuthException? = null

    /**
     * What `reader.publication-import.v1` answers (#139). Available by default,
     * so every test about the flow itself starts from an offered action.
     */
    var capability: ReaderPublicationImportCapability = IMPORT_AVAILABLE
    var capabilityFailure: ReaderAuthException? = null

    /**
     * Capability reads, counted apart from [calls] on purpose: [calls] is the
     * record of what touched the import routes, and the tests that assert
     * "nothing about the book was sent" assert it on that list. A capabilities
     * read names no book and is counted here instead.
     */
    var capabilityReads: Int = 0

    /** When set, a capability read waits for it — a read still on the wire. */
    var capabilityGate: CompletableDeferred<Unit>? = null

    /** Answers for `start`/`resume`, in order; the last one repeats. */
    val steps = ArrayDeque<PublicationImportStep>()
    var stepFailure: ReaderAuthException? = null

    /** Answers for `refresh`, in order; running out means "unchanged". */
    val refreshes = ArrayDeque<PublicationImportRecord>()

    private val engine = PublicationImportEngine(UnusedOperations, PublicationTransferClient())

    override suspend fun importPolicy(): PublicationImportPolicyResponse {
        calls += "importPolicy()"
        policyFailure?.let { throw it }
        return policy
    }

    override suspend fun importCapability(): ReaderPublicationImportCapability {
        capabilityReads++
        capabilityGate?.await()
        capabilityFailure?.let { throw it }
        return capability
    }

    override fun refuse(
        policy: PublicationImportPolicyResponse,
        source: PublicationSource,
    ): PublicationImportRefusal? = engine.refuse(policy, source)

    override suspend fun start(
        accountId: String,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit,
    ): PublicationImportStep {
        calls += "start($accountId)"
        consents += consent
        stepFailure?.let { throw it }
        return nextStep()
    }

    override suspend fun resume(
        record: PublicationImportRecord,
        source: PublicationSource,
        consent: UploadConsent,
        onProgress: (PublicationImportRecord) -> Unit,
    ): PublicationImportStep {
        calls += "resume(${record.clientImportId})"
        consents += consent
        stepFailure?.let { throw it }
        return nextStep()
    }

    override suspend fun refresh(record: PublicationImportRecord): PublicationImportRecord {
        calls += "refresh(${record.importId})"
        return refreshes.removeFirstOrNull() ?: record
    }

    override suspend fun cancel(record: PublicationImportRecord): PublicationImportRecord {
        calls += "cancel(${record.importId})"
        record.importId?.let { cancelled += it }
        return record
    }

    private fun nextStep(): PublicationImportStep =
        if (steps.size > 1) steps.removeFirst() else steps.firstOrNull() ?: error("no scripted import step")

    companion object {

        /** reader-api's echo, the same shape the account gateway's fake uses. */
        const val REQUEST_ID: String = "0f1e2d3c-4b5a-4697-8877-665544332211"

        const val EPUB_MIME: String = "application/epub+zip"

        /** Exactly one `reader.publication-import.v1` entry, available. */
        val IMPORT_AVAILABLE: ReaderPublicationImportCapability = ReaderPublicationImportCapability(
            availability = ReaderCapabilityAvailability.AVAILABLE,
            reason = ReaderCapabilityReason.AVAILABLE,
            entries = 1,
        )

        /** A deployment that takes EPUBs up to [cap] bytes and says so. */
        fun accepting(cap: Long = 52_428_800, enabled: Boolean = true): PublicationImportPolicyResponse =
            PublicationImportPolicyResponse(
                requestId = REQUEST_ID,
                enabled = enabled,
                formats = listOf(
                    PublicationFormatPolicy(
                        format = PublicationFormat.EPUB,
                        extensions = listOf("epub"),
                        mimeTypes = listOf(EPUB_MIME),
                        maxSourceBytes = cap,
                        archive = true,
                    ),
                ),
                archive = PublicationArchivePolicy(
                    maxEntries = 10_000,
                    maxExpandedBytes = 524_288_000,
                    maxExpansionRatio = 100,
                ),
                maxActiveImports = 3,
                maxActiveBytes = 157_286_400,
            )
    }
}

/**
 * The operations half of the engine the fake builds only to borrow `refuse`.
 *
 * Every member throws: reaching one would mean `refuse` had stopped being the
 * pure, local check it is, which is a change worth failing a test over rather
 * than absorbing.
 */
private object UnusedOperations : ReaderLibraryOperations {
    private fun nope(): Nothing = error("the refusal check makes no request")

    override suspend fun library(): ReaderLibraryResponse = nope()
    override suspend fun progress(): ReaderProgressListResponse = nope()
    override suspend fun applyMutations(mutations: List<ReaderSyncMutationEnvelope>): ReaderSyncMutationBatchResponse =
        nope()
    override suspend fun deltas(afterCursor: String, limit: Int): ReaderSyncDeltaResponse = nope()
    override suspend fun syncCapability(): ReaderSyncCapability = nope()
    override suspend fun publicationImportCapability(): ReaderPublicationImportCapability = nope()
    override suspend fun importPolicy(): PublicationImportPolicyResponse = nope()
    override suspend fun admitImport(request: CreatePublicationImportRequest): PublicationImportAdmissionResponse =
        nope()
    override suspend fun importRecord(importId: String): PublicationImportResponse = nope()
    override suspend fun completeImport(importId: String): PublicationImportResponse = nope()
    override suspend fun cancelImport(importId: String, reason: String): PublicationImportResponse = nope()
    override suspend fun assetDownloadGrant(assetId: String): ReaderAssetGrantResponse = nope()
}
