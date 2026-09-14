package com.cedagova.reader.library

import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderSyncCapability
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope

/**
 * Every account-library operation this module offers, and nothing else.
 *
 * It is an interface so a host can substitute a scripted double in its own
 * tests without a mock engine; [ReaderLibraryClient] is the one production
 * implementation. There is deliberately no generic `call(path, body)` here:
 * every request FastReader can send is one of the five below, and each is
 * declared by the pinned OpenAPI document in `contracts/`.
 *
 * Failures are never new types. Each operation throws exactly the
 * `com.cedagova.reader.auth.ReaderAuthException` branch `:reader-auth`'s call
 * policy already produces — `SignedOut`, `Forbidden`, `TryLater`,
 * `NetworkUnavailable`, `ApiError` — unchanged and unwrapped. A server answer
 * that is well-formed HTTP but does not match the pinned contract is an
 * `ApiError`, because that is what it is: an answer this client cannot use.
 */
interface ReaderLibraryOperations {

    /** `GET /v1/reader/library`: the account's library rows. */
    suspend fun library(): ReaderLibraryResponse

    /** `GET /v1/reader/progress`: every reading position the account holds. */
    suspend fun progress(): ReaderProgressListResponse

    /**
     * `POST /v1/reader/sync/mutations`: admit a batch of 1 to 50 envelopes.
     *
     * Results are per envelope and partial outcomes are the contract, so the
     * caller reads every entry of the response rather than one batch verdict.
     *
     * @throws IllegalArgumentException when the batch is empty or larger than
     *   50 — a caller bug the module refuses locally instead of learning from
     *   a 422.
     */
    suspend fun applyMutations(mutations: List<ReaderSyncMutationEnvelope>): ReaderSyncMutationBatchResponse

    /**
     * `GET /v1/reader/sync/deltas`: the account's changes after [afterCursor].
     *
     * `cursor_expired` and `cursor_invalid` come back as
     * [ReaderSyncDeltaResponse.status], never as an exception.
     *
     * @throws IllegalArgumentException when [afterCursor] is not a decimal
     *   cursor or [limit] is outside 1..500.
     */
    suspend fun deltas(
        afterCursor: String = ReaderLibraryClient.FIRST_CURSOR,
        limit: Int = ReaderLibraryClient.DEFAULT_DELTA_LIMIT,
    ): ReaderSyncDeltaResponse

    /**
     * `GET /v1/reader/capabilities?clientVersion=…`: the `reader.sync.v1` entry.
     *
     * A document without that entry reads as
     * [ReaderSyncCapability.UNDECLARED] — unavailable, reason unknown.
     */
    suspend fun syncCapability(): ReaderSyncCapability
}
