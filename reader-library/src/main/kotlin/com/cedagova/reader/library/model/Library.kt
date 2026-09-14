package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /v1/reader/library` — the account's library as the server holds it.
 *
 * Every field below is checked against the pinned document's
 * `ReaderLibraryResponse` schema by `ReaderLibraryContractTest`.
 */
@Serializable
data class ReaderLibraryResponse(
    /** Echoed `X-Request-ID`; the id to quote when reading a server log. */
    @SerialName("request_id") val requestId: String,
    @SerialName("items") val items: List<ReaderLibraryItem> = emptyList(),
    @SerialName("contract_version") val contractVersion: String = CONTRACT_VERSION,
) {
    companion object {
        /** The document's constant for the reader payload family. */
        const val CONTRACT_VERSION: String = "reader.v1"
    }
}

/** One row of the account library: the book, its files, and the account's state for it. */
@Serializable
data class ReaderLibraryItem(
    @SerialName("book") val book: ReaderBook,
    @SerialName("status") val status: ReaderLibraryStatus = ReaderLibraryStatus.UNKNOWN,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("assets") val assets: List<ReaderBookAsset> = emptyList(),
    @SerialName("cover_status") val coverStatus: ReaderCoverStatus = ReaderCoverStatus.PENDING,
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
)

/** The book itself: the canonical id the sync protocol keys on, and its metadata. */
@Serializable
data class ReaderBook(
    /** The canonical account-side UUID. Content identity is the asset checksum, not this. */
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("source_type") val sourceType: ReaderBookSourceType = ReaderBookSourceType.UNKNOWN,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("author") val author: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("metadata") val metadata: JsonObject = EMPTY_JSON_OBJECT,
)

/**
 * One file of a book. [checksum] is the content SHA-256 the shelf merges on
 * (AD-23): a device book and an account book are the same row when these agree.
 */
@Serializable
data class ReaderBookAsset(
    @SerialName("asset_id") val assetId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("kind") val kind: ReaderBookAssetKind = ReaderBookAssetKind.UNKNOWN,
    @SerialName("upload_status") val uploadStatus: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** The content SHA-256, when the server has computed one. */
    @SerialName("checksum") val checksum: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("original_file_name") val originalFileName: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("metadata") val metadata: JsonObject = EMPTY_JSON_OBJECT,
)

/** The empty document every optional free-form object defaults to. */
val EMPTY_JSON_OBJECT: JsonObject = JsonObject(emptyMap())
