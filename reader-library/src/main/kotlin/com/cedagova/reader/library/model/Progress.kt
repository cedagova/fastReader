package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /v1/reader/progress` — every reading position the account holds. */
@Serializable
data class ReaderProgressListResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("progress") val progress: List<ReaderProgress> = emptyList(),
    @SerialName("contract_version") val contractVersion: String = ReaderLibraryResponse.CONTRACT_VERSION,
)

/**
 * One book's position. [locator] is the portable locator document the reader
 * publishes and consumes; this module carries it through untouched — mapping it
 * to and from FastReader's own position is LEAF821's work, not the contract
 * module's.
 */
@Serializable
data class ReaderProgress(
    @SerialName("book_id") val bookId: String,
    /** 0-100 inclusive, as the document declares it. */
    @SerialName("progress_percent") val progressPercent: Double,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("locator") val locator: JsonObject = EMPTY_JSON_OBJECT,
    @SerialName("chapter_title") val chapterTitle: String? = null,
)
