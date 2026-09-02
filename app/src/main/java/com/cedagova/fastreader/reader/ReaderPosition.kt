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
     *    content at all and is ignored outright.
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
