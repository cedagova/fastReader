package com.cedagova.reader.library.sync

import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.library.model.ReaderCapabilityAvailability
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderDeltaStatus
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import com.cedagova.reader.library.model.ReaderSyncMutationResult
import kotlinx.coroutines.CompletableDeferred

/**
 * The scripted account-library gateway the sync tests and the shelf goldens
 * run against: no SDK, no network, no Keystore — the same bargain
 * [FakeReaderAccountGateway] makes for the sign-in surface.
 *
 * Each operation records its call, then either answers from the script, throws
 * the [ReaderAuthException] a test set, or parks on a gate so a test can watch
 * the in-flight state. Answers are queues: a test states the successive
 * responses it cares about and the last one repeats, which is what a sync loop
 * that calls `deltas` more than once needs.
 */
class FakeReaderLibraryGateway : ReaderLibraryGateway {

    /** Every call, in order, as `operation(arguments)`. */
    val calls = mutableListOf<String>()

    /** The envelopes each `applyMutations` call was given. */
    val submitted = mutableListOf<List<ReaderSyncMutationEnvelope>>()

    /** The failure the next operation throws, consumed once. */
    var nextFailure: ReaderAuthException? = null

    /** When set, the next operation parks here until the test completes it. */
    var gate: CompletableDeferred<Unit>? = null

    /** Failures keyed by the exact call (`deltas(0, 1)`), each thrown once when that call is made. */
    val failuresOn = mutableMapOf<String, ReaderAuthException>()

    /** Gates keyed by the exact call: that call parks until the test completes it, once. */
    val gatesOn = mutableMapOf<String, CompletableDeferred<Unit>>()

    var libraryResponses: ArrayDeque<ReaderLibraryResponse> =
        ArrayDeque(listOf(ReaderLibraryResponse(requestId = REQUEST_ID)))
    var progressResponses: ArrayDeque<ReaderProgressListResponse> =
        ArrayDeque(listOf(ReaderProgressListResponse(requestId = REQUEST_ID)))
    var mutationResponses: ArrayDeque<ReaderSyncMutationBatchResponse> =
        ArrayDeque(listOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID)))
    var deltaResponses: ArrayDeque<ReaderSyncDeltaResponse> = ArrayDeque(listOf(emptyDeltas()))
    var capability: ReaderSyncCapability = AVAILABLE

    override suspend fun library(): ReaderLibraryResponse {
        record("library()")
        return libraryResponses.take()
    }

    override suspend fun progress(): ReaderProgressListResponse {
        record("progress()")
        return progressResponses.take()
    }

    override suspend fun applyMutations(
        mutations: List<ReaderSyncMutationEnvelope>,
    ): ReaderSyncMutationBatchResponse {
        record("applyMutations(${mutations.size})")
        submitted += mutations
        return mutationResponses.take()
    }

    override suspend fun deltas(afterCursor: String, limit: Int): ReaderSyncDeltaResponse {
        record("deltas($afterCursor, $limit)")
        return deltaResponses.take()
    }

    override suspend fun syncCapability(): ReaderSyncCapability {
        record("syncCapability()")
        return capability
    }

    /** Queue [results] as the outcome of the next `applyMutations`. */
    fun answerMutations(vararg results: ReaderSyncMutationResult) {
        mutationResponses = ArrayDeque(
            listOf(ReaderSyncMutationBatchResponse(requestId = REQUEST_ID, results = results.toList())),
        )
    }

    private suspend fun record(call: String) {
        calls += call
        gate?.let { gate = null; it.await() }
        nextFailure?.let { nextFailure = null; throw it }
        gatesOn.remove(call)?.await()
        failuresOn.remove(call)?.let { throw it }
    }

    /** The last queued answer repeats, so a test states only the answers that matter. */
    private fun <T> ArrayDeque<T>.take(): T = if (size > 1) removeFirst() else first()

    companion object {
        const val REQUEST_ID: String = "0f1e2d3c-4b5a-4697-8877-665544332211"

        /** `reader.sync.v1` available, which is the ordinary case. */
        val AVAILABLE: ReaderSyncCapability = ReaderSyncCapability(
            availability = ReaderCapabilityAvailability.AVAILABLE,
            reason = ReaderCapabilityReason.AVAILABLE,
        )

        fun emptyDeltas(
            status: ReaderDeltaStatus = ReaderDeltaStatus.OK,
            latestCursor: String = "0",
        ): ReaderSyncDeltaResponse = ReaderSyncDeltaResponse(
            requestId = REQUEST_ID,
            status = status,
            minimumValidCursor = "0",
            latestCursor = latestCursor,
        )
    }
}
