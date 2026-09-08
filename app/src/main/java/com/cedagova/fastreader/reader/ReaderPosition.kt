package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.timing.RsvpTiming
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
     * 3. A different book's digest (AD-2) — the position does not belong to this
     *    content at all and is ignored outright. This catches a position handed
     *    to the wrong book, which is a caller mistake.
     *
     * ## What case 3 stopped catching in v1.1.0
     *
     * Until v1.1.0 the reader hashed the file it was reading, so this comparison
     * put *what was stored* against *what was just read off disk* and a file
     * replaced in place at the same URI failed it. Identity is now an input
     * (AD-8): for a library book both sides are the catalog id, so the
     * comparison cannot fail and this is no longer a content-change guard.
     *
     * A file swapped under an unchanged catalog entry is now caught by the
     * rescan fingerprint (size and last-modified on [com.cedagova.fastreader
     * .library.BookSource]) re-keying the book, not here — so between the swap
     * and the next rescan the reader will resume at the stored index in the new
     * text. Restoring a real guard needs a second stored signal, which is a
     * catalog schema change and deliberately not part of the leaf that made this
     * one stop firing.
     */
    fun resolveIndex(content: BookContent): Int {
        if (content.isEmpty) return 0
        val last = content.tokens.lastIndex
        if (position.bookDigest != content.bookDigest) return 0
        if (position.pipelineVersion != content.pipelineVersion) {
            return (progressFraction.coerceIn(0f, 1f) * last).toInt().coerceIn(0, last)
        }
        return position.tokenIndex.coerceIn(0, last)
    }

    /** True when [resolveIndex] had to fall back rather than use the stored index. */
    fun isApproximate(content: BookContent): Boolean =
        position.bookDigest == content.bookDigest &&
            position.pipelineVersion != content.pipelineVersion
}

/** The position a session is at right now, ready to be stored. */
fun ReaderSession.toPosition(): ReaderPosition = ReaderPosition(
    position = content.positionAt(index),
    progressFraction = progressFraction,
    wpm = settings.wpm,
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

    /** Non-null while storage is refusing writes, so a lost position is visible. */
    val failure: StateFlow<String?>
}
