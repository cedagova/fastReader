package com.cedagova.reader.library.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /v1/reader/progress` — every reading position the account holds. */
@Serializable
public data class ReaderProgressListResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("progress") val progress: List<ReaderProgress> = emptyList(),
    @SerialName("contract_version") val contractVersion: String = ReaderLibraryResponse.CONTRACT_VERSION,
)

/**
 * One book's position. [location] is the portable location the reader publishes
 * and consumes — the publication it belongs to plus the portable locator inside
 * it; this module carries the locator through untouched — mapping it to and
 * from FastReader's own position is the host's work (`PortableReadingPosition`),
 * not the contract module's.
 *
 * Note [bookId]: a position record's identity in this contract is the **book**,
 * and the document says so twice — this required `book_id`, and the only routes
 * that address a single position, `PUT` and `DELETE /v1/reader/progress/{book_id}`.
 *
 * [location] is required with no default, as the pinned document marks it: the
 * server validates every row it lists against the same model before answering
 * (reader-api `ReaderProductsRepository._progress`), so a row without one is a
 * server fault to be seen, not a shape to paper over.
 */
@Serializable
public data class ReaderProgress(
    @SerialName("book_id") val bookId: String,
    /** 0-100 inclusive, as the document declares it. */
    @SerialName("progress_percent") val progressPercent: Double,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("location") val location: ReaderPortableLocationV1,
    @SerialName("chapter_title") val chapterTitle: String? = null,
)

/**
 * What a client states about a reading position (`PutReaderProgressRequest`).
 *
 * This is the shape of the position a client *publishes*. FastReader publishes
 * through `POST /v1/reader/sync/mutations` rather than the `PUT` route — the
 * queue, the persisted idempotency key and canonical adoption of AD-22 only
 * exist on the mutations path. The envelope's `payload` is typed as a free-form
 * object in the document, but reader-api validates a `reading_progress` upsert's
 * payload against exactly this model (`reader_sync.service._UPSERT_MODELS`) and
 * rejects anything else as `invalid_payload` — so this is the payload's shape,
 * not a derivation of it (#139).
 *
 * Three fields, and deliberately no fourth. A token index, a words-per-minute
 * value, a pipeline version and a structural fingerprint are FastReader's own
 * and have no field here to travel in (REQ-512). [location]'s publication names
 * the account book the envelope already addresses — reader-api refuses one whose
 * `publication_id` differs from the envelope's `resource_id` — and constants.
 */
@Serializable
internal data class PutReaderProgressRequest(
    /** 0-100 inclusive. FastReader sends the whole percent the reader is shown. */
    @SerialName("progress_percent") val progressPercent: Double,
    @SerialName("location") val location: ReaderPortableLocationV1,
    @SerialName("chapter_title") val chapterTitle: String? = null,
)

/**
 * `ReaderPortableLocationV1`: a portable place plus the publication it is in.
 *
 * [locator] is the document's `oneOf` of `ReaderEpubLocatorV1` and
 * `ReaderPdfLocatorV1`, discriminated by its `format`. It is carried as an object
 * because a location *read* may be either — another client can leave a PDF
 * place — while the one FastReader *writes* is always built from
 * [ReaderEpubLocatorV1] (see the host's `PortableReadingPosition.locatorFor`), so
 * the outbound key set stays closed by a type.
 *
 * reader-api requires [locator]'s `format` to equal [publication]'s.
 */
@Serializable
public data class ReaderPortableLocationV1(
    @SerialName("contract_version") val contractVersion: String = PORTABLE_SEMANTICS_VERSION,
    @SerialName("publication") val publication: ReaderPortablePublicationV1,
    @SerialName("locator") val locator: JsonObject,
)

/**
 * `ReaderPortablePublicationV1`: which publication a portable place is in.
 *
 * FastReader states an account book here and nothing else: [publicationId] is
 * the account's book id (which reader-api requires to equal the envelope's
 * `resource_id`), [format] and [mediaType] are the EPUB constants, and [source]
 * is `account`. `content_identity` is optional in the document and deliberately
 * not modelled: FastReader never sends a digest of a book in a position.
 *
 * [format], [mediaType] and [source] are strings rather than enums so that a
 * location read from another client's PDF record still decodes; the values this
 * module *sends* are the constants below, which `ReaderLibraryContractTest`
 * checks against the document's own enums.
 */
@Serializable
public data class ReaderPortablePublicationV1(
    @SerialName("publication_id") val publicationId: String,
    @SerialName("format") val format: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("source") val source: String,
) {
    public companion object {
        /** An EPUB the account holds, identified by the account's book id. */
        public fun accountEpub(bookId: String): ReaderPortablePublicationV1 = ReaderPortablePublicationV1(
            publicationId = bookId,
            format = LOCATOR_FORMAT_EPUB,
            mediaType = MEDIA_TYPE_EPUB,
            source = PUBLICATION_SOURCE_ACCOUNT,
        )
    }
}

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
internal data class ReaderEpubLocatorV1(
    @SerialName("format") val format: String = LOCATOR_FORMAT_EPUB,
    @SerialName("href") val href: String? = null,
    @SerialName("progression") val progression: Double? = null,
    @SerialName("epub_cfi") val epubCfi: String? = null,
    @SerialName("contract_version") val contractVersion: String = PORTABLE_SEMANTICS_VERSION,
)

/** The `reader.portable-semantics.v1` constant every portable locator carries. */
public const val PORTABLE_SEMANTICS_VERSION: String = "reader.portable-semantics.v1"

/** The locator format discriminator for an EPUB. The only one FastReader writes. */
public const val LOCATOR_FORMAT_EPUB: String = "epub"

/** `ReaderPortablePublicationV1.media_type` for an EPUB; the document requires it to agree with `format`. */
public const val MEDIA_TYPE_EPUB: String = "application/epub+zip"

/** `ReaderPortablePublicationV1.source` for a publication the account holds. */
public const val PUBLICATION_SOURCE_ACCOUNT: String = "account"
