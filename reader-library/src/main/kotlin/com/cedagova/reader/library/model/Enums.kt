package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The single wire value that is deliberately **not** in the Reader API
 * contract.
 *
 * Every enum below carries an [UNKNOWN_VALUE] member and every decoded field
 * that uses one has a default, so `ReaderLibraryJson`'s `coerceInputValues`
 * turns a value reader-api adds later into that member instead of throwing.
 * That is what "forward-compatible" means here: an older FastReader keeps
 * reading a newer server's documents and shows the rows it understands.
 *
 * `ReaderLibraryContractTest` skips this member when it compares an enum with
 * the document, and this module never *sends* it: the one enum on the request
 * side ([ReaderMutationKind] inside [ReaderSyncMutationEnvelope]) is chosen by
 * the caller from the contract's own values.
 */
internal const val UNKNOWN_VALUE: String = "__unknown__"

/** `ReaderLibraryItem.status`: where a book sits in the account's library. */
@Serializable
public enum class ReaderLibraryStatus {
    @SerialName("queued")
    QUEUED,

    @SerialName("reading")
    READING,

    @SerialName("finished")
    FINISHED,

    @SerialName("archived")
    ARCHIVED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/**
 * `ReaderLibraryItem.cover_status`: the server's authoritative classification.
 * `PENDING` is unknown or retryable and must never be rendered as genuinely
 * coverless.
 */
@Serializable
public enum class ReaderCoverStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("covered")
    COVERED,

    @SerialName("coverless")
    COVERLESS,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderBook.source_type`: how the account acquired the book. */
@Serializable
public enum class ReaderBookSourceType {
    @SerialName("upload")
    UPLOAD,

    @SerialName("url")
    URL,

    @SerialName("catalog")
    CATALOG,

    @SerialName("import")
    IMPORT,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderBookAsset.kind`: which file of a book an asset is. */
@Serializable
public enum class ReaderBookAssetKind {
    @SerialName("epub")
    EPUB,

    @SerialName("cover")
    COVER,

    @SerialName("extracted_text")
    EXTRACTED_TEXT,

    @SerialName("audio")
    AUDIO,

    @SerialName("other")
    OTHER,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** The resource a sync envelope, result or change is about. */
@Serializable
public enum class ReaderResourceType {
    @SerialName("profile")
    PROFILE,

    @SerialName("book")
    BOOK,

    @SerialName("library_item")
    LIBRARY_ITEM,

    @SerialName("reading_progress")
    READING_PROGRESS,

    @SerialName("note")
    NOTE,

    @SerialName("bookmark")
    BOOKMARK,

    @SerialName("settings")
    SETTINGS,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** What a sync envelope, result or change does to its resource. */
@Serializable
public enum class ReaderMutationKind {
    @SerialName("upsert")
    UPSERT,

    @SerialName("delete")
    DELETE,

    @SerialName("restore")
    RESTORE,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/**
 * `ReaderSyncMutationResult.status`: the authoritative per-envelope outcome.
 *
 * `APPLIED` and `SUPERSEDED` mean a new transaction was admitted; `REPLAYED`
 * means the exact durable admission was returned without admitting it again —
 * which is what a re-sent idempotency key gets. `CONFLICT` and `REJECTED`
 * carry [ReaderSyncConflict] and [ReaderSyncRejection] instead of a revision.
 */
@Serializable
public enum class ReaderSyncStatus {
    @SerialName("applied")
    APPLIED,

    @SerialName("replayed")
    REPLAYED,

    @SerialName("superseded")
    SUPERSEDED,

    @SerialName("conflict")
    CONFLICT,

    @SerialName("rejected")
    REJECTED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderSyncMutationResult.server_admission`: null for a conflict or rejection. */
@Serializable
public enum class ReaderServerAdmission {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("replayed")
    REPLAYED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderSyncConflict.code`. */
@Serializable
public enum class ReaderSyncConflictCode {
    @SerialName("revision_conflict")
    REVISION_CONFLICT,

    @SerialName("content_conflict")
    CONTENT_CONFLICT,

    @SerialName("field_conflict")
    FIELD_CONFLICT,

    @SerialName("tombstone_requires_restore")
    TOMBSTONE_REQUIRES_RESTORE,

