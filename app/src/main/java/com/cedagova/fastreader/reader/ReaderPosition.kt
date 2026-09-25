package com.cedagova.fastreader.reader

import com.cedagova.reader.engine.content.BookContent
import com.cedagova.reader.engine.content.TokenPosition
import com.cedagova.reader.engine.timing.RsvpTiming
import kotlinx.coroutines.flow.StateFlow

/**
 * The durable slice of a reading session (REQ-016): where the reader is, and how
 * fast they were going.
 *
 * Deliberately not the whole [ReaderSession]. Playback mode is not persisted —
 * a book always reopens paused, showing its context view (REQ-009/REQ-010) — and
 * neither is the timing ramp, which REQ-013 restarts on every resume anyway.
 */
data class ReaderPosition(
    val position: TokenPosition,
    /** Share of the book already shown; the library's "% read", and the fallback below. */
    val progressFraction: Float,
    val wpm: Int = RsvpTiming.DEFAULT_WPM,
    /**
     * The structure of the file this position was taken in (AD-18), or null when
     * that was not known — see [resolveIndex]'s case 3.
     */
    val structuralFingerprint: String? = null,
) {

    /**
     * The token this position means *in this parse of this book*.
     *
     * Three cases, and only the first is the ordinary one:
     *
     * 1. Same book, same tokenization rules — the stored index, clamped.
     * 2. Same book, different [TokenPosition.pipelineVersion] — the index counts
     *    a stream that no longer exists, so it is not used. The progress fraction
     *    still describes the same *place in the book*, so it is remapped onto the
     *    new stream: approximate, but a paragraph or two out beats resuming
     *    somewhere arbitrary, and it never silently reports a wrong exact word.
     * 3. Not this content — the position is ignored outright and the book opens at
     *    0. Two different things land here, and they fail for different reasons:
     *    a different book's digest (AD-2), which is a caller handing a position to
     *    the wrong book; and the same book's *bytes having changed*, which is the
     *    guard [structuralFingerprint] exists for.
     *
     * ## What case 3 catches, and what it does not
     *
     * The digest half stopped detecting a changed file in v1.1.0. Until then the
     * reader hashed what it read, so the comparison put *what was stored* against
     * *what was just read off disk*. Identity is now an input (AD-8): for a
     * library book both sides are the catalog id, so that half can only ever catch
     * the caller mistake, never a swapped file.
     *
     * [structuralFingerprint] is the second stored signal that restores the rest
     * (AD-18). It is a digest of the archive directory's per-entry names,
     * uncompressed sizes and CRC-32 values, taken from the read the open already
     * performs, so nothing re-reads the file and REQ-110 is untouched. When the
     * stored one and the one just computed disagree, the file is not the file this
     * position was taken in and the book restarts at 0.
     *
     * Either side being null is **no guard, not a mismatch** — the position
     * resumes exactly as it did before this existed:
     *
     * - the stored side is null for every position written before the schema 7
     *   migration, and for one written by an open that produced no fingerprint;
     * - the computed side is null when the open fell back to the streaming
     *   archive, which reads no central directory (see
     *   `com.cedagova.reader.engine.epub.EpubArchive.structuralFingerprint`).
     *
     * It is a change detector, not a tamper check: two files whose entries have
     * identical names, sizes and CRC-32s count as the same content, so the same
     * book re-downloaded or re-copied resumes rather than restarting.
     */
    fun resolveIndex(content: BookContent): Int {
        if (content.isEmpty) return 0
        val last = content.tokens.lastIndex
        if (!belongsTo(content)) return 0
        if (position.pipelineVersion != content.pipelineVersion) {
            return (progressFraction.coerceIn(0f, 1f) * last).toInt().coerceIn(0, last)
        }
        return position.tokenIndex.coerceIn(0, last)
    }

    /** True when [resolveIndex] had to fall back rather than use the stored index. */
    fun isApproximate(content: BookContent): Boolean =
        belongsTo(content) && position.pipelineVersion != content.pipelineVersion

    /**
     * Case 3, as one predicate: the same book, and — when both sides know it — the
     * same bytes of it.
     */
    private fun belongsTo(content: BookContent): Boolean {
        if (position.bookDigest != content.bookDigest) return false
        val stored = structuralFingerprint ?: return true
        val opened = content.structuralFingerprint ?: return true
        return stored == opened
    }
}

/** The position a session is at right now, ready to be stored. */
fun ReaderSession.toPosition(): ReaderPosition = ReaderPosition(
    position = content.positionAt(index),
    progressFraction = progressFraction,
    wpm = settings.wpm,
    // Null when this open produced none. Storage must then keep whatever
    // fingerprint is already recorded rather than clearing it (AD-18): a book
    // only ever gains this protection.
    structuralFingerprint = content.structuralFingerprint,
)

/**
 * How the reader reaches durable storage, so the ViewModel needs neither the
 * catalog's API nor its types.
 *
 * [record] is called on every token change and must therefore be cheap and
 * non-blocking; [flush] is called for everything else and makes the last recorded
 * position durable now.
 */
interface ReaderPositions {

    /** The stored position for a book, or null when it has never been read. */
    fun restore(bookId: String): ReaderPosition?

    /** Notes a position. Called per word: no I/O on this path. */
    fun record(bookId: String, position: ReaderPosition)

    /** Makes the last recorded position durable — pause, jump, background, close. */
    fun flush()

    /**
     * Offers the portable position of a non-word event to whatever publishes it
     * (REQ-511, AD-25).
     *
     * Called from exactly the moments [flush] is, and never from [record]: the
     * per-word throttle is the one path that must stay free of anything but an
     * in-memory store, and a position published sixteen times a second would be
     * a mutation stream rather than a reading position.
     *
     * Whether anything is actually sent is not the reader's question. A device
     * book, a signed-out app and a position that says nothing new all end here
     * and go no further; the reader's part is to say where it is, at the moments
     * it is worth saying.
     */
    fun publishPortable(bookId: String, content: BookContent, tokenIndex: Int)

    /**
     * The offer to resume from the place another client left in this book, or
     * null when there is none to make (REQ-511).
     *
     * The mirror of [publishPortable] and gated the same way: a device book, a
     * signed-out app and a book the account holds no position for all end here
     * and produce nothing. [tokenIndex] is where the reader is *now*, which is
     * what makes the difference between a question worth asking and one that
     * would offer to move somebody to where they already are.
     *
     * Asked rather than pushed, and asked again whenever the account's rows
     * change: a position from another device arrives on an ordinary foreground
     * sync (REQ-502), which can land while the book is already open.
     */
    fun remoteOffer(bookId: String, content: BookContent, tokenIndex: Int): ResumeOffer?

    /** Non-null while storage is refusing writes, so a lost position is visible. */
    val failure: StateFlow<String?>
}
