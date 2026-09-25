package com.cedagova.reader.library.imports

import com.cedagova.reader.library.model.CreatePublicationImportRequest
import com.cedagova.reader.library.model.PublicationFailureCategory
import com.cedagova.reader.library.model.PublicationFormat
import com.cedagova.reader.library.model.PublicationImport
import com.cedagova.reader.library.model.PublicationImportAdmissionResponse
import com.cedagova.reader.library.model.PublicationImportStatus
import com.cedagova.reader.library.model.PublicationTransferGrant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the account store keeps about one import, so that an interrupted or
 * restarted add addresses the same admission instead of making a second one
 * (AD-26).
 *
 * This is **not** a wire shape — reader-api never sees it — which is why it
 * lives here and not in `model/`. It is the durable half of the lifecycle, and
 * LEAF802 (#117) owns persisting it beside the account library. Every field is
 * here because losing it costs something concrete:
 *
 * - [clientImportId] is derived, not random ([derive]): after app death the
 *   same file in the same account produces the same id, the backend replays the
 *   existing admission with `created = false`, and the owner gets one import
 *   rather than two.
 * - [importId] addresses the record for status, complete and cancel.
 * - [transferLocation] is the resumable upload the provider created. Without it
 *   a resume can only start from zero.
 * - [grantExpiresAt] is the moment that location's signature stops working; a
 *   record past it goes straight to a fresh admission instead of spending a
 *   round trip learning the same thing.
 * - [uploadedOffset] is the last offset the *provider* acknowledged, never a
 *   local count of bytes written. It is a lower bound, not the whole truth: a
 *   connection lost mid-`PATCH` can leave the provider holding part of that
 *   chunk with the client never told, so a resume always re-reads the
 *   provider's own offset with `HEAD` rather than trusting this. Keeping it a
 *   lower bound is the point — over-counting would skip bytes that were never
 *   stored, and a book missing a chunk fails verification at the backend.
 * - [status], [failureCategory] and [canonicalBookId] are the backend's answer,
 *   carried forward so a host can render a terminal import it has not re-read
 *   yet, and so `ready` can bind the device book to its account row (AD-23).
 *
 * The one thing it deliberately does not hold is the grant's headers. Those are
 * the provider's signed credential; they live for the length of one transfer
 * and a resume gets fresh ones by replaying the idempotent admission.
 */
@Serializable
public data class PublicationImportRecord(
    /** Stable across app death, derived from content identity and account. */
    @SerialName("client_import_id") val clientImportId: String,
    /** The account this import belongs to; the store is per-account, this makes [derive] checkable. */
    @SerialName("account_id") val accountId: String,
    /** The source's whole-file SHA-256, bare lowercase hex. The shelf's identity (AD-23). */
    @SerialName("content_sha256") val contentSha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("source_format") val sourceFormat: PublicationFormat,
    @SerialName("source_mime_type") val sourceMimeType: String,
    @SerialName("original_file_name") val originalFileName: String? = null,
    @SerialName("import_id") val importId: String? = null,
    @SerialName("status") val status: PublicationImportStatus = PublicationImportStatus.PENDING_UPLOAD,
    @SerialName("transfer_location") val transferLocation: String? = null,
    @SerialName("grant_expires_at") val grantExpiresAt: String? = null,
    @SerialName("uploaded_offset") val uploadedOffset: Long = 0,
    @SerialName("failure_category") val failureCategory: PublicationFailureCategory? = null,
    @SerialName("failure_retryable") val failureRetryable: Boolean? = null,
    @SerialName("canonical_book_id") val canonicalBookId: String? = null,
    @SerialName("canonical_asset_checksum") val canonicalAssetChecksum: String? = null,
) {

    /** True once the backend will not move this import again on its own. */
    val isTerminal: Boolean get() = status.isTerminal

    /** The fraction of the source the provider has confirmed, 0.0..1.0. */
    val transferredFraction: Float
        get() = if (sizeBytes <= 0) 0f else (uploadedOffset.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)

    /**
     * The admission body for this record.
     *
     * [consent] is a required parameter with no default and its only value is
     * `GRANTED`, so `upload_consent = true` cannot be reached by forgetting
     * something — see [UploadConsent].
     */
    public fun admissionRequest(consent: UploadConsent): CreatePublicationImportRequest =
        CreatePublicationImportRequest(
            clientImportId = clientImportId,
            sourceFormat = sourceFormat,
            sourceMimeType = sourceMimeType,
            sizeBytes = sizeBytes,
            sha256 = contentSha256,
            uploadConsent = consent == UploadConsent.GRANTED,
            originalFileName = originalFileName,
        )

    /** This record with the backend's own answer folded in. */
    public fun withImport(admitted: PublicationImport): PublicationImportRecord = copy(
        importId = admitted.id,
        status = admitted.status,
        failureCategory = admitted.failure?.category,
        failureRetryable = admitted.failure?.retryable,
        canonicalBookId = admitted.canonicalBookId ?: admitted.promotion?.accountBookId ?: canonicalBookId,
        canonicalAssetChecksum = admitted.canonicalAssetChecksum ?: canonicalAssetChecksum,
    )

    /** This record with the admission answer folded in (the record, and the grant's expiry). */
    public fun withAdmission(admission: PublicationImportAdmissionResponse): PublicationImportRecord =
        withImport(admission.importRecord).let { updated ->
            admission.transferGrant?.let { updated.copy(grantExpiresAt = it.expiresAt) } ?: updated
        }

    /** A transfer that has just been created at [location]; nothing confirmed yet. */
    public fun transferringAt(location: String, grant: PublicationTransferGrant): PublicationImportRecord =
        copy(transferLocation = location, grantExpiresAt = grant.expiresAt, uploadedOffset = 0)

    /** The provider confirmed [offset]. Never moves backwards. */
    public fun confirmedOffset(offset: Long): PublicationImportRecord =
        if (offset <= uploadedOffset) this else copy(uploadedOffset = offset)

    /** The resumable upload is gone or its signature is spent; the next attempt creates a new one. */
    public fun withoutTransfer(): PublicationImportRecord =
        copy(transferLocation = null, grantExpiresAt = null, uploadedOffset = 0)

    public companion object {
        /**
         * The `client_import_id` prefix. It names the protocol and its version,
         * never the app: `:reader-library` is liftable (AD-19), and an id that
         * named the host app would make every account row carry the name of one
         * client forever.
         */
        public const val CLIENT_IMPORT_ID_PREFIX: String = "reader-import-v1-"

        /** The separator between the two halves of the derived identity. */
        private const val IDENTITY_SEPARATOR: String = "/"

        /**
         * The stable id for one file in one account.
         *
         * A hash, not a concatenation, for two reasons: the account id never
         * goes on the wire in clear, and the result is a fixed 81 characters,
         * comfortably inside the contract's 255. The same file added to a
         * different account is a different import, which is what makes the
         * backend's idempotency per-account rather than global.
         *
         * Both halves are fixed-shape — an account id and 64 hex characters —
         * so the separator cannot be used to make two different pairs hash the
         * same way.
         */
        public fun derive(accountId: String, contentSha256: String): String {
            require(accountId.isNotBlank()) { "an import id is derived from a real account" }
            val normalized = normalizeSha256(contentSha256)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$accountId$IDENTITY_SEPARATOR$normalized".toByteArray(StandardCharsets.UTF_8))
            return CLIENT_IMPORT_ID_PREFIX + digest.joinToString("") { "%02x".format(it) }
        }

        /** A fresh record for [source] in [accountId], before anything has been sent. */
        public fun forSource(
            accountId: String,
            source: PublicationSource,
            sourceFormat: PublicationFormat,
        ): PublicationImportRecord {
            val sha256 = normalizeSha256(source.sha256)
            return PublicationImportRecord(
                clientImportId = derive(accountId, sha256),
                accountId = accountId,
                contentSha256 = sha256,
                sizeBytes = source.sizeBytes,
                sourceFormat = sourceFormat,
                sourceMimeType = source.mimeType,
                originalFileName = source.fileName,
            )
        }

        /** Bare lowercase hex, whichever of the contract's two spellings came in. */
        public fun normalizeSha256(value: String): String {
            val bare = value.removePrefix("sha256:").lowercase()
            require(HEX_64.matches(bare)) { "a content sha256 is 64 hex characters" }
            return bare
        }

        private val HEX_64 = Regex("^[0-9a-f]{64}$")
    }
}
