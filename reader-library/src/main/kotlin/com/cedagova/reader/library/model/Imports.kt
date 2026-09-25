package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The publication-import contract as reader-api publishes it (#116, LEAF801 of
 * #104), modelled the same way the rest of this module is: one Kotlin type per
 * schema, every wire name spelled out, every enum carrying the [UNKNOWN_VALUE]
 * forward-compatibility member, and every one of them pinned to its schema by
 * `ReaderLibraryContractTest`.
 *
 * Read the pinned document, not this file, for the authority: these are
 * `CreatePublicationImportRequest`, `PublicationImportPolicyResponse`,
 * `PublicationTransferGrant`, `PublicationImport` and their neighbours in
 * `contracts/reader-api.openapi.json`.
 *
 * Two shapes deliberately have no Kotlin equivalent:
 *
 * - `PublicationImport.terminal_code`, which the document marks deprecated and
 *   describes as a bounded alias of `failure.category`. This module reads the
 *   category.
 * - every `const` field (`promotion_source`, `ownership_intent`, `source`,
 *   `destination`, …) is a `String` with a companion constant rather than a
 *   one-member enum, because the document spells them `const`, not `enum`, and
 *   a single accepted value is a constant, not a choice.
 */

/** `PublicationFormat`: every source format the backend routes. */
@Serializable
public enum class PublicationFormat {
    @SerialName("epub")
    EPUB,

    @SerialName("pdf")
    PDF,

    @SerialName("txt")
    TXT,

    @SerialName("mobi")
    MOBI,

    @SerialName("azw")
    AZW,

    @SerialName("azw3")
    AZW3,

    @SerialName("docx")
    DOCX,

    @SerialName("fb2")
    FB2,

    @SerialName("md")
    MD,

    @SerialName("html")
    HTML,

    @SerialName("mhtml")
    MHTML,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/**
 * `PublicationImport.status`: the whole import lifecycle.
 *
 * [isTerminal] is the only judgement this module makes about it — a caller
 * polls while an import is not terminal and stops when it is.
 */
@Serializable
public enum class PublicationImportStatus {
    @SerialName("pending_upload")
    PENDING_UPLOAD,

    @SerialName("verifying_upload")
    VERIFYING_UPLOAD,

    @SerialName("queued")
    QUEUED,

    @SerialName("processing")
    PROCESSING,

    @SerialName("ready")
    READY,

    @SerialName("failed")
    FAILED,

    @SerialName("cancel_requested")
    CANCEL_REQUESTED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("deleting")
    DELETING,

    @SerialName("deleted")
    DELETED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,

    ;

    /** True once the backend will not move this import again on its own. */
    public val isTerminal: Boolean
        get() = this == READY || this == FAILED || this == CANCELLED || this == DELETED
}

/**
 * `PublicationImportFailure.category`: why an import ended badly.
 *
 * This is the closed set a host renders. It is the backend's classification,
 * never a message: REQ-507 asks for the backend's category, and a client that
 * invented its own wording would be showing something the server did not say.
 */
@Serializable
public enum class PublicationFailureCategory {
    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("protected")
    PROTECTED,

    @SerialName("unsafe")
    UNSAFE,

    @SerialName("too_large")
    TOO_LARGE,

    @SerialName("malformed")
    MALFORMED,

    @SerialName("upload")
    UPLOAD,

    @SerialName("conversion")
    CONVERSION,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `PublicationFormatPolicy.account_admission`: what account admission costs for a format. */
@Serializable
public enum class PublicationAccountAdmission {
    @SerialName("upload_only")
    UPLOAD_ONLY,

    @SerialName("upload_and_conversion")
    UPLOAD_AND_CONVERSION,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `PublicationFormatPolicy.device_renderability`. */
@Serializable
public enum class PublicationDeviceRenderability {
    @SerialName("device_native")
    DEVICE_NATIVE,

    @SerialName("reader_conversion_required")
    READER_CONVERSION_REQUIRED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `PublicationPromotion.account_admission`: how far the promotion has got. */
@Serializable
public enum class PublicationPromotionAdmission {
    @SerialName("pending")
    PENDING,

    @SerialName("admitted")
    ADMITTED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/** `PublicationPromotion.activity_continuity`. */
@Serializable
public enum class PublicationActivityContinuity {
    @SerialName("wait_for_admission")
    WAIT_FOR_ADMISSION,

    @SerialName("replay_to_admitted_identity")
    REPLAY_TO_ADMITTED_IDENTITY,

    @SerialName(UNKNOWN_VALUE)
    UNKNOWN,
}

/**
 * `POST /reader/v1/imports` — the admission body.
 *
 * [uploadConsent] is nullable on the wire because the document makes it
 * optional, and is `true` in every request this module sends: the one place it
 * is put on the wire, `ReaderLibraryClient.admitImport`, refuses anything else
 * locally. Bytes leaving the device is the owner's decision (REQ-505), so it is
 * not a field with a convenient default.
 */
@Serializable
public data class CreatePublicationImportRequest(
    /** Derived from the content identity and the account; see `PublicationImportRecord`. */
    @SerialName("client_import_id") val clientImportId: String,
    @SerialName("source_format") val sourceFormat: PublicationFormat,
    @SerialName("source_mime_type") val sourceMimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    /** Bare lowercase hex or `sha256:`-prefixed; the backend normalises either. */
    @SerialName("sha256") val sha256: String,
    @SerialName("promotion_source") val promotionSource: String? = PROMOTION_SOURCE_DEVICE_ONLY,
    @SerialName("ownership_intent") val ownershipIntent: String? = OWNERSHIP_INTENT_ACCOUNT_LIBRARY,
    @SerialName("upload_consent") val uploadConsent: Boolean? = null,
    @SerialName("original_file_name") val originalFileName: String? = null,
) {
    public companion object {
        /** The document's only accepted `promotion_source`. */
        public const val PROMOTION_SOURCE_DEVICE_ONLY: String = "device_only"

        /** The document's only accepted `ownership_intent`. */
        public const val OWNERSHIP_INTENT_ACCOUNT_LIBRARY: String = "account_library"

        /** `client_import_id` is 1..255 characters. */
        public const val MAX_CLIENT_IMPORT_ID_LENGTH: Int = 255
    }
}

/** `POST /reader/v1/imports/{id}/cancel` — the cancel body. */
@Serializable
public data class CancelPublicationImportRequest(@SerialName("reason") val reason: String = DEFAULT_REASON) {
    public companion object {
        /** The document's own default, and the only reason this module sends. */
        public const val DEFAULT_REASON: String = "cancelled_by_actor"
        public const val MAX_REASON_LENGTH: Int = 120
    }
}

/**
 * `GET /reader/v1/imports/policy` — the admission policy.
 *
 * Every bound a host may show or enforce lives here and nowhere else: the cap
 * comes from [PublicationFormatPolicy.maxSourceBytes], the accepted types from
 * [PublicationFormatPolicy.mimeTypes], the transfer size from the grant. This
 * module hard-codes none of them (REQ-506).
 */
@Serializable
public data class PublicationImportPolicyResponse(
    @SerialName("request_id") val requestId: String,
    /** False when the deployment offers no imports at all; a host shows the action off. */
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("formats") val formats: List<PublicationFormatPolicy> = emptyList(),
    @SerialName("archive") val archive: PublicationArchivePolicy,
    @SerialName("max_active_imports") val maxActiveImports: Int,
    @SerialName("max_active_bytes") val maxActiveBytes: Long,
    @SerialName("ownership") val ownership: PublicationOwnershipPolicy = PublicationOwnershipPolicy(),
) {
    /** The policy entry whose declared MIME types contain [mimeType], case-insensitively. */
    public fun formatForMimeType(mimeType: String): PublicationFormatPolicy? {
        val wanted = mimeType.substringBefore(';').trim().lowercase()
        return formats.firstOrNull { entry ->
            entry.mimeTypes.any { it.substringBefore(';').trim().lowercase() == wanted }
        }
    }
}

/** One format the backend routes, with the bounds that apply to it. */
@Serializable
public data class PublicationFormatPolicy(
    @SerialName("format") val format: PublicationFormat = PublicationFormat.UNKNOWN,
    @SerialName("extensions") val extensions: List<String> = emptyList(),
    @SerialName("mime_types") val mimeTypes: List<String> = emptyList(),
    /** The effective uploaded-source maximum for this format. The cap, read never assumed. */
    @SerialName("max_source_bytes") val maxSourceBytes: Long,
    @SerialName("archive") val archive: Boolean,
    @SerialName("device_renderability") val deviceRenderability: PublicationDeviceRenderability? = null,
    @SerialName("device_only_allowed") val deviceOnlyAllowed: Boolean? = null,
    @SerialName("account_admission") val accountAdmission: PublicationAccountAdmission? = null,
    @SerialName("explicit_upload_consent_required") val explicitUploadConsentRequired: Boolean? = null,
)

/**
 * The ownership half of the policy. Only the three statements this module acts
 * on are modelled; the rest of the schema is read past, as everywhere else.
 */
@Serializable
public data class PublicationOwnershipPolicy(
    /** True in the contract: why [com.cedagova.reader.library.imports.UploadConsent] exists. */
    @SerialName("promotion_upload_consent_required") val promotionUploadConsentRequired: Boolean = true,
    /** False in the contract: reading a device book uploads nothing. */
    @SerialName("device_only_upload_bytes") val deviceOnlyUploadBytes: Boolean = false,
    /** False in the contract: reading a device book calls no route at all. */
    @SerialName("device_only_api_calls_required") val deviceOnlyApiCallsRequired: Boolean = false,
)

/** The archive-expansion bounds the backend enforces on a zipped source. */
@Serializable
public data class PublicationArchivePolicy(
    @SerialName("max_entries") val maxEntries: Long,
    @SerialName("max_expanded_bytes") val maxExpandedBytes: Long,
    @SerialName("max_expansion_ratio") val maxExpansionRatio: Long,
)

/**
 * The bounded, signed TUS grant an admission issues.
 *
 * [headers] are the provider's own and are the *only* authentication the
 * transfer has: the document says in so many words that bearer authentication
 * is not a substitute. `PublicationTransferClient` sends these and never the
 * session, and `PublicationTransferClientTest` asserts it.
 */
@Serializable
public data class PublicationTransferGrant(
    /** The signed creation endpoint; the contract has it ending in `/upload/resumable/sign`. */
    @SerialName("endpoint") val endpoint: String,
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
    @SerialName("metadata") val metadata: Map<String, String> = emptyMap(),
    /** The largest PATCH body the provider accepts. The transfer chunks to this, never to a constant. */
    @SerialName("chunk_size_bytes") val chunkSizeBytes: Long,
    /** ISO-8601; after this the signature is rejected and the admission is replayed for a new grant. */
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("checksum") val checksum: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("protocol") val protocol: String = PROTOCOL_TUS,
    @SerialName("method") val method: String = METHOD_POST,
) {
    public companion object {
        public const val PROTOCOL_TUS: String = "tus"
        public const val METHOD_POST: String = "POST"

        /** The suffix the document promises on [endpoint]. */
        public const val SIGNED_ENDPOINT_SUFFIX: String = "/upload/resumable/sign"
    }
}

/** The durable import record: the backend's own lifecycle state for one admission. */
@Serializable
public data class PublicationImport(
    @SerialName("id") val id: String,
    @SerialName("client_import_id") val clientImportId: String,
    @SerialName("source_format") val sourceFormat: PublicationFormat = PublicationFormat.UNKNOWN,
    @SerialName("source_mime_type") val sourceMimeType: String,
    @SerialName("expected_sha256") val expectedSha256: String,
    @SerialName("expected_size_bytes") val expectedSizeBytes: Long,
    @SerialName("status") val status: PublicationImportStatus = PublicationImportStatus.UNKNOWN,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("original_file_name") val originalFileName: String? = null,
    @SerialName("publication_id") val publicationId: String? = null,
    @SerialName("promotion") val promotion: PublicationPromotion? = null,
    @SerialName("failure") val failure: PublicationImportFailure? = null,
    @SerialName("observed_sha256") val observedSha256: String? = null,
    @SerialName("observed_size_bytes") val observedSizeBytes: Long? = null,
    /** The canonical account-side book id (AD-23's pair), present once the import is `ready`. */
    @SerialName("canonical_book_id") val canonicalBookId: String? = null,
    @SerialName("canonical_asset_id") val canonicalAssetId: String? = null,
    /** `sha256:`-prefixed content checksum of the canonical asset. */
    @SerialName("canonical_asset_checksum") val canonicalAssetChecksum: String? = null,
    @SerialName("canonical_asset_size_bytes") val canonicalAssetSizeBytes: Long? = null,
    @SerialName("canonical_asset_mime_type") val canonicalAssetMimeType: String? = null,
    @SerialName("ready_at") val readyAt: String? = null,
)

/** The promotion half of a record: device-only source, account-library destination. */
@Serializable
public data class PublicationPromotion(
    @SerialName(
        "account_admission",
    ) val accountAdmission: PublicationPromotionAdmission = PublicationPromotionAdmission.UNKNOWN,
    @SerialName(
        "activity_continuity",
    ) val activityContinuity: PublicationActivityContinuity = PublicationActivityContinuity.UNKNOWN,
    /** Only ever present once `account_admission` is `admitted`. */
    @SerialName("account_book_id") val accountBookId: String? = null,
    @SerialName("source") val source: String = SOURCE_DEVICE_ONLY,
    @SerialName("destination") val destination: String = DESTINATION_ACCOUNT_LIBRARY,
    @SerialName("local_state_disposition") val localStateDisposition: String = LOCAL_STATE_RETAIN,
    @SerialName("local_state_may_be_discarded") val localStateMayBeDiscarded: Boolean = false,
) {
    public companion object {
        public const val SOURCE_DEVICE_ONLY: String = "device_only"
        public const val DESTINATION_ACCOUNT_LIBRARY: String = "account_library"
        public const val LOCAL_STATE_RETAIN: String = "retain"
    }
}

/**
 * Why an import ended badly, and — just as load-bearing for REQ-507 — what it
 * did *not* do: [brokenAccountEntryCreated] is `false` and
 * [localStateDisposition] is `retained` in the contract, which is the backend
 * promising that the device book is untouched.
 */
@Serializable
public data class PublicationImportFailure(
    @SerialName("category") val category: PublicationFailureCategory = PublicationFailureCategory.UNKNOWN,
    @SerialName("retryable") val retryable: Boolean,
    @SerialName("broken_account_entry_created") val brokenAccountEntryCreated: Boolean = false,
    @SerialName("local_state_disposition") val localStateDisposition: String = LOCAL_STATE_RETAINED,
    @SerialName("external_source_disposition") val externalSourceDisposition: String = EXTERNAL_SOURCE_UNTOUCHED,
) {
    public companion object {
        public const val LOCAL_STATE_RETAINED: String = "retained"
        public const val EXTERNAL_SOURCE_UNTOUCHED: String = "untouched"
    }
}

/**
 * The admission answer. [created] is `false` when the backend replayed an
 * existing admission for the same `client_import_id` — which is exactly what a
 * re-admission after a grant expiry gets, and why a retry is never a second
 * import (AD-26).
 */
@Serializable
public data class PublicationImportAdmissionResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("import") val importRecord: PublicationImport,
    @SerialName("created") val created: Boolean,
    /** Absent once the upload has been completed; a fresh one needs a fresh admission. */
    @SerialName("transfer_grant") val transferGrant: PublicationTransferGrant? = null,
)

/** The answer to a status read, a completion and a cancellation alike. */
@Serializable
public data class PublicationImportResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("import") val importRecord: PublicationImport,
)
