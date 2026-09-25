package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `POST /v1/reader/sync/mutations` — the request body.
 *
 * The document allows 1 to 50 envelopes; `ReaderLibraryClient` refuses a batch
 * outside that range before it sends anything, so the bound is never learned
 * from a 422.
 */
@Serializable
internal data class ReaderSyncMutationBatchRequest(
    @SerialName("mutations") val mutations: List<ReaderSyncMutationEnvelope>,
) {
    companion object {
        const val MIN_MUTATIONS: Int = 1
        const val MAX_MUTATIONS: Int = 50
    }
}

/**
 * One mutation the client asks the server to admit.
 *
 * [idempotencyKey] is what makes a re-send safe: the server returns the exact
 * durable admission again as `replayed` rather than admitting it twice, which
 * is why the outbox keeps a queued mutation's original key across a retry, a
 * process death and a cursor re-bootstrap (AD-22).
 *
 * [clientCreatedAt] is retained in the request fingerprint only; it never
 * becomes canonical time and never selects a winner.
 */
@Serializable
public data class ReaderSyncMutationEnvelope(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("resource_type") val resourceType: ReaderResourceType,
    @SerialName("resource_id") val resourceId: String,
    @SerialName("mutation_kind") val mutationKind: ReaderMutationKind,
    /** The revision the client believed was current; 0 for a resource it has never seen. */
    @SerialName("base_revision") val baseRevision: Long,
    @SerialName("payload") val payload: JsonObject = EMPTY_JSON_OBJECT,
    @SerialName("client_created_at") val clientCreatedAt: String? = null,
)

/**
 * `POST /v1/reader/sync/mutations` — the response.
 *
 * Results are per envelope: an unrelated conflict or rejection does not roll
 * back its accepted siblings, so a caller reads every entry of [results] and
 * never treats the batch as one outcome.
 */
@Serializable
public data class ReaderSyncMutationBatchResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("results") val results: List<ReaderSyncMutationResult> = emptyList(),
    @SerialName("contract_version") val contractVersion: String = SYNC_CONTRACT_VERSION,
    @SerialName("publication_membership_version") val publicationMembershipVersion: String = MEMBERSHIP_VERSION,
    @SerialName("activity_convergence_version") val activityConvergenceVersion: String = ACTIVITY_CONVERGENCE_VERSION,
)

/** The `reader.sync.v1` contract constant the batch and delta documents carry. */
internal const val SYNC_CONTRACT_VERSION: String = "reader.sync.v1"

/** The publication-membership contract constant. */
internal const val MEMBERSHIP_VERSION: String = "reader.publication-membership.v1"

/** The activity-convergence contract constant. */
internal const val ACTIVITY_CONVERGENCE_VERSION: String = "reader.activity-convergence.v1"

/**
 * What the server did with one envelope.
 *
 * [canonicalPayload] is the server's own state for the resource and is what a
 * caller adopts — for `applied`, `replayed`, `superseded` *and* `conflict*
 * alike (AD-22: canonical adoption, never local resolution). A rejection
 * carries an empty object there and the reason in [rejection].
 *
 * [revision], [cursor], [serverAdmission] and [serverAdmittedAt] are present
 * exactly for an admitted result and null otherwise; the document states that
 * as a conditional requirement, which is why they are nullable here.
 */
@Serializable
public data class ReaderSyncMutationResult(
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("resource_type") val resourceType: ReaderResourceType = ReaderResourceType.UNKNOWN,
    @SerialName("resource_id") val resourceId: String,
    @SerialName("mutation_kind") val mutationKind: ReaderMutationKind = ReaderMutationKind.UNKNOWN,
    @SerialName("status") val status: ReaderSyncStatus = ReaderSyncStatus.UNKNOWN,
    @SerialName("canonical_payload") val canonicalPayload: JsonObject = EMPTY_JSON_OBJECT,
    @SerialName("server_admission") val serverAdmission: ReaderServerAdmission? = null,
    @SerialName("revision") val revision: Long? = null,
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("server_admitted_at") val serverAdmittedAt: String? = null,
    @SerialName("conflict") val conflict: ReaderSyncConflict? = null,
    @SerialName("rejection") val rejection: ReaderSyncRejection? = null,
    @SerialName("membership") val membership: ReaderPublicationMembershipOutcome? = null,
) {
    /** True when the server admitted this envelope — newly, or as the exact replay of an earlier one. */
    val isAdmitted: Boolean
        get() = status == ReaderSyncStatus.APPLIED ||
            status == ReaderSyncStatus.REPLAYED ||
            status == ReaderSyncStatus.SUPERSEDED
}

