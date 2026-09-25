package com.cedagova.reader.library.sync

import com.cedagova.reader.library.model.LOCATOR_FORMAT_EPUB
import com.cedagova.reader.library.model.PORTABLE_SEMANTICS_VERSION
import com.cedagova.reader.library.model.PutReaderProgressRequest
import com.cedagova.reader.library.model.ReaderEpubLocatorV1
import com.cedagova.reader.library.model.ReaderPortableLocationV1
import com.cedagova.reader.library.model.ReaderPortablePublicationV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The client-generic half of the portable reading position (AD-25, REQ-511,
 * REQ-512): what a published `reading_progress` payload is, which book a
 * received record is about, and what a canonical payload states.
 *
 * It knows nothing of how a host counts its own place in a book. A host maps
 * its own position to a [LocalReadingPosition] and a [RemoteReadingPosition]
 * back to its own position; FastReader's token mapping is its
 * `PortableReadingPosition`, not this.
 *
 * ## Two derivations live here
 *
 * 1. **Which book a progress record is about.** Verified against stage (#109,
 *    `docs/evidence/109/resource-id-verification.md`): a `reading_progress`
 *    record's `resource_id` is the book id. [recordFor] still prefers the
 *    payload's own `book_id` and surfaces an unplaceable record as a value, so a
 *    backend change costs a visible outcome rather than a wrong book.
 * 2. **What a published payload looks like.** reader-api validates a
 *    `reading_progress` upsert's payload against `PutReaderProgressRequest` and
 *    rejects anything else as `invalid_payload`. [payloadFor] serializes exactly
 *    that body.
 *
 * ## What never leaves
 *
 * [payloadFor] builds its object from [PutReaderProgressRequest] and nothing
 * else, so the set of keys that can appear in an outbound position is closed by
 * a type rather than by review. A host's own reading unit (a token index, a
 * page), its reading speed or any content digest are not fields of it.
 *
 * ## What this file never does
 *
 * It never compares two positions to pick a winner. `reader.activity-
 * convergence.v1` says the causal successor wins, later admission wins, and
 * percentage never selects — so a backward move is published exactly like a
 * forward one, and a remote position is consumed exactly as it arrived.
 */
public object PortableProgress {

    /**
     * How an outbound position is encoded.
     *
     * `explicitNulls = false` is the load-bearing one and the reason this is
     * stated here rather than left to a default: an absent href, chapter title
     * or CFI is **omitted** from the payload instead of travelling as an
     * explicit `null`. So a position that has no section to name sends three
     * keys, not six, and `the exact key set of every published position` can
     * assert a closed set rather than a set-minus-the-nulls. It matches
     * `:reader-library`'s own wire configuration, which is internal to that
     * module.
     */
    private val wire: Json = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * The payload of a `reading_progress` upsert for [position] in the account
     * book [bookId].
     *
     * Built by serializing [PutReaderProgressRequest], so the key set is the
     * model's and a field cannot be added here by accident. [bookId] must be the
     * envelope's `resource_id`: reader-api rejects a location whose
     * `publication_id` differs from it.
     */
    public fun payloadFor(bookId: String, position: LocalReadingPosition): JsonObject {
        val body = PutReaderProgressRequest(
            progressPercent = position.percent.toDouble(),
            location = ReaderPortableLocationV1(
                contractVersion = PORTABLE_SEMANTICS_VERSION,
                publication = ReaderPortablePublicationV1.accountEpub(bookId),
                locator = locatorFor(position),
            ),
            chapterTitle = position.chapterTitle,
        )
        return wire.encodeToJsonElement(body).jsonObject
    }

    /** The portable locator object [position] publishes. */
    public fun locatorFor(position: LocalReadingPosition): JsonObject {
        val locator = ReaderEpubLocatorV1(
            format = LOCATOR_FORMAT_EPUB,
            href = position.href,
            progression = position.progression,
            // Never written: an RSVP stream has no DOM to point a CFI into, and a
            // fabricated one would claim a precision this app does not have (A2).
            epubCfi = null,
            contractVersion = PORTABLE_SEMANTICS_VERSION,
        )
        return wire.encodeToJsonElement(locator).jsonObject
    }

