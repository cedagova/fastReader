package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.model.ReaderCoverStatus
import com.cedagova.reader.library.model.ReaderLibraryStatus
import com.cedagova.reader.library.model.ReaderSyncRejectionCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Adopting the backend's canonical payload (AD-22).
 *
 * The pinned contract types a canonical payload as a free-form JSON object —
 * it fixes no per-resource shape — so this reads the keys the library
 * documents do use and *keeps what it already has* for every key an answer
 * does not carry. That is what adoption has to mean when the shape is not
 * pinned: a thinner payload than expected must never blank a row's title, and
 * a key the backend adds later must never be an error. Nothing here computes
 * a value, merges two versions, or decides anything; the only local input is
 * the row that was already stored.
 */
internal object AccountCanonicalPayload {

    /**
     * The row [payload] describes for the library item [resourceId], starting
     * from [existing] (null when the account has no row for it yet).
     *
     * [revision] is the server's revision for the admitted or streamed change
     * and always replaces the stored one — it is the base revision the next
     * mutation for this book quotes.
     */
    fun libraryItem(
        existing: AccountBook?,
        resourceId: String,
        payload: JsonObject,
        revision: Long?,
    ): AccountBook {
        val book = payload.obj("book")
        val assets = (payload["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val asset = assets.firstOrNull { it.string("checksum") != null } ?: assets.firstOrNull()
        val base = existing ?: AccountBook(bookId = resourceId)
        return base.copy(
            bookId = book?.string("id") ?: payload.string("book_id") ?: resourceId,
            title = book?.string("title") ?: payload.string("title") ?: base.title,
            author = if (book?.has("author") == true) {
                book.string("author")
            } else if (payload.has("author")) {
                payload.string("author")
            } else {
                base.author
            },
            language = if (book?.has("language") == true) {
                book.string("language")
            } else if (payload.has("language")) {
                payload.string("language")
            } else {
                base.language
            },
            contentSha256 = asset?.string("checksum") ?: base.contentSha256,
            assetId = asset?.string("asset_id") ?: base.assetId,
            status = libraryStatus(payload.string("status")) ?: base.status,
            coverStatus = coverStatus(payload.string("cover_status")) ?: base.coverStatus,
            lastOpenedAt = if (payload.has("last_opened_at")) {
                payload.string("last_opened_at")
            } else {
                base.lastOpenedAt
            },
            revision = revision ?: base.revision,
        )
    }

    /**
     * The row [payload] describes for the reading progress of [resourceId].
     *
     * Only the percentage and its time are read: the portable locator is
     * carried by `:reader-library` untouched and mapped by LEAF821, not here.
     * A progress payload for a book the account has no row for is ignored —
     * the row arrives with the library item, and inventing one from a position
     * would put a titleless book on the shelf.
     */
    fun readingProgress(existing: AccountBook?, payload: JsonObject): AccountBook? {
        if (existing == null) return null
        return existing.copy(
            progressPercent = payload.double("progress_percent") ?: existing.progressPercent,
            progressUpdatedAt = if (payload.has("updated_at")) {
                payload.string("updated_at")
            } else {
                existing.progressUpdatedAt
            },
        )
    }

    /** The revision a canonical payload states, when it states one. */
    fun revision(payload: JsonObject): Long? = (payload["revision"] as? JsonPrimitive)?.longOrNull

    private fun libraryStatus(wire: String?): ReaderLibraryStatus? =
        wire?.let { value -> ReaderLibraryStatus.entries.firstOrNull { it.wireName() == value } }

    private fun coverStatus(wire: String?): ReaderCoverStatus? =
        wire?.let { value -> ReaderCoverStatus.entries.firstOrNull { it.wireName() == value } }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.has(key: String): Boolean = containsKey(key)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * The wire name the pinned contract gives an enum member, read from the
 * serializer's own descriptor rather than restated here — so a `@SerialName`
 * that changes in `:reader-library` changes this too, instead of drifting.
 */
internal fun ReaderLibraryStatus.wireName(): String =
    ReaderLibraryStatus.serializer().descriptor.getElementName(ordinal)

internal fun ReaderCoverStatus.wireName(): String =
    ReaderCoverStatus.serializer().descriptor.getElementName(ordinal)

internal fun ReaderSyncRejectionCode.wireName(): String =
    ReaderSyncRejectionCode.serializer().descriptor.getElementName(ordinal)
