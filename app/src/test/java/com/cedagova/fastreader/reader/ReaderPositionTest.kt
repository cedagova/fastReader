package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.ContentPipelineVersion
import com.cedagova.fastreader.content.TokenPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes a stored index mean something later.
 *
 * The failure this guards against is silent: the same integer read back against
 * a stream produced by different rules, or against another book, still *looks*
 * like a position. It resolves to the wrong word, and nothing says so.
 */
class ReaderPositionTest {

    private val book = ReaderFixtures.englishNovel

    @Test
    fun `the stored index is used when the book and the rules both match`() {
        val stored = position(book.bookDigest, 24, book.pipelineVersion)

        assertEquals(24, stored.resolveIndex(book))
        assertFalse(stored.isApproximate(book))
    }

    @Test
    fun `an index past the end of a shortened book is clamped, not thrown`() {
        val stored = position(book.bookDigest, 10_000_000, book.pipelineVersion)

        assertEquals(book.tokens.lastIndex, stored.resolveIndex(book))
    }

    // AD-3: a pipeline change moves every index, so the index is not trusted.
    @Test
    fun `a position from older tokenization rules falls back to progress`() {
        val stored = ReaderPosition(
            position = TokenPosition(book.bookDigest, tokenIndex = 3, pipelineVersion = book.pipelineVersion - 1),
            progressFraction = 0.5f,
        )

        val resolved = stored.resolveIndex(book)

        assertTrue(stored.isApproximate(book))
        assertEquals(book.tokens.lastIndex / 2, resolved)
        // Not the stored index, which under the new rules is a different word.
        assertTrue("the stale index must not be used as-is", resolved != 3)
    }

    // AD-2: identity is the content digest, so another book's position is not this one's.
    @Test
    fun `a position taken in a different book is ignored`() {
        val stored = position("sha256:someone-elses-book", 24, book.pipelineVersion)

        assertEquals(0, stored.resolveIndex(book))
        assertFalse(stored.isApproximate(book))
    }

    @Test
    fun `a session reports the position it is actually at`() {
        val session = ReaderSession(book, index = 27).withWpm(400)

        val position = session.toPosition()

        assertEquals(book.bookDigest, position.position.bookDigest)
        assertEquals(27, position.position.tokenIndex)
        assertEquals(ContentPipelineVersion.CURRENT, position.position.pipelineVersion)
        assertEquals(400, position.wpm)
        assertEquals(book.progressFraction(27), position.progressFraction, 0f)
    }

    /** Round trip: what a session stores is what the same session restores. */
    @Test
    fun `a stored position reopens on the same word at the same speed`() {
        val stored = ReaderSession(book, index = 31).withWpm(700).toPosition()

        val reopened = ReaderSession(
            content = book,
            index = stored.resolveIndex(book),
            settings = com.cedagova.fastreader.timing.TimingSettings(wpm = stored.wpm),
        )

        assertEquals(31, reopened.index)
        assertEquals(700, reopened.settings.wpm)
        assertEquals(ReaderMode.PAUSED, reopened.mode)
    }

    private fun position(digest: String, tokenIndex: Int, pipelineVersion: Int) = ReaderPosition(
        position = TokenPosition(digest, tokenIndex, pipelineVersion),
        progressFraction = 0f,
    )
}
