package com.cedagova.fastreader.account.library

import com.cedagova.fastreader.content.BookContent
import com.cedagova.reader.library.model.LOCATOR_FORMAT_EPUB
import com.cedagova.reader.library.model.PORTABLE_SEMANTICS_VERSION
import com.cedagova.reader.library.model.PutReaderProgressRequest
import com.cedagova.reader.library.model.ReaderEpubLocatorV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * The one place FastReader's position and the account's portable position meet
 * (AD-25, REQ-511).
 *
 * ## Why this file exists at all
 *
 * Everything about the portable position that could be *wrong about the backend*
 * is in here, on purpose. Three separate derivations live in this file, none of
 * them yet observed against stage:
 *
 * 1. **Which book a progress record is about.** The pinned document types a sync
 *    change's `resource_id` as a bare string and fixes no meaning per resource
 *    type. That a `reading_progress` record's identity is the **book** is
 *    derived — from `ReaderProgress.book_id`, which the document marks required,
 *    and from the only two routes that address a single position,
 *    `PUT`/`DELETE /v1/reader/progress/{book_id}`. reader-web addresses it the
 *    same way (`putReaderProductProgress` composes
 *    `/v1/reader/progress/${bookId}`). It is still a derivation: nobody has read
 *    a real record off stage. [recordFor] is the whole of it, and it prefers the
 *    payload's own `book_id` over the resource id precisely so a resource id
 *    that turns out to be something else — a composite key, an opaque row id —
 *    costs a fallback rather than a wrong book.
 * 2. **What a published payload looks like.** The mutations envelope's `payload`
 *    is a free-form object, so the document states no payload shape for a
 *    `reading_progress` upsert. [payloadFor] uses the body the document *does*
 *    declare for stating a position, `PutReaderProgressRequest`.
 * 3. **How a section maps to a word.** Section plus fraction is honest for an
 *    RSVP stream and a paginated reader alike, and exact word equivalence is
 *    promised by neither (assumption A2).
 *
 * If any of the three is wrong, the fix is this file. That is the point of it.
 *
 * ## What never leaves
 *
 * [payloadFor] builds its object from [PutReaderProgressRequest] and nothing
 * else, so the set of keys that can appear in an outbound position is closed by
 * a type rather than by review. The token index, the words-per-minute setting,
 * the pipeline version and the structural fingerprint are not fields of it
 * (REQ-512).
 *
 * ## What this file never does
 *
 * It never compares two positions to pick a winner. `reader.activity-
 * convergence.v1` says the causal successor wins, later admission wins, and
 * percentage never selects — so a backward move is published exactly like a
 * forward one, and a remote position is consumed exactly as it arrived. There is
 * no `max`, no latest-wins and no percentage comparison anywhere in here, and
 * [RemoteReadingPosition] carries the server's own admission time and revision
 * so a host never needs to invent an ordering of its own.
 */
