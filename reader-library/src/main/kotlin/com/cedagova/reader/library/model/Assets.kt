package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The asset download grant as reader-api publishes it (#118, LEAF811 of #104):
 * the one way a book's bytes may come *onto* this device.
 *
 * It is the mirror image of `PublicationTransferGrant` in `Imports.kt` and is
 * modelled the same way — one Kotlin type per schema, every wire name spelled
 * out, pinned to the document by `ReaderLibraryContractTest`. Read
 * `contracts/reader-api.openapi.json` (`ReaderAssetGrantResponse`,
 * `ReaderAssetGrant`) for the authority; this file only says what shape
 * FastReader reads it in.
 *
 * The two grants differ in one way that matters to this module's design: the
 * upload grant carries a `chunk_size_bytes` because TUS sends the file in
 * pieces, and this one does not, because a download is one `GET` the server
 * streams. Everything else a caller might be tempted to hard-code —
 * [ReaderAssetGrant.url], [ReaderAssetGrant.headers],
 * [ReaderAssetGrant.sizeBytes], [ReaderAssetGrant.checksum] and the TTL in
 * [ReaderAssetGrant.expiresAt] — is read from the grant on every attempt.
 */

/**
 * `POST /v1/reader/assets/{asset_id}/download-grant` — a short-lived direct
 * download for one asset the signed-in actor may read.
 */
@Serializable
data class ReaderAssetGrantResponse(
    /** Echoed `X-Request-ID`; the id to quote when reading a server log. */
    @SerialName("request_id") val requestId: String,
    @SerialName("grant") val grant: ReaderAssetGrant,
    @SerialName("contract_version") val contractVersion: String = ReaderLibraryResponse.CONTRACT_VERSION,
)

/**
 * One signed transfer of one asset, in one direction, until it expires.
 *
 * [url] is the storage provider's own signed URL. It is the *only* address this
 * client may fetch a book from: nothing in FastReader composes a download URL,
 * which is why `AssetDownloadClient` takes a whole grant rather than a string.
 *
 * [checksum] is the content SHA-256 the server holds for the stored object. It
 * is the same identity the shelf merges rows on (AD-23) and the value a placed
 * copy must match before it is readable (REQ-510) — the copy store compares
 * against the *account book's* identity, and this field is how a grant that
 * points at some other object is caught before a byte is written.
 *
 * [headers] is the provider's signature, and the only credential a download may
 * carry. The account bearer is not in it and cannot be: see `AssetDownloadClient`.
 */
@Serializable
data class ReaderAssetGrant(
    @SerialName("asset_id") val assetId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("direction") val direction: ReaderAssetDirection = ReaderAssetDirection.UNKNOWN,
    @SerialName("method") val method: ReaderAssetMethod = ReaderAssetMethod.UNKNOWN,
    /** The provider's signed URL. Never composed, never rewritten, never cached. */
    @SerialName("url") val url: String,
    /** ISO-8601; after this the signature is rejected and a fresh grant is asked for. */
    @SerialName("expires_at") val expiresAt: String,
    /** The stored object's content SHA-256, with or without a `sha256:` prefix. */
    @SerialName("checksum") val checksum: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("upload_status") val uploadStatus: String,
    /** The provider's signature headers; the only credential a transfer may send. */
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
) {

    /** [checksum] without the optional `sha256:` prefix, lowercased. */
    val checksumHex: String get() = checksum.removePrefix(SHA256_PREFIX).lowercase()

    /** True when this grant is what a download needs: a readable `GET`. */
    val isDownload: Boolean
        get() = direction == ReaderAssetDirection.DOWNLOAD && method == ReaderAssetMethod.GET

    companion object {
        /** The prefix the contract's own `sha256` pattern makes optional. */
        const val SHA256_PREFIX: String = "sha256:"
    }
}