/**
 * A conflict the server resolved authoritatively.
 *
 * The contract says this cannot occur for activity or membership and may only
 * occur for profile and settings, which FastReader never sends — so a caller
 * adopts [canonicalPayload] and logs it, and never prompts (AD-22).
 */
@Serializable
public data class ReaderSyncConflict(
    @SerialName("conflict_id") val conflictId: String,
    @SerialName("code") val code: ReaderSyncConflictCode = ReaderSyncConflictCode.UNKNOWN,
    @SerialName("remote_revision") val remoteRevision: Long,
    @SerialName("conflicting_fields") val conflictingFields: List<String> = emptyList(),
    @SerialName("canonical_payload") val canonicalPayload: JsonObject = EMPTY_JSON_OBJECT,
)

/** Why the server refused one envelope outright. [retryable] false means re-sending it changes nothing. */
@Serializable
public data class ReaderSyncRejection(
    @SerialName("code") val code: ReaderSyncRejectionCode = ReaderSyncRejectionCode.UNKNOWN,
    @SerialName("detail") val detail: String,
    @SerialName("retryable") val retryable: Boolean = false,
)

/**
 * What an add, remove or undo did to the account's membership of a publication.
 *
 * The three constants the document fixes — activity identity is preserved, no
 * download bytes change, no durable recovery — are modelled so the contract
 * test pins them; the module never sends this shape.
 */
@Serializable
public data class ReaderPublicationMembershipOutcome(
    @SerialName("state") val state: ReaderMembershipState = ReaderMembershipState.UNKNOWN,
    @SerialName("action") val action: ReaderMembershipAction = ReaderMembershipAction.UNKNOWN,
    @SerialName(
        "open_session_behavior",
    ) val openSessionBehavior: ReaderOpenSessionBehavior = ReaderOpenSessionBehavior.UNKNOWN,
    @SerialName("reopen_allowed") val reopenAllowed: Boolean,
    @SerialName("identity_restored") val identityRestored: Boolean = false,
    @SerialName("undo_available") val undoAvailable: Boolean = false,
    @SerialName("undo_scope") val undoScope: String = "immediate_confirmation",
    @SerialName("activity_identity_preserved") val activityIdentityPreserved: Boolean = true,
    @SerialName("download_bytes_changed") val downloadBytesChanged: Boolean = false,
    @SerialName("durable_recovery_available") val durableRecoveryAvailable: Boolean = false,
)

/**
 * `GET /v1/reader/sync/deltas` — everything the account changed after a cursor.
 *
 * [status] is the typed outcome: `cursor_expired` and `cursor_invalid` are
 * answers, not failures. When [rebootstrapRequired] is true the caller throws
 * its cursor away and reads the lists again; [minimumValidCursor] is the oldest
 * cursor the stream can still serve.
 */
@Serializable
public data class ReaderSyncDeltaResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("status") val status: ReaderDeltaStatus = ReaderDeltaStatus.UNKNOWN,
    @SerialName("minimum_valid_cursor") val minimumValidCursor: String,
    @SerialName("latest_cursor") val latestCursor: String,
    @SerialName("changes") val changes: List<ReaderSyncChange> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("rebootstrap_required") val rebootstrapRequired: Boolean = false,
    @SerialName("contract_version") val contractVersion: String = SYNC_CONTRACT_VERSION,
    @SerialName("publication_membership_version") val publicationMembershipVersion: String = MEMBERSHIP_VERSION,
)

/** One change in the account's stream, in canonical order by [cursor]. */
@Serializable
public data class ReaderSyncChange(
    @SerialName("cursor") val cursor: String,
    @SerialName("resource_type") val resourceType: ReaderResourceType = ReaderResourceType.UNKNOWN,
    @SerialName("resource_id") val resourceId: String,
    @SerialName("revision") val revision: Long,
    @SerialName("kind") val kind: ReaderMutationKind = ReaderMutationKind.UNKNOWN,
    @SerialName("server_admitted_at") val serverAdmittedAt: String,
    @SerialName("canonical_payload") val canonicalPayload: JsonObject = EMPTY_JSON_OBJECT,
)
