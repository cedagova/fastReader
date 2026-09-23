package com.cedagova.reader.library.sync

import com.cedagova.reader.library.model.ReaderCapabilityReason
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
     * The row [payload] describes for a book's reading progress.
     *
     * A progress payload for a book the account has no row for is ignored — the
     * row arrives with the library item, and inventing one from a position would
     * put a titleless book on the shelf. Which book the payload is *about* is not
     * decided here: [PortableProgress.recordFor] owns that, and hands the
     * already-resolved [position] in (#120).
     *
     * Nothing is compared here. The position replaces whatever was stored, because
     * the backend decides who wins by admission order and a record that arrives is
     * the one it admitted — the engine has already dropped one whose revision is
     * not newer than the stored one (§7.3) — a position that moves the row *backwards*
     * is adopted exactly like one that moves it forwards
     * (`causal-progress-can-move-backward`).
     */
    fun readingProgress(
        existing: AccountBook?,
        payload: JsonObject,
        position: RemoteReadingPosition,
        revision: Long?,
        serverAdmittedAt: String?,
    ): AccountBook? {
        if (existing == null) return null
        return existing.copy(
            progressPercent = payload.double("progress_percent") ?: existing.progressPercent,
            progressUpdatedAt = if (payload.has("updated_at")) {
                payload.string("updated_at")
            } else {
                existing.progressUpdatedAt
            },
            remotePosition = remotePosition(
                position = position,
                existing = existing.remotePosition,
                revision = revision,
                serverAdmittedAt = serverAdmittedAt,
            ),
        )
    }

    /**
     * The stored remote position for [position], keeping the server's ordering.
     *
     * [revision] and [serverAdmittedAt] are the change's own and replace the
     * stored ones when the answer carries them; a list read that carries neither
     * leaves what is already recorded alone, exactly as a thinner library payload
     * leaves a row's title alone.
     */
    fun remotePosition(
        position: RemoteReadingPosition,
        existing: AccountRemotePosition?,
        revision: Long?,
        serverAdmittedAt: String?,
    ): AccountRemotePosition = AccountRemotePosition(
        href = position.href,
        chapterTitle = position.chapterTitle,
        progression = position.progression,
        percent = position.percent,
        updatedAt = position.updatedAt ?: existing?.updatedAt,
        revision = revision ?: existing?.revision ?: 0,
        serverAdmittedAt = serverAdmittedAt ?: existing?.serverAdmittedAt,
    )

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
fun ReaderLibraryStatus.wireName(): String =
    ReaderLibraryStatus.serializer().descriptor.getElementName(ordinal)

fun ReaderCoverStatus.wireName(): String =
    ReaderCoverStatus.serializer().descriptor.getElementName(ordinal)

fun ReaderSyncRejectionCode.wireName(): String =
    ReaderSyncRejectionCode.serializer().descriptor.getElementName(ordinal)

/** The reason a capability document states, as the shelf quotes it. */
fun ReaderCapabilityReason.wireName(): String =
    ReaderCapabilityReason.serializer().descriptor.getElementName(ordinal)