object PortableReadingPosition {

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
     * The portable position of [tokenIndex] in [content].
     *
     * Null for a book with no tokens: there is no fraction of nothing, and a
     * position stating 0 % of an empty book would be a claim about a book that
     * has not been read.
     *
     * The percentage is the **whole percent the reader is shown** — the same
     * `(fraction * 100).roundToInt()` as
     * [com.cedagova.fastreader.reader.ui.ReaderBookView], derived here from the
     * same fraction rather than restated — so what reader-web displays is what
     * FastReader displayed. The precise fraction still travels, in the locator's
     * `progression`, where it is the value a resume maps back through.
     *
     * The href is the spine path of the chapter the reader is in. An empty
     * chapter cannot be the one a token is in, so it never becomes an href
     * ([BookContent.chapterAt] matches on a half-open range that an empty
     * chapter's is not) — a book whose sections are all empty has no tokens at
     * all and is already null above.
     */
    fun of(content: BookContent, tokenIndex: Int): LocalReadingPosition? {
        if (content.isEmpty) return null
        val index = tokenIndex.coerceIn(0, content.tokens.lastIndex)
        val chapter = content.chapterAt(index)
        val fraction = content.progressFraction(index)
        return LocalReadingPosition(
            href = chapter?.spinePath,
            chapterTitle = chapter?.title,
            progression = fraction.toDouble().coerceIn(0.0, 1.0),
            percent = (fraction * 100).roundToInt().coerceIn(0, 100),
        )
    }

    /**
     * The token a remote position means in this parse of this book — the
     * "nearest word" of REQ-511.
     *
     * Three cases, in the order AD-25 fixes:
     *
     * 1. The href names a chapter this parse has: `progression × totalTokens`,
     *    **clamped into that chapter's own range**. The clamp is what makes the
     *    section authoritative and the fraction advisory: two clients that
     *    disagree slightly about how far through the book a chapter starts still
     *    land inside the right chapter.
     * 2. The href names a chapter this parse does not have — a different edition,
     *    a spine that was renamed: the chapter cannot be honoured, so the
     *    fraction alone decides. Falling back to a *wrong* chapter's start would
     *    be worse than the fraction, which is at least about this book.
     * 3. No href at all: the fraction alone, which is the whole of what the
     *    record said.
     *
     * A chapter named by the href but holding no tokens resolves to its start,
     * which is the first token after it — there is no word inside it to land on.
     */
    fun tokenIndexFor(content: BookContent, position: RemoteReadingPosition): Int {
        if (content.isEmpty) return 0
        val last = content.tokens.lastIndex
        val fraction = position.progression?.coerceIn(0.0, 1.0)
        val byFraction = fraction
            ?.let { (it * content.totalTokens).toInt() }
            ?.coerceIn(0, last)
        val chapter = position.href?.let { href -> content.chapters.firstOrNull { it.spinePath == href } }
            ?: return byFraction ?: 0
        if (chapter.isEmpty) return chapter.startTokenIndex.coerceIn(0, last)
        return (byFraction ?: chapter.startTokenIndex)
            .coerceIn(chapter.startTokenIndex, chapter.endTokenIndex - 1)
    }

    /**
     * The payload of a `reading_progress` upsert for [position].
     *
     * Built by serializing [PutReaderProgressRequest], so the key set is the
     * model's and a field cannot be added here by accident.
     */
    fun payloadFor(position: LocalReadingPosition): JsonObject {
        val body = PutReaderProgressRequest(
            progressPercent = position.percent.toDouble(),
            locator = locatorFor(position),
            chapterTitle = position.chapterTitle,
        )
        return wire.encodeToJsonElement(body).jsonObject
    }

    /** The portable locator object [position] publishes. */
    fun locatorFor(position: LocalReadingPosition): JsonObject {
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
    fun recordFor(
        resourceId: String,
        payload: JsonObject,
        knownBook: (String) -> Boolean,
    ): ProgressRecord {
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
     * Every field is read and none is computed. A payload that carries no
     * locator, or one whose locator is a shape this app does not recognise,
     * yields a position with a null href — which [tokenIndexFor] answers with
     * the fraction alone. That is the documented fallback, not an error: a
     * malformed locator must not cost the reader the percentage that came with
     * it.
     */
    fun positionOf(payload: JsonObject): RemoteReadingPosition {
        val locator = payload["locator"] as? JsonObject
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

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * The position this device is at, in the only terms it can state portably.
 *
 * [percent] is the whole percent the reader is shown; [progression] is the
 * precise fraction. Both describe the same place — the percent is what a person
 * reads on another client, the fraction is what a resume maps back through.
 */
data class LocalReadingPosition(
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
data class RemoteReadingPosition(
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
sealed interface ProgressRecord {

    /** The record is about a book this account holds, and states this position. */
    data class Recognized(val bookId: String, val position: RemoteReadingPosition) : ProgressRecord

    /** The record cannot be placed. Surfaced, never dropped quietly. */
    data class Unrecognized(
        val resourceId: String,
        val payloadBookId: String?,
        val reason: String,
    ) : ProgressRecord
}
