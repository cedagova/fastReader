package com.cedagova.fastreader.account.library

import com.cedagova.reader.engine.content.BookContent
import com.cedagova.reader.library.sync.LocalReadingPosition
import com.cedagova.reader.library.sync.RemoteReadingPosition
import kotlin.math.roundToInt

/**
 * The one place FastReader's token positions and the account's portable
 * position meet (AD-25, REQ-511).
 *
 * The client-generic half — what a published payload is, which book a record is
 * about, what a canonical payload states — is `:reader-library`'s
 * [com.cedagova.reader.library.sync.PortableProgress] (#147). What stays here is
 * the one derivation only FastReader can make: **how a section maps to a word.**
 * Section plus fraction is honest for an RSVP stream and a paginated reader
 * alike, and exact word equivalence is promised by neither (assumption A2).
 *
 * Nothing about the local position leaves through here: [of] yields a
 * [LocalReadingPosition], whose payload `PortableProgress.payloadFor` builds
 * from the contract's own body type, so the token index and the reading speed
 * have no field to travel in (REQ-512).
 *
 * It never compares two positions to pick a winner: a remote position is
 * consumed exactly as it arrived (`reader.activity-convergence.v1`).
 */
object PortableReadingPosition {

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
}
