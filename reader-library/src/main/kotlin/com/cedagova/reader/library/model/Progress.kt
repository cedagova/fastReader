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
 * to and from FastReader's own position is the host's work (LEAF821's
 * `PortableReadingPosition`), not the contract module's.
 *
 * Note [bookId]: a position record's identity in this contract is the **book**,
 * and the document says so twice — this required `book_id`, and the only routes
 * that address a single position, `PUT` and `DELETE /v1/reader/progress/{book_id}`.
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

/**
 * What a client states about a reading position (`PutReaderProgressRequest`).
 *
 * This is the shape of the position a client *publishes*, and it is the only
 * such shape the pinned document declares. FastReader publishes through
 * `POST /v1/reader/sync/mutations` rather than the `PUT` route — the queue,
 * the persisted idempotency key and canonical adoption of AD-22 only exist on
 * the mutations path — and the envelope's `payload` is typed as a free-form
 * object, so the document does not state per-resource payload shapes. This
 * model is therefore a **derivation**: the payload of a `reading_progress`
 * upsert is the body the document declares for stating a position. Nothing has
 * yet observed what the backend stores for a progress payload delivered that
 * way; see the host's `PortableReadingPosition` for the single seam that
 * assumption is isolated behind.
 *
 * Three fields, and deliberately no fourth. A token index, a words-per-minute
 * value, a pipeline version and a structural fingerprint are FastReader's own
 * and have no field here to travel in (REQ-512).
 */
@Serializable
data class PutReaderProgressRequest(
    /** 0-100 inclusive. FastReader sends the whole percent the reader is shown. */
    @SerialName("progress_percent") val progressPercent: Double,
    @SerialName("locator") val locator: JsonObject = EMPTY_JSON_OBJECT,
    @SerialName("chapter_title") val chapterTitle: String? = null,
)

/**
 * The portable EPUB locator (`ReaderEpubLocatorV1`), the whole of what a
 * position says portably: which section, and how far through the book.
 *
 * [href] is the section — for FastReader, the spine path of the chapter the
 * reader is in. [progression] is the book-level fraction in `0.0..1.0`. Both are
 * nullable in the document and either may be absent from a record another client
 * wrote, which is why consuming one falls back rather than failing.
 *
 * [epubCfi] is modelled because the document declares it and another client may
 * send one; FastReader never writes it. An RSVP stream has no DOM to point a CFI
 * into, and a fabricated one would be a precision this app does not have (A2).
 */
@Serializable
data class ReaderEpubLocatorV1(
    @SerialName("format") val format: String = LOCATOR_FORMAT_EPUB,
    @SerialName("href") val href: String? = null,
    @SerialName("progression") val progression: Double? = null,
    @SerialName("epub_cfi") val epubCfi: String? = null,
    @SerialName("contract_version") val contractVersion: String = PORTABLE_SEMANTICS_VERSION,
)

/** The `reader.portable-semantics.v1` constant every portable locator carries. */
const val PORTABLE_SEMANTICS_VERSION: String = "reader.portable-semantics.v1"

/** The locator format discriminator for an EPUB. The only one FastReader writes. */
const val LOCATOR_FORMAT_EPUB: String = "epub"