    @SerialName("restore_requires_tombstone")
    RESTORE_REQUIRES_TOMBSTONE,

    @SerialName("resource_missing")
    RESOURCE_MISSING,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderSyncRejection.code`: why the server refused one envelope outright. */
@Serializable
public enum class ReaderSyncRejectionCode {
    @SerialName("invalid_payload")
    INVALID_PAYLOAD,

    @SerialName("invalid_resource_id")
    INVALID_RESOURCE_ID,

    @SerialName("idempotency_mismatch")
    IDEMPOTENCY_MISMATCH,

    @SerialName("related_resource_missing")
    RELATED_RESOURCE_MISSING,

    @SerialName("unsupported_mutation")
    UNSUPPORTED_MUTATION,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderPublicationMembershipOutcome.state`. */
@Serializable
public enum class ReaderMembershipState {
    @SerialName("present")
    PRESENT,

    @SerialName("absent")
    ABSENT,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderPublicationMembershipOutcome.action`. */
@Serializable
public enum class ReaderMembershipAction {
    @SerialName("add")
    ADD,

    @SerialName("remove")
    REMOVE,

    @SerialName("undo")
    UNDO,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderPublicationMembershipOutcome.open_session_behavior`. */
@Serializable
public enum class ReaderOpenSessionBehavior {
    @SerialName("unchanged")
    UNCHANGED,

    @SerialName("keep_readable_until_close")
    KEEP_READABLE_UNTIL_CLOSE,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/**
 * `ReaderSyncDeltaResponse.status`. `CURSOR_EXPIRED` and `CURSOR_INVALID` are
 * typed results, never exceptions: the caller re-bootstraps instead of
 * treating them as a failure.
 */
@Serializable
public enum class ReaderDeltaStatus {
    @SerialName("ok")
    OK,

    @SerialName("cursor_expired")
    CURSOR_EXPIRED,

    @SerialName("cursor_invalid")
    CURSOR_INVALID,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderCapabilityEntry.key`: the capability keys the document declares. */
@Serializable
internal enum class ReaderCapabilityKey {
    @SerialName("reader.sync.v1")
    SYNC_V1,

    @SerialName("reader.ai-quota.v1")
    AI_QUOTA_V1,

    @SerialName("reader.notifications.registration.v1")
    NOTIFICATIONS_REGISTRATION_V1,

    @SerialName("reader.publication-import.v1")
    PUBLICATION_IMPORT_V1,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/**
 * `ReaderAssetGrant.direction`: which way a signed transfer goes.
 *
 * FastReader asks for a download grant and reads this back rather than
 * assuming it: a grant that came back for the other direction is a grant this
 * client must not spend, and `AssetDownloadClient` refuses one.
 */
@Serializable
public enum class ReaderAssetDirection {
    @SerialName("upload")
    UPLOAD,

    @SerialName("download")
    DOWNLOAD,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderAssetGrant.method`: the HTTP method the provider signed the URL for. */
@Serializable
public enum class ReaderAssetMethod {
    @SerialName("GET")
    GET,

    @SerialName("PUT")
    PUT,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderCapabilityEntry.availability`. */
@Serializable
public enum class ReaderCapabilityAvailability {
    @SerialName("available")
    AVAILABLE,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `ReaderCapabilityEntry.reason`: why a capability is or is not available. */
@Serializable
public enum class ReaderCapabilityReason {
    @SerialName("available")
    AVAILABLE,

    @SerialName("actor_dependency_unavailable")
    ACTOR_DEPENDENCY_UNAVAILABLE,

    @SerialName("client_version_invalid")
    CLIENT_VERSION_INVALID,

    @SerialName("client_version_missing")
    CLIENT_VERSION_MISSING,

    @SerialName("client_version_too_new")
    CLIENT_VERSION_TOO_NEW,

    @SerialName("client_version_too_old")
    CLIENT_VERSION_TOO_OLD,

    @SerialName("quota_exhausted")
    QUOTA_EXHAUSTED,

    @SerialName("service_not_configured")
    SERVICE_NOT_CONFIGURED,

    @SerialName("service_not_enabled")
    SERVICE_NOT_ENABLED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}
