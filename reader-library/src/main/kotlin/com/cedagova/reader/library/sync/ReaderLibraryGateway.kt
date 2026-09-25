package com.cedagova.reader.library.sync

import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.ReaderLibraryOperations
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope

/**
 * The five library operations [AccountSyncEngine] needs, as a seam a host can
 * substitute.
 *
 * It exists for testability. [ReaderLibraryClient] needs a live
 * `ReaderApiClient` behind it, and [ReaderLibraryOperations] carries every
 * import and download route the engine never calls — while the sync engine, its
 * unit tests and a host's own screenshot tests all need to run with no SDK, no
 * network and no Keystore against a double of just these five calls.
 * Production is [ReaderApiLibraryGateway], a pass-through; tests substitute a
 * scripted fake.
 *
 * Every method here is a *named library operation*. There is deliberately no
 * `get(path)`, `post(path, body)` or any other generic call on this seam: the
 * set of requests the engine can send is the set below, each one declared by
 * the pinned OpenAPI document `:reader-library` commits, and a generic escape
 * hatch would quietly undo that.
 *
 * Failures are `com.cedagova.reader.auth.ReaderAuthException` branches thrown
 * through unchanged. This gateway maps nothing, retries nothing and classifies
 * nothing.
 */
public interface ReaderLibraryGateway {

    /** The account's library rows, as the server holds them. */
    public suspend fun library(): ReaderLibraryResponse

    /** Every reading position the account holds. */
    public suspend fun progress(): ReaderProgressListResponse

    /** Admit a batch of 1 to 50 mutations; results are per envelope. */
    public suspend fun applyMutations(mutations: List<ReaderSyncMutationEnvelope>): ReaderSyncMutationBatchResponse

    /** The account's changes after [afterCursor]; cursor expiry is a status, not a failure. */
    public suspend fun deltas(
        afterCursor: String = ReaderLibraryClient.FIRST_CURSOR,
        limit: Int = ReaderLibraryClient.DEFAULT_DELTA_LIMIT,
    ): ReaderSyncDeltaResponse

    /** Whether `reader.sync.v1` is available to this client right now. */
    public suspend fun syncCapability(): ReaderSyncCapability
}

/**
 * The production gateway: a thin pass-through over the one
 * [ReaderLibraryOperations] the application owns, itself built on the one
 * authenticated client. Each method is exactly one library call.
 */
public class ReaderApiLibraryGateway(private val operations: ReaderLibraryOperations) : ReaderLibraryGateway {

    override suspend fun library(): ReaderLibraryResponse = operations.library()

    override suspend fun progress(): ReaderProgressListResponse = operations.progress()

    override suspend fun applyMutations(mutations: List<ReaderSyncMutationEnvelope>): ReaderSyncMutationBatchResponse =
        operations.applyMutations(mutations)

    override suspend fun deltas(afterCursor: String, limit: Int): ReaderSyncDeltaResponse =
        operations.deltas(afterCursor, limit)

    override suspend fun syncCapability(): ReaderSyncCapability = operations.syncCapability()
}
