package com.cedagova.fastreader.reader

import com.cedagova.fastreader.content.BookContentResult
import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.ContentFixtures
import com.cedagova.fastreader.content.ContentPipelineVersion
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.epub.EpubFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * #81 annotated every word with a span and some with a breath class, and
     * neither moves a stored position: the same words still produce the same
     * token count in the same order with the same indices, so the pipeline
     * version stays at 1 and a position stored before the change resolves
     * exactly, not approximately. Bumping the version defensively would drop
     * every reader to the fallback position for no reason.
     */
    @Test
    fun `span and breath annotations do not move stored positions`() {
        assertEquals(1, ContentPipelineVersion.CURRENT)
        assertEquals(ContentPipelineVersion.CURRENT, book.pipelineVersion)
        val storedBeforeTheChange = position(book.bookDigest, 24, pipelineVersion = 1)

        assertEquals(24, storedBeforeTheChange.resolveIndex(book))
        assertFalse(storedBeforeTheChange.isApproximate(book))
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

    // --- Case 3, the content-change guard (AD-18, #62) ---
    //
    // Four states, because the guard has to fire in exactly one of them. It is
    // armed only when *both* sides know a fingerprint; either side missing means
    // "no guard", which is what keeps every position written before schema 7 —
    // and every book opened through the streaming archive — resuming as it always
    // did.

    @Test
    fun `a position whose fingerprint matches the opened file resumes`() {
        val opened = book.copy(structuralFingerprint = "zipdir1:aaaa")
        val stored = position(book.bookDigest, 24, book.pipelineVersion, fingerprint = "zipdir1:aaaa")

        assertEquals(24, stored.resolveIndex(opened))
        assertFalse(stored.isApproximate(opened))
    }

    @Test
    fun `a position whose fingerprint disagrees with the opened file restarts the book`() {
        val opened = book.copy(structuralFingerprint = "zipdir1:bbbb")
        val stored = position(book.bookDigest, 24, book.pipelineVersion, fingerprint = "zipdir1:aaaa")

        // The identity still matches — it is the catalog id on both sides (AD-8),
        // so this is exactly the case the digest comparison can no longer catch.
        assertEquals(book.bookDigest, opened.bookDigest)
        assertEquals(0, stored.resolveIndex(opened))
        assertFalse(stored.isApproximate(opened))
    }

    /** Every position written before the schema 7 migration. */
    @Test
    fun `a position stored without a fingerprint resumes against a file that has one`() {
        val opened = book.copy(structuralFingerprint = "zipdir1:bbbb")
        val stored = position(book.bookDigest, 24, book.pipelineVersion, fingerprint = null)

        assertEquals(24, stored.resolveIndex(opened))
    }

    /** The streaming fallback: the open read no central directory, so it knows nothing. */
    @Test
    fun `a position with a fingerprint resumes when the open produced none`() {
        val opened = book.copy(structuralFingerprint = null)
        val stored = position(book.bookDigest, 24, book.pipelineVersion, fingerprint = "zipdir1:aaaa")

        assertEquals(24, stored.resolveIndex(opened))
    }

    /**
     * The two halves of case 3 are independent: a changed file is refused even
     * when the tokenization rules also moved, rather than falling through to the
     * progress-fraction remap that case 2 would otherwise apply.
     */
    @Test
    fun `a changed file is refused rather than remapped onto progress`() {
        val opened = book.copy(structuralFingerprint = "zipdir1:bbbb")
        val stored = ReaderPosition(
            position = TokenPosition(book.bookDigest, tokenIndex = 3, pipelineVersion = book.pipelineVersion - 1),
            progressFraction = 0.5f,
            structuralFingerprint = "zipdir1:aaaa",
        )

        assertEquals(0, stored.resolveIndex(opened))
        assertFalse(stored.isApproximate(opened))
    }

    @Test
    fun `a session stores the fingerprint of the file it was reading`() {
        val opened = book.copy(structuralFingerprint = "zipdir1:aaaa")

        val position = ReaderSession(opened, index = 27).toPosition()

        assertEquals("zipdir1:aaaa", position.structuralFingerprint)
    }

    // --- The same thing, end to end through the real pipeline ---

    /**
     * The failure #62 exists to fix, reproduced at the seam where it happens: a
     * file replaced in place keeps its catalog id, so the two parses carry the
     * *same identity* and differ only in what the bytes say. Before the guard the
     * stored index resolved straight onto the new text.
     */
    @Test
    fun `a book edited under the same identity restarts, and the untouched one resumes`() {
        val original = ReaderFixtures.parse(
            EpubFixtures.validEpub(bodyText = "One word at a time, and then the next one."),
        )
        val edited = ReaderFixtures.parse(
            EpubFixtures.validEpub(bodyText = "One word at a tyme, and then the next one."),
        )
        assertEquals("the swap must not change identity", original.bookDigest, edited.bookDigest)

        val stored = ReaderSession(original, index = 5).toPosition()

        assertEquals("the changed file must restart at 0", 0, stored.resolveIndex(edited))
        assertEquals("the untouched file must resume", 5, stored.resolveIndex(original))
    }

    /**
     * A source with no seekable view reads no central directory, so it computes no
     * fingerprint and the guard simply does not apply. The reader resumes exactly
     * as it did before this existed — and, on the write side, the stored value is
     * left alone rather than cleared (see `LibraryRepositoryTest`).
     */
    @Test
    fun `a book opened through the streaming fallback resumes on its stored position`() {
        val bytes = EpubFixtures.validEpub(bodyText = "One word at a time, and then the next one.")
        val seekable = ReaderFixtures.parse(bytes)
        val streamed = runBlocking {
            val result = EpubContentPipeline(Dispatchers.Unconfined).parse(
                ContentFixtures.streamingSource(bytes),
                BookIdentity(ReaderFixtures.ENGLISH_NOVEL_ID),
            )
            (result as BookContentResult.Parsed).content
        }
        assertNull("the streaming open must produce no fingerprint", streamed.structuralFingerprint)

        val stored = ReaderSession(seekable, index = 5).toPosition()

        assertEquals(5, stored.resolveIndex(streamed))
    }

    private fun position(
        digest: String,
        tokenIndex: Int,
        pipelineVersion: Int,
        fingerprint: String? = null,
    ) = ReaderPosition(
        position = TokenPosition(digest, tokenIndex, pipelineVersion),
        progressFraction = 0f,
        structuralFingerprint = fingerprint,
    )
}