    /**
     * What a `reading_progress` record is about, as a value rather than a guess.
     *
     * This is derivation 1 of the three named at the top of this file, and the
     * only place in the app that answers it. The order is deliberate:
     *
     * - the payload's own `book_id` first, because the document marks that field
     *   required on `ReaderProgress` and it is the backend's own answer;
     * - the `resource_id` second, which is what the identity is derived to be;
     * - and [ProgressRecord.Unrecognized] third, carrying both values and the
     *   reason, so a record this app cannot place shows up as a typed outcome a
     *   host can surface and a test can assert. It is never dropped silently.
     *
     * [knownBook] is asked rather than assumed so that "the resource id is not a
     * book id after all" is *detected* on stage instead of producing a position
     * filed under a book that does not exist.
     */
    internal fun recordFor(resourceId: String, payload: JsonObject, knownBook: (String) -> Boolean): ProgressRecord {
        val fromPayload = payload.string("book_id")
        if (fromPayload != null && knownBook(fromPayload)) {
            return ProgressRecord.Recognized(fromPayload, positionOf(payload))
        }
        if (knownBook(resourceId)) {
            return ProgressRecord.Recognized(resourceId, positionOf(payload))
        }
        return ProgressRecord.Unrecognized(
            resourceId = resourceId,
            payloadBookId = fromPayload,
            reason = if (fromPayload == null) {
                "the record names no book_id and its resource id is not a book this account holds"
            } else {
                "neither the record's book_id nor its resource id is a book this account holds"
            },
        )
    }

    /**
     * The remote position a canonical payload states.
     *
     * Every field is read and none is computed. The locator is the one inside
     * the payload's `location` (`ReaderPortableLocationV1`, since the #139
     * re-pin). A payload that carries no location, or one whose locator is a
     * shape this app does not recognise, yields a position with a null href —
     * which [tokenIndexFor] answers with the fraction alone. That is the
     * documented fallback, not an error: a malformed locator must not cost the
     * reader the percentage that came with it.
     */
    public fun positionOf(payload: JsonObject): RemoteReadingPosition {
        val locator = (payload["location"] as? JsonObject)?.get("locator") as? JsonObject
        val format = locator?.string("format")
        // A pdf locator, or a format this build does not know, states nothing
        // about an EPUB's spine. Its progression is still a fraction of the
        // publication, so it is kept and only the href is dropped.
        val href = if (format == null || format == LOCATOR_FORMAT_EPUB) locator?.string("href") else null
        return RemoteReadingPosition(
            href = href,
            chapterTitle = payload.string("chapter_title"),
            progression = locator?.double("progression")?.coerceIn(0.0, 1.0),
            percent = payload.double("progress_percent"),
            updatedAt = payload.string("updated_at"),
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * The position this device is at, in the only terms it can state portably.
 *
 * [percent] is the whole percent the reader is shown; [progression] is the
 * precise fraction. Both describe the same place — the percent is what a person
 * reads on another client, the fraction is what a resume maps back through.
 */
public data class LocalReadingPosition(
    /** The spine path of the chapter the reader is in, or null for a book with no chapters. */
    val href: String?,
    val chapterTitle: String?,
    /** `0.0..1.0`, the book-level token fraction. */
    val progression: Double,
    /** `0..100`, equal to the percent the reader is shown. */
    val percent: Int,
) {
    /**
     * What AD-25 compares to decide whether a flush is worth publishing: the
     * section, and the *rounded* percent. Deliberately not the fraction — at
     * 1000 WPM the fraction changes sixteen times a second and the percent does
     * not, and this is the whole of why reading a long chapter does not become a
     * stream of mutations.
     *
     * It is a publish-or-not test, never a winner test: both values come from
     * this device's own last publish, and nothing remote is compared.
     */
    val publishKey: Pair<String?, Int> get() = href to percent
}

/**
 * A position another client left, as the backend described it.
 *
 * Every field is the server's. [updatedAt] and the revision that accompanies the
 * record are the backend's own ordering, kept so a host never has to invent one:
 * `reader.activity-convergence.v1` decides who wins, and this app's job is to
 * carry the answer rather than to reach it.
 */
public data class RemoteReadingPosition(
    /** The section the other client named, when it named one this parse can use. */
    val href: String?,
    val chapterTitle: String?,
    /** `0.0..1.0` when the record carried one. */
    val progression: Double?,
    /** `0..100` as the record stated it. */
    val percent: Double?,
    /** The server's own time for the record. Never compared to a local clock. */
    val updatedAt: String?,
)

/**
 * What a `reading_progress` record turned out to be about.
 *
 * [Unrecognized] exists so that a wrong derivation about `resource_id` is
 * *visible*. The alternative — dropping the record — is how an assumption stays
 * unfalsified for as long as nobody happens to look.
 */
internal sealed interface ProgressRecord {

    /** The record is about a book this account holds, and states this position. */
    data class Recognized(val bookId: String, val position: RemoteReadingPosition) : ProgressRecord

    /** The record cannot be placed. Surfaced, never dropped quietly. */
    data class Unrecognized(val resourceId: String, val payloadBookId: String?, val reason: String) : ProgressRecord
}
