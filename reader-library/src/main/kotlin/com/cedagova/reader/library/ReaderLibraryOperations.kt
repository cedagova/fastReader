package com.cedagova.reader.library

import com.cedagova.reader.library.model.CancelPublicationImportRequest
import com.cedagova.reader.library.model.CreatePublicationImportRequest
import com.cedagova.reader.library.model.PublicationImportAdmissionResponse
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.PublicationImportResponse
import com.cedagova.reader.library.model.ReaderAssetGrantResponse
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
 * every request FastReader can send is one of the eleven below, and each is
 * declared by the pinned OpenAPI document in `contracts/`.
 *
 * Five of them are the publication-import lifecycle (#116) and the last is the
 * asset download grant (#118). All six carry the
 * session like every other route here; the *bytes* do not go through them at
 * all — they go to Storage under the grant an admission returns, over
 * `com.cedagova.reader.library.imports.PublicationTransferClient`, and come
 * back under the grant `assetDownloadGrant` returns, over
 * `com.cedagova.reader.library.downloads.AssetDownloadClient`. Neither of
 * those two clients has a session to carry.
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

    // ---- Publication imports (#116) ---------------------------------------------------------

    /**
     * `GET /reader/v1/imports/policy`: what this deployment will admit.
     *
     * Every bound a host enforces — enabled at all, which formats, the size cap
     * per format, the active-import limits — is read from here on each attempt
     * and never remembered as a constant (REQ-506).
     */
    suspend fun importPolicy(): PublicationImportPolicyResponse

    /**
     * `POST /reader/v1/imports`: admit one device-only publication into the
     * account, idempotently on `client_import_id`.
     *
     * The answer carries the durable record and, while it awaits bytes, a
     * bounded TUS grant. Replaying the same `client_import_id` returns the same
     * record with `created = false`, which is how a retry after app death or a
     * grant expiry addresses the same admission instead of making a second one.
     *
     * @throws IllegalArgumentException when the request does not carry explicit
     *   `upload_consent`, the device-only promotion source or the
     *   account-library ownership intent. That is not a server round trip this
     *   module is willing to make: bytes leave the device only on the owner's
     *   word (REQ-505).
     */
    suspend fun admitImport(request: CreatePublicationImportRequest): PublicationImportAdmissionResponse

    /** `GET /reader/v1/imports/{id}`: the durable lifecycle state of one import. */
    suspend fun importRecord(importId: String): PublicationImportResponse

    /**
     * `POST /reader/v1/imports/{id}/complete`: the bytes are all at Storage.
     *
     * The backend observes the stored object itself and enters verification; it
     * never takes a digest from the uploader, so there is nothing to send.
     */
    suspend fun completeImport(importId: String): PublicationImportResponse

    /** `POST /reader/v1/imports/{id}/cancel`: stop an import; idempotent. */
    suspend fun cancelImport(
        importId: String,
        reason: String = CancelPublicationImportRequest.DEFAULT_REASON,
    ): PublicationImportResponse

    // ---- Asset downloads (#118) -------------------------------------------------------------

    /**
     * `POST /v1/reader/assets/{asset_id}/download-grant`: a short-lived signed
     * download for one asset the account may read.
     *
     * This is the only way book bytes come *onto* the device (REQ-510, D2). The
     * grant carries the provider's URL, its signature headers, the object's
     * length, its content SHA-256 and a TTL; FastReader composes none of them.
     * The bytes themselves do not come back through this route — they are
     * fetched by
     * `com.cedagova.reader.library.downloads.AssetDownloadClient`, which has no
     * session to carry.
     *
     * @throws IllegalArgumentException when [assetId] is not a UUID — a caller
     *   bug refused before a request exists, rather than a path segment built
     *   from something else.
     */
    suspend fun assetDownloadGrant(assetId: String): ReaderAssetGrantResponse
}
